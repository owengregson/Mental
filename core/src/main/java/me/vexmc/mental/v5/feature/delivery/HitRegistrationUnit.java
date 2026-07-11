package me.vexmc.mental.v5.feature.delivery;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import me.vexmc.mental.api.event.AsyncHitRegisterEvent;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.debug.DebugLog;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.math.HurtYaw;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.math.MeasuredReality;
import me.vexmc.mental.kernel.math.PocketServo;
import me.vexmc.mental.kernel.math.PocketServoConfig;
import me.vexmc.mental.kernel.math.PredictorInputs;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitGeometry;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.ResetModel;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.ResistancePolicy;
import me.vexmc.mental.kernel.wire.CompensationQuery;
import me.vexmc.mental.kernel.wire.CpsLimiter;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.kernel.wire.PositionRing;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.coexist.AnticheatPolicy;
import me.vexmc.mental.v5.feature.combo.ComboPredictor;
import me.vexmc.mental.v5.feature.combo.ComboReachHandicap;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.config.settings.CompensationSettings;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.config.settings.ReachHandicapSettings;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.rim.BurstSender;
import me.vexmc.mental.v5.rim.ConnectionDomains;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * The netty fast path (spec §3.3; the retired {@code HitPacketListener}/{@code HitApplier}
 * flow re-expressed on the rim/kernel seam). On an ATTACK packet the attacker's
 * netty thread validates against tick-frozen views, cancels the vanilla packet,
 * optionally pre-sends the victim burst, and schedules the authoritative damage on
 * the victim's owning region thread — where the knockback family re-resolves and
 * ships the era vector.
 *
 * <p>Netty discipline (Folia): the victim resolves through the frozen
 * entityId→UUID index and {@code Bukkit.getPlayer}; every state read is from the
 * published {@link PlayerView} or the owning-thread {@link PositionRing} sample,
 * never a live entity. Player positions for the pre-send direction come from the
 * ring (the owning-thread sampler wrote them); the view lacks horizontal position
 * by design, so the ring is the off-region-safe source. A hit whose pre-send is
 * suppressed (anticheat, legacy resistance roll, missing views, the
 * feedback window) still ships hurt and still schedules the authoritative pass; the
 * era vector is computed and shipped there.</p>
 */
public final class HitRegistrationUnit implements FeatureUnit {

    private static final double VANILLA_REACH = 6.0;
    private static final double REACH_LENIENCY = 1.0;
    private static final double MAX_REACH_SQUARED =
            (VANILLA_REACH + REACH_LENIENCY) * (VANILLA_REACH + REACH_LENIENCY);

    private final SessionService sessions;
    private final ConnectionDomains domains;
    private final LatencyModel latency;
    private final AnticheatPolicy anticheat;
    private final AtomicBoolean wtapConsultWire;
    private final TickClock clock;
    private final Supplier<Snapshot> snapshot;
    private final Scheduling scheduling;
    private final boolean folia;
    private final boolean modernProtocol;
    private final me.vexmc.mental.v5.delivery.HitIds ids;
    private final me.vexmc.mental.v5.VelocityValve valve;
    private final me.vexmc.mental.v5.feature.damage.DamageShaper shaper;
    private final me.vexmc.mental.v5.feature.damage.ToolWear toolWear;
    private final DebugLog.Scoped debug;

    private final CpsLimiter cps = new CpsLimiter();
    private final FeedbackGate feedbackGate = new FeedbackGate();

    public HitRegistrationUnit(
            SessionService sessions, ConnectionDomains domains, LatencyModel latency,
            AnticheatPolicy anticheat, AtomicBoolean wtapConsultWire, TickClock clock,
            Supplier<Snapshot> snapshot, Scheduling scheduling,
            me.vexmc.mental.v5.VelocityValve valve, me.vexmc.mental.v5.delivery.HitIds ids,
            me.vexmc.mental.v5.feature.damage.DamageShaper shaper,
            me.vexmc.mental.v5.feature.damage.ToolWear toolWear,
            boolean folia, boolean modernProtocol, DebugLog.Scoped debug) {
        this.sessions = sessions;
        this.domains = domains;
        this.latency = latency;
        this.anticheat = anticheat;
        this.wtapConsultWire = wtapConsultWire;
        this.clock = clock;
        this.snapshot = snapshot;
        this.scheduling = scheduling;
        this.valve = valve;
        this.ids = ids;
        this.shaper = shaper;
        this.toolWear = toolWear;
        this.folia = folia;
        this.modernProtocol = modernProtocol;
        this.debug = debug;
    }

    @Override
    public Feature descriptor() {
        return Feature.HIT_REGISTRATION;
    }

    /**
     * The attacker's sprint-reset model for the input-driven dynamic chase, read
     * through the NON-creating {@link ConnectionDomains#peek} (a packetless attacker
     * has no domain, and {@code domainFor} would poison it) — {@link
     * ResetModel#UNKNOWN} then, so the servo keeps its measured-ring fallback.
     */
    private ResetModel attackerResetModel(UUID attackerId, TickStamp now) {
        ConnectionDomains.Domain domain = domains.peek(attackerId);
        return domain != null ? domain.resetModel().modelAt(now) : ResetModel.UNKNOWN;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        BurstSender senders = new BurstSender(modernProtocol);
        scope.packets(new Listener(senders));
        scope.task(() -> {
            // Per-player forget for the CPS/feedback state is registered as a
            // session forget hook by the plugin; the packet listener alone owns
            // no per-session timer, so the scope's close is the listener removal.
            return () -> {};
        });
    }

    /** Per-player teardown (registered by the plugin as a session forget hook). */
    public void forget(UUID id) {
        cps.forget(id);
        feedbackGate.forget(id);
    }

    @SuppressWarnings("unchecked")
    private HitRegSettings settings() {
        return snapshot.get().settings(
                (me.vexmc.mental.v5.feature.SettingsKey<HitRegSettings>) Feature.HIT_REGISTRATION.settingsKey());
    }

    /**
     * The pocket-servo config for a pre-send from {@code attackerId} to the victim
     * whose frozen {@code victimView} is given (combo-hold §3.2). Active only when
     * the module is on AND this attacker holds the victim's active combo — the same
     * gate the region path uses, off the one frozen truth, so an adopted pre-send
     * and a recompute agree. Otherwise {@link PocketServoConfig#INACTIVE}. The config
     * carries the victim's EFFECTIVE answer reach (the era reach folded with the
     * combo reach handicap when it is live) so the two combo submodules compose — the
     * same fold the region path applies.
     */
    @SuppressWarnings("unchecked")
    private PocketServoConfig comboServoFor(PlayerView victimView, UUID attackerId) {
        if (victimView == null || attackerId == null || !snapshot.get().enabled(Feature.COMBO_HOLD)) {
            return PocketServoConfig.INACTIVE;
        }
        if (!attackerId.equals(victimView.comboAttackerId())) {
            return PocketServoConfig.INACTIVE;
        }
        ComboSettings settings = snapshot.get().settings(
                (me.vexmc.mental.v5.feature.SettingsKey<ComboSettings>) Feature.COMBO_HOLD.settingsKey());
        return settings.servo(effectiveVictimReach(settings));
    }

    /**
     * The victim's EFFECTIVE answer reach for the answer-denial boundary target —
     * the era reach shortened by the combo reach handicap when it is enabled AND
     * enforceable (1.20.5+ attribute lever). The handicap is live for exactly the
     * combo's duration, which is exactly when the servo is active, so lowering
     * {@code R_v} drops the deny boundary and opens the keepable pocket. Netty-safe:
     * one snapshot read and the class-load-constant lever probe. {@code R_a} (the
     * attacker) is never handicapped.
     */
    @SuppressWarnings("unchecked")
    private double effectiveVictimReach(ComboSettings settings) {
        double base = settings.victimReach();
        if (snapshot.get().enabled(Feature.COMBO_REACH_HANDICAP) && ComboReachHandicap.leverSupported()) {
            ReachHandicapSettings handicap = snapshot.get().settings(
                    (SettingsKey<ReachHandicapSettings>) Feature.COMBO_REACH_HANDICAP.settingsKey());
            return base * handicap.scale();
        }
        return base;
    }

    /* ---------------------------- the netty listener ---------------------------- */

    private final class Listener extends PacketListenerAbstract {

        private final BurstSender senders;

        Listener(BurstSender senders) {
            super(PacketListenerPriority.NORMAL);
            this.senders = senders;
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            if (event.isCancelled()
                    || event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
                return;
            }
            try {
                register(event);
            } catch (Throwable failure) {
                // Never let the fast path break the inbound pipeline; on any
                // fault the packet flows through to vanilla.
            }
        }

        private void register(PacketReceiveEvent event) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() != InteractAction.ATTACK) {
                return;
            }
            User user = event.getUser();
            UUID attackerId = user == null ? null : user.getUUID();
            if (attackerId == null) {
                return;
            }
            HitRegSettings settings = settings();
            long now = System.currentTimeMillis();
            if (!cps.tryAcquire(attackerId, settings.maxCps(), now)) {
                event.setCancelled(true);
                return;
            }
            int targetId = packet.getEntityId();
            if (targetId < 0) {
                return;
            }

            UUID victimId = sessions.playerIdByEntityId(targetId);
            Player playerVictim = null;
            Damageable damageable;
            if (victimId != null) {
                playerVictim = Bukkit.getPlayer(victimId);
                if (playerVictim == null) {
                    return;
                }
                damageable = playerVictim;
                PlayerView victimView = sessions.viewOf(victimId);
                if (victimView == null || !victimView.fresh(clock.current())
                        || victimView.creative() || !victimView.pvpAllowed()) {
                    return;
                }
            } else {
                // A non-player target can't be resolved off-region on Folia, so
                // the hit falls through to vanilla there (mob combat / stands keep
                // working); on Paper the live scan is tolerated.
                if (folia || !(event.getPlayer() instanceof Player attacker)) {
                    return;
                }
                Entity target = SpigotConversionUtil.getEntityById(attacker.getWorld(), targetId);
                if (!(target instanceof Damageable resolved) || resolved.isDead()) {
                    return;
                }
                damageable = resolved;
            }

            if (playerVictim != null && !passesReach(attackerId, victimId, settings)) {
                event.setCancelled(true);
                return;
            }

            Player bukkitAttacker = Bukkit.getPlayer(attackerId);
            if (bukkitAttacker == null) {
                return;
            }
            AsyncHitRegisterEvent api = new AsyncHitRegisterEvent(bukkitAttacker, damageable);
            Bukkit.getPluginManager().callEvent(api);
            if (api.isCancelled()) {
                event.setCancelled(true);
                return;
            }
            if (!settings.fastPath()) {
                return; // rate-limit + async hook only; leave the packet for vanilla
            }
            event.setCancelled(true);

            HitTransaction tx = plan(attackerId, victimId, playerVictim, settings, now);
            dispatch(tx, attackerId, victimId, playerVictim, damageable, targetId, settings);
        }

        /**
         * Builds the Melee transaction and, when the velocity pre-send is
         * eligible, ships the burst and submits the pre-delivered vector to the
         * victim's desk. A suppressed pre-send leaves the transaction REGISTERED
         * for the authoritative pass to compute.
         */
        private HitTransaction plan(
                UUID attackerId, UUID victimId, Player playerVictim,
                HitRegSettings settings, long now) {
            SprintVerdict verdict = sprintVerdict(attackerId);
            PlayerView attackerView = sessions.viewOf(attackerId);
            PlayerView victimView = victimId == null ? null : sessions.viewOf(victimId);
            boolean victimHasWire = playerVictim != null
                    && PacketEvents.getAPI().getPlayerManager().getUser(playerVictim) != null;
            Double compensationY = compensationFor(victimId, victimView);

            HitContext context = new HitContext(
                    ids.next(), new HitSource.Melee(), attackerId, victimId,
                    verdict, victimHasWire, compensationY, clock.current(), registrationYaw(attackerId));
            HitTransaction tx = new HitTransaction(context);

            // The pre-send only exists for player victims with published views —
            // each absence stamps its own F9 pre-send disposition before returning.
            if (playerVictim == null || victimId == null || attackerView == null || victimView == null) {
                tx.presend("no-view");
                return tx;
            }
            if (!settings.preSendFeedback()) {
                tx.presend("off");
                return tx;
            }
            if (victimView.damageImmune()) {
                tx.presend("suppressed:frozen-immune");
                return tx;
            }
            tx.profileName(victimView.profile().name());

            KnockbackProfile profile = victimView.profile();
            KnockbackVector vector = null;
            String suppressed = suppressorFor(profile, victimView);
            if (suppressed != null) {
                tx.presend("suppressed:" + suppressed); // suppressed:anticheat / suppressed:resistance-roll
            }
            if (suppressed == null) {
                // The pocket servo (combo-hold §3.2): active only when THIS attacker
                // holds the victim's active combo (read from the victim's frozen view
                // — the SAME truth the region path reads, so the adopted pre-send and a
                // recompute would agree). INACTIVE ⇒ σ = 1.0 (byte-identical).
                PocketServoConfig servo = comboServoFor(victimView, attackerId);
                // The precision predictor inputs (combo-hold §3.2b) — frozen views +
                // ring + latency, built only when the servo is active; zero cost off.
                // The attacker position and tick are hoisted so the build and the
                // post-hit window commit below share one truth.
                double servoAttackerX = positionX(attackerId);
                double servoAttackerZ = positionZ(attackerId);
                TickStamp servoNow = clock.current();
                PredictorInputs inputs = servo.active()
                        ? ComboPredictor.build(attackerId, victimId,
                                servoAttackerX, servoAttackerZ,
                                positionX(victimId), positionZ(victimId),
                                victimView, attackerView, sessions.positions(), latency, servoNow,
                                attackerResetModel(attackerId, servoNow))
                        : PredictorInputs.degraded(
                                victimView.grounded(), victimView.slipperiness(), victimView.moveSpeedAttr());
                EntityState preAttacker = preAttackerState(attackerId, attackerView, verdict);
                EntityState preVictim = preVictimState(victimId, victimView);
                // The exact base() geometry this hit consumed (F9 journal attribution).
                tx.geometry(new HitGeometry(preAttacker.x(), preAttacker.z(), preAttacker.yaw(),
                        preVictim.x(), preVictim.z()));
                boolean freshSprint = verdict.fresh() != null && verdict.fresh() && verdict.sprinting();
                KnockbackEngine.Paced paced = KnockbackEngine.computePaced(
                        preAttacker, preVictim, profile, compensationY, ThreadLocalRandom.current(),
                        freshSprint, servo, inputs);
                vector = paced.vector();
                tx.paceFactor(paced.paceFactor()); // journal the factors the pre-send applied (D-6)
                tx.comboFactor(paced.comboFactor());
                // Commit the post-hit chase window on EVERY active servo hit
                // (servo-lab 2.4.5) — load-bearing solve state, never gated behind
                // the sink or the target mode. A suppressed velocity later in this
                // method leaves the region recompute to re-solve this hit; its own
                // commit lands under the minimum window gap, so no double-advance.
                if (servo.active()) {
                    ComboPredictor.rememberWindow(victimId, servoAttackerX, servoAttackerZ, servoNow, inputs);
                }
                // The debug sink alone rides the debug gate: the answer-denial
                // boundary target is computed fresh from geometry each hit, with no
                // cross-hit memory to commit, so nothing gameplay-shaping runs
                // debug-off. The post-hit chase window commit above is the only
                // load-bearing solve state and is never gated behind the sink.
                if (servo.active() && debug.active()) {
                    PocketServo.Solution solution = KnockbackEngine.explainServo(
                            preAttacker, preVictim, profile, compensationY, freshSprint, servo, inputs);
                    debug.log(() -> ComboPredictor.debugLine(victimId, attackerId, inputs, solution));
                }
            }

            // B4: the pacing gate paces the VELOCITY component ONLY. A hurt-only
            // burst (velocity suppressed — a LEGACY resistance roll or
            // an anticheat force-safe posture) must NEVER debit the budget, or it
            // starves a later eligible velocity pre-send. Compute the velocity
            // eligibility first, then consult the gate only when a velocity would
            // actually ship — i.e. the FeedbackPlan includes the VELOCITY component.
            long minInterval = settings.feedbackMinIntervalMillis() >= 0
                    ? settings.feedbackMinIntervalMillis()
                    : Math.max(0, victimView.maxNoDamageTicks() / 2 - 1) * 50L;
            boolean velocityShips = admitVelocityPreSend(
                    feedbackGate, victimId, victimHasWire, vector != null, now, minInterval);
            // The F9 pre-send disposition: wire/pinned when the velocity ships, paced-out
            // when an eligible velocity lost this window. commitPreSendState overwrites this
            // with "unsendable-downgrade" if the wire then refuses the burst (F2).
            if (velocityShips) {
                tx.presend(victimHasWire ? "wire" : "pinned");
            } else if (vector != null) {
                tx.presend("paced-out");
            }

            KnockbackVector shipped = velocityShips ? vector : null; // TRACKER (full stamp); TRACKER_DECAYED handled by the desk record

            float hurtYaw = HurtYaw.hurtYaw(
                    attackerView == null ? 0 : positionX(attackerId),
                    attackerView == null ? 0 : positionZ(attackerId),
                    positionX(victimId), positionZ(victimId),
                    lastYaw(victimId));
            // The wire burst ships whenever the victim has a wire and the hit is
            // NOT an eligible-but-gated velocity: the velocity when it passed the
            // gate (shipped != null), else a hurt-only burst — which is gated by
            // nothing. An eligible velocity paced out this window skips the wire
            // (the authoritative pass delivers it), preserving the pacing.
            boolean shipBurst = victimHasWire && (vector == null || velocityShips);
            // The burst ships FIRST and the transaction state commits off its
            // Outcome — BurstSender's contract: an UNSENDABLE burst (user-null
            // race, mid-ship throw) must be PINNED, never accounted
            // wire-delivered. Committing PRE_SENT ahead of the ship let a failed
            // burst arm the valve, which then ate the authoritative
            // ENTITY_VELOCITY — the victim's only copy (F2).
            BurstSender.Outcome outcome = null;
            if (shipBurst) {
                outcome = senders.ship(PacketEvents.getAPI().getPlayerManager().getUser(playerVictim),
                        victimView.entityId(), shipped, hurtYaw, settings.bundleFeedback());
            }
            commitPreSendState(tx, shipped, velocityShips, victimHasWire, outcome);
            if (tx.state() == HitTransaction.State.PRE_SENT || tx.state() == HitTransaction.State.PINNED) {
                sessions.sessionFor(victimId).desk().submitFromWire(tx);
            }
            return tx;
        }

        private void dispatch(
                HitTransaction tx, UUID attackerId, UUID victimId, Player playerVictim,
                Damageable damageable, int targetId, HitRegSettings settings) {
            if (playerVictim != null) {
                UUID victim = victimId != null ? victimId : playerVictim.getUniqueId();
                scheduling.runOn(playerVictim,
                        () -> applyPlayer(attackerId, victim, tx),
                        () -> retract(victim, tx));
            } else {
                scheduling.runOn(damageable,
                        () -> applyNonPlayer(attackerId, targetId, tx),
                        () -> {});
            }
        }

        private EntityState preAttackerState(UUID attackerId, PlayerView view, SprintVerdict verdict) {
            PositionRing.Sample sample = sessions.positions().latest(attackerId);
            return HitRegistrationUnit.preAttackerState(
                    sample != null ? sample.x() : 0,
                    sample != null ? sample.y() : 0,
                    sample != null ? sample.z() : 0,
                    lastYaw(attackerId), view, verdict);
        }

        private EntityState preVictimState(UUID victimId, PlayerView view) {
            PositionRing.Sample sample = sessions.positions().latest(victimId);
            double x = sample != null ? sample.x() : 0;
            double y = sample != null ? sample.y() : 0;
            double z = sample != null ? sample.z() : 0;
            // The measured-reality clamp on the vertical residual — the pre-send twin
            // of EntityStates.captureVictim, reading the SAME clamp off the SAME frozen
            // view so the pre-sent knock and the region recompute agree (the ledger's
            // runaway free-fall past the victim's real hover is a model bug, not era
            // truth; 2026-07-10-downward-kb-and-stacking-diagnoses.md report 1). NaN
            // measured (packetless first tick) ⇒ strict no-op. Horizontal untouched.
            double vy = MeasuredReality.clampVy(view.motion().vy(), view.measuredVy());
            return new EntityState(x, y, z, 0,
                    view.motion().vx(), vy, view.motion().vz(),
                    view.grounded(), false, 0, view.knockbackResistance());
        }

        private double positionX(UUID id) {
            PositionRing.Sample sample = sessions.positions().latest(id);
            return sample == null ? 0 : sample.x();
        }

        private double positionZ(UUID id) {
            PositionRing.Sample sample = sessions.positions().latest(id);
            return sample == null ? 0 : sample.z();
        }

        /**
         * The connection's last movement-packet yaw, resolved through a NON-creating
         * {@link ConnectionDomains#peek}. A packetless party (synthetic player /
         * in-process bot) has no domain and falls back to 0 — never {@code domainFor},
         * which would spuriously create the domain and poison the ledger feed (the
         * 2.4.4 domain-poisoning bug).
         */
        private float lastYaw(UUID id) {
            ConnectionDomains.Domain domain = domains.peek(id);
            return domain == null ? 0f : domain.lastYaw();
        }

        /**
         * The click-flush attacker yaw for the era-moment stamp — the same
         * connection-domain value the pre-send directs the extras along — or null
         * when the attacker has no connection domain (packetless attacker: no wire
         * yaw exists, and the region recompute's live capture IS its attack-time
         * yaw). NON-creating peek — the 2.4.4 domain-poisoning rule.
         */
        private Float registrationYaw(UUID id) {
            ConnectionDomains.Domain domain = domains.peek(id);
            return domain == null ? null : domain.lastYaw();
        }
    }

    /* ---------------------------- shared helpers ---------------------------- */

    /**
     * Whether the netty velocity pre-send is admitted for this hit. The pacing
     * {@link FeedbackGate} is consulted ONLY when a velocity would actually ship —
     * the FeedbackPlan includes the VELOCITY component ({@code velocityEligible})
     * AND the victim has a wire. A hurt-only burst (no eligible velocity) never
     * touches the gate, so it can never debit the pacing budget and starve a later
     * eligible velocity pre-send (B4). A connectionless victim is pinned, never
     * paced by the wire gate. Pure over the gate so the pacing contract is
     * unit-pinned at this seam.
     */
    static boolean admitVelocityPreSend(
            FeedbackGate gate, UUID victimId, boolean victimHasWire,
            boolean velocityEligible, long nowMillis, long minIntervalMillis) {
        if (!velocityEligible) {
            return false; // hurt-only: never debit the gate
        }
        if (!victimHasWire) {
            return true; // connectionless: pinned pre-send, no wire pacing
        }
        return gate.tryPreSend(victimId, nowMillis, minIntervalMillis);
    }

    /**
     * The pre-send attacker capture, pure over its frozen inputs so the enchant
     * parity is unit-pinned at this seam. Attacker velocity is unused by the
     * formula's attacker terms; sprint is the stamped verdict; the Knockback
     * enchant level is the view's per-tick freeze — the netty thread cannot read
     * inventory (Folia), and an adopted PRE_SENT/PINNED vector is never
     * recomputed, so the value shipped HERE is the value the era extra rides. The
     * movement-speed attribute rides the published view so the pre-sent knock's
     * pace scaling matches the tick path (one truth).
     */
    static EntityState preAttackerState(
            double x, double y, double z, float lastYaw, PlayerView view, SprintVerdict verdict) {
        return new EntityState(x, y, z, lastYaw,
                0, 0, 0, view.grounded(), verdict.sprinting(),
                view.kbEnchantLevel(), view.knockbackResistance(), view.moveSpeedAttr());
    }

    /**
     * Commits the transaction state for a planned velocity pre-send off the
     * burst's actual ship {@link BurstSender.Outcome}. Only a DELIVERED burst may
     * account wire-carried (PRE_SENT — the valve will consume the tracker
     * duplicate); anything else on a wired victim is the wire-failed pin — the
     * era-moment vector ships once via the genuine velocity event and no valve
     * arms (a phantom PRE_SENT would let the valve eat the victim's only copy).
     * A connectionless victim pins plain (the pre-existing B4 path). A no-velocity
     * plan (hurt-only burst) commits nothing — the hit stays REGISTERED for the
     * authoritative recompute. Pure over the transaction so the contract is
     * unit-pinned at this seam.
     */
    static void commitPreSendState(
            HitTransaction tx, KnockbackVector shipped, boolean velocityShips,
            boolean victimHasWire, BurstSender.Outcome outcome) {
        if (!velocityShips) {
            return;
        }
        tx.planned();
        if (victimHasWire && outcome == BurstSender.Outcome.DELIVERED) {
            tx.preSent(shipped);
        } else if (victimHasWire) {
            tx.pinnedWireFailed(shipped);
            tx.presend("unsendable-downgrade"); // the wire refused the burst — journal it (F9 namespace)
        } else {
            tx.pinned(shipped);
        }
    }

    /**
     * The two transaction states that mean a knock is ALREADY committed to the
     * victim's client, so the region apply must land the damage to stay coherent
     * (F1). {@code PRE_SENT} — the velocity burst rode the wire to the client;
     * {@code PINNED} — the era vector was pinned to ship once via the genuine
     * velocity event (a connectionless victim, OR a wire-failed burst: {@link
     * HitTransaction#pinnedWireFailed} downgrades INTO {@code PINNED}, so there is no
     * separate wire-failed state to enumerate). Both were already submitted to the
     * victim's desk ({@code submitFromWire}) at plan time, so both have promised the
     * victim a knock. Every other state has committed nothing:
     * {@code REGISTERED}/{@code PLANNED} shipped no knock (velocity suppressed,
     * paced-out, hurt-only, module off, or no view), and the resolved terminals are
     * post-delivery — so era window-silence there stays era silence. Pure so the
     * state inclusion is unit-pinned at this seam.
     *
     * <p>This is why {@code applyNonPlayer} needs no adoption and stays untouched: a
     * non-player target resolves {@code victimId == null}, so {@code plan()} returns
     * on its first guard as a {@code REGISTERED} "no-view" transaction — never
     * PRE_SENT/PINNED — and a non-player victim carries no immunity window to clamp
     * anyway.</p>
     */
    static boolean committedKnock(HitTransaction.State state) {
        return state == HitTransaction.State.PRE_SENT || state == HitTransaction.State.PINNED;
    }

    /**
     * Whether the region apply should adopt this hit as a boundary-fresh hit — clamp
     * the victim's immunity to {@code max/2} so {@code victim.damage} lands full
     * damage — enforcing the 1.6.0 boundary-hit design intent (an admitted boundary
     * hit applies full damage) that the netty pre-send has already committed to on
     * the victim's client (F1;
     * {@code docs/superpowers/plans/2026-07-10-mental-255beta-feedback-coherence.md}).
     *
     * <p>True iff ALL hold:</p>
     * <ul>
     *   <li>{@code committedKnock} — a knock is already on the victim's client (a
     *       PRE_SENT wire burst, or a PINNED velocity-event vector). An uncommitted
     *       (REGISTERED) hit shipped no knock, so an era window-rejection there is
     *       era-correct silence and must stay silent — never clamp it.</li>
     *   <li>{@code max/2 < noDamageTicks <= max/2 + 1} — the LIVE victim is inside
     *       the immunity window (vanilla would reject) AND within ONE tick of the
     *       boundary. The lower bound is vanilla's own gate: at exactly
     *       {@code == max/2} the strict {@code >} ACCEPTS, so there is nothing to
     *       adopt. The upper bound binds the adoption to exactly the race sliver the
     *       fast path opens: the pre-send fires off the FROZEN boundary view whose
     *       {@code damageImmune()} carries the 1.6.0 {@code +1} staleness allowance
     *       ({@code noDamageTicks > max/2 + 1}), and absent a foreign re-arm the live
     *       counter at apply time can only be {@code <=} that frozen read — so the
     *       self-race lands at exactly {@code max/2 + 1} (the damage task beating the
     *       victim's per-tick counter decrement) and the clamp's perturbation is ONE
     *       tick by construction. A live counter ABOVE {@code max/2 + 1} can only
     *       mean a DIFFERENT accepted hit re-armed the window between this hit's
     *       commit and its apply (a pile-on interleaving: a co-attacker's paced-out
     *       hit landing fresh in the 1-2-tick gap) — adopting there would land full
     *       damage deep inside a window the era legitimately awarded to the other
     *       hit, a shared-window DPS bypass. Those keep vanilla's rejection; the
     *       already-shipped knock stands as a rare bounded phantom rather than an
     *       era damage distortion.</li>
     *   <li>{@code amount <= lastDamage} — a same-strength (or weaker) re-hit, which
     *       vanilla's window rejects outright. The UPGRADE branch
     *       ({@code amount > lastDamage}) is DELIBERATELY excluded: vanilla ACCEPTS
     *       an upgrade but subtracts only the DELTA ({@code amount - lastDamage});
     *       clamping the immunity there would make {@code victim.damage(amount)} deal
     *       the FULL {@code amount}, over-damaging where the era dealt the
     *       difference. The upgrade hit fires its own EDBEE with the delta as the
     *       event damage and needs no adoption — so this branch must NEVER clamp.</li>
     * </ul>
     *
     * <p>{@code maxNoDamageTicks / 2} is integer division to match vanilla's gate;
     * for any integer {@code noDamageTicks} it is equivalent to the float
     * {@code max/2.0} the sibling knockback gates use (floor and half-step land on
     * the same integer threshold). Pure over primitives; the immunity/damage reads
     * at the call site are flat Bukkit API across the whole 1.9.4→26.x range, so no
     * version seam is needed there.</p>
     */
    static boolean adoptBoundary(
            boolean committedKnock, int noDamageTicks, int maxNoDamageTicks,
            double amount, double lastDamage) {
        return committedKnock
                && noDamageTicks > maxNoDamageTicks / 2
                && noDamageTicks <= maxNoDamageTicks / 2 + 1
                && amount <= lastDamage;
    }

    /**
     * The F9 journal discriminator for an adopted boundary hit: the prior pre-send
     * disposition with {@code "+boundary-adopted"} appended (the open F9 namespace;
     * {@code commitPreSendState}'s overwrite is the precedent), so the journal
     * Capture and the JOURNAL debug channel tell an adopted hit apart for suites and
     * live diagnosis. A committed hit always carries a prior disposition
     * ({@code "wire"} / {@code "pinned"} / {@code "unsendable-downgrade"}); the null
     * fallback is defensive only. Pure so the composed value is unit-pinned.
     */
    static String boundaryAdopted(String prior) {
        return prior == null ? "boundary-adopted" : prior + "+boundary-adopted";
    }

    private SprintVerdict sprintVerdict(UUID attackerId) {
        if (wtapConsultWire.get()) {
            // NON-creating peek: a packetless attacker has no wire, so it falls
            // through to the published-view sprint rather than materialising a
            // domain here and poisoning its own ledger feed (the 2.4.4 bug).
            ConnectionDomains.Domain domain = domains.peek(attackerId);
            if (domain != null) {
                return domain.sprint().verdictAt(clock.current());
            }
        }
        PlayerView view = sessions.viewOf(attackerId);
        return new SprintVerdict(view != null && view.sprinting(), null, clock.current());
    }

    private Double compensationFor(UUID victimId, PlayerView victimView) {
        if (victimId == null || victimView == null
                || !snapshot.get().enabled(Feature.LATENCY_COMPENSATION)) {
            return null;
        }
        CompensationSettings settings = compensationSettings();
        Double ping = latency.forPlayer(victimId).filteredPingMillis(settings.spikeThresholdMillis());
        int rtt = ping == null ? 0 : (int) Math.round(ping);
        return CompensationQuery.verticalFor(
                victimView, rtt, victimView.motion().vy(), settings.offGroundSync());
    }

    @SuppressWarnings("unchecked")
    private CompensationSettings compensationSettings() {
        return snapshot.get().settings(
                (SettingsKey<CompensationSettings>) Feature.LATENCY_COMPENSATION.settingsKey());
    }

    /** The velocity-pre-send suppressor reason, or null when the pre-send is eligible. */
    private String suppressorFor(KnockbackProfile profile, PlayerView victimView) {
        if (!anticheat.allowVelocityPreSend()) {
            return "anticheat";
        }
        if (profile.resistance() == ResistancePolicy.LEGACY && victimView.knockbackResistance() > 0.0) {
            return "resistance-roll";
        }
        return null;
    }

    private boolean passesReach(UUID attackerId, UUID victimId, HitRegSettings settings) {
        HitRegSettings.ReachValidation reach = settings.reachValidation();
        if (!reach.enabled() || !anticheat.allowReachValidation() || victimId == null) {
            return true;
        }
        PlayerView attackerView = sessions.viewOf(attackerId);
        PlayerView victimView = sessions.viewOf(victimId);
        if (attackerView == null || victimView == null || attackerView.creative()) {
            return true;
        }
        PositionRing.Sample attackerPos = sessions.positions().latest(attackerId);
        PositionRing.Sample victimPos = sessions.positions().latest(victimId);
        if (attackerPos == null || victimPos == null) {
            return true;
        }
        long rewindMillis = Math.min(
                (long) attackerView.pingMillis() + reach.interpolationOffsetMillis(),
                reach.rewindCapMillis());
        long instantNanos = System.nanoTime() - rewindMillis * 1_000_000L;
        // The combo reach handicap's server-side backstop (interaction audit; the
        // servo-lab 243 S2 correction): when the ATTACKER of this swing is themselves
        // a combo VICTIM (someone holds a combo against them, read off their own
        // frozen view) and the handicap is live, vanilla's melee gate would enforce
        // the SHORTENED attribute — but the fast path cancelled that gate. A non-null
        // scale makes ReachClamp scale BOTH the reach and the leniency by the handicap
        // AND measure eye-to-CENTRE, so the window bites below an attribute-blind
        // client's own send envelope (the audit's eye-to-box scale·reach+full-leniency
        // clamp sat above everything such a client could attempt — nil margin at 0.8).
        // Null when the handicap is off, unsupported, or no combo is held against this
        // attacker — then ReachClamp is the byte-identical plain eye-to-box window.
        Double handicapScale = comboReachHandicapScale(attackerView);
        return ReachClamp.passes(
                attackerPos.x(), attackerPos.y() + ReachClamp.EYE_HEIGHT, attackerPos.z(),
                sessions.positions().samplesAround(victimId, instantNanos, 75_000_000L),
                victimPos.x(), victimPos.y(), victimPos.z(),
                reach.maxReach(), reach.leniency(), handicapScale);
    }

    /**
     * The active reach-handicap scale for a swing by the player whose frozen view
     * is {@code attackerView}, or null when the handicap does not shorten their
     * reach: no combo held against them, the sub-feature off, or the attribute lever
     * absent (below 1.20.5, where vanilla's gate could not
     * enforce it either). Netty-safe: one frozen view read, one snapshot read,
     * and the class-load-constant lever probe.
     */
    @SuppressWarnings("unchecked")
    private Double comboReachHandicapScale(PlayerView attackerView) {
        if (attackerView.comboAttackerId() == null) {
            return null; // no combo is held against this attacker (detection runs under either keeper)
        }
        Snapshot current = snapshot.get();
        if (!current.enabled(Feature.COMBO_REACH_HANDICAP) || !ComboReachHandicap.leverSupported()) {
            return null; // the promoted module off, or below 1.20.5 (vanilla's gate could not enforce it)
        }
        ReachHandicapSettings handicap = current.settings(
                (SettingsKey<ReachHandicapSettings>) Feature.COMBO_REACH_HANDICAP.settingsKey());
        return handicap.scale();
    }

    /* ---------------------------- owning-thread appliers ---------------------------- */

    private void applyPlayer(UUID attackerUuid, UUID victimUuid, HitTransaction tx) {
        try {
            Player attacker = Bukkit.getPlayer(attackerUuid);
            if (attacker == null) {
                return;
            }
            if (!scheduling.isOwnedByCurrentRegion(attacker)) {
                retract(victimUuid, tx); // cross-region: drop the pending
                // DROPPED (logged skip). UUIDs only — the attacker sits off this
                // region, so a live getName() would throw on Folia.
                debug.log(() -> "fast-path hit DROPPED (logged skip): attacker " + attackerUuid
                        + " is off victim " + victimUuid + "'s region");
                return;
            }
            if (!attacker.isOnline() || attacker.getGameMode() == GameMode.SPECTATOR) {
                return;
            }
            Player victim = Bukkit.getPlayer(victimUuid);
            if (victim == null || victim.isDead()
                    || !isStillAttackable(attacker, victim) || !isInReach(attacker, victim)) {
                return;
            }
            damageWithSlot(attacker, victim, victimUuid, tx);
            applyToolWear(attacker);
        } catch (IllegalStateException offRegion) {
            if (!folia) {
                throw offRegion;
            }
            // Folia off-region attacker read: the era-correct outcome is a dropped hit.
        }
    }

    private void applyNonPlayer(UUID attackerUuid, int targetEntityId, HitTransaction tx) {
        Player attacker = Bukkit.getPlayer(attackerUuid);
        if (attacker == null || !attacker.isOnline() || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Entity entity = lookupEntity(attacker, targetEntityId);
        if (!(entity instanceof Damageable target) || target.isDead()
                || !isStillAttackable(attacker, target) || !isInReach(attacker, target)) {
            return;
        }
        // A non-player LivingEntity is legacy-composed like a player victim off
        // the attacker's live state; a non-living Damageable takes the retired
        // HitApplier's flat 1.0.
        double amount = target instanceof LivingEntity ? composedAmount(attacker) : 1.0;
        // Bracket the ATTACKER's session with the minted Melee transaction (a mob
        // victim has no session of its own) so the per-hit crit gate can tell this
        // fast-path-composed damage apart from a genuine Player#attack landing
        // (audit C2 — CritFallback must not double-crit Paper mob hits). This path
        // never runs on Folia (non-player targets fall through to vanilla there),
        // so the attacker's owning thread is the current one. A nested event this
        // damage triggers against a PLAYER (thorns-back) is re-established by the
        // DamageRouter per event and is cause-gated out of every melee consumer.
        CombatSession attackerSession = sessions.sessionFor(attackerUuid);
        if (attackerSession != null) {
            attackerSession.activeInbound(tx);
        }
        try {
            target.damage(amount, attacker);
        } finally {
            if (attackerSession != null) {
                attackerSession.clearActiveInbound();
            }
        }
        applyToolWear(attacker);
    }

    private void damageWithSlot(Player attacker, Player victim, UUID victimUuid, HitTransaction tx) {
        CombatSession session = sessions.sessionFor(victimUuid);
        if (session != null) {
            session.activeInbound(tx);
        }
        try {
            // Hoisted so the adoption decision and the damage call read ONE amount
            // (composedAmount re-reads live attacker state; a second call could drift).
            double amount = composedAmount(attacker);
            // Boundary adoption (F1;
            // docs/superpowers/plans/2026-07-10-mental-255beta-feedback-coherence.md).
            // When this hit already committed a knock to the victim's client — the
            // wire burst rode (PRE_SENT) or the era vector was pinned to ship on the
            // genuine velocity event (PINNED) — and the LIVE victim would window-reject
            // it in the ≤1-tick race sliver the +1-stale boundary view opened, clamp
            // the immunity to the boundary so the hit lands as the boundary-fresh hit
            // the pre-send already promised. EDBEE then fires and the cosmetics realign
            // with the knock the player already saw (knock ⇔ damage ⇔ EDBEE ⇔ effects).
            // The upgrade branch is never clamped (adoptBoundary vetoes amount >
            // lastDamage — vanilla deals only the delta there; a clamp would over-damage).
            if (adoptBoundary(committedKnock(tx.state()), victim.getNoDamageTicks(),
                    victim.getMaximumNoDamageTicks(), amount, victim.getLastDamage())) {
                victim.setNoDamageTicks(victim.getMaximumNoDamageTicks() / 2);
                tx.presend(boundaryAdopted(tx.presend())); // F9 journal discriminator
            }
            victim.damage(amount, attacker);
        } finally {
            if (session != null) {
                session.clearActiveInbound();
            }
        }
    }

    private void retract(UUID victimUuid, HitTransaction tx) {
        CombatSession session = sessions.sessionFor(victimUuid);
        if (session != null) {
            session.desk().withdraw(tx.context().id());
        }
    }

    /**
     * The fast-path damage amount (spec §4.6): the {@link me.vexmc.mental.v5.feature.damage.DamageShaper}
     * legacy composition — weapon base → era Strength/Weakness → crit ×1.5 →
     * Sharpness — off the attacker's live state. {@code simulateCrits}/{@code
     * legacyToolDamage} come from the hit-reg settings; {@code old-potion-values}
     * from its live toggle. Owning thread only (the attacker read is
     * region-guarded by the caller).
     */
    private double composedAmount(Player attacker) {
        HitRegSettings settings = settings();
        return shaper.compose(
                attacker,
                settings.simulateCrits(), settings.legacyToolDamage(),
                snapshot.get().enabled(Feature.POTION_VALUES));
    }

    /**
     * Wears the attacker's weapon by one hit when {@code old-tool-durability} is
     * enabled (the retired {@code HitApplier} durability step). Scheduled on the
     * attacker's region — {@link ToolWear} re-reads the main-hand item, so a Folia
     * hop cannot wear a stale or swapped item.
     */
    private void applyToolWear(Player attacker) {
        if (!snapshot.get().enabled(Feature.TOOL_DURABILITY)) {
            return;
        }
        scheduling.runOn(attacker, () -> toolWear.applyOneHit(attacker), () -> {});
    }

    private static Entity lookupEntity(Player attacker, int entityId) {
        for (Entity entity : attacker.getWorld().getEntities()) {
            if (entity.getEntityId() == entityId) {
                return entity;
            }
        }
        return null;
    }

    private static boolean isStillAttackable(Player attacker, Damageable target) {
        if (attacker.getWorld() != target.getWorld()) {
            return false;
        }
        if (target instanceof Player victim) {
            return victim.getGameMode() != GameMode.CREATIVE
                    && victim.getGameMode() != GameMode.SPECTATOR
                    && attacker.getWorld().getPVP();
        }
        return true;
    }

    private static boolean isInReach(Player attacker, Damageable target) {
        double dx = attacker.getLocation().getX() - target.getLocation().getX();
        double dy = attacker.getLocation().getY() - target.getLocation().getY();
        double dz = attacker.getLocation().getZ() - target.getLocation().getZ();
        return dx * dx + dy * dy + dz * dz <= MAX_REACH_SQUARED;
    }
}

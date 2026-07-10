package me.vexmc.mental.v5.feature.knockback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.debug.DebugLog;
import me.vexmc.mental.kernel.combo.ComboTracker;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.delivery.ValvePayload;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.HurtYaw;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.math.PocketServo;
import me.vexmc.mental.kernel.math.PocketServoConfig;
import me.vexmc.mental.kernel.math.PredictorInputs;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.ResetModel;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.wire.CompensationQuery;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.kernel.wire.SprintWire;
import me.vexmc.mental.platform.Enchantments;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.VelocityValve;
import me.vexmc.mental.v5.Vectors;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.config.settings.CompensationSettings;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.config.settings.ReachHandicapSettings;
import me.vexmc.mental.v5.delivery.HitIds;
import me.vexmc.mental.v5.feature.EphemeralDecoration;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.feature.combo.ComboEvents;
import me.vexmc.mental.v5.feature.combo.ComboPredictor;
import me.vexmc.mental.v5.feature.combo.ComboReachHandicap;
import me.vexmc.mental.v5.rim.BurstSender;
import me.vexmc.mental.v5.rim.ConnectionDomains;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 1.7.10 melee knockback (the retired {@code KnockbackModule} on the v5 seams).
 * A pure vector computer: it reads the event's transaction from the
 * {@code DamageRouter} slot, computes the engine vector from the victim's
 * {@link me.vexmc.mental.kernel.ledger.MotionLedger} residual, and submits it to
 * the victim's {@link DeliveryDesk} — which swaps it into the velocity event and
 * records what shipped (spec §3.4–§3.5). A fast-path hit that already carried a
 * pre-delivered vector is <em>adopted</em>, not recomputed (the era servers
 * stamped once).
 *
 * <p>Dispatch is on the typed {@link HitSource}: a {@code RodPull} is never
 * treated as melee (B6); a server-side melee that never hit the fast path arrives
 * as {@code Vanilla(ENTITY_ATTACK)} and is knocked. Accepted sprint-bonus hits run
 * the vanilla obligations the cancelled attack left behind — the attacker's
 * {@code setSprinting(false)}, its ledger {@code ×0.6} self-slow, and the wire
 * sprint clear.</p>
 */
public final class KnockbackUnit implements FeatureUnit, Listener {

    private final SessionService sessions;
    private final ConnectionDomains domains;
    private final LatencyModel latency;
    private final Scheduling scheduling;
    private final Supplier<Snapshot> snapshot;
    private final HitIds ids;
    private final TickClock clock;
    private final VelocityValve valve;
    private final DebugLog.Scoped debug;
    private final ComboEvents comboEvents;

    /**
     * The sword-block decoration service — read-only here, and ONLY to ask "is
     * this victim blocking with Mental's own injected temp shield?" (audit C1).
     * On the off-hand tier (≤1.20.6) that shield is a real {@code SHIELD}, so
     * vanilla FULL-blocks a raised frontal hit; the {@code shieldBlockingCancels}
     * withdraw must never fire for it — the era sword-block contract is half
     * damage + FULL knock, the exact inverse of the modern-shield semantics the
     * cancel encodes. With SWORD_BLOCKING off the service tracks nobody, so the
     * query is constant-false and the cancel behaves exactly as before
     * (zero-touch).
     */
    private final EphemeralDecoration swordBlock;

    /**
     * The burst sender reused for the blocked-hit hurt presentation (ships
     * VELOCITY + HURT exactly as the fast path would). Lazily constructed on the
     * first blocked delivery so its {@code PacketEvents} version read happens at
     * runtime (post-init), never eagerly at reconciler registration.
     */
    private volatile BurstSender blockBurst;

    public KnockbackUnit(
            SessionService sessions, ConnectionDomains domains,
            LatencyModel latency, Scheduling scheduling, Supplier<Snapshot> snapshot,
            HitIds ids, TickClock clock, VelocityValve valve, DebugLog.Scoped debug,
            ComboEvents comboEvents, EphemeralDecoration swordBlock) {
        this.sessions = sessions;
        this.domains = domains;
        this.latency = latency;
        this.scheduling = scheduling;
        this.snapshot = snapshot;
        this.ids = ids;
        this.clock = clock;
        this.valve = valve;
        this.debug = debug;
        this.comboEvents = comboEvents;
        this.swordBlock = swordBlock;
    }

    @Override
    public Feature descriptor() {
        return Feature.KNOCKBACK;
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
        scope.listen(this);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // DamageModifier is the only shield-absorption signal Bukkit has
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof LivingEntity attacker)
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        CombatSession session = sessions.sessionFor(victim.getUniqueId());
        if (session == null) {
            return;
        }
        HitTransaction tx = session.currentEventTransaction();
        if (tx == null) {
            return;
        }
        HitSource source = tx.context().source();
        if (source instanceof HitSource.RodPull) {
            return; // a rod deals ENTITY_ATTACK through victim.damage(rodder) — not melee (B6)
        }
        boolean melee = source instanceof HitSource.Melee
                || (source instanceof HitSource.Vanilla vanilla && "ENTITY_ATTACK".equals(vanilla.damageCause()));
        if (!melee) {
            return;
        }

        DeliveryDesk desk = session.desk();
        // A cross-region attacker (Folia boundary straddle / pearl in the dispatch
        // tick): skip before any attacker read — the swallowed-throw trap.
        if (!scheduling.isOwnedByCurrentRegion(attacker)) {
            desk.withdraw(tx.context().id());
            // DROPPED (logged skip). UUIDs only — the attacker sits off this
            // region, so a live getName() would throw on Folia.
            debug.log(() -> "melee knock DROPPED (logged skip): attacker " + tx.context().attackerId()
                    + " is off victim " + victim.getUniqueId() + "'s region");
            return;
        }
        KnockbackProfile profile = profileFor(session, victim);
        boolean blockModifier = event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0;
        // A block by Mental's OWN injected temp shield (the ≤1.20.6 sword-block
        // off-hand tier, audit C1). Vanilla treats it as a real raised shield —
        // full-blocks the damage and skips markHurt — but the feature it decorates
        // is the 1.7 SWORD block, whose era contract is half damage + FULL knock.
        // Such a hit is exempt from the full-block cancel below and routes through
        // the blocked-knock redelivery instead (normally the SwordBlockingUnit has
        // already rewritten the BLOCKING modifier to the era value at HIGH, so the
        // hit reads as a partial block; this exemption is the belt for the raw
        // full-negate arriving here unrewritten).
        boolean mentalTempShieldBlock = blockModifier && swordBlock.isBlockingWithTempShield(victim);
        // Era rule: blocking shaped DAMAGE only (knockBack ran first), so a
        // blocking victim was knocked in FULL — cancel only for a FULL block
        // (final damage zero, the modern shield vanilla itself never knocks
        // through); a partial reduction knocks like the era.
        if (profile.shieldBlockingCancels() && blockModifier && !mentalTempShieldBlock
                && event.getFinalDamage() <= 0.0) {
            desk.withdraw(tx.context().id());
            return;
        }
        boolean sprinting = attackerSprinting(source, tx, attacker);
        // A PARTIAL native block (a negative BLOCKING modifier that still lands
        // damage — the BLOCKS_ATTACKS component tier on 1.21.5+, or the off-hand
        // tier after the SwordBlockingUnit rewrote a temp-shield full block to the
        // era value). Vanilla runs its blocked-damage pipeline but SKIPS markHurt,
        // so no ENTITY_VELOCITY is broadcast and no PlayerVelocityEvent fires — the
        // desk's await would be swept as "no-velocity-event" and the era knock
        // lost. The era knocks a blocked hit in FULL, so deliver the vector
        // directly; a temp-shield full block that arrived UNREWRITTEN (final still
        // zero) is included — the era knock must ship even when vanilla zeroed the
        // damage (audit C1). Only a FRESH hit (not a mid-invulnerability
        // difference hit) knocks: the difference branch is era-silent and must
        // stay so (compendium: stronger mid-invuln hit deals difference damage
        // with NO knock and no flinch).
        // Mid-invulnerability at hit time: vanilla deals only the difference damage
        // and applies NO knockback (no velocity event) — the era-silent difference
        // branch (compendium: no knock, no flinch). Read once; it splits the
        // blocked-knock shape into its fresh and era-silent halves below.
        boolean immune = victimImmune(session, victim);
        // The blocked-knock event shape: a partial native block still landing
        // damage, or an unrewritten temp-shield full block (audit C1). Vanilla
        // SKIPS markHurt for it, so no velocity event ever resolves it — the two
        // branches below decide whether the era knock ships anyway (fresh) or the
        // era withholds it (mid-invuln difference).
        boolean blockedKnockShape = blockModifier
                && (event.getFinalDamage() > 0.0 || mentalTempShieldBlock);
        boolean freshBlockedKnock = blockedKnockShape && !immune;
        // The era-silent blocked difference hit — freshBlockedKnock's exact
        // inverse within the blocked shape (the 2.4.0 difference-branch contract:
        // no knock, no flinch). This, and ONLY this, is the class the region-path
        // submit below leaves UNARMED: it is the one class where the frozen
        // immune read is corroborated by the event shape vanilla actually took.
        // The bare frozen read is NOT a discriminator on its own — it is a
        // boundary read (end of the previous tick, +1 staleness allowance) and
        // classifies a legal boundary combo hit as immune too, while vanilla, on
        // its LIVE counter, knocks that hit in full and fires its velocity event
        // (the 1.9.4 second-regression: unarming on the bare read left the combo
        // second hit's genuine event to pass through a packetless victim's zeroed
        // vanilla motion — applied 0,0,0 instead of the ledger-stacked stamp).
        boolean eraSilentBlockedHit = blockedKnockShape && immune;

        HitTransaction.State state = tx.state();
        if (state == HitTransaction.State.PRE_SENT || state == HitTransaction.State.PINNED) {
            if (freshBlockedKnock) {
                deliverBlockedKnock(
                        session, victim, attacker, source, tx, tx.carried(),
                        state == HitTransaction.State.PRE_SENT, sprinting, tx.paceFactor(),
                        tx.comboFactor());
                return;
            }
            // Adopt the pre-delivered vector — the era stamped once.
            desk.awaitVelocityEvent(tx);
            applyAttackerObligations(attacker, sprinting, tx.context().sprint());
            return;
        }

        Double compensationY = compensationFor(source, tx, session, victim);
        EntityState victimState = EntityStates.captureVictim(victim, session.ledger());
        // The era-moment yaw: the extras are directed along the attacker's facing,
        // which vanilla read synchronously inside attack() at click-flush. A
        // fast-path REGISTERED hit recomputes here 1–2 ticks later, so the live
        // location yaw has drifted with the attacker's mouse — consume the
        // registration stamp instead (the SprintVerdict's exact sibling). Null
        // stamp (Vanilla-source mint, packetless attacker) keeps the live capture,
        // which is the click-flush yaw for both of those cases.
        EntityState attackerState = adoptRegistrationYaw(
                EntityStates.capture(attacker, sprinting), tx.context().attackerYaw());
        boolean freshSprint = source instanceof HitSource.Melee && sprinting
                && tx.context().sprint().fresh() != null && tx.context().sprint().fresh();
        // The pocket servo (combo-hold §3.2): active only when THIS attacker holds
        // the victim's active combo, read from the victim's frozen view — one truth
        // shared with the netty pre-send. INACTIVE ⇒ σ = 1.0 (byte-identical).
        PocketServoConfig servo = comboServoFor(session.view(), attacker.getUniqueId());
        // The precision predictor inputs (combo-hold §3.2b) — built from the frozen
        // views + ring + latency, with the live capture positions as the axis source.
        // Built only when the servo is active; the degraded inputs otherwise.
        PlayerView victimView = session.view();
        PlayerView attackerView = sessions.viewOf(attacker.getUniqueId());
        // One tick read serves the build AND the window commit below — the post-hit
        // chase window's gap arithmetic needs both on the same clock instant.
        TickStamp servoNow = clock.current();
        PredictorInputs inputs = servo.active() && victimView != null
                ? ComboPredictor.build(attacker.getUniqueId(), victim.getUniqueId(),
                        attackerState.x(), attackerState.z(), victimState.x(), victimState.z(),
                        victimView, attackerView, sessions.positions(), latency, servoNow,
                        attackerResetModel(attacker.getUniqueId(), servoNow))
                : PredictorInputs.degraded(victimState.grounded(),
                        victimView != null ? victimView.slipperiness() : Decay.DEFAULT_SLIPPERINESS,
                        victimState.moveSpeedAttr());
        KnockbackEngine.Paced paced = KnockbackEngine.computePaced(
                attackerState, victimState, profile, compensationY,
                ThreadLocalRandom.current(), freshSprint, servo, inputs);
        KnockbackVector vector = paced.vector();
        tx.paceFactor(paced.paceFactor()); // journal the factor actually applied (D-6)
        tx.comboFactor(paced.comboFactor());
        // Commit the post-hit chase window on EVERY active servo hit (servo-lab
        // 2.4.5): this hit's attacker position/tick anchors the next window's
        // measurement, and the EMA the inputs carried is persisted. Load-bearing —
        // never gated behind the diagnostics sink or the dynamic-target mode.
        if (servo.active()) {
            ComboPredictor.rememberWindow(victim.getUniqueId(),
                    attackerState.x(), attackerState.z(), servoNow, inputs);
        }
        // The debug sink alone rides the debug gate: the answer-denial boundary
        // target is computed fresh from geometry each hit (no cross-hit memory to
        // commit), so under the shipped defaults nothing gameplay-shaping runs
        // debug-off. The post-hit chase window commit above is the only load-bearing
        // solve state and is never gated.
        if (servo.active() && debug.active()) {
            PocketServo.Solution solution = KnockbackEngine.explainServo(
                    attackerState, victimState, profile, compensationY, freshSprint, servo, inputs);
            debug.log(() -> ComboPredictor.debugLine(
                    victim.getUniqueId(), attacker.getUniqueId(), inputs, solution));
        }

        if (freshBlockedKnock) {
            deliverBlockedKnock(session, victim, attacker, source, tx, vector, false, sprinting,
                    tx.paceFactor(), tx.comboFactor());
            return;
        }

        desk.submit(tx, vector);
        if (!eraSilentBlockedHit) {
            // Arm the desk for the victim's PlayerVelocityEvent — vanilla knocks
            // this hit and fires it (and, for a packetless victim whose event lands
            // late or never, the armed decision lets the region-path net ship the
            // stranding, F1). This deliberately includes hits whose FROZEN immune
            // read says mid-invuln: that read is a boundary snapshot and flags legal
            // boundary combo hits too, whose genuine velocity event must resolve to
            // the ledger-stacked stamp exactly as it always did (arming here is the
            // pre-F1-net behavior for every non-blocked hit). Only the era-silent
            // blocked difference hit stays UNARMED: vanilla applies no knockback and
            // fires no velocity event for it, so the sweep drops it and the net's
            // awaitingDeliveryFor gate never fabricates a knock the era withholds —
            // the submitted vector still feeds the mirror/journal exactly as before,
            // so only the (never-firing) velocity-event arm changes.
            desk.awaitVelocityEvent(tx);
        }
        applyAttackerObligations(attacker, sprinting, tx.context().sprint());
    }

    /** A protection plugin cancelling the melee hit withdraws the queued knock. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMeleeCancelled(EntityDamageByEntityEvent event) {
        if (!event.isCancelled()
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        CombatSession session = sessions.sessionFor(victim.getUniqueId());
        if (session == null) {
            return;
        }
        HitTransaction tx = session.currentEventTransaction();
        if (tx != null && !(tx.context().source() instanceof HitSource.RodPull)) {
            session.desk().withdraw(tx.context().id());
        }
    }

    private boolean attackerSprinting(HitSource source, HitTransaction tx, LivingEntity attacker) {
        if (source instanceof HitSource.Melee) {
            SprintVerdict verdict = tx.context().sprint();
            return verdict != null && verdict.sprinting();
        }
        return attacker instanceof Player player && player.isSprinting();
    }

    private Double compensationFor(
            HitSource source, HitTransaction tx, CombatSession session, Player victim) {
        if (source instanceof HitSource.Melee) {
            return tx.context().compensationY(); // compute-once from the fast path
        }
        if (!snapshot.get().enabled(Feature.LATENCY_COMPENSATION)) {
            return null;
        }
        PlayerView view = session.view();
        if (view == null) {
            return null;
        }
        CompensationSettings settings = compensationSettings();
        Double ping = latency.forPlayer(victim.getUniqueId()).filteredPingMillis(settings.spikeThresholdMillis());
        int rtt = ping == null ? 0 : (int) Math.round(ping);
        return CompensationQuery.verticalFor(view, rtt, view.motion().vy(), settings.offGroundSync());
    }

    @SuppressWarnings("unchecked")
    private CompensationSettings compensationSettings() {
        return snapshot.get().settings(
                (SettingsKey<CompensationSettings>) Feature.LATENCY_COMPENSATION.settingsKey());
    }

    /**
     * The vanilla obligations the cancelled attack left behind, for an accepted
     * sprint-bonus hit: the attacker's ledger {@code ×0.6} self-slow, the wire
     * sprint clear, and {@code setSprinting(false)} (a no-op when vanilla's own
     * attack already cleared it on the server-side melee path).
     *
     * <p>Both clears are guarded by the hit's {@code verdict}. A wire-peeked verdict
     * carries the wire's arrival sequence at peek time ({@link SprintVerdict#wireSeq}),
     * so the clear is ordered by ARRIVAL, not by tick granularity: vanilla cleared
     * sprint synchronously inside {@code attack}, so it could never eat a re-engage
     * that arrived afterwards; Mental's clears run 1–2 ticks late at the deferred
     * EDBEE, so a w-tap START that landed in the SAME tick as (and after) the ATTACK
     * must survive — the 2.4.x same-tick retro-clear defect a tick stamp could not
     * separate. The wire clear no-ops under a wire write with a larger seq
     * ({@link SprintWire#onServerClear(long)}), and the deferred
     * {@code setSprinting(false)} is skipped when the wire has re-armed past this
     * hit's peek seq by execution time (F1). A verdict with no wire provenance
     * ({@link SprintVerdict#fromWire} false) falls back to the tick-stamp guard
     * ({@link SprintWire#onServerClear(TickStamp)}) and the plain live-wire read,
     * byte-identical to 2.4.1.</p>
     */
    private void applyAttackerObligations(LivingEntity attacker, boolean sprinting, SprintVerdict verdict) {
        if (!(attacker instanceof Player player)) {
            return;
        }
        UUID attackerId = player.getUniqueId();
        CombatSession attackerSession = sessions.sessionFor(attackerId);
        // Combo-hold retaliation (combo-hold §3.1): the player who just landed this
        // accepted melee is the combo VICTIM if they were being juggled — landing
        // any hit ends the combo held against them. This resolves the ATTACKER's
        // own session (same region as the victim for melee), so it stamps the
        // right tracker regardless of whether the hit carried a sprint bonus.
        if (attackerSession != null && attackerSession.comboTracker() != null) {
            comboEvents.fire(player, attackerSession.comboTracker().onOwnHitLanded(clock.current()));
        }
        boolean bonus = sprinting || heldKnockbackLevel(player) > 0;
        if (!bonus) {
            return;
        }
        if (attackerSession != null) {
            attackerSession.ledger().scaleHorizontal(0.6);
        }
        // Mirror vanilla's in-attack sprint clear onto the wire — but ONLY when a
        // real connection domain already exists to clear. A packetless attacker
        // (synthetic player / in-process bot) has no SprintWire the rim ever fed,
        // and creating one here (domainFor is computeIfAbsent) would make it look
        // connected — permanently standing its ground sampler down and free-falling
        // its ledger to a zero vertical (the 2.4.4 domain-poisoning bug; the
        // SwordBlockingUnit:259 non-creating idiom is the precedent).
        if (domains.has(attackerId)) {
            SprintWire wire = domains.domainFor(attackerId).sprint();
            if (verdict.fromWire()) {
                wire.onServerClear(verdict.wireSeq());
            } else {
                // No wire provenance (wtap-registration off / view-fallback verdict):
                // the stamp guard is the best available ordering, byte-identical to 2.4.1.
                wire.onServerClear(verdict.at());
            }
        }
        if (player.isSprinting()) {
            scheduling.runOn(player, () -> {
                // Skip the server-flag clear only for a re-engage NEWER than this hit's
                // verdict peek (arrival order) — vanilla's synchronous clear could never
                // eat it. With no wire provenance, keep the plain live-wire read (2.4.1).
                if (domains.has(attackerId)) {
                    SprintVerdict live = domains.domainFor(attackerId).sprint().verdictAt(clock.current());
                    if (live.sprinting()
                            && (!verdict.fromWire() || live.wireSeq() > verdict.wireSeq())) {
                        return;
                    }
                }
                player.setSprinting(false);
            }, () -> {});
        }
    }

    /**
     * Deliver a fresh partial-block knock when no vanilla velocity event will
     * resolve it — the desk's no-velocity-event path (the same mechanism the
     * thrown-projectile ensure uses). On the {@code BLOCKS_ATTACKS} component tier
     * (1.21.5+) vanilla reduces a blocked hit natively but SKIPS {@code markHurt},
     * so it broadcasts no {@code ENTITY_VELOCITY} and fires no {@code
     * PlayerVelocityEvent}; the desk's await would be swept as "no-velocity-event"
     * and the era knock lost. The era knocks a partial block in FULL
     * (compendium: blocking halves damage AFTER knockBack ran), so we ship it.
     *
     * <p>The original transaction is withdrawn — and JOURNALED
     * ({@code blocked-redeliver} → the fresh id) so the sweep never records a
     * false drop and the blocked hit stays correlatable — and a fresh one is
     * submitted carrying the ORIGINAL sprint verdict (not a misleading
     * {@code SprintVerdict(false)}). {@code setVelocity} triggers the tracker's
     * velocity event, which the desk resolves to the full stamp (undecayed: a
     * REGISTERED resolve ships the submitted vector, ignoring the physics-decayed
     * api value) and journals a SHIP. Delivery runs INLINE when the current thread
     * already owns the victim (the EDBEE does), so the knock ships the SAME tick —
     * era-consistent (vanilla knockback applies during attack processing, not a
     * tick later) — falling back to {@code runOn} otherwise; a retired victim
     * journals a {@code victim-retired} drop rather than vanishing silently.</p>
     *
     * <p>Presentation mirrors the fast path: a client with no wire pre-send gets a
     * VELOCITY + HURT burst, and the valve is armed whenever a wire copy exists so
     * the authoritative tracker re-emission is consumed once, never doubled. The
     * VICTIM'S OWN HURT SOUND is played to the victim alone in BOTH branches:
     * vanilla's {@code playHurtSound} broadcast excludes exactly the victim, and
     * the victim's client derives its own hurt sound from the
     * {@code ClientboundDamageEventPacket} that the blocked branch replaces with a
     * no-op {@code onBlocked} (blockSound empty) — so the one missing audience is
     * the victim (F4; the 2.4.0 record wrongly assumed vanilla still served it).
     * Mental's HURT_ANIMATION burst is soundless by design. A world broadcast
     * would double the sound for the bystanders vanilla already served. Since the
     * temp-shield exemption (audit C1) this path also fires on the off-hand tier
     * (1.9.4–1.20.6): {@code Sound.ENTITY_PLAYER_HURT} is spelled identically
     * across that whole range (the ToolWear precedent), and the four-arg
     * {@code playSound} is used because the {@code SoundCategory} overload only
     * exists from API 1.11.</p>
     */
    private void deliverBlockedKnock(
            CombatSession session, Player victim, LivingEntity attacker, HitSource source,
            HitTransaction original, KnockbackVector era, boolean wirePreSent, boolean sprinting,
            double paceFactor, double comboFactor) {
        applyAttackerObligations(attacker, sprinting, original.context().sprint());
        if (era == null) {
            return; // nothing carried/computed — leave vanilla's (absent) knock
        }
        DeliveryDesk desk = session.desk();
        UUID victimId = victim.getUniqueId();
        UUID attackerId = attacker.getUniqueId();
        // Mint the fresh redelivery up front so the withdraw can name its
        // superseding id and a retired delivery can journal a correlated drop. It
        // carries the ORIGINAL sprint verdict — the journal context stays honest.
        HitTransaction fresh = mint(source, attackerId, victimId, original.context().sprint(),
                paceFactor, comboFactor, original.context().attackerYaw());
        // Withdraw + JOURNAL the original (a PRE_SENT/PINNED submitFromWire) so the
        // tick sweep cannot record it as a false "no-velocity-event"; a REGISTERED
        // region-path original was never on the desk, so this is a no-op there.
        desk.withdrawSuperseded(original.context().id(), "blocked-redeliver", fresh.context().id());
        PlayerView view = session.view();
        int entityId = view != null ? view.entityId() : victim.getEntityId();
        float hurtYaw = hurtYawFor(attacker, victim);
        boolean bundle = bundleFeedback();
        Runnable delivery = () -> {
            desk.submit(fresh, era);
            desk.awaitVelocityEvent(fresh);
            boolean wireCopy = wirePreSent;
            if (!wirePreSent) {
                User user = PacketEvents.getAPI().getPlayerManager().getUser(victim);
                if (user != null) {
                    // No registration burst reached the wire (server-side melee, or a
                    // paced-out velocity): ship VELOCITY + HURT now, exactly as the
                    // fast path would have — the client sees the era knock and flinch.
                    burstSender().ship(user, entityId, era, hurtYaw, bundle);
                    wireCopy = true;
                }
            }
            if (wireCopy) {
                // A wire copy already carries the value; arm the valve so the
                // authoritative tracker re-emission below (and any late vanilla
                // velocity for this hit) is consumed once, never stacked on it.
                valve.arm(victimId, ValvePayload.of(entityId, era));
            }
            // The victim's own hurt sound — the one client vanilla's blocked branch
            // leaves silent (see the javadoc). Play it to the victim alone; pitch
            // mirrors vanilla LivingEntity.handleDamageEvent (1 + (r1 − r2) × 0.2).
            float pitch = 1.0f + (ThreadLocalRandom.current().nextFloat()
                    - ThreadLocalRandom.current().nextFloat()) * 0.2f;
            // Four-arg overload: the SoundCategory form floors at API 1.11, and this
            // path now runs down to 1.9.4 (the off-hand temp-shield exemption).
            victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, pitch);
            // The authoritative motion: overwrites vanilla's blocked deltaMovement,
            // triggers the velocity event (→ desk SHIP journal), and moves the server
            // entity (server-authoritative for anticheats and clientless fakes).
            victim.setVelocity(Vectors.toBukkit(era));
        };
        // Same-tick ship: the EDBEE already runs on the victim's owning thread, so
        // run inline when we own it; a retired victim journals a drop, not silence.
        scheduling.ensureOn(victim, delivery, () -> desk.journalDrop(fresh, "victim-retired"));
    }

    private HitTransaction mint(
            HitSource source, UUID attackerId, UUID victimId, SprintVerdict verdict,
            double paceFactor, double comboFactor, Float attackerYaw) {
        HitContext context = new HitContext(
                ids.next(), source, attackerId, victimId, verdict, false, null,
                clock.current(), attackerYaw);
        HitTransaction fresh = new HitTransaction(context);
        fresh.paceFactor(paceFactor); // carry the factors the original applied (D-6)
        fresh.comboFactor(comboFactor);
        return fresh;
    }

    /** The registration-time yaw stamp folded over the live capture; null = no stamp, live wins. */
    static EntityState adoptRegistrationYaw(EntityState captured, Float registrationYaw) {
        return registrationYaw == null ? captured : captured.withYaw(registrationYaw);
    }

    /**
     * True when the victim reads mid-invulnerability off the FROZEN boundary view
     * (end of the previous tick, +1 staleness allowance). This is an approximation
     * of vanilla's live counter, not a truth about the branch vanilla took: a legal
     * boundary combo hit — accepted and knocked in full by vanilla's LIVE read —
     * can still read immune here. It is therefore only ever a discriminator when
     * CORROBORATED by the blocked event shape (the era-silent blocked difference
     * hit); never gate a non-blocked hit's delivery on it alone.
     */
    private boolean victimImmune(CombatSession session, Player victim) {
        PlayerView view = session.view();
        if (view != null) {
            return view.damageImmune();
        }
        return victim.getNoDamageTicks() > victim.getMaximumNoDamageTicks() / 2.0;
    }

    private static float hurtYawFor(LivingEntity attacker, Player victim) {
        Location a = attacker.getLocation();
        Location v = victim.getLocation();
        return HurtYaw.hurtYaw(a.getX(), a.getZ(), v.getX(), v.getZ(), v.getYaw());
    }

    @SuppressWarnings("unchecked")
    private boolean bundleFeedback() {
        HitRegSettings settings = snapshot.get().settings(
                (SettingsKey<HitRegSettings>) Feature.HIT_REGISTRATION.settingsKey());
        return settings.bundleFeedback();
    }

    /** Lazily constructed on the first blocked delivery — {@code PacketEvents} read at runtime, not at boot. */
    private BurstSender burstSender() {
        BurstSender local = blockBurst;
        if (local == null) {
            local = new BurstSender();
            blockBurst = local;
        }
        return local;
    }

    private KnockbackProfile profileFor(CombatSession session, Player victim) {
        PlayerView view = session.view();
        return view != null ? view.profile() : snapshot.get().profileFor(victim.getWorld().getName());
    }

    /**
     * The pocket-servo config for a hit from {@code attackerId} to the victim
     * whose frozen {@code view} is given (combo-hold §3.2). Active only when the
     * module is on AND this attacker is the one the view says holds the victim's
     * active combo — the same gate the netty pre-send uses, off one frozen truth.
     * Otherwise {@link PocketServoConfig#INACTIVE} (σ = 1.0, byte-identical). The
     * config carries the victim's EFFECTIVE answer reach (the era reach folded with
     * the combo reach handicap when it is live), so the two combo submodules compose
     * — see {@link #effectiveVictimReach}.
     */
    @SuppressWarnings("unchecked")
    private PocketServoConfig comboServoFor(PlayerView view, UUID attackerId) {
        if (view == null || attackerId == null || !snapshot.get().enabled(Feature.COMBO_HOLD)) {
            return PocketServoConfig.INACTIVE;
        }
        if (!attackerId.equals(view.comboAttackerId())) {
            return PocketServoConfig.INACTIVE;
        }
        ComboSettings settings = snapshot.get().settings(
                (SettingsKey<ComboSettings>) Feature.COMBO_HOLD.settingsKey());
        return settings.servo(effectiveVictimReach(settings));
    }

    /**
     * The victim's EFFECTIVE answer reach for the answer-denial boundary target —
     * the era reach ({@code combo-hold.victim-reach}), shortened by the combo reach
     * handicap when that module is enabled AND enforceable on this server (the
     * client-synced attribute exists, 1.20.5+). The handicap's attribute modifier is
     * live for exactly the combo's duration, which is exactly when the servo is
     * active, so lowering {@code R_v} here drops the deny boundary and OPENS the
     * keepable pocket — the whole reason "the reach nerf is the only thing keeping my
     * combo." {@code R_a} (the attacker) is never handicapped.
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

    private static int heldKnockbackLevel(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && main.getType() != Material.AIR) {
            return main.getEnchantmentLevel(Enchantments.knockback());
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off == null || off.getType() == Material.AIR) {
            return 0;
        }
        return off.getEnchantmentLevel(Enchantments.knockback());
    }
}

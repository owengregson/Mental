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
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.math.HurtYaw;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.ResistancePolicy;
import me.vexmc.mental.kernel.wire.CompensationQuery;
import me.vexmc.mental.kernel.wire.CpsLimiter;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.kernel.wire.PositionRing;
import me.vexmc.mental.kernel.wire.ReachValidator;
import me.vexmc.mental.v5.coexist.AnticheatPolicy;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
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
 * suppressed (anticheat, OCM ownership, legacy resistance roll, missing views, the
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

    private final CpsLimiter cps = new CpsLimiter();
    private final FeedbackGate feedbackGate = new FeedbackGate();

    public HitRegistrationUnit(
            SessionService sessions, ConnectionDomains domains, LatencyModel latency,
            AnticheatPolicy anticheat, AtomicBoolean wtapConsultWire, TickClock clock,
            Supplier<Snapshot> snapshot, Scheduling scheduling,
            me.vexmc.mental.v5.VelocityValve valve, me.vexmc.mental.v5.delivery.HitIds ids,
            me.vexmc.mental.v5.feature.damage.DamageShaper shaper,
            me.vexmc.mental.v5.feature.damage.ToolWear toolWear,
            boolean folia, boolean modernProtocol) {
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
    }

    @Override
    public Feature descriptor() {
        return Feature.HIT_REGISTRATION;
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
            boolean ocmOwns = attackerView != null && attackerView.ocmOwnsMeleeKnockback();
            boolean victimHasWire = playerVictim != null
                    && PacketEvents.getAPI().getPlayerManager().getUser(playerVictim) != null;
            Double compensationY = compensationFor(victimId, victimView);

            HitContext context = new HitContext(
                    ids.next(), new HitSource.Melee(), attackerId, victimId,
                    verdict, ocmOwns, victimHasWire, compensationY, clock.current());
            HitTransaction tx = new HitTransaction(context);

            // The pre-send only exists for player victims with published views.
            if (playerVictim == null || victimId == null || attackerView == null || victimView == null
                    || !settings.preSendFeedback() || victimView.damageImmune()) {
                return tx;
            }

            KnockbackProfile profile = victimView.profile();
            KnockbackVector vector = null;
            String suppressed = suppressorFor(ocmOwns, profile, victimView);
            if (suppressed == null) {
                vector = KnockbackEngine.compute(
                        preAttackerState(attackerId, attackerView, verdict),
                        preVictimState(victimId, victimView),
                        profile, compensationY, ThreadLocalRandom.current(),
                        verdict.fresh() != null && verdict.fresh() && verdict.sprinting());
            }

            long minInterval = settings.feedbackMinIntervalMillis() >= 0
                    ? settings.feedbackMinIntervalMillis()
                    : Math.max(0, victimView.maxNoDamageTicks() / 2 - 1) * 50L;
            boolean gatePassed = !victimHasWire || feedbackGate.tryPreSend(victimId, now, minInterval);

            KnockbackVector shipped = null;
            if (vector != null && gatePassed) {
                shipped = vector; // TRACKER (full stamp); TRACKER_DECAYED handled by the desk record
                tx.planned();
                if (victimHasWire) {
                    tx.preSent(shipped);
                } else {
                    tx.pinned(shipped);
                }
            }

            float hurtYaw = HurtYaw.hurtYaw(
                    attackerView == null ? 0 : positionX(attackerId),
                    attackerView == null ? 0 : positionZ(attackerId),
                    positionX(victimId), positionZ(victimId),
                    domains.domainFor(victimId).lastYaw());
            if (victimHasWire && gatePassed) {
                senders.ship(PacketEvents.getAPI().getPlayerManager().getUser(playerVictim),
                        victimView.entityId(), shipped, hurtYaw, settings.bundleFeedback());
            }
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
                        () -> applyNonPlayer(attackerId, targetId),
                        () -> {});
            }
        }

        private EntityState preAttackerState(UUID attackerId, PlayerView view, SprintVerdict verdict) {
            PositionRing.Sample sample = sessions.positions().latest(attackerId);
            double x = sample != null ? sample.x() : 0;
            double y = sample != null ? sample.y() : 0;
            double z = sample != null ? sample.z() : 0;
            // Attacker velocity/enchant/resistance/grounded are unused by the
            // formula's attacker terms; yaw comes from the connection's last
            // movement packet, enchant is corrected on the authoritative pass.
            return new EntityState(x, y, z, domains.domainFor(attackerId).lastYaw(),
                    0, 0, 0, view.grounded(), verdict.sprinting(), 0, view.knockbackResistance());
        }

        private EntityState preVictimState(UUID victimId, PlayerView view) {
            PositionRing.Sample sample = sessions.positions().latest(victimId);
            double x = sample != null ? sample.x() : 0;
            double y = sample != null ? sample.y() : 0;
            double z = sample != null ? sample.z() : 0;
            return new EntityState(x, y, z, 0,
                    view.motion().vx(), view.motion().vy(), view.motion().vz(),
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
    }

    /* ---------------------------- shared helpers ---------------------------- */

    private SprintVerdict sprintVerdict(UUID attackerId) {
        if (wtapConsultWire.get()) {
            return domains.domainFor(attackerId).sprint().verdictAt(clock.current());
        }
        PlayerView view = sessions.viewOf(attackerId);
        return new SprintVerdict(view != null && view.sprinting(), null, clock.current());
    }

    private Double compensationFor(UUID victimId, PlayerView victimView) {
        if (victimId == null || victimView == null || !snapshot.get().enabled(Feature.LATENCY_COMPENSATION)) {
            return null;
        }
        Double ping = latency.forPlayer(victimId).pingMillis();
        int rtt = ping == null ? 0 : (int) Math.round(ping);
        return CompensationQuery.verticalFor(victimView, rtt, victimView.motion().vy());
    }

    /** The velocity-pre-send suppressor reason, or null when the pre-send is eligible. */
    private String suppressorFor(boolean ocmOwns, KnockbackProfile profile, PlayerView victimView) {
        if (!anticheat.allowVelocityPreSend()) {
            return "anticheat";
        }
        if (ocmOwns) {
            return "ocm";
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
        ReachValidator.Verdict verdict = ReachValidator.validate(
                attackerPos.x(), attackerPos.y() + ReachValidator.EYE_HEIGHT, attackerPos.z(),
                sessions.positions().samplesAround(victimId, instantNanos, 75_000_000L),
                victimPos.x(), victimPos.y(), victimPos.z(),
                reach.maxReach(), reach.leniency());
        return verdict.valid();
    }

    /* ---------------------------- owning-thread appliers ---------------------------- */

    private void applyPlayer(UUID attackerUuid, UUID victimUuid, HitTransaction tx) {
        try {
            Player attacker = Bukkit.getPlayer(attackerUuid);
            if (attacker == null) {
                return;
            }
            if (!scheduling.isOwnedByCurrentRegion(attacker)) {
                retract(victimUuid, tx); // cross-region: logged skip, drop the pending
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

    private void applyNonPlayer(UUID attackerUuid, int targetEntityId) {
        Player attacker = Bukkit.getPlayer(attackerUuid);
        if (attacker == null || !attacker.isOnline() || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Entity entity = lookupEntity(attacker, targetEntityId);
        if (!(entity instanceof Damageable target) || target.isDead()
                || !isStillAttackable(attacker, target) || !isInReach(attacker, target)) {
            return;
        }
        // A non-player LivingEntity is legacy-composed like a player victim (no
        // transaction, so ownership resolves from the attacker); a non-living
        // Damageable takes the retired HitApplier's flat 1.0.
        double amount = target instanceof LivingEntity ? composedAmount(attacker, null) : 1.0;
        target.damage(amount, attacker);
        applyToolWear(attacker);
    }

    private void damageWithSlot(Player attacker, Player victim, UUID victimUuid, HitTransaction tx) {
        me.vexmc.mental.v5.CombatSession session = sessions.sessionFor(victimUuid);
        if (session != null) {
            session.activeInbound(tx);
        }
        try {
            victim.damage(composedAmount(attacker, tx), attacker);
        } finally {
            if (session != null) {
                session.clearActiveInbound();
            }
        }
    }

    private void retract(UUID victimUuid, HitTransaction tx) {
        me.vexmc.mental.v5.CombatSession session = sessions.sessionFor(victimUuid);
        if (session != null) {
            session.desk().withdraw(tx.context().id());
        }
    }

    /**
     * The fast-path damage amount (spec §4.6): the {@link me.vexmc.mental.v5.feature.damage.DamageShaper}
     * legacy composition — weapon base → era Strength/Weakness → crit ×1.5 →
     * Sharpness — off the attacker's live state, or a vanilla-shaped amount when
     * OCM owns the shaping. {@code simulateCrits}/{@code legacyToolDamage} come
     * from the hit-reg settings; {@code old-potion-values} from its live toggle.
     * Owning thread only (the attacker read is region-guarded by the caller).
     */
    private double composedAmount(Player attacker, HitTransaction tx) {
        HitRegSettings settings = settings();
        return shaper.compose(
                attacker, tx == null ? null : tx.context(),
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

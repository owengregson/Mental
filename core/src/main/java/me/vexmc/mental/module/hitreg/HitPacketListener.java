package me.vexmc.mental.module.hitreg;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.api.event.AsyncHitRegisterEvent;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.HitRegSettings;
import me.vexmc.mental.config.KnockbackDelivery;
import me.vexmc.mental.config.KnockbackProfile;
import me.vexmc.mental.config.KnockbackSettings;
import me.vexmc.mental.config.ResistancePolicy;
import me.vexmc.mental.module.knockback.KnockbackEngine;
import me.vexmc.mental.module.knockback.KnockbackHints;
import me.vexmc.mental.module.knockback.KnockbackPipeline;
import me.vexmc.mental.module.knockback.KnockbackVector;
import me.vexmc.mental.module.knockback.VictimMotion;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts {@code INTERACT_ENTITY/ATTACK} on the netty loop.
 *
 * <p>Fast path: validate against tick-frozen snapshots, fire the cancellable
 * {@link AsyncHitRegisterEvent}, cancel the vanilla packet, optionally
 * pre-send feedback, then hand damage to the victim's owning thread. With the
 * fast path off the listener degrades to rate limiting plus the async event
 * hook, leaving the packet for vanilla.</p>
 *
 * <p>The velocity pre-send — the one behavior a movement-prediction anticheat
 * could mispredict — additionally consults the live {@code AnticheatGate};
 * the hurt animation is cosmetic and always eligible.</p>
 */
final class HitPacketListener implements PacketListener {

    private final MentalServices services;
    private final CpsLimiter limiter;
    private final HitApplier applier;
    private final PlayerStateCache stateCache;
    private final HitFeedbackGate feedbackGate;
    private final FeedbackSenders senders;
    private final PositionHistory positionHistory;
    private final KnockbackPipeline pipeline;
    private final KnockbackHints hints;

    HitPacketListener(
            @NotNull MentalServices services,
            @NotNull CpsLimiter limiter,
            @NotNull HitApplier applier,
            @NotNull PlayerStateCache stateCache,
            @NotNull HitFeedbackGate feedbackGate,
            @NotNull FeedbackSenders senders,
            @NotNull PositionHistory positionHistory,
            @NotNull KnockbackPipeline pipeline,
            @NotNull KnockbackHints hints) {
        this.services = services;
        this.limiter = limiter;
        this.applier = applier;
        this.stateCache = stateCache;
        this.feedbackGate = feedbackGate;
        this.senders = senders;
        this.positionHistory = positionHistory;
        this.pipeline = pipeline;
        this.hints = hints;
    }

    @Override
    public void onPacketReceive(@NotNull PacketReceiveEvent event) {
        if (event.isCancelled() || event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        if (!(event.getPlayer() instanceof Player attacker)) {
            return;
        }

        try {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() != InteractAction.ATTACK) {
                return;
            }

            HitRegSettings settings = services.config().hitReg();
            if (!settings.enabled()) {
                return;
            }

            long now = System.currentTimeMillis();
            UUID attackerId = attacker.getUniqueId();
            if (settings.rateLimited() && !limiter.tryAcquire(attackerId, settings.maxCps(), now)) {
                event.setCancelled(true);
                debug(attacker, () -> "rate-limited at " + settings.maxCps() + " cps");
                return;
            }

            int targetId = packet.getEntityId();
            if (targetId < 0) {
                return;
            }

            // Read-only world scan; the owning-thread applier re-resolves and
            // re-validates before anything stateful happens.
            Entity target = SpigotConversionUtil.getEntityById(attacker.getWorld(), targetId);
            if (!(target instanceof Damageable damageable) || damageable.isDead()
                    || !isAttackable(attacker, damageable)) {
                return;
            }

            if (damageable instanceof Player victim
                    && !passesReachValidation(attacker, victim, settings)) {
                event.setCancelled(true);
                return;
            }

            AsyncHitRegisterEvent api = new AsyncHitRegisterEvent(attacker, damageable);
            Bukkit.getPluginManager().callEvent(api);
            if (api.isCancelled()) {
                event.setCancelled(true);
                debug(attacker, () -> "cancelled by AsyncHitRegisterEvent listener");
                return;
            }

            if (!settings.fastPath()) {
                debug(attacker, () -> "passed through to vanilla on " + describe(damageable));
                return;
            }

            event.setCancelled(true);

            if (settings.preSendFeedback() && damageable instanceof Player victim) {
                preSendCombatFeedback(attacker, victim, now, settings);
            }

            // The sprint flag as the ATTACK saw it (tick-frozen snapshot):
            // a faithful client drops its own sprint right after attacking
            // and the sync packet beats the deferred damage to the server —
            // a live read there would deny the era's sprint bonus to every
            // perfectly-timed authoritative hit.
            PlayerStateCache.Snapshot attackerSnap = stateCache.get(attackerId);
            if (attackerSnap != null) {
                services.sprintTracker().stampAttackSprint(attackerId, attackerSnap.sprinting());
            }
            services.scheduling().runOn(
                    damageable,
                    () -> applier.apply(attackerId, targetId),
                    () -> debug(attacker, () -> "target retired before damage application"));
            debug(attacker, () -> "fast-path dispatched on " + describe(damageable));
        } catch (Throwable failure) {
            services.plugin().getLogger().warning(
                    "Error in hit packet listener; allowing packet through: " + failure);
        }
    }

    /**
     * Both guards bias toward not pre-sending — a skipped pre-send just falls
     * back to vanilla's next-tick feedback, never a phantom hit: the cached
     * invulnerability check catches victims already inside their damage
     * window, and the per-victim gate enforces vanilla's once-per-window
     * cadence across attackers and sub-tick bursts.
     */
    private void preSendCombatFeedback(Player attacker, Player victim, long now, HitRegSettings settings) {
        KnockbackSettings knockback = services.config().knockback();
        if (!knockback.enabled()) {
            return;
        }

        PlayerStateCache.Snapshot attackerSnap = stateCache.get(attacker.getUniqueId());
        PlayerStateCache.Snapshot victimSnap = stateCache.get(victim.getUniqueId());
        if (attackerSnap == null || victimSnap == null) {
            return;
        }
        // The victim's profile, frozen by their per-tick snapshot — the same
        // one the authoritative owning-thread path resolves this tick.
        KnockbackProfile profile = victimSnap.profile();
        if (victimSnap.isDamageImmune()) {
            debug(attacker, () -> "pre-send skipped: " + victim.getName() + " still invulnerable");
            return;
        }
        // auto follows the victim's live hurt window MINUS ONE TICK. Vanilla
        // applies a knockback-bearing hit the moment noDamageTicks falls to
        // max/2, so a perfect-cadence combo throws legal hits exactly one
        // half-window apart — a gate equal to that cadence makes every such
        // hit race the boundary on millisecond jitter, and each loser falls
        // back to the authoritative next-tick send computed from POST-landing
        // state (measured on the wire: boundary combo hits then ship the
        // grounded 0.3608 vertical where era servers ship the pre-landing
        // ~0.25 — the floaty-combo signature). The gate's real job is only
        // sub-window spam and same-window multi-attacker bursts.
        long minIntervalMillis = settings.feedbackMinIntervalMillis() >= 0
                ? settings.feedbackMinIntervalMillis()
                : Math.max(0, victimSnap.maxNoDamageTicks() / 2 - 1) * 50L;
        if (!feedbackGate.tryPreSend(victim.getUniqueId(), now, minIntervalMillis)) {
            debug(attacker, () -> "pre-send gated: " + victim.getName()
                    + " inside " + minIntervalMillis + "ms window");
            return;
        }

        Vector velocity = null;
        if (!services.anticheatGate().allowVelocityPreSend()) {
            debug(attacker, () -> "velocity pre-send suppressed by anticheat policy");
        } else if (attackerSnap.ocmOwnsMeleeKnockback()) {
            // OCM computes this hit's knockback on the main thread; a Mental
            // vector pre-sent now would mispredict it.
            debug(attacker, () -> "velocity pre-send skipped: OCM owns this attacker's knockback");
        } else if (profile.resistance() == ResistancePolicy.LEGACY
                && victimSnap.knockbackResistance() > 0.0) {
            // The all-or-nothing roll belongs to the authoritative path; a
            // pre-sent vector the roll then suppresses would be a phantom.
            debug(attacker, () -> "velocity pre-send skipped: legacy resistance roll pending");
        } else {
            // Peek (never consume) sprint freshness — the authoritative
            // owning-thread pass spends it when it adopts this vector.
            boolean freshSprint = attackerSnap.sprinting()
                    && services.sprintTracker().peekFresh(attacker.getUniqueId());
            // The compensation hint is published per tick; whoever computes
            // the hit's final vector consumes it — here, when pre-sending.
            Double victimYOverride = hints.takeYOverride(victim.getUniqueId());
            KnockbackVector vector = KnockbackEngine.compute(
                    attackerSnap.toEntityState(), victimSnap.toEntityState(), profile,
                    victimYOverride, ThreadLocalRandom.current(), freshSprint);
            if (vector != null) {
                // The opt-in later-joiner wire (TRACKER_DECAYED): the packet
                // ships one victim physics tick late. TRACKER ships the full
                // stamp — vanilla's tracker only decayed when the victim's
                // connection slot ran between the hit and the send.
                KnockbackVector shipped = vector;
                if (profile.meleeDelivery() == KnockbackDelivery.TRACKER_DECAYED) {
                    VictimMotion.Motion decayed = VictimMotion.decayOnce(
                            vector.x(), vector.y(), vector.z(),
                            victimSnap.grounded(), victimSnap.gravity());
                    shipped = new KnockbackVector(decayed.vx(), decayed.vy(), decayed.vz());
                }
                velocity = shipped.toBukkit();
                // The authoritative pass adopts this exact delivery and the
                // duplicate end-of-tick packet is suppressed — one wire stamp
                // per hit, like the era servers.
                pipeline.submitPreDelivered(victim, vector, shipped, attacker);
            }
        }

        // One burst to the victim — velocity (when eligible) and hurt land in
        // a single bundle frame on 1.19.4+; the attacker's third-person view
        // is its own connection and needs no bundling.
        float hurtYaw = FeedbackSenders.hurtYaw(
                attackerSnap.x(), attackerSnap.z(), victimSnap.x(), victimSnap.z(), victimSnap.yaw());
        senders.sendVictimBurst(
                victim, victimSnap.entityId(), velocity, hurtYaw, settings.bundleFeedback());
        senders.sendHurt(attacker, victimSnap.entityId(), hurtYaw);
    }

    /**
     * The rewound-reach gate: passes unless every candidate instant — the
     * victim's history around (now − ping − interpolation) plus their live
     * position — puts the hitbox beyond reach + leniency. Bias runs toward
     * allowing: untracked parties, creative attackers, and a detected
     * anticheat (whose job reach is) all skip the check.
     */
    private boolean passesReachValidation(Player attacker, Player victim, HitRegSettings settings) {
        HitRegSettings.ReachValidation reach = settings.reachValidation();
        if (!reach.enabled() || !services.anticheatGate().allowReachValidation()) {
            return true;
        }
        PlayerStateCache.Snapshot attackerSnap = stateCache.get(attacker.getUniqueId());
        PlayerStateCache.Snapshot victimSnap = stateCache.get(victim.getUniqueId());
        if (attackerSnap == null || victimSnap == null || attackerSnap.creative()) {
            return true;
        }

        long rewindMillis = Math.min(
                (long) attackerSnap.pingMillis() + reach.interpolationOffsetMillis(),
                reach.rewindCapMillis());
        long instantNanos = System.nanoTime() - rewindMillis * 1_000_000L;
        // One tick of slack on each side of the rewound instant: the victim
        // may have moved up to a sample boundary in either direction.
        ReachValidator.Verdict verdict = ReachValidator.validate(
                attackerSnap.x(), attackerSnap.y() + ReachValidator.EYE_HEIGHT, attackerSnap.z(),
                positionHistory.samplesAround(victim.getUniqueId(), instantNanos, 75_000_000L),
                victimSnap.x(), victimSnap.y(), victimSnap.z(),
                Math.max(reach.maxReach(), attackerSnap.attackReach()), reach.leniency());
        if (!verdict.valid()) {
            debug(attacker, () -> String.format(Locale.ROOT,
                    "hit on %s dropped by reach validation: %.2f blocks at every candidate"
                            + " (rewind %dms)", victim.getName(), verdict.bestDistance(), rewindMillis));
        }
        return verdict.valid();
    }

    private static boolean isAttackable(Player attacker, Damageable target) {
        if (attacker.getWorld() != target.getWorld()) {
            return false;
        }
        if (target instanceof Player victim
                && (victim.getGameMode() == GameMode.CREATIVE || !attacker.getWorld().getPVP())) {
            return false;
        }
        return attacker.getGameMode() != GameMode.SPECTATOR;
    }

    private static String describe(Damageable target) {
        return target instanceof Player player
                ? player.getName()
                : target.getType().toString().toLowerCase(Locale.ROOT);
    }

    private void debug(Player attacker, Supplier<String> message) {
        services.debug().log(
                DebugCategory.HITREG,
                () -> "[" + attacker.getName() + "] " + message.get());
    }
}

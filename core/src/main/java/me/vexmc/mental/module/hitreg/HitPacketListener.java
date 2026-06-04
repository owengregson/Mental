package me.vexmc.mental.module.hitreg;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.UUID;
import java.util.function.Supplier;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.api.event.AsyncHitRegisterEvent;
import me.vexmc.mental.config.HitRegSettings;
import me.vexmc.mental.config.KnockbackSettings;
import me.vexmc.mental.module.knockback.KnockbackEngine;
import me.vexmc.mental.module.knockback.KnockbackVector;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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

    HitPacketListener(
            @NotNull MentalServices services,
            @NotNull CpsLimiter limiter,
            @NotNull HitApplier applier,
            @NotNull PlayerStateCache stateCache,
            @NotNull HitFeedbackGate feedbackGate,
            @NotNull FeedbackSenders senders) {
        this.services = services;
        this.limiter = limiter;
        this.applier = applier;
        this.stateCache = stateCache;
        this.feedbackGate = feedbackGate;
        this.senders = senders;
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
                preSendCombatFeedback(attacker, victim, now, settings.feedbackMinIntervalMillis());
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
    private void preSendCombatFeedback(Player attacker, Player victim, long now, long minIntervalMillis) {
        KnockbackSettings knockback = services.config().knockback();
        if (!knockback.enabled()) {
            return;
        }

        PlayerStateCache.Snapshot attackerSnap = stateCache.get(attacker.getUniqueId());
        PlayerStateCache.Snapshot victimSnap = stateCache.get(victim.getUniqueId());
        if (attackerSnap == null || victimSnap == null) {
            return;
        }
        if (victimSnap.isDamageImmune()) {
            debug(attacker, () -> "pre-send skipped: " + victim.getName() + " still invulnerable");
            return;
        }
        if (!feedbackGate.tryPreSend(victim.getUniqueId(), now, minIntervalMillis)) {
            debug(attacker, () -> "pre-send gated: " + victim.getName()
                    + " inside " + minIntervalMillis + "ms window");
            return;
        }

        if (services.anticheatGate().allowVelocityPreSend()) {
            KnockbackVector vector = KnockbackEngine.compute(
                    attackerSnap.toEntityState(), victimSnap.toEntityState(), knockback, null);
            senders.sendVelocity(victim, victimSnap.entityId(), vector.toBukkit());
        } else {
            debug(attacker, () -> "velocity pre-send suppressed by anticheat policy");
        }

        float hurtYaw = FeedbackSenders.hurtYaw(
                attackerSnap.x(), attackerSnap.z(), victimSnap.x(), victimSnap.z(), victimSnap.yaw());
        senders.sendHurt(victim, victimSnap.entityId(), hurtYaw);
        senders.sendHurt(attacker, victimSnap.entityId(), hurtYaw);
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
                : target.getType().toString().toLowerCase(java.util.Locale.ROOT);
    }

    private void debug(Player attacker, Supplier<String> message) {
        services.debug().log(
                me.vexmc.mental.common.debug.DebugCategory.HITREG,
                () -> "[" + attacker.getName() + "] " + message.get());
    }
}

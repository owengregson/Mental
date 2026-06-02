package me.vexmc.strikesync.module.hitreg;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.vexmc.strikesync.api.event.AsyncHitRegisterEvent;
import me.vexmc.strikesync.config.HitRegSettings;
import me.vexmc.strikesync.config.KnockbackSettings;
import me.vexmc.strikesync.core.StrikeSyncService;
import me.vexmc.strikesync.module.knockback.KnockbackEngine;
import me.vexmc.strikesync.module.knockback.KnockbackVector;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The PacketEvents listener that intercepts {@code INTERACT_ENTITY} packets
 * on the netty event loop and either applies a fast plugin-driven hit
 * registration path or lets vanilla handle the hit, depending on configuration.
 *
 * <h2>Two paths</h2>
 * <ul>
 *   <li><strong>Fast path</strong> ({@code fast-path.enabled: true}, default):
 *     <ol>
 *       <li>Validate (action, CPS, target reachable).</li>
 *       <li>Fire {@link AsyncHitRegisterEvent} (truly async).</li>
 *       <li>Cancel the original packet so vanilla never sees it.</li>
 *       <li>Optionally pre-send the velocity packet (if
 *           {@code pre-send-velocity: true}) using cached state — this is the
 *           headline latency win, saving the next-tick + tracker-pulse delay.</li>
 *       <li>Schedule {@link HitApplier} on the target's owning thread to apply
 *           damage and trigger Bukkit's normal event chain.</li>
 *     </ol>
 *   </li>
 *   <li><strong>Slow path</strong> ({@code fast-path.enabled: false}):
 *     same validation + event fire, but the packet is left for vanilla. The
 *     plugin's role degrades to "rate-limit + cancellable async event hook."
 *   </li>
 * </ul>
 *
 * <h2>What we do NOT replicate from {@code Player#attack}</h2>
 * Sweep attacks, weapon durability damage, statistics, advancement triggers,
 * hunger cost. These are deliberate omissions — they don't matter for the
 * 1.8 PvP target audience, and re-implementing them via NMS reflection would
 * compromise the cross-version stability the plugin aims for.
 */
final class HitPacketListener implements PacketListener {

    private final StrikeSyncService service;
    private final CpsLimiter limiter;
    private final HitDispatcher dispatcher;
    private final HitApplier applier;
    private final PlayerStateCache stateCache;
    private final HitFeedbackGate feedbackGate;

    HitPacketListener(StrikeSyncService service,
                      CpsLimiter limiter,
                      HitDispatcher dispatcher,
                      HitApplier applier,
                      PlayerStateCache stateCache,
                      HitFeedbackGate feedbackGate) {
        this.service = service;
        this.limiter = limiter;
        this.dispatcher = dispatcher;
        this.applier = applier;
        this.stateCache = stateCache;
        this.feedbackGate = feedbackGate;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.isCancelled()) return;
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;

        Player attacker = (Player) event.getPlayer();
        if (attacker == null) return;

        try {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() != InteractAction.ATTACK) {
                return; // INTERACT / INTERACT_AT — let vanilla handle.
            }

            HitRegSettings settings = service.config().hitReg();
            if (!settings.enabled()) return;

            long now = System.currentTimeMillis();
            UUID attackerId = attacker.getUniqueId();
            if (settings.rateLimited()
                    && !limiter.tryAcquire(attackerId, settings.maxCps(), now)) {
                event.setCancelled(true);
                debug(attacker, () -> "rate-limited at " + settings.maxCps() + " cps");
                return;
            }

            int targetId = packet.getEntityId();
            if (targetId < 0) return;

            // SpigotConversionUtil.getEntityById is documented "unsafe" for cross-thread use,
            // but in practice it performs a read-only world entity scan and is fine for
            // identifying the target. The main-thread HitApplier will re-resolve and
            // re-validate before doing anything stateful.
            Entity target = SpigotConversionUtil.getEntityById(attacker.getWorld(), targetId);
            if (!(target instanceof Damageable damageable)) return;
            if (damageable.isDead()) return;
            if (!isAttackable(attacker, damageable)) return;

            // Fire the cancellable async event before doing anything that mutates state.
            AsyncHitRegisterEvent api = new AsyncHitRegisterEvent(attacker, damageable);
            Bukkit.getPluginManager().callEvent(api);
            if (api.isCancelled()) {
                event.setCancelled(true);
                debug(attacker, () -> "cancelled by AsyncHitRegisterEvent listener");
                return;
            }

            if (settings.fastPath()) {
                event.setCancelled(true);

                // Headline latency win: emit the velocity AND hurt-animation
                // packets now, from the netty thread. Skips the next-tick wait
                // and the tracker pulse (~50–100 ms saved on both feedback
                // signals).
                if (settings.preSendFeedback() && damageable instanceof Player victim) {
                    preSendCombatFeedback(attacker, victim, now, settings.feedbackMinIntervalMs());
                }

                // Damage is still applied on the target's owning thread for correctness.
                final int dispatchedTargetId = targetId;
                dispatcher.dispatch(damageable, () -> applier.apply(attackerId, dispatchedTargetId));

                debug(attacker, () -> "fast-path dispatched on " + describe(damageable));
            } else {
                debug(attacker, () -> "passed through to vanilla on " + describe(damageable));
            }
        } catch (Throwable t) {
            // Never throw from a packet handler — log and let vanilla handle.
            service.log().warn("Error in HitPacketListener; allowing packet through", t);
        }
    }

    /* ----------------------------- helpers ----------------------------- */

    /**
     * Compute the knockback vector from cached state and ship both the
     * velocity packet and the hurt-animation packet directly from the netty
     * thread. Skipped if either party is missing from the cache (just-joined
     * player, etc.) — in that case the main-thread path will still produce
     * the correct knockback + animation at the next tracker tick.
     *
     * <h2>Recipient choices</h2>
     * <ul>
     *   <li>Velocity: sent to the <em>victim</em> only. The victim's client
     *       drives their own physics; nearby viewers receive the corrected
     *       position from the next entity-tracker pulse anyway.</li>
     *   <li>Hurt animation: sent to <em>both</em> the victim (so they see the
     *       screen-shake / red-flash immediately) and the attacker (so they
     *       see the target flinch immediately, which is the dominant
     *       hit-confirmation cue in PvP). Other nearby viewers see it on the
     *       next tracker pulse, same as today.</li>
     * </ul>
     */
    private void preSendCombatFeedback(Player attacker, Player victim, long now, long minIntervalMs) {
        KnockbackSettings ks = service.config().knockback();
        if (!ks.enabled()) return;

        PlayerStateCache.Snapshot attSnap = stateCache.get(attacker.getUniqueId());
        PlayerStateCache.Snapshot vicSnap = stateCache.get(victim.getUniqueId());
        if (attSnap == null || vicSnap == null) return;

        // Respect the victim's damage-invulnerability window. Vanilla (and 1.8)
        // only apply a knockback-bearing hit about once per window — a hit
        // inside it deals no knockback unless it out-damages the previous one.
        // The pre-send runs on the netty thread BEFORE main-thread damage, so
        // without this it would ship a velocity packet on EVERY spam-click and
        // the victim's client would re-launch each time ("spam → fly"). Two
        // guards, both biased toward NOT pre-sending (a skipped pre-send simply
        // falls back to vanilla's next-tick velocity — never a phantom hit):
        //   1. cached invulnerability: the victim is already immune (from this
        //      attacker, another attacker, or e.g. fall damage) as of last tick;
        //   2. per-victim rate gate: at most one pre-send per window, race-free
        //      across multiple attackers and sub-tick click bursts.
        if (vicSnap.isDamageImmune()) {
            debug(attacker, () -> "pre-send skipped: " + victim.getName() + " still invulnerable");
            return;
        }
        if (!feedbackGate.tryPreSend(victim.getUniqueId(), now, minIntervalMs)) {
            debug(attacker, () -> "pre-send gated: " + victim.getName()
                    + " inside " + minIntervalMs + "ms invulnerability window");
            return;
        }

        KnockbackVector vector = KnockbackEngine.computeFromCache(attSnap, vicSnap, ks, null);
        AsyncVelocitySender.send(victim, vicSnap.entityId(), vector.toBukkit());

        float hurtYaw = AsyncHurtSender.computeHurtYaw(
                attSnap.x(), attSnap.z(),
                vicSnap.x(), vicSnap.z(),
                vicSnap.yaw());
        AsyncHurtSender.send(victim, vicSnap.entityId(), hurtYaw);
        AsyncHurtSender.send(attacker, vicSnap.entityId(), hurtYaw);
    }

    private static boolean isAttackable(Player attacker, Damageable target) {
        if (attacker.getWorld() != target.getWorld()) return false;
        if (target instanceof Player && !pvpEnabled(attacker)) return false;
        if (target instanceof Player tp && tp.getGameMode() == GameMode.CREATIVE) return false;
        return attacker.getGameMode() != GameMode.SPECTATOR;
    }

    private static boolean pvpEnabled(Player attacker) {
        Boolean pvp = attacker.getWorld().getGameRuleValue(GameRules.PVP);
        return pvp == null || pvp; // game rule defaults to true; treat null as enabled.
    }

    private static String describe(Damageable target) {
        if (target instanceof Player p) return p.getName();
        return target.getType().toString().toLowerCase();
    }

    private void debug(Player attacker, Supplier<String> message) {
        service.log().debug(() -> "hitreg[" + attacker.getName() + "] " + message.get());
    }
}

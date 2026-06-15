package me.vexmc.mental.module.hitreg;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.User;
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
import me.vexmc.mental.module.knockback.SprintTracker;
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

            // Resolve the target WITHOUT touching a live entity on this thread.
            // This runs on PacketEvents' netty loop, where reading entity state
            // is undefined on Paper and THROWS on Folia: getGameMode(),
            // getName(), getEntities() and SpigotConversionUtil#getEntityById
            // all trip Folia's "accessing entity state off owning region's
            // thread" / "asynchronous getEntities" checks. A thrown read here
            // was silently caught below and the attack passed through to
            // vanilla — every player-vs-player melee on Folia got VANILLA
            // knockback instead of Mental's. Player victims (the regionised
            // case Mental exists for) resolve through the frozen entity-id
            // index; the owning-thread applier re-resolves and re-validates.
            UUID victimId = stateCache.playerIdByEntityId(targetId);
            Player playerVictim = null;
            Damageable damageable;
            if (victimId != null) {
                playerVictim = Bukkit.getPlayer(victimId);
                if (playerVictim == null) {
                    return;
                }
                damageable = playerVictim;
            } else {
                // Not a tracked player. A non-player entity can't be resolved
                // off-region on Folia, so the hit falls through to vanilla there
                // (mob combat, armour stands and item frames keep working); on
                // Paper the live scan is tolerated and the legacy path runs.
                if (services.capabilities().folia()) {
                    return;
                }
                Entity target = SpigotConversionUtil.getEntityById(attacker.getWorld(), targetId);
                if (!(target instanceof Damageable resolved) || resolved.isDead()
                        || !isAttackable(attacker, resolved)) {
                    return;
                }
                damageable = resolved;
                if (resolved instanceof Player resolvedPlayer) {
                    playerVictim = resolvedPlayer;
                }
            }

            // Player-victim attackability from frozen + region-safe reads only:
            // the snapshot's creative flag and the world PvP toggle (a world
            // config read, safe off-region — verified on Folia). Same-world is
            // implied (a melee packet can only target an entity the attacker
            // sees) and re-checked authoritatively in the applier; spectators
            // can't send ATTACK, and the applier guards that case regardless.
            if (playerVictim != null && victimId != null) {
                PlayerStateCache.Snapshot frozen = stateCache.get(victimId);
                if (frozen == null || frozen.creative() || !attacker.getWorld().getPVP()) {
                    return;
                }
            }

            if (playerVictim != null
                    && !passesReachValidation(attacker, playerVictim, settings)) {
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

            // The sprint answer this ATTACK gets. With wtap-registration on,
            // the wire view: the attacker's own STOP/START packets replayed
            // in arrival order on this very thread — the era queue's
            // contract, under which a w-tap that beat this packet to the
            // socket counts however fast the tap was, and an s-tap's release
            // denies the bonus the way the era denied it. Without it (module
            // off, or synthetic players who send no packets), the
            // tick-frozen snapshot — sprint state as of the boundary, up to
            // a tick older than the era read.
            PlayerStateCache.Snapshot attackerSnap = stateCache.get(attackerId);
            SprintTracker.WireVerdict wire = services.sprintTracker().peekWire(attackerId);
            boolean attackerSprinting = wire != null
                    ? wire.sprinting()
                    : attackerSnap != null && attackerSnap.sprinting();
            boolean bukkitFresh = services.sprintTracker().peekFresh(attackerId);
            // The wire's freshness still honors a Bukkit-armed toggle: a
            // plugin's own setSprinting grant never crosses the wire.
            Boolean wireFresh = wire != null ? wire.fresh() || bukkitFresh : null;
            boolean freshSprint = attackerSprinting
                    && (wireFresh != null ? wireFresh : bukkitFresh);
            if (wire != null && attackerSnap != null
                    && wire.sprinting() != attackerSnap.sprinting()) {
                debug(attacker, () -> "wire sprint " + wire.sprinting()
                        + " overrides tick-frozen " + attackerSnap.sprinting()
                        + " — in-order w-tap/s-tap registration");
            }

            if (settings.preSendFeedback() && playerVictim != null) {
                preSendCombatFeedback(attacker, attackerSnap, playerVictim, now, settings,
                        attackerSprinting, freshSprint);
            }

            // Stamped for the deferred damage pass: a faithful client drops
            // its own sprint right after attacking and the sync packet beats
            // the deferred damage to the server — a live read there would
            // deny the era's sprint bonus to every perfectly-timed
            // authoritative hit. The wire-resolved freshness rides along;
            // null keeps the authoritative pass on its Bukkit ledger.
            if (attackerSnap != null || wire != null) {
                services.sprintTracker().stampAttackVerdict(
                        attackerId, attackerSprinting, wireFresh, System.nanoTime());
            }
            // Damage runs on the region that owns the TARGET. Player victims
            // resolve by UUID there (Bukkit#getPlayer is region-agnostic), so
            // the applier never scans world entities off-region; non-player
            // targets (Paper only here) keep the entity-id re-resolution.
            if (playerVictim != null) {
                UUID victimUuid = victimId != null ? victimId : playerVictim.getUniqueId();
                services.scheduling().runOn(
                        playerVictim,
                        () -> applier.applyPlayer(attackerId, victimUuid),
                        () -> debug(attacker, () -> "target retired before damage application"));
            } else {
                services.scheduling().runOn(
                        damageable,
                        () -> applier.apply(attackerId, targetId),
                        () -> debug(attacker, () -> "target retired before damage application"));
            }
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
    private void preSendCombatFeedback(Player attacker, PlayerStateCache.Snapshot attackerSnap,
            Player victim, long now, HitRegSettings settings,
            boolean attackerSprinting, boolean freshSprint) {
        KnockbackSettings knockback = services.config().knockback();
        if (!knockback.enabled()) {
            return;
        }

        PlayerStateCache.Snapshot victimSnap = stateCache.get(victim.getUniqueId());
        if (attackerSnap == null || victimSnap == null) {
            return;
        }
        // No netty channel (in-process bots, synthetic players) means no
        // burst can ship and nothing may be accounted as wire-delivered —
        // but the registration-time compute still reads the era's in-order
        // processing moment, so its VECTOR is pinned for the authoritative
        // pass to adopt; it ships once, through the normal velocity event.
        boolean wired = senders.hasConnection(victim);
        // The victim's profile, frozen by their per-tick snapshot — the same
        // one the authoritative owning-thread path resolves this tick.
        KnockbackProfile profile = victimSnap.profile();
        if (victimSnap.isDamageImmune()) {
            debug(attacker, () -> "pre-send skipped: " + safeName(victim) + " still invulnerable");
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
        if (wired && !feedbackGate.tryPreSend(victim.getUniqueId(), now, minIntervalMillis)) {
            // The gate paces the WIRE burst only; a pinned (connectionless)
            // hit sends nothing here, and the authoritative pass already
            // owns its immunity pacing.
            debug(attacker, () -> "pre-send gated: " + safeName(victim)
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
            // Sprint and freshness arrive resolved from registration (the
            // wire view when the wtap module is on; peeked, never consumed —
            // the authoritative owning-thread pass spends the ledgers when
            // it adopts this vector).
            // The compensation hint is published per tick; whoever computes
            // the hit's final vector consumes it — here, when pre-sending.
            Double victimYOverride = hints.takeYOverride(victim.getUniqueId());
            KnockbackVector vector = KnockbackEngine.compute(
                    attackerSnap.toEntityState(attackerSprinting), victimSnap.toEntityState(),
                    profile, victimYOverride, ThreadLocalRandom.current(), freshSprint);
            if (vector != null) {
                // The opt-in later-joiner wire (TRACKER_DECAYED): the packet
                // ships one victim physics tick late. TRACKER ships the full
                // stamp — vanilla's tracker only decayed when the victim's
                // connection slot ran between the hit and the send.
                KnockbackVector shipped = vector;
                if (profile.meleeDelivery() == KnockbackDelivery.TRACKER_DECAYED) {
                    VictimMotion.Motion decayed = VictimMotion.decayOnce(
                            vector.x(), vector.y(), vector.z(),
                            victimSnap.grounded(), victimSnap.groundSlipperiness(),
                            victimSnap.gravity());
                    shipped = new KnockbackVector(decayed.vx(), decayed.vy(), decayed.vz());
                }
                if (wired) {
                    velocity = shipped.toBukkit();
                    // The authoritative pass adopts this exact delivery and
                    // the duplicate end-of-tick packet is suppressed — one
                    // wire stamp per hit, like the era servers.
                    pipeline.submitPreDelivered(victim, vector, shipped, attacker);
                } else {
                    // Nothing was (or could be) sent: pin the era-moment
                    // values for the authoritative pass and let the normal
                    // velocity event ship them once, at vanilla cadence.
                    pipeline.submitPinned(victim, vector, shipped, attacker);
                    debug(attacker, () -> "pre-send pinned: " + safeName(victim)
                            + " has no connection — registration vector ships authoritatively");
                }
            }
        }

        // One burst to the victim — velocity (when eligible) and hurt land in
        // a single bundle frame on 1.19.4+; the attacker's third-person view
        // is its own connection and needs no bundling. A connectionless
        // victim gets no burst, but the attacker's flinch still pre-sends.
        float hurtYaw = FeedbackSenders.hurtYaw(
                attackerSnap.x(), attackerSnap.z(), victimSnap.x(), victimSnap.z(), victimSnap.yaw());
        if (wired) {
            senders.sendVictimBurst(
                    victim, victimSnap.entityId(), velocity, hurtYaw, settings.bundleFeedback());
        }
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
                            + " (rewind %dms)", safeName(victim), verdict.bestDistance(), rewindMillis));
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
                ? safeName(player)
                : target.getType().toString().toLowerCase(Locale.ROOT);
    }

    /**
     * A player's name resolved without touching the live entity: {@code
     * Player#getName()} reads {@code getHandle()} and throws off the owning
     * region thread on Folia, so debug call sites on the netty loop read the
     * cached PacketEvents username instead (and fall back to the UUID).
     */
    private static String safeName(@NotNull Player player) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        String name = user != null ? user.getName() : null;
        return name != null ? name : player.getUniqueId().toString();
    }

    private void debug(Player attacker, Supplier<String> message) {
        services.debug().log(
                DebugCategory.HITREG,
                () -> "[" + safeName(attacker) + "] " + message.get());
    }
}

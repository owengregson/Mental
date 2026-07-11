package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import me.vexmc.mental.kernel.fx.IndicatorBallistics;
import me.vexmc.mental.kernel.fx.IndicatorPlacement;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.feature.damage.DamageShaper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Spawns one damage indicator per landed melee at EDBEE MONITOR — the same
 * once-per-hit seam as {@link HitFeedbackListener}, on the victim's region
 * thread. Unlike the rest of the FEEDBACK family this plays to ONE user: the
 * indicator is an attacker-client-only packet armor stand — no other client
 * ever receives a byte of it, and no server entity exists behind it.
 *
 * <h2>Frozen ground truth</h2>
 * The landing plane is scanned HERE, at spawn time — the one moment block reads
 * are region-legal — from the spawn column downward (≤{@value #GROUND_SCAN_DEPTH}
 * blocks, first solid top wins; a shaft with no floor freezes a
 * {@value #GROUND_SCAN_DEPTH}-block fallback). The driver's per-tick step then
 * performs ZERO world reads: the indicator falls under pure kernel ballistics
 * against that frozen plane.
 *
 * <h2>Variant</h2>
 * The crit template fires on the era crit posture ({@link DamageShaper}'s
 * falling-attacker read) OR on damage at/above the configured heart threshold —
 * a big hit reads as a crit even when the posture missed it.
 *
 * <h2>Same-tick aggregation (the plugin-bonus fold)</h2>
 * Enchantment plugins deal their bonus as a SECOND {@code victim.damage(bonus,
 * attacker)} inside the first hit's aftermath — a second EDBEE for the same
 * (attacker, victim) pair in the same tick, a multiplicity vanilla's
 * no-damage-ticks never produce on their own. Each such event is folded into
 * the tick's remembered spawn through the attacker's {@link IndicatorMergeBook}:
 * the just-spawned stand is destroyed and a replacement carrying the SUMMED
 * damage ships in the same bundle, at the same ring position and ballistic
 * phase, against the same frozen ground plane (zero fresh world reads). The
 * fold accepts the two common shapes — a second {@code ENTITY_ATTACK} (the
 * {@code damage(amount, attacker)} API) and a {@code CUSTOM} carrying a Player
 * damager (the modern {@code DamageSource} API) — but a {@code CUSTOM} event
 * with no same-pair-same-tick spawn to fold into is ignored outright, so
 * plugin-authored ambient damage never invents a phantom indicator.
 */
public final class DamageIndicatorsListener implements Listener {

    /**
     * The text pops off mid-chest — victim feet plus this, in blocks (the
     * hit-feedback posture). Package-visible so the heal-indicator path
     * ({@link HealIndicators}) pops its text off the same chest offset.
     */
    static final double CHEST_OFFSET = 1.2;

    /** How many blocks below the spawn the ground scan looks before freezing the fallback plane. */
    private static final int GROUND_SCAN_DEPTH = 6;

    private final DamageIndicatorsSettings settings;
    private final ConcurrentHashMap<UUID, IndicatorDriver> drivers;
    private final Scheduling scheduling;
    private final TickClock clock;
    private final PlayerManager playerManager;
    private final FeedbackTrace trace;
    private final Logger logger;

    /**
     * The F4 last-hit attribution stamper, or null when heal indicators are off
     * (heal-text blank) — a null-check no-op then, so the zero-touch contract holds.
     * Stamped on every qualifying EDBEE (Player attacker+victim, {@code finalDamage
     * > 0}), before the sendability early-returns, so a clientless attacker is still
     * recorded as the healer.
     */
    private final HealIndicators heal;

    private final IndicatorBallistics.Params params;
    private final double critThresholdDamage; // half-hearts, precomputed from the hearts knob
    private final boolean modernSpawn; // server 1.19+: the unified SPAWN_ENTITY packet
    private final boolean bundleSupported; // server 1.19.4+: spawn+metadata land in one client frame

    private final AtomicBoolean noEntityIdWarned = new AtomicBoolean(false);

    public DamageIndicatorsListener(
            DamageIndicatorsSettings settings, ConcurrentHashMap<UUID, IndicatorDriver> drivers,
            Scheduling scheduling, TickClock clock, boolean modernSpawn, boolean bundleSupported,
            FeedbackTrace trace, Logger logger, HealIndicators heal) {
        this.settings = settings;
        this.drivers = drivers;
        this.scheduling = scheduling;
        this.clock = clock;
        this.playerManager = PacketEvents.getAPI().getPlayerManager();
        this.trace = trace;
        this.logger = logger;
        this.heal = heal;
        this.params = new IndicatorBallistics.Params(
                settings.launchVertical(), settings.launchOutward(), settings.gravity(), settings.drag());
        this.critThresholdDamage = settings.critThresholdHearts() * 2.0;
        this.modernSpawn = modernSpawn;
        this.bundleSupported = bundleSupported;
    }

    /**
     * Spawns one indicator for this hit — variant, text, ring placement, frozen
     * ground plane, a client-only entity id, the spawn+metadata ship, and the
     * hand-off to the attacker's driver — journaling the decision throughout.
     * A same-pair-same-tick event (an enchantment plugin's bonus damage) folds
     * into the tick's remembered stand through {@link #respawnMerged} instead
     * of spawning a second one.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        boolean melee = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK;
        if (!melee && event.getCause() != EntityDamageEvent.DamageCause.CUSTOM) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        double finalDamage = event.getFinalDamage();
        if (finalDamage <= 0.0) {
            return;
        }

        // Stamp the last-hit attribution for the heal-indicator path (F4) — identity
        // only, so a clientless attacker (no PE user) is still recorded as the healer.
        // Both the melee and the plugin-bonus CUSTOM fold qualify; stamped BEFORE the
        // sendability early-returns for exactly that reason. The reference is null when
        // heal indicators are off (zero-touch no-op).
        if (heal != null) {
            heal.recordHit(victim.getUniqueId(), attacker.getUniqueId(), clock.current().value());
        }

        User user = playerManager.getUser(attacker);
        if (user == null) {
            if (melee) {
                // Synthetic / disconnecting attacker, in-process bot — no client to draw on.
                trace.record(new FeedbackTrace.Entry(
                        "damage-indicators", attacker.getUniqueId(), victim.getUniqueId(),
                        "UNSENDABLE", "attacker has no PacketEvents user"));
            }
            return;
        }

        // The merge target rides the attacker's driver. A melee hit creates the
        // driver on demand (it is about to adopt a stand either way); a CUSTOM
        // event only ever FOLDS into an existing same-tick spawn, so a missing
        // driver means there is nothing to fold — never a phantom indicator for
        // plugin-authored damage that isn't a melee bonus.
        IndicatorDriver driver = melee
                ? drivers.computeIfAbsent(
                        attacker.getUniqueId(), id -> new IndicatorDriver(scheduling, attacker, user, params))
                : drivers.get(attacker.getUniqueId());
        if (driver == null) {
            return;
        }

        long tick = clock.current().value();
        // The crit posture is a property of the SWING, not the remaining health, so
        // it is decided on the RAW finalDamage — a killing overkill still reads crit.
        boolean crit = DamageShaper.isLegacyCritical(attacker) || finalDamage >= critThresholdDamage;

        // Overkill clamp (F3): a killing hit only subtracts the victim's pre-hit red
        // hearts, not the full swing. getHealth() at EDBEE MONITOR is that pre-hit pool
        // (the event fires before the subtraction), so the RENDERED and MERGE-FOLDED
        // amount is the health actually lost. A victim already at 0 (a same-tick death
        // race) loses nothing visible: skip the stand entirely, but keep the decision
        // in the trace so the seam still reports what happened.
        double displayed = displayedDamage(finalDamage, victim.getHealth());
        if (displayed <= 0.0) {
            trace.record(new FeedbackTrace.Entry(
                    "damage-indicators", attacker.getUniqueId(), victim.getUniqueId(),
                    crit ? "CRIT" : "NORMAL", "dmg=" + finalDamage + " shown=0"));
            return;
        }

        IndicatorMergeBook.Merged merged = driver.merges().merge(
                victim.getUniqueId(), tick, displayed, crit, critThresholdDamage);
        if (merged != null) {
            respawnMerged(attacker, victim, user, driver, merged, tick, finalDamage, displayed);
            return;
        }
        if (!melee) {
            return; // a CUSTOM event with no same-pair-same-tick spawn — not a hit of its own
        }

        String rendered = IndicatorText.render(crit ? settings.critText() : settings.text(), displayed);
        Component name = LegacyComponentSerializer.legacyAmpersand().deserialize(rendered);

        Location victimLoc = victim.getLocation();
        Location attackerLoc = attacker.getLocation();
        IndicatorPlacement.Spawn spawn = IndicatorPlacement.place(
                victimLoc.getX(), victimLoc.getY(), victimLoc.getZ(),
                attackerLoc.getX(), attackerLoc.getZ(),
                settings.ringRadius(), CHEST_OFFSET, settings.heightJitter(),
                ThreadLocalRandom.current());
        double groundY = scanGround(victim.getWorld(), spawn);

        int entityId;
        try {
            // A client-only id from the server's own entity counter — guaranteed
            // never to collide with a real entity the attacker's client tracks.
            entityId = SpigotReflectionUtil.generateEntityId();
        } catch (IllegalStateException noId) {
            if (noEntityIdWarned.compareAndSet(false, true)) {
                logger.warning("damage-indicators: could not generate a client-only entity id"
                        + " on this server — indicators are skipped");
            }
            trace.record(new FeedbackTrace.Entry(
                    "damage-indicators", attacker.getUniqueId(), victim.getUniqueId(),
                    "ID_UNAVAILABLE", "generateEntityId() failed"));
            return;
        }

        List<PacketWrapper<?>> packets = IndicatorStandPackets.spawn(
                entityId, UUID.randomUUID(), spawn, name, user.getClientVersion(), modernSpawn);
        ship(user, packets, bundleSupported);

        driver.add(entityId, IndicatorBallistics.launch(spawn, params), groundY, settings.lifetimeTicks());
        driver.merges().remember(victim.getUniqueId(), tick, entityId, displayed, crit, spawn, groundY);

        trace.record(new FeedbackTrace.Entry(
                "damage-indicators", attacker.getUniqueId(), victim.getUniqueId(),
                crit ? "CRIT" : "NORMAL",
                "y=" + spawn.y() + " ttl=" + settings.lifetimeTicks()
                        + " dmg=" + finalDamage + shownSuffix(finalDamage, displayed)));
    }

    /**
     * Ships the fold: the remembered stand's destroy and the replacement's
     * spawn+metadata ride ONE bundle/flush, so the attacker's client never
     * renders a frame with zero — or two — indicators for this hit. The
     * replacement reuses the first spawn's frozen geometry (ring position,
     * launch bearing, ground plane), so the merge path performs ZERO world
     * reads; only the presentation changes: the summed damage rendered through
     * the OR-ed crit's template. Should the entity id ever be unavailable, the
     * original stand simply stays — an under-reporting indicator beats a
     * vanished one.
     */
    private void respawnMerged(
            Player attacker, Player victim, User user, IndicatorDriver driver,
            IndicatorMergeBook.Merged merged, long tick, double eventDamageRaw, double eventDisplayed) {
        int entityId;
        try {
            entityId = SpigotReflectionUtil.generateEntityId();
        } catch (IllegalStateException noId) {
            if (noEntityIdWarned.compareAndSet(false, true)) {
                logger.warning("damage-indicators: could not generate a client-only entity id"
                        + " on this server — indicators are skipped");
            }
            trace.record(new FeedbackTrace.Entry(
                    "damage-indicators", attacker.getUniqueId(), victim.getUniqueId(),
                    "ID_UNAVAILABLE", "generateEntityId() failed"));
            return;
        }

        String rendered = IndicatorText.render(
                merged.crit() ? settings.critText() : settings.text(), merged.totalDamage());
        Component name = LegacyComponentSerializer.legacyAmpersand().deserialize(rendered);

        List<PacketWrapper<?>> packets = new ArrayList<>(3);
        packets.add(IndicatorStandPackets.destroy(merged.priorEntityId()));
        packets.addAll(IndicatorStandPackets.spawn(
                entityId, UUID.randomUUID(), merged.spawn(), name, user.getClientVersion(), modernSpawn));
        ship(user, packets, bundleSupported);

        driver.replace(
                merged.priorEntityId(), entityId,
                IndicatorBallistics.launch(merged.spawn(), params), merged.groundY(), settings.lifetimeTicks());
        driver.merges().remember(
                victim.getUniqueId(), tick, entityId,
                merged.totalDamage(), merged.crit(), merged.spawn(), merged.groundY());

        trace.record(new FeedbackTrace.Entry(
                "damage-indicators", attacker.getUniqueId(), victim.getUniqueId(),
                merged.crit() ? "CRIT" : "NORMAL",
                "y=" + merged.spawn().y() + " ttl=" + settings.lifetimeTicks()
                        + " dmg=" + eventDamageRaw + shownSuffix(eventDamageRaw, eventDisplayed)
                        + " merged total=" + merged.totalDamage()));
    }

    /**
     * The overkill clamp (F3): the per-event damage actually shown, folded, and
     * summed — the health the victim genuinely lost, not the full swing. A killing
     * hit renders its pre-hit red hearts; a normal hit passes through unchanged; a
     * victim already at (or below) zero — a same-tick death race — shows nothing, so
     * the caller skips the stand. Pure and package-visible so it is unit-pinned
     * without a live server; CRIT is decided on the raw swing, never through here.
     */
    static double displayedDamage(double finalDamage, double preHitHealth) {
        return preHitHealth <= 0.0 ? 0.0 : Math.min(finalDamage, preHitHealth);
    }

    /** {@code " shown=<displayed>"} when the clamp bit (raw != displayed), else empty — the trace stays terse on the common no-clamp hit. */
    private static String shownSuffix(double raw, double displayed) {
        return raw == displayed ? "" : " shown=" + displayed;
    }

    /**
     * The one region-legal block read: the spawn column scanned downward for the
     * first solid top. The result is FROZEN into the indicator — the driver
     * never reads the world again, so a block broken mid-flight is invisible to
     * the fall (an acceptable cosmetic staleness bought for a read-free tick).
     * Static and package-visible so the heal-indicator path ({@link HealIndicators})
     * freezes its plane through the byte-identical scan.
     */
    static double scanGround(World world, IndicatorPlacement.Spawn spawn) {
        int blockX = (int) Math.floor(spawn.x());
        int blockZ = (int) Math.floor(spawn.z());
        int startY = (int) Math.floor(spawn.y());
        for (int blockY = startY; blockY > startY - GROUND_SCAN_DEPTH; blockY--) {
            if (world.getBlockAt(blockX, blockY, blockZ).getType().isSolid()) {
                return blockY + 1.0;
            }
        }
        return spawn.y() - GROUND_SCAN_DEPTH;
    }

    /**
     * Ships spawn+metadata in one flush — inside bundle delimiters on 1.19.4+
     * so the stand never renders a frame without its name and marker flags.
     * Silent writes (the {@code BurstSender} seam) skip Mental's own PE
     * send-event stage; catch-Throwable because a mid-(re)configuration target
     * throws inside PacketEvents and a missed cosmetic beats a pipeline error.
     * Static and package-visible so the heal-indicator path ({@link HealIndicators})
     * ships its stand through the identical bundle discipline.
     */
    static void ship(User user, List<PacketWrapper<?>> packets, boolean bundleSupported) {
        try {
            if (bundleSupported) {
                user.writePacketSilently(new WrapperPlayServerBundle());
            }
            for (PacketWrapper<?> packet : packets) {
                user.writePacketSilently(packet);
            }
            if (bundleSupported) {
                user.writePacketSilently(new WrapperPlayServerBundle());
            }
            user.flushPackets();
        } catch (Throwable reconfiguring) {
            // A missed cosmetic beats a surfaced exception on the send path.
        }
    }
}

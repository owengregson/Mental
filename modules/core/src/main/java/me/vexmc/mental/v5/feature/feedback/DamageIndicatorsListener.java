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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * The {@code damage-indicators} coordinator: one indicator per victim damage
 * WINDOW, carrying the final rolled amount, drawn ONLY on the attacker's client
 * (a client-only packet armor stand — no other client ever receives a byte of
 * it, and no server entity exists behind it). The EDBEE fires at MONITOR on the
 * victim's region thread; the driver ticks each stand on the attacker's region.
 *
 * <h2>The window model (2026-07-12)</h2>
 * A FRESH melee hit opens a window through the {@link IndicatorWindowBook} and
 * HOLDS its marker for {@code roll-hold-ticks} ticks. Vanilla's mid-window
 * UPGRADE deltas (a stronger hit inside the victim's half-open invulnerability
 * window — {@link UpgradeWindow}) FOLD into that one held marker (sum the
 * displayed amounts, OR the crit) instead of ghosting their own crit-styled
 * stand — the owner's "-0.3 ❤" double-report. If the marker already shipped, a
 * delta BUMPS the live stand in place (the 2.5.3 replace-in-place path, reused —
 * no new packet surface). The held marker ships once its hold elapses (the roll
 * flush on the victim's session tick), or immediately on the victim's death.
 *
 * <h2>Frozen ground truth</h2>
 * The landing plane is scanned HERE, at the fresh EDBEE — the one moment block
 * reads are region-legal — from the spawn column downward (≤{@value
 * #GROUND_SCAN_DEPTH} blocks, first solid top wins). The driver's per-tick step
 * then performs ZERO world reads: the indicator falls under pure kernel
 * ballistics against that frozen plane. A held window carries that frozen
 * geometry to its (later, same-region) ship, so shipping never re-reads the
 * world either.
 *
 * <h2>Units</h2>
 * {@code {HEALTH}} renders in DAMAGE POINTS ({@link IndicatorText}); a bare-fist
 * hit reads {@code 1}, not {@code 0.5}. The crit-threshold knob is a PERCENT of
 * the victim's max (total) health, scaling with a scaled-max-health victim — it is a threshold, not display.
 *
 * <h2>Same-tick / plugin-bonus fold</h2>
 * An enchantment plugin's bonus (a second {@code victim.damage(bonus, attacker)}
 * in the hit's aftermath) lands with the window already open — a same-tick fresh
 * fold, or (with {@code noDamageTicks} re-armed) a delta fold — so it sums into
 * the one window exactly as the 2.5.3 merge book did. A standalone CUSTOM hit
 * (fresh branch, no live window) opens a window of its own, exactly as it drew
 * its own stand before the window model; only a CUSTOM-cause DELTA with no live
 * window is ambient plugin damage and never invents a stand.
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
    private final IndicatorWindowBook windows;
    private final Scheduling scheduling;
    private final TickClock clock;
    private final PlayerManager playerManager;
    private final FeedbackTrace trace;
    private final Logger logger;

    /**
     * The F4 last-hit attribution stamper, or null when heal indicators are off
     * (heal-text blank) — a null-check no-op then, so the zero-touch contract holds.
     * Stamped on every qualifying EDBEE (Player attacker+victim, {@code finalDamage
     * > 0}) INCLUDING delta hits (a delta is real combat; heal attribution must see
     * it), before any window routing.
     */
    private final HealIndicators heal;

    private final IndicatorBallistics.Params params;
    private final double critThresholdPercent; // PERCENT of the victim's max health — the crit-indicator margin
    private final boolean modernSpawn; // server 1.19+: the unified SPAWN_ENTITY packet
    private final boolean bundleSupported; // server 1.19.4+: spawn+metadata land in one client frame

    private final AtomicBoolean noEntityIdWarned = new AtomicBoolean(false);

    /**
     * The mob-side tick, installed by the unit right after construction (it needs this
     * listener's flush, so the two cannot both be constructor-injected). Null only for
     * the brief window before the unit wires it, and in unit tests that drive the pure
     * seams directly — hence the null-guard at the one call site.
     */
    private volatile MobFeedbackTicker mobs;

    public DamageIndicatorsListener(
            DamageIndicatorsSettings settings, ConcurrentHashMap<UUID, IndicatorDriver> drivers,
            IndicatorWindowBook windows, Scheduling scheduling, TickClock clock,
            boolean modernSpawn, boolean bundleSupported,
            FeedbackTrace trace, Logger logger, HealIndicators heal) {
        this.settings = settings;
        this.drivers = drivers;
        this.windows = windows;
        this.scheduling = scheduling;
        this.clock = clock;
        this.playerManager = PacketEvents.getAPI().getPlayerManager();
        this.trace = trace;
        this.logger = logger;
        this.heal = heal;
        this.params = new IndicatorBallistics.Params(
                settings.launchVertical(), settings.launchOutward(), settings.gravity(), settings.drag());
        this.critThresholdPercent = settings.critThresholdPercent();
        this.modernSpawn = modernSpawn;
        this.bundleSupported = bundleSupported;
    }

    /**
     * Routes one landed melee into the window book: a fresh hit opens+holds a
     * window, a mid-window upgrade delta folds/bumps into it, journaling the
     * decision throughout. A delta never spawns its own stand.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        boolean melee = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK;
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return; // an armor stand / item frame has no health pool to report
        }
        double finalDamage = event.getFinalDamage();
        if (finalDamage <= 0.0) {
            return;
        }

        // The DEALER (nullable): the entity whose blow this was, projectiles unwrapped
        // to their shooter, and the PLAYER behind it when there is one. Environmental
        // damage (fall, lava, drowning, poison) has neither.
        Entity source = dealer(event);
        Player attacker = source instanceof Player player ? player : null;

        long tick = clock.current().value();
        UUID victimId = victim.getUniqueId();
        UUID attackerId = attacker == null ? null : attacker.getUniqueId();

        // Stamp the last-hit attribution for the heal-indicator path (F4) — identity
        // only, so a clientless attacker (no PE user) is still recorded as the healer.
        // Runs for EVERY qualifying event, deltas included (a delta is real combat).
        // The reference is null when heal indicators are off (zero-touch no-op).
        if (heal != null && attackerId != null) {
            heal.recordHit(victimId, attackerId, tick);
        }

        // The crit posture is a property of the SWING, not the REMAINING health, so it
        // is decided on the RAW finalDamage — a killing overkill still reads crit. The
        // marker fires on a critical hit ({@code isLegacyCritical}) OR a hit landing at
        // or above the configured PERCENT of the victim's MAX (total) health, so the
        // margin scales with a scaled-max-health victim. getMaxHealth() is the
        // deprecated-but-stable accessor across 1.9.4→26.x (the hit-feedback low-health
        // posture's precedent), so the hot path never touches the version.
        @SuppressWarnings("deprecation")
        double critMargin = victim.getMaxHealth() * critThresholdPercent / 100.0;
        boolean crit = (attacker != null && DamageShaper.isLegacyCritical(attacker))
                || finalDamage >= critMargin;

        // Overkill clamp (F3): getHealth() at EDBEE MONITOR is the pre-hit pool (the
        // event fires before the subtraction), so the rolled/folded amount is the health
        // actually lost. A victim already at 0 (a same-tick death race) loses nothing
        // visible: record the decision but ship nothing.
        double displayed = displayedDamage(finalDamage, victim.getHealth());
        if (displayed <= 0.0) {
            trace.record(new FeedbackTrace.Entry(
                    "damage-indicators", attackerId, victimId,
                    crit ? "CRIT" : "NORMAL", "dmg=" + finalDamage + " shown=0"));
            return;
        }

        // A FRESH hit — either cause: a standalone CUSTOM ability hit is a hit of
        // its own and opens a window exactly as it drew its own stand before the
        // window model — opens/holds a window; a mid-window delta folds/bumps into
        // the live one. The upgrade-window predicate is the ONE shared source
        // hit-feedback's era-silence also reads.
        // WHO sees it (IndicatorViewers): nearby players minus the victim, plus the
        // attacker wherever they stand. Resolved HERE — the one region-legal moment —
        // and frozen into the window, so a held window's later ship reads no world.
        // Nobody in range means no packet work at all: an unwatched mob farm is free.
        List<UUID> viewers = IndicatorViewers.resolve(victim, attacker);
        if (viewers.isEmpty()) {
            return;
        }

        // A mob victim has no session tick, so arm (or refresh) its own: without it a
        // roll-held window would never flush and its heals would never be sampled.
        // Players already get both from their CombatSession.
        MobFeedbackTicker ticker = mobs;
        if (ticker != null && !(victim instanceof Player)) {
            ticker.arm(victim);
        }

        if (!UpgradeWindow.isDelta(victim)) {
            onFreshHit(victim, source, victimId, attackerId, tick, displayed, crit, viewers);
        } else {
            onDeltaHit(victim, source, victimId, attackerId, tick, displayed, crit, melee, viewers);
        }
    }

    /**
     * The entity that dealt this damage, or {@code null} for environmental causes.
     * A projectile resolves to its SHOOTER, so a bow/trident/snowball hit attributes
     * to the player who fired it — before this the arrow itself failed the old
     * {@code damager instanceof Player} gate, so ranged hits drew no indicator at all.
     */
    private static Entity dealer(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity entity ? entity : damager;
        }
        return damager;
    }

    private void onFreshHit(
            LivingEntity victim, Entity source, UUID victimId, UUID attackerId,
            long tick, double displayed, boolean crit, List<UUID> viewers) {
        IndicatorPlacement.Spawn spawn = place(victim, source);
        double groundY = scanGround(victim.getWorld(), spawn);
        IndicatorWindowBook.FreshResult result = windows.onFresh(
                victimId, attackerId, tick, displayed, crit,
                settings.rollHoldTicks(), victim.getMaximumNoDamageTicks(), spawn, groundY, viewers);
        // A now-closed prior window drew its held stand as this fresh hit displaced it
        // (two fresh hits inside one hold — rare; the invuln window normally outlasts
        // the hold). It is out of the book: draw and forget it.
        if (result.priorShip() != null) {
            draw(result.priorShip(), result.priorShip().crit() ? "CRIT" : "NORMAL");
        }
        switch (result.kind()) {
            case OPEN_HELD -> trace.record(new FeedbackTrace.Entry(
                    "damage-indicators", attackerId, victimId, "WINDOW_HELD",
                    "hold=" + settings.rollHoldTicks() + "t dmg=" + displayed));
            case FOLD_HELD -> trace.record(new FeedbackTrace.Entry(
                    "damage-indicators", attackerId, victimId, "WINDOW_FOLDED",
                    "same-tick fresh fold dmg=" + displayed));
            case OPEN_SHIP -> windows.shipped(victimId,
                    draw(result.action(), result.action().crit() ? "CRIT" : "NORMAL"));
            case FOLD_BUMP -> windows.shipped(victimId, draw(result.action(), "WINDOW_BUMPED"));
        }
    }

    private void onDeltaHit(
            LivingEntity victim, Entity source, UUID victimId, UUID attackerId,
            long tick, double displayed, boolean crit, boolean melee, List<UUID> viewers) {
        IndicatorWindowBook.DeltaResult result = windows.onDelta(victimId, attackerId, tick, displayed, crit);
        switch (result.kind()) {
            case FOLD_HELD -> trace.record(new FeedbackTrace.Entry(
                    "damage-indicators", attackerId, victimId, "WINDOW_FOLDED",
                    "delta folded into held window dmg=" + displayed));
            case BUMP -> windows.shipped(victimId, draw(result.action(), "WINDOW_BUMPED"));
            case UNTRACKED -> {
                if (!melee) {
                    // A CUSTOM-cause DELTA with no live window is a plugin topping up an
                    // untracked (environmental) window — ambient, never a hit of its own,
                    // no phantom stand (standalone CUSTOM hits are fresh-branch and DO
                    // open windows above). Recorded, never a silent skip.
                    trace.record(new FeedbackTrace.Entry(
                            "damage-indicators", attackerId, victimId, "WINDOW_UNTRACKED",
                            "custom-cause delta with no live window — dropped dmg=" + displayed));
                    return;
                }
                // A melee delta whose window opener never passed the cause gate
                // (environmental — fall/fire). Ship the melee DELTA once: the honest
                // melee contribution. getLastDamage()+delta ROLLED totals are NOT read —
                // the pre-event lastHurt is not cross-version-reliable during the upgrade
                // EDBEE (CraftBukkit stamps lastHurt around the event differently per
                // band), and the environmental opener's damage is not ours to show.
                // Remember it as a shipped window so a further delta bumps this one stand.
                IndicatorPlacement.Spawn spawn = place(victim, source);
                double groundY = scanGround(victim.getWorld(), spawn);
                IndicatorWindowBook.Ship plan = new IndicatorWindowBook.Ship(
                        victimId, attackerId, viewers, spawn, groundY, displayed, crit, -1);
                int entityId = draw(plan, crit ? "CRIT" : "NORMAL");
                windows.rememberUntracked(victimId, attackerId, tick, displayed, crit,
                        victim.getMaximumNoDamageTicks(), spawn, groundY, entityId, viewers);
            }
        }
    }

    /** Installs the mob-side tick (see {@link #mobs}); called once by the unit at assembly. */
    void useMobTicker(MobFeedbackTicker ticker) {
        this.mobs = ticker;
    }

    /**
     * The roll-hold flush — invoked once per tick per victim from the session tick
     * (the same seam the heal sampler rides), on the victim's region thread. A held
     * window whose hold elapsed ships its one stand here, trailing the fresh hit by
     * {@code roll-hold-ticks}.
     */
    public void flush(UUID victimId, long now) {
        IndicatorWindowBook.Ship plan = windows.due(victimId, now);
        if (plan != null) {
            windows.shipped(victimId, draw(plan, plan.crit() ? "CRIT" : "NORMAL"));
        }
    }

    /**
     * The death boundary: flush a still-held window immediately with its (already
     * overkill-clamped) killing total, so the marker shows before an instant respawn.
     * The entry is dropped, so no {@link IndicatorWindowBook#shipped} tracking follows.
     */
    public void deathFlush(UUID victimId) {
        IndicatorWindowBook.Ship plan = windows.onDeath(victimId);
        if (plan != null) {
            draw(plan, plan.crit() ? "CRIT" : "NORMAL");
        }
    }

    /**
     * The front-half ring placement off the victim's chest, toward whoever dealt the
     * blow (the one region-legal geometry read). Environmental damage has no dealer,
     * so the bearing falls back to the victim's OWN facing — the ring still gets a
     * stable front-half reference instead of an arbitrary one, so a falling player's
     * number pops in front of them rather than jittering around the compass.
     */
    private IndicatorPlacement.Spawn place(LivingEntity victim, Entity source) {
        Location victimLoc = victim.getLocation();
        double towardX;
        double towardZ;
        if (source != null && !source.getUniqueId().equals(victim.getUniqueId())) {
            Location sourceLoc = source.getLocation();
            towardX = sourceLoc.getX();
            towardZ = sourceLoc.getZ();
        } else {
            Vector facing = victimLoc.getDirection();
            towardX = victimLoc.getX() + facing.getX();
            towardZ = victimLoc.getZ() + facing.getZ();
        }
        return IndicatorPlacement.place(
                victimLoc.getX(), victimLoc.getY(), victimLoc.getZ(),
                towardX, towardZ,
                settings.ringRadius(), CHEST_OFFSET, settings.heightJitter(),
                ThreadLocalRandom.current());
    }

    /**
     * Draws one window's stand on the attacker's client — a fresh spawn
     * ({@code priorEntityId < 0}) or a replace-in-place bump (destroy the prior +
     * spawn the new in ONE bundle, so the client never renders a frame with zero or
     * two stands for the hit). Geometry is frozen in the {@link IndicatorWindowBook.Ship},
     * so this performs ZERO world reads and is region-thread-agnostic (it writes only
     * to the attacker's PacketEvents User). Returns the live entity id, or a negative
     * sentinel when unsendable (no client) or no entity id — journaling the decision
     * throughout, never a silent skip.
     */
    private int draw(IndicatorWindowBook.Ship plan, String decision) {
        if (plan.viewers().isEmpty()) {
            trace.record(new FeedbackTrace.Entry(
                    "damage-indicators", plan.attackerId(), plan.victimId(), "NO_VIEWERS",
                    "nobody in range to draw for"));
            return -1;
        }

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
                    "damage-indicators", plan.attackerId(), plan.victimId(),
                    "ID_UNAVAILABLE", "generateEntityId() failed"));
            return -1;
        }

        String rendered = IndicatorText.render(
                plan.crit() ? settings.critText() : settings.text(), plan.total());
        Component name = LegacyComponentSerializer.legacyAmpersand().deserialize(rendered);
        IndicatorBallistics.State launch = IndicatorBallistics.launch(plan.spawn(), params);

        // ONE client-only entity id, drawn on EVERY viewer's connection. Sharing the id
        // across viewers is safe precisely because the stand is packet-only: no server
        // entity exists, each client tracks its own copy, and the replace-in-place bump
        // destroys the same id on each of them in its own bundle.
        int delivered = 0;
        for (UUID viewerId : plan.viewers()) {
            Player viewer = Bukkit.getPlayer(viewerId);
            User user = viewer == null ? null : playerManager.getUser(viewer);
            if (user == null) {
                continue; // quit between freeze and ship, or no PacketEvents user (a fake)
            }
            IndicatorDriver driver = drivers.computeIfAbsent(
                    viewerId, id -> new IndicatorDriver(scheduling, viewer, user, params));
            List<PacketWrapper<?>> packets;
            if (plan.priorEntityId() >= 0) {
                packets = new ArrayList<>(3);
                packets.add(IndicatorStandPackets.destroy(plan.priorEntityId()));
                packets.addAll(IndicatorStandPackets.spawn(
                        entityId, UUID.randomUUID(), plan.spawn(), name, user.getClientVersion(), modernSpawn));
                ship(user, packets, bundleSupported);
                driver.replace(plan.priorEntityId(), entityId, launch, plan.groundY(), settings.lifetimeTicks());
            } else {
                packets = IndicatorStandPackets.spawn(
                        entityId, UUID.randomUUID(), plan.spawn(), name, user.getClientVersion(), modernSpawn);
                ship(user, packets, bundleSupported);
                driver.add(entityId, launch, plan.groundY(), settings.lifetimeTicks());
            }
            delivered++;
        }
        if (delivered == 0) {
            trace.record(new FeedbackTrace.Entry(
                    "damage-indicators", plan.attackerId(), plan.victimId(), "UNSENDABLE",
                    "no viewer had a live PacketEvents user"));
            return -1;
        }

        trace.record(new FeedbackTrace.Entry(
                "damage-indicators", plan.attackerId(), plan.victimId(), decision,
                "viewers=" + delivered + " y=" + plan.spawn().y()
                        + " ttl=" + settings.lifetimeTicks() + " total=" + plan.total()));
        return entityId;
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

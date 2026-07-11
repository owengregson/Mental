package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import me.vexmc.mental.kernel.fx.IndicatorBallistics;
import me.vexmc.mental.kernel.fx.IndicatorPlacement;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.session.SessionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The healing-indicator consumer inside {@code damage-indicators} (F4) — the
 * {@link SessionService.HealSampler} the session tick feeds a per-victim health
 * delta on every tick. A detected heal is attributed to the victim's most recent
 * attacker (within {@value #ATTRIBUTION_TICKS} ticks) and drawn as an
 * attacker-client-only packet armor stand off the victim's chest, riding the same
 * {@link IndicatorDriver} / {@link IndicatorStandPackets} / {@link IndicatorPlacement}
 * stack as the damage path — the ONE difference being that it NEVER touches
 * {@link IndicatorMergeBook} (the same-tick damage fold must not cross-contaminate a
 * heal, a hard rule).
 *
 * <h2>Detection over attribution over pacing</h2>
 * The per-tick delta ({@code current − previous}) catches every heal source the
 * event listeners miss — {@code setHealth} (StarEnchants, RegenUnit), event heals
 * (pots, regen, gapples), and cancelled non-heals correctly, since only APPLIED
 * health moves the number. A raw delta with no fresh combat context is dropped
 * SILENTLY (no trace): ambient regen must never spam the trace ring. A drip is
 * paced through a per-victim {@link HealFold} — a pot burst ships at once, a
 * 1-HP-a-tick regen aggregates into one stand per fold window.
 *
 * <h2>Threading</h2>
 * Every method here runs on the VICTIM's region thread (the sampler is invoked
 * inside that player's session tick; {@link #recordHit} rides the victim-region
 * EDBEE). The only cross-region reads are guarded: {@code Bukkit.getPlayer} is
 * thread-safe, and the attacker's {@code getLocation()} is wrapped in try-catch
 * with a random-bearing fallback (a Folia {@code ensureTickThread} throw off the
 * attacker's region). {@link IndicatorDriver#add} is already designed for the
 * cross-thread hand-off, exactly as the damage path uses it.
 */
final class HealIndicators implements SessionService.HealSampler {

    /** A heal attributes to the last hit only within this many ticks (200 ticks = 10 s). */
    private static final int ATTRIBUTION_TICKS = 200;

    /** One stamped hit: who last damaged this victim, and when. Identity only — no PE user needed. */
    private record LastHit(UUID attackerId, long tick) {}

    private final ConcurrentHashMap<UUID, IndicatorDriver> drivers;
    private final DamageIndicatorsSettings settings;
    private final Scheduling scheduling;
    private final PlayerManager playerManager;
    private final IndicatorBallistics.Params params;
    private final FeedbackTrace trace;
    private final Logger logger;
    private final boolean modernSpawn;
    private final boolean bundleSupported;

    /** Per-victim last-attacker attribution, stamped on every qualifying melee EDBEE. */
    private final ConcurrentHashMap<UUID, LastHit> lastHits = new ConcurrentHashMap<>();

    /** Per-victim heal aggregation/pacing — created lazily on the first heal, dropped on forget/respawn. */
    private final ConcurrentHashMap<UUID, HealFold> folds = new ConcurrentHashMap<>();

    private final AtomicBoolean noEntityIdWarned = new AtomicBoolean(false);

    HealIndicators(
            ConcurrentHashMap<UUID, IndicatorDriver> drivers, DamageIndicatorsSettings settings,
            Scheduling scheduling, IndicatorBallistics.Params params, FeedbackTrace trace,
            Logger logger, boolean modernSpawn, boolean bundleSupported) {
        this.drivers = drivers;
        this.settings = settings;
        this.scheduling = scheduling;
        this.playerManager = PacketEvents.getAPI().getPlayerManager();
        this.params = params;
        this.trace = trace;
        this.logger = logger;
        this.modernSpawn = modernSpawn;
        this.bundleSupported = bundleSupported;
    }

    /**
     * Stamps the last hit against {@code victim} — identity only, so a clientless
     * attacker (no PacketEvents user) is still recorded as the healer for a later heal.
     * A self-hit (a plugin's CUSTOM self-damage, a reflect path with damager ==
     * victim) never stamps: it is not an attribution target, and letting it
     * OVERWRITE an earlier real attacker's stamp would silently swallow that
     * attacker's heal indicator at ship time.
     */
    void recordHit(UUID victim, UUID attacker, long tick) {
        if (victim.equals(attacker)) {
            return;
        }
        lastHits.put(victim, new LastHit(attacker, tick));
    }

    /**
     * The death boundary. The in-band respawn guard in {@link #sample} only fires
     * when a session tick OBSERVES the corpse ({@code previousHealth <= 0}) — but
     * the plugin's core audience runs instant/1-tick auto-respawn, where death and
     * respawn complete BETWEEN samples: the next sample reads (pre-death health →
     * full respawn health) as a giant "heal" and would draw a phantom indicator on
     * the KILLER's client on every kill. Clearing the fold and the attribution at
     * the death EVENT closes that hole: the respawn delta then ships nowhere (no
     * fresh stamp), and post-respawn regen is ambient until real combat re-stamps.
     */
    void onDeath(UUID victim) {
        folds.remove(victim);
        lastHits.remove(victim);
    }

    @Override
    public void sample(UUID victimId, double previousHealth, double currentHealth, TickStamp now) {
        if (Double.isNaN(previousHealth)) {
            return; // the victim's first sampled tick — no delta to read yet
        }
        if (previousHealth <= 0.0) {
            // A respawn/death boundary: the jump up from a corpse (0, or a mid-death
            // read) to full health is not a heal. Forget this victim's fold and
            // attribution so no phantom heal indicator rides the respawn.
            folds.remove(victimId);
            lastHits.remove(victimId);
            return;
        }
        double delta = currentHealth - previousHealth;
        if (delta > 1.0e-9) {
            folds.computeIfAbsent(victimId, id -> new HealFold()).add(delta);
        }
        // Poll every tick — even a tick with no fresh delta must flush a drip whose
        // pacing window has now elapsed. A fold exists only once a heal has landed.
        HealFold fold = folds.get(victimId);
        if (fold == null) {
            return;
        }
        long tick = now.value();
        double shipped = fold.poll(tick);
        if (shipped > 0.0) {
            shipHeal(victimId, shipped, tick);
        }
    }

    /**
     * Attributes the shipped heal to the fresh last-hit attacker and draws the stand
     * on their client. Attribution is dropped SILENTLY (no trace) when there is no
     * fresh combat context — a self-heal, an expired stamp, or no stamp at all is
     * ambient regen, and ambient regen must not spam the trace ring. A fresh
     * attribution whose attacker is offline / has no PacketEvents user traces
     * {@code HEAL_UNSENDABLE} instead (the matrix-assertable seam).
     */
    private void shipHeal(UUID victimId, double shippedAmount, long nowTick) {
        LastHit lastHit = lastHits.get(victimId);
        if (lastHit == null
                || lastHit.attackerId().equals(victimId)
                || nowTick - lastHit.tick() > ATTRIBUTION_TICKS) {
            return; // ambient regen with no fresh combat context — no trace, no ring spam
        }
        UUID attackerId = lastHit.attackerId();
        Player attacker = Bukkit.getPlayer(attackerId);
        User user = attacker == null ? null : playerManager.getUser(attacker);
        if (attacker == null || user == null) {
            trace.record(new FeedbackTrace.Entry(
                    "damage-indicators", attackerId, victimId, "HEAL_UNSENDABLE",
                    attacker == null ? "attacker offline" : "attacker has no PacketEvents user"));
            return;
        }
        Player victim = Bukkit.getPlayer(victimId);
        if (victim == null) {
            return; // the victim vanished between sample and ship — nothing to place around
        }

        String rendered = IndicatorText.render(settings.healText(), shippedAmount);
        Component name = LegacyComponentSerializer.legacyAmpersand().deserialize(rendered);

        Location victimLoc = victim.getLocation();
        double attackerX;
        double attackerZ;
        try {
            // We are on the VICTIM's region thread. Reading the attacker's location is
            // cross-region under Folia and can throw (ensureTickThread); a best-effort
            // read decides the ring bearing, and on failure a random front-half bearing
            // around the victim stands in — the stand still pops off the victim's chest.
            Location attackerLoc = attacker.getLocation();
            attackerX = attackerLoc.getX();
            attackerZ = attackerLoc.getZ();
        } catch (Throwable crossRegion) {
            double theta = ThreadLocalRandom.current().nextDouble() * 2.0 * Math.PI;
            attackerX = victimLoc.getX() + Math.cos(theta);
            attackerZ = victimLoc.getZ() + Math.sin(theta);
        }

        IndicatorPlacement.Spawn spawn = IndicatorPlacement.place(
                victimLoc.getX(), victimLoc.getY(), victimLoc.getZ(),
                attackerX, attackerZ,
                settings.ringRadius(), DamageIndicatorsListener.CHEST_OFFSET, settings.heightJitter(),
                ThreadLocalRandom.current());
        double groundY = DamageIndicatorsListener.scanGround(victim.getWorld(), spawn);

        int entityId;
        try {
            entityId = SpigotReflectionUtil.generateEntityId();
        } catch (IllegalStateException noId) {
            if (noEntityIdWarned.compareAndSet(false, true)) {
                logger.warning("damage-indicators: could not generate a client-only entity id"
                        + " on this server — heal indicators are skipped");
            }
            trace.record(new FeedbackTrace.Entry(
                    "damage-indicators", attackerId, victimId, "ID_UNAVAILABLE", "generateEntityId() failed"));
            return;
        }

        List<PacketWrapper<?>> packets = IndicatorStandPackets.spawn(
                entityId, UUID.randomUUID(), spawn, name, user.getClientVersion(), modernSpawn);
        DamageIndicatorsListener.ship(user, packets, bundleSupported);

        // The heal rides the attacker's driver exactly like a damage indicator, but
        // NEVER through merges() — the same-tick damage fold must not fold a heal in.
        IndicatorDriver driver = drivers.computeIfAbsent(
                attackerId, id -> new IndicatorDriver(scheduling, attacker, user, params));
        driver.add(entityId, IndicatorBallistics.launch(spawn, params), groundY, settings.lifetimeTicks());

        trace.record(new FeedbackTrace.Entry(
                "damage-indicators", attackerId, victimId, "HEAL",
                "healed=" + IndicatorText.hearts(shippedAmount)));
    }

    /** Session forget (quit): drop this player as a heal victim AND as any victim's stamped attacker. */
    void forget(UUID id) {
        lastHits.remove(id);
        folds.remove(id);
        lastHits.values().removeIf(hit -> hit.attackerId().equals(id));
    }
}

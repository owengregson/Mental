package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import me.vexmc.mental.kernel.fx.IndicatorBallistics;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Assembles {@code damage-indicators}: the EDBEE MONITOR spawner
 * ({@code scope.listen}) plus a scope-owned registry of per-attacker
 * {@link IndicatorDriver}s ({@code scope.task}), closed as a unit — scope
 * teardown destroys every in-flight stand and cancels every drive task, so a
 * disabled feature leaves nothing on any client and nothing scheduled
 * (zero-touch).
 *
 * <p>The two version gates resolve here, once, off the PacketEvents server
 * version (the {@code BurstSender} posture): the unified SPAWN_ENTITY packet at
 * 1.19 (below it, living entities spawn through the dedicated legacy wrapper)
 * and bundle delimiters at 1.19.4. Everything per-hit is version-blind.</p>
 *
 * <p>{@link #forget(UUID)} is the session hook (wired by the plugin's forget
 * path): an attacker who leaves gets their driver — its stale entity and
 * PacketEvents user references included — closed and dropped, so a relog builds
 * a fresh driver against the fresh connection.</p>
 *
 * <p>The HEALING indicator (F4) rides the same scope: when {@code heal-text} is
 * non-blank the unit builds a {@link HealIndicators} and installs it as the
 * {@link SessionService.HealSampler}, torn down (sampler cleared) on scope close —
 * so a blank template (the DEFAULTS) installs NO sampler and the whole heal
 * machinery stays dormant (zero-touch, era-exact no-op).</p>
 */
public final class DamageIndicatorsUnit implements FeatureUnit {

    private final SessionService sessions;
    private final Scheduling scheduling;
    private final TickClock clock;
    private final FeedbackTrace trace;
    private final Logger logger;

    /** The CURRENT assembly's per-attacker drivers; empty whenever the feature is off. */
    private volatile ConcurrentHashMap<UUID, IndicatorDriver> drivers;

    /** The CURRENT assembly's heal consumer, or null when off / heal-text blank (mirrors {@link #drivers}). */
    private volatile HealIndicators heal;

    public DamageIndicatorsUnit(
            SessionService sessions, Scheduling scheduling, TickClock clock, FeedbackTrace trace, Logger logger) {
        this.sessions = sessions;
        this.scheduling = scheduling;
        this.clock = clock;
        this.trace = trace;
        this.logger = logger;
    }

    @Override
    public Feature descriptor() {
        return Feature.DAMAGE_INDICATORS;
    }

    /** Templates/ballistics resolve into the listener at assemble — a settings reload must re-assemble. */
    @Override
    public boolean rebuildOnSettingsChange() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void assemble(Scope scope, Snapshot snapshot) {
        DamageIndicatorsSettings settings = snapshot.settings(
                (SettingsKey<DamageIndicatorsSettings>) Feature.DAMAGE_INDICATORS.settingsKey());

        ServerVersion server = PacketEvents.getAPI().getServerManager().getVersion();
        boolean modernSpawn = server.isNewerThanOrEquals(ServerVersion.V_1_19);
        boolean bundleSupported = server.isNewerThanOrEquals(ServerVersion.V_1_19_4);

        ConcurrentHashMap<UUID, IndicatorDriver> map = new ConcurrentHashMap<>();
        this.drivers = map;

        // The healing indicator is built ONLY when a heal-text template is set — a
        // blank template (the DEFAULTS) installs no sampler at all, so heal detection
        // and its per-victim maps never come into existence (the era-exact no-op).
        HealIndicators healIndicators = null;
        if (settings.healText() != null && !settings.healText().isBlank()) {
            IndicatorBallistics.Params params = new IndicatorBallistics.Params(
                    settings.launchVertical(), settings.launchOutward(), settings.gravity(), settings.drag());
            healIndicators = new HealIndicators(
                    map, settings, scheduling, params, trace, logger, modernSpawn, bundleSupported);
            sessions.setHealSampler(healIndicators);
        }
        this.heal = healIndicators;
        HealIndicators installed = healIndicators; // effectively final for the teardown lambda

        scope.listen(new DamageIndicatorsListener(
                settings, map, scheduling, clock, modernSpawn, bundleSupported, trace, logger, healIndicators));
        if (healIndicators != null) {
            scope.listen(new HealDeathBoundary(healIndicators));
        }
        scope.task(() -> (AutoCloseable) () -> {
            // Scope close clears the heal sampler (restoring zero-touch), then tears
            // every stand down and cancels every drive task.
            if (installed != null) {
                sessions.clearHealSampler();
            }
            this.heal = null;
            map.values().forEach(IndicatorDriver::close);
            map.clear();
        });
    }

    /**
     * The heal consumer's death boundary ({@link HealIndicators#onDeath}): the
     * in-band respawn guard only fires when a session tick OBSERVES the corpse,
     * but instant/1-tick auto-respawn (the practice-server norm) completes
     * between samples — the next sample would read pre-death health → full
     * respawn health as a giant heal and draw a phantom indicator on the
     * KILLER's client. Scope-owned, so a disabled module listens to nothing.
     */
    private record HealDeathBoundary(HealIndicators heal) implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(PlayerDeathEvent event) {
            heal.onDeath(event.getEntity().getUniqueId());
        }
    }

    /**
     * Session forget (quit): closes that attacker's driver — stands destroyed, task
     * cancelled — and drops the player from the heal consumer's attribution/fold maps
     * (both as a heal victim and as any victim's stamped attacker).
     */
    public void forget(UUID player) {
        ConcurrentHashMap<UUID, IndicatorDriver> map = drivers;
        if (map != null) {
            IndicatorDriver driver = map.remove(player);
            if (driver != null) {
                driver.close();
            }
        }
        HealIndicators healIndicators = heal;
        if (healIndicators != null) {
            healIndicators.forget(player);
        }
    }
}

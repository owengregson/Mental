package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;

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
 */
public final class DamageIndicatorsUnit implements FeatureUnit {

    private final Scheduling scheduling;
    private final FeedbackTrace trace;
    private final Logger logger;

    /** The CURRENT assembly's per-attacker drivers; empty whenever the feature is off. */
    private volatile ConcurrentHashMap<UUID, IndicatorDriver> drivers;

    public DamageIndicatorsUnit(Scheduling scheduling, FeedbackTrace trace, Logger logger) {
        this.scheduling = scheduling;
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
        scope.listen(new DamageIndicatorsListener(
                settings, map, scheduling, modernSpawn, bundleSupported, trace, logger));
        scope.task(() -> (AutoCloseable) () -> {
            // Scope close tears every stand down and cancels every drive task.
            map.values().forEach(IndicatorDriver::close);
            map.clear();
        });
    }

    /** Session forget (quit): closes that attacker's driver — stands destroyed, task cancelled. */
    public void forget(UUID player) {
        ConcurrentHashMap<UUID, IndicatorDriver> map = drivers;
        if (map == null) {
            return;
        }
        IndicatorDriver driver = map.remove(player);
        if (driver != null) {
            driver.close();
        }
    }
}

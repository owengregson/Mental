package me.vexmc.mental;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.util.List;
import me.vexmc.mental.common.debug.DebugLog;
import me.vexmc.mental.common.platform.Capabilities;
import me.vexmc.mental.common.platform.ServerEnvironment;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.config.MentalConfig;
import me.vexmc.mental.debug.ConsoleSink;
import me.vexmc.mental.engine.ModuleRegistry;
import me.vexmc.mental.module.anticheat.AnticheatGate;
import me.vexmc.mental.platform.SchedulingFactory;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bootstrap. Owns wiring and lifecycle ordering; substance lives in the
 * modules.
 *
 * <p>PacketEvents lifecycle: the API is built and loaded in {@code onLoad}
 * (shaded and relocated, so no external plugin is involved), initialised in
 * {@code onEnable} <em>after</em> every module has registered its packet
 * listeners, and terminated in {@code onDisable} <em>after</em> modules have
 * unregistered.</p>
 */
public final class MentalPlugin extends JavaPlugin {

    private MentalConfig config;
    private MentalServices services;
    private ModuleRegistry modules;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
                .checkForUpdates(false)
                .reEncodeByDefault(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.config = new MentalConfig();
        reportConfigIssues(config.reload(getConfig()));

        Capabilities capabilities = Capabilities.detect();
        ServerEnvironment environment = ServerEnvironment.parse(Bukkit.getBukkitVersion());
        Scheduling scheduling = SchedulingFactory.create(this, capabilities);

        DebugLog debug = new DebugLog();
        debug.addSink(new ConsoleSink(getLogger()));
        applyDebugSettings(debug);

        this.services = new MentalServices(
                this, config, capabilities, environment, scheduling, debug, new AnticheatGate());
        this.modules = new ModuleRegistry(getLogger());

        registerModules();
        modules.enableAll();

        registerCommands();

        PacketEvents.getAPI().init();

        getLogger().info(() -> "Mental enabled — server " + environment.describe()
                + ", scheduling=" + scheduling.describe()
                + ", " + capabilities.describe());
    }

    @Override
    public void onDisable() {
        if (modules != null) {
            modules.disableAll();
        }
        try {
            if (PacketEvents.getAPI() != null) {
                PacketEvents.getAPI().terminate();
            }
        } catch (Throwable failure) {
            getLogger().warning("PacketEvents termination threw: " + failure.getMessage());
        }
        modules = null;
        services = null;
        config = null;
        getLogger().info("Mental disabled.");
    }

    /**
     * Re-reads config.yml, swaps the typed snapshot atomically, and converges
     * every module onto its configured state. Returns config warnings for
     * display to the invoking command sender.
     */
    public @NotNull List<String> reloadAll() {
        reloadConfig();
        List<String> warnings = config.reload(getConfig());
        reportConfigIssues(warnings);
        applyDebugSettings(services.debug());
        modules.reloadAll();
        return warnings;
    }

    public @NotNull MentalServices services() {
        return services;
    }

    public @NotNull ModuleRegistry modules() {
        return modules;
    }

    private void registerModules() {
        // Modules register here in dependency order as they come online.
    }

    private void registerCommands() {
        // Command tree wiring lands with the command system.
    }

    private void applyDebugSettings(DebugLog debug) {
        debug.enabled(config.debug().enabled());
        debug.activateAll(config.debug().categories());
    }

    private void reportConfigIssues(List<String> warnings) {
        for (String warning : warnings) {
            getLogger().warning("config.yml — " + warning);
        }
    }
}

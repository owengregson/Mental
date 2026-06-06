package me.vexmc.mental;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import me.vexmc.mental.api.Mental;
import me.vexmc.mental.command.BukkitCommandRenderer;
import me.vexmc.mental.command.MentalCommands;
import me.vexmc.mental.common.command.CommandTree;
import me.vexmc.mental.common.debug.DebugLog;
import me.vexmc.mental.common.platform.Capabilities;
import me.vexmc.mental.common.platform.ServerEnvironment;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.config.ConfigStore;
import me.vexmc.mental.config.MentalConfig;
import me.vexmc.mental.debug.ConsoleSink;
import me.vexmc.mental.debug.PlayerDebugSink;
import me.vexmc.mental.engine.ModuleRegistry;
import me.vexmc.mental.module.anticheat.AnticheatCompatModule;
import me.vexmc.mental.module.anticheat.AnticheatGate;
import me.vexmc.mental.module.compensation.LatencyCompensationModule;
import me.vexmc.mental.module.fishing.FishingKnockbackModule;
import me.vexmc.mental.module.fishing.FishingRodVelocityModule;
import me.vexmc.mental.module.hitreg.HitRegistrationModule;
import me.vexmc.mental.module.knockback.GroundPacketTap;
import me.vexmc.mental.module.knockback.GroundTransitionWatcher;
import me.vexmc.mental.module.knockback.KnockbackModule;
import me.vexmc.mental.module.knockback.KnockbackPipeline;
import me.vexmc.mental.module.knockback.KnockbackProfiles;
import me.vexmc.mental.module.knockback.SprintTracker;
import me.vexmc.mental.module.knockback.VelocityDuplicateSuppressor;
import me.vexmc.mental.module.knockback.VictimMotion;
import me.vexmc.mental.module.ocm.OcmCompatModule;
import me.vexmc.mental.module.ocm.OcmGate;
import me.vexmc.mental.module.projectile.ProjectileKnockbackModule;
import me.vexmc.mental.platform.SchedulingFactory;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

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

    private static final String BRIGADIER_BRIDGE = "me.vexmc.mental.compat.brigadier.BrigadierBridge";
    private static final int BSTATS_PLUGIN_ID = 31788;

    private MentalConfig config;
    private ConfigStore configStore;
    private MentalServices services;
    private ModuleRegistry modules;
    private PlayerDebugSink playerDebugSink;
    private KnockbackPipeline knockbackPipeline;
    private GroundTransitionWatcher groundWatcher;
    private VelocityDuplicateSuppressor velocitySuppressor;
    private Metrics metrics;

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

        this.configStore = new ConfigStore(
                getDataFolder(), this::getResource, message -> getLogger().info(message));
        if (configStore.migrateLegacyLayout(getConfig())) {
            reloadConfig();
        }
        configStore.ensureDefaultFiles();

        this.config = new MentalConfig();
        reportConfigIssues(config.reload(configStore.loadSources(getConfig())));

        Capabilities capabilities = Capabilities.detect();
        ServerEnvironment environment = ServerEnvironment.parse(Bukkit.getBukkitVersion());
        Scheduling scheduling = SchedulingFactory.create(this, capabilities);

        DebugLog debug = new DebugLog();
        debug.addSink(new ConsoleSink(getLogger()));
        this.playerDebugSink = new PlayerDebugSink();
        debug.addSink(playerDebugSink);
        applyDebugSettings(debug);

        this.services = new MentalServices(
                this, config, capabilities, environment, scheduling, debug,
                new AnticheatGate(), new OcmGate(),
                new SprintTracker(),
                new KnockbackProfiles(config));
        this.modules = new ModuleRegistry(getLogger());

        registerModules();
        modules.enableAll();

        registerCommands();

        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                playerDebugSink.forget(event.getPlayer().getUniqueId());
                services.knockbackProfiles().forget(event.getPlayer().getUniqueId());
            }
        }, this);

        Mental.register(new MentalApiImpl(this, modules));

        this.metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("anticheat_mode",
                () -> config.anticheat().mode().name().toLowerCase(Locale.ROOT)));
        metrics.addCustomChart(new SimplePie("probe_strategy",
                () -> config.compensation().probeStrategy().name().toLowerCase(Locale.ROOT)));
        metrics.addCustomChart(new SimplePie("scheduling_backend",
                () -> services.scheduling().describe()));
        metrics.addCustomChart(new SimplePie("ocm_coordination",
                () -> services.ocmGate().mode().name().toLowerCase(Locale.ROOT)));

        // Registered before init like the module listeners; idle unless the
        // pipeline arms it for a pre-delivered knock.
        velocitySuppressor = new VelocityDuplicateSuppressor();
        PacketEvents.getAPI().getEventManager()
                .registerListener(velocitySuppressor, PacketListenerPriority.HIGHEST);
        knockbackPipeline.suppressor(velocitySuppressor);

        // The era movement-packet bookkeeping ran per packet, in arrival
        // order; the tap feeds the watcher the same events so jump stamps
        // land the tick the client's packet does, not a sample later
        // (read-only — observes after every other listener has had its say).
        PacketEvents.getAPI().getEventManager()
                .registerListener(new GroundPacketTap(groundWatcher), PacketListenerPriority.MONITOR);

        PacketEvents.getAPI().init();

        getLogger().info(() -> "Mental enabled — server " + environment.describe()
                + ", scheduling=" + scheduling.describe()
                + ", " + capabilities.describe());
    }

    @Override
    public void onDisable() {
        Mental.register(null);
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
        if (modules != null) {
            modules.disableAll();
        }
        if (groundWatcher != null) {
            groundWatcher.shutdown();
            groundWatcher = null;
        }
        if (velocitySuppressor != null) {
            velocitySuppressor.clear();
            velocitySuppressor = null;
        }
        if (knockbackPipeline != null) {
            knockbackPipeline.clear();
            knockbackPipeline = null;
        }
        if (services != null) {
            services.sprintTracker().clear();
            services.knockbackProfiles().clear();
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
     * Re-reads every configuration file, swaps the typed snapshot atomically,
     * and converges every module onto its configured state. Returns config
     * warnings for display to the invoking command sender.
     */
    public @NotNull List<String> reloadAll() {
        reloadConfig();
        configStore.ensureDefaultFiles();
        List<String> warnings = new ArrayList<>(
                config.reload(configStore.loadSources(getConfig())));
        for (UUID stale : services.knockbackProfiles().clearStaleOverrides()) {
            warnings.add("knockback profile override cleared for " + stale
                    + " — its profile no longer exists");
        }
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

    /** The delivery pipeline (and through it the victim-motion ledger) — test access. */
    public @NotNull KnockbackPipeline knockbackPipeline() {
        return knockbackPipeline;
    }

    private void registerModules() {
        VictimMotion victimMotion = new VictimMotion();
        knockbackPipeline = new KnockbackPipeline(services, victimMotion);
        getServer().getPluginManager().registerEvents(knockbackPipeline, this);
        // Observation only — feeds the wtap-extra freshness branch; with the
        // knob disabled (every default) it changes nothing.
        getServer().getPluginManager().registerEvents(services.sprintTracker(), this);
        // Pure observation as well: replicates the era movement-packet
        // bookkeeping (jump stamps, landings) into the ledger so knockback
        // verticals see the same baselines a legacy server's fields held.
        groundWatcher = new GroundTransitionWatcher(services, victimMotion);
        getServer().getPluginManager().registerEvents(groundWatcher, this);
        groundWatcher.watchOnlinePlayers();

        KnockbackModule knockback = new KnockbackModule(services, victimMotion, knockbackPipeline);
        LatencyCompensationModule compensation = new LatencyCompensationModule(services, victimMotion);
        knockback.hints(compensation);

        modules.register(new AnticheatCompatModule(services));
        modules.register(new OcmCompatModule(services, services.ocmGate()));
        modules.register(new HitRegistrationModule(services, victimMotion, knockbackPipeline, compensation));
        modules.register(knockback);
        modules.register(compensation);
        modules.register(new FishingKnockbackModule(services, knockbackPipeline));
        modules.register(new FishingRodVelocityModule(services));
        modules.register(new ProjectileKnockbackModule(services, knockbackPipeline));
    }

    private void registerCommands() {
        CommandTree tree = new MentalCommands(this, services, modules, playerDebugSink).build();

        var command = getCommand("mental");
        if (command != null) {
            BukkitCommandRenderer renderer = new BukkitCommandRenderer(tree);
            command.setExecutor(renderer);
            command.setTabCompleter(renderer);
        } else {
            getLogger().warning("plugin.yml is missing the 'mental' command — commands unavailable.");
        }

        if (services.capabilities().brigadierCommands()) {
            try {
                Class.forName(BRIGADIER_BRIDGE)
                        .getMethod("register", org.bukkit.plugin.java.JavaPlugin.class,
                                CommandTree.class, String.class, List.class)
                        .invoke(null, this, tree, "Mental root command — dashboard, modules, knockback profiles, ping, debug.",
                                List.of("mtl"));
                getLogger().info("Commands rendered natively via Brigadier.");
            } catch (ReflectiveOperationException failure) {
                getLogger().warning("Brigadier bridge failed to load; classic commands remain active: "
                        + failure);
            }
        }
    }

    private void applyDebugSettings(DebugLog debug) {
        debug.enabled(config.debug().enabled());
        debug.activateAll(config.debug().categories());
    }

    private void reportConfigIssues(List<String> warnings) {
        for (String warning : warnings) {
            getLogger().warning("config — " + warning);
        }
    }
}

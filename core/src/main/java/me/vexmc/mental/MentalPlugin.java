package me.vexmc.mental;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import me.vexmc.mental.gui.MenuContext;
import me.vexmc.mental.gui.MenuManager;
import me.vexmc.mental.manage.ManagementService;
import me.vexmc.mental.module.anticheat.AnticheatCompatModule;
import me.vexmc.mental.module.anticheat.AnticheatGate;
import me.vexmc.mental.module.block.SwordBlockingModule;
import me.vexmc.mental.module.compensation.LatencyCompensationModule;
import me.vexmc.mental.module.damage.ArmourDurabilityModule;
import me.vexmc.mental.module.damage.ArmourStrengthModule;
import me.vexmc.mental.module.damage.CritFallbackModule;
import me.vexmc.mental.module.hitbox.HitboxModule;
import me.vexmc.mental.module.damage.ToolDurabilityModule;
import me.vexmc.mental.module.fishing.FishingKnockbackModule;
import me.vexmc.mental.module.fishing.FishingRodVelocityModule;
import me.vexmc.mental.module.hitreg.HitRegistrationModule;
import me.vexmc.mental.module.hitreg.WtapRegistrationModule;
import me.vexmc.mental.module.knockback.GroundPacketTap;
import me.vexmc.mental.module.knockback.GroundTransitionWatcher;
import me.vexmc.mental.module.knockback.KnockbackEventMirror;
import me.vexmc.mental.module.knockback.KnockbackModule;
import me.vexmc.mental.module.knockback.KnockbackPipeline;
import me.vexmc.mental.module.knockback.KnockbackProfiles;
import me.vexmc.mental.module.knockback.ServerTickClock;
import me.vexmc.mental.module.knockback.SprintTracker;
import me.vexmc.mental.module.knockback.VelocityDuplicateSuppressor;
import me.vexmc.mental.module.knockback.VictimMotion;
import me.vexmc.mental.module.ocm.OcmCompatModule;
import me.vexmc.mental.module.ocm.OcmGate;
import me.vexmc.mental.module.projectile.ProjectileKnockbackModule;
import me.vexmc.mental.module.rules.cooldown.AttackCooldownModule;
import me.vexmc.mental.module.rules.cooldown.CooldownSpoofListener;
import me.vexmc.mental.module.rules.cooldown.WeaponAttributeTooltipHider;
import me.vexmc.mental.module.rules.sound.AttackSoundListener;
import me.vexmc.mental.module.rules.sound.AttackSoundModule;
import me.vexmc.mental.module.rules.crafting.DisableCraftingModule;
import me.vexmc.mental.module.consumable.EnderPearlCooldownModule;
import me.vexmc.mental.module.consumable.GoldenAppleModule;
import me.vexmc.mental.module.health.RegenModule;
import me.vexmc.mental.module.potion.PotionDurationModule;
import me.vexmc.mental.module.potion.PotionValueModule;
import me.vexmc.mental.module.rules.offhand.OffhandModule;
import me.vexmc.mental.module.rules.sweep.SweepModule;
import me.vexmc.mental.module.rules.sweep.SweepParticleListener;
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
    private ManagementService management;
    private MenuManager menuManager;
    private ModuleRegistry modules;
    private PlayerDebugSink playerDebugSink;
    private KnockbackPipeline knockbackPipeline;
    private GroundTransitionWatcher groundWatcher;
    private ServerTickClock serverTickClock;
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
                new SprintTracker(capabilities.folia()),
                new KnockbackProfiles(config));
        this.management = new ManagementService(this);
        this.modules = new ModuleRegistry(getLogger());

        registerModules();
        modules.enableAll();

        // The management GUI replaces command-based administration: always-on
        // routing infrastructure (like the command system), registered for the
        // plugin lifetime and torn down in onDisable. Never touches the game.
        this.menuManager = new MenuManager(
                new MenuContext(services, management, playerDebugSink));
        getServer().getPluginManager().registerEvents(menuManager, this);

        registerCommands();

        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                playerDebugSink.forget(event.getPlayer().getUniqueId());
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
        // land the tick the client's packet does, not a sample later, and
        // feeds the sprint tracker's wire view so attack registration can
        // read sprint toggles the same way (read-only — observes after
        // every other listener has had its say).
        PacketEvents.getAPI().getEventManager()
                .registerListener(new GroundPacketTap(groundWatcher, services.sprintTracker()),
                        PacketListenerPriority.MONITOR);

        // Rewrites attack_speed in UPDATE_ATTRIBUTES for the receiver's own
        // entity so the client never renders a cooldown overlay.  Registered
        // for the plugin lifetime; the listener gates on the config flag.
        PacketEvents.getAPI().getEventManager()
                .registerListener(new CooldownSpoofListener(config),
                        PacketListenerPriority.NORMAL);

        // Era-faithful attribute tooltips (display-only, on the outgoing packet copy). Three per-rule
        // transforms, each gated on its own restore module: strip the cooldown-spoof attack_speed line
        // (attack-cooldown), strip the meaningless toughness line (old-armour-strength), and re-value a
        // display-swapped weapon's attack_damage to the era number (legacy-tool-damage, marker-aware). A full
        // no-op when none are on; registered for the plugin lifetime, it gates internally.
        PacketEvents.getAPI().getEventManager()
                .registerListener(new WeaponAttributeTooltipHider(config),
                        PacketListenerPriority.NORMAL);

        // Cancels SOUND_EFFECT / ENTITY_SOUND_EFFECT packets whose sound name
        // falls in the entity.player.attack.* family (added in 1.9; absent
        // entirely in 1.7/1.8).  Cosmetic only — no game state is touched.
        // Registered for the plugin lifetime; the listener gates on the flag.
        PacketEvents.getAPI().getEventManager()
                .registerListener(new AttackSoundListener(config),
                        PacketListenerPriority.NORMAL);

        // Cancels outbound PARTICLE packets whose particle type is sweep_attack
        // (minecraft:sweep_attack — added in 1.9 with the sweep mechanic).
        // Registered for the plugin lifetime; the listener gates on the flag.
        PacketEvents.getAPI().getEventManager()
                .registerListener(new SweepParticleListener(config),
                        PacketListenerPriority.NORMAL);

        PacketEvents.getAPI().init();

        getLogger().info(() -> "Mental enabled — server " + environment.describe()
                + ", scheduling=" + scheduling.describe()
                + ", " + capabilities.describe());
    }

    @Override
    public void onDisable() {
        Mental.register(null);
        if (menuManager != null) {
            menuManager.shutdown();
            menuManager = null;
        }
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
        if (serverTickClock != null) {
            serverTickClock.stop();
            serverTickClock = null;
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
        }
        try {
            if (PacketEvents.getAPI() != null) {
                PacketEvents.getAPI().terminate();
            }
        } catch (Throwable failure) {
            getLogger().warning("PacketEvents termination threw: " + failure.getMessage());
        }
        modules = null;
        management = null;
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
        reportConfigIssues(warnings);
        applyDebugSettings(services.debug());
        modules.reloadAll();
        return warnings;
    }

    public @NotNull MentalServices services() {
        return services;
    }

    /** The config write-back service behind the management GUI and the API. */
    public @NotNull ManagementService management() {
        return management;
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
        // The netty-readable server tick behind the era attack-ordering
        // exclusion. Started here (before the GroundPacketTap registers) so the
        // Folia global counter is already advancing when the first movement
        // packet arrives; a no-op on Paper, which reads getCurrentTick directly.
        serverTickClock = new ServerTickClock(services.capabilities().folia(), Bukkit::getCurrentTick);
        serverTickClock.start(services.scheduling());
        // Observation only — feeds the wtap-extra freshness branch; with the
        // knob disabled (every default) it changes nothing.
        getServer().getPluginManager().registerEvents(services.sprintTracker(), this);
        // Pure observation as well: replicates the era movement-packet
        // bookkeeping (jump stamps, landings) into the ledger so knockback
        // verticals see the same baselines a legacy server's fields held.
        groundWatcher = new GroundTransitionWatcher(services, victimMotion, serverTickClock);
        getServer().getPluginManager().registerEvents(groundWatcher, this);
        groundWatcher.watchOnlinePlayers();
        // Mirror Mental's knockback onto Paper's EntityKnockbackEvent (1.20.6+) so
        // mid-pass observers (anticheats, SimpleBoxer) see Mental's value, not
        // vanilla's pre-override delta. A no-op below 1.20.6 (capability absent)
        // and while the knockback module is disabled; the velocity event stays the
        // authoritative apply, so the final wire velocity is unchanged.
        if (services.capabilities().knockbackEvent()) {
            new KnockbackEventMirror(services, knockbackPipeline).register(this);
        }

        KnockbackModule knockback = new KnockbackModule(services, victimMotion, knockbackPipeline);
        LatencyCompensationModule compensation = new LatencyCompensationModule(services, victimMotion);
        knockback.hints(compensation);

        modules.register(new AnticheatCompatModule(services));
        modules.register(new OcmCompatModule(services, services.ocmGate()));
        modules.register(new HitRegistrationModule(
                services, victimMotion, knockbackPipeline, compensation, serverTickClock));
        modules.register(new WtapRegistrationModule(services));
        modules.register(knockback);
        modules.register(compensation);
        modules.register(new FishingKnockbackModule(services, knockbackPipeline));
        modules.register(new FishingRodVelocityModule(services));
        modules.register(new ProjectileKnockbackModule(services, knockbackPipeline));
        modules.register(new AttackCooldownModule(services));
        modules.register(new AttackSoundModule(services));
        modules.register(new SweepModule(services));
        modules.register(new DisableCraftingModule(services));
        modules.register(new OffhandModule(services));
        modules.register(new GoldenAppleModule(services));
        modules.register(new EnderPearlCooldownModule(services));
        modules.register(new RegenModule(services));
        modules.register(new ArmourStrengthModule(services));
        modules.register(new ArmourDurabilityModule(services));
        modules.register(new PotionDurationModule(services));
        modules.register(new PotionValueModule(services));
        modules.register(new CritFallbackModule(services));
        modules.register(new ToolDurabilityModule(services));
        modules.register(new SwordBlockingModule(services));
        modules.register(new HitboxModule(services));
    }

    private void registerCommands() {
        CommandTree tree = new MentalCommands(this, menuManager).build();

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
                        .invoke(null, this, tree, "Open the Mental management menu; reload from the console.",
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

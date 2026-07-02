package me.vexmc.mental.v5;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import me.vexmc.mental.platform.Capabilities;
import me.vexmc.mental.platform.PersistentData;
import me.vexmc.mental.platform.Pings;
import me.vexmc.mental.platform.ServerEnvironment;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.kernel.wire.PositionRing;
import me.vexmc.mental.platform.SchedulingFactory;
import me.vexmc.mental.platform.debug.DebugCategory;
import me.vexmc.mental.platform.debug.DebugLog;
import me.vexmc.mental.api.Mental;
import me.vexmc.mental.v5.api.MentalFacade;
import me.vexmc.mental.v5.coexist.AnticheatPolicy;
import me.vexmc.mental.v5.coexist.OcmBinding;
import me.vexmc.mental.v5.command.MentalCommand;
import me.vexmc.mental.v5.gui.MenuContext;
import me.vexmc.mental.v5.gui.MenuManager;
import me.vexmc.mental.v5.manage.Management;
import me.vexmc.mental.v5.config.ConfigStore;
import me.vexmc.mental.v5.config.Migrations;
import me.vexmc.mental.v5.config.Overlay;
import me.vexmc.mental.v5.config.ProbeStrategy;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.SnapshotParser;
import me.vexmc.mental.v5.config.settings.CompensationSettings;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.delivery.DamageRouter;
import me.vexmc.mental.v5.delivery.DeskRouter;
import me.vexmc.mental.v5.delivery.HitIds;
import me.vexmc.mental.v5.delivery.MirrorListener;
import me.vexmc.mental.v5.feature.BukkitRegistrar;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.Reconciler;
import me.vexmc.mental.v5.feature.damage.ArmourDurabilityUnit;
import me.vexmc.mental.v5.feature.damage.ArmourStrengthUnit;
import me.vexmc.mental.v5.feature.damage.CritFallbackUnit;
import me.vexmc.mental.v5.feature.damage.DamageOwnership;
import me.vexmc.mental.v5.feature.damage.DamageShaper;
import me.vexmc.mental.v5.feature.damage.PotionValuesUnit;
import me.vexmc.mental.v5.feature.damage.SwordBlockingUnit;
import me.vexmc.mental.v5.feature.damage.ToolDurabilityUnit;
import me.vexmc.mental.v5.feature.damage.ToolWear;
import me.vexmc.mental.v5.feature.cadence.AttackCooldownUnit;
import me.vexmc.mental.v5.feature.cadence.AttackSoundsUnit;
import me.vexmc.mental.v5.feature.cadence.SweepUnit;
import me.vexmc.mental.v5.feature.sustain.EnderPearlCooldownUnit;
import me.vexmc.mental.v5.feature.sustain.GoldenApplesUnit;
import me.vexmc.mental.v5.feature.sustain.PotionDurationsUnit;
import me.vexmc.mental.v5.feature.sustain.RegenUnit;
import me.vexmc.mental.v5.feature.loadout.CraftingUnit;
import me.vexmc.mental.v5.feature.loadout.HitboxUnit;
import me.vexmc.mental.v5.feature.loadout.OffhandUnit;
import me.vexmc.mental.v5.feature.EphemeralDecoration;
import me.vexmc.mental.v5.platform.PlatformProfile;
import me.vexmc.mental.v5.feature.delivery.AnticheatCompatUnit;
import me.vexmc.mental.v5.feature.delivery.HitRegistrationUnit;
import me.vexmc.mental.v5.feature.delivery.OcmCompatUnit;
import me.vexmc.mental.v5.feature.delivery.WtapRegistrationUnit;
import me.vexmc.mental.v5.feature.knockback.FishingKnockbackUnit;
import me.vexmc.mental.v5.feature.knockback.KnockbackUnit;
import me.vexmc.mental.v5.feature.knockback.LatencyCompensationUnit;
import me.vexmc.mental.v5.feature.knockback.ProjectileKnockbackUnit;
import me.vexmc.mental.v5.feature.knockback.RodVelocityUnit;
import me.vexmc.mental.v5.rim.ConnectionDomains;
import me.vexmc.mental.v5.rim.PacketTap;
import me.vexmc.mental.v5.rim.ProbeRim;
import me.vexmc.mental.v5.rim.TransactionProbeRim;
import me.vexmc.mental.v5.rim.ValveListener;
import me.vexmc.mental.v5.session.SessionService;
import me.vexmc.mental.v5.session.ViewBuilder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.PluginCommand;
import org.jetbrains.annotations.NotNull;

/**
 * The v5 plugin entry point (spec §7; the retired v4 core's boot ordering
 * re-expressed on the two-realm seams). Owns lifecycle and the
 * config-snapshot reference; substance lives in the feature units, the session
 * service, and the packet rim.
 *
 * <p>Sub-phase 4A1 is the live spine: it boots, loads config, drives the clock,
 * binds the arbiter, and registers ZERO feature units — a disabled feature does
 * nothing to the game, so with no units the server is byte-for-byte vanilla
 * (zero-touch by construction). The session service, packet rim, and delivery
 * routers are wired in across 4A1.1–4A1.3.</p>
 *
 * <p>PacketEvents lifecycle mirrors the old plugin: built and loaded in
 * {@code onLoad}, {@code init()}'d in {@code onEnable} <em>after</em> every
 * always-on listener has registered (the netty-fast-path init ordering), and
 * terminated in {@code onDisable} last.</p>
 */
public final class MentalPluginV5 extends JavaPlugin {

    /** Per-victim journal ring depth — the bounded "what did we ship" seam (spec §3.6). */
    static final int JOURNAL_CAPACITY = 16;

    /** bStats plugin id (spec §13; kept from the retired plugin, config-gated on {@code metrics.enabled}). */
    private static final int BSTATS_PLUGIN_ID = 31788;

    private Metrics metrics;

    private Capabilities capabilities;
    private ServerEnvironment environment;
    private PlatformProfile platformProfile;
    private Scheduling scheduling;

    private ConfigStore configStore;
    private Overlay overlay;
    private volatile Snapshot snapshot;

    /** The verbose debug seam (zero-cost when off) — config-driven, re-applied on reload. */
    private final DebugLog debug = new DebugLog();

    private TickClock clock;
    private CounterTickClock counterClock;
    private TaskHandle counterClockTask;

    private OcmBinding ocmBinding;
    private BukkitRegistrar registrar;
    private Reconciler reconciler;

    private VelocityValve valve;
    private ViewBuilder viewBuilder;
    private SessionService sessions;
    private ConnectionDomains domains;
    private LatencyModel latency;
    private LatencyCompensationUnit latencyCompensation;
    /** The effective latency-probe transport for this server version, resolved at parse. */
    private ProbeStrategy probeTransport = ProbeStrategy.PING;
    private PositionRing positions;
    private HitIds hitIds;
    private AnticheatPolicy anticheatPolicy;
    private final AtomicBoolean wtapConsultWire = new AtomicBoolean(true);

    private Management management;
    private MenuManager menuManager;
    private MentalFacade facade;

    private List<String> parseIssues = List.of();

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
        this.capabilities = Capabilities.detect();
        this.environment = ServerEnvironment.parse(Bukkit.getBukkitVersion());
        // The platform profile (R10/B10): one manifest of typed resolutions built
        // once at boot — attribute/enchantment handles, protocol capabilities, and
        // the item-component adapters. A Required mapping break disables only its
        // owning feature; everything else is a typed OptionalSince fallback.
        this.platformProfile = PlatformProfile.resolve(
                environment, capabilities, message -> getLogger().warning(message));
        getLogger().info(platformProfile.bootReport());
        // No silent degradation (mandate B10): one loud line covers every PDC consumer at once when the
        // PersistentDataContainer API is absent (< 1.14). The effective-material marker becomes unreadable
        // (items resolve to their own type) and the temporary-shield tag + arrow-punch stamp ride in-memory
        // state instead of item NBT — documented, lifecycle-equivalent fallbacks, never a crash.
        if (!PersistentData.supported()) {
            getLogger().warning("persistent-data-container ABSENT (server < 1.14) — combat:effective_material "
                    + "reads degrade to the item's own type; the temporary-shield marker and arrow-punch "
                    + "stamp use in-memory fallbacks (no item NBT). See docs/effective-material-contract.md.");
        }
        this.scheduling = SchedulingFactory.create(this, capabilities);

        // Config: extract the bundled defaults (a fresh install is a v2 tree),
        // then run the migration chain that bumps it to v3 (creates the machine
        // overlay). Extract-before-migrate is the deliberate fresh-install order
        // (Phase 3 outcome): the bundle stamps config-version: 2, so migrate()
        // sees a v2 tree and carries it to v3 on first boot.
        Path dataDir = getDataFolder().toPath();
        this.configStore = new ConfigStore(dataDir, this::getResource, message -> getLogger().info(message));
        configStore.ensureDefaultFiles();
        Migrations.Result migration =
                new Migrations(dataDir, this::getResource, message -> getLogger().info(message)).migrate();
        if (!migration.stepsApplied().isEmpty()) {
            getLogger().info("config migrated " + migration.fromVersion() + " -> " + migration.toVersion());
        }
        this.overlay = new Overlay(configStore.overridesFile());
        this.snapshot = parseSnapshot();

        // The verbose debug seam (DebugLog): zero-cost when off — a sink fires
        // only for an active category and message suppliers run only then. The
        // sink is registered once; the active categories come from the config
        // debug section and are re-applied on every reload.
        debug.addSink((category, message) ->
                getLogger().info("[debug/" + category.key() + "] " + message));
        applyDebug(snapshot.debug());

        // The tick clock — the only clock currency in the delivery core. Modern
        // Paper reads getCurrentTick() (netty-safe there). Folia AND the legacy
        // backport targets below Bukkit.getCurrentTick() advance a global counter
        // any thread may read, starting at NO_TICK so a stalled counter degrades to
        // no-exclusion rather than a false universal match. The getCurrentTick
        // method reference lives only in the else branch, so it never links on a
        // server that lacks the method (invokedynamic bootstraps per taken branch).
        if (capabilities.folia() || !capabilities.currentTick()) {
            this.counterClock = new CounterTickClock();
            this.counterClockTask = scheduling.repeatGlobal(1L, 1L, counterClock::advance);
            this.clock = counterClock;
        } else {
            this.clock = new PaperTickClock(Bukkit::getCurrentTick);
        }

        // Coexistence: the binding stays ABSENT (Mental owns everything) until
        // the OCM compat feature scans and binds it — that feature lands with the
        // delivery family. The startup warnings derive from the current facts.
        this.ocmBinding = new OcmBinding();
        for (String warning : ocmBinding.warnings(enabledTokens())) {
            getLogger().warning("coexistence — " + warning);
        }

        // The packet rim's connection domains (spec §6): per-player sprint + ground
        // FSMs fed by the rim's movement taps. Created before the session service so
        // the session's tick-sampler can gate its ledger ground feed to packetless
        // players (a connected player's FSM feeds the ledger; the sampler serves the
        // rest — synthetic test players, in-process bots).
        this.domains = new ConnectionDomains(clock);

        // The D2 session domain: one CombatSession per player, ticked on its
        // owning region thread, publishing the frozen PlayerView the rim reads.
        // Always-on infrastructure — observation only, so zero-touch holds.
        this.valve = new VelocityValve();
        this.viewBuilder = new ViewBuilder(clock);
        this.positions = new PositionRing();
        this.sessions = new SessionService(
                scheduling, clock, viewBuilder, valve, ocmBinding, this::snapshot, positions, domains);
        sessions.start(this);

        // The packet rim (spec §6): the netty realm's only Bukkit-adjacent code —
        // parse wrappers into kernel records, feed the session inbox and the wire,
        // consume the outbound valve, match latency probes. Always-on, observation
        // only. Registered BEFORE PacketEvents.init(); PE teardown unregisters them.
        this.latency = new LatencyModel();
        this.hitIds = new HitIds();
        this.anticheatPolicy = new AnticheatPolicy();
        sessions.addForgetHook(domains::forget);
        sessions.addForgetHook(latency::forget);
        sessions.addForgetHook(valve::forget);
        PacketEvents.getAPI().getEventManager().registerListener(new PacketTap(domains, sessions, clock));
        PacketEvents.getAPI().getEventManager().registerListener(new ValveListener(valve));
        // Exactly ONE latency-probe receive rim, chosen by the effective transport
        // (version-determined at parse): below 1.17 the play PING/PONG channel is absent
        // on the wire, so probes ride window-confirmation transactions (TransactionProbeRim);
        // at/above 1.17 the dedicated play channel (ProbeRim). Both read only their own
        // inbound packets + the per-player LatencyModel record (netty discipline).
        PacketListenerAbstract probeRim = probeTransport == ProbeStrategy.TRANSACTION
                ? new TransactionProbeRim(latency)
                : new ProbeRim(latency);
        PacketEvents.getAPI().getEventManager().registerListener(probeRim);
        getLogger().info("latency probe transport: " + probeTransport + " (rim="
                + probeRim.getClass().getSimpleName() + ")");

        // The delivery routers (spec §3.4–§3.6): the desk's sole PlayerVelocityEvent
        // writer, the damage-pass router, and the capability-gated knockback-event
        // mirror. Always-on infra, inert while nothing submits to a desk (all of 4A1).
        getServer().getPluginManager().registerEvents(new DeskRouter(sessions, valve), this);
        getServer().getPluginManager().registerEvents(new DamageRouter(sessions, clock, hitIds), this);
        if (capabilities.knockbackEvent()) {
            new MirrorListener(sessions).register(this);
        }

        // The feature reconciler. The delivery + knockback families register here
        // (4A2); the damage/cadence/sustain/loadout families follow in 4B–4D. A
        // feature with no registered unit is simply never converged.
        this.registrar = new BukkitRegistrar(this, ocmBinding);
        this.reconciler = new Reconciler(
                registrar, message -> getLogger().warning(message), platformProfile.disabledFeatures());
        registerUnits();
        reconciler.converge(snapshot);

        // PacketEvents comes up LAST, after every always-on listener has
        // registered (4A1 registers none; the rim registers in 4A1.2).
        PacketEvents.getAPI().init();

        // The management write-back seam + the public API facade (spec §11; the
        // 4E seams pulled forward). The facade is published both in the static
        // Mental holder and the Bukkit ServicesManager.
        this.management = new Management(this);
        this.facade = new MentalFacade(this, management, latency);
        Mental.register(facade);
        getServer().getServicesManager().register(Mental.MentalApi.class, facade, this, ServicePriority.Normal);

        // The descriptor-driven management GUI (Phase 6). Always-on infrastructure
        // (like the command system it fronts): the click router is registered for
        // the plugin's lifetime and never touches the game, so zero-touch holds
        // trivially. Menu reads flow through the live snapshot + reconciler; every
        // write flows through Management / the machine overlay, never the human
        // YAML. The bare /mental opens it for a permitted player.
        this.menuManager = new MenuManager(new MenuContext(this, management));
        getServer().getPluginManager().registerEvents(menuManager, this);
        PluginCommand command = getCommand("mental");
        if (command != null) {
            command.setExecutor(new MentalCommand(this, menuManager));
        } else {
            getLogger().warning("plugin.yml is missing the 'mental' command — the menu and reload are unavailable.");
        }

        // bStats (spec §13; owner decision: KEEP). Config-gated on metrics.enabled
        // (default true) — warn-and-fallback: absent section reads true. The four
        // charts read the v5 sources live through suppliers, so a reload's changed
        // anticheat/OCM/probe posture is reflected on the next report.
        if (snapshot.metricsEnabled()) {
            this.metrics = new Metrics(this, BSTATS_PLUGIN_ID);
            metrics.addCustomChart(new SimplePie("anticheat_mode",
                    () -> snapshot().anticheat().mode().name().toLowerCase(Locale.ROOT)));
            metrics.addCustomChart(new SimplePie("probe_strategy",
                    () -> probeTransport().name().toLowerCase(Locale.ROOT)));
            metrics.addCustomChart(new SimplePie("scheduling_backend",
                    () -> scheduling.describe()));
            metrics.addCustomChart(new SimplePie("ocm_coordination",
                    () -> ocmBinding.mode().name().toLowerCase(Locale.ROOT)));
        } else {
            getLogger().info("bStats metrics disabled (metrics.enabled=false).");
        }

        getLogger().info(() -> "Mental v5 enabled — server " + environment.describe()
                + ", scheduling=" + scheduling.describe() + ", ping=" + Pings.describe()
                + ", " + capabilities.describe());
    }

    @Override
    public void onDisable() {
        // Reverse order, each step isolated (B8 teardown isolation): one failing
        // teardown never skips the rest.
        isolate("bStats shutdown", () -> {
            if (metrics != null) {
                metrics.shutdown();
                metrics = null;
            }
        });
        isolate("api facade unregister", () -> {
            Mental.register(null);
            if (facade != null) {
                getServer().getServicesManager().unregister(Mental.MentalApi.class, facade);
            }
        });
        isolate("menu manager shutdown", () -> {
            if (menuManager != null) {
                menuManager.shutdown();
            }
        });
        isolate("reconciler.closeAll", () -> {
            if (reconciler != null) {
                reconciler.closeAll();
            }
        });
        isolate("sessions.shutdown", () -> {
            if (sessions != null) {
                sessions.shutdown();
            }
        });
        isolate("counter clock", () -> {
            if (counterClockTask != null) {
                counterClockTask.cancel();
                counterClockTask = null;
            }
        });
        isolate("PacketEvents.terminate", () -> {
            if (PacketEvents.getAPI() != null) {
                PacketEvents.getAPI().terminate();
            }
        });
        getLogger().info("Mental v5 disabled.");
    }

    /**
     * Re-reads every config file, re-applies the overlay, parses a fresh
     * {@link Snapshot}, swaps it by one reference, and converges the reconciler
     * onto it. Session and rim code read the snapshot through {@link #snapshot()},
     * so the swap is picked up on the next tick without a restart. Returns the
     * warn-and-fallback issues for the invoking sender.
     */
    public @NotNull List<String> reloadAll() {
        configStore.ensureDefaultFiles();
        this.overlay = new Overlay(configStore.overridesFile());
        this.snapshot = parseSnapshot();
        applyDebug(snapshot.debug());
        reconciler.converge(snapshot);
        return parseIssues;
    }

    /**
     * Applies the config {@code debug} section onto the {@link DebugLog}: the
     * master switch plus the active category keys (unknown keys are ignored — the
     * framework stays forward-compatible with keys a newer config may carry).
     */
    private void applyDebug(@NotNull me.vexmc.mental.v5.config.settings.DebugSettings settings) {
        debug.enabled(settings.enabled());
        EnumSet<DebugCategory> active = EnumSet.noneOf(DebugCategory.class);
        for (String key : settings.categories()) {
            DebugCategory.byKey(key).ifPresent(active::add);
        }
        debug.activateAll(active);
    }

    /** The current immutable config snapshot — read through a reference the reload swaps. */
    public @NotNull Snapshot snapshot() {
        return snapshot;
    }

    /** The tick clock backing every {@code TickStamp} — Paper authoritative, Folia counter. */
    public @NotNull TickClock clock() {
        return clock;
    }

    /** The region-correct scheduling surface (reused from {@code common}). */
    public @NotNull Scheduling scheduling() {
        return scheduling;
    }

    /** The D2 session domain — the rim and routers read published views through it. */
    public @NotNull SessionService sessions() {
        return sessions;
    }

    /** The velocity valve — armed by the desk, consumed by the rim's outbound listener. */
    public @NotNull VelocityValve valve() {
        return valve;
    }

    /** The live OCM arbiter binding — the routers' frozen ownership source. */
    public @NotNull OcmBinding ocmBinding() {
        return ocmBinding;
    }

    /** Boot-time capability report (Folia, knockback event, …). */
    public @NotNull Capabilities capabilities() {
        return capabilities;
    }

    /** The parsed server-version report (major/minor/patch, recognized, raw). */
    public @NotNull ServerEnvironment environment() {
        return environment;
    }

    /** The boot-resolved platform manifest — the tester reads version-gated flags through it. */
    public @NotNull PlatformProfile platformProfile() {
        return platformProfile;
    }

    /** The config write-back seam behind the API facade and the (Phase 6) GUI. */
    public @NotNull Management management() {
        return management;
    }

    /**
     * Writes one machine-overlay key (persisted to {@code state/overrides.yml});
     * the caller reloads to pick it up. The management seam and the GUI route
     * their writes through here so the human config files are never re-serialized.
     */
    public void overlaySet(@NotNull String key, @NotNull Object value) {
        overlay.set(key, value);
    }

    /** True when {@code feature} has an open scope right now (the tester's module-active check). */
    public boolean featureActive(@NotNull Feature feature) {
        return reconciler.active(feature);
    }

    /**
     * Runtime module toggle via the machine overlay + reconverge — the write-back
     * seam the tester and (Phase 6) the GUI use. The human config files are never
     * touched; the overlay wins over them, so a reload picks up the toggle.
     */
    public @NotNull List<String> setModuleEnabled(@NotNull String yamlKey, boolean enabled) {
        overlay.set("modules." + yamlKey, enabled);
        return reloadAll();
    }

    /* ------------------------------------------------------------------ */

    /**
     * Registers the delivery and knockback feature units on the reconciler
     * (sub-phase 4A2). Always-on infrastructure (anticheat coexistence) and the
     * seven era engine features default ON; the reconciler converges each against
     * the snapshot. The fast path and the compensation transport register per-player
     * forget hooks so their CPS/gate/combat state does not leak across sessions.
     */
    private void registerUnits() {
        boolean folia = capabilities.folia();
        boolean modernProtocol = platformProfile.modernHurtProtocol();

        // The single crit/tool-damage verdict source, threaded to BOTH the fast
        // path (DamageShaper) and the vanilla-path crit fallback (mandate §4.6 —
        // the forgotten-gate bug class is structurally dead).
        DamageOwnership damageOwnership = new DamageOwnership(ocmBinding::mentalOwns);
        DamageShaper damageShaper = new DamageShaper(damageOwnership);
        ToolWear toolWear = new ToolWear(platformProfile);
        EphemeralDecoration swordBlockDecoration =
                new EphemeralDecoration(this, scheduling, platformProfile.swordBlock());

        HitRegistrationUnit hitRegistration = new HitRegistrationUnit(
                sessions, domains, latency, anticheatPolicy, wtapConsultWire, clock,
                this::snapshot, scheduling, valve, hitIds, damageShaper, toolWear, folia, modernProtocol,
                debug.scoped(DebugCategory.HITREG));
        this.latencyCompensation =
                new LatencyCompensationUnit(latency, scheduling, this::snapshot, probeTransport);
        sessions.addForgetHook(hitRegistration::forget);
        sessions.addForgetHook(latencyCompensation::forget);

        reconciler.register(new AnticheatCompatUnit(
                anticheatPolicy, this::snapshot, message -> getLogger().info(message)));
        // Live OCM arbiter binding — keeps the OcmBinding current (service API
        // per-player, else config-conservative), the driver the routers' frozen
        // ownership reads and the coexistence warnings depend on.
        reconciler.register(new OcmCompatUnit(
                ocmBinding, this::snapshot, this::enabledTokens, message -> getLogger().info(message)));
        reconciler.register(hitRegistration);
        reconciler.register(new WtapRegistrationUnit(wtapConsultWire));
        reconciler.register(new KnockbackUnit(
                sessions, domains, ocmBinding, latency, scheduling, this::snapshot,
                debug.scoped(DebugCategory.KNOCKBACK)));
        reconciler.register(latencyCompensation);
        reconciler.register(new FishingKnockbackUnit(
                sessions, ocmBinding, scheduling, this::snapshot, hitIds, clock, folia));
        reconciler.register(new RodVelocityUnit(ocmBinding, scheduling));
        reconciler.register(new ProjectileKnockbackUnit(
                this, sessions, ocmBinding, scheduling, this::snapshot, hitIds, clock,
                platformProfile.projectileKnockbackRestored()));

        // The damage family (4B). The fast-path legacy composition (tool table,
        // era potion values, era crit) lives in the shaper above and is read live
        // from the snapshot; CritFallback restores the era crit on fast-path-off
        // hits through the SAME ownership verdict; ArmourStrength/ArmourDurability
        // port the era flat armour reduction and Unbreaking skip.
        reconciler.register(new ArmourStrengthUnit());
        reconciler.register(new ArmourDurabilityUnit());
        reconciler.register(new CritFallbackUnit(damageOwnership, this::snapshot));
        reconciler.register(new ToolDurabilityUnit());
        reconciler.register(new PotionValuesUnit());
        reconciler.register(new SwordBlockingUnit(domains, clock, swordBlockDecoration));

        // The cadence family (4C). Attack-cooldown is the complete B5 contract in
        // one scope (server rule + client spoof + tooltip hider + sweep re-disable);
        // attack-sounds and sweep are the standalone cosmetic/event suppressors.
        reconciler.register(new AttackCooldownUnit(scheduling, platformProfile.weaponTooltip()));
        reconciler.register(new AttackSoundsUnit());
        reconciler.register(new SweepUnit());

        // The sustain family (4C). Golden apples + potion durations compute era
        // values from the kernel and apply them at the confirmed terminal event
        // (B13); regen drives per-player 80-tick heal tasks; the ender-pearl unit
        // clears the 1.9 throw cooldown. All pure Bukkit + Scheduling.
        reconciler.register(new GoldenApplesUnit(this, scheduling));
        reconciler.register(new EnderPearlCooldownUnit(scheduling));
        reconciler.register(new RegenUnit(scheduling));
        reconciler.register(new PotionDurationsUnit());

        // The loadout family (4D). Crafting + off-hand are pure Bukkit-event rules:
        // crafting nulls a blocked crafting result (SHIELD by default); off-hand
        // blocks the 1.9 slot via the kernel OffhandPolicy (live snapshot reads).
        // Hitbox tunes whichever era-reach lever the running server exposes — the
        // ENTITY_INTERACTION_RANGE attribute (1.20.5+) and the ATTACK_RANGE weapon
        // component (1.21.5+, boot-probed on the PlatformProfile) — a documented no-op
        // where neither exists (the client picks the melee target).
        reconciler.register(new CraftingUnit(this::snapshot));
        reconciler.register(new OffhandUnit(this::snapshot, scheduling));
        reconciler.register(new HitboxUnit(this, scheduling, platformProfile.attackRange()));
    }

    private Snapshot parseSnapshot() {
        ConfigStore.Sources sources = configStore.loadSources();
        overlay.apply(sources.main(), sources.knockback(), sources.hitReg(), sources.latency());
        SnapshotParser.Result result = SnapshotParser.parse(
                sources.main(), sources.knockback(), sources.hitReg(), sources.latency(), sources.profiles());
        List<String> issues = new ArrayList<>(result.issues());
        // Reconcile the raw configured probe strategy to the effective wire transport for
        // THIS server version — the parser is deliberately version-blind, so the mapping
        // lives here where the ServerEnvironment is known. Below 1.17 the play PING/PONG
        // channel is absent, so PING resolves to window-confirmation TRANSACTION probes
        // (an info line — the expected legacy path, not a config problem); a legacy-only
        // TRANSACTION on 1.17+ or the retired KEEPALIVE is a loud warn that ALSO joins the
        // reload issue report so /mental reload surfaces it to the admin.
        this.probeTransport = ProbeStrategy.resolveEffective(
                configuredProbeStrategy(result.snapshot()),
                !environment.isAtLeast(1, 17, 0),
                info -> getLogger().info("latency probe — " + info),
                warn -> issues.add("latency-compensation.yml: probe-strategy: " + warn));
        for (String issue : issues) {
            getLogger().warning("config — " + issue);
        }
        this.parseIssues = issues;
        return result.snapshot();
    }

    /** The RAW configured probe strategy (pre-resolution) read from a snapshot. */
    @SuppressWarnings("unchecked")
    private ProbeStrategy configuredProbeStrategy(Snapshot snap) {
        SettingsKey<CompensationSettings> key =
                (SettingsKey<CompensationSettings>) Feature.LATENCY_COMPENSATION.settingsKey();
        return snap.settings(key).probeStrategy();
    }

    /**
     * The effective latency-probe transport this server uses — the {@code probe_strategy}
     * bStats chart source and the tester's regression pin (TRANSACTION below 1.17, PING
     * at/above). Version-determined and resolved once per parse, so it is stable across
     * a reload (the version never changes) even if the configured value does.
     */
    public @NotNull ProbeStrategy probeTransport() {
        return probeTransport;
    }

    /**
     * Boot self-test seam (tester): drives the active transport's probe send path once
     * so a pre-1.17 wrapper classload/encoding break surfaces at boot. Returns true on a
     * clean pass (see {@link LatencyCompensationUnit#probeSelfTest()} for why clientless
     * test players cannot observe the wire round-trip here).
     */
    public boolean probeSelfTest() {
        return latencyCompensation != null && latencyCompensation.probeSelfTest();
    }

    /** The tokens every enabled feature restores — the input to the coexistence warnings. */
    private Set<MechanicToken> enabledTokens() {
        Set<MechanicToken> tokens = EnumSet.noneOf(MechanicToken.class);
        for (Feature feature : Feature.values()) {
            if (snapshot.enabled(feature)) {
                tokens.addAll(feature.tokens());
            }
        }
        return tokens;
    }

    private void isolate(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable failure) {
            getLogger().warning("teardown step '" + what + "' threw: " + failure);
        }
    }
}

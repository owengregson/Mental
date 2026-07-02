package me.vexmc.mental.v5;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import me.vexmc.mental.common.platform.Capabilities;
import me.vexmc.mental.common.platform.ServerEnvironment;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.platform.SchedulingFactory;
import me.vexmc.mental.v5.coexist.OcmBinding;
import me.vexmc.mental.v5.config.ConfigStore;
import me.vexmc.mental.v5.config.Migrations;
import me.vexmc.mental.v5.config.Overlay;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.SnapshotParser;
import me.vexmc.mental.v5.feature.BukkitRegistrar;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.Reconciler;
import me.vexmc.mental.v5.session.SessionService;
import me.vexmc.mental.v5.session.ViewBuilder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * The v5 plugin entry point (spec §7; the retired {@code MentalPlugin} boot
 * ordering re-expressed on the two-realm seams). Owns lifecycle and the
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

    private Capabilities capabilities;
    private ServerEnvironment environment;
    private Scheduling scheduling;

    private ConfigStore configStore;
    private Overlay overlay;
    private volatile Snapshot snapshot;

    private TickClock clock;
    private CounterTickClock foliaClock;
    private TaskHandle foliaClockTask;

    private OcmBinding ocmBinding;
    private BukkitRegistrar registrar;
    private Reconciler reconciler;

    private VelocityValve valve;
    private ViewBuilder viewBuilder;
    private SessionService sessions;

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

        // The tick clock — the only clock currency in the delivery core. Paper
        // reads getCurrentTick() (netty-safe there); Folia advances a global
        // counter any thread may read, starting at NO_TICK so a stalled counter
        // degrades to no-exclusion rather than a false universal match.
        if (capabilities.folia()) {
            this.foliaClock = new CounterTickClock();
            this.foliaClockTask = scheduling.repeatGlobal(1L, 1L, foliaClock::advance);
            this.clock = foliaClock;
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

        // The D2 session domain: one CombatSession per player, ticked on its
        // owning region thread, publishing the frozen PlayerView the rim reads.
        // Always-on infrastructure — observation only, so zero-touch holds.
        this.valve = new VelocityValve();
        this.viewBuilder = new ViewBuilder(clock);
        this.sessions = new SessionService(scheduling, clock, viewBuilder, valve, ocmBinding, this::snapshot);
        sessions.start(this);

        // The feature reconciler. 4A1 registers ZERO units — the spine is a
        // no-op server. Later sub-phases register their families here before the
        // converge.
        this.registrar = new BukkitRegistrar(this, ocmBinding);
        this.reconciler = new Reconciler(registrar, message -> getLogger().warning(message));
        reconciler.converge(snapshot);

        // PacketEvents comes up LAST, after every always-on listener has
        // registered (4A1 registers none; the rim registers in 4A1.2).
        PacketEvents.getAPI().init();

        getLogger().info(() -> "Mental v5 enabled — server " + environment.describe()
                + ", scheduling=" + scheduling.describe() + ", " + capabilities.describe());
    }

    @Override
    public void onDisable() {
        // Reverse order, each step isolated (B8 teardown isolation): one failing
        // teardown never skips the rest.
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
        isolate("folia clock", () -> {
            if (foliaClockTask != null) {
                foliaClockTask.cancel();
                foliaClockTask = null;
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
        reconciler.converge(snapshot);
        return parseIssues;
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

    /* ------------------------------------------------------------------ */

    private Snapshot parseSnapshot() {
        ConfigStore.Sources sources = configStore.loadSources();
        overlay.apply(sources.main(), sources.knockback(), sources.hitReg(), sources.latency());
        SnapshotParser.Result result = SnapshotParser.parse(
                sources.main(), sources.knockback(), sources.hitReg(), sources.latency(), sources.profiles());
        for (String issue : result.issues()) {
            getLogger().warning("config — " + issue);
        }
        this.parseIssues = result.issues();
        return result.snapshot();
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

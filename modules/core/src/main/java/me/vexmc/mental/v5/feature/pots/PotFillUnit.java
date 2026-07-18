package me.vexmc.mental.v5.feature.pots;

import java.util.function.Consumer;
import java.util.function.Supplier;
import me.vexmc.mental.platform.CommandMaps;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.PotFillSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Wires the {@code /potfill} command onto the reconciler (POTS family). Enabling
 * the feature dynamically registers the command on the server command map;
 * disabling (reload-off, scope close, shutdown) unregisters it cleanly — so a
 * disabled feature leaves NO command-map or tab-complete trace (zero-touch).
 *
 * <p>The command, filler, item factory and economy port are all constructed once
 * per enable; settings are read LIVE from the snapshot on each invocation, so a
 * reload's changed permission/cost takes effect without a re-register. The one
 * loud enable-time line (mandate B10) notes an unconstructible potion or an
 * economy-required-but-absent configuration.</p>
 */
public final class PotFillUnit implements FeatureUnit {

    private final Plugin plugin;
    private final Supplier<Snapshot> snapshot;
    private final Scheduling scheduling;
    private final Consumer<String> log;
    private final EconomyPort economy;
    private final HealPotItems items;

    public PotFillUnit(
            @NotNull Plugin plugin,
            @NotNull Supplier<Snapshot> snapshot,
            @NotNull Scheduling scheduling,
            @NotNull Consumer<String> log) {
        this(plugin, snapshot, scheduling, log, new VaultEconomyPort(), new HealPotItems());
    }

    /** Test seam: inject a stub economy port and item factory (no Vault / server registry needed). */
    PotFillUnit(
            @NotNull Plugin plugin,
            @NotNull Supplier<Snapshot> snapshot,
            @NotNull Scheduling scheduling,
            @NotNull Consumer<String> log,
            @NotNull EconomyPort economy,
            @NotNull HealPotItems items) {
        this.plugin = plugin;
        this.snapshot = snapshot;
        this.scheduling = scheduling;
        this.log = log;
        this.economy = economy;
        this.items = items;
    }

    @Override
    public Feature descriptor() {
        return Feature.POT_FILL;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        PotFiller filler = new PotFiller(items);
        PotFillCommand command = new PotFillCommand(this::settings, economy, filler, scheduling);

        if (!items.available()) {
            log.accept("pot-fill: this server cannot construct the heal potion (" + items.describe()
                    + ") — /potfill will refuse until resolved.");
        }
        PotFillSettings settings = settings();
        if (settings.costPerPotion() > 0.0 && !economy.present()) {
            log.accept("pot-fill: cost-per-potion is " + settings.costPerPotion()
                    + " but no economy (Vault) is available — /potfill will refuse charged fills"
                    + " until one is installed.");
        }

        // Dynamic registration as the feature's one resource: the task starter
        // registers the command and returns the unregister closeable, so scope
        // close (disable/reload-off/shutdown) removes it — command, aliases,
        // namespaced forms and tab-complete all gone.
        scope.task(() -> {
            if (!CommandMaps.register(plugin, command)) {
                log.accept("pot-fill: the server exposes no command map — /potfill could not be registered.");
            }
            return () -> CommandMaps.unregister(command);
        });
    }

    @SuppressWarnings("unchecked")
    private PotFillSettings settings() {
        return snapshot.get().settings(
                (SettingsKey<PotFillSettings>) Feature.POT_FILL.settingsKey());
    }
}

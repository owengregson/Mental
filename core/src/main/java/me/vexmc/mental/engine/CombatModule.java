package me.vexmc.mental.engine;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.common.debug.DebugLog;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * Base of every feature module.
 *
 * <p>Lifecycle is owned by the {@link ModuleRegistry}: a module only ever
 * sees {@code onEnable} when inactive, {@code onDisable} when active, and
 * {@code onReload} while active. Listeners registered through
 * {@link #listen(Listener)} are unhooked automatically on disable.</p>
 */
public abstract class CombatModule {

    protected final MentalServices services;
    protected final DebugLog.Scoped debug;

    private final String id;
    private final String displayName;
    private final String description;
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean active;

    protected CombatModule(
            @NotNull MentalServices services,
            @NotNull String id,
            @NotNull String displayName,
            @NotNull String description,
            @NotNull DebugCategory debugCategory) {
        this.services = services;
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.debug = services.debug().scoped(debugCategory);
    }

    public final @NotNull String id() {
        return id;
    }

    public final @NotNull String displayName() {
        return displayName;
    }

    public final @NotNull String description() {
        return description;
    }

    public final boolean active() {
        return active;
    }

    /** Whether configuration wants this module running right now. */
    public abstract boolean configEnabled();

    final void enable() throws Exception {
        if (active) {
            return;
        }
        onEnable();
        active = true;
        debug.log(() -> id + " enabled");
    }

    final void disable() {
        if (!active) {
            return;
        }
        try {
            onDisable();
        } finally {
            for (Listener listener : listeners) {
                HandlerList.unregisterAll(listener);
            }
            listeners.clear();
            active = false;
        }
        debug.log(() -> id + " disabled");
    }

    final void reload() throws Exception {
        if (active) {
            onReload();
        }
    }

    protected abstract void onEnable() throws Exception;

    protected abstract void onDisable();

    protected void onReload() throws Exception {}

    protected final void listen(@NotNull Listener listener) {
        listeners.add(listener);
        services.plugin().getServer().getPluginManager().registerEvents(listener, services.plugin());
    }
}

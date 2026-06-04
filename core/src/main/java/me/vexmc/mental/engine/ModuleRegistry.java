package me.vexmc.mental.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Ordered module lifecycle with per-module exception isolation: one
 * misbehaving module never takes its siblings down with it.
 */
public final class ModuleRegistry {

    private final Map<String, CombatModule> modules = new LinkedHashMap<>();
    private final Logger logger;

    public ModuleRegistry(@NotNull Logger logger) {
        this.logger = logger;
    }

    public void register(@NotNull CombatModule module) {
        CombatModule previous = modules.putIfAbsent(module.id(), module);
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate module id: " + module.id());
        }
    }

    public @NotNull Optional<CombatModule> byId(@NotNull String id) {
        return Optional.ofNullable(modules.get(id));
    }

    public @NotNull Collection<CombatModule> all() {
        return List.copyOf(modules.values());
    }

    public @NotNull List<String> ids() {
        return List.copyOf(modules.keySet());
    }

    public void enableAll() {
        for (CombatModule module : modules.values()) {
            if (module.configEnabled()) {
                enableQuietly(module);
            }
        }
    }

    public void disableAll() {
        List<CombatModule> reversed = new ArrayList<>(modules.values());
        java.util.Collections.reverse(reversed);
        for (CombatModule module : reversed) {
            disableQuietly(module);
        }
    }

    /** Converges every module onto its configured state, then refreshes the active ones. */
    public void reloadAll() {
        for (CombatModule module : modules.values()) {
            boolean wanted = module.configEnabled();
            if (wanted && !module.active()) {
                enableQuietly(module);
            } else if (!wanted && module.active()) {
                disableQuietly(module);
            } else if (module.active()) {
                try {
                    module.reload();
                } catch (Exception failure) {
                    logger.log(Level.SEVERE, "Module '" + module.id() + "' failed to reload", failure);
                }
            }
        }
    }

    /** Applies one module's configured state immediately (used by /mental module toggles). */
    public void converge(@NotNull CombatModule module) {
        boolean wanted = module.configEnabled();
        if (wanted && !module.active()) {
            enableQuietly(module);
        } else if (!wanted && module.active()) {
            disableQuietly(module);
        }
    }

    private void enableQuietly(CombatModule module) {
        try {
            module.enable();
        } catch (Exception failure) {
            logger.log(Level.SEVERE, "Module '" + module.id() + "' failed to enable", failure);
            disableQuietly(module);
        }
    }

    private void disableQuietly(CombatModule module) {
        try {
            module.disable();
        } catch (Exception failure) {
            logger.log(Level.SEVERE, "Module '" + module.id() + "' failed to disable", failure);
        }
    }
}

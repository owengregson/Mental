package me.vexmc.mental.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after a rules bundle is applied server-wide — through the management GUI's
 * Combat Presets screen or {@code Management.applyBundle}. A rules bundle is a
 * macro that flips a batch of module toggles (and optionally the knockback profile
 * and effects preset) in one atomic reload, so a single apply can change many
 * features at once; this event fires exactly once, after the configuration
 * snapshot has been swapped. It carries only the bundle's name — a listener that
 * wants the resulting feature states reads them from the (already-updated) live
 * config.
 *
 * <p>Fires on the thread that performed the apply (the main thread on non-Folia
 * servers, the global region thread on Folia), synchronously, after the reload —
 * so a listener querying module state or the active profile sees the new values.
 * A practice core can listen here to re-sync a whole ruleset in one hop rather
 * than tracking each module toggle. Added in API generation 3
 * (publication 3.1.0); {@code apiVersion()} is unchanged.</p>
 */
public final class RulesBundleAppliedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String bundle;

    public RulesBundleAppliedEvent(@NotNull String bundle) {
        this.bundle = bundle;
    }

    /** The name (stem) of the bundle that was applied. */
    public @NotNull String getBundle() {
        return bundle;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}

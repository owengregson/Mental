package me.vexmc.mental.module.knockback;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-victim carrier from {@link KnockbackPipeline}'s HIGH apply handler to its
 * MONITOR ledger-record handler for the SAME {@code PlayerVelocityEvent},
 * without a wall-clock TTL.
 *
 * <p>Both handlers run synchronously on one thread in priority order (HIGH then
 * MONITOR) before the next event is dispatched, and both ignore cancelled
 * events. The HIGH handler {@link #clearFor clears} the victim's slot on entry
 * and {@link #setFor sets} it only when it actually applied a vector; the
 * MONITOR handler {@link #takeFor takes} it. Any event reaching MONITOR
 * uncancelled also passed HIGH (which cleared, then maybe set, the slot), so
 * MONITOR reads exactly THIS event's tag — a GC pause between the two handlers
 * can no longer strand it, which the prior 25&nbsp;ms TTL allowed.</p>
 *
 * <p>Keyed by victim, not by thread: a nested velocity event for a DIFFERENT
 * victim (a third-party HIGHEST handler calling {@code setVelocity} between our
 * HIGH and MONITOR) cannot clobber this victim's tag, and on Folia the
 * per-region-thread velocity events stay isolated by victim as well. The
 * clear-at-entry is what lets the TTL go: a tag leaked by an event cancelled
 * between HIGH and MONITOR is dropped the next time that victim's velocity event
 * passes HIGH, before its MONITOR could read it.</p>
 */
final class AppliedTagStore {

    private final ConcurrentHashMap<UUID, AppliedTag> tags = new ConcurrentHashMap<>();

    /** Drops any tag for {@code victim}; the HIGH handler's first action. */
    void clearFor(@NotNull UUID victim) {
        tags.remove(victim);
    }

    /** Records what the HIGH handler applied for {@code victim}. */
    void setFor(@NotNull UUID victim, @NotNull AppliedTag tag) {
        tags.put(victim, tag);
    }

    /** Consumes {@code victim}'s tag for the MONITOR handler ({@code null} if none). */
    @Nullable AppliedTag takeFor(@NotNull UUID victim) {
        return tags.remove(victim);
    }

    void forget(@NotNull UUID victim) {
        tags.remove(victim);
    }

    void clear() {
        tags.clear();
    }
}

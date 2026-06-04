package me.vexmc.mental.common.debug;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

/**
 * Zero-cost-when-off verbose logging.
 *
 * <p>The hot-path contract: a disabled log is one volatile read and a branch.
 * Message suppliers are only invoked when their category is live, so callers
 * may interpolate freely without allocating on the fast path.</p>
 */
public final class DebugLog {

    /** Receives already-rendered debug lines; implementations must be thread-safe. */
    public interface Sink {
        void accept(@NotNull DebugCategory category, @NotNull String message);
    }

    private volatile boolean enabled;
    private volatile Set<DebugCategory> active = EnumSet.noneOf(DebugCategory.class);
    private final List<Sink> sinks = new CopyOnWriteArrayList<>();

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean value) {
        this.enabled = value;
    }

    public boolean active(@NotNull DebugCategory category) {
        return enabled && active.contains(category);
    }

    public @NotNull Set<DebugCategory> activeCategories() {
        return Set.copyOf(active);
    }

    public void activate(@NotNull DebugCategory category, boolean value) {
        EnumSet<DebugCategory> next = EnumSet.noneOf(DebugCategory.class);
        next.addAll(active);
        if (value) {
            next.add(category);
        } else {
            next.remove(category);
        }
        this.active = next;
    }

    public void activateAll(@NotNull Set<DebugCategory> categories) {
        this.active = categories.isEmpty()
                ? EnumSet.noneOf(DebugCategory.class)
                : EnumSet.copyOf(categories);
    }

    public void addSink(@NotNull Sink sink) {
        sinks.add(sink);
    }

    public void removeSink(@NotNull Sink sink) {
        sinks.remove(sink);
    }

    public void log(@NotNull DebugCategory category, @NotNull Supplier<String> message) {
        if (!enabled || !active.contains(category)) {
            return;
        }
        String rendered = message.get();
        for (Sink sink : sinks) {
            sink.accept(category, rendered);
        }
    }

    public @NotNull Scoped scoped(@NotNull DebugCategory category) {
        return new Scoped(this, category);
    }

    /** A category-bound view, handed to modules so call sites stay terse. */
    public record Scoped(@NotNull DebugLog log, @NotNull DebugCategory category) {

        public boolean active() {
            return log.active(category);
        }

        public void log(@NotNull Supplier<String> message) {
            log.log(category, message);
        }
    }
}

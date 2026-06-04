package me.vexmc.mental.module.compensation;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Who is currently "in combat" — probes are only spent on players whose
 * latency actually matters right now. Stale tags are reaped by the probe task.
 */
final class CombatTracker {

    private final ConcurrentHashMap<UUID, Long> tagged = new ConcurrentHashMap<>();

    void mark(@NotNull UUID player, long now) {
        tagged.put(player, now);
    }

    void evictExpired(long now, long timeoutMillis) {
        tagged.entrySet().removeIf(entry -> now - entry.getValue() > timeoutMillis);
    }

    @NotNull Set<UUID> snapshot() {
        return Set.copyOf(tagged.keySet());
    }

    void forget(@NotNull UUID player) {
        tagged.remove(player);
    }

    void clear() {
        tagged.clear();
    }
}

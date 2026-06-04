package me.vexmc.mental.module.hitreg;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Per-player clicks-per-second limiter over a sliding one-second window.
 * Thread-safe: state is per-UUID, briefly synchronized per bucket. A cap of
 * zero disables limiting.
 */
final class CpsLimiter {

    private static final long WINDOW_MILLIS = 1_000L;

    private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    /** True if a hit at {@code now} fits within {@code maxCps} for the trailing second. */
    boolean tryAcquire(@NotNull UUID player, int maxCps, long now) {
        if (maxCps <= 0) {
            return true;
        }
        return buckets.computeIfAbsent(player, id -> new Bucket(maxCps)).tryAcquire(maxCps, now);
    }

    void forget(@NotNull UUID player) {
        buckets.remove(player);
    }

    void clear() {
        buckets.clear();
    }

    private static final class Bucket {

        private long[] window;
        private int head;
        private int size;

        Bucket(int capacity) {
            this.window = new long[Math.max(1, capacity)];
        }

        synchronized boolean tryAcquire(int capacity, long now) {
            if (window.length != Math.max(1, capacity)) {
                window = new long[Math.max(1, capacity)];
                head = 0;
                size = 0;
            }
            if (size == window.length) {
                long oldest = window[head];
                if (now - oldest < WINDOW_MILLIS) {
                    return false;
                }
                window[head] = now;
                head = (head + 1) % window.length;
                return true;
            }
            int tail = (head + size) % window.length;
            window[tail] = now;
            size++;
            return true;
        }
    }
}

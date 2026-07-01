package me.vexmc.mental.kernel.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player position ring buffer — the rewind source for reach validation.
 *
 * <p>One sample per tick per tracked player (the hit-registration snapshot
 * task), forty samples deep: two seconds of history, comfortably past any
 * playable ping plus interpolation. Writes happen on the player's owning
 * thread; the netty fast path reads concurrently — each ring synchronizes
 * its tiny critical sections, and contention is effectively nil at one
 * write per tick.</p>
 */
public final class PositionRing {

    private static final int CAPACITY = 40;

    /** One historical position; {@code nanos} is the owning-thread sample time. */
    public record Sample(double x, double y, double z, long nanos) {}

    private static final class Ring {
        private final Sample[] samples = new Sample[CAPACITY];
        private int head;

        synchronized void record(Sample sample) {
            samples[head] = sample;
            head = (head + 1) % CAPACITY;
        }

        synchronized List<Sample> within(long fromNanos, long toNanos) {
            List<Sample> matched = new ArrayList<>();
            for (Sample sample : samples) {
                if (sample != null && sample.nanos() >= fromNanos && sample.nanos() <= toNanos) {
                    matched.add(sample);
                }
            }
            return matched;
        }
    }

    private final ConcurrentHashMap<UUID, Ring> rings = new ConcurrentHashMap<>();

    /** Records the player's position now; owning thread. */
    public void record(UUID player, double x, double y, double z, long nowNanos) {
        rings.computeIfAbsent(player, ignored -> new Ring())
                .record(new Sample(x, y, z, nowNanos));
    }

    /**
     * Every sample within {@code windowNanos} of {@code instantNanos} —
     * the candidate victim positions for a hit the attacker threw at that
     * (rewound) moment. Empty when the player is untracked or the instant
     * predates the ring.
     */
    public List<Sample> samplesAround(UUID player, long instantNanos, long windowNanos) {
        Ring ring = rings.get(player);
        if (ring == null) {
            return List.of();
        }
        return ring.within(instantNanos - windowNanos, instantNanos + windowNanos);
    }

    public void forget(UUID player) {
        rings.remove(player);
    }

    public void clear() {
        rings.clear();
    }
}

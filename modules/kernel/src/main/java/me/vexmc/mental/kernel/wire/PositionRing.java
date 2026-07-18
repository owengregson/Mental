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

        synchronized Sample latest() {
            return samples[(head - 1 + CAPACITY) % CAPACITY];
        }

        synchronized List<Sample> recent(int n) {
            int want = Math.min(n, CAPACITY);
            List<Sample> out = new ArrayList<>(want);
            for (int back = want - 1; back >= 0; back--) {
                Sample sample = samples[(head - 1 - back + 2 * CAPACITY) % CAPACITY];
                if (sample != null) {
                    out.add(sample);
                }
            }
            return out;
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

    /**
     * The most recently recorded sample for {@code player}, or {@code null} when
     * untracked — the netty fast path's off-region-safe position source (the
     * owning-thread sampler records it, the netty reader consumes the frozen
     * value; no live-entity read).
     */
    public Sample latest(UUID player) {
        Ring ring = rings.get(player);
        return ring == null ? null : ring.latest();
    }

    /**
     * The last {@code n} samples for {@code player}, oldest-first (up to the ring
     * capacity), or an empty list when untracked — the pocket-servo precision
     * round's drift/chase estimators read consecutive per-tick deltas from it. Same
     * synchronized section as {@link #latest}; the owning thread writes, the netty
     * reader consumes frozen samples.
     */
    public List<Sample> recent(UUID player, int n) {
        Ring ring = rings.get(player);
        return ring == null ? List.of() : ring.recent(n);
    }

    public void forget(UUID player) {
        rings.remove(player);
    }

    public void clear() {
        rings.clear();
    }
}

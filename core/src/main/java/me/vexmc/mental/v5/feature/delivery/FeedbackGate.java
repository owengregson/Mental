package me.vexmc.mental.v5.feature.delivery;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-victim pacing for the netty velocity pre-send (the retired
 * {@code HitFeedbackGate}). Vanilla applies a knockback-bearing hit the moment
 * {@code noDamageTicks} falls to {@code max/2}; a perfect-cadence combo throws
 * legal hits exactly one half-window apart, so the gate window is
 * {@code (max/2 − 1)} ticks — a window equal to the cadence would make every
 * legal hit race the boundary on millisecond jitter (the floaty-combo signature).
 * The gate paces the WIRE velocity burst only; the authoritative pass owns its
 * own immunity pacing.
 */
public final class FeedbackGate {

    private final ConcurrentHashMap<UUID, Long> lastPreSend = new ConcurrentHashMap<>();

    /**
     * True when a velocity pre-send at {@code nowMillis} respects the victim's
     * {@code minIntervalMillis} window (consuming the slot on success).
     */
    public boolean tryPreSend(UUID victim, long nowMillis, long minIntervalMillis) {
        if (minIntervalMillis <= 0) {
            lastPreSend.put(victim, nowMillis);
            return true;
        }
        Long previous = lastPreSend.get(victim);
        if (previous != null && nowMillis - previous < minIntervalMillis) {
            return false;
        }
        lastPreSend.put(victim, nowMillis);
        return true;
    }

    public void forget(UUID victim) {
        lastPreSend.remove(victim);
    }
}

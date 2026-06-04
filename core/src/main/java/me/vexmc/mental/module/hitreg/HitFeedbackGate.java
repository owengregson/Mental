package me.vexmc.mental.module.hitreg;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Per-victim rate gate for feedback pre-sends.
 *
 * <p>Vanilla applies a knockback-bearing hit roughly once per victim
 * invulnerability window; pre-sending on every spam-click would re-launch the
 * victim's client each time. This admits at most one pre-send per victim per
 * window, keyed by victim (an invulnerability is shared across attackers),
 * decided inside an atomic compute so sub-tick click bursts race-resolve to
 * exactly one admission. A window of zero disables gating.</p>
 */
final class HitFeedbackGate {

    private final ConcurrentHashMap<UUID, Long> nextEligibleMillis = new ConcurrentHashMap<>();

    boolean tryPreSend(@NotNull UUID victim, long nowMillis, long windowMillis) {
        if (windowMillis <= 0L) {
            return true;
        }
        boolean[] admitted = {false};
        nextEligibleMillis.compute(victim, (id, nextEligible) -> {
            if (nextEligible == null || nowMillis >= nextEligible) {
                admitted[0] = true;
                return nowMillis + windowMillis;
            }
            return nextEligible;
        });
        return admitted[0];
    }

    void forget(@NotNull UUID victim) {
        nextEligibleMillis.remove(victim);
    }

    void clear() {
        nextEligibleMillis.clear();
    }
}

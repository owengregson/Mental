package me.vexmc.strikesync.module.hitreg;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-victim rate gate for the async combat-feedback pre-send.
 *
 * <h2>Why this exists</h2>
 * The fast path pre-sends a velocity (and hurt-animation) packet straight from
 * the netty thread the moment an attack packet is accepted. Vanilla, by
 * contrast, only applies a knockback-bearing hit once per victim
 * <em>invulnerability window</em> (~10 ticks): a second hit inside that window
 * deals no knockback at all unless it out-damages the first. Without a matching
 * gate, every spam-click would ship a fresh velocity packet and the victim's
 * client would re-launch on each one — the "spam-hit → fly across the map" bug.
 *
 * <p>This gate admits at most one pre-send per victim per {@code windowMillis},
 * reproducing vanilla's cadence on the netty thread without touching Bukkit
 * state. It is keyed by <em>victim</em> (not attacker), mirroring vanilla: a
 * victim's invulnerability is shared across everyone hitting them, so two
 * attackers spamming one target still can't exceed the vanilla knockback rate.
 *
 * <h2>Threading</h2>
 * Invoked from PacketEvents' netty event loop. The per-key
 * {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)} call
 * is atomic, so concurrent attacks on the same victim resolve to a single
 * admitted pre-send. A {@code windowMillis <= 0} disables gating entirely
 * (same "0 means off" convention as {@link CpsLimiter}).
 */
final class HitFeedbackGate {

    private final ConcurrentHashMap<UUID, Long> nextEligibleMillis = new ConcurrentHashMap<>();

    /**
     * Attempt to admit a feedback pre-send for {@code victim} at {@code nowMillis}.
     *
     * @return {@code true} if the victim is eligible (and the window is now
     *         advanced past {@code nowMillis + windowMillis}); {@code false} if
     *         a pre-send was already admitted within the current window.
     */
    boolean tryPreSend(UUID victim, long nowMillis, long windowMillis) {
        if (windowMillis <= 0L) {
            return true; // gating disabled
        }
        // Capture the decision inside the atomic compute: relying on the
        // returned value alone is unsafe, because two clicks in the same
        // millisecond would both compare equal to (now + window).
        boolean[] admitted = {false};
        nextEligibleMillis.compute(victim, (id, nextEligible) -> {
            if (nextEligible == null || nowMillis >= nextEligible) {
                admitted[0] = true;
                return nowMillis + windowMillis;
            }
            return nextEligible; // still inside the window — leave it untouched
        });
        return admitted[0];
    }

    /** Forget a victim's window; called on quit to keep the map small. */
    void forget(UUID victim) {
        nextEligibleMillis.remove(victim);
    }

    /** Drop everything (used on disable). */
    void clear() {
        nextEligibleMillis.clear();
    }
}

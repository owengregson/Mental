package me.vexmc.mental.v5.feature.feedback;

/**
 * Per-victim heal aggregation and pacing (F4). A pot burst's whole heal ships on
 * the next poll — it is never held back — while a slow regen drip (~1 HP a tick)
 * aggregates into at most ONE heal indicator per {@value #WINDOW_TICKS}-tick
 * window, carrying the summed amount, so a regenerating player raises one stand
 * every half-second instead of ten a second.
 *
 * <p>Pure primitive state (no Bukkit, no PacketEvents), so it is unit-pinned
 * directly on the tick arithmetic. Synchronized DEFENSIVELY: every caller is the
 * victim's own region thread (the heal sampler runs inside that player's session
 * tick), so contention is nil, but the guard costs nothing under no contention and
 * documents the single-writer contract explicitly.</p>
 */
final class HealFold {

    /** At most one heal indicator per victim per this many ticks (the F4 pacing window). */
    static final int WINDOW_TICKS = 10;

    /**
     * No indicator under one heart (2.0 health points — the owner's directive:
     * sub-heart heals are noise, not information). Sub-threshold sums are NOT
     * consumed — {@link #poll} leaves them accumulating, so a regen trickle that
     * crosses a heart still ships exactly once, carrying the summed amount.
     * Strict {@code <}: a full one-heart heal shows (the display rounds 2.0 pts
     * to "1"). A sum that never crosses before death is dropped by the existing
     * {@link #reset} boundary, exactly as an unshipped heal should be.
     */
    static final double MIN_SHIP_HEALTH = 2.0;

    /**
     * The "never shipped" sentinel: the very first heal ships immediately (a pot
     * burst is not held). A real prior ship stamps an int-range server tick, so the
     * elapsed compare below never overflows for a genuine window; only this sentinel
     * would, which is exactly why it is short-circuited rather than subtracted.
     */
    private double sum;
    private long lastShipTick = Long.MIN_VALUE;

    /** Accumulates one applied heal delta; the caller's contract is {@code delta > 0}. */
    synchronized void add(double delta) {
        sum += delta;
    }

    /**
     * Ships the accumulated heal when one is pending AND the pacing window since the
     * last ship has elapsed — resetting the accumulator and re-stamping the window —
     * else 0 (nothing pending, or still inside the window). The first heal ever
     * (lastShipTick still {@link Long#MIN_VALUE}) ships immediately.
     */
    synchronized double poll(long nowTick) {
        if (sum < MIN_SHIP_HEALTH) {
            return 0.0; // sub-heart: hold and keep accumulating — never consumed here
        }
        boolean windowElapsed = lastShipTick == Long.MIN_VALUE || nowTick - lastShipTick >= WINDOW_TICKS;
        if (!windowElapsed) {
            return 0.0;
        }
        double shipped = sum;
        sum = 0.0;
        lastShipTick = nowTick;
        return shipped;
    }

    /** Clears both the accumulator and the window stamp (a respawn/death boundary). */
    synchronized void reset() {
        sum = 0.0;
        lastShipTick = Long.MIN_VALUE;
    }
}

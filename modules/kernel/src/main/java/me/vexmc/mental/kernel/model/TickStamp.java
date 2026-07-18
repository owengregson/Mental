package me.vexmc.mental.kernel.model;

/**
 * A server tick index that can express "unknown" without a magic int — the
 * only clock currency in the delivery core (spec §2). Recency is a pure
 * tick-delta compare: no wall clock is ever consulted, so the same value
 * behaves identically on Paper's authoritative tick and Folia's global
 * counter.
 */
public record TickStamp(int value) {

    /** The absent stamp: a writer with no era ordering contract, or a clock before it starts. */
    public static final TickStamp NO_TICK = new TickStamp(Integer.MIN_VALUE);

    /** Whether this stamp carries a real tick (i.e. it is not {@link #NO_TICK}). */
    public boolean known() {
        return value != Integer.MIN_VALUE;
    }

    /**
     * True when both stamps are known and {@code this} is within {@code ticks}
     * of {@code now} (inclusive). A future stamp ({@code now} before
     * {@code this}) is never recent — the delta is negative.
     */
    public boolean recentAt(TickStamp now, int ticks) {
        return known() && now.known() && now.value - value >= 0 && now.value - value <= ticks;
    }
}

package me.vexmc.mental.kernel.math;

/**
 * The Combat Test 8c attack-charge math (spec §2.1, code-confirmed against
 * {@code Player.getAttackStrengthScale}). Pure gates over two quantities the
 * core's server-side charge ledger supplies: the ticks elapsed since the
 * attacker last reset the timer, and the full delay ({@code getAttackDelay()·2})
 * for the currently-held weapon.
 *
 * <p>The scale is {@code clamp(2·ticksSinceReset / fullDelay, 0, 2)} — it
 * charges to <b>200%</b> and passes through {@code 1.0} (100%) at the halfway
 * point (spec §2.1). A landed hit requires full recharge ({@code scale ≥ 1});
 * the sole exception is <b>miss recovery</b>, where an air swing lets a
 * re-attack through once more than four ticks have elapsed — a lenient partial
 * gate, not a hard lockout. Two ≥195% gates ride on top: the {@code +1.0}
 * charged reach bonus (unless crouching) and the sweep permission.</p>
 */
public final class Ct8cChargeMath {

    /** The ≥195% threshold both the charged reach bonus and sweep gate on (spec §2.1/§2.3). */
    public static final double CHARGED_THRESHOLD = 1.95;

    /** The lenient miss-recovery partial-recharge gate: {@code startValue − ticker > 4.0} (spec §2.1). */
    public static final double MISS_RECOVERY_TICKS = 4.0;

    private Ct8cChargeMath() {}

    /**
     * The attack-strength scale: {@code clamp(2·ticksSinceReset / fullDelay, 0,
     * 2)}. 100% at half the full delay, 200% at the full delay (spec §2.1).
     */
    public static double scale(double ticksSinceReset, double fullDelayTicks) {
        return Math.max(0.0, Math.min(2.0, 2.0 * ticksSinceReset / fullDelayTicks));
    }

    /**
     * Whether an attack may land: fully charged ({@code scale ≥ 1}), OR the
     * miss-recovery lane is open (an air swing set {@code missRecovery} and more
     * than four ticks have elapsed since the reset) — spec §2.1.
     */
    public static boolean attackAllowed(double scale, boolean missRecovery, double ticksSinceReset) {
        return scale >= 1.0 || (missRecovery && ticksSinceReset > MISS_RECOVERY_TICKS);
    }

    /** The {@code +1.0} charged reach bonus gate: {@code scale > 1.95 && !crouching} (spec §2.1). */
    public static boolean chargedBonus(double scale, boolean crouching) {
        return scale > CHARGED_THRESHOLD && !crouching;
    }

    /** The sweep-charge gate: {@code scale > 1.95} for swords AND axes (spec §2.3, code §1). */
    public static boolean sweepAllowed(double scale) {
        return scale > CHARGED_THRESHOLD;
    }
}

package me.vexmc.mental.v5.feature.sustain;

import me.vexmc.mental.kernel.math.Ct8cPotionMath;

/**
 * Recovers the Instant Health amplifier from a vanilla heal amount and returns
 * the Combat Test 8c value (spec §2.8, decompile-confirmed). Vanilla Instant
 * Health heals {@code 4·2^amp} (I=4, II=8) across Mental's whole Paper range;
 * CT8c raises the base to {@code 6·2^amp} (I=6, II=12) so it mirrors Instant
 * Damage — {@link Ct8cPotionMath#instantHealth(int)}.
 *
 * <p>The Bukkit {@code EntityRegainHealthEvent} exposes only the healed amount,
 * never the source potion's amplifier, so the amplifier is recovered from the
 * amount ({@code amp = round(log2(amount/4))}) and the CT8c value substituted
 * absolutely — the substitution the {@code Ct8cPotionsUnit} writes back. The
 * {@code 4.0} vanilla base is the ONE version-assumption here (stable since
 * 1.9.4); a non-power-of-two amount rounds to the nearest amplifier and a
 * non-positive amount is passed through untouched (never a negative heal).</p>
 */
public final class Ct8cInstantHeal {

    /**
     * The vanilla Instant Health base, {@code 4·2^amp} (MobEffects HEAL, obf
     * confirmed stable across the range). CT8c substitutes {@code 6·2^amp}.
     */
    static final double VANILLA_INSTANT_HEALTH_BASE = 4.0;

    private static final double LN2 = Math.log(2.0);

    private Ct8cInstantHeal() {}

    /**
     * The CT8c Instant Health amount for a given vanilla heal amount — recovers
     * the amplifier and returns {@code 6·2^amp}. A non-positive amount is left
     * unchanged (no phantom heal on a degenerate event).
     */
    public static double substituted(double vanillaHeal) {
        if (vanillaHeal <= 0.0) {
            return vanillaHeal;
        }
        return Ct8cPotionMath.instantHealth(amplifierOf(vanillaHeal));
    }

    /** The 0-based amplifier recovered from a vanilla heal amount, floored at 0. */
    static int amplifierOf(double vanillaHeal) {
        int amp = (int) Math.round(Math.log(vanillaHeal / VANILLA_INSTANT_HEALTH_BASE) / LN2);
        return Math.max(0, amp);
    }
}

package me.vexmc.mental.v5.feature.sustain;

import me.vexmc.mental.kernel.math.Ct8cPotionMath;

/**
 * The Combat Test 8c Strength/Weakness melee factors (spec §2.8): CT8c replaces
 * vanilla's flat {@code +3}/{@code −4} attack-damage modifiers with a
 * {@code ±20%·level} MULTIPLY_TOTAL on ATTACK_DAMAGE — Strength I {@code ×1.2},
 * II {@code ×1.4}; Weakness I {@code ×0.8}, II {@code ×0.6}. The fractions come
 * from the kernel {@link Ct8cPotionMath}; this helper turns them into the
 * multiplicative factor the fast-path damage composition applies.
 *
 * <p>The application seam is {@code DamageShaper} (owned by the CT8c damage
 * cluster, Task D): {@code Ct8cPotionsUnit} is presence-only for this half — its
 * reconciler presence makes {@code featureActive(CT8C_POTIONS)} true, and the
 * shaper, gated on that toggle, multiplies the recovered weapon base by
 * {@link #apply(double, int, int)} in place of the era {@code eraPotionBase}.
 * Only Mental-delivered fast-path player melee is affected; mob/vanilla-path
 * hits keep vanilla values (the documented scope trade-off, mirroring
 * {@code old-potion-values}).</p>
 *
 * <p>Amplifiers follow the {@code DamageShaper} convention: 0-based, or
 * {@code -1} when the effect is absent (no factor applied).</p>
 */
public final class Ct8cPotionValues {

    private Ct8cPotionValues() {}

    /** The Strength MULTIPLY_TOTAL factor: {@code 1 + 0.2·(amp+1)} — I {@code 1.2}, II {@code 1.4}. */
    public static double strengthFactor(int amplifier) {
        return 1.0 + Ct8cPotionMath.strengthMultiplier(amplifier);
    }

    /** The Weakness MULTIPLY_TOTAL factor: {@code 1 − 0.2·(amp+1)} — I {@code 0.8}, II {@code 0.6}. */
    public static double weaknessFactor(int amplifier) {
        return 1.0 + Ct8cPotionMath.weaknessMultiplier(amplifier);
    }

    /**
     * Composes the CT8c Strength/Weakness factors onto a pure weapon base.
     * Each effect is applied only when present ({@code amp >= 0}); both apply
     * multiplicatively (MULTIPLY_TOTAL) when a player holds both. The result is
     * clamped at 0 — a very high Weakness can never make a hit heal.
     */
    public static double apply(double weaponBase, int strengthAmp, int weaknessAmp) {
        double damage = weaponBase;
        if (strengthAmp >= 0) {
            damage *= strengthFactor(strengthAmp);
        }
        if (weaknessAmp >= 0) {
            damage *= weaknessFactor(weaknessAmp);
        }
        return Math.max(0.0, damage);
    }
}

package me.vexmc.mental.kernel.math;

/**
 * The Combat Test 8c potion values (spec §2.8, code-confirmed against {@code
 * MobEffects}/{@code AttackDamageMobEffect}/{@code Items}). Pure arithmetic the
 * core shell substitutes into the vanilla effect application:
 *
 * <ul>
 *   <li><b>Instant Health / Damage.</b> {@code 6·2^amp} — I heals/hits 6, II
 *       heals/hits 12 (mirrored).</li>
 *   <li><b>Strength / Weakness.</b> ±20% per level as a MULTIPLY_TOTAL
 *       ATTACK_DAMAGE modifier ({@code ±0.2·(amp+1)}), replacing vanilla's flat
 *       {@code +3}/{@code −4}.</li>
 *   <li><b>Tipped arrows.</b> Instantaneous effects scaled ×1/8.</li>
 * </ul>
 */
public final class Ct8cPotionMath {

    private Ct8cPotionMath() {}

    /** Instant Health healing: {@code 6·2^amp} = {@code 6 << amp} (spec §2.8; I=6, II=12). */
    public static int instantHealth(int amplifier) {
        return 6 << amplifier;
    }

    /** Instant Damage: the Instant Health mirror, {@code 6·2^amp} (spec §2.8). */
    public static int instantDamage(int amplifier) {
        return 6 << amplifier;
    }

    /**
     * The Strength ATTACK_DAMAGE MULTIPLY_TOTAL fraction: {@code +0.2·(amp+1)}
     * (+20%/level, spec §2.8). Strength I → {@code +0.2}, Strength II → {@code
     * +0.4}.
     */
    public static double strengthMultiplier(int amplifier) {
        return 0.2 * (amplifier + 1);
    }

    /** The Weakness ATTACK_DAMAGE MULTIPLY_TOTAL fraction: {@code −0.2·(amp+1)} (−20%/level, spec §2.8). */
    public static double weaknessMultiplier(int amplifier) {
        return -0.2 * (amplifier + 1);
    }

    /** The tipped-arrow instantaneous-effect scale: ×1/8 (spec §2.8/§2.10). */
    public static double tippedArrowScale() {
        return 0.125;
    }
}

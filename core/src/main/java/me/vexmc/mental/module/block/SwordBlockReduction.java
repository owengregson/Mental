package me.vexmc.mental.module.block;

/**
 * The 1.8 sword-blocking damage reduction, as a pure function.
 *
 * <p>Era truth: a blocked melee hit deals {@code (1 + damage) * 0.5} taken,
 * i.e. it is REDUCED by {@code (damage - 1) * 0.5}
 * ({@code ModuleShieldDamageReduction f = (1.0F + f) * 0.5F}; the combat
 * compendium wire-measured {@code 4.5 = (1 + 8) * 0.5} on real 1.8). The
 * reduction is clamped at 0 so a sub-1-damage hit is never amplified.</p>
 *
 * <p>This is the software reduction used on the Tier-B (consumable-only)
 * versions where the {@code CONSUMABLE} component grants the block <em>pose</em>
 * but performs no damage reduction. On Tier A (1.21.5+) the native
 * {@code BlocksAttacks} component computes the byte-identical value itself
 * ({@code clamp(base + factor*damage)} with {@code base=-0.5, factor=0.5}), so
 * this software path is NOT applied there — the two must never compound.</p>
 *
 * <p>Era note: a blocked hit still knocks the victim FULL — only the DAMAGE is
 * reduced. Mental owns knockback; nothing here touches velocity.</p>
 */
public final class SwordBlockReduction {

    private SwordBlockReduction() {}

    /**
     * The amount of damage to subtract from an incoming melee hit that the
     * victim is blocking with a sword: {@code max(0, (damage - 1) * 0.5)}.
     *
     * @param damage the incoming damage before blocking
     * @return the non-negative reduction to subtract
     */
    public static double blockedDamage(double damage) {
        return Math.max(0.0, (damage - 1.0) * 0.5);
    }
}

package me.vexmc.mental.module.damage;

/**
 * The era weapon/tool Unbreaking skip probability — pure integer math.
 *
 * <h2>Ground truth</h2>
 * <p>A melee weapon loses <em>1</em> durability per entity hit, unchanged
 * 1.7→modern for the attack case (block-break costs 2; the ATTACK case is 1).
 * Unbreaking on a weapon uses the generic tool skip — NOT the armour-specific
 * {@code 0.6} branch ({@code ArmourDurabilityMath}). 1.8.9
 * {@code EnchantmentDurability.a(stack, level, random)} ({@code acg.java:36-41})
 * returns the armour short-circuit only when the item is armour; a weapon falls
 * through to {@code return random.nextInt(level + 1) > 0;}.</p>
 *
 * <h2>Semantics (note the direction)</h2>
 * <p>That NMS expression is the <em>negation</em> verdict: it returns
 * {@code true} (SKIP the durability damage) when {@code nextInt(level+1) > 0},
 * so the item DAMAGES only on the single outcome {@code nextInt(level+1) == 0}
 * — probability {@code 1/(level+1)}. {@link #damagesThisHit} encodes the
 * positive (damage) side directly: damage iff the injected roll is the zero
 * outcome.</p>
 *
 * <p><strong>Level-0 behaviour:</strong> {@code nextInt(1)} is always 0, so an
 * unenchanted weapon ALWAYS damages — the era baseline (1 per hit). Each level
 * of Unbreaking widens the skip window: damage prob 1 (L0) → 1/2 (L1) → 1/4
 * (L3), exactly the standard tool/weapon Unbreaking.</p>
 *
 * <p>The roll is injected so this stays pure and deterministic; the caller
 * supplies {@code random.nextInt(level + 1)} (a value in {@code [0, level]}).</p>
 */
public final class ToolDurabilityMath {

    private ToolDurabilityMath() {}

    /**
     * Whether this hit damages the weapon's durability, given a roll in
     * {@code [0, unbreakingLevel]} (the caller's {@code random.nextInt(level+1)}).
     * The weapon damages iff the roll is the single zero outcome — probability
     * {@code 1/(level+1)}. Negative levels are treated as 0 (always damages).
     */
    @SuppressWarnings("unused") // level is part of the documented signature; the roll already encodes it
    public static boolean damagesThisHit(int unbreakingLevel, int roll0toLevelInclusive) {
        // Mirrors NMS acg.java:40 — the negation skips when nextInt(level+1) > 0,
        // so the damaging side is exactly the zero roll. The level chooses the
        // roll range (the caller's nextInt(level+1)); the verdict is the roll.
        return roll0toLevelInclusive == 0;
    }
}

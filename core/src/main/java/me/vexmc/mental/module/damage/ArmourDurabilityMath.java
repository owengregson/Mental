package me.vexmc.mental.module.damage;

/**
 * The 1.8 armour-durability Unbreaking skip probability — pure integer math.
 *
 * <h2>Ground truth</h2>
 * <p>Per-hit durability <em>magnitude</em> is byte-identical 1.8↔modern
 * ({@code max(1, floor(eventDamage/4))} per worn piece —
 * {@code wm.java:370-380}, ground-truth doc §4), so the module never touches it.
 * The only era difference is the Unbreaking <em>skip</em> probability: armour in
 * 1.8 uses {@code 0.6 + 0.4/(level+1)} ({@code acg.java:37+40}) where modern
 * armour uses the generic {@code level/(level+1)}. This class encodes the 1.8
 * form exactly as OCM's {@code ModuleOldArmourDurability} does.</p>
 *
 * <h2>Semantics (mirrors OCM precisely — note the direction)</h2>
 * <p>OCM computes {@code damageChance = 60 + 40/(level+1)} (integer division)
 * and then, given a roll {@code 0..99}, KEEPS the vanilla durability damage when
 * {@code roll < damageChance} and SKIPS it ({@code setDamage(0)}) when
 * {@code roll >= damageChance}. So {@link #damageChancePercent(int)} is the
 * percent chance the piece DOES take wear; the skip chance is its complement
 * ({@code 100 - damageChance}).</p>
 *
 * <p><strong>Level-0 behaviour:</strong> at unbreaking 0, {@code damageChance =
 * 60 + 40/1 = 100}, and no {@code 0..99} roll satisfies {@code roll >= 100} — so
 * unenchanted armour <em>never</em> skips and always takes the (small) vanilla
 * wear. This matches both OCM and the raw 1.8 NMS, where the Unbreaking
 * negation loop only runs when {@code unbreakingLevel > 0}
 * ({@code EnchantmentDurability.negateDamage}, server-1.8.9). The "armour lasts
 * longer" feel therefore comes from the kept-small vanilla magnitude plus the
 * rising skip window as Unbreaking increases (0% at L0, 20% at L1, 30% at L3),
 * not from a flat level-0 base skip.</p>
 *
 * <p>The roll is injected so this stays pure and deterministic; the module
 * supplies {@code ThreadLocalRandom.current().nextInt(100)}.</p>
 */
public final class ArmourDurabilityMath {

    private ArmourDurabilityMath() {}

    /**
     * The percent chance ({@code 0..100}) that the armour piece DOES take its
     * vanilla durability wear this hit: {@code 60 + 40/(unbreakingLevel+1)},
     * integer division — OCM's exact expression. Negative levels are treated as 0.
     */
    public static int damageChancePercent(int unbreakingLevel) {
        int level = Math.max(0, unbreakingLevel);
        return 60 + (40 / (level + 1));
    }

    /**
     * Whether this hit's durability wear should be SKIPPED, given a roll in
     * {@code [0, 100)}. OCM's direction: skip iff {@code roll >= damageChance}.
     */
    public static boolean skipsDamage(int unbreakingLevel, int roll0to99) {
        return roll0to99 >= damageChancePercent(unbreakingLevel);
    }
}

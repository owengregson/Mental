package me.vexmc.mental.v5.config.settings;

/**
 * The {@code charged-attacks} module's tunables — Combat Test 8c's server-side
 * charge gate (design spec §2.1, code-confirmed from CT8c {@code Player}'s
 * {@code getAttackStrengthScale}). The attack scale ramps 0 → 2.0 across the
 * full attack delay (100% at the halfway point); a landed hit below full charge
 * is rejected ({@code requireFullCharge}) EXCEPT via the lenient miss-recovery
 * lane — an air swing allows a re-attack once more than {@code missRecoveryTicks}
 * ticks have elapsed. A charge at or above {@code chargedThreshold} (1.95 = the
 * ≥195% gate) grants {@code chargedReachBonus} extra reach unless the attacker
 * is crouching ({@code denyBonusWhileCrouching}).
 *
 * <p>Every knob is an era-exact no-op only because the MODULE defaults OFF; the
 * {@code DEFAULTS} are the code-confirmed §2.1 values.</p>
 */
public record ChargedAttackSettings(
        boolean requireFullCharge,
        int missRecoveryTicks,
        double chargedThreshold,
        double chargedReachBonus,
        boolean denyBonusWhileCrouching) {

    /**
     * The code-confirmed CT8c charge gate (spec §2.1): a landed hit needs a full
     * recharge, the miss-recovery lane opens after 4 ticks, and a ≥195% charge
     * grants +1.0 reach unless the attacker is crouching.
     */
    public static final ChargedAttackSettings DEFAULTS =
            new ChargedAttackSettings(true, 4, 1.95, 1.0, true);
}

package me.vexmc.mental.module.rules.cooldown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins vanilla's 1.9+ attack-cooldown DAMAGE scaling and proves why the
 * attack-cooldown module must raise the player's SERVER {@code attack_speed}
 * base — not just the client one — to deal full damage on every swing.
 *
 * <h2>Why this test exists (the bug it guards)</h2>
 * <p>The attack-cooldown module originally rewrote only the CLIENT's
 * {@code attack_speed} attribute (the charge-bar overlay), leaving the SERVER
 * attribute at vanilla {@code 4.0}. Mental's fast path bypasses
 * {@code Player#attack} (it composes damage in {@code DamageCalculator}, which
 * reads no cooldown term), so the gap was invisible for fast-path hits. But a
 * non-player target on Folia — and any fast-path-OFF hit — falls through to
 * vanilla {@code Player#attack}, which scales melee damage by
 * {@code baseDamageScaleFactor()}. Spam-clicking holds the attack-strength
 * ticker near zero, so those hits landed at ~20% damage (an iron golem took
 * 11-12 hits instead of 5). Raising the SERVER base to the full-charge value
 * makes {@code getAttackStrengthScale()} return {@code 1.0} on every swing,
 * regardless of inter-click timing or held weapon.</p>
 *
 * <h2>The formula (byte-verified against the Folia 1.21.11 server jar)</h2>
 * <ul>
 *   <li>{@code getCurrentItemAttackStrengthDelay() = 20.0 / attackSpeed} (ticks)</li>
 *   <li>{@code getAttackStrengthScale(adj) = clamp((ticker + adj) / delay, 0, 1)}
 *       — {@code Player#attack} passes {@code adj = 0.5f}</li>
 *   <li>{@code baseDamageScaleFactor() = 0.2 + scale*scale*0.8}</li>
 * </ul>
 * These are vanilla's, unchanged since the 1.9 combat update; the constants
 * are reproduced here only to DOCUMENT the mechanism and to pin that the
 * module's chosen base value defeats it for any weapon.
 */
class CooldownDamageScalingTest {

    /** Vanilla {@code Player#attack} reads the scale with a {@code 0.5f} look-ahead. */
    private static final double ATTACK_ADJUST = 0.5;

    /** Vanilla player base {@code attack_speed} (the value the spoof used to leave untouched). */
    private static final double VANILLA_ATTACK_SPEED = 4.0;

    /**
     * A diamond/iron sword's {@code attack_speed} modifier is {@code -2.4}
     * (effective {@code 1.6} on a vanilla player); -3.0/-3.2 cover axes — the
     * most negative real melee modifiers. Used to prove the chosen base keeps
     * the EFFECTIVE speed in the full-charge band for every weapon.
     */
    private static final double SWORD_SPEED_MODIFIER = -2.4;

    private static double delayTicks(double attackSpeed) {
        return 20.0 / attackSpeed;
    }

    private static double attackStrengthScale(double tickerTicks, double attackSpeed) {
        double raw = (tickerTicks + ATTACK_ADJUST) / delayTicks(attackSpeed);
        return Math.max(0.0, Math.min(1.0, raw));
    }

    /** Vanilla's multiplicative damage factor for a swing at the given charge. */
    private static double baseDamageScaleFactor(double tickerTicks, double attackSpeed) {
        double scale = attackStrengthScale(tickerTicks, attackSpeed);
        return 0.2 + scale * scale * 0.8;
    }

    /* ------------------------------------------------------------------ */
    /*  The bug: vanilla attack_speed scales spam-clicks down to ~20%      */
    /* ------------------------------------------------------------------ */

    @Test
    void vanillaAttackSpeedScalesSpamClicksDownToAboutAFifth() {
        double effective = VANILLA_ATTACK_SPEED + SWORD_SPEED_MODIFIER; // 1.6 -> delay 12.5 ticks
        // Spam: the ticker is reset to 0 by the previous swing and re-clicked
        // before it recovers. factor = 0.2 + (0.5/12.5)^2 * 0.8 = 0.20128.
        double spamFactor = baseDamageScaleFactor(0.0, effective);
        assertTrue(spamFactor < 0.25,
                "vanilla spam-click factor must be ~0.2 (the 11/12-hits regime); was " + spamFactor);
        assertEquals(0.20128, spamFactor, 1e-5, "exact vanilla spam factor for a sword");

        // Charged: ticker fully recovered (>= 12.5 ticks) -> scale clamps to 1.0.
        double chargedFactor = baseDamageScaleFactor(13.0, effective);
        assertEquals(1.0, chargedFactor, 1e-9, "a fully charged swing deals full damage");

        // The whole symptom in one line: charged deals ~5x a spam hit.
        assertTrue(chargedFactor / spamFactor > 4.0,
                "charged-vs-spam ratio must explain ~5 vs ~11/12 hits; was " + (chargedFactor / spamFactor));
    }

    /* ------------------------------------------------------------------ */
    /*  The fix: the full-charge base deals 100% on EVERY swing            */
    /* ------------------------------------------------------------------ */

    @Test
    void fullChargeBaseDealsFullDamageRegardlessOfTimingOrWeapon() {
        double base = CooldownSpoof.FULL_CHARGE_ATTACK_SPEED; // the value the module writes server-side
        // Every realistic melee weapon modifier (hand 0, sword -2.4, axe -3.0,
        // a generous -4.0), at the WORST timing (ticker just reset to 0).
        for (double modifier : new double[] {0.0, SWORD_SPEED_MODIFIER, -3.0, -4.0}) {
            double effective = base + modifier;
            double factor = baseDamageScaleFactor(0.0, effective);
            assertEquals(1.0, factor, 1e-9,
                    "full-charge base must yield factor 1.0 at ticker 0 for modifier " + modifier
                            + " (effective " + effective + ")");
        }
    }

    /**
     * The guarantee behind the constant choice: a swing is full-charge at
     * {@code ticker == 0} iff {@code (0 + 0.5) / (20 / eff) >= 1}, i.e. the
     * EFFECTIVE attack speed is {@code >= 40}. The chosen base must keep the
     * effective speed in that band for any weapon — guarding against a future
     * lowering of {@link CooldownSpoof#FULL_CHARGE_ATTACK_SPEED} below the safe
     * floor (OCM's default 40.0 base, by contrast, dips to ~0.94 with a sword's
     * -2.4 modifier, which is why Mental does not copy that value).
     */
    @Test
    void chosenBaseStaysInTheGuaranteedFullChargeBandForAnyWeapon() {
        double worstModifier = -4.0; // more negative than any vanilla melee weapon
        double effectiveFloor = CooldownSpoof.FULL_CHARGE_ATTACK_SPEED + worstModifier;
        assertTrue(effectiveFloor >= 40.0,
                "the module's base must keep effective attack_speed >= 40 (the full-charge floor) "
                        + "even for the heaviest weapon; effective floor was " + effectiveFloor);

        // And it must be strictly necessary: vanilla 4.0 does NOT clear the floor,
        // so a client-only spoof (server left at 4.0) cannot fix the damage.
        assertTrue(VANILLA_ATTACK_SPEED + SWORD_SPEED_MODIFIER < 40.0,
                "vanilla effective speed must be below the full-charge floor (proving the fix is needed)");
    }
}

package me.vexmc.mental.module.potion;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.jetbrains.annotations.NotNull;

/**
 * Restores the pre-1.9 (1.8) Strength/Weakness damage VALUES on fast-path melee.
 *
 * <p>1.9 weakened Strength and strengthened Weakness. Era Strength was a
 * <em>multiplicative</em> bonus on the weapon base — {@code factor = 1 +
 * 2.5×(amp+1)} (Strength I ×3.5, Strength II ×6.0, MULTIPLY_TOTAL
 * [pe.java:17]) — versus 1.9's flat {@code +3×(amp+1)} ADD; era Weakness
 * subtracted {@code 2.0×(amp+1)} [pe.java:30 / rv.java:28] versus 1.9's
 * {@code 4.0×(amp+1)}. These era values are applied to the weapon base before
 * the crit ×1.5 and the Sharpness additive [wn.java:761-764].</p>
 *
 * <h2>Where the behaviour lives</h2>
 * <p>This module owns no listener of its own — the era values are computed
 * inside the fast-path {@code DamageCalculator}, which {@code HitApplier} gates
 * on this module's {@code old-potion-values} flag. The CombatModule wiring
 * exists so the feature appears in {@code /mental module list} and can be
 * toggled live (a reload republishes the atomic config snapshot the
 * DamageCalculator reads), mirroring {@code AttackCooldownModule}: a no-op
 * enable/disable backing a fast-path-resident feature.</p>
 *
 * <h2>Known limitation (accepted scope)</h2>
 * <p>Era Strength/Weakness VALUES apply <b>only to the fast-path melee hits
 * Mental registers</b> — the dominant PvP case. Mob attacks and any hit that
 * does not flow through the fast path keep vanilla Strength/Weakness, and when
 * OldCombatMechanics shapes the damage (the {@code vanillaShape} handoff) the
 * era values are deliberately skipped so OCM stays the single source of truth.
 * This is a deliberate trade-off — we do NOT override the attribute-modifier
 * lifecycle to make the values global.</p>
 *
 * <p>This is the VALUES half only; potion <em>durations</em> are the separate
 * {@code old-potion-durations} module.</p>
 *
 * <p>Zero-touch: when disabled (the default), the DamageCalculator never applies
 * era potion values and the game is untouched.</p>
 */
public final class PotionValueModule extends CombatModule {

    public PotionValueModule(@NotNull MentalServices services) {
        super(services,
                "old-potion-values",
                "Old Potion Values",
                "Restores the 1.8 Strength (×3.5 / ×6.0) and Weakness (−2 per level) damage "
                        + "values on fast-path melee — applied to the weapon base before crit "
                        + "and Sharpness (era values, far stronger Strength than 1.9).",
                DebugCategory.HITREG);
    }

    @Override
    public boolean configEnabled() {
        return services.config().potionValues().enabled();
    }

    @Override
    protected void onEnable() {
        // Behaviour lives in the fast-path DamageCalculator, gated on the
        // old-potion-values flag read from the atomic config snapshot. Nothing to
        // register here (mirrors AttackCooldownModule).
    }

    @Override
    protected void onDisable() {
        // DamageCalculator gates on the config flag; no teardown required.
    }
}

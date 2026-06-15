package me.vexmc.mental.module.damage;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.jetbrains.annotations.NotNull;

/**
 * Restores weapon durability loss on Mental's fast-path melee hits.
 *
 * <p>The fast path applies damage with a direct {@code damage(amount)} call,
 * which — unlike vanilla's {@code Player#attack} — never runs the attack-time
 * durability step ({@code HitApplier}'s javadoc lists durability among the
 * deliberate omissions). So a weapon never wears on a Mental-registered hit. When
 * this module is on, the fast path damages the attacker's main-hand weapon by
 * <em>1</em> per accepted hit (the era 1.7→modern attack case), modified by the
 * standard tool/weapon Unbreaking skip ({@code 1/(level+1)} damage chance — see
 * {@link ToolDurabilityMath}), breaking the item like vanilla when its durability
 * is exhausted.</p>
 *
 * <h2>Where the behaviour lives</h2>
 * <p>This module owns no listener of its own — the durability write lives inside
 * the fast-path {@code HitApplier}, gated on this module's
 * {@code old-tool-durability} flag (read from the atomic config snapshot). The
 * CombatModule wiring exists so the feature appears in {@code /mental module list}
 * and can be toggled live (a reload republishes the snapshot the HitApplier
 * reads), mirroring {@code PotionValueModule}/{@code AttackCooldownModule}: a
 * no-op enable/disable backing a fast-path-resident feature.</p>
 *
 * <h2>Scope (accepted)</h2>
 * <p>Durability only — the damage VALUES are already era-correct in
 * {@code DamageCalculator} and are not touched here. This restores durability for
 * the fast-path melee hits Mental registers (the dominant PvP case); it does not
 * add a fast-path-off event-path tool-damage handler.</p>
 *
 * <p>Zero-touch: when disabled (the default), the HitApplier never touches the
 * weapon and its behaviour is byte-identical to the durability-omitting fast path
 * it has always shipped.</p>
 */
public final class ToolDurabilityModule extends CombatModule {

    public ToolDurabilityModule(@NotNull MentalServices services) {
        super(services,
                "old-tool-durability",
                "Old Tool Durability",
                "Restores weapon durability loss on fast-path melee hits (1 per hit, "
                        + "standard Unbreaking skip) — the fast path otherwise omits it.",
                DebugCategory.HITREG);
    }

    @Override
    public boolean configEnabled() {
        return services.config().toolDurability().enabled();
    }

    @Override
    protected void onEnable() {
        // Behaviour lives in the fast-path HitApplier, gated on the
        // old-tool-durability flag read from the atomic config snapshot. Nothing
        // to register here (mirrors PotionValueModule/AttackCooldownModule).
    }

    @Override
    protected void onDisable() {
        // HitApplier gates on the config flag; no teardown required.
    }
}

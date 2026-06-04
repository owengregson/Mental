package me.vexmc.mental.module.ocm;

import org.jetbrains.annotations.NotNull;

/**
 * The OldCombatMechanics modules whose territory overlaps Mental's, keyed by
 * OCM's configurable module names. For each, the <em>decider</em> — whose
 * modeset settles ownership of a given interaction — mirrors OCM's own rule:
 * offensive mechanics follow the attacker, projectile knockback follows the
 * defender (OCM checks {@code isEnabled(damager, damagee)} with a non-human
 * damager).
 */
public enum OcmMechanic {

    /** {@code ModulePlayerKnockback} — melee knockback; attacker decides. */
    MELEE_KNOCKBACK("old-player-knockback"),

    /** {@code ModuleFishingKnockback} — hook hits and reel-in; the rodder decides. */
    FISHING_KNOCKBACK("old-fishing-knockback"),

    /** {@code ModuleFishingRodVelocity} — cast speed and hook gravity; the caster decides. */
    FISHING_ROD_VELOCITY("fishing-rod-velocity"),

    /** {@code ModuleProjectileKnockback} — thrown-projectile damage substitution; the victim decides. */
    PROJECTILE_KNOCKBACK("projectile-knockback"),

    /** {@code ModuleOldToolDamage} — weapon damage and sharpness; attacker decides. */
    TOOL_DAMAGE("old-tool-damage"),

    /** {@code ModuleOldCriticalHits} — the crit multiplier; attacker decides. */
    CRITICAL_HITS("old-critical-hits");

    private final String ocmName;

    OcmMechanic(@NotNull String ocmName) {
        this.ocmName = ocmName;
    }

    /** OCM's configurable module name, as accepted by its service API. */
    public @NotNull String ocmName() {
        return ocmName;
    }
}

package me.vexmc.mental.kernel.coexist;

/**
 * Which party's modeset settles ownership of an interaction (mandate §4.11 —
 * a fixed table, not a per-hit heuristic). Offensive mechanics follow the
 * attacker, fishing follows the rodder, thrown-projectile knockback follows
 * the victim, and arrow knockback is Mental's on every version.
 */
public enum Decider {
    ATTACKER,
    RODDER,
    VICTIM,
    ALWAYS_MENTAL
}

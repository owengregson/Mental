package me.vexmc.mental.kernel.profile;

/**
 * How knockback resistance (netherite armor) is honored.
 *
 * <p>1.7.10 treated resistance as a <em>probability</em> of ignoring the hit
 * entirely — partial resistance sources did not exist yet, so all-or-nothing
 * was the era's semantics. {@code SCALING} is the deterministic alternative
 * for servers where netherite is obtainable; {@code NONE} reproduces the
 * 1.7.10 item pool, where nothing a player wore resisted knockback.</p>
 */
public enum ResistancePolicy {
    /** Ignore resistance entirely — the authentic 1.7.10 PvP experience. */
    NONE,
    /** Probabilistic all-or-nothing roll, the 1.7.10 {@code knockBack} rule. */
    LEGACY,
    /** Deterministically scale horizontal knockback by {@code 1 − resistance}. */
    SCALING
}

package me.vexmc.mental.kernel.coexist;

/**
 * The TOTAL set of era mechanics Mental can restore — one token per mechanic,
 * whether it is one of the historically arbitrated six or a rule ported from
 * OldCombatMechanics in 2026-06.
 *
 * <p>{@code ocmKey} is OCM's own configuration module name for the same
 * mechanic, or {@code null} when OCM has no equivalent (arrow knockback is
 * always Mental's). The historical six — the {@code arbitrated} tokens — are
 * the only ones whose ownership is settled BOUND/CONFIG against OCM: for them,
 * Mental yields when OCM owns the interaction. Every other token is
 * Mental-owned and merely peer-detected: enabling the same rule in both
 * plugins double-applies it, so the arbiter warns loudly at startup but never
 * yields.</p>
 *
 * <p>The {@code ocmKey} strings are the OCM contract. The six arbitrated names
 * are verified byte-identical against the retired {@code module.ocm.OcmMechanic}
 * enum; the rest are OCM's real module keys as recorded in the OCM port
 * ground-truth and standalone-suite roadmap (docs/superpowers/plans/
 * 2026-06-14-ocm-*). Two Mental tokens may share one OCM key (potion
 * durations/values are both OCM's {@code old-potion-effects}; tool durability
 * folds into OCM's {@code old-tool-damage}).</p>
 */
public enum MechanicToken {

    /* -- The historically arbitrated six (OCM owns per the decider table). -- */

    MELEE_KNOCKBACK("old-player-knockback", Decider.ATTACKER, true),
    FISHING_KNOCKBACK("old-fishing-knockback", Decider.RODDER, true),
    FISHING_ROD_VELOCITY("fishing-rod-velocity", Decider.RODDER, true),
    PROJECTILE_KNOCKBACK("projectile-knockback", Decider.VICTIM, true),
    TOOL_DAMAGE("old-tool-damage", Decider.ATTACKER, true),
    CRITICAL_HITS("old-critical-hits", Decider.ATTACKER, true),

    /* -- Mental-owned tokens (peer-detected; double-enable warns, never yields). -- */

    ARROW_KNOCKBACK(null, Decider.ALWAYS_MENTAL, false),
    ATTACK_COOLDOWN("disable-attack-cooldown", Decider.ATTACKER, false),
    ATTACK_SOUNDS("disable-attack-sounds", Decider.ATTACKER, false),
    SWEEP("disable-sword-sweep", Decider.ATTACKER, false),
    CRAFTING("disable-crafting", Decider.ATTACKER, false),
    OFFHAND("disable-offhand", Decider.ATTACKER, false),
    GOLDEN_APPLES("old-golden-apples", Decider.VICTIM, false),
    ENDER_PEARL_COOLDOWN("disable-enderpearl-cooldown", Decider.ATTACKER, false),
    REGEN("old-player-regen", Decider.VICTIM, false),
    ARMOUR_STRENGTH("old-armour-strength", Decider.VICTIM, false),
    ARMOUR_DURABILITY("old-armour-durability", Decider.VICTIM, false),
    POTION_DURATIONS("old-potion-effects", Decider.VICTIM, false),
    POTION_VALUES("old-potion-effects", Decider.VICTIM, false),
    TOOL_DURABILITY("old-tool-damage", Decider.ATTACKER, false),
    SWORD_BLOCKING("sword-blocking", Decider.VICTIM, false),
    HITBOX("attack-range", Decider.ATTACKER, false);

    private final String ocmKey;
    private final Decider decider;
    private final boolean arbitrated;

    MechanicToken(String ocmKey, Decider decider, boolean arbitrated) {
        this.ocmKey = ocmKey;
        this.decider = decider;
        this.arbitrated = arbitrated;
    }

    /** OCM's config module name for this mechanic, or {@code null} when OCM has none. */
    public String ocmKey() {
        return ocmKey;
    }

    /** Whose modeset settles ownership when arbitration applies. */
    public Decider decider() {
        return decider;
    }

    /** Whether ownership is arbitrated against OCM (the historical six) rather than Mental-owned. */
    public boolean arbitrated() {
        return arbitrated;
    }
}

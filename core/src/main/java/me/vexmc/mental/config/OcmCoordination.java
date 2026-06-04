package me.vexmc.mental.config;

/** How Mental coordinates with an installed OldCombatMechanics. */
public enum OcmCoordination {
    /** Yield every mechanic OCM is configured to handle (per modeset when its API allows). */
    AUTO,
    /** Pretend OCM is absent. Both plugins will fight over knockback — for unusual setups only. */
    IGNORE
}

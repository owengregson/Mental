package me.vexmc.mental.v5.config;

/**
 * How Mental coordinates with an installed OldCombatMechanics (frozen from the
 * retired {@code config.OcmCoordination}). {@link #AUTO} yields every mechanic
 * OCM is configured to handle (per modeset when its API allows); {@link #IGNORE}
 * pretends OCM is absent.
 */
public enum OcmCoordination {
    AUTO,
    IGNORE
}

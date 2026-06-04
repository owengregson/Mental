package me.vexmc.mental.config;

/**
 * What reeling in a hooked entity does.
 *
 * <p>1.7.10 pulled the catch toward the angler — {@code motion += Δ × 0.1}
 * per axis plus {@code √distance × 0.08} of lift — and rod players used it
 * deliberately. {@code CANCEL} suppresses the pull entirely (the common
 * competitive-server preference this plugin shipped before); {@code VANILLA}
 * leaves the server's own reel behavior untouched.</p>
 */
public enum ReelInPolicy {
    /** The 1.7.10 pull formula, applied by Mental on every version. */
    LEGACY,
    /** No pull at all: the catch event is cancelled and the hook removed. */
    CANCEL,
    /** Whatever the running server does natively. */
    VANILLA
}

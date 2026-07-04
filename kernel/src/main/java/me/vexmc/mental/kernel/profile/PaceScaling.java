package me.vexmc.mental.kernel.profile;

/**
 * Speed-conformal knockback ("pace scaling") — the per-profile knob family that
 * multiplies the HORIZONTAL knock Mental delivers by a factor derived from the
 * attacker's movement-speed attribute, so a Speed/Slowness fight keeps the
 * base-speed combo rhythm (design 2026-07-04, spec
 * {@code docs/superpowers/specs/2026-07-04-speed-conformal-knockback-design.md}).
 *
 * <p>A combo is a spacing equilibrium between three speeds: the victim's
 * knock-flight (absolute, does NOT scale with the Speed attribute — verified
 * assumption A1), the attacker's ground chase, and the victim's ground flee
 * (both ×1.6 at Speed III). Scaling the horizontal knock by the same factor
 * makes every LENGTH in the system scale together while every TIME (flight
 * duration, click cadence, the immunity window) stays fixed — a spatially-zoomed
 * replica with identical rhythm.</p>
 *
 * <p><b>Era-exact no-op default.</b> {@link #OFF} is the parse-empty default and
 * the value every archived-server preset carries; with it the engine multiplies
 * by nothing (byte-identical to the era stamp). Only Mental's own
 * {@code signature} preset opts in ({@link Mode#ATTACKER}).</p>
 */
public record PaceScaling(Mode mode, double exponent, double min, double max) {

    /** Which quantity drives the pace factor. */
    public enum Mode {
        /** No scaling — the era stamp ships byte-identically (the default). */
        OFF,
        /** Scale by the attacker's movement-speed attribute over its stance baseline. */
        ATTACKER
    }

    /**
     * The era-exact no-op default: mode off, fully-conformal exponent, and the
     * default clamp window. {@code parse(empty)} yields this, so the invariant
     * {@code parse(empty) == LEGACY_17} holds.
     */
    public static final PaceScaling OFF = new PaceScaling(Mode.OFF, 1.0, 0.5, 2.0);

    /** Whether the factor is ever applied (false ⇒ the engine skips the multiply entirely). */
    public boolean active() {
        return mode != Mode.OFF;
    }
}

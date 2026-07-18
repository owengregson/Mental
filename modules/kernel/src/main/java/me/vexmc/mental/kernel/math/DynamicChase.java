package me.vexmc.mental.kernel.math;

/**
 * The input-driven dynamic chase (servo dynamic-chase spec, 2026-07-07): the axis
 * distance a sprint-resetting attacker closes over the victim's flight window,
 * modelling the re-acceleration RAMP after a reset instead of a flat rate. A fresh
 * knock only exists because the attacker just w-tapped / blockhit, so at the
 * shaping instant they sit at the BOTTOM of their speed cycle and are
 * re-accelerating; pricing their chase at a flat rate under-reads the close and
 * mis-places the victim.
 *
 * <p>The attacker's per-tick closing speed re-approaches a steady value:
 * {@code speed(t) = steadySpeed · (1 − r^t)}, with {@code r ∈ (0,1)} the per-tick
 * approach factor and {@code t} ticks since the reset. The chase over a window of
 * {@code w} ticks, entering {@code phaseTicks} into the ramp, is
 * {@code Σ_{k=1}^{w} speed(phaseTicks + k)} — the closed form below.
 * {@code steadySpeed} is already alignment- and technique-resolved by the caller
 * (sprint, or the block-slowed sprint the owner flagged), so the kernel stays
 * vocabulary-blind: it sees only a target speed, a phase, a ramp, and a window.</p>
 *
 * <p>Pure JDK, no Bukkit. A zero or invalid ramp collapses to the flat
 * {@code steadySpeed · w} — the exact linear chase the servo priced before this
 * model — so the dynamic chase degrades continuously to its predecessor (the
 * bottom rung of the spec's fallback ladder).</p>
 */
public final class DynamicChase {

    private DynamicChase() {}

    /**
     * The projected axis chase travel over {@code window} ticks. {@code steadySpeed}
     * (b/t, positive = closing, already {@code alignment × technique speed}) is the
     * ramp asymptote; {@code phaseTicks} ({@code ≥ 0}) is how far into the re-accel
     * the window starts; {@code rampFactor} {@code r ∈ (0,1)} is the per-tick
     * approach. Closed form of {@code Σ_{k=1}^{w} steadySpeed·(1 − r^{phaseTicks+k})}:
     *
     * <pre>
     *   travel = steadySpeed · ( window − r^{phaseTicks+1}·(1 − r^window)/(1 − r) )
     * </pre>
     *
     * An invalid ramp ({@code r ≤ 0}, {@code r ≥ 1}, or {@link Double#NaN}) returns
     * the flat {@code steadySpeed · window} — no ramp, the linear-chase degrade.
     */
    public static double projectTravel(double steadySpeed, int phaseTicks, double rampFactor, int window) {
        if (window <= 0) {
            return 0.0;
        }
        if (!(rampFactor > 0.0) || rampFactor >= 1.0) {
            return steadySpeed * window; // no ramp — the flat linear chase (fallback rung)
        }
        int tau = Math.max(0, phaseTicks);
        // Σ_{k=1}^{window} r^{tau+k} = r^{tau+1} · (1 − r^window) / (1 − r)
        double rampDeficit =
                Math.pow(rampFactor, tau + 1) * (1.0 - Math.pow(rampFactor, window)) / (1.0 - rampFactor);
        return steadySpeed * (window - rampDeficit);
    }

    /**
     * The average chase RATE (b/t) the {@link #projectTravel travel} implies over the
     * window — {@code travel / window} — the effective rate that reproduces the
     * ramped travel when a caller re-multiplies by the same window. {@code window ≤ 0}
     * yields 0.
     */
    public static double projectRate(double steadySpeed, int phaseTicks, double rampFactor, int window) {
        if (window <= 0) {
            return 0.0;
        }
        return projectTravel(steadySpeed, phaseTicks, rampFactor, window) / window;
    }
}

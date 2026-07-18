package me.vexmc.mental.v5.feature.pots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.v5.feature.pots.PotsAim.Aim;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed pins for the exact-ballistic fast-pot aim (redesign 2026-07-07;
 * speed-band + lead round same day). The load-bearing pin is
 * {@link #landsOnTheLedPredictedFeet}: it forward-simulates the real discrete potion
 * flight ({@code vy -= 0.05; v *= 0.99; pos += v}) from the returned aim and asserts
 * the burst lands on the thrower's LED predicted feet {@code feet + vel·(N + lead)}
 * — the whole contract, verified against an independent simulation rather than the
 * production closed form. The speed band ({@code [minSpeed, maxSpeed]}), the forward
 * lead, both clamp fallbacks (ceiling and floor), and the degenerate case are pinned
 * alongside.
 */
class PotsAimTest {

    private static final double EXACT = 1.0e-6;

    /**
     * The exact server-order potion integration ({@code gravity, drag, move}),
     * independent of {@link PotsAim}'s closed form. Returns the potion position
     * after {@code ticks} ticks launched from {@code (lx,ly,lz)} at {@code aim}.
     */
    private static double[] simulate(
            double lx, double ly, double lz, Aim aim, int ticks) {
        double x = lx, y = ly, z = lz;
        double vx = aim.x(), vy = aim.y(), vz = aim.z();
        for (int k = 0; k < ticks; k++) {
            vy -= PotsAim.GRAVITY;              // gravity
            vx *= PotsAim.DRAG;                 // drag/inertia (all axes)
            vy *= PotsAim.DRAG;
            vz *= PotsAim.DRAG;
            x += vx;                            // move by the resulting delta
            y += vy;
            z += vz;
        }
        return new double[] {x, y, z};
    }

    /**
     * A resting thrower whose feet are 1.62 below the launch: the only lever is
     * straight down, and with lead 0 the target is the bare feet. With a generous
     * band the aim solves in one tick — closed form {@code v0y = (0 - 1.62 + G(1))/H(1)
     * = (-1.5705)/0.99 = -1.5863636}. The magnitude is what the throw NEEDS (1.586),
     * comfortably inside the band [0.5, 1.6]: speed is a bounded budget, not a target.
     */
    @Test
    void restingSelfThrowSolvesOneTickWithinTheBand() {
        Aim aim = PotsAim.aim(
                0.0, 1.62, 0.0,   // launch (eye)
                0.0, 0.0, 0.0,    // feet now
                0.0, 0.0, 0.0,    // thrower velocity
                0.5, 1.6, 0.0);   // [minSpeed, maxSpeed], leadTicks
        assertEquals(1, aim.ticks(), "a 1.62-block drop within the band solves in one tick");
        assertEquals(0.0, aim.x(), EXACT, "no horizontal lever");
        assertEquals(-1.5863636, aim.y(), EXACT, "the exact one-tick launch to the feet");
        assertEquals(0.0, aim.z(), EXACT, "no horizontal lever");
        assertTrue(aim.magnitude() <= 1.6 + EXACT, "the magnitude never exceeds the ceiling");
        assertTrue(aim.magnitude() >= 0.5 - EXACT, "the magnitude never dips below the floor");
        assertTrue(aim.magnitude() < 1.6 - 0.01, "and it spends only what the throw needs, not the whole ceiling");

        double[] burst = simulate(0.0, 1.62, 0.0, aim, aim.ticks());
        assertEquals(0.0, burst[1], EXACT, "the burst lands exactly on the feet's ground level");
    }

    /**
     * Lead 0 reproduces the un-led feet exactly (byte-identical to the pre-lead
     * intent). A moving thrower (vx = 0.2), feet below the launch, band [0.5, 1.5]:
     * {@code N=1} needs {@code sqrt(0.202^2 + 1.586^2) = 1.599 > 1.5}, so the aim
     * steps to {@code N=2}: {@code v0x = 0.4/H(2) = 0.4/1.9701 = 0.2030353},
     * {@code v0y = (-1.62 + G(2))/H(2) = -1.471995/1.9701 = -0.747167}. The two-tick
     * flight tracks the feet's motion so the burst lands at the un-led {@code P(2) =
     * (0.4, 0, 0)}.
     */
    @Test
    void leadZeroLandsOnTheUnledFeet() {
        Aim aim = PotsAim.aim(
                0.0, 1.62, 0.0,
                0.0, 0.0, 0.0,
                0.2, 0.0, 0.0,
                0.5, 1.5, 0.0);   // lead 0
        assertEquals(2, aim.ticks(), "N=1 exceeds the ceiling, so the smallest feasible tick is 2");
        assertEquals(0.2030353, aim.x(), EXACT, "the launch that reaches the un-led feet in two ticks");
        assertEquals(-0.747167, aim.y(), EXACT, "the remaining budget goes downward");
        assertEquals(0.0, aim.z(), EXACT, "no z lever");
        assertTrue(aim.magnitude() <= 1.5 + EXACT && aim.magnitude() >= 0.5 - EXACT, "within the band");

        double[] burst = simulate(0.0, 1.62, 0.0, aim, aim.ticks());
        assertEquals(0.4, burst[0], EXACT, "lead 0 → burst on the feet at impact (fx + vx·N)");
        assertEquals(0.0, burst[1], EXACT, "and lands on the feet's ground level");
        assertEquals(0.0, burst[2], EXACT, "no z drift");
    }

    /**
     * The lead lands the burst AHEAD of the feet-at-impact. Same throw as
     * {@link #leadZeroLandsOnTheUnledFeet} but {@code leadTicks = 1.0}: the target is
     * {@code feet + vel·(N + 1)}, so at {@code N=2} the aim solves for {@code P =
     * 0.2·3 = 0.6} — {@code v0x = 0.6/1.9701 = 0.3045531}, {@code v0y = -0.7471677}.
     * The forward-sim lands the burst at {@code 0.6}, which is {@code vx·lead = 0.2}
     * IN FRONT of the un-led feet-at-impact {@code (fx + vx·N = 0.4)} — the running
     * player moves into the cloud. Verified by the independent simulation.
     */
    @Test
    void leadLandsTheBurstAheadOfTheFeetAtImpact() {
        double vx = 0.2, leadTicks = 1.0;
        Aim aim = PotsAim.aim(
                0.0, 1.62, 0.0,
                0.0, 0.0, 0.0,
                vx, 0.0, 0.0,
                0.5, 1.5, leadTicks);
        assertEquals(2, aim.ticks(), "same feasible tick as lead 0 — the lead moves the landing, not N");
        assertEquals(0.3045531, aim.x(), EXACT, "the launch that reaches the LED feet in two ticks");
        assertEquals(-0.7471677, aim.y(), EXACT, "vertical budget to the same ground level");

        int n = aim.ticks();
        double[] burst = simulate(0.0, 1.62, 0.0, aim, n);
        double unledFeetAtImpact = vx * n;              // 0.4
        double ledTarget = vx * (n + leadTicks);        // 0.6
        assertEquals(ledTarget, burst[0], EXACT, "burst lands on the LED feet (fx + vx·(N + lead))");
        assertTrue(burst[0] > unledFeetAtImpact + EXACT, "the burst is ahead of the feet-at-impact");
        assertEquals(vx * leadTicks, burst[0] - unledFeetAtImpact, EXACT, "ahead by exactly vx·lead");
        assertEquals(0.0, burst[1], EXACT, "still lands on the feet's ground level");
        assertEquals(0.0, burst[2], EXACT, "no z drift");
    }

    /**
     * Landing accuracy on arbitrary, asymmetric geometry and motion, with a lead: the
     * burst, forward-simulated for the solved tick count, lands on the LED predicted
     * feet {@code feet + vel·(ticks + lead)} — the redesign's whole point, verified
     * end-to-end against the independent simulation. All three velocity components are
     * solved independently (the geometry is asymmetric on every axis).
     */
    @Test
    void landsOnTheLedPredictedFeet() {
        double fx = 5.0, fy = 0.5, fz = 4.0;
        double vx = 0.1, vy = -0.02, vz = 0.3;
        double lx = 2.0, ly = 3.0, lz = -1.0;
        double leadTicks = 1.0;
        Aim aim = PotsAim.aim(lx, ly, lz, fx, fy, fz, vx, vy, vz, 0.5, 2.0, leadTicks);
        assertTrue(aim.magnitude() <= 2.0 + EXACT, "the magnitude never exceeds the ceiling");
        assertTrue(aim.magnitude() >= 0.5 - EXACT, "the magnitude never dips below the floor");

        int n = aim.ticks();
        double lead = n + leadTicks;
        double[] burst = simulate(lx, ly, lz, aim, n);
        assertEquals(fx + vx * lead, burst[0], EXACT, "burst x = LED predicted feet x");
        assertEquals(fy + vy * lead, burst[1], EXACT, "burst y = LED predicted feet y");
        assertEquals(fz + vz * lead, burst[2], EXACT, "burst z = LED predicted feet z");
    }

    /**
     * Ceiling clamp: a thrower too fast to chase in-band (vx = 2.5, band [0.5, 1.0]).
     * Along the direct-throw arm the required speed bottoms out at {@code N=4}
     * (≈2.5798 b/t — {@code N=3}→2.5902, {@code N=5}→2.5827 both higher), still far
     * above the ceiling, so no N lands in-band. The fallback clamps the closest
     * candidate ({@code N=4}) down to {@code maxSpeed}: {@code |v| == 1.0}, still
     * pointed at the lead.
     */
    @Test
    void outrunFallbackClampsToTheCeiling() {
        Aim aim = PotsAim.aim(
                0.0, 1.62, 0.0,
                0.0, 0.0, 0.0,
                2.5, 0.0, 0.0,   // 2.5 b/t — far faster than any in-band chase
                0.5, 1.0, 0.0);  // band [0.5, 1.0]
        assertEquals(4, aim.ticks(), "the direct-arm speed minimum is at N=4");
        assertEquals(1.0, aim.magnitude(), EXACT, "clamped to the ceiling — spends the whole budget chasing the lead");
    }

    /**
     * Floor clamp: a resting 1.62-drop with a narrow band [0.46, 0.55] that falls in
     * the GAP between the direct-arm speeds {@code N=2 (0.7472)} and {@code N=3
     * (0.4506)} — nothing lands in-band. The closest candidate is {@code N=3}
     * (0.4506, just below the floor 0.46, nearer than N=2 above the ceiling), so the
     * fallback clamps its magnitude UP to {@code minSpeed}: {@code (0, -0.46, 0)}. The
     * direct-arm walk stops at the geometric minimum, so no slow upward-arc lob is
     * ever considered.
     */
    @Test
    void slowSolveFallbackClampsToTheFloor() {
        Aim aim = PotsAim.aim(
                0.0, 1.62, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.46, 0.55, 0.0);
        assertEquals(3, aim.ticks(), "the closest in-gap candidate is the three-tick throw");
        assertEquals(0.0, aim.x(), EXACT, "no horizontal lever");
        assertEquals(-0.46, aim.y(), EXACT, "clamped up to the floor, straight down");
        assertEquals(0.0, aim.z(), EXACT, "no horizontal lever");
        assertEquals(0.46, aim.magnitude(), EXACT, "clamped to the floor — never slower than minSpeed");
    }

    /** A zero (or negative) ceiling cannot aim anything — a defined zero result. */
    @Test
    void nonPositiveCeilingYieldsZero() {
        Aim aim = PotsAim.aim(0.0, 1.62, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        assertEquals(0.0, aim.magnitude(), EXACT);
        assertEquals(0, aim.ticks());
    }

    /** The closed-form range and drop coefficients match their hand-computed values. */
    @Test
    void coefficientsMatchTheHandComputedValues() {
        assertEquals(0.99, PotsAim.rangeCoefficient(1), EXACT);
        assertEquals(1.9701, PotsAim.rangeCoefficient(2), EXACT);
        assertEquals(0.0495, PotsAim.gravityDrop(1), EXACT);
        assertEquals(0.148005, PotsAim.gravityDrop(2), EXACT);
    }
}

package me.vexmc.mental.kernel.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Hand-computed pins for the vertical-axis combo shaper (COMBO_VERTICAL). Every
 * expected apex is derived by walking the vanilla vertical arc by hand (gravity
 * 0.08, drag 0.98, the {@link Decay} constants) so the kernel math is anchored to
 * arithmetic, not to itself.
 *
 * <p>The two reference apexes (feet-height above the launch ground):</p>
 * <pre>
 *   launch vy 0.40  → apex 1.1531078912 at tick 5 (the design's "~1.15" era standard):
 *     t1 y=0.40                 vy=(0.40-0.08)*0.98   =0.3136
 *     t2 y=0.7136               vy=(0.3136-0.08)*0.98 =0.228928
 *     t3 y=0.942528             vy=(0.228928-0.08)*0.98=0.14594944
 *     t4 y=1.08847744           vy=(0.14594944-0.08)*0.98=0.0646304512
 *     t5 y=1.1531078912         vy<0 → apex
 *   launch vy 0.3608 → apex 0.964792652928 at tick 5 (the real grounded opener,
 *     where the victim parks at the -0.0784 vertical equilibrium: -0.0784/2 + 0.4):
 *     t1 y=0.3608  t2 y=0.635984  t3 y=0.82726432  t4 y=0.9363190336  t5 y=0.964792652928
 * </pre>
 */
class VerticalTrimTest {

    private static final double EPSILON = 1.0e-9;

    /** Apex of a standard 0.40 fresh launch from ground — the era "~1.15" citation. */
    private static final double APEX_040 = 1.1531078912;

    /** Apex of the real grounded opener's 0.3608 fresh launch from ground. */
    private static final double APEX_03608 = 0.964792652928;

    /** The grounded opener's era shipped vertical (-0.0784 equilibrium halved, + 0.4 base). */
    private static final double V0_OPENER = 0.3608;

    private static final double BOUND = 0.06;

    /** The apex helper matches the hand-walked era arc for both reference launches. */
    @Test
    void apexHeightMatchesTheHandWalkedEraArc() {
        assertEquals(APEX_040, VerticalTrim.apexHeight(0.40, 0.0), EPSILON,
                "the 0.40 launch peaks at ~1.153 blocks (the era standard apex)");
        assertEquals(APEX_03608, VerticalTrim.apexHeight(0.3608, 0.0), EPSILON,
                "the real 0.3608 grounded opener peaks at ~0.965 blocks");
        // A launch from height h just offsets the whole arc by h (the vy update is
        // position-independent), so the apex shifts by exactly h.
        assertEquals(0.5 + APEX_040, VerticalTrim.apexHeight(0.40, 0.5), EPSILON,
                "launch height shifts the apex one-for-one");
        // A non-rising launch peaks at its launch height (no lift), never below ground.
        assertEquals(0.0, VerticalTrim.apexHeight(-0.1, 0.0), EPSILON);
        assertEquals(0.75, VerticalTrim.apexHeight(0.0, 0.75), EPSILON);
    }

    /** INACTIVE ⇒ the shipped vertical is byte-identical to the era value (zero-touch). */
    @Test
    void inactiveConfigIsIdentity() {
        VerticalTrim.Result result = VerticalTrim.trim(VerticalTrimConfig.INACTIVE, V0_OPENER, 0.0);
        assertEquals(V0_OPENER, result.shipped(), 0.0, "OFF ships the era vertical unchanged");
        assertEquals(0.0, result.delta(), 0.0);
        assertFalse(result.saturated(), "an inactive shaper never flags saturation");
    }

    /**
     * A LIFT toward the target apex, within the bound: the real 0.3608 opener (apex
     * 0.965) targeted at the 0.40-launch apex 1.153 solves to a 0.40 launch — a
     * +0.0392 lift, inside the 0.06 bound, so it is applied in full and does not flag.
     */
    @Test
    void liftsTowardTargetApexWithinTheBound() {
        VerticalTrim.Result result =
                VerticalTrim.trim(VerticalTrimConfig.of(APEX_040, BOUND), V0_OPENER, 0.0);
        assertFalse(result.saturated(), "a +0.0392 lift fits inside the 0.06 bound");
        assertEquals(0.40, result.shipped(), 1.0e-6, "solved to the 0.40 launch that apexes at the target");
        assertEquals(0.40 - V0_OPENER, result.delta(), 1.0e-6, "the applied lift is +0.0392");
        assertEquals(APEX_040, result.achievedApex(), 1.0e-5, "the shipped launch reaches the target apex");
    }

    /**
     * A DAMP toward the target apex, within the bound: a 0.40 shipping opener (apex
     * 1.153) targeted at the lower 0.965 apex solves to a 0.3608 launch — a -0.0392
     * trim, inside the bound.
     */
    @Test
    void dampsTowardTargetApexWithinTheBound() {
        VerticalTrim.Result result =
                VerticalTrim.trim(VerticalTrimConfig.of(APEX_03608, BOUND), 0.40, 0.0);
        assertFalse(result.saturated(), "a -0.0392 damp fits inside the 0.06 bound");
        assertEquals(V0_OPENER, result.shipped(), 1.0e-6, "solved down to the 0.3608 launch");
        assertEquals(V0_OPENER - 0.40, result.delta(), 1.0e-6, "the applied damp is -0.0392");
        assertEquals(APEX_03608, result.achievedApex(), 1.0e-5);
    }

    /**
     * Bound saturation — the "something is wrong" signal. A 2.0-block target apex
     * from the 0.3608 opener demands a launch well past 0.4208, so the +0.06 bound
     * clamps it AND {@code saturated} is raised; the shipped vertical is exactly
     * {@code V0 + bound} and the achieved apex falls short of the target.
     */
    @Test
    void saturatesAndFlagsWhenTargetDemandsMoreThanTheBound() {
        VerticalTrim.Result result =
                VerticalTrim.trim(VerticalTrimConfig.of(2.0, BOUND), V0_OPENER, 0.0);
        assertTrue(result.saturated(), "a 2.0-block target overshoots the bound → flagged");
        assertEquals(V0_OPENER + BOUND, result.shipped(), EPSILON, "clamped to exactly V0 + bound");
        assertEquals(BOUND, result.delta(), EPSILON);
        assertTrue(result.achievedApex() < 2.0, "the bounded lift cannot reach the demanded apex");
    }

    /**
     * A degenerate no-op: the era vertical already produces the target apex, so the
     * solve wants a zero delta and the shipped vertical is the era value.
     */
    @Test
    void noOpWhenTheEraVerticalAlreadyMeetsTheTarget() {
        VerticalTrim.Result result =
                VerticalTrim.trim(VerticalTrimConfig.of(APEX_040, BOUND), 0.40, 0.0);
        assertFalse(result.saturated());
        assertEquals(0.40, result.shipped(), 1.0e-6, "already at the target apex → no shaping");
        assertEquals(0.0, result.delta(), 1.0e-6);
    }

    /**
     * The apex target is measured above the LAUNCH GROUND: an airborne victim launched
     * from 0.5 blocks up, targeted at {@code 0.5 + apex(0.40)}, solves to the same 0.40
     * launch as the grounded case — the launch height shifts the target one-for-one.
     */
    @Test
    void airborneLaunchTargetsApexAboveTheLaunchGround() {
        VerticalTrim.Result result =
                VerticalTrim.trim(VerticalTrimConfig.of(0.5 + APEX_040, BOUND), V0_OPENER, 0.5);
        assertFalse(result.saturated());
        assertEquals(0.40, result.shipped(), 1.0e-6, "same 0.40 launch — the target rode the 0.5 launch height");
        assertEquals(0.5 + APEX_040, result.achievedApex(), 1.0e-5);
    }
}

package me.vexmc.mental.kernel.math;

/**
 * The pure legacy victim-physics decay model — the stateless authority
 * extracted from the {@code VictimMotion} ledger. Legacy servers ticked
 * full living-entity physics for players every connection tick: per tick,
 * vertical {@code (v − gravity) × 0.98}, horizontal {@code × 0.91} airborne
 * or {@code × 0.546} grounded, and ground collisions zeroing the vertical
 * BEFORE the gravity step, which parks grounded entities at the
 * {@code −gravity × 0.98} equilibrium (−0.0784) — never at zero. The
 * 1.7.10/1.8.9 standing-hit vertical of 0.3608 (= −0.0784/2 + 0.4) only
 * falls out with that baseline.
 *
 * <p>Every function here is pure; the stateful ledger (Phase 2's
 * {@code MotionLedger}) folds these over its samples.</p>
 */
public final class Decay {

    private Decay() {}

    /** Vanilla living-entity gravity; callers pass the attribute value when it differs. */
    public static final double DEFAULT_GRAVITY = 0.08;

    /** The vanilla jump impulse the legacy jump bookkeeping stamped into {@code motY}. */
    public static final double JUMP_IMPULSE = 0.42;

    /** The horizontal facing push vanilla's jump added for sprinting players. */
    public static final double SPRINT_JUMP_PUSH = 0.2;

    public static final double VERTICAL_DRAG = 0.98;
    public static final double AIR_DRAG = 0.91;
    public static final double TERMINAL_VELOCITY = 3.92;

    /**
     * The era default block slipperiness (stone & almost everything else).
     * The legacy ground drag is {@code slipperiness × 0.91}: 0.546 on stone,
     * 0.8918 on ice — measured on real 1.7.10: a packed-ice lane ships
     * hit 1 at 0.4 × 0.8918 = 0.3567 (the decay-on-send friction IS the
     * block) and residuals compound between hits (settle 5.37 vs stone's
     * 2.99 — ice nearly doubles era knockback distances).
     */
    public static final double DEFAULT_SLIPPERINESS = 0.6;
    public static final double REST_THRESHOLD = 0.005;

    /**
     * Tick stamp for writers with no era ordering contract (the per-tick
     * sampler, the velocity-event recorder): their records are never
     * excluded by the ledger's boundary read, preserving the pre-1.5.1
     * behavior the suites pin for packetless fake players.
     */
    public static final int NO_TICK = Integer.MIN_VALUE;

    /** A decayed read; all-zero when no residual survives an airborne read. */
    public record Motion(double vx, double vy, double vz) {

        public static final Motion ZERO = new Motion(0.0, 0.0, 0.0);

        public boolean isZero() {
            return vx == 0.0 && vy == 0.0 && vz == 0.0;
        }
    }

    /** The grounded vertical equilibrium: collision zeroes motY, then one gravity step. */
    public static double groundedEquilibrium(double gravity) {
        return -gravity * VERTICAL_DRAG;
    }

    /** The pure decay model at the era default slipperiness (stone). */
    public static Motion decay(
            double vx, double vy, double vz, int ticks, boolean grounded, double gravity) {
        return decay(vx, vy, vz, ticks, grounded, DEFAULT_SLIPPERINESS, gravity);
    }

    /**
     * The pure decay model — exposed for the engine tests and the test
     * suites. Grounded drag is {@code slipperiness × 0.91} (the era read the
     * block under the entity's feet: stone 0.546, ice 0.8918, slime 0.728).
     */
    public static Motion decay(
            double vx, double vy, double vz, int ticks, boolean grounded,
            double slipperiness, double gravity) {
        double drag = grounded ? slipperiness * AIR_DRAG : AIR_DRAG;
        for (int i = 0; i < ticks; i++) {
            vx *= drag;
            vz *= drag;
            vy = (vy - gravity) * VERTICAL_DRAG;
            if (vy < -TERMINAL_VELOCITY) {
                vy = -TERMINAL_VELOCITY;
            }
        }
        if (grounded) {
            vy = groundedEquilibrium(gravity);
        }
        if (Math.abs(vx) < REST_THRESHOLD) {
            vx = 0.0;
        }
        if (Math.abs(vz) < REST_THRESHOLD) {
            vz = 0.0;
        }
        return new Motion(vx, vy, vz);
    }

    /** Delivery decay at the era default slipperiness (stone). */
    public static Motion decayOnce(
            double vx, double vy, double vz, boolean grounded, double gravity) {
        return decayOnce(vx, vy, vz, grounded, DEFAULT_SLIPPERINESS, gravity);
    }

    /**
     * One tick of the legacy victim physics applied to a vector about to
     * ship — the 1.7.10 tracker delivery decay (the victim's connection
     * ticked between the knock mutating the fields and the end-of-tick
     * tracker send). Friction comes from the victim's pre-move ground state
     * AND the block under their feet: a grounded knock on packed ice ships
     * ×0.8918, not ×0.546 (measured: real 1.7.10 ice hit 1 = 0.3567).
     */
    public static Motion decayOnce(
            double vx, double vy, double vz, boolean grounded, double slipperiness, double gravity) {
        double drag = grounded ? slipperiness * AIR_DRAG : AIR_DRAG;
        return new Motion(vx * drag, (vy - gravity) * VERTICAL_DRAG, vz * drag);
    }
}

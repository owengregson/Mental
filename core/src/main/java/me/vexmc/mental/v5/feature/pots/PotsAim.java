package me.vexmc.mental.v5.feature.pots;

/**
 * The pure ballistic aim for {@code fast-pots} (POTS family, owner directive
 * 2026-07-04): given where a splash potion is launched from, where the thrower's
 * feet are now, and how fast the thrower is moving, compute the launch velocity
 * that makes the potion land on the thrower's <em>predicted</em> feet at a fixed
 * target speed.
 *
 * <p>Bukkit-free plain doubles, so it is exhaustively unit-pinnable off the
 * server (the {@code FastPotsUnit} converts to/from {@code Vector}). Core-level,
 * not kernel: it is combat-adjacent gameplay math, not part of the frozen
 * delivery core.</p>
 *
 * <h2>The ballistic model (deliberately simplified)</h2>
 *
 * A thrown splash potion in Minecraft falls under gravity {@code 0.05} blocks/tick
 * and loses {@code 1%} of its speed to drag each tick ({@code ×0.99}). At the
 * {@code fast-pots} speeds (≥ vanilla ×1) the flight from eye to feet is one to a
 * few ticks, so:
 *
 * <ul>
 *   <li><b>Drag is neglected.</b> Over ~2 ticks it removes &lt; 2% of the speed —
 *       below the splash radius tolerance — and modelling it would not change
 *       which block the cloud centres on.</li>
 *   <li><b>Gravity is compensated to first order.</b> Over a flight of {@code T}
 *       ticks a projectile drops about {@code ½·g·T²}; we raise the aim's target
 *       Y by that amount so the potion arrives <em>at</em> the feet rather than
 *       short of them. The dominant term is still the straight aim at the
 *       predicted feet — for a near-vertical self-throw the correction is a few
 *       centimetres.</li>
 * </ul>
 *
 * <p>{@code T} and the aim are a mutual fixed point (the flight time depends on
 * the aim distance, which depends on the predicted feet, which depend on the
 * flight time); {@link #ITERATIONS} passes settle it — flight is short, so it
 * converges in two.</p>
 *
 * <p><b>Magnitude invariant:</b> the returned velocity always has length exactly
 * {@code targetSpeed} (the direction is normalised, then scaled), so "magnitude =
 * multiplier × vanilla launch speed" holds by construction — the caller passes
 * {@code targetSpeed = multiplier × vanillaSpeed}.</p>
 */
public final class PotsAim {

    /** Splash-potion gravity, blocks/tick — the vertical drop the aim compensates for. */
    public static final double GRAVITY = 0.05;

    /** Fixed-point passes over the flight-time/aim pair; two settle a short flight. */
    public static final int ITERATIONS = 3;

    /** Below this the launch/feet points coincide and the aim degenerates — go straight down. */
    private static final double DEGENERATE = 1.0e-9;

    private PotsAim() {}

    /** An immutable aimed launch velocity. */
    public record Aim(double x, double y, double z) {
        /** Euclidean length — the magnitude invariant equals the requested target speed. */
        public double magnitude() {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    /**
     * The launch velocity that lands a potion on the thrower's predicted feet at
     * {@code targetSpeed}.
     *
     * @param lx,ly,lz  the launch point (the potion's spawn — roughly the eye)
     * @param fx,fy,fz  the thrower's feet position <em>now</em>
     * @param vx,vy,vz  the thrower's per-tick velocity (their displacement/tick)
     * @param targetSpeed the final launch speed (= multiplier × vanilla launch speed)
     */
    public static Aim aim(
            double lx, double ly, double lz,
            double fx, double fy, double fz,
            double vx, double vy, double vz,
            double targetSpeed) {

        if (targetSpeed <= DEGENERATE) {
            return new Aim(0.0, 0.0, 0.0);
        }

        // Seed the flight time from the straight-line distance to the feet now.
        double t = distance(lx, ly, lz, fx, fy, fz) / targetSpeed;

        double dx = 0.0;
        double dy = 0.0;
        double dz = 0.0;
        for (int pass = 0; pass < ITERATIONS; pass++) {
            // Where the feet will be when the potion arrives.
            double px = fx + vx * t;
            double py = fy + vy * t;
            double pz = fz + vz * t;
            // Raise the aim by the gravity drop so it arrives at the feet, not short.
            double dropCompensation = 0.5 * GRAVITY * t * t;
            dx = px - lx;
            dy = (py + dropCompensation) - ly;
            dz = pz - lz;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            t = length / targetSpeed;
        }

        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= DEGENERATE) {
            // Launch and (compensated) feet coincide — a self-throw with no lever to
            // aim along. Straight down at speed is the sane fast-pot default.
            return new Aim(0.0, -targetSpeed, 0.0);
        }
        double scale = targetSpeed / length;
        return new Aim(dx * scale, dy * scale, dz * scale);
    }

    private static double distance(
            double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

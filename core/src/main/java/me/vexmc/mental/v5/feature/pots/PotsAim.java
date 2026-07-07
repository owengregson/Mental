package me.vexmc.mental.v5.feature.pots;

/**
 * The exact-ballistic aim for {@code fast-pots} (POTS family; exact-ballistic
 * redesign, owner directive 2026-07-07): given where a splash potion is launched
 * from, where the thrower's feet are now, and how fast the thrower is moving,
 * compute the launch velocity that makes the potion's burst land <em>on</em> the
 * thrower's predicted feet — solving the real discrete potion flight, not a
 * continuous approximation.
 *
 * <p>Bukkit-free plain doubles, so it is exhaustively unit-pinnable off the
 * server (the {@code FastPotsUnit} converts to/from {@code Vector}). Core-level,
 * not kernel: it is combat-adjacent gameplay math, not part of the frozen
 * delivery core.</p>
 *
 * <h2>The ballistic model (exact discrete flight)</h2>
 *
 * A thrown splash potion integrates each tick as {@code vy -= 0.05} (gravity —
 * {@code AbstractThrownPotion.getDefaultGravity()}, which overrides the {@code
 * 0.03} throwable base), then {@code v *= 0.99} (drag/inertia), then {@code pos +=
 * v} — the modern {@code ThrowableProjectile.tick()} order (gravity, drag, move).
 * Summing that recurrence over {@code N} ticks is closed-form: with
 *
 * <pre>
 *   H(N) = d·(1 - d^N)/(1 - d)         // horizontal range per unit launch velocity
 *   G(N) = (d·g/(1 - d))·(N - H(N))    // total gravity drop over N ticks
 * </pre>
 *
 * a launch {@code v0} from spawn {@code L} lands, after {@code N} ticks, at
 * {@code x = L.x + v0.x·H(N)}, {@code z = L.z + v0.z·H(N)}, and
 * {@code y = L.y + v0.y·H(N) - G(N)}. Inverting for the launch that reaches a
 * target {@code (xt, yt, zt)} at tick {@code N}:
 *
 * <pre>
 *   v0.x = (xt - L.x) / H(N)
 *   v0.z = (zt - L.z) / H(N)
 *   v0.y = (yt - L.y + G(N)) / H(N)
 * </pre>
 *
 * <p>The target is the thrower's <em>predicted</em> feet at impact —
 * {@code feet + throwerVel·N} on every axis (a grounded thrower has {@code
 * vel.y ≈ 0}, so the target y is the feet's ground level, which is where the
 * self-thrown potion bursts: it cannot hit its own thrower, so it breaks on the
 * ground below).</p>
 *
 * <h2>Speed is a budget, not a target (owner 2026-07-07)</h2>
 *
 * The magnitude is a free variable. Iterating {@code N = 1 … }{@link #NMAX}, the
 * aim returns the <b>smallest N whose required launch speed does not exceed the
 * cap</b> — the promptest exact-on-feet landing within the {@code speedCap =
 * multiplier × vanilla} budget. Every feasible {@code N} lands exactly on the
 * predicted feet by construction; a smaller {@code N} throws harder (up to the
 * cap) and bursts sooner, so it drifts least from the prediction. If no {@code N}
 * is feasible (the thrower is outrunning the potion at the cap), the
 * minimum-required-speed candidate is scaled to the cap — the closest reachable
 * throw toward the predicted lead. A grounded target is always below the spawn, so
 * a feasible {@code N} normally exists (in the limit the potion is simply dropped).
 *
 * <p><b>Cross-version:</b> {@code g = 0.05} and {@code d = 0.99} are
 * potion-universal across the range; only the pre-1.13 tick order (move before
 * drag/gravity, a ≈1% range difference — sub-centimetre over a 1–3-tick flight)
 * differs, a documented bounded approximation of the modern order modelled here.</p>
 */
public final class PotsAim {

    /** Splash-potion gravity, blocks/tick — {@code AbstractThrownPotion.getDefaultGravity()}. */
    public static final double GRAVITY = 0.05;

    /** Air drag/inertia per tick — {@code ThrowableProjectile.applyInertia()} (0.99, not in water). */
    public static final double DRAG = 0.99;

    /** The impact-tick search horizon (1 s). Realistic flights settle in ≤ 6 ticks. */
    public static final int NMAX = 20;

    /** Below this the launch degenerates — no speed budget to aim anything. */
    private static final double DEGENERATE = 1.0e-9;

    private PotsAim() {}

    /** An immutable aimed launch velocity plus the impact tick it was solved for. */
    public record Aim(double x, double y, double z, int ticks) {
        /**
         * Euclidean length — the solved launch speed, at or below the requested cap.
         * The feasible path returns the exact vector whose length was compared to the
         * cap, so it is {@code ≤} by construction; the rare out-of-budget fallback
         * re-scales, which float rounding can push a couple of ulp over the cap
         * (~1e-15 b/t — physically nil, and the redirect reads {@link #x()}/{@link #y()}/
         * {@link #z()} directly, never this).
         */
        public double magnitude() {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    /**
     * The launch velocity that lands a potion's burst on the thrower's predicted
     * feet, spending the least speed up to {@code speedCap}.
     *
     * @param lx,ly,lz  the launch point (the potion's spawn — {@code (x, eyeY-0.1, z)})
     * @param fx,fy,fz  the thrower's feet position <em>now</em>
     * @param vx,vy,vz  the thrower's per-tick velocity (their displacement/tick)
     * @param speedCap  the launch-speed ceiling (= multiplier × vanilla launch speed)
     */
    public static Aim aim(
            double lx, double ly, double lz,
            double fx, double fy, double fz,
            double vx, double vy, double vz,
            double speedCap) {

        if (speedCap <= DEGENERATE) {
            return new Aim(0.0, 0.0, 0.0, 0);
        }

        double bestSpeed = Double.POSITIVE_INFINITY;
        double bestX = 0.0;
        double bestY = -speedCap; // a straight-down drop is the safe worst-case default
        double bestZ = 0.0;
        int bestTicks = NMAX;

        for (int n = 1; n <= NMAX; n++) {
            double h = rangeCoefficient(n);
            double drop = gravityDrop(n);
            // The predicted feet at the moment the potion arrives.
            double px = fx + vx * n;
            double py = fy + vy * n;
            double pz = fz + vz * n;
            // The exact launch that lands there at tick n (closed-form inverse).
            double v0x = (px - lx) / h;
            double v0z = (pz - lz) / h;
            double v0y = (py - ly + drop) / h;
            double speed = Math.sqrt(v0x * v0x + v0y * v0y + v0z * v0z);
            if (speed <= speedCap) {
                // Smallest feasible N — the promptest exact-on-feet landing.
                return new Aim(v0x, v0y, v0z, n);
            }
            if (speed < bestSpeed) {
                bestSpeed = speed;
                bestX = v0x;
                bestY = v0y;
                bestZ = v0z;
                bestTicks = n;
            }
        }

        // No feasible N within the cap: scale the least-speed candidate down to the
        // cap so the throw still points at the predicted lead as far as the budget
        // reaches (the thrower is outrunning the potion — a rare, documented edge).
        if (bestSpeed <= DEGENERATE || Double.isInfinite(bestSpeed)) {
            return new Aim(0.0, -speedCap, 0.0, bestTicks);
        }
        double scale = speedCap / bestSpeed;
        return new Aim(bestX * scale, bestY * scale, bestZ * scale, bestTicks);
    }

    /** {@code H(N) = d·(1 - d^N)/(1 - d)} — total horizontal travel per unit launch velocity over N ticks. */
    static double rangeCoefficient(int n) {
        return DRAG * (1.0 - Math.pow(DRAG, n)) / (1.0 - DRAG);
    }

    /** {@code G(N) = (d·g/(1 - d))·(N - H(N))} — total vertical drop from gravity over N ticks. */
    static double gravityDrop(int n) {
        return (DRAG * GRAVITY / (1.0 - DRAG)) * (n - rangeCoefficient(n));
    }
}

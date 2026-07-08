package me.vexmc.mental.v5.feature.pots;

/**
 * The exact-ballistic aim for {@code fast-pots} (POTS family; exact-ballistic
 * redesign, owner directive 2026-07-07; speed-band + lead round same day): given
 * where a splash potion is launched from, where the thrower's feet are now, and how
 * fast the thrower is moving, compute the launch velocity that makes the potion's
 * burst land <em>just in front of</em> the thrower's predicted feet — solving the
 * real discrete potion flight, not a continuous approximation.
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
 * <p>All three components are solved independently — the closed form fully
 * determines the velocity vector (direction and magnitude) for each {@code N}, and
 * the spawn {@code L} is a pure input, never moved. Only which impact-tick {@code N}
 * is reachable is constrained (by the speed band below); no velocity component is.</p>
 *
 * <h2>The target: the LED predicted feet (owner 2026-07-07)</h2>
 *
 * The target is the thrower's <em>predicted, forward-led</em> feet:
 * {@code P(N) = feet + throwerVel·(N + leadTicks)} on every axis. Two reasons for
 * the {@code leadTicks} lead (default ≈ 1 tick), both pulling the same way:
 *
 * <ol>
 *   <li><b>The burst is sub-tick.</b> The potion's ground collision is a swept/AABB
 *       test that fires when it first dips into the ground block <em>during</em> tick
 *       {@code N}'s move — a moment strictly between {@code P(N-1)} and {@code P(N)}
 *       — so an un-led aim ({@code feet + vel·N}) plants the burst slightly
 *       <em>behind</em> the tick-{@code N} feet along the thrower's motion.</li>
 *   <li><b>Run-into-the-cloud feel.</b> The owner wants the cloud a touch ahead so a
 *       moving player runs INTO it, not onto the spot just vacated.</li>
 * </ol>
 *
 * <p>A forward lead of one tick of the thrower's own motion absorbs the sub-tick lag
 * and delivers the requested bias in one knob. A grounded thrower has {@code vel.y ≈
 * 0}, so the lead never disturbs the target {@code y} (still the feet's ground level,
 * where the self-thrown pot bursts — it cannot hit its own thrower, so it breaks on
 * the ground below). {@code leadTicks = 0} restores the un-led feet exactly.</p>
 *
 * <h2>Speed is a bounded band, not a target (owner 2026-07-07)</h2>
 *
 * The magnitude is a free variable, bounded into {@code [minSpeed, maxSpeed]} (=
 * {@code [minMultiplier, maxMultiplier] × vanilla}, the owner's {@code [0.5, 1.5]}
 * band by default). Walking {@code N = 1 … }{@link #NMAX} along the <b>direct-throw
 * arm</b> (increasing {@code N} monotonically lowers the required speed until the
 * geometric minimum, past which the closed form only yields slow upward-arc lobs —
 * never wanted for a self-heal, so the walk stops at the first rise), the aim returns
 * the <b>smallest N whose required launch speed {@code |v0(N)|} lies within the
 * band</b> — the promptest exact-on-(led-)feet landing at a speed the band permits.
 * Every in-band {@code N} lands exactly on the led feet by construction; a smaller {@code
 * N} throws harder (up to the ceiling) and bursts sooner, so it drifts least from
 * the prediction. The band is the only real lever on which {@code N} is reachable:
 * beyond it the solver must use the OTHER variables (all already free), so it steps
 * {@code N} rather than exceeding {@code 1.5×}.
 *
 * <p><b>Best-effort fallback</b> (no direct-arm {@code N} lands in-band — e.g. the
 * thrower is outrunning the pot at the ceiling, or a barely-below drop needs less
 * than the floor): pick the direct-arm candidate whose required speed is
 * <em>closest</em> to the band and clamp its magnitude to the near edge ({@code
 * maxSpeed} if it was too fast, {@code minSpeed} if too slow) — the closest reachable
 * direct throw toward the led feet. A grounded target is normally below the spawn, so
 * an in-band {@code N} usually exists.</p>
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
         * Euclidean length — the solved launch speed, within the requested band. The
         * in-band path returns the exact vector whose length was compared to the band,
         * so it is {@code ∈ [minSpeed, maxSpeed]} by construction; the fallback
         * re-scales to a band edge, which float rounding can push a couple of ulp past
         * the edge (~1e-15 b/t — physically nil, and the redirect reads {@link #x()}/
         * {@link #y()}/{@link #z()} directly, never this).
         */
        public double magnitude() {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    /**
     * The launch velocity that lands a potion's burst on the thrower's led,
     * predicted feet, spending the least speed within {@code [minSpeed, maxSpeed]}.
     *
     * @param lx,ly,lz  the launch point (the potion's spawn — {@code (x, eyeY-0.1, z)}); never moved
     * @param fx,fy,fz  the thrower's feet position <em>now</em>
     * @param vx,vy,vz  the thrower's per-tick velocity (their displacement/tick)
     * @param minSpeed  the launch-speed FLOOR (= minMultiplier × vanilla launch speed)
     * @param maxSpeed  the launch-speed CEILING (= maxMultiplier × vanilla launch speed)
     * @param leadTicks forward lead, in ticks of thrower motion (target = feet + vel·(N + leadTicks))
     */
    public static Aim aim(
            double lx, double ly, double lz,
            double fx, double fy, double fz,
            double vx, double vy, double vz,
            double minSpeed, double maxSpeed, double leadTicks) {

        if (maxSpeed <= DEGENERATE) {
            return new Aim(0.0, 0.0, 0.0, 0);
        }

        double bestDistance = Double.POSITIVE_INFINITY; // distance of the best candidate's speed to the band
        double bestSpeed = maxSpeed;
        double bestX = 0.0;
        double bestY = -maxSpeed; // a straight-down drop is the safe worst-case default
        double bestZ = 0.0;
        int bestTicks = NMAX;
        double previousSpeed = Double.POSITIVE_INFINITY;

        for (int n = 1; n <= NMAX; n++) {
            double h = rangeCoefficient(n);
            double drop = gravityDrop(n);
            // The LED predicted feet — where they will be leadTicks past the arrival tick.
            double lead = n + leadTicks;
            double px = fx + vx * lead;
            double py = fy + vy * lead;
            double pz = fz + vz * lead;
            // The exact launch that lands there at tick n (closed-form inverse).
            double v0x = (px - lx) / h;
            double v0z = (pz - lz) / h;
            double v0y = (py - ly + drop) / h;
            double speed = Math.sqrt(v0x * v0x + v0y * v0y + v0z * v0z);
            if (speed > previousSpeed) {
                // Past the direct-throw arm's minimum — every further N is a slower
                // upward-arc lob (thrown up to fall back onto the feet), never wanted
                // for a self-heal. Stop; the best direct throw is already recorded.
                break;
            }
            previousSpeed = speed;
            if (speed >= minSpeed && speed <= maxSpeed) {
                // Smallest in-band N — the promptest exact-on-led-feet landing.
                return new Aim(v0x, v0y, v0z, n);
            }
            // Not in-band: how far this candidate's speed is from the band edge it missed.
            double distance = speed < minSpeed ? (minSpeed - speed) : (speed - maxSpeed);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestSpeed = speed;
                bestX = v0x;
                bestY = v0y;
                bestZ = v0z;
                bestTicks = n;
            }
        }

        // No in-band N: clamp the closest candidate's magnitude to the near band edge
        // — the closest reachable throw toward the led feet (a rare, documented edge:
        // the thrower is outrunning the pot at the ceiling, or the drop is too small
        // to need the floor).
        if (bestSpeed <= DEGENERATE) {
            // Direction is numerically undefined; ship a straight drop at the floor.
            return new Aim(0.0, -Math.max(minSpeed, DEGENERATE), 0.0, bestTicks);
        }
        double clampedSpeed = bestSpeed < minSpeed ? minSpeed : maxSpeed;
        double scale = clampedSpeed / bestSpeed;
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

package me.vexmc.mental.module.knockback;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * The 1.7.10 server's view of a victim's motion, replicated per player.
 *
 * <p>Legacy servers left the victim's motion fields mutated after every
 * knockback, decaying only through friction — so the next hit's halving
 * operated on the residual of the previous one and successive hits
 * compounded. Modern servers either revert the fields (melee, 1.8.9+) or
 * let them go stale, so {@code getVelocity()} cannot reproduce that
 * behavior. This ledger can: every velocity actually shipped to a victim
 * is recorded, and reads decay it through the legacy friction model
 * ({@code y ← (y − gravity) × 0.98}, horizontal {@code × 0.91} airborne or
 * {@code × 0.546} on the ground, exactly the constants a 1.7.10 server
 * applied to a knocked player).</p>
 *
 * <p>Client-only motion (walking, jumping) never enters the ledger — it
 * never entered the legacy server's fields either, which is precisely why
 * W-tapping reduced felt knockback without reducing computed knockback.</p>
 *
 * <p>Reads are pure functions of an immutable sample, so the netty fast
 * path may read concurrently with owning-thread writes.</p>
 */
public final class VictimMotion {

    /** Vanilla living-entity gravity; callers pass the attribute value when it differs. */
    public static final double DEFAULT_GRAVITY = 0.08;

    private static final double VERTICAL_DRAG = 0.98;
    private static final double AIR_DRAG = 0.91;
    private static final double GROUND_DRAG = 0.91 * 0.6;
    private static final double TERMINAL_VELOCITY = 3.92;
    private static final double REST_THRESHOLD = 0.005;
    private static final int DEAD_AFTER_TICKS = 60;
    private static final long NANOS_PER_TICK = 50_000_000L;

    /** A decayed read; {@link #ZERO} when no knockback residual survives. */
    public record Motion(double vx, double vy, double vz) {

        public static final Motion ZERO = new Motion(0.0, 0.0, 0.0);

        public boolean isZero() {
            return vx == 0.0 && vy == 0.0 && vz == 0.0;
        }
    }

    private record Sample(double vx, double vy, double vz, long nanos) {}

    private final ConcurrentHashMap<UUID, Sample> samples = new ConcurrentHashMap<>();

    /** Records a velocity that was actually delivered to the victim's client. */
    public void record(@NotNull UUID victim, double vx, double vy, double vz, long nowNanos) {
        samples.put(victim, new Sample(vx, vy, vz, nowNanos));
    }

    /**
     * The victim's residual motion as a legacy server would see it now.
     * {@code groundedNow} selects the horizontal friction constant and kills
     * the vertical residual (a grounded entity's motionY collides to zero);
     * it is the caller's live or snapshot view of the victim.
     */
    public @NotNull Motion current(@NotNull UUID victim, long nowNanos, boolean groundedNow, double gravity) {
        Sample sample = samples.get(victim);
        if (sample == null) {
            return Motion.ZERO;
        }
        long elapsed = Math.max(0L, (nowNanos - sample.nanos()) / NANOS_PER_TICK);
        if (elapsed >= DEAD_AFTER_TICKS) {
            return Motion.ZERO;
        }
        return decay(sample.vx(), sample.vy(), sample.vz(), (int) elapsed, groundedNow, gravity);
    }

    /** The pure decay model — exposed for the engine tests and the test suites. */
    public static @NotNull Motion decay(
            double vx, double vy, double vz, int ticks, boolean grounded, double gravity) {
        double drag = grounded ? GROUND_DRAG : AIR_DRAG;
        for (int i = 0; i < ticks; i++) {
            vx *= drag;
            vz *= drag;
            vy = (vy - gravity) * VERTICAL_DRAG;
            if (vy < -TERMINAL_VELOCITY) {
                vy = -TERMINAL_VELOCITY;
            }
        }
        if (grounded) {
            vy = 0.0;
        }
        if (Math.abs(vx) < REST_THRESHOLD) {
            vx = 0.0;
        }
        if (Math.abs(vy) < REST_THRESHOLD) {
            vy = 0.0;
        }
        if (Math.abs(vz) < REST_THRESHOLD) {
            vz = 0.0;
        }
        return new Motion(vx, vy, vz);
    }

    public void forget(@NotNull UUID victim) {
        samples.remove(victim);
    }

    public void clear() {
        samples.clear();
    }
}

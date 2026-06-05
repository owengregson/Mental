package me.vexmc.mental.module.knockback;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * The legacy server's view of a victim's motion, replicated per player.
 *
 * <p>Legacy servers ticked full living-entity physics for players every
 * connection tick (input-free — a real victim's walk never entered the
 * fields), so the server's motion state was a little machine driven by
 * exactly three writers, all replicated here:</p>
 *
 * <ul>
 *   <li><b>Knockback</b> — the delivered velocity replaced the fields
 *       ({@link #record}); on 1.7.10 they then persisted and decayed (combos),
 *       on 1.8.9 melee restored the pre-hit fields after the send.</li>
 *   <li><b>The jump bookkeeping</b> — the movement-packet handler treated any
 *       grounded→airborne transition with upward motion as a jump and
 *       overwrote {@code motY = 0.42} (plus a 0.2 facing push for sprinting
 *       players). A knocked victim's liftoff fires it one tick after the hit,
 *       so the era vertical baseline for airborne victims is the jump value
 *       free-falling — NOT the delivered knock decaying. Measured on vanilla
 *       1.8.9: combo hit two ships vy 0.3478 = 0.42 nine gravity steps later
 *       ({@link #recordLiftoff}).</li>
 *   <li><b>Physics</b> — per tick: vertical {@code (v − gravity) × 0.98},
 *       horizontal {@code × 0.91} airborne or {@code × 0.546} grounded, and
 *       ground collisions zeroing the vertical BEFORE the gravity step, which
 *       parks grounded entities at the {@code −gravity × 0.98} equilibrium
 *       (−0.0784) — never at zero. The 1.7.10/1.8.9 standing-hit vertical of
 *       0.3608 (= −0.0784/2 + 0.4) only falls out with that baseline.</li>
 * </ul>
 *
 * <p>Reads are pure functions of an immutable sample, so the netty fast
 * path may read concurrently with owning-thread writes.</p>
 */
public final class VictimMotion {

    /** Vanilla living-entity gravity; callers pass the attribute value when it differs. */
    public static final double DEFAULT_GRAVITY = 0.08;

    /** The vanilla jump impulse the legacy jump bookkeeping stamped into {@code motY}. */
    public static final double JUMP_IMPULSE = 0.42;

    /** The horizontal facing push vanilla's jump added for sprinting players. */
    public static final double SPRINT_JUMP_PUSH = 0.2;

    private static final double VERTICAL_DRAG = 0.98;
    private static final double AIR_DRAG = 0.91;
    private static final double GROUND_DRAG = 0.91 * 0.6;
    private static final double TERMINAL_VELOCITY = 3.92;
    private static final double REST_THRESHOLD = 0.005;
    private static final int DEAD_AFTER_TICKS = 200;
    private static final long NANOS_PER_TICK = 50_000_000L;

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

    private record Sample(double vx, double vy, double vz, boolean grounded, long nanos) {}

    private final ConcurrentHashMap<UUID, Sample> samples = new ConcurrentHashMap<>();

    /**
     * Records a velocity that was actually delivered to the victim's client.
     * {@code grounded} is the victim's state at delivery — it selects the
     * horizontal drag for this segment until a transition restamps.
     */
    public void record(
            @NotNull UUID victim, double vx, double vy, double vz, boolean grounded, long nowNanos) {
        samples.put(victim, new Sample(vx, vy, vz, grounded, nowNanos));
    }

    /**
     * The legacy jump bookkeeping: a grounded→airborne transition moving
     * upward overwrote the server vertical with the jump impulse, plus the
     * sprint facing push. Call from the per-tick ground watcher; this is what
     * a knocked victim's liftoff looked like to the era server.
     */
    public void recordLiftoff(
            @NotNull UUID victim, boolean rising, boolean sprinting, float yawDegrees,
            long nowNanos, double gravity) {
        Motion current = current(victim, nowNanos, false, gravity);
        double vx = current.vx();
        double vz = current.vz();
        double vy = rising ? JUMP_IMPULSE : groundedEquilibrium(gravity);
        if (rising && sprinting) {
            double yawRadians = Math.toRadians(yawDegrees);
            vx += -Math.sin(yawRadians) * SPRINT_JUMP_PUSH;
            vz += Math.cos(yawRadians) * SPRINT_JUMP_PUSH;
        }
        samples.put(victim, new Sample(vx, vy, vz, false, nowNanos));
    }

    /**
     * An airborne→grounded transition: the simulated move collides, the
     * vertical zeroes and re-equilibrates, and horizontal decay switches to
     * ground drag from here on.
     */
    public void recordLanding(@NotNull UUID victim, long nowNanos, double gravity) {
        Motion current = current(victim, nowNanos, false, gravity);
        samples.put(victim, new Sample(
                current.vx(), groundedEquilibrium(gravity), current.vz(), true, nowNanos));
    }

    /**
     * The victim's residual motion as a legacy server would see it now.
     * {@code groundedNow} is the caller's live view; it wins over a stale
     * stamp when the watcher has not caught a landing yet.
     */
    public @NotNull Motion current(@NotNull UUID victim, long nowNanos, boolean groundedNow, double gravity) {
        Sample sample = samples.get(victim);
        if (sample == null) {
            return groundedNow ? new Motion(0.0, groundedEquilibrium(gravity), 0.0) : Motion.ZERO;
        }
        long elapsed = Math.max(0L, (nowNanos - sample.nanos()) / NANOS_PER_TICK);
        if (elapsed >= DEAD_AFTER_TICKS) {
            return groundedNow ? new Motion(0.0, groundedEquilibrium(gravity), 0.0) : Motion.ZERO;
        }
        boolean grounded = sample.grounded() || groundedNow;
        Motion decayed = decay(
                sample.vx(), sample.vy(), sample.vz(), (int) elapsed, sample.grounded(), gravity);
        if (grounded) {
            return new Motion(decayed.vx(), groundedEquilibrium(gravity), decayed.vz());
        }
        return decayed;
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

    /**
     * One tick of the legacy victim physics applied to a vector about to
     * ship — the 1.7.10 tracker delivery decay (the victim's connection
     * ticked between the knock mutating the fields and the end-of-tick
     * tracker send). Friction comes from the victim's pre-move ground state.
     */
    public static @NotNull Motion decayOnce(
            double vx, double vy, double vz, boolean grounded, double gravity) {
        double drag = grounded ? GROUND_DRAG : AIR_DRAG;
        return new Motion(vx * drag, (vy - gravity) * VERTICAL_DRAG, vz * drag);
    }

    public void forget(@NotNull UUID victim) {
        samples.remove(victim);
    }

    public void clear() {
        samples.clear();
    }
}

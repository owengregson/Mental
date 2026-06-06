package me.vexmc.mental.module.knockback;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Tick stamp for writers with no era ordering contract (the per-tick
     * sampler, the velocity-event recorder): their records are never
     * excluded by {@link #currentExcludingTick}, preserving the pre-1.5.1
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

    /**
     * {@code tick} is the server tick the record arrived in ({@link #NO_TICK}
     * for tick-agnostic writers); {@code previous} holds the displaced
     * older-tick sample so a boundary read can see the state as of the end of
     * the previous tick — depth one, never a chain.
     */
    private record Sample(
            double vx, double vy, double vz, boolean grounded, long nanos,
            int tick, @Nullable Sample previous) {

        Sample stripped() {
            return previous == null ? this
                    : new Sample(vx, vy, vz, grounded, nanos, tick, null);
        }
    }

    private final ConcurrentHashMap<UUID, Sample> samples = new ConcurrentHashMap<>();

    /** The displaced-state slot: a new tick's first record keeps the old sample as "previous". */
    private @Nullable Sample displaced(
            @Nullable Sample existing, int tick) {
        if (existing == null) {
            return null;
        }
        return existing.tick() != tick ? existing.stripped() : existing.previous();
    }

    /**
     * Records a velocity that was actually delivered to the victim's client.
     * {@code grounded} is the victim's state at delivery — it selects the
     * horizontal drag for this segment until a transition restamps.
     */
    public void record(
            @NotNull UUID victim, double vx, double vy, double vz, boolean grounded, long nowNanos) {
        record(victim, vx, vy, vz, grounded, nowNanos, NO_TICK);
    }

    public void record(
            @NotNull UUID victim, double vx, double vy, double vz, boolean grounded,
            long nowNanos, int tick) {
        samples.compute(victim, (id, existing) -> new Sample(
                vx, vy, vz, grounded, nowNanos, tick, displaced(existing, tick)));
    }

    /**
     * The legacy jump bookkeeping: a grounded→airborne transition moving
     * upward overwrote the server vertical with the jump impulse, plus the
     * sprint facing push. Fed per movement packet (real clients) or per tick
     * sample (packetless players); this is what a knocked victim's liftoff
     * looked like to the era server.
     */
    public void recordLiftoff(
            @NotNull UUID victim, boolean rising, boolean sprinting, float yawDegrees,
            long nowNanos, double gravity) {
        recordLiftoff(victim, rising, sprinting, yawDegrees, nowNanos, gravity, NO_TICK);
    }

    public void recordLiftoff(
            @NotNull UUID victim, boolean rising, boolean sprinting, float yawDegrees,
            long nowNanos, double gravity, int tick) {
        Motion current = current(victim, nowNanos, false, gravity);
        double pushX = 0.0;
        double pushZ = 0.0;
        if (rising && sprinting) {
            double yawRadians = Math.toRadians(yawDegrees);
            pushX = -Math.sin(yawRadians) * SPRINT_JUMP_PUSH;
            pushZ = Math.cos(yawRadians) * SPRINT_JUMP_PUSH;
        }
        double vx = current.vx() + pushX;
        double vy = rising ? JUMP_IMPULSE : groundedEquilibrium(gravity);
        double vz = current.vz() + pushZ;
        samples.compute(victim, (id, existing) -> new Sample(
                vx, vy, vz, false, nowNanos, tick, displaced(existing, tick)));
    }

    /**
     * An airborne→grounded transition: the simulated move collides, the
     * vertical zeroes and re-equilibrates, and horizontal decay switches to
     * ground drag from here on.
     */
    public void recordLanding(@NotNull UUID victim, long nowNanos, double gravity) {
        recordLanding(victim, nowNanos, gravity, NO_TICK);
    }

    public void recordLanding(@NotNull UUID victim, long nowNanos, double gravity, int tick) {
        Motion current = current(victim, nowNanos, false, gravity);
        samples.compute(victim, (id, existing) -> new Sample(
                current.vx(), groundedEquilibrium(gravity), current.vz(), true,
                nowNanos, tick, displaced(existing, tick)));
    }

    /**
     * The victim's residual motion as a legacy server would see it now.
     * {@code groundedNow} is the caller's live view; it wins over a stale
     * stamp when the watcher has not caught a landing yet.
     */
    public @NotNull Motion current(@NotNull UUID victim, long nowNanos, boolean groundedNow, double gravity) {
        return read(samples.get(victim), nowNanos, groundedNow, gravity);
    }

    /**
     * The residual as of the END OF THE PREVIOUS TICK: a record that arrived
     * during {@code excludeTick} is skipped in favor of the sample it
     * displaced. This is the era's attack-ordering contract — legacy servers
     * processed an attack in the attacker's connection slot BEFORE the
     * victim's same-tick movement packets applied, so a knock thrown the
     * instant its victim touches down reads the pre-landing flight (measured
     * on real 1.8.9, both join orders: boundary combo hits ship the declining
     * ~0.25 vertical, never a grounded re-stamp). Only packet-fed records
     * carry real ticks; sampler records ({@link #NO_TICK}) are never
     * excluded, so packetless players keep the inclusive view.
     */
    public @NotNull Motion currentExcludingTick(
            @NotNull UUID victim, int excludeTick, long nowNanos, boolean groundedNow, double gravity) {
        Sample sample = samples.get(victim);
        if (sample != null && sample.tick() != NO_TICK && sample.tick() == excludeTick) {
            Sample previous = sample.previous();
            // The boundary sample's own grounded state is the as-of truth;
            // the caller's live view would smuggle the excluded transition
            // back in. No previous means no pre-transition knowledge — fall
            // back to the no-sample semantics.
            return previous == null
                    ? (groundedNow ? new Motion(0.0, groundedEquilibrium(gravity), 0.0) : Motion.ZERO)
                    : read(previous, nowNanos, previous.grounded(), gravity);
        }
        return read(sample, nowNanos, groundedNow, gravity);
    }

    private @NotNull Motion read(
            @Nullable Sample sample,
            long nowNanos, boolean groundedNow, double gravity) {
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

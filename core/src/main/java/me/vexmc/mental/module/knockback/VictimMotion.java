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
    private static final double TERMINAL_VELOCITY = 3.92;

    /**
     * The era default block slipperiness (stone & almost everything else).
     * The legacy ground drag is {@code slipperiness × 0.91}: 0.546 on stone,
     * 0.8918 on ice — measured on real 1.7.10: a packed-ice lane ships
     * hit 1 at 0.4 × 0.8918 = 0.3567 (the decay-on-send friction IS the
     * block) and residuals compound between hits (settle 5.37 vs stone's
     * 2.99 — ice nearly doubles era knockback distances).
     */
    public static final double DEFAULT_SLIPPERINESS = 0.6;
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
     * the previous tick — depth one, never a chain. {@code slip} is the
     * block-under-feet slipperiness governing this grounded segment's drag.
     */
    private record Sample(
            double vx, double vy, double vz, boolean grounded, double slip, long nanos,
            int tick, @Nullable Sample previous) {

        Sample stripped() {
            return previous == null ? this
                    : new Sample(vx, vy, vz, grounded, slip, nanos, tick, null);
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
        record(victim, vx, vy, vz, grounded, DEFAULT_SLIPPERINESS, nowNanos, NO_TICK);
    }

    public void record(
            @NotNull UUID victim, double vx, double vy, double vz, boolean grounded,
            double slipperiness, long nowNanos, int tick) {
        samples.compute(victim, (id, existing) -> new Sample(
                vx, vy, vz, grounded, slipperiness, nowNanos, tick, displaced(existing, tick)));
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
        recordLiftoff(victim, rising, sprinting, yawDegrees, nowNanos, gravity, JUMP_IMPULSE, NO_TICK);
    }

    /**
     * {@code jumpImpulse} is the era stamp for THIS victim: 0.42 plus
     * 0.1 × (amplifier + 1) under Jump Boost — vanilla's jump() adds the
     * potion before the sprint push (measured on real 1.8.9: a Jump Boost I
     * victim's combo hit 2 ships vy 0.3286 = the 0.52 stamp eight decays
     * later).
     */
    public void recordLiftoff(
            @NotNull UUID victim, boolean rising, boolean sprinting, float yawDegrees,
            long nowNanos, double gravity, double jumpImpulse, int tick) {
        double pushX = 0.0;
        double pushZ = 0.0;
        if (rising && sprinting) {
            double yawRadians = Math.toRadians(yawDegrees);
            pushX = -Math.sin(yawRadians) * SPRINT_JUMP_PUSH;
            pushZ = Math.cos(yawRadians) * SPRINT_JUMP_PUSH;
        }
        double facingPushX = pushX;
        double facingPushZ = pushZ;
        samples.compute(victim, (id, existing) -> {
            // Read-modify-write atomically: the carried residual and the launch
            // drag are derived from the sample INSIDE compute(). Reading them
            // before compute() (as this once did) opens a gap in which a
            // region-thread record() — the knock — is silently overwritten by a
            // transition built on the stale pre-knock motion, corrupting the
            // residual the next combo hit reads. scaleHorizontal already reads
            // inside its mutator; this matches it.
            Motion current = read(existing, nowNanos, false, gravity);
            // The liftoff tick's own pre-move friction is still GROUND drag (the
            // era selected friction before the move that lifted the victim), so
            // the carried horizontals take one more grounded decay before the
            // airborne segment starts. Measured on real 1.7.10: chain residuals
            // run 0.4 × slip² × 0.91^k — two grounded decays (knock tick +
            // liftoff tick), e.g. ice hit 2 = 0.4821 = 0.4 × 0.8918² × 0.91⁷.
            double launchDrag = existing != null && existing.grounded()
                    ? existing.slip() * AIR_DRAG
                    : 1.0;
            double vx = current.vx() * launchDrag + facingPushX;
            double vy = rising ? jumpImpulse : groundedEquilibrium(gravity);
            double vz = current.vz() * launchDrag + facingPushZ;
            return new Sample(
                    vx, vy, vz, false, DEFAULT_SLIPPERINESS, nowNanos, tick, displaced(existing, tick));
        });
    }

    /**
     * An airborne→grounded transition: the simulated move collides, the
     * vertical zeroes and re-equilibrates, and horizontal decay switches to
     * ground drag — at the LANDED block's slipperiness — from here on.
     */
    public void recordLanding(@NotNull UUID victim, long nowNanos, double gravity) {
        recordLanding(victim, nowNanos, gravity, DEFAULT_SLIPPERINESS, NO_TICK);
    }

    public void recordLanding(
            @NotNull UUID victim, long nowNanos, double gravity, double slipperiness, int tick) {
        samples.compute(victim, (id, existing) -> {
            // Atomic read-modify-write — see recordLiftoff: deriving the carried
            // horizontals from a pre-read sample outside compute() would drop a
            // knock record() that raced in from the velocity-event thread.
            Motion current = read(existing, nowNanos, false, gravity);
            return new Sample(
                    current.vx(), groundedEquilibrium(gravity), current.vz(), true, slipperiness,
                    nowNanos, tick, displaced(existing, tick));
        });
    }

    /**
     * The attacker-side self-multiply: vanilla's attack ends every
     * bonus-knockback hit with {@code motX *= 0.6; motZ *= 0.6} on the
     * SERVER's copy of the attacker's motion (both eras, beside the sprint
     * clear). For a player knocked mid-trade who counter-hits, the next
     * knock they receive compounds off the smaller residual.
     */
    public void scaleHorizontal(@NotNull UUID player, double factor, long nowNanos, double gravity) {
        samples.computeIfPresent(player, (id, sample) -> {
            Motion now = read(sample, nowNanos, sample.grounded(), gravity);
            return new Sample(
                    now.vx() * factor, now.vy(), now.vz() * factor,
                    sample.grounded(), sample.slip(), nowNanos, NO_TICK, sample.stripped());
        });
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
                sample.vx(), sample.vy(), sample.vz(), (int) elapsed, sample.grounded(),
                sample.slip(), gravity);
        if (grounded) {
            return new Motion(decayed.vx(), groundedEquilibrium(gravity), decayed.vz());
        }
        return decayed;
    }

    /** The pure decay model at the era default slipperiness (stone). */
    public static @NotNull Motion decay(
            double vx, double vy, double vz, int ticks, boolean grounded, double gravity) {
        return decay(vx, vy, vz, ticks, grounded, DEFAULT_SLIPPERINESS, gravity);
    }

    /**
     * The pure decay model — exposed for the engine tests and the test
     * suites. Grounded drag is {@code slipperiness × 0.91} (the era read the
     * block under the entity's feet: stone 0.546, ice 0.8918, slime 0.728).
     */
    public static @NotNull Motion decay(
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
    public static @NotNull Motion decayOnce(
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
    public static @NotNull Motion decayOnce(
            double vx, double vy, double vz, boolean grounded, double slipperiness, double gravity) {
        double drag = grounded ? slipperiness * AIR_DRAG : AIR_DRAG;
        return new Motion(vx * drag, (vy - gravity) * VERTICAL_DRAG, vz * drag);
    }

    /**
     * The machine's own grounded view — the last recorded segment's state.
     * Callers recording a fresh knock use this over the live server flag:
     * modern servers flip a knocked player's onGround to airborne on the hit
     * tick itself, but the era's pre-move friction decayed the launch tick
     * at GROUND drag (the victim had not moved yet) — recording the knock
     * airborne skips that decay and ships hot residuals into the next hit
     * (measured: stone chain hit 2 read 0.494 where era ships 0.428).
     */
    public boolean groundedView(@NotNull UUID victim, boolean fallback) {
        Sample sample = samples.get(victim);
        return sample == null ? fallback : sample.grounded();
    }

    public void forget(@NotNull UUID victim) {
        samples.remove(victim);
    }

    public void clear() {
        samples.clear();
    }
}

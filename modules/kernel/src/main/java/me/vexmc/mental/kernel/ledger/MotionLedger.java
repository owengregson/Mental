package me.vexmc.mental.kernel.ledger;

import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.model.TickStamp;

/**
 * One victim's legacy motion fields — the single-writer residual fold that
 * replaces the old {@code VictimMotion} ledger. Only the owning session thread
 * (spec §2, D2) calls any method; there are deliberately NO concurrency
 * primitives here — thread safety is ownership, and the interleaving harness
 * (Task 2.6) proves the protocol.
 *
 * <p>The decay math itself is the pure {@link Decay} authority; this class only
 * folds it over time. State is a single "base sample" — the last record,
 * liftoff, landing or scale — plus a count of session ticks since that base was
 * set. {@link #current()} is exactly the base decayed that many ticks, so the
 * ported {@code DecayTest}/{@code VictimMotionTest} pins hold byte-for-byte.
 * Publication exclusion (the old {@code currentExcludingTick}) is now the
 * session's tick-boundary publication (Task 2.5), not a field here.</p>
 */
public final class MotionLedger {

    /**
     * Ticks of stillness after which the residual is dead — a grounded entity
     * parks at equilibrium, an airborne one reads zero. The old
     * {@code VictimMotion} constant is 200 and its {@code residualExpiresAfterTheDeadWindow}
     * test pins the window at 200 ticks (record then read at the 200-tick
     * boundary reads dead); that pinned value is carried here. (The mandate
     * §4.3 mentions "dead after 60", but the tests pin 200 — see the task
     * note.)
     */
    public static final int DEAD_AFTER_TICKS = 200;

    private final double gravity;

    private double vx;
    private double vy;
    private double vz;
    private boolean grounded;
    private double slip;
    private int ticksSinceRecord;

    public MotionLedger(double gravity) {
        this.gravity = gravity;
        // A fresh player is standing: grounded, at rest, vertical at equilibrium.
        this.vx = 0.0;
        this.vy = 0.0;
        this.vz = 0.0;
        this.grounded = true;
        this.slip = Decay.DEFAULT_SLIPPERINESS;
        this.ticksSinceRecord = 0;
    }

    /**
     * The knock record: the FINAL delivered value replaces all three axes
     * (the legacy halve-then-add already happened in the engine), and
     * {@code grounded} + {@code slip} — the block under the victim's feet at
     * launch — select the decay branch for subsequent {@link #tick(TickStamp)}s.
     */
    public void record(double vx, double vy, double vz, boolean grounded, double slip, TickStamp tick) {
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.grounded = grounded;
        this.slip = slip;
        this.ticksSinceRecord = 0;
    }

    /**
     * The legacy jump bookkeeping: a grounded→airborne transition overwrote the
     * vertical with the (pre-resolved) jump stamp and added the sprint facing
     * push to the horizontals. The carried horizontals take ONE MORE grounded
     * decay first — the liftoff tick's own pre-move ground friction (the era
     * selected friction before the move that lifted the victim), the #1 trap.
     */
    public void recordLiftoff(double jumpVy, double facingPushX, double facingPushZ, TickStamp tick) {
        Decay.Motion carried = current();
        double launchDrag = grounded ? slip * Decay.AIR_DRAG : 1.0;
        this.vx = carried.vx() * launchDrag + facingPushX;
        this.vy = jumpVy;
        this.vz = carried.vz() * launchDrag + facingPushZ;
        this.grounded = false;
        this.slip = Decay.DEFAULT_SLIPPERINESS;
        this.ticksSinceRecord = 0;
    }

    /**
     * An airborne→grounded transition: the simulated move collides, the
     * vertical re-equilibrates to {@link Decay#groundedEquilibrium(double)}
     * (never zero — mandate §4.2), the decayed horizontal is preserved, and
     * decay switches to ground drag from here on (at the era default slip; the
     * landed block's slip arrives with the next knock's {@link #record}).
     */
    public void recordLanding(TickStamp tick) {
        Decay.Motion carried = current();
        this.vx = carried.vx();
        this.vy = Decay.groundedEquilibrium(gravity);
        this.vz = carried.vz();
        this.grounded = true;
        this.slip = Decay.DEFAULT_SLIPPERINESS;
        this.ticksSinceRecord = 0;
    }

    /**
     * The attacker-side self-multiply (mandate §4.3): vanilla's attack ends
     * every bonus-knockback hit with {@code motX *= 0.6; motZ *= 0.6} on the
     * server's copy of the attacker's motion. Horizontals only — the vertical
     * is untouched.
     */
    public void scaleHorizontal(double factor) {
        Decay.Motion carried = current();
        this.vx = carried.vx() * factor;
        this.vy = carried.vy();
        this.vz = carried.vz() * factor;
        this.ticksSinceRecord = 0;
    }

    /** Advance one tick of legacy decay — called once per session tick. */
    public void tick(TickStamp now) {
        if (ticksSinceRecord < DEAD_AFTER_TICKS) {
            ticksSinceRecord++;
        }
    }

    /** The residual as of the last completed tick — what {@code PlayerView} publishes. */
    public Decay.Motion current() {
        if (ticksSinceRecord >= DEAD_AFTER_TICKS) {
            return grounded
                    ? new Decay.Motion(0.0, Decay.groundedEquilibrium(gravity), 0.0)
                    : Decay.Motion.ZERO;
        }
        return Decay.decay(vx, vy, vz, ticksSinceRecord, grounded, slip, gravity);
    }

    /** The machine's own grounded view — the last recorded segment's state. */
    public boolean groundedView() {
        return grounded;
    }
}

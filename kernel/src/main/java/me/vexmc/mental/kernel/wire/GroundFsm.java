package me.vexmc.mental.kernel.wire;

import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.model.LedgerEvent;
import me.vexmc.mental.kernel.port.TickClock;

/**
 * The client-movement FSM, owned by one connection thread (spec §2, D1). A
 * pure state machine: inputs are packet-shaped values plus the victim's
 * published {@link ViewSlice} (jump boost, sprint, yaw, gravity); outputs are
 * {@link LedgerEvent}s stamped with the current tick. It never touches a live
 * entity — the netty-realm safety the architecture certifies by type.
 *
 * <p>The era jump bookkeeping (verified against the old GroundTransitionWatcher
 * source): a grounded→airborne transition that is rising is a jump —
 * {@code motY = 0.42 + 0.1×(JumpBoost amplifier + 1)} plus a 0.2 facing push
 * for sprinting players; a non-rising step off a ledge free-falls from the
 * grounded equilibrium; an airborne→grounded transition is a landing. Liftoff
 * needs a position to judge rising, so a flag-only liftoff is deferred; a
 * teleport-scale position jump is a resync, not a transition.</p>
 */
public final class GroundFsm {

    /** A position jump this large is a teleport (or resync), never a movement transition. */
    private static final double TELEPORT_RESET_DISTANCE = 8.0;

    private final TickClock clock;

    private boolean seen;
    private boolean lastOnGround;
    private double lastY;

    public GroundFsm(TickClock clock) {
        this.clock = clock;
    }

    /**
     * The slice of {@code PlayerView} the FSM needs. {@code jumpImpulse} is the
     * base impulse (0.42 by default); {@code jumpBoostAmplifier} is the 0-based
     * potion amplifier, or a negative sentinel when no Jump Boost is active
     * (Jump Boost I is amplifier 0 and must add 0.1, so absence cannot be 0).
     */
    public record ViewSlice(double jumpImpulse, int jumpBoostAmplifier,
                            boolean sprinting, float yawDegrees, double gravity) {}

    /** @return the event to enqueue, or null when the packet caused no transition. */
    public LedgerEvent onMovement(boolean onGround, boolean hasPosition, double y, ViewSlice view) {
        if (!seen) {
            seen = true;
            lastOnGround = onGround;
            if (hasPosition) {
                lastY = y;
            }
            return null;
        }
        double knownY = hasPosition ? y : lastY;
        if (hasPosition && Math.abs(knownY - lastY) > TELEPORT_RESET_DISTANCE) {
            // A teleport-scale jump: resync the baseline, judge nothing.
            lastOnGround = onGround;
            lastY = knownY;
            return null;
        }
        if (lastOnGround == onGround) {
            // No grounded change (includes rotation-only packets): just track y.
            lastY = knownY;
            return null;
        }
        if (onGround) {
            lastOnGround = true;
            lastY = knownY;
            return new LedgerEvent.Landing(clock.current());
        }
        // grounded -> airborne: needs a position to judge rising.
        if (!hasPosition) {
            return null; // defer the liftoff to the next position-bearing packet
        }
        boolean rising = knownY > lastY;
        lastOnGround = false;
        lastY = knownY;
        double jumpVy;
        double pushX = 0.0;
        double pushZ = 0.0;
        if (rising) {
            double boost = view.jumpBoostAmplifier() >= 0
                    ? 0.1 * (view.jumpBoostAmplifier() + 1)
                    : 0.0;
            jumpVy = view.jumpImpulse() + boost;
            if (view.sprinting()) {
                double radians = Math.toRadians(view.yawDegrees());
                pushX = -Math.sin(radians) * Decay.SPRINT_JUMP_PUSH;
                pushZ = Math.cos(radians) * Decay.SPRINT_JUMP_PUSH;
            }
        } else {
            // A step off a ledge: no jump stamp, no push — free-fall from equilibrium.
            jumpVy = Decay.groundedEquilibrium(view.gravity());
        }
        return new LedgerEvent.Liftoff(jumpVy, pushX, pushZ, clock.current());
    }

    /** The teleport ack seam: emit a reset and forget the last state. */
    public LedgerEvent onTeleport() {
        seen = false;
        return new LedgerEvent.Reset(clock.current());
    }
}

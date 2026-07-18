package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import me.vexmc.mental.kernel.model.LedgerEvent;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.wire.GroundFsm.ViewSlice;
import org.junit.jupiter.api.Test;

/**
 * Pins the client-movement FSM against the era jump bookkeeping (verified
 * against the old GroundTransitionWatcher source; there was no unit test to
 * port, so these are hand-derived from that source and the compendium). The
 * FSM is pure state: packet-shaped inputs plus a {@link ViewSlice} of the
 * victim's published view produce {@link LedgerEvent}s, never touching a live
 * entity.
 */
class GroundFsmTest {

    private static final double GRAVITY = 0.08;
    private static final double EPSILON = 1.0e-9;

    /** A mutable test clock — the harness's settable clock in miniature. */
    private static final class Clock implements TickClock {
        int tick;

        @Override
        public TickStamp current() {
            return new TickStamp(tick);
        }
    }

    /** ViewSlice with a sprinting/yaw override; jumpBoostAmplifier −1 = no boost. */
    private static ViewSlice view(boolean sprinting, float yaw, int jumpBoostAmplifier) {
        return new ViewSlice(0.42, jumpBoostAmplifier, sprinting, yaw, GRAVITY);
    }

    @Test
    void risingLiftoffStampsTheJumpImpulseWithBoostAndSprintPushAtYaw45() {
        Clock clock = new Clock();
        clock.tick = 7;
        GroundFsm fsm = new GroundFsm(clock);

        // Seed grounded (first packet just establishes the baseline).
        assertNull(fsm.onMovement(true, true, 64.0, view(false, 45.0f, -1)));

        // Sprinting, Jump Boost I (amplifier 0), rising, yaw 45°.
        LedgerEvent event = fsm.onMovement(false, true, 64.42, view(true, 45.0f, 0));
        LedgerEvent.Liftoff liftoff = assertInstanceOf(LedgerEvent.Liftoff.class, event);

        // 0.42 + 0.1 × (0 + 1) = 0.52 (Jump Boost I raises the stamp).
        assertEquals(0.52, liftoff.jumpVy(), EPSILON);
        // Sprint facing push 0.2 along yaw 45°: pushX = −sin, pushZ = cos.
        double radians = Math.toRadians(45.0);
        assertEquals(-Math.sin(radians) * 0.2, liftoff.pushX(), EPSILON);
        assertEquals(Math.cos(radians) * 0.2, liftoff.pushZ(), EPSILON);
        assertEquals(new TickStamp(7), liftoff.tick());
    }

    @Test
    void liftoffWithoutBoostOrSprintCarriesNoPushAndBareImpulse() {
        Clock clock = new Clock();
        GroundFsm fsm = new GroundFsm(clock);
        fsm.onMovement(true, true, 64.0, view(false, 0.0f, -1));

        LedgerEvent.Liftoff liftoff = assertInstanceOf(LedgerEvent.Liftoff.class,
                fsm.onMovement(false, true, 64.42, view(false, 0.0f, -1)));
        assertEquals(0.42, liftoff.jumpVy(), EPSILON); // no boost: bare impulse
        assertEquals(0.0, liftoff.pushX(), EPSILON);
        assertEquals(0.0, liftoff.pushZ(), EPSILON);
    }

    @Test
    void nonRisingLiftoffFreeFallsFromEquilibriumWithNoJumpStamp() {
        // Walking off a ledge: grounded→airborne but not rising. The era server
        // stamped no jump; the vertical free-falls from the grounded
        // equilibrium (never a 0.42 jump), and no facing push is added.
        Clock clock = new Clock();
        GroundFsm fsm = new GroundFsm(clock);
        fsm.onMovement(true, true, 64.0, view(true, 0.0f, 0));

        LedgerEvent.Liftoff liftoff = assertInstanceOf(LedgerEvent.Liftoff.class,
                fsm.onMovement(false, true, 63.9, view(true, 0.0f, 0))); // y fell
        assertEquals(me.vexmc.mental.kernel.math.Decay.groundedEquilibrium(GRAVITY),
                liftoff.jumpVy(), EPSILON);
        assertEquals(0.0, liftoff.pushX(), EPSILON);
        assertEquals(0.0, liftoff.pushZ(), EPSILON);
    }

    @Test
    void airborneToGroundedEmitsLandingAndRotationOnlyNeverTransitions() {
        Clock clock = new Clock();
        clock.tick = 3;
        GroundFsm fsm = new GroundFsm(clock);

        fsm.onMovement(true, true, 64.0, view(false, 0.0f, -1));           // seed grounded
        fsm.onMovement(false, true, 64.42, view(false, 0.0f, -1));         // liftoff -> airborne

        // A rotation-only packet (no position) never transitions, even mid-flight.
        assertNull(fsm.onMovement(false, false, 0.0, view(false, 90.0f, -1)));

        // Airborne -> grounded is a landing.
        clock.tick = 8;
        LedgerEvent event = fsm.onMovement(true, true, 64.0, view(false, 0.0f, -1));
        LedgerEvent.Landing landing = assertInstanceOf(LedgerEvent.Landing.class, event);
        assertEquals(new TickStamp(8), landing.tick());

        // Rotation-only while grounded also never transitions.
        assertNull(fsm.onMovement(true, false, 0.0, view(false, 45.0f, -1)));
    }

    @Test
    void teleportEmitsResetAndForgetsStateSoNoPhantomTransition() {
        Clock clock = new Clock();
        clock.tick = 2;
        GroundFsm fsm = new GroundFsm(clock);

        fsm.onMovement(true, true, 64.0, view(false, 0.0f, -1));   // grounded
        fsm.onMovement(false, true, 64.42, view(false, 0.0f, -1)); // airborne now

        LedgerEvent.Reset reset = assertInstanceOf(LedgerEvent.Reset.class, fsm.onTeleport());
        assertEquals(new TickStamp(2), reset.tick());

        // The FSM forgot it was airborne: the first packet after the ack seam
        // re-seeds (no phantom Landing across the teleport).
        assertNull(fsm.onMovement(true, true, 100.0, view(false, 0.0f, -1)));
    }

    @Test
    void aTeleportScaleJumpInAMovementPacketResyncsWithoutAnEvent() {
        // A position delta beyond the teleport threshold is a resync, not a
        // jump — the old watcher's TELEPORT_RESET_DISTANCE guard.
        Clock clock = new Clock();
        GroundFsm fsm = new GroundFsm(clock);
        fsm.onMovement(true, true, 64.0, view(false, 0.0f, -1));
        assertNull(fsm.onMovement(false, true, 200.0, view(false, 0.0f, -1)));
    }
}

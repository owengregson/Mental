package me.vexmc.mental.v5;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.kernel.delivery.ValvePayload;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.TickStamp;
import org.junit.jupiter.api.Test;

/**
 * Pins the velocity valve — the PacketEvents-independent dedup keyed on the
 * exact wire encoding (motion×8000 shorts, integer equality, no epsilon). It
 * replaces VelocityDuplicateSuppressor; consume-once, mismatch-false, and the
 * age-aware session-tick clearStale sweep (F3): an arm is dropped only past the
 * two-tick legal-dup grace, so a task-phase arm survives to its own end-of-tick
 * tracker dup.
 */
class VelocityValveTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final int ENTITY = 7;
    private static final ValvePayload ARMED = ValvePayload.of(ENTITY, new KnockbackVector(0.4, 0.3608, 0.0));

    @Test
    void armedPayloadIsConsumedExactlyOnce() {
        VelocityValve valve = new VelocityValve();
        valve.arm(PLAYER, ARMED, new TickStamp(10));
        assertTrue(valve.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));
        // Consume-once: the second identical send is a genuine foreign velocity.
        assertFalse(valve.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));
    }

    @Test
    void aMismatchNeverConsumes() {
        VelocityValve valve = new VelocityValve();
        valve.arm(PLAYER, ARMED, new TickStamp(10));
        assertFalse(valve.consume(PLAYER, ENTITY, ARMED.qx(), (short) (ARMED.qy() + 1), ARMED.qz()),
                "a different quantized y is a different send");
        assertFalse(valve.consume(PLAYER, ENTITY + 1, ARMED.qx(), ARMED.qy(), ARMED.qz()),
                "a different entity id is a different send");
        // The armed payload survived both mismatches — the real send still consumes.
        assertTrue(valve.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));
    }

    @Test
    void consumeWithNothingArmedIsFalse() {
        VelocityValve valve = new VelocityValve();
        assertFalse(valve.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));
    }

    @Test
    void clearStaleKeepsASameTickArm() {
        // The blocked-knock scenario: a task-phase arm made this tick survives its own
        // session-tick sweep to the end-of-tick dup (10 − 10 = 0 < 2).
        VelocityValve valve = new VelocityValve();
        valve.arm(PLAYER, ARMED, new TickStamp(10));
        valve.clearStale(PLAYER, new TickStamp(10));
        assertTrue(valve.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));
    }

    @Test
    void clearStaleKeepsAOneTickOldArm() {
        // Event-loop backlog / Folia ±1 skew (11 − 10 = 1 < 2).
        VelocityValve valve = new VelocityValve();
        valve.arm(PLAYER, ARMED, new TickStamp(10));
        valve.clearStale(PLAYER, new TickStamp(11));
        assertTrue(valve.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));
    }

    @Test
    void clearStaleDropsATwoTickOldArm() {
        // The stale-arm expiry (12 − 10 = 2 >= 2 — provably past any legal dup).
        VelocityValve valve = new VelocityValve();
        valve.arm(PLAYER, ARMED, new TickStamp(10));
        valve.clearStale(PLAYER, new TickStamp(12));
        assertFalse(valve.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));
    }

    @Test
    void clearStaleDropsAnUnknownStampArm() {
        // A dead clock degrades to the old unconditional sweep behavior (either side unknown).
        VelocityValve unknownArm = new VelocityValve();
        unknownArm.arm(PLAYER, ARMED, TickStamp.NO_TICK);
        unknownArm.clearStale(PLAYER, new TickStamp(12));
        assertFalse(unknownArm.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));

        VelocityValve unknownNow = new VelocityValve();
        unknownNow.arm(PLAYER, ARMED, new TickStamp(10));
        unknownNow.clearStale(PLAYER, TickStamp.NO_TICK);
        assertFalse(unknownNow.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));
    }
}

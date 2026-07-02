package me.vexmc.mental.v5;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.kernel.delivery.ValvePayload;
import me.vexmc.mental.kernel.model.KnockbackVector;
import org.junit.jupiter.api.Test;

/**
 * Pins the velocity valve — the PacketEvents-independent dedup keyed on the
 * exact wire encoding (motion×8000 shorts, integer equality, no epsilon). It
 * replaces VelocityDuplicateSuppressor; consume-once, mismatch-false, and the
 * session-tick clearStale sweep.
 */
class VelocityValveTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final int ENTITY = 7;
    private static final ValvePayload ARMED = ValvePayload.of(ENTITY, new KnockbackVector(0.4, 0.3608, 0.0));

    @Test
    void armedPayloadIsConsumedExactlyOnce() {
        VelocityValve valve = new VelocityValve();
        valve.arm(PLAYER, ARMED);
        assertTrue(valve.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));
        // Consume-once: the second identical send is a genuine foreign velocity.
        assertFalse(valve.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));
    }

    @Test
    void aMismatchNeverConsumes() {
        VelocityValve valve = new VelocityValve();
        valve.arm(PLAYER, ARMED);
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
    void clearStaleDropsAnUnconsumedPayload() {
        VelocityValve valve = new VelocityValve();
        valve.arm(PLAYER, ARMED);
        valve.clearStale(PLAYER); // the session-tick sweep
        assertFalse(valve.consume(PLAYER, ENTITY, ARMED.qx(), ARMED.qy(), ARMED.qz()));
    }
}

package me.vexmc.mental.v5.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.kernel.delivery.Directive;
import me.vexmc.mental.kernel.delivery.ValvePayload;
import me.vexmc.mental.kernel.model.KnockbackVector;
import org.junit.jupiter.api.Test;

/**
 * The DeskRouter's decision mapping (the pure {@link DirectiveExecutor}) against a
 * stubbed velocity sink: SHIP sets the velocity and returns no arm intent,
 * SHIP_AND_ARM_VALVE sets it and RETURNS the exact arm intent (the caller confirms
 * and arms at MONITOR), CANCEL_EVENT cancels, PASS_THROUGH leaves the event alone.
 * The executor no longer touches the valve — arming moved to the MONITOR confirm.
 */
class DirectiveExecutorTest {

    private static final int ENTITY_ID = 42;
    private static final KnockbackVector VECTOR = new KnockbackVector(0.9, 0.4608, -0.3);

    private static final class RecordingSink implements VelocitySink {
        KnockbackVector shipped;
        boolean cancelled;
        @Override public void ship(KnockbackVector velocity) { this.shipped = velocity; }
        @Override public void cancel() { this.cancelled = true; }
    }

    @Test
    void shipSetsTheVelocityAndReturnsNoArm() {
        RecordingSink sink = new RecordingSink();
        ValvePayload returned = DirectiveExecutor.apply(
                new Directive(Directive.Action.SHIP, VECTOR, null), sink);

        assertSame(VECTOR, sink.shipped);
        assertFalse(sink.cancelled);
        assertNull(returned, "SHIP returns no arm intent");
    }

    @Test
    void shipAndArmValveReturnsTheExactPayload() {
        RecordingSink sink = new RecordingSink();
        ValvePayload payload = ValvePayload.of(ENTITY_ID, VECTOR);

        ValvePayload returned = DirectiveExecutor.apply(
                new Directive(Directive.Action.SHIP_AND_ARM_VALVE, VECTOR, payload), sink);

        assertSame(VECTOR, sink.shipped);
        assertSame(payload, returned, "the caller confirms this exact intent at MONITOR");
    }

    @Test
    void cancelEventCancels() {
        RecordingSink sink = new RecordingSink();
        ValvePayload returned = DirectiveExecutor.apply(
                new Directive(Directive.Action.CANCEL_EVENT, null, null), sink);

        assertTrue(sink.cancelled);
        assertNull(sink.shipped);
        assertNull(returned);
    }

    @Test
    void passThroughLeavesTheEventAlone() {
        RecordingSink sink = new RecordingSink();
        ValvePayload returned = DirectiveExecutor.apply(
                new Directive(Directive.Action.PASS_THROUGH, null, null), sink);

        assertNull(sink.shipped);
        assertFalse(sink.cancelled);
        assertNull(returned);
    }
}

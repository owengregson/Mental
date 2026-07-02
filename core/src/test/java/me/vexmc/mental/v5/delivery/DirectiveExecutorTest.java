package me.vexmc.mental.v5.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.kernel.delivery.Directive;
import me.vexmc.mental.kernel.delivery.ValvePayload;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.v5.VelocityValve;
import org.junit.jupiter.api.Test;

/**
 * The DeskRouter's decision mapping (the pure {@link DirectiveExecutor}) against a
 * stubbed velocity sink: SHIP sets the velocity, SHIP_AND_ARM_VALVE also arms the
 * valve, CANCEL_EVENT cancels, PASS_THROUGH leaves the event alone.
 */
class DirectiveExecutorTest {

    private static final UUID VICTIM = UUID.randomUUID();
    private static final int ENTITY_ID = 42;
    private static final KnockbackVector VECTOR = new KnockbackVector(0.9, 0.4608, -0.3);

    private static final class RecordingSink implements VelocitySink {
        KnockbackVector shipped;
        boolean cancelled;
        @Override public void ship(KnockbackVector velocity) { this.shipped = velocity; }
        @Override public void cancel() { this.cancelled = true; }
    }

    @Test
    void shipSetsTheVelocityAndArmsNoValve() {
        RecordingSink sink = new RecordingSink();
        VelocityValve valve = new VelocityValve();

        DirectiveExecutor.apply(
                new Directive(Directive.Action.SHIP, VECTOR, null), sink, VICTIM, valve);

        assertSame(VECTOR, sink.shipped);
        assertFalse(sink.cancelled);
        ValvePayload payload = ValvePayload.of(ENTITY_ID, VECTOR);
        assertFalse(valve.consume(VICTIM, payload.entityId(), payload.qx(), payload.qy(), payload.qz()),
                "SHIP must not arm the valve");
    }

    @Test
    void shipAndArmValveSetsTheVelocityAndArmsTheExactPayload() {
        RecordingSink sink = new RecordingSink();
        VelocityValve valve = new VelocityValve();
        ValvePayload payload = ValvePayload.of(ENTITY_ID, VECTOR);

        DirectiveExecutor.apply(
                new Directive(Directive.Action.SHIP_AND_ARM_VALVE, VECTOR, payload), sink, VICTIM, valve);

        assertSame(VECTOR, sink.shipped);
        assertTrue(valve.consume(VICTIM, payload.entityId(), payload.qx(), payload.qy(), payload.qz()),
                "the exact wire encoding is armed");
    }

    @Test
    void cancelEventCancels() {
        RecordingSink sink = new RecordingSink();
        DirectiveExecutor.apply(
                new Directive(Directive.Action.CANCEL_EVENT, null, null), sink, VICTIM, new VelocityValve());

        assertTrue(sink.cancelled);
        assertNull(sink.shipped);
    }

    @Test
    void passThroughLeavesTheEventAlone() {
        RecordingSink sink = new RecordingSink();
        DirectiveExecutor.apply(
                new Directive(Directive.Action.PASS_THROUGH, null, null), sink, VICTIM, new VelocityValve());

        assertNull(sink.shipped);
        assertFalse(sink.cancelled);
    }
}

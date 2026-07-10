package me.vexmc.mental.v5.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.kernel.delivery.ValvePayload;
import me.vexmc.mental.kernel.model.KnockbackVector;
import org.junit.jupiter.api.Test;

/**
 * The pure MONITOR arm-confirm predicate ({@link DeskRouter#confirmsArm}, F3): the
 * valve is armed only when the velocity event SURVIVED (not cancelled) and its
 * final velocity still quantizes to the pre-sent wire encoding — because vanilla
 * emits the matching ENTITY_VELOCITY duplicate only for a surviving event. Arming
 * at HIGH left a dead arm behind any HIGHEST/MONITOR foreign cancel/modify, and a
 * dead arm aliases the next byte-identical hit's duplicate.
 */
class DeskRouterConfirmTest {

    /** shorts 3200 / 2886 / 0. */
    private static final ValvePayload PLANNED =
            ValvePayload.of(7, new KnockbackVector(0.4, 0.3608, 0.0));

    @Test
    void aCancelledEventNeverConfirms() {
        // Foreign cancel ⇒ no arm ⇒ the next hit's dup meets an empty slot (the leg-a
        // cascade's root is gone; the burst side is structurally immune via silent writes).
        assertFalse(DeskRouter.confirmsArm(true, 0.4, 0.3608, 0.0, PLANNED));
    }

    @Test
    void anUntouchedVelocityConfirms() {
        assertTrue(DeskRouter.confirmsArm(false, 0.4, 0.3608, 0.0, PLANNED));
    }

    @Test
    void aMonitorModifiedVelocityRefusesTheArm() {
        // q(0.5) = 4000 ≠ 3200 — the modified dup must ship as the correction, not be eaten.
        assertFalse(DeskRouter.confirmsArm(false, 0.5, 0.3608, 0.0, PLANNED));
    }

    @Test
    void aSubQuantumTouchStillConfirms() {
        // trunc(0.400001 × 8000) = trunc(3200.008) = 3200 — a byte-identical dup must still be eaten.
        assertTrue(DeskRouter.confirmsArm(false, 0.400001, 0.3608, 0.0, PLANNED));
    }
}

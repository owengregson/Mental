package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SprintTrackerTest {

    @Test
    void freshnessArmsPeeksWithoutSpendingAndConsumesOnce() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();

        assertFalse(tracker.peekFresh(attacker));
        assertFalse(tracker.consumeFresh(attacker));

        tracker.arm(attacker);
        // The netty pre-send peeks — repeatedly, without spending.
        assertTrue(tracker.peekFresh(attacker));
        assertTrue(tracker.peekFresh(attacker));

        // The authoritative compute spends it exactly once.
        assertTrue(tracker.consumeFresh(attacker));
        assertFalse(tracker.peekFresh(attacker));
        assertFalse(tracker.consumeFresh(attacker));

        // Re-engaging sprint re-arms — the w-tap cycle.
        tracker.arm(attacker);
        assertTrue(tracker.consumeFresh(attacker));
    }

    @Test
    void clearDropsEveryAttacker() {
        SprintTracker tracker = new SprintTracker();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        tracker.arm(first);
        tracker.arm(second);

        tracker.clear();

        assertFalse(tracker.peekFresh(first));
        assertFalse(tracker.peekFresh(second));
    }
}

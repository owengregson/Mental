package me.vexmc.mental.v5.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.api.event.KnockbackApplyEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * Pins the §8 {@code KnockbackApplyEvent} outcome machine — one last-writer-wins
 * ordering across {@code velocity()}, {@code suppress()}, and
 * {@code setCancelled()}. The event has no runtime null checks, so these
 * construct with null parties (no server needed — {@code getHandlers()} is never
 * called).
 */
class KnockbackApplyOutcomeTest {

    @Test
    void freshEventShips() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0), null,
                KnockbackApplyEvent.Source.MELEE);
        assertEquals(KnockbackApplyEvent.Outcome.SHIP, event.getOutcome());
        assertFalse(event.isCancelled());
    }

    @Test
    void suppressThenVelocityRestoresShip() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0), null,
                KnockbackApplyEvent.Source.MELEE);
        event.suppress();
        assertEquals(KnockbackApplyEvent.Outcome.SUPPRESSED, event.getOutcome());
        event.velocity(new Vector(0, 0, 2));
        assertEquals(KnockbackApplyEvent.Outcome.SHIP, event.getOutcome());
        assertEquals(2.0, event.velocity().getZ());
    }

    @Test
    void cancelYieldsAndVelocityClearsIt() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0), null,
                KnockbackApplyEvent.Source.MELEE);
        event.setCancelled(true);
        assertEquals(KnockbackApplyEvent.Outcome.YIELDED, event.getOutcome());
        assertTrue(event.isCancelled());
        event.velocity(new Vector(3, 0, 0));
        assertEquals(KnockbackApplyEvent.Outcome.SHIP, event.getOutcome());
        assertFalse(event.isCancelled());
    }

    @Test
    void uncancelRestoresShipWithLastWrittenVector() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0), null,
                KnockbackApplyEvent.Source.MELEE);
        event.velocity(new Vector(5, 0, 0));
        event.setCancelled(true);
        event.setCancelled(false);
        assertEquals(KnockbackApplyEvent.Outcome.SHIP, event.getOutcome());
        assertEquals(5.0, event.velocity().getX());
    }

    @Test
    void suppressAfterCancelWinsAndClearsCancel() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0), null,
                KnockbackApplyEvent.Source.MELEE);
        event.setCancelled(true);
        event.suppress();
        assertEquals(KnockbackApplyEvent.Outcome.SUPPRESSED, event.getOutcome());
        assertFalse(event.isCancelled());
    }

    @Test
    void deprecatedConstructorDerivesDefaults() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0));
        assertNull(event.getAttackerId());
        assertEquals(KnockbackApplyEvent.Source.OTHER, event.getSource());
    }
}

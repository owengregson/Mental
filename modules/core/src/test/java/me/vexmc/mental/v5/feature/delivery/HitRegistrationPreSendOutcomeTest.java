package me.vexmc.mental.v5.feature.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.Directive;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.v5.rim.BurstSender.Outcome;
import org.junit.jupiter.api.Test;

/**
 * The F2 pre-send outcome contract at the {@link HitRegistrationUnit#commitPreSendState}
 * seam: the transaction state commits off the burst's ACTUAL ship {@link Outcome}, so an
 * UNSENDABLE burst (a user-null race, a mid-ship throw) downgrades to a wire-failed PIN
 * that ships once via the genuine velocity event and never arms a valve — a phantom
 * PRE_SENT would have let the valve eat the victim's only authoritative ENTITY_VELOCITY.
 * Pure over the transaction, so the seam needs no PacketEvents scaffolding.
 */
class HitRegistrationPreSendOutcomeTest {

    // horizontal 0.9 = era base 0.4 + sprint extra 0.5; vertical 0.4608 =
    // grounded-equilibrium vy −0.0784 × vertical friction 0.5 + base 0.4 + bonus 0.1.
    private static final KnockbackVector VECTOR = new KnockbackVector(0.9, 0.4608, 0.0);

    private static HitContext context() {
        return new HitContext(
                new HitId(1L), new HitSource.Melee(),
                UUID.randomUUID(), UUID.randomUUID(),
                new SprintVerdict(true, Boolean.TRUE, new TickStamp(5)),
                true, null, new TickStamp(5));
    }

    private static HitTransaction fresh() {
        return new HitTransaction(context());
    }

    /** A dead-simple fixed clock — the desk stamps its journal at tick 0. */
    private static final class FixedClock implements TickClock {
        @Override
        public TickStamp current() {
            return new TickStamp(0);
        }
    }

    @Test
    void deliveredOutcomeCommitsPreSent() {
        HitTransaction tx = fresh();
        HitRegistrationUnit.commitPreSendState(tx, VECTOR, true, true, Outcome.DELIVERED);
        assertEquals(HitTransaction.State.PRE_SENT, tx.state());
        assertTrue(tx.wireCarried());
        assertEquals(VECTOR, tx.carried());
        assertNull(tx.deliveryNote());
    }

    @Test
    void unsendableOutcomeDowngradesToPinnedWireFailedAndNeverArmsAValve() {
        HitTransaction tx = fresh();
        HitRegistrationUnit.commitPreSendState(tx, VECTOR, true, true, Outcome.UNSENDABLE);
        assertEquals(HitTransaction.State.PINNED, tx.state());
        assertTrue(!tx.wireCarried());
        assertEquals("wire-failed", tx.deliveryNote());

        // Feed it through a real desk: the velocity event ships the carried era
        // vector and NO valve ever arms (the authoritative ENTITY_VELOCITY is the
        // victim's only copy).
        DeliveryDesk desk = new DeliveryDesk(42, new FixedClock(), 4);
        desk.submitFromWire(tx);
        desk.awaitVelocityEvent(tx);
        Directive directive = desk.resolve(VECTOR.x(), VECTOR.y(), VECTOR.z());
        assertEquals(Directive.Action.SHIP, directive.action());
        assertNull(directive.arm(), "no valve ever armed");
        assertEquals(VECTOR, directive.ship());

        JournalEntry entry = desk.journal().get(0);
        assertEquals("wire-failed", entry.suppressReason());
        assertTrue(!entry.wireCarried());
    }

    @Test
    void nullOutcomeOnAWiredVictimIsAlsoWireFailed() {
        // Defensive — unreachable in plan() but the pure seam must never phantom-PRE_SENT.
        HitTransaction tx = fresh();
        HitRegistrationUnit.commitPreSendState(tx, VECTOR, true, true, null);
        assertEquals(HitTransaction.State.PINNED, tx.state());
        assertEquals("wire-failed", tx.deliveryNote());
    }

    @Test
    void noWireCommitsAPlainPin() {
        HitTransaction tx = fresh();
        HitRegistrationUnit.commitPreSendState(tx, VECTOR, true, false, null);
        assertEquals(HitTransaction.State.PINNED, tx.state());
        assertNull(tx.deliveryNote(), "byte-identical to the pre-fix connectionless path");
    }

    @Test
    void hurtOnlyPlanCommitsNothing() {
        HitTransaction tx = fresh();
        HitRegistrationUnit.commitPreSendState(tx, null, false, true, Outcome.DELIVERED);
        assertEquals(HitTransaction.State.REGISTERED, tx.state());
    }
}

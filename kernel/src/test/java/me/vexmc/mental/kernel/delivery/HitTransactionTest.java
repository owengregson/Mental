package me.vexmc.mental.kernel.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.kernel.delivery.HitTransaction.State;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import org.junit.jupiter.api.Test;

/**
 * Pins the hit transaction state machine (spec §3.2): every legal transition
 * succeeds once, every illegal one throws an {@link IllegalStateException}
 * naming both states, and PINNED can never be flagged wire-carried (so it can
 * never arm a valve — B4's conflation is unrepresentable).
 */
class HitTransactionTest {

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

    @Test
    void constructedTransactionIsRegisteredAndNotTerminal() {
        HitTransaction tx = fresh();
        assertEquals(State.REGISTERED, tx.state());
        assertFalse(tx.terminal());
        assertNull(tx.carried(), "no vector carried before pre-send/pin");
        assertFalse(tx.wireCarried());
    }

    @Test
    void preSentPathReachesRecorded() {
        HitTransaction tx = fresh();
        tx.planned();
        assertEquals(State.PLANNED, tx.state());
        tx.preSent(VECTOR);
        assertEquals(State.PRE_SENT, tx.state());
        assertEquals(VECTOR, tx.carried());
        assertTrue(tx.wireCarried(), "the wire carried the burst");
        tx.adopted();
        assertEquals(State.ADOPTED, tx.state());
        assertFalse(tx.terminal(), "resolved but not yet journaled");
        tx.recorded();
        assertEquals(State.RECORDED, tx.state());
        assertTrue(tx.terminal());
    }

    @Test
    void pinnedPathCarriesTheVectorButIsNeverWireCarried() {
        HitTransaction tx = fresh();
        tx.planned();
        tx.pinned(VECTOR);
        assertEquals(State.PINNED, tx.state());
        assertEquals(VECTOR, tx.carried());
        assertFalse(tx.wireCarried(), "PINNED never arms a valve");
        tx.adopted();
        tx.recorded();
        assertTrue(tx.terminal());
    }

    @Test
    void suppressedRetractedDroppedEnsuredAllResolveToRecorded() {
        HitTransaction suppressed = fresh();
        suppressed.suppressed("resistance-roll");
        assertEquals(State.SUPPRESSED, suppressed.state());
        suppressed.recorded();
        assertEquals(State.RECORDED, suppressed.state());

        HitTransaction retracted = fresh();
        retracted.planned();
        retracted.preSent(VECTOR);
        retracted.retracted();
        assertEquals(State.RETRACTED, retracted.state());
        retracted.recorded();
        assertTrue(retracted.terminal());

        HitTransaction dropped = fresh();
        dropped.planned();
        dropped.preSent(VECTOR);
        dropped.dropped("no-velocity-event");
        assertEquals(State.DROPPED, dropped.state());
        dropped.recorded();
        assertTrue(dropped.terminal());

        HitTransaction ensured = fresh();
        ensured.planned();
        ensured.preSent(VECTOR);
        ensured.ensured();
        assertEquals(State.ENSURED, ensured.state());
        ensured.recorded();
        assertTrue(ensured.terminal());
    }

    @Test
    void adoptedMayShortCircuitFromRegisteredOrPlanned() {
        HitTransaction fromRegistered = fresh();
        fromRegistered.adopted();
        assertEquals(State.ADOPTED, fromRegistered.state());

        HitTransaction fromPlanned = fresh();
        fromPlanned.planned();
        fromPlanned.adopted();
        assertEquals(State.ADOPTED, fromPlanned.state());
    }

    @Test
    void preSentAfterPinnedIsIllegalAndNamesBothStates() {
        HitTransaction tx = fresh();
        tx.planned();
        tx.pinned(VECTOR);
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> tx.preSent(VECTOR));
        assertTrue(error.getMessage().contains("PINNED"), "names the current state");
        assertTrue(error.getMessage().contains("PRE_SENT"), "names the target state");
    }

    @Test
    void recordedFromRegisteredIsIllegal() {
        HitTransaction tx = fresh();
        IllegalStateException error =
                assertThrows(IllegalStateException.class, tx::recorded);
        assertTrue(error.getMessage().contains("REGISTERED"));
        assertTrue(error.getMessage().contains("RECORDED"));
    }

    @Test
    void doubleAdoptedIsIllegal() {
        HitTransaction tx = fresh();
        tx.adopted();
        assertThrows(IllegalStateException.class, tx::adopted);
    }

    @Test
    void noTransitionEscapesRecorded() {
        HitTransaction tx = fresh();
        tx.adopted();
        tx.recorded();
        assertThrows(IllegalStateException.class, tx::planned);
        assertThrows(IllegalStateException.class, () -> tx.preSent(VECTOR));
        assertThrows(IllegalStateException.class, () -> tx.pinned(VECTOR));
        assertThrows(IllegalStateException.class, tx::adopted);
        assertThrows(IllegalStateException.class, () -> tx.suppressed("x"));
        assertThrows(IllegalStateException.class, tx::retracted);
        assertThrows(IllegalStateException.class, () -> tx.dropped("x"));
        assertThrows(IllegalStateException.class, tx::ensured);
        assertThrows(IllegalStateException.class, tx::recorded);
    }

    @Test
    void preSentRequiresPlannedFirst() {
        HitTransaction tx = fresh();
        assertThrows(IllegalStateException.class, () -> tx.preSent(VECTOR));
    }
}

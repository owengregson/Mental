package me.vexmc.mental.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KinematicState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.LedgerEvent;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import org.junit.jupiter.api.Test;

/**
 * Pins the D2 session scaffold's tick step: drain the inbox (events applied
 * BEFORE the decay), tick the ledger, publish the view (AFTER), sweep the desk.
 * The scripted sequence reproduces interleaving scenario 1's view timing — a
 * queued event never changes the published view until the next tick step.
 */
class CombatSessionTest {

    private static final double GRAVITY = 0.08;
    private static final double EPSILON = 1.0e-9;

    private static PlayerView freshView(int at) {
        return new PlayerView(
                UUID.randomUUID(), 1, new TickStamp(at), Decay.Motion.ZERO, true, 0.6, GRAVITY,
                0.42, -1, false, false, true, 0, 20, 0.0, false, KnockbackProfile.LEGACY_17, 0,
                new KinematicState(0.0, 0.0, true));
    }

    @Test
    void tickStepAppliesEventsBeforeDecayAndPublishesAfter() {
        AtomicInteger serverTick = new AtomicInteger(0);
        CombatSession session = new CombatSession(GRAVITY, 1, new PaperTickClock(serverTick::get), 8);

        // A queued liftoff is applied (vy base 0.42) then decayed once by the tick.
        session.enqueue(new LedgerEvent.Liftoff(0.42, 0.0, 0.0, new TickStamp(0)));
        serverTick.set(1);
        PlayerView view1 = freshView(1);
        session.tickStep(view1);

        assertEquals((0.42 - GRAVITY) * 0.98, session.ledger().current().vy(), EPSILON);
        assertSame(view1, session.view(), "the view is published after the ledger update");

        // Scenario 1 timing: a queued landing does NOT change the published view
        // until the next tick step drains it.
        session.enqueue(new LedgerEvent.Landing(new TickStamp(2)));
        assertSame(view1, session.view(), "a queued event alone never re-publishes");

        serverTick.set(2);
        PlayerView view2 = freshView(2);
        session.tickStep(view2);
        assertSame(view2, session.view());
        assertEquals(Decay.groundedEquilibrium(GRAVITY), session.ledger().current().vy(), EPSILON);
        assertTrue(session.ledger().groundedView(), "the landing re-grounded the ledger");
    }

    @Test
    void tickStepSweepsAnEarlierTickTransaction() {
        AtomicInteger serverTick = new AtomicInteger(0);
        CombatSession session = new CombatSession(GRAVITY, 1, new PaperTickClock(serverTick::get), 8);

        HitContext context = new HitContext(
                new HitId(1), new HitSource.Melee(), UUID.randomUUID(), UUID.randomUUID(),
                new SprintVerdict(false, Boolean.FALSE, new TickStamp(0)), false, true, null,
                new TickStamp(0));
        HitTransaction tx = new HitTransaction(context);
        tx.planned();
        tx.preSent(new KnockbackVector(0.9, 0.46, 0.0));
        session.desk().submitFromWire(tx); // ahead of a damage task that never comes

        serverTick.set(1);
        session.tickStep(freshView(1)); // drains the wire, sweeps the earlier-tick tx

        assertEquals(1, session.desk().journal().size());
        assertEquals("no-velocity-event", session.desk().journal().get(0).suppressReason());
    }
}

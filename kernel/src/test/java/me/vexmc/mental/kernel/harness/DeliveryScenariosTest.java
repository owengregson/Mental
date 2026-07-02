package me.vexmc.mental.kernel.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.vexmc.mental.kernel.delivery.Directive;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.MotionMath;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.TickStamp;
import org.junit.jupiter.api.Test;

/**
 * The scripted interleaving scenarios (spec §12.2). Each asserts via the journal
 * only. Together they prove the delivery core's ordering guarantees on a single
 * deterministic thread the test drives.
 */
class DeliveryScenariosTest {

    private static final double EPSILON = 1.0e-9;

    /* ── 1. boundary combo decline ─────────────────────────────────────── */

    @Test
    void boundaryComboHitReadsThePreLandingFlightNotAGroundedReStamp() {
        SimulatedThreads sim = new SimulatedThreads(8);

        // The victim is grounded; hit 1 is a standing hit -> ships 0.3608.
        sim.setTick(0);
        sim.movement(sim.victim, true, true, 64.0); // seed the FSM grounded
        KnockbackVector hit1 = sim.registerMelee(false, 0);
        assertEquals(0.3608, hit1.y(), EPSILON);

        // The victim jumps: the GroundFsm stamps the 0.42 liftoff.
        sim.movement(sim.victim, false, true, 64.42);
        // Nine session ticks free-fall the jump stamp. Publication happens AFTER
        // the decay, so the view at tick 9 shows the flight residual.
        sim.runSessionTicks(sim.victim, 9);

        // Touchdown tick: the landing packet arrives, but hit 2 registers BEFORE
        // session-V drains it — the published view (tick 9) still shows flight.
        sim.setTick(10);
        sim.movement(sim.victim, true, true, 64.0); // landing enqueued, NOT drained
        KnockbackVector hit2 = sim.registerMelee(false, 0);

        // Hand-computed pin: 0.42 free-fallen 9 ticks (vanilla move-then-decay),
        // halved into the formula and +0.4 base.
        //   flightVy = simulateVerticalVelocity(0.42, 0.08, 9) ≈ −0.30153
        //   hit2.y  = flightVy × 0.5 + 0.4                     ≈ 0.24924
        double flightVy = MotionMath.simulateVerticalVelocity(0.42, SimulatedThreads.GRAVITY, 9);
        double expected = flightVy * 0.5 + 0.4;
        assertEquals(expected, hit2.y(), EPSILON);
        assertTrue(hit2.y() > 0.24 && hit2.y() < 0.26, "the declining ~0.25 band, not a grounded re-stamp");
        assertTrue(Math.abs(hit2.y() - 0.3608) > 0.1, "never the grounded 0.3608 re-stamp");

        // The SAME landing, now drained by session-V, re-stamps grounded: hit 3
        // ships the grounded 0.3608. This is what the boundary read avoided.
        sim.sessionTick(sim.victim); // drains the landing -> grounded
        KnockbackVector hit3 = sim.registerMelee(false, 0);
        assertEquals(0.3608, hit3.y(), EPSILON);

        List<JournalEntry> journal = sim.victim.desk.journal();
        assertEquals(0.3608, journal.get(0).shipped().y(), EPSILON);
        assertEquals(expected, journal.get(1).shipped().y(), EPSILON);
        assertEquals(0.3608, journal.get(2).shipped().y(), EPSILON);
    }

    /* ── 2. melee superseded by rod, same tick ─────────────────────────── */

    @Test
    void meleeSupersededByRodSameTickShipsTheRodAndJournalsBoth() {
        SimulatedThreads sim = new SimulatedThreads(8);
        KnockbackVector meleeVec = new KnockbackVector(0.9, 0.46, 0.0);
        KnockbackVector rodVec = new KnockbackVector(0.4, 0.36, 0.4);

        HitTransaction melee = new HitTransaction(sim.context(new HitSource.Melee()));
        melee.planned();
        melee.preSent(meleeVec);
        HitTransaction rod = new HitTransaction(sim.context(new HitSource.RodPull()));
        rod.planned();
        rod.preSent(rodVec);

        sim.victim.desk.submit(melee, meleeVec);
        sim.victim.desk.submit(rod, rodVec); // last wins
        sim.victim.desk.awaitVelocityEvent(rod);
        Directive directive = sim.victim.desk.resolve(rodVec.x(), rodVec.y(), rodVec.z());

        assertEquals(rodVec, directive.ship());
        List<JournalEntry> journal = sim.victim.desk.journal();
        assertEquals(2, journal.size());
        assertEquals("superseded", journal.get(0).suppressReason());
        assertEquals(HitSource.Melee.class, journal.get(0).source().getClass());
        assertEquals(rodVec, journal.get(1).shipped());
    }

    @Test
    void withdrawingTheRodLeavesTheSupersededMeleeEntryUntouched() {
        SimulatedThreads sim = new SimulatedThreads(8);
        KnockbackVector meleeVec = new KnockbackVector(0.9, 0.46, 0.0);
        KnockbackVector rodVec = new KnockbackVector(0.4, 0.36, 0.4);

        HitTransaction melee = new HitTransaction(sim.context(new HitSource.Melee()));
        melee.planned();
        melee.preSent(meleeVec);
        HitTransaction rod = new HitTransaction(sim.context(new HitSource.RodPull()));
        rod.planned();
        rod.preSent(rodVec);

        sim.victim.desk.submit(melee, meleeVec);
        sim.victim.desk.submit(rod, rodVec);
        sim.victim.desk.withdraw(rod.context().id()); // pull the rod back mid-way

        sim.victim.desk.awaitVelocityEvent(rod);
        Directive directive = sim.victim.desk.resolve(rodVec.x(), rodVec.y(), rodVec.z());
        assertEquals(Directive.Action.PASS_THROUGH, directive.action());
        // The melee's superseded entry is untouched — exactly one entry.
        assertEquals(1, sim.victim.desk.journal().size());
        assertEquals("superseded", sim.victim.desk.journal().get(0).suppressReason());
    }

    /* ── 3. retired mid-flight ─────────────────────────────────────────── */

    @Test
    void wireSubmittedTransactionRetiredMidFlightIsJournaledWithoutAValve() {
        SimulatedThreads sim = new SimulatedThreads(8);
        sim.setTick(0);
        HitTransaction tx = new HitTransaction(sim.context(new HitSource.Melee()));
        tx.planned();
        tx.preSent(new KnockbackVector(0.9, 0.46, 0.0));
        sim.victim.desk.submitFromWire(tx); // ahead of a damage task

        // The victim session never runs a damage pass; the scheduler retires it.
        tx.retracted();

        // The next session tick sweeps and journals it — no valve was ever armed.
        sim.setTick(1);
        sim.sessionTick(sim.victim);

        List<JournalEntry> journal = sim.victim.desk.journal();
        assertEquals(1, journal.size());
        assertFalse(journal.get(0).wireCarried(), "no valve — nothing wire-carried authoritatively");
        assertEquals("retracted", journal.get(0).suppressReason());
        assertEquals(HitTransaction.State.RECORDED, tx.state());
    }

    /* ── 4. stale view declines exclusion ──────────────────────────────── */

    @Test
    void aStaleViewMakesTheFastPathDecline() {
        SimulatedThreads sim = new SimulatedThreads(8);
        sim.setTick(0);
        sim.sessionTick(sim.victim); // publishes a view at tick 0
        // Do NOT tick session-V again; let the clock run 5 ticks past it.
        sim.setTick(sim.victim.published.at().value() + 5);

        assertFalse(sim.victim.published.fresh(sim.now()), "the view is older than 4 ticks");
        assertNull(sim.registerMelee(false, 0), "the fast path declines a stale view");
        assertTrue(sim.victim.desk.journal().isEmpty(), "nothing registered, nothing journaled");
    }

    /* ── 5. third-party modification ───────────────────────────────────── */

    @Test
    void thirdPartyModificationShipsACorrectionWithoutAValve() {
        SimulatedThreads sim = new SimulatedThreads(8);
        KnockbackVector formula = new KnockbackVector(0.9, 0.46, 0.0);
        HitTransaction tx = new HitTransaction(sim.context(new HitSource.Melee()));
        tx.planned();
        tx.preSent(formula);
        sim.victim.desk.submit(tx, formula);
        sim.victim.desk.awaitVelocityEvent(tx);

        Directive directive = sim.victim.desk.resolve(0.2, 0.2, 0.2); // a plugin changed it
        assertEquals(Directive.Action.SHIP, directive.action());
        assertNull(directive.arm(), "no valve for a modified event");
        JournalEntry entry = sim.victim.desk.journal().get(0);
        assertEquals(new KnockbackVector(0.2, 0.2, 0.2), entry.shipped());
        assertFalse(entry.wireCarried());
    }

    /* ── 6. pinned victim ──────────────────────────────────────────────── */

    @Test
    void pinnedVictimShipsWithoutAValve() {
        SimulatedThreads sim = new SimulatedThreads(8);
        KnockbackVector eraVec = new KnockbackVector(0.9, 0.46, 0.0);
        HitTransaction tx = new HitTransaction(sim.context(new HitSource.Melee()));
        tx.planned();
        tx.pinned(eraVec);
        sim.victim.desk.submit(tx, eraVec);
        sim.victim.desk.awaitVelocityEvent(tx);

        Directive directive = sim.victim.desk.resolve(eraVec.x(), eraVec.y(), eraVec.z());
        assertEquals(Directive.Action.SHIP, directive.action());
        assertNull(directive.arm(), "PINNED never arms a valve");
        JournalEntry entry = sim.victim.desk.journal().get(0);
        assertEquals(eraVec, entry.shipped());
        assertFalse(entry.wireCarried());
    }
}

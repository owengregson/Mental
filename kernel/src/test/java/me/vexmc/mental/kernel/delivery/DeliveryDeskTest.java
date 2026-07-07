package me.vexmc.mental.kernel.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import me.vexmc.mental.kernel.delivery.Directive.Action;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import org.junit.jupiter.api.Test;

/**
 * Pins the delivery desk's resolution algorithm (every numbered semantic of
 * Task 2.4) and the four canonical end-to-end knockback pins driven through the
 * real KnockbackEngine + LEGACY_17 profile. Assertions read the JOURNAL — the
 * single "what did we actually ship" seam (B7/R13).
 */
class DeliveryDeskTest {

    private static final int VICTIM_ENTITY = 42;
    private static final double EPSILON = 1.0e-9;
    private static final KnockbackVector VECTOR = new KnockbackVector(0.9, 0.4608, 0.0);

    private final Clock clock = new Clock();

    private static final class Clock implements TickClock {
        int tick;

        @Override
        public TickStamp current() {
            return new TickStamp(tick);
        }
    }

    private DeliveryDesk desk() {
        return new DeliveryDesk(VICTIM_ENTITY, clock, 4);
    }

    private DeliveryDesk desk(int journalCapacity) {
        return new DeliveryDesk(VICTIM_ENTITY, clock, journalCapacity);
    }

    private static HitContext ctx(long id, HitSource source, int registeredAtTick) {
        return new HitContext(
                new HitId(id), source, UUID.randomUUID(), UUID.randomUUID(),
                new SprintVerdict(true, Boolean.TRUE, new TickStamp(registeredAtTick)),
                true, null, new TickStamp(registeredAtTick));
    }

    /** A PRE_SENT transaction carrying {@code vector}, registered at {@code tick}. */
    private static HitTransaction preSent(long id, KnockbackVector vector, int tick) {
        HitTransaction tx = new HitTransaction(ctx(id, new HitSource.Melee(), tick));
        tx.planned();
        tx.preSent(vector);
        return tx;
    }

    /* ── 1. no awaited transaction ─────────────────────────────────────── */

    @Test
    void resolveWithNoAwaitedTransactionPassesThrough() {
        DeliveryDesk desk = desk();
        Directive directive = desk.resolve(1.0, 0.5, -1.0);
        assertEquals(Action.PASS_THROUGH, directive.action());
        assertTrue(desk.journal().isEmpty(), "foreign velocity is never journaled");
    }

    /* ── 2. null submitted vector = resistance roll ────────────────────── */

    @Test
    void nullSubmittedVectorCancelsAsResistanceRoll() {
        DeliveryDesk desk = desk();
        HitTransaction tx = new HitTransaction(ctx(1, new HitSource.Melee(), 0));
        desk.submit(tx, null);
        desk.awaitVelocityEvent(tx);
        Directive directive = desk.resolve(0.0, 0.4, 0.0);
        assertEquals(Action.CANCEL_EVENT, directive.action());
        List<JournalEntry> journal = desk.journal();
        assertEquals(1, journal.size());
        assertNull(journal.get(0).shipped(), "a cancelled roll ships nothing");
        assertEquals("resistance-roll", journal.get(0).suppressReason());
        assertTrue(!journal.get(0).wireCarried());
        assertEquals(HitTransaction.State.RECORDED, tx.state());
    }

    /* ── 3. PRE_SENT unmodified = ship + arm valve ─────────────────────── */

    @Test
    void preSentUnmodifiedShipsAndArmsTheValve() {
        DeliveryDesk desk = desk();
        HitTransaction tx = preSent(1, VECTOR, 0);
        desk.submit(tx, VECTOR);
        desk.awaitVelocityEvent(tx);

        Directive directive = desk.resolve(VECTOR.x(), VECTOR.y(), VECTOR.z());
        assertEquals(Action.SHIP_AND_ARM_VALVE, directive.action());
        assertEquals(VECTOR, directive.ship());
        assertEquals(ValvePayload.of(VICTIM_ENTITY, VECTOR), directive.arm());

        JournalEntry entry = desk.journal().get(0);
        assertEquals(VECTOR, entry.shipped());
        assertTrue(entry.wireCarried(), "the wire carried it — arm the valve");
        assertNull(entry.suppressReason());
        assertEquals(HitTransaction.State.RECORDED, tx.state());
    }

    /* ── 4. PRE_SENT modified by a third party = ship correction, no valve ─ */

    @Test
    void preSentModifiedShipsACorrectionWithoutValve() {
        DeliveryDesk desk = desk();
        HitTransaction tx = preSent(1, VECTOR, 0);
        desk.submit(tx, VECTOR);
        desk.awaitVelocityEvent(tx);

        // A third party changed the event velocity.
        Directive directive = desk.resolve(0.5, 0.5, 0.5);
        assertEquals(Action.SHIP, directive.action());
        assertNull(directive.arm(), "a modified event never arms the valve");
        assertEquals(new KnockbackVector(0.5, 0.5, 0.5), directive.ship());

        JournalEntry entry = desk.journal().get(0);
        // The ledger must record what the client will actually have.
        assertEquals(new KnockbackVector(0.5, 0.5, 0.5), entry.shipped());
        assertTrue(!entry.wireCarried());
    }

    /* ── 5. PINNED = ship, never a valve ───────────────────────────────── */

    @Test
    void pinnedShipsWithoutAValve() {
        DeliveryDesk desk = desk();
        HitTransaction tx = new HitTransaction(ctx(1, new HitSource.Melee(), 0));
        tx.planned();
        tx.pinned(VECTOR);
        desk.submit(tx, VECTOR);
        desk.awaitVelocityEvent(tx);

        Directive directive = desk.resolve(VECTOR.x(), VECTOR.y(), VECTOR.z());
        assertEquals(Action.SHIP, directive.action());
        assertNull(directive.arm(), "PINNED never arms a valve — B4");
        assertEquals(VECTOR, directive.ship());

        JournalEntry entry = desk.journal().get(0);
        assertEquals(VECTOR, entry.shipped());
        assertTrue(!entry.wireCarried(), "PINNED is never wire-carried");
    }

    /* ── 6. last-submitter-wins ────────────────────────────────────────── */

    @Test
    void lastSubmitterWinsSupersedingTheEarlier() {
        DeliveryDesk desk = desk();
        KnockbackVector meleeVec = new KnockbackVector(0.9, 0.46, 0.0);
        KnockbackVector rodVec = new KnockbackVector(0.4, 0.36, 0.4);
        HitTransaction melee = preSent(1, meleeVec, 0);
        HitTransaction rod = new HitTransaction(ctx(2, new HitSource.RodPull(), 0));
        rod.planned();
        rod.preSent(rodVec);

        desk.submit(melee, meleeVec);
        desk.submit(rod, rodVec); // last wins: melee superseded + journaled
        desk.awaitVelocityEvent(rod);
        Directive directive = desk.resolve(rodVec.x(), rodVec.y(), rodVec.z());

        assertEquals(rodVec, directive.ship());
        List<JournalEntry> journal = desk.journal();
        assertEquals(2, journal.size());
        assertEquals("superseded", journal.get(0).suppressReason());
        assertEquals(HitSource.Melee.class, journal.get(0).source().getClass());
        assertEquals(HitSource.RodPull.class, journal.get(1).source().getClass());
        assertEquals(rodVec, journal.get(1).shipped());
        assertEquals(HitTransaction.State.RECORDED, melee.state());
    }

    /* ── 7. withdraw is by exact id — never disturbs another (B4) ──────── */

    @Test
    void withdrawByIdNeverDisturbsAnotherTransaction() {
        DeliveryDesk desk = desk();
        HitTransaction tx = preSent(1, VECTOR, 0);
        desk.submit(tx, VECTOR);

        desk.withdraw(new HitId(999)); // a different id: no effect
        desk.awaitVelocityEvent(tx);
        Directive stillResolves = desk.resolve(VECTOR.x(), VECTOR.y(), VECTOR.z());
        assertEquals(Action.SHIP_AND_ARM_VALVE, stillResolves.action());
    }

    @Test
    void withdrawByExactIdRemovesTheDecisionLeavingTheSupersededEntryUntouched() {
        DeliveryDesk desk = desk();
        KnockbackVector meleeVec = new KnockbackVector(0.9, 0.46, 0.0);
        KnockbackVector rodVec = new KnockbackVector(0.4, 0.36, 0.4);
        HitTransaction melee = preSent(1, meleeVec, 0);
        HitTransaction rod = preSent(2, rodVec, 0);
        desk.submit(melee, meleeVec);
        desk.submit(rod, rodVec); // melee superseded + journaled
        desk.withdraw(new HitId(2)); // pull the rod back mid-way

        desk.awaitVelocityEvent(rod);
        Directive directive = desk.resolve(rodVec.x(), rodVec.y(), rodVec.z());
        assertEquals(Action.PASS_THROUGH, directive.action(), "the withdrawn rod no longer resolves");
        // The melee's superseded entry is untouched — one entry, still superseded.
        assertEquals(1, desk.journal().size());
        assertEquals("superseded", desk.journal().get(0).suppressReason());
    }

    /* ── withdrawSuperseded + journalDrop (blocked-knock correlation) ──── */

    @Test
    void withdrawSupersededJournalsTheRedeliverReferencingTheFreshId() {
        DeliveryDesk desk = desk();
        HitTransaction original = preSent(1, VECTOR, 0);
        desk.submit(original, VECTOR);

        desk.withdrawSuperseded(new HitId(1), "blocked-redeliver", new HitId(7));

        List<JournalEntry> journal = desk.journal();
        assertEquals(1, journal.size());
        assertNull(journal.get(0).shipped(), "a withdrawn redelivery ships nothing");
        assertEquals("blocked-redeliver -> 7", journal.get(0).suppressReason(),
                "the withdrawal must reference the superseding fresh id");
        assertEquals(HitTransaction.State.RECORDED, original.state());

        // The pending is cleared — a later resolve finds nothing to ship.
        desk.awaitVelocityEvent(original);
        assertEquals(Action.PASS_THROUGH, desk.resolve(VECTOR.x(), VECTOR.y(), VECTOR.z()).action());
    }

    @Test
    void withdrawSupersededIsANoOpForANeverSubmittedOriginal() {
        DeliveryDesk desk = desk();
        // A region-path original is never submitted to the desk (REGISTERED).
        desk.withdrawSuperseded(new HitId(1), "blocked-redeliver", new HitId(7));
        assertTrue(desk.journal().isEmpty(), "nothing pending ⇒ no correlation record");
    }

    @Test
    void journalDropAppendsACorrelatedNoteWithoutAStateTransition() {
        DeliveryDesk desk = desk();
        HitTransaction fresh = new HitTransaction(ctx(9, new HitSource.Melee(), 0));

        desk.journalDrop(fresh, "victim-retired");

        List<JournalEntry> journal = desk.journal();
        assertEquals(1, journal.size());
        assertEquals(9L, journal.get(0).id().value());
        assertNull(journal.get(0).shipped());
        assertEquals("victim-retired", journal.get(0).suppressReason());
        // The fresh tx never became pending — no state transition (still REGISTERED).
        assertEquals(HitTransaction.State.REGISTERED, fresh.state());
    }

    /* ── 8/9. sweep drops earlier-tick awaiting transactions ───────────── */

    @Test
    void sweepDropsAnAwaitingTransactionTwoTicksOld() {
        DeliveryDesk desk = desk();
        HitTransaction tx = preSent(1, VECTOR, 5);
        desk.submit(tx, VECTOR);
        desk.awaitVelocityEvent(tx);

        desk.sweep(new TickStamp(7)); // registered at 5, age 2 ⇒ dropped
        List<JournalEntry> journal = desk.journal();
        assertEquals(1, journal.size());
        assertEquals("no-velocity-event", journal.get(0).suppressReason());
        assertEquals(HitTransaction.State.RECORDED, tx.state());
        // A later resolve finds nothing pending.
        assertEquals(Action.PASS_THROUGH, desk.resolve(VECTOR.x(), VECTOR.y(), VECTOR.z()).action());
    }

    @Test
    void wireSubmittedThenSweptWithoutADamagePassIsDropped() {
        DeliveryDesk desk = desk();
        HitTransaction tx = preSent(1, VECTOR, 3);
        desk.submitFromWire(tx); // ahead of a damage task that never comes

        desk.sweep(new TickStamp(5)); // age 2 ⇒ dropped
        assertEquals(1, desk.journal().size());
        assertEquals("no-velocity-event", desk.journal().get(0).suppressReason());
        assertEquals(HitTransaction.State.RECORDED, tx.state());
    }

    @Test
    void sweepLeavesACurrentTickTransactionAlone() {
        DeliveryDesk desk = desk();
        HitTransaction tx = preSent(1, VECTOR, 5);
        desk.submit(tx, VECTOR);
        desk.awaitVelocityEvent(tx);
        desk.sweep(new TickStamp(5)); // same tick — its velocity event may still come
        assertTrue(desk.journal().isEmpty());
        assertEquals(Action.SHIP_AND_ARM_VALVE,
                desk.resolve(VECTOR.x(), VECTOR.y(), VECTOR.z()).action());
    }

    @Test
    void sweepLeavesAnAgeOneTransactionThenDropsAtAgeTwo() {
        DeliveryDesk desk = desk();
        HitTransaction tx = preSent(1, VECTOR, 5);
        desk.submit(tx, VECTOR);
        desk.awaitVelocityEvent(tx);

        // Age 1 survives — the one-tick margin absorbs the Folia counter skew (F6).
        desk.sweep(new TickStamp(6));
        assertTrue(desk.journal().isEmpty(), "an age-1 transaction survives one sweep");
        assertEquals(HitTransaction.State.PRE_SENT, tx.state());

        // Age 2 drops it.
        desk.sweep(new TickStamp(7));
        assertEquals(1, desk.journal().size());
        assertEquals("no-velocity-event", desk.journal().get(0).suppressReason());
        assertEquals(HitTransaction.State.RECORDED, tx.state());
    }

    @Test
    void resolveOnARecordedPendingPassesThroughWithoutThrowing() {
        DeliveryDesk desk = desk();
        HitTransaction tx = preSent(1, VECTOR, 5);
        desk.submit(tx, VECTOR);
        desk.awaitVelocityEvent(tx);
        desk.sweep(new TickStamp(7)); // age 2 ⇒ recorded + cleared, tx now RECORDED

        // The Folia skew race: the deferred damage re-submits the now-RECORDED tx
        // and a velocity event resolves it. adopted() would throw INSIDE the event
        // handler; the guard journals a late-resolve note and passes through.
        desk.submit(tx, VECTOR);
        desk.awaitVelocityEvent(tx);
        Directive directive = desk.resolve(VECTOR.x(), VECTOR.y(), VECTOR.z());

        assertEquals(Action.PASS_THROUGH, directive.action(), "a RECORDED pending never adopts");
        assertEquals(2, desk.journal().size(), "the no-velocity-event drop plus the late-resolve note");
        assertEquals("late-resolve-recorded", desk.journal().get(1).suppressReason());
    }

    /* ── 10. journal ring evicts the oldest ────────────────────────────── */

    @Test
    void journalRingEvictsTheOldest() {
        DeliveryDesk desk = desk(2); // capacity 2
        for (int i = 1; i <= 3; i++) {
            HitTransaction tx = new HitTransaction(ctx(i, new HitSource.Melee(), 0));
            desk.submit(tx, null);
            desk.awaitVelocityEvent(tx);
            desk.resolve(0, 0, 0); // resistance-roll cancel, journaled
        }
        List<JournalEntry> journal = desk.journal();
        assertEquals(2, journal.size(), "capacity is 2");
        assertEquals(2L, journal.get(0).id().value(), "oldest (id 1) evicted");
        assertEquals(3L, journal.get(1).id().value());
    }

    /* ── 11. ensure ships an unresolved transaction, then is idempotent ── */

    @Test
    void ensureShipsAnUnresolvedTransactionThenPassesThrough() {
        DeliveryDesk desk = desk();
        HitTransaction tx = new HitTransaction(ctx(1, new HitSource.RodPull(), 0));
        tx.planned(); // a rod self-launch: submitted, but no vanilla velocity event
        desk.submit(tx, VECTOR);

        Directive shipped = desk.ensure(new HitId(1));
        assertEquals(Action.SHIP, shipped.action());
        assertEquals(VECTOR, shipped.ship());
        assertNull(shipped.arm(), "ensure never arms a valve");
        assertEquals(HitTransaction.State.RECORDED, tx.state());
        JournalEntry entry = desk.journal().get(0);
        assertEquals(VECTOR, entry.shipped());
        assertTrue(!entry.wireCarried());

        // Idempotent: a second ensure finds nothing to do.
        assertEquals(Action.PASS_THROUGH, desk.ensure(new HitId(1)).action());
        assertEquals(1, desk.journal().size());
    }

    /* ── awaitingDeliveryFor: the region-path net's arm gate (F1 vs era-silent) ── */

    @Test
    void awaitingDeliveryForDiscriminatesAnArmedDecisionFromAnUnarmedOne() {
        DeliveryDesk desk = desk();
        HitTransaction tx = new HitTransaction(ctx(1, new HitSource.Melee(), 5));

        // Submitted but NOT armed for a velocity event — the era-silent
        // mid-invulnerability path (vanilla knocks nothing, fires no event). The net
        // must NOT ship this. A submitted-but-unarmed decision still carries a vector
        // (pendingVectorFor is non-null), so the two queries are deliberately distinct.
        desk.submit(tx, VECTOR);
        assertEquals(VECTOR, desk.pendingVectorFor(new HitId(1)),
                "a submitted decision still carries its vector");
        assertTrue(!desk.awaitingDeliveryFor(new HitId(1)),
                "submitted but unarmed is NOT awaiting delivery — the era-silent gate");

        // Arming it (a fresh hit expecting the victim's PlayerVelocityEvent) flips it
        // to awaiting-delivery — the exact F1 stranding the net ships.
        desk.awaitVelocityEvent(tx);
        assertTrue(desk.awaitingDeliveryFor(new HitId(1)),
                "submitted + armed IS awaiting delivery — the F1 net's fire condition");
        assertTrue(!desk.awaitingDeliveryFor(new HitId(2)), "a different id never matches");

        // Once resolved it no longer awaits (idempotent, non-fabricating).
        desk.resolve(VECTOR.x(), VECTOR.y(), VECTOR.z());
        assertTrue(!desk.awaitingDeliveryFor(new HitId(1)),
                "a resolved decision is no longer awaiting delivery");
    }

    @Test
    void awaitingDeliveryForDoesNotInheritASupersededDecisionsArm() {
        DeliveryDesk desk = desk();
        HitTransaction armed = new HitTransaction(ctx(1, new HitSource.Melee(), 5));
        desk.submit(armed, VECTOR);
        desk.awaitVelocityEvent(armed); // decision 1 is armed

        // A fresh decision supersedes it (same-tick double region hit). The await arm
        // must NOT carry over — the new pending was submitted, not yet armed.
        HitTransaction fresh = new HitTransaction(ctx(2, new HitSource.Melee(), 5));
        desk.submit(fresh, VECTOR);
        assertTrue(!desk.awaitingDeliveryFor(new HitId(2)),
                "superseding resets the arm — the fresh pending is not yet awaiting delivery");
        assertTrue(!desk.awaitingDeliveryFor(new HitId(1)),
                "the superseded decision is no longer the pending");
    }

    @Test
    void unexpectedVelocityEventAtAnUnarmedPendingPassesThroughAndLeavesItForTheSweep() {
        DeliveryDesk desk = desk();
        HitTransaction silent = new HitTransaction(ctx(1, new HitSource.Melee(), 5));
        desk.submit(silent, VECTOR); // era-silent blocked difference hit: submitted, never armed

        // A velocity event the desk never expected (a third-party setVelocity while
        // the era-silent decision sits): an unarmed pending means NO event is owed to
        // this decision, so the desk treats it as foreign — pass it through exactly
        // as it stands. It must never CANCEL (that would zero a velocity Mental does
        // not own) and never SHIP the submitted vector (that would deliver the very
        // knock the era withholds).
        Directive foreign = desk.resolve(0.1, 0.2, 0.3);
        assertEquals(Action.PASS_THROUGH, foreign.action(),
                "an unarmed pending never hijacks a foreign velocity event");
        assertNull(foreign.ship(), "pass-through ships nothing of Mental's");
        assertTrue(desk.journal().isEmpty(), "a foreign velocity is never journaled");

        // The decision itself is undisturbed — still pending, still unarmed, still
        // carrying its vector — so the sweep remains its one owner and closes it as
        // the era-silent drop.
        assertEquals(VECTOR, desk.pendingVectorFor(new HitId(1)),
                "the pass-through leaves the unarmed decision pending");
        assertTrue(!desk.awaitingDeliveryFor(new HitId(1)),
                "the pass-through never arms the decision");
        desk.sweep(new TickStamp(7));
        List<JournalEntry> journal = desk.journal();
        assertEquals(1, journal.size());
        assertNull(journal.get(0).shipped(), "the era-silent decision ships nothing");
        assertEquals("no-velocity-event", journal.get(0).suppressReason());
    }

    /* ── mirror + pending-formula views ────────────────────────────────── */

    @Test
    void mirrorAndPendingFormulaExposeTheDecisionThenClearAfterResolve() {
        DeliveryDesk desk = desk();
        assertNull(desk.pendingFormula(), "nothing pending");
        assertNull(desk.mirrorView());

        HitTransaction tx = preSent(1, VECTOR, 0);
        desk.submit(tx, VECTOR);
        assertEquals(VECTOR, desk.pendingFormula());
        assertEquals(VECTOR, desk.mirrorView());

        desk.awaitVelocityEvent(tx);
        desk.resolve(VECTOR.x(), VECTOR.y(), VECTOR.z());
        assertNull(desk.pendingFormula(), "resolved and cleared");
        assertNull(desk.mirrorView());
    }

    /* ── ValvePayload quantization (motion × 8000 as shorts, ±3.9 clamp) ── */

    @Test
    void valvePayloadQuantizesAndClamps() {
        ValvePayload payload = ValvePayload.of(7, new KnockbackVector(0.4, 0.3608, -1.0));
        assertEquals(7, payload.entityId());
        assertEquals((short) 3200, payload.qx());   // 0.4 × 8000
        assertEquals((short) 2886, payload.qy());    // 0.3608 × 8000 = 2886.4 -> 2886
        assertEquals((short) -8000, payload.qz());   // -1.0 × 8000

        // Beyond ±3.9 the axis clamps before quantizing.
        ValvePayload clamped = ValvePayload.of(7, new KnockbackVector(9.0, -9.0, 0.0));
        assertEquals((short) (3.9 * 8000), clamped.qx());
        assertEquals((short) (-3.9 * 8000), clamped.qy());
    }

    /* ── the four canonical end-to-end pins through the desk path ──────── */

    /**
     * Builds the EntityState fixtures the KnockbackEngineTest uses, but with the
     * victim's vertical seeded at the grounded equilibrium (−0.0784) — the value
     * the MotionLedger publishes for a standing grounded victim. That is what
     * makes the standing vertical 0.3608 and the bonus-hit vertical 0.4608.
     */
    private static EntityState attacker(float yaw, boolean sprinting, int enchant) {
        return new EntityState(0, 0, 0, yaw, 0, 0, 0, true, sprinting, enchant, 0);
    }

    private static EntityState groundedVictim() {
        return new EntityState(0, 0, 4, 0.0f, 0, Decay.groundedEquilibrium(0.08), 0, true, false, 0, 0);
    }

    private void driveThroughDesk(DeliveryDesk desk, HitTransaction tx, KnockbackVector vector) {
        desk.submit(tx, vector);
        desk.awaitVelocityEvent(tx);
        desk.resolve(vector.x(), vector.y(), vector.z());
    }

    @Test
    void canonicalKnockbackPinsShipThroughTheDeskPath() {
        KnockbackProfile profile = KnockbackProfile.LEGACY_17;
        EntityState victim = groundedVictim();

        KnockbackVector standing = KnockbackEngine.compute(attacker(0, false, 0), victim, profile, null);
        KnockbackVector sprint = KnockbackEngine.compute(attacker(0, true, 0), victim, profile, null);
        KnockbackVector kbTwo = KnockbackEngine.compute(attacker(0, false, 2), victim, profile, null);
        KnockbackVector sprintKbTwo = KnockbackEngine.compute(attacker(0, true, 2), victim, profile, null);

        // Standing: (≈0.4 h, 0.3608 v). h is on the +z axis; x is 0.
        assertEquals(0.0, standing.x(), EPSILON);
        assertEquals(0.3608, standing.y(), EPSILON); // −0.0784×0.5 + 0.4
        assertEquals(0.4, standing.z(), EPSILON);
        // Bonus hits: h grows 0.4 → 0.9 → 1.4 → 1.9, vertical is 0.4608 for all.
        // (0.3608 + 0.1 bonus = 0.4608; the plan's prose "0.4607" is a rounding.)
        assertEquals(0.9, sprint.z(), EPSILON);
        assertEquals(0.4608, sprint.y(), EPSILON);
        assertEquals(1.4, kbTwo.z(), EPSILON);
        assertEquals(0.4608, kbTwo.y(), EPSILON);
        assertEquals(1.9, sprintKbTwo.z(), EPSILON);
        assertEquals(0.4608, sprintKbTwo.y(), EPSILON);

        // Now prove each ships byte-identically through the desk path.
        assertShipsThroughDesk(0, standing);
        assertShipsThroughDesk(1, sprint);
        assertShipsThroughDesk(2, kbTwo);
        assertShipsThroughDesk(3, sprintKbTwo);
    }

    private void assertShipsThroughDesk(long id, KnockbackVector vector) {
        DeliveryDesk desk = desk();
        HitTransaction tx = preSent(id, vector, 0);
        driveThroughDesk(desk, tx, vector);
        JournalEntry entry = desk.journal().get(0);
        assertSame(vector, entry.shipped(), "the desk ships exactly the engine's vector");
        assertTrue(entry.wireCarried());
    }
}

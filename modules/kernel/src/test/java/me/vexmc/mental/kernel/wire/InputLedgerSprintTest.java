package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import org.junit.jupiter.api.Test;

/**
 * Pins the arrival-order sprint truth for one attacker, ported from the wire
 * cases of the old {@code SprintTrackerTest} onto the tick-stamped API (no
 * wall clock). A START arms freshness at arrival; the release half of a tap
 * never disarms; the desk's accepted-bonus clear CONSUMES the engagement, and
 * only a client-expressed re-gesture (a later wire START, the block re-arm)
 * arms the next one; the server flag wins a disagreement only after the wire
 * has been quiet — and never resurrects a spent engagement.
 */
class InputLedgerSprintTest {

    private static final class Clock implements TickClock {
        int tick;

        @Override
        public TickStamp current() {
            return new TickStamp(tick);
        }
    }

    @Test
    void startArmsSprintingAndFreshnessReadableTheSameTick() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        // No tick gating on reads: an ATTACK the same tick as the START sees it.
        SprintVerdict verdict = wire.verdictAt(new TickStamp(5));
        assertTrue(verdict.sprinting());
        assertEquals(Boolean.TRUE, verdict.fresh());
        assertEquals(new TickStamp(5), verdict.at());
    }

    @Test
    void stopDropsSprintingButFreshnessSurvivesTheReleaseHalfOfTheTap() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();  // arms
        wire.onSprintStop();   // the release never disarms freshness
        SprintVerdict verdict = wire.verdictAt(new TickStamp(0));
        assertFalse(verdict.sprinting());
        assertEquals(Boolean.TRUE, verdict.fresh(), "armed freshness waits for the hit that spends it");
    }

    @Test
    void serverClearDropsBothAndALaterStartReArms() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        // The accepted bonus hit: mirror vanilla's in-attack clear and spend
        // the freshness the hit used.
        wire.onServerClear();
        SprintVerdict afterHit = wire.verdictAt(new TickStamp(1));
        assertFalse(afterHit.sprinting(), "the era flag dropped inside the attack");
        assertEquals(Boolean.FALSE, afterHit.fresh(), "freshness spent by the hit that used it");

        // A later wire START (a w-tap) re-arms both — arrival order, not wall clock.
        clock.tick = 2;
        wire.onSprintStart();
        SprintVerdict reArmed = wire.verdictAt(new TickStamp(2));
        assertTrue(reArmed.sprinting());
        assertEquals(Boolean.TRUE, reArmed.fresh());
    }

    @Test
    void reconcileAdoptsTheServerFlagOnlyAfterTheQuietWindow() {
        Clock clock = new Clock();
        clock.tick = 10;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStop(); // wire says not-sprinting, last write at tick 10

        // Live flag disagrees (a plugin's setSprinting grant never crosses the
        // wire). Inside the quiet window the fresh wire STOP stands.
        wire.reconcile(true, new TickStamp(12), 3); // age 2 < 3 → hold
        assertFalse(wire.verdictAt(new TickStamp(12)).sprinting(),
                "a fresh wire STOP is not overwritten by a stale-high server flag");

        // At the quiet window the server's own flag wins the disagreement.
        wire.reconcile(true, new TickStamp(13), 3); // age 3 ≥ 3 → adopt
        assertTrue(wire.verdictAt(new TickStamp(13)).sprinting());
    }

    @Test
    void reconcileSeedsAnUnseenAttackerFromTheServerFlag() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        // Absent wire history: reconcile seeds from the live flag immediately.
        wire.reconcile(true, new TickStamp(0), 3);
        SprintVerdict seeded = wire.verdictAt(new TickStamp(0));
        assertTrue(seeded.sprinting());
        assertEquals(Boolean.FALSE, seeded.fresh(), "a seed is not fresh — no re-engage happened");
    }

    @Test
    void unseenWireFallsBackToNotSprinting() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);
        SprintVerdict verdict = wire.verdictAt(new TickStamp(0));
        assertFalse(verdict.sprinting());
        assertEquals(Boolean.FALSE, verdict.fresh());
    }

    /* ── the stamp-guarded clear + raw client flag (2.4.1, F2/F3 fix) ─────── */

    @Test
    void guardedServerClearNoOpsUnderANewerWireWrite() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        // A re-engage START arrives at tick 6 — strictly newer than a hit stamped
        // at tick 5. The deferred post-hit clear pertains to that T=5 hit and must
        // NOT eat the later re-engage (vanilla's synchronous clear never could).
        clock.tick = 6;
        wire.onSprintStart();
        wire.onServerClear(new TickStamp(5));

        SprintVerdict verdict = wire.verdictAt(new TickStamp(6));
        assertTrue(verdict.sprinting(), "a START newer than the hit stamp survives the guarded clear");
        assertEquals(Boolean.TRUE, verdict.fresh(), "and the freshness the re-engage armed survives too");
    }

    @Test
    void guardedServerClearAtOrBeforeTheStampClears() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();                 // lastWrite = 5
        wire.onServerClear(new TickStamp(5)); // asOf == lastWrite, not strictly older ⇒ clears

        SprintVerdict verdict = wire.verdictAt(new TickStamp(5));
        assertFalse(verdict.sprinting(), "no strictly-newer wire write ⇒ the guarded clear drops sprint");
        assertEquals(Boolean.FALSE, verdict.fresh());
    }

    @Test
    void clientSprintingSurvivesAServerClearAndOnlyStopLowersIt() {
        Clock clock = new Clock();
        clock.tick = 3;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        assertTrue(wire.clientSprinting(), "a START raises the raw client flag");

        wire.onServerClear(new TickStamp(3));
        assertFalse(wire.verdictAt(new TickStamp(3)).sprinting(), "the era wire view cleared");
        assertTrue(wire.clientSprinting(),
                "the RAW client flag survives the post-hit clear — the block-hit re-arm signal (F3)");

        clock.tick = 4;
        wire.onSprintStop();
        assertFalse(wire.clientSprinting(), "only a STOP packet lowers the raw client flag");
    }

    @Test
    @SuppressWarnings("deprecation") // the no-arg form retains the pre-guard behaviour by contract
    void deprecatedNoArgServerClearStillRetroClearsUnconditionally() {
        Clock clock = new Clock();
        clock.tick = 6;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart(); // lastWrite = 6, sprinting + armed, clientSprinting
        wire.onServerClear(); // the old form has NO guard — clears even a fresh START

        SprintVerdict verdict = wire.verdictAt(new TickStamp(6));
        assertFalse(verdict.sprinting(), "the unconditional form clears regardless of wire freshness");
        assertEquals(Boolean.FALSE, verdict.fresh());
        assertTrue(wire.clientSprinting(), "even the unconditional clear never touches the raw client flag");
    }

    /* ── the block-hit gesture (2.6.0: one hit per reset, the chain retired) ─ */

    @Test
    void aBlockResetGrantsExactlyOneFreshHitAndTheConsumeSpendsIt() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();       // sprinting toward the target
        wire.onBlockSprintReset();  // right-click block: the reconstructed era gesture

        // Hit #1 while holding the block — fresh, exactly like a w-tap.
        SprintVerdict first = wire.verdictAt(new TickStamp(5));
        assertTrue(first.sprinting());
        assertEquals(Boolean.TRUE, first.fresh());

        // The accepted bonus hit consumes the engagement — one hit per reset (the
        // 2.6.0 owner directive; the 2.5.1 sticky held-chain is retired).
        wire.onServerClear(new TickStamp(5));

        // Hit #2 while STILL holding the block ships PLAIN: the grant was spent,
        // and the next block-hit takes a fresh right-click.
        SprintVerdict second = wire.verdictAt(new TickStamp(5));
        assertFalse(second.sprinting(), "a held-block combo's second hit is plain — one hit per reset");
        assertEquals(Boolean.FALSE, second.fresh());

        // The fresh right-click re-arms — the next engagement, fresh as ever.
        wire.onBlockSprintReset();
        SprintVerdict third = wire.verdictAt(new TickStamp(5));
        assertTrue(third.sprinting(), "the re-engaged block arms the next one-hit grant");
        assertEquals(Boolean.TRUE, third.fresh());
    }

    @Test
    void withoutABlockEngagementTheServerClearStillDropsFreshness() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();                 // a plain sprinter, NO block engagement
        wire.onServerClear(new TickStamp(5)); // the first hit spends the freshness

        // The contrast pin: a stale-sprint hit with no block reset is NEVER
        // freshened — the blockhit contract must not blanket-freshen ordinary hits.
        SprintVerdict second = wire.verdictAt(new TickStamp(5));
        assertFalse(second.sprinting(), "no block reset ⇒ a stale-sprint hit stays exactly as before");
        assertEquals(Boolean.FALSE, second.fresh());
    }

    @Test
    void anUnspentBlockReArmSurvivesTheReleaseLikeTheEraReleaseReEngage() {
        Clock clock = new Clock();
        clock.tick = 3;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        wire.onBlockSprintReset();
        // Release WITHOUT hitting: the era client's release re-engage is what
        // re-armed the bonus, so a block-tap earns the same single fresh hit a
        // w-tap does — the unspent gesture stands until a hit spends it.
        wire.onBlockReleased();
        SprintVerdict afterRelease = wire.verdictAt(new TickStamp(3));
        assertTrue(afterRelease.sprinting(), "an unspent block re-arm survives the release (era-exact)");
        assertEquals(Boolean.TRUE, afterRelease.fresh());

        // The hit spends it whole — and with the block released there is nothing
        // left to re-arm without a new gesture.
        wire.onServerClear(new TickStamp(3));
        SprintVerdict afterHit = wire.verdictAt(new TickStamp(3));
        assertFalse(afterHit.sprinting());
        assertEquals(Boolean.FALSE, afterHit.fresh());
    }

    @Test
    void reEngagingTheBlockAfterASpentGrantArmsTheNextOne() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        wire.onBlockSprintReset();
        wire.onServerClear(new TickStamp(0)); // the hit spent the grant
        wire.onBlockReleased();               // evidence only — nothing left to drop
        assertFalse(wire.verdictAt(new TickStamp(0)).sprinting());

        // Press the block again (W still held → the raw client flag is up) — fresh.
        clock.tick = 2;
        wire.onBlockSprintReset();
        SprintVerdict reEngaged = wire.verdictAt(new TickStamp(2));
        assertTrue(reEngaged.sprinting());
        assertEquals(Boolean.TRUE, reEngaged.fresh());
    }

    @Test
    void aStopDuringAHeldBlockDropsTheWireUntilSprintResumes() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        wire.onBlockSprintReset(); // armed and fresh — the one-hit grant stands
        assertTrue(wire.verdictAt(new TickStamp(0)).sprinting());

        // Release the sprint key while still holding the block: a STOP drops the
        // wire view. You are no longer moving at sprint speed, so no phantom
        // sprint bonus — plain wire truth, no override needed.
        clock.tick = 1;
        wire.onSprintStop();
        assertFalse(wire.verdictAt(new TickStamp(1)).sprinting(),
                "a STOP drops the wire — no phantom bonus while standing");

        // Re-press sprint (still block-holding): a genuine START re-arms.
        clock.tick = 2;
        wire.onSprintStart();
        assertTrue(wire.verdictAt(new TickStamp(2)).sprinting());
        assertEquals(Boolean.TRUE, wire.verdictAt(new TickStamp(2)).fresh());
    }

    @Test
    void blockReleaseIsANoOpWhenNoResetIsHeld() {
        Clock clock = new Clock();
        clock.tick = 4;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart(); // a plain sprinter, never block-engaged

        // A RELEASE_USE_ITEM from any other item use (eating, drawing a bow) must
        // not churn the wire — the verdict is byte-identical either way (the
        // always-inbound RELEASE packets stay zero-touch).
        SprintVerdict before = wire.verdictAt(new TickStamp(4));
        wire.onBlockReleased();
        SprintVerdict after = wire.verdictAt(new TickStamp(4));
        assertEquals(before.sprinting(), after.sprinting());
        assertEquals(before.fresh(), after.fresh());
        assertTrue(after.sprinting());
        assertEquals(Boolean.TRUE, after.fresh());
    }

    /* ── the arrival-sequence clear (F1: same-tick w-tap retro-clear) ───────── */

    @Test
    void wireSeqStampsIntoTheVerdictAtPeek() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        // A fresh wire peeks at INITIAL seq 0, and a live peek is always wire-provenanced.
        SprintVerdict fresh = wire.verdictAt(new TickStamp(0));
        assertEquals(0L, fresh.wireSeq());
        assertTrue(fresh.fromWire());

        // A START bumps the arrival sequence 0 → 1; the peek carries it.
        wire.onSprintStart();
        SprintVerdict armed = wire.verdictAt(new TickStamp(0));
        assertEquals(1L, armed.wireSeq());
        assertTrue(armed.sprinting());
        assertEquals(Boolean.TRUE, armed.fresh());

        // The compat 3-arg constructor mints a verdict with no wire provenance.
        SprintVerdict mint = new SprintVerdict(false, null, new TickStamp(0));
        assertEquals(SprintVerdict.NO_WIRE_SEQ, mint.wireSeq());
        assertFalse(mint.fromWire());
    }

    @Test
    void sameTickReEngageAfterTheAttackSurvivesTheSeqGuardedClear() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        clock.tick = 4;
        wire.onSprintStart();                                    // engagement: seq 1, sprinting+armed
        clock.tick = 5;
        SprintVerdict first = wire.verdictAt(new TickStamp(5));  // the ATTACK's peek
        assertTrue(first.sprinting());
        assertEquals(1L, first.wireSeq());
        wire.onSprintStop();                                     // the attack-flush STOP: seq 2 (armed survives)
        wire.onSprintStart();                                    // the same-tick w-tap re-press: seq 3
        clock.tick = 7;
        wire.onServerClear(first.wireSeq());                     // deferred EDBEE clear: 3 > 1 ⇒ no-op
        SprintVerdict second = wire.verdictAt(new TickStamp(7)); // the next-tick ATTACK's peek
        assertTrue(second.sprinting());                         // ships the sprint knock (0.9 h, not 0.4)
        assertEquals(Boolean.TRUE, second.fresh());
        assertEquals(3L, second.wireSeq());
    }

    @Test
    void seqGuardedClearWithNoLaterWriteClears() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();                                    // seq 1
        SprintVerdict peek = wire.verdictAt(new TickStamp(5));
        assertEquals(1L, peek.wireSeq());
        clock.tick = 7;
        wire.onServerClear(1L);                                  // 1 <= 1 ⇒ clears
        SprintVerdict cleared = wire.verdictAt(new TickStamp(7));
        assertFalse(cleared.sprinting());
        assertEquals(Boolean.FALSE, cleared.fresh());
        assertTrue(wire.clientSprinting(), "the raw client flag is untouched, as ever");
    }

    @Test
    void aStopAfterTheAttackIsNotRetroEatenButStaysNonSprinting() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();                                    // seq 1
        SprintVerdict peek = wire.verdictAt(new TickStamp(5));
        assertEquals(1L, peek.wireSeq());
        wire.onSprintStop();                                     // the s-tap: seq 2, sprinting=false, armed survives
        wire.onServerClear(1L);                                  // 2 > 1 ⇒ no-op
        SprintVerdict verdict = wire.verdictAt(new TickStamp(5));
        assertFalse(verdict.sprinting(), "STOP's value stands — no bonus possible without sprinting");
        assertEquals(Boolean.TRUE, verdict.fresh(),
                "armed survives the release half; the newer-write semantic now at arrival granularity");
    }

    @Test
    void theSeqGuardedClearSpendsABlockReArmLikeAnyGesture() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();                                    // seq 1
        wire.onBlockSprintReset();                               // seq 2 — the block gesture
        SprintVerdict peek = wire.verdictAt(new TickStamp(5));
        assertEquals(2L, peek.wireSeq());
        wire.onServerClear(2L);                                  // 2 <= 2 ⇒ the consume applies
        SprintVerdict spent = wire.verdictAt(new TickStamp(5));
        assertFalse(spent.sprinting(), "the block grant is one hit — the consume spends it whole (2.6.0)");
        assertEquals(Boolean.FALSE, spent.fresh());
        assertTrue(wire.clientSprinting(), "the raw client flag survives, keeping the door eligible");
    }

    @Test
    void reconcileWritesCountForTheSeqGuard() {
        Clock clock = new Clock();
        clock.tick = 0;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();                                    // engage at tick 0: seq 1
        SprintVerdict peek = wire.verdictAt(new TickStamp(0));
        assertEquals(1L, peek.wireSeq());
        // A reconcile adopt IS a wire write — it bumps the seq (parity with the
        // lastWrite guard, which reconcile already refreshes today).
        wire.reconcile(false, new TickStamp(10), 3);            // disagree + age 10 ≥ 3 ⇒ adopt, seq 2
        wire.onServerClear(1L);                                  // 2 > 1 ⇒ no-op
        SprintVerdict verdict = wire.verdictAt(new TickStamp(10));
        assertFalse(verdict.sprinting(), "the adopted server flag stands; the stale clear no-ops against it");
    }

    /* ── the spend latch: one engagement, one sprint knock (2.5.1) ─────────── */

    @Test
    void aHeldServerFlagNeverReArmsASpentEngagement() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();                    // ONE engagement: clientSprinting up, sprinting+armed
        wire.onServerClear(new TickStamp(5));    // the bonus hit consumes it: the spend latch opens

        // A held-W modern client keeps moving (one reconcile per movement packet)
        // and its server flag stays true forever — its STOP never comes, because
        // vanilla's own clear lives inside the cancelled ATTACK. No re-gesture
        // crossed the wire, so nothing re-arms: not one tick after the clear (the
        // 2.5.0 post-clear re-arm cadence), not past the adopt quiet window, not
        // ever. The era server separated exactly this: a no-w-tap double flew 7.2
        // blocks on real 1.8.9 where a w-tap double flew 11.4.
        wire.reconcile(true, new TickStamp(6), 3);
        assertFalse(wire.verdictAt(new TickStamp(6)).sprinting(),
                "no automatic re-arm — a continuous hold is ONE engagement");
        wire.reconcile(true, new TickStamp(9), 3);   // age 4 ≥ 3: the adopt door, latch-blocked
        wire.reconcile(true, new TickStamp(20), 3);  // and long after
        SprintVerdict late = wire.verdictAt(new TickStamp(20));
        assertFalse(late.sprinting(),
                "the stale-high server flag never resurrects a spent engagement");
        assertEquals(Boolean.FALSE, late.fresh());
    }

    @Test
    void aWtapReGestureReArmsASpentEngagement() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        wire.onServerClear(new TickStamp(5));            // engagement spent
        wire.reconcile(true, new TickStamp(8), 3);       // held flag: latch-blocked
        assertFalse(wire.verdictAt(new TickStamp(8)).sprinting());

        // The w-tap: releasing W un-sprints the client (a STOP crosses, closing
        // the latch) and the re-press re-engages (a START crosses) — the genuine
        // re-gesture arms a NEW engagement, fresh as ever.
        clock.tick = 9;
        wire.onSprintStop();
        wire.onSprintStart();
        SprintVerdict reArmed = wire.verdictAt(new TickStamp(9));
        assertTrue(reArmed.sprinting(), "the re-gesture arms a new engagement");
        assertEquals(Boolean.TRUE, reArmed.fresh(), "and it is fresh — the w-tap extra applies");
    }

    @Test
    void aBlockHitReArmsASpentEngagement() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        wire.onServerClear(new TickStamp(5));   // spent; the raw client flag survives
        assertTrue(wire.clientSprinting());

        // The modern block-hit: item use never drops the client's own sprint, so
        // no STOP ever crosses — the SwordBlockingUnit re-arm IS the re-gesture.
        clock.tick = 6;
        wire.onBlockSprintReset();
        SprintVerdict reArmed = wire.verdictAt(new TickStamp(6));
        assertTrue(reArmed.sprinting(), "the block re-arm closes the latch and arms a new engagement");
        assertEquals(Boolean.TRUE, reArmed.fresh());
    }

    @Test
    void aClientStopAfterTheClearBlocksTheReArmThenAStartReArmsFreshness() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        wire.onServerClear(new TickStamp(5));    // clearedAt = 5, clientSprinting still up

        // The client DID express un-sprint (a w-tap's release, or a genuine stop):
        // the STOP lowers the raw client flag and closes the spend latch, and the
        // wire stays un-sprinted — a reconcile never synthesizes sprint.
        clock.tick = 6;
        wire.onSprintStop();
        assertFalse(wire.clientSprinting());
        wire.reconcile(true, new TickStamp(6), 3);
        assertFalse(wire.verdictAt(new TickStamp(6)).sprinting(),
                "a fresh wire STOP stands inside the quiet window — the w-tap contract is preserved");

        // The re-engage START (the actual w-tap) re-arms freshness the ordinary way.
        clock.tick = 7;
        wire.onSprintStart();
        SprintVerdict reArmed = wire.verdictAt(new TickStamp(7));
        assertTrue(reArmed.sprinting());
        assertEquals(Boolean.TRUE, reArmed.fresh(), "freshness is armed by the real START, w-tap intact");
    }

    @Test
    void aReconcileNeverReArmsWithoutAPriorClear() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        // The published-view / packetless-fallback shape: the server flag disagrees
        // but no hit ever opened the spend latch (clearedAt absent). Reconcile is a
        // seed, then an adopt strictly after the quiet window — never a synthesized
        // re-arm.
        wire.reconcile(true, new TickStamp(5), 3);            // unseen ⇒ seed to sprinting, NOT fresh
        SprintVerdict seeded = wire.verdictAt(new TickStamp(5));
        assertTrue(seeded.sprinting());
        assertEquals(Boolean.FALSE, seeded.fresh(), "a seed is not fresh — no re-engage happened");

        wire.onSprintStop();                                  // wire says not-sprinting at tick 5
        wire.reconcile(true, new TickStamp(7), 3);            // age 2 < 3 ⇒ hold (no clearedAt to re-arm from)
        assertFalse(wire.verdictAt(new TickStamp(7)).sprinting(),
                "no clearedAt window ⇒ the fresh wire STOP is not overwritten inside the quiet window");
    }

    @Test
    void aServerGrantIsAdoptedOnlyWhenNoConsumeIsOutstanding() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        wire.onServerClear(new TickStamp(5));                 // the spend latch opens

        // While the latch is open, a high server flag — stale hold OR a genuine
        // plugin setSprinting grant, indistinguishable on the wire — is never
        // adopted: the engagement was spent and only a client gesture re-arms.
        wire.reconcile(true, new TickStamp(9), 3);            // disagree + age 4 ⇒ latch-blocked
        assertFalse(wire.verdictAt(new TickStamp(9)).sprinting(),
                "adopt-true is blocked while a consume is outstanding");

        // The client's next real gesture closes the latch (here a STOP), after
        // which ordinary adoption resumes: the quiet window elapses and a
        // persistent server grant is adopted, not fresh.
        clock.tick = 10;
        wire.onSprintStop();
        wire.reconcile(true, new TickStamp(14), 3);           // disagree + age 4 ⇒ adopt true
        SprintVerdict adopted = wire.verdictAt(new TickStamp(14));
        assertTrue(adopted.sprinting(), "the latch closed — a server grant adopts as before");
        assertEquals(Boolean.FALSE, adopted.fresh(), "an adopt is never fresh");
    }

    /* ── the seed raises clientSprinting (mid-sprint plugin load/reload) ───── */

    @Test
    void seedFromASprintingServerFlagRaisesClientSprintingButAHitStillSpendsTheEngagement() {
        Clock clock = new Clock();
        clock.tick = 0;
        InputLedger wire = new InputLedger(clock);

        // A plugin load/reload mid-play creates the wire for a player who is ALREADY
        // sprinting. The first movement reconcile seeds the unseen wire from the live
        // server flag, which — its own pre-wire START having set it through vanilla's
        // handler — IS the client's last transmitted sprint state.
        wire.reconcile(true, new TickStamp(0), 3); // unseen ⇒ seed
        SprintVerdict seeded = wire.verdictAt(new TickStamp(0));
        assertTrue(seeded.sprinting(), "seeded sprinting from the live server flag");
        assertEquals(Boolean.FALSE, seeded.fresh(), "a seed is not fresh — no re-engage happened");
        assertTrue(wire.clientSprinting(),
                "the seed adopts the server flag as the client's last transmitted sprint state");

        // A bonus hit consumes the seeded engagement (the seq-guarded clear; no
        // later wire write).
        clock.tick = 1;
        wire.onServerClear(seeded.wireSeq()); // seq not advanced ⇒ clears sprinting/armed, latch opens
        assertFalse(wire.verdictAt(new TickStamp(1)).sprinting(), "the hit cleared the wire view");

        // A seeded engagement spends like any other: the held (never re-gestured)
        // server flag stays spent through every later reconcile — one engagement,
        // one sprint knock. The raised clientSprinting still matters: it is what
        // keeps the block-hit re-gesture eligible for this player.
        wire.reconcile(true, new TickStamp(2), 3);
        assertFalse(wire.verdictAt(new TickStamp(2)).sprinting(),
                "a seeded engagement is not resurrected by the stale-high server flag");
        assertTrue(wire.clientSprinting(), "the raw client flag still stands for the blockhit gate");
    }

    @Test
    void seedFromANonSprintingServerFlagLeavesClientSprintingLowAndDoesNotReArm() {
        Clock clock = new Clock();
        clock.tick = 0;
        InputLedger wire = new InputLedger(clock);

        // The reloaded player was walking: a non-sprinting seed leaves the raw flag down.
        wire.reconcile(false, new TickStamp(0), 3); // unseen ⇒ seed to not-sprinting
        SprintVerdict seeded = wire.verdictAt(new TickStamp(0));
        assertFalse(seeded.sprinting());
        assertFalse(wire.clientSprinting(), "a non-sprinting seed leaves clientSprinting false");

        // No clear window and no client sprint intent ⇒ later reconciles never synthesize
        // a bonus. Byte-identical to the pre-fix behaviour.
        wire.reconcile(false, new TickStamp(2), 3);
        assertFalse(wire.verdictAt(new TickStamp(2)).sprinting(),
                "no re-arm without a surviving client sprint intent");
    }

    @Test
    void theAdoptBranchNeverTouchesClientSprinting() {
        Clock clock = new Clock();
        clock.tick = 4;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart(); // seen=true, clientSprinting up, sprinting+armed, lastWrite = 4
        assertTrue(wire.clientSprinting(), "a START raises the raw client flag");

        // A persistent server un-sprint is ADOPTED into the wire's sprinting flag after
        // the quiet window — but the raw client flag is packet-only, so the adopt (unlike
        // the seed) must never write it. This is the existing packet-only invariant.
        wire.reconcile(false, new TickStamp(8), 3); // disagree + age 4 ≥ 3 ⇒ adopt sprinting=false
        assertFalse(wire.verdictAt(new TickStamp(8)).sprinting(), "the adopt took the server un-sprint");
        assertTrue(wire.clientSprinting(), "the ADOPT branch never touches the raw client flag");
    }

    /* ── the PLAYER_INPUT sprint-key corroborator (the block door's entry gate) ─ */

    @Test
    void unknownKeyIntentLeavesTheBlockhitGateByteIdentical() {
        Clock clock = new Clock();
        clock.tick = 4;
        InputLedger wire = new InputLedger(clock);

        // No onKeyIntent is ever called — keyIntent stays UNKNOWN (null), the value
        // for every pre-1.21.2 / Via / packetless client. The entry gate must
        // collapse to the raw-flag test exactly.
        wire.onSprintStart(); // clientSprinting = true
        assertTrue(wire.blockReArmEligible(),
                "UNKNOWN keyIntent + clientSprinting=true ⇒ eligible, as the raw-flag gate always was");

        wire.onBlockSprintReset();
        assertTrue(wire.verdictAt(new TickStamp(4)).sprinting(),
                "the block gesture arms the wire with UNKNOWN keyIntent exactly as with a known one");

        // Drop the raw client flag: with no keyIntent to widen it, the gate refuses.
        clock.tick = 5;
        wire.onSprintStop(); // clientSprinting = false; keyIntent still UNKNOWN
        assertFalse(wire.blockReArmEligible(),
                "UNKNOWN keyIntent + clientSprinting=false ⇒ refused (byte-identical to the raw-flag gate)");
        assertFalse(wire.verdictAt(new TickStamp(6)).sprinting(),
                "and the STOP dropped the wire view — no phantom bonus");
    }

    @Test
    void aGenuineStopWithARecentlySprintingHeldKeyKeepsTheDoorEligible() {
        Clock clock = new Clock();
        clock.tick = 0;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();   // lastSprintingAt = 0, clientSprinting = true
        wire.onKeyIntent(true); // the raw sprint KEY is held (a ctrl/toggle holder)

        // A full-charge / spoofed hit lands while item-use blocked the same-tick re-arm:
        // the ONE case a genuine STOP crosses for a key-holder — the raw client flag falls.
        clock.tick = 5;
        wire.onSprintStop();
        assertFalse(wire.clientSprinting(), "the blocked-tick STOP lowered the raw client flag");

        // The entry gate: 5 ticks since last sprint, key still held ⇒ the door may re-arm.
        assertTrue(wire.blockReArmEligible(),
                "held sprint key + recent sprint carries the era block-hitter past the blocked STOP");

        // And the re-arm it admits is a full gesture: the next hit is fresh — the
        // corroborator widens the DOOR, and the gesture itself carries the verdict.
        wire.onBlockSprintReset();
        SprintVerdict v = wire.verdictAt(new TickStamp(6));
        assertTrue(v.sprinting(), "the corroborated block re-arm arms the wire");
        assertEquals(Boolean.TRUE, v.fresh());
    }

    @Test
    void aHeldKeyThatHasNotSprintedInOverASecondIsRefusedByTheGate() {
        Clock clock = new Clock();
        clock.tick = 0;
        InputLedger wire = new InputLedger(clock);

        // A stationary defensive ctrl-holder: the sprint KEY is held, but the last time
        // the wire was actually sprinting is now stale (> 20 ticks).
        wire.onSprintStart();      // lastSprintingAt = 0
        wire.onKeyIntent(true);
        wire.onSprintStop();       // raw client flag down; lastSprintingAt stays 0

        clock.tick = 25; // 25 ticks since the last sprint > ERA_BLOCKHIT_RECENCY_TICKS (20)
        assertFalse(wire.blockReArmEligible(),
                "25 ticks since last sprint ⇒ the stationary defensive ctrl-holder earns no re-arm");
        assertFalse(wire.verdictAt(new TickStamp(25)).sprinting(),
                "and with no admitted gesture the wire stays plain — no phantom bonus");
    }

    @Test
    void keyIntentFalseNeverVetoesAClientSprintingPath() {
        Clock clock = new Clock();
        clock.tick = 3;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();    // clientSprinting = true (a double-tap-W sprinter IS sprinting)
        wire.onKeyIntent(false); // ...but the raw sprint KEY reads false (double-tap holds no key)
        assertTrue(wire.blockReArmEligible(),
                "clientSprinting=true passes regardless of a FALSE key intent — double-tap sprinters unaffected");

        wire.onBlockSprintReset();
        SprintVerdict v = wire.verdictAt(new TickStamp(3));
        assertTrue(v.sprinting(), "the block gesture arms; a FALSE key intent never vetoes it");
        assertEquals(Boolean.TRUE, v.fresh());
    }

    @Test
    void aKeyIntentWriteNeverDefeatsTheDeferredConsume() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();                               // seq 1, sprinting + armed
        SprintVerdict peek = wire.verdictAt(new TickStamp(5));
        assertEquals(1L, peek.wireSeq());
        // PLAYER_INPUT fires on ANY of its 7 bits flipping — a jump, a strafe
        // reverse, a sneak — so an active-movement combo lands one in nearly every
        // hit's 1–2-tick deferred-clear window. It is an observation, not a
        // gesture: it must not veto the consume the way a re-engage START does
        // (the clear carries keyIntent through unchanged — nothing to retro-eat).
        wire.onKeyIntent(true);                             // NOT a wire write: seq stays 1
        clock.tick = 7;
        wire.onServerClear(peek.wireSeq());                 // 1 == 1 ⇒ the consume applies
        SprintVerdict after = wire.verdictAt(new TickStamp(7));
        assertFalse(after.sprinting(), "the engagement is spent — key intent is not a re-gesture");
        assertEquals(Boolean.FALSE, after.fresh());
        assertEquals(1L, after.wireSeq(), "onKeyIntent bumps nothing");

        // Nor does a key sample close the spend latch: the stale-high server flag
        // stays latch-blocked until a genuine gesture (START/STOP/block re-arm).
        wire.reconcile(true, new TickStamp(10), 3);
        assertFalse(wire.verdictAt(new TickStamp(10)).sprinting(),
                "a key-intent sample neither spends nor re-arms an engagement");
    }
}

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
 * never disarms; the desk's accepted-bonus clear drops both, and a later wire
 * START re-arms; the server flag wins a disagreement only after the wire has
 * been quiet.
 */
class SprintWireTest {

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
        SprintWire wire = new SprintWire(clock);

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
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart();  // arms
        wire.onSprintStop();   // the release never disarms freshness
        SprintVerdict verdict = wire.verdictAt(new TickStamp(0));
        assertFalse(verdict.sprinting());
        assertEquals(Boolean.TRUE, verdict.fresh(), "armed freshness waits for the hit that spends it");
    }

    @Test
    void serverClearDropsBothAndALaterStartReArms() {
        Clock clock = new Clock();
        SprintWire wire = new SprintWire(clock);

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
        SprintWire wire = new SprintWire(clock);

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
        SprintWire wire = new SprintWire(clock);

        // Absent wire history: reconcile seeds from the live flag immediately.
        wire.reconcile(true, new TickStamp(0), 3);
        SprintVerdict seeded = wire.verdictAt(new TickStamp(0));
        assertTrue(seeded.sprinting());
        assertEquals(Boolean.FALSE, seeded.fresh(), "a seed is not fresh — no re-engage happened");
    }

    @Test
    void unseenWireFallsBackToNotSprinting() {
        Clock clock = new Clock();
        SprintWire wire = new SprintWire(clock);
        SprintVerdict verdict = wire.verdictAt(new TickStamp(0));
        assertFalse(verdict.sprinting());
        assertEquals(Boolean.FALSE, verdict.fresh());
    }

    /* ── the stamp-guarded clear + raw client flag (2.4.1, F2/F3 fix) ─────── */

    @Test
    void guardedServerClearNoOpsUnderANewerWireWrite() {
        Clock clock = new Clock();
        SprintWire wire = new SprintWire(clock);

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
        SprintWire wire = new SprintWire(clock);

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
        SprintWire wire = new SprintWire(clock);

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
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart(); // lastWrite = 6, sprinting + armed, clientSprinting
        wire.onServerClear(); // the old form has NO guard — clears even a fresh START

        SprintVerdict verdict = wire.verdictAt(new TickStamp(6));
        assertFalse(verdict.sprinting(), "the unconditional form clears regardless of wire freshness");
        assertEquals(Boolean.FALSE, verdict.fresh());
        assertTrue(wire.clientSprinting(), "even the unconditional clear never touches the raw client flag");
    }

    /* ── the universal blockhit contract (held-block sprint reset) ───────── */

    @Test
    void heldBlockResetKeepsEveryComboHitFreshAcrossTheServerClear() {
        Clock clock = new Clock();
        clock.tick = 5;
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart();       // sprinting toward the target
        wire.onBlockSprintReset();  // right-click block: the sticky held-block reset

        // Hit #1 while holding the block — fresh, exactly like a w-tap.
        SprintVerdict first = wire.verdictAt(new TickStamp(5));
        assertTrue(first.sprinting());
        assertEquals(Boolean.TRUE, first.fresh());

        // The accepted bonus hit clears the wire (the post-hit onServerClear) — but
        // the block is still held, so the freshness the first hit spent must survive.
        wire.onServerClear(new TickStamp(5));

        // Hit #2 while STILL holding the block — the defect this contract fixes:
        // pre-change this read non-sprint (armed dropped, no re-arm without a new
        // right-click). It now ships the FULL fresh sprint knock.
        SprintVerdict second = wire.verdictAt(new TickStamp(5));
        assertTrue(second.sprinting(), "a held-block combo's second hit still ships the sprint knock");
        assertEquals(Boolean.TRUE, second.fresh(), "and it ships the full fresh stamp, not a decayed one");
    }

    @Test
    void withoutABlockEngagementTheServerClearStillDropsFreshness() {
        Clock clock = new Clock();
        clock.tick = 5;
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart();                 // a plain sprinter, NO block engagement
        wire.onServerClear(new TickStamp(5)); // the first hit spends the freshness

        // The contrast pin: a stale-sprint hit with no block reset is NEVER
        // freshened — the blockhit contract must not blanket-freshen ordinary hits.
        SprintVerdict second = wire.verdictAt(new TickStamp(5));
        assertFalse(second.sprinting(), "no block reset ⇒ a stale-sprint hit stays exactly as before");
        assertEquals(Boolean.FALSE, second.fresh());
    }

    @Test
    void blockReleaseEndsTheResetSoAPostReleaseHitFallsBackToTheWire() {
        Clock clock = new Clock();
        clock.tick = 3;
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart();
        wire.onBlockSprintReset();
        wire.onServerClear(new TickStamp(3)); // a hit spent the freshness; block still held
        assertTrue(wire.verdictAt(new TickStamp(3)).sprinting(), "held block: fresh");

        // Release the block button (the client's RELEASE_USE_ITEM) — the "while
        // currently blocking" boundary. The sticky reset drops; the underlying wire
        // (cleared by the hit) governs again, so a post-release hit is not a sprint
        // hit until a real re-tap.
        wire.onBlockReleased();
        SprintVerdict afterRelease = wire.verdictAt(new TickStamp(3));
        assertFalse(afterRelease.sprinting(), "after the block releases the ordinary wire verdict returns");
        assertEquals(Boolean.FALSE, afterRelease.fresh());
    }

    @Test
    void reEngagingTheBlockAfterAReleaseReArmsTheReset() {
        Clock clock = new Clock();
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart();
        wire.onBlockSprintReset();
        wire.onServerClear(new TickStamp(0));
        wire.onBlockReleased();
        assertFalse(wire.verdictAt(new TickStamp(0)).sprinting());

        // Press the block again (W still held → the raw client flag is up) — fresh.
        clock.tick = 2;
        wire.onBlockSprintReset();
        SprintVerdict reEngaged = wire.verdictAt(new TickStamp(2));
        assertTrue(reEngaged.sprinting());
        assertEquals(Boolean.TRUE, reEngaged.fresh());
    }

    @Test
    void aStopSprintDuringAHeldBlockGatesTheResetUntilSprintResumes() {
        Clock clock = new Clock();
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart();
        wire.onBlockSprintReset();
        wire.onServerClear(new TickStamp(0)); // held-block, fresh
        assertTrue(wire.verdictAt(new TickStamp(0)).sprinting());

        // Release the sprint key while still holding the block: a STOP lowers the
        // raw client flag → the reset is gated. You are no longer moving at sprint
        // speed, so no phantom sprint bonus (the resetSprintForBlock gate's spirit).
        clock.tick = 1;
        wire.onSprintStop();
        assertFalse(wire.verdictAt(new TickStamp(1)).sprinting(),
                "a STOP ends the held-block reset — no phantom bonus while standing");

        // Re-press sprint (still block-holding): the reset governs again.
        clock.tick = 2;
        wire.onSprintStart();
        assertTrue(wire.verdictAt(new TickStamp(2)).sprinting());
        assertEquals(Boolean.TRUE, wire.verdictAt(new TickStamp(2)).fresh());
    }

    @Test
    void blockReleaseIsANoOpWhenNoResetIsHeld() {
        Clock clock = new Clock();
        clock.tick = 4;
        SprintWire wire = new SprintWire(clock);

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
        SprintWire wire = new SprintWire(clock);

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
        SprintWire wire = new SprintWire(clock);

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
        SprintWire wire = new SprintWire(clock);

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
        SprintWire wire = new SprintWire(clock);

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
    void heldBlockResetSurvivesTheSeqGuardedClear() {
        Clock clock = new Clock();
        clock.tick = 5;
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart();                                    // seq 1
        wire.onBlockSprintReset();                               // seq 2, sticky blockReset
        SprintVerdict peek = wire.verdictAt(new TickStamp(5));
        assertEquals(2L, peek.wireSeq());
        wire.onServerClear(2L);                                  // 2 <= 2 ⇒ clears sprinting/armed, blockReset survives
        SprintVerdict held = wire.verdictAt(new TickStamp(5));
        assertTrue(held.sprinting(), "blockReset && clientSprinting ⇒ fresh sprint on the seq overload");
        assertEquals(Boolean.TRUE, held.fresh());
    }

    @Test
    void reconcileWritesCountForTheSeqGuard() {
        Clock clock = new Clock();
        clock.tick = 0;
        SprintWire wire = new SprintWire(clock);

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

    /* ── the post-clear re-arm (modern-client sprint latch fix) ──────────── */

    @Test
    void aSameTickReconcileDoesNotReArmTheGapHolds() {
        Clock clock = new Clock();
        clock.tick = 5;
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart();                    // clientSprinting up, sprinting+armed
        wire.onServerClear(new TickStamp(5));    // the post-hit clear: clearedAt = 5
        // A movement reconcile the SAME tick as the clear: the era one-tick re-engage
        // gap has not elapsed, so the bonus stays dropped (bonus → gap → re-arm).
        wire.reconcile(true, new TickStamp(5), 3);

        SprintVerdict sameTick = wire.verdictAt(new TickStamp(5));
        assertFalse(sameTick.sprinting(), "no re-arm the same tick as the clear — the era gap holds");
        assertEquals(Boolean.FALSE, sameTick.fresh());
    }

    @Test
    void aReconcileAfterTheClearReArmsWhenTheClientNeverUnSprinted() {
        Clock clock = new Clock();
        clock.tick = 5;
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart();                    // clientSprinting up, sprinting+armed
        wire.onServerClear(new TickStamp(5));    // the post-hit clear: sprinting/armed drop
        long seqAfterClear = wire.verdictAt(new TickStamp(5)).wireSeq();
        assertFalse(wire.verdictAt(new TickStamp(5)).sprinting());

        // A movement reconcile ≥1 tick after the clear with the RAW client flag still
        // up (no STOP followed the hit): the modern client never expressed un-sprint,
        // so re-engage the bonus at the era one-tick re-engage cadence.
        wire.reconcile(true, new TickStamp(6), 3);
        SprintVerdict reArmed = wire.verdictAt(new TickStamp(6));
        assertTrue(reArmed.sprinting(), "re-armed at the era one-tick re-engage cadence");
        assertEquals(Boolean.FALSE, reArmed.fresh(),
                "armed is untouched — freshness still comes only from a real START");
        assertEquals(seqAfterClear + 1, reArmed.wireSeq(), "the re-arm is a wire write — the seq bumps");
    }

    @Test
    void aClientStopAfterTheClearBlocksTheReArmThenAStartReArmsFreshness() {
        Clock clock = new Clock();
        clock.tick = 5;
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart();
        wire.onServerClear(new TickStamp(5));    // clearedAt = 5, clientSprinting still up

        // The client DID express un-sprint (a w-tap's release, or a genuine stop): the
        // STOP lowers the raw client flag AND resets clearedAt, forbidding the re-arm.
        clock.tick = 6;
        wire.onSprintStop();
        assertFalse(wire.clientSprinting());
        wire.reconcile(true, new TickStamp(6), 3);
        assertFalse(wire.verdictAt(new TickStamp(6)).sprinting(),
                "a client STOP after the hit forbids the auto re-arm — the w-tap contract is preserved");

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
        SprintWire wire = new SprintWire(clock);

        // The published-view / packetless-fallback shape: the server flag disagrees but
        // NO post-hit clear ever opened a re-arm window (clearedAt absent). Only a hit's
        // clear opens it, so reconcile behaves byte-identically to before — a seed, then
        // an adopt strictly after the quiet window, never a synthesized re-arm.
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
    void aServerAdoptAfterAReArmClosesTheWindowSoNoOscillation() {
        Clock clock = new Clock();
        clock.tick = 5;
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart();
        wire.onServerClear(new TickStamp(5));                 // clearedAt = 5
        wire.reconcile(true, new TickStamp(6), 3);            // re-arm: sprinting again
        assertTrue(wire.verdictAt(new TickStamp(6)).sprinting());

        // A genuine server-side un-sprint (an external setSprinting(false)/hunger drop)
        // that persists: after the quiet window the wire adopts the server's false flag
        // AND resets clearedAt, so the next reconcile does NOT re-arm over it — a
        // genuine un-sprint is never overridden (no post-hit re-arm oscillation).
        wire.reconcile(false, new TickStamp(9), 3);           // disagree + age 3 ⇒ adopt false, clearedAt reset
        assertFalse(wire.verdictAt(new TickStamp(9)).sprinting());
        wire.reconcile(false, new TickStamp(10), 3);          // window closed ⇒ no re-arm
        assertFalse(wire.verdictAt(new TickStamp(10)).sprinting(),
                "the adopted server un-sprint stands — the re-arm window closed on adopt");
    }

    /* ── the seed raises clientSprinting (mid-sprint plugin load/reload) ───── */

    @Test
    void seedFromASprintingServerFlagRaisesClientSprintingAndKeepsTheBonusAcrossAHit() {
        Clock clock = new Clock();
        clock.tick = 0;
        SprintWire wire = new SprintWire(clock);

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

        // A bonus hit clears the wire (the seq-guarded clear; no later wire write).
        clock.tick = 1;
        wire.onServerClear(seeded.wireSeq()); // seq not advanced ⇒ clears sprinting/armed, clearedAt = 1
        assertFalse(wire.verdictAt(new TickStamp(1)).sprinting(), "the hit cleared the wire view");

        // The +1-tick movement reconcile with the raw client flag surviving the seed:
        // the mid-sprint-reload player keeps the bonus. WITHOUT the seed fix the seed
        // carried the INITIAL false clientSprinting, so this re-arm would refuse.
        wire.reconcile(true, new TickStamp(2), 3);
        assertTrue(wire.verdictAt(new TickStamp(2)).sprinting(),
                "the seed's raised clientSprinting lets the post-clear re-arm re-engage the bonus");
    }

    @Test
    void seedFromANonSprintingServerFlagLeavesClientSprintingLowAndDoesNotReArm() {
        Clock clock = new Clock();
        clock.tick = 0;
        SprintWire wire = new SprintWire(clock);

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
        SprintWire wire = new SprintWire(clock);

        wire.onSprintStart(); // seen=true, clientSprinting up, sprinting+armed, lastWrite = 4
        assertTrue(wire.clientSprinting(), "a START raises the raw client flag");

        // A persistent server un-sprint is ADOPTED into the wire's sprinting flag after
        // the quiet window — but the raw client flag is packet-only, so the adopt (unlike
        // the seed) must never write it. This is the existing packet-only invariant.
        wire.reconcile(false, new TickStamp(8), 3); // disagree + age 4 ≥ 3 ⇒ adopt sprinting=false
        assertFalse(wire.verdictAt(new TickStamp(8)).sprinting(), "the adopt took the server un-sprint");
        assertTrue(wire.clientSprinting(), "the ADOPT branch never touches the raw client flag");
    }
}

package me.vexmc.mental.kernel.combo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import me.vexmc.mental.kernel.model.TickStamp;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed pins for the combo detector state machine (combo-hold §3.1,
 * widened for the gen-3 surface). Defaults: minHits 2, maxGapTicks 20,
 * groundedRunTicks 10, blowoutBlocks 6.0. Every boundary (activation on the
 * second hit — the 2.4.5 retune, the developing-chain edges CHAIN_OPENED /
 * CHAIN_ADVANCED / CHAIN_ABORTED and the active HIT edge, gap expiry at exactly
 * gap+1, attacker-switch restart, retaliation, grounded-run, blowout, NaN
 * separation) is asserted so the machine can never drift silently.
 */
class ComboTrackerTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final ComboRules RULES = ComboRules.DEFAULTS;

    private static TickStamp t(int value) {
        return new TickStamp(value);
    }

    private static ComboTracker tracker() {
        return new ComboTracker(RULES);
    }

    /**
     * Drive three same-attacker hits at a 10-tick cadence to an active combo. The
     * combo goes active on the SECOND hit (minHits 2); the third continues it and
     * leaves {@code lastHitTick} at t(20) — the gap clock the downstream gap/grounded
     * pins measure their offsets from.
     */
    private static ComboTracker active() {
        ComboTracker tracker = tracker();
        tracker.onKnockShipped(A, t(0));
        tracker.onKnockShipped(A, t(10)); // second hit → active
        tracker.onKnockShipped(A, t(20));
        return tracker;
    }

    /** onKnockShipped returns 1+ transitions; a same-attacker continuation is a single one. */
    private static ComboTransition one(List<ComboTransition> transitions) {
        assertTrue(transitions.size() <= 1, () -> "expected at most one transition, got " + transitions);
        return transitions.isEmpty() ? ComboTransition.NONE : transitions.get(0);
    }

    @Test
    void activatesExactlyOnTheSecondHit() {
        // The 2.4.5 retune: minHits 2, so the SECOND shipped hit fires COMBO START.
        ComboTracker tracker = tracker();

        // hit 1 now surfaces CHAIN_OPENED (was silent before gen-3) but does not activate.
        ComboTransition first = one(tracker.onKnockShipped(A, t(0)));
        assertEquals(ComboTransition.Kind.CHAIN_OPENED, first.kind(), "hit 1 opens the developing chain");
        assertEquals(A, first.attacker());
        assertEquals(1, first.hits());
        assertFalse(tracker.active());
        assertNull(tracker.snapshot().attackerId(), "developing chain publishes no attacker");

        ComboTransition second = one(tracker.onKnockShipped(A, t(10)));
        assertTrue(second.started(), "hit 2 activates the combo");
        assertEquals(A, second.attacker());
        assertEquals(2, second.hits());
        assertTrue(tracker.active());
        assertEquals(A, tracker.snapshot().attackerId());
        assertEquals(t(10), tracker.snapshot().activeSince());

        // A third same-attacker hit continues the active chain — HIT, no fresh START.
        ComboTransition third = one(tracker.onKnockShipped(A, t(20)));
        assertEquals(ComboTransition.Kind.HIT, third.kind(), "hit 3 continues the active combo");
        assertEquals(3, third.hits());
        assertTrue(tracker.active());
        assertEquals(t(10), tracker.snapshot().activeSince(), "activeSince stays the second hit's tick");
    }

    @Test
    void gapExpiresAtExactlyMaxGapPlusOne() {
        // Two hits at 0 and 20 (gap 20 == maxGap) activate the chain (minHits 2);
        // a third at 40 (gap 20) continues it, then a tick at 61 (gap 21 > maxGap) ends it.
        ComboTracker tracker = tracker();
        tracker.onKnockShipped(A, t(0));
        // The second hit at 20 (gap 20 == maxGap, not expired) activates it.
        assertTrue(one(tracker.onKnockShipped(A, t(20))).started());
        assertTrue(tracker.active());
        // A third at 40 (gap 20) continues the active chain — a HIT, lastHit → 40.
        assertEquals(ComboTransition.Kind.HIT, one(tracker.onKnockShipped(A, t(40))).kind());
        assertTrue(tracker.active());

        // onTick at gap == maxGap (tick 60, lastHit 40) does NOT expire.
        assertEquals(ComboTransition.Kind.NONE, tracker.onTick(t(60), false, Double.NaN).kind());
        assertTrue(tracker.active());
        // onTick at gap == maxGap + 1 (tick 61) expires exactly here.
        ComboTransition expired = tracker.onTick(t(61), false, Double.NaN);
        assertTrue(expired.ended());
        assertEquals(ComboEndReason.EXPIRED, expired.reason());
        assertEquals(A, expired.attacker());
        assertFalse(tracker.active());
    }

    @Test
    void aHitAfterMaxGapPlusOneRestartsTheChain() {
        ComboTracker tracker = active(); // active, lastHit at tick 20
        // A same-attacker hit at 41 (gap 21 > 20) abandons the old active combo
        // (END EXPIRED) and opens a fresh chain at one hit (CHAIN_OPENED).
        List<ComboTransition> restart = tracker.onKnockShipped(A, t(41));
        assertEquals(2, restart.size());
        assertTrue(restart.get(0).ended());
        assertEquals(ComboEndReason.EXPIRED, restart.get(0).reason());
        assertEquals(ComboTransition.Kind.CHAIN_OPENED, restart.get(1).kind());
        assertEquals(A, restart.get(1).attacker());
        assertFalse(tracker.active(), "the fresh chain is developing, not active");
        // One more within the window re-activates (minHits 2: the second hit of the fresh chain).
        assertTrue(one(tracker.onKnockShipped(A, t(51))).started());
        assertTrue(tracker.active());
    }

    @Test
    void aDifferentAttackerRestartsTheChainOnThatAttacker() {
        ComboTracker tracker = active(); // A holds an active combo
        List<ComboTransition> switched = tracker.onKnockShipped(B, t(25));
        assertEquals(2, switched.size());
        assertTrue(switched.get(0).ended(), "A's combo ends when B takes over");
        assertEquals(A, switched.get(0).attacker());
        // The active end vocabulary is frozen (§5.5): a takeover still reports EXPIRED.
        assertEquals(ComboEndReason.EXPIRED, switched.get(0).reason());
        assertEquals(ComboTransition.Kind.CHAIN_OPENED, switched.get(1).kind());
        assertEquals(B, switched.get(1).attacker());
        assertFalse(tracker.active());
        // B now develops its own chain; its SECOND hit activates it (minHits 2).
        ComboTransition bActive = one(tracker.onKnockShipped(B, t(30)));
        assertTrue(bActive.started());
        assertEquals(B, bActive.attacker());
        assertEquals(B, tracker.snapshot().attackerId());
    }

    @Test
    void minHitsOneRestartEmitsTheBalancedEndThenStartPair() {
        // The min-hits:1 START-swallow fix: at minHits 1 the first hit of any chain
        // activates it, so a single hit that switches attacker BOTH ends the old active
        // combo AND activates the new one. onKnockShipped must return the balanced
        // END(A)-then-START(B) pair — the old code returned only the END, swallowing the
        // START (no ComboStartEvent, handicap never re-applied on the new combo).
        ComboTracker tracker = new ComboTracker(new ComboRules(1, 20, 10, 6.0));
        List<ComboTransition> firstHit = tracker.onKnockShipped(A, t(0));
        assertEquals(1, firstHit.size(), "minHits 1: the first hit activates immediately");
        assertTrue(firstHit.get(0).started());
        assertEquals(A, firstHit.get(0).attacker());

        List<ComboTransition> restart = tracker.onKnockShipped(B, t(1)); // switch, A active
        assertEquals(2, restart.size(), () -> "a min-hits:1 restart must emit END+START, got " + restart);
        assertTrue(restart.get(0).ended(), "the abandoned combo's END fires first");
        assertEquals(A, restart.get(0).attacker());
        assertTrue(restart.get(1).started(), "the new combo's START fires second — no longer swallowed");
        assertEquals(B, restart.get(1).attacker());
        assertTrue(tracker.active());
        assertEquals(B, tracker.snapshot().attackerId());
    }

    @Test
    void retaliationEndsAnActiveCombo() {
        ComboTracker tracker = active();
        ComboTransition retaliated = tracker.onOwnHitLanded(t(24));
        assertTrue(retaliated.ended());
        assertEquals(ComboEndReason.RETALIATION, retaliated.reason());
        assertEquals(A, retaliated.attacker());
        assertEquals(3, retaliated.hits());
        assertEquals(24, retaliated.tick().value());
        assertFalse(tracker.active());
        assertNull(tracker.snapshot().attackerId());
    }

    @Test
    void ownHitOnADevelopingChainAborts() {
        // A developing retaliation now surfaces CHAIN_ABORTED(RETALIATION) (was silent).
        ComboTracker tracker = tracker();
        tracker.onKnockShipped(A, t(0)); // one hit, still developing (minHits 2 not reached)
        ComboTransition abort = tracker.onOwnHitLanded(t(12));
        assertEquals(ComboTransition.Kind.CHAIN_ABORTED, abort.kind());
        assertEquals(ComboAbortReason.RETALIATION, abort.abortReason());
        assertEquals(A, abort.attacker());
        assertEquals(1, abort.hits());
        assertFalse(tracker.active());
        assertEquals(0, tracker.snapshot().hits());
    }

    @Test
    void groundedRunAtEraCadenceEndsOnTheTwelfthGroundedTick() {
        // The gap-aware grounded run (servo-lab 2.4.5): active() observed a 10-tick
        // cadence (gaps 10, 10 → EMA 10), so the effective threshold is the observed
        // cadence plus the ±2-tick jitter slack — max(10, min(20, 10 + 2)) = 12.
        ComboTracker tracker = active(); // active, lastHit tick 20, cadence EMA 10
        assertEquals(10.0, tracker.cadenceTicks(), 1.0e-9, "EMA of gaps 10, 10");
        // Eleven grounded ticks (21..31) are rhythm-legitimate skims.
        for (int tick = 21; tick <= 31; tick++) {
            assertEquals(ComboTransition.Kind.NONE, tracker.onTick(t(tick), true, Double.NaN).kind(),
                    "grounded tick " + tick + " is a survivable skim");
            assertTrue(tracker.active());
        }
        // The twelfth consecutive grounded tick (32, still inside the 20-tick gap
        // window) ends it — a real settle, not a between-hits stretch.
        ComboTransition grounded = tracker.onTick(t(32), true, Double.NaN);
        assertTrue(grounded.ended());
        assertEquals(ComboEndReason.GROUNDED, grounded.reason());
        assertFalse(tracker.active());
    }

    @Test
    void groundedRunScalesWithASlowObservedCadence() {
        // An 18-tick attacker (the lab's C cells): threshold = max(10, min(20, 18+2))
        // = 20 — the legitimate ~8-tick between-hit ground time (18t gap − ~10t
        // flight) can never end the combo mid-gap any more (the lab's 57% coverage
        // was GROUNDED deaths mid-gap), while gap expiry still owns a rhythm break.
        ComboTracker tracker = tracker();
        tracker.onKnockShipped(A, t(0));
        tracker.onKnockShipped(A, t(18)); // second hit → active, cadence seeds at 18
        tracker.onKnockShipped(A, t(36)); // EMA 0.3·18 + 0.7·18 = 18
        assertEquals(18.0, tracker.cadenceTicks(), 1.0e-9);
        // Nineteen grounded ticks (37..55) survive — the old fixed 10 died here.
        for (int tick = 37; tick <= 55; tick++) {
            assertEquals(ComboTransition.Kind.NONE, tracker.onTick(t(tick), true, Double.NaN).kind(),
                    "grounded tick " + tick + " is inside the observed rhythm");
            assertTrue(tracker.active());
        }
        // The twentieth (56 — gap 20 == maxGap, not yet expired) ends it GROUNDED:
        // the scaled threshold stays ordered under the gap clock.
        ComboTransition grounded = tracker.onTick(t(56), true, Double.NaN);
        assertTrue(grounded.ended());
        assertEquals(ComboEndReason.GROUNDED, grounded.reason());
    }

    @Test
    void groundedRunKeepsTheConfiguredFloorWhenCadenceIsUnknown() {
        // minHits 1 activates on the FIRST hit — no gap observed yet, so the
        // threshold is exactly the configured groundedRunTicks (the pre-round
        // behaviour, byte-identical).
        ComboTracker tracker = new ComboTracker(new ComboRules(1, 20, 10, 6.0));
        tracker.onKnockShipped(A, t(0));
        assertTrue(tracker.active());
        assertTrue(Double.isNaN(tracker.cadenceTicks()), "one hit — no cadence");
        for (int tick = 1; tick <= 9; tick++) {
            assertEquals(ComboTransition.Kind.NONE, tracker.onTick(t(tick), true, Double.NaN).kind());
        }
        ComboTransition grounded = tracker.onTick(t(10), true, Double.NaN);
        assertTrue(grounded.ended());
        assertEquals(ComboEndReason.GROUNDED, grounded.reason());
    }

    @Test
    void wideConfiguredThresholdStaysTheFloorUnderAFastCadence() {
        // The suite-staging shape (grounded-run 400 / max-gap 400, an 8-tick fake
        // chain): the CONFIGURED threshold is the floor — max(400, min(400, 8+2)) =
        // 400 — so a widened window is never narrowed by the observed cadence.
        ComboTracker tracker = new ComboTracker(new ComboRules(2, 400, 400, 6.0));
        tracker.onKnockShipped(A, t(0));
        tracker.onKnockShipped(A, t(8));
        assertTrue(tracker.active());
        assertEquals(8.0, tracker.cadenceTicks(), 1.0e-9);
        for (int tick = 9; tick <= 108; tick += 11) {
            assertEquals(ComboTransition.Kind.NONE, tracker.onTick(t(tick), true, Double.NaN).kind(),
                    "a stationary victim under the widened window never grounds out");
        }
        assertTrue(tracker.active());
    }

    @Test
    void cadenceEmaSeedsBlendsAndResetsWithTheChain() {
        // Seed on the first gap, α = 0.3 blend on the next: 0.3·18 + 0.7·14 = 15.2.
        ComboTracker tracker = tracker();
        tracker.onKnockShipped(A, t(0));
        tracker.onKnockShipped(A, t(14));
        assertEquals(14.0, tracker.cadenceTicks(), 1.0e-9, "the first gap seeds the EMA");
        tracker.onKnockShipped(A, t(32)); // gap 18
        assertEquals(15.2, tracker.cadenceTicks(), 1.0e-9, "0.3·18 + 0.7·14 = 15.2");
        // A chain reset (retaliation) forgets the rhythm with the chain.
        tracker.onOwnHitLanded(t(33));
        assertTrue(Double.isNaN(tracker.cadenceTicks()), "the cadence is per-chain state");
    }

    @Test
    void aFreshKnockResetsTheGroundedRun() {
        ComboTracker tracker = active();
        for (int tick = 21; tick <= 29; tick++) {
            tracker.onTick(t(tick), true, Double.NaN); // 9 grounded ticks, still active
        }
        // A fresh knock at tick 30 re-launches: grounded run resets to zero, so the
        // next nine grounded ticks again survive.
        tracker.onKnockShipped(A, t(30));
        assertTrue(tracker.active());
        for (int tick = 31; tick <= 39; tick++) {
            assertEquals(ComboTransition.Kind.NONE, tracker.onTick(t(tick), true, Double.NaN).kind());
        }
        assertTrue(tracker.active(), "the grounded run restarted after the fresh knock");
    }

    @Test
    void separationPastBlowoutEndsButAtTheThresholdSurvives() {
        ComboTracker atEdge = active();
        // Exactly at the threshold (6.0, not > 6.0) survives.
        assertEquals(ComboTransition.Kind.NONE, atEdge.onTick(t(21), false, 6.0).kind());
        assertTrue(atEdge.active());

        ComboTracker blown = active();
        ComboTransition blowout = blown.onTick(t(21), false, 6.0001);
        assertTrue(blowout.ended());
        assertEquals(ComboEndReason.BLOWOUT, blowout.reason());
        assertFalse(blown.active());
    }

    @Test
    void nanSeparationNeverEndsTheCombo() {
        ComboTracker tracker = active();
        // Many airborne ticks with unknown separation, all inside the gap window,
        // never end the combo (blowout is skipped for NaN).
        for (int tick = 21; tick <= 30; tick += 3) {
            assertEquals(ComboTransition.Kind.NONE, tracker.onTick(t(tick), false, Double.NaN).kind());
        }
        assertTrue(tracker.active());
    }

    @Test
    void explicitResetEndsWithTheGivenReason() {
        ComboTracker retired = active();
        ComboTransition retiredEnd = retired.reset(ComboEndReason.RETIRED, t(30));
        assertTrue(retiredEnd.ended());
        assertEquals(ComboEndReason.RETIRED, retiredEnd.reason());
        assertFalse(retired.active());

        ComboTracker disabled = active();
        assertEquals(ComboEndReason.DISABLED, disabled.reset(ComboEndReason.DISABLED, t(30)).reason());

        // Resetting an idle tracker is a silent no-op.
        assertEquals(ComboTransition.Kind.NONE, tracker().reset(ComboEndReason.DISABLED, t(0)).kind());
    }

    @Test
    void inactiveSnapshotHidesTheAttacker() {
        assertNull(ComboSnapshot.INACTIVE.attackerId());
        assertFalse(ComboSnapshot.INACTIVE.active());
        assertSame(TickStamp.NO_TICK, ComboSnapshot.INACTIVE.activeSince());
    }

    @Test
    void chainOpenedCarriesTheGapDeadline() {
        ComboTracker tracker = new ComboTracker(RULES);
        List<ComboTransition> t = tracker.onKnockShipped(A, t(10));
        assertEquals(1, t.size());
        assertEquals(ComboTransition.Kind.CHAIN_OPENED, t.get(0).kind());
        assertEquals(A, t.get(0).attacker());
        assertEquals(1, t.get(0).hits());
        assertEquals(10, t.get(0).tick().value());
        assertEquals(30, t.get(0).gapDeadline().value()); // 10 + maxGap 20
    }

    @Test
    void chainAdvancesBeforePromotionAtMinHitsThree() {
        ComboTracker tracker = new ComboTracker(new ComboRules(3, 20, 10, 6.0));
        tracker.onKnockShipped(A, t(0));
        List<ComboTransition> t2 = tracker.onKnockShipped(A, t(8));
        assertEquals(ComboTransition.Kind.CHAIN_ADVANCED, t2.get(0).kind());
        assertEquals(2, t2.get(0).hits());
        List<ComboTransition> t3 = tracker.onKnockShipped(A, t(16));
        assertEquals(ComboTransition.Kind.STARTED, t3.get(0).kind()); // promotion is STARTED alone — never HIT, never CHAIN
        assertEquals(3, t3.get(0).hits());
    }

    @Test
    void activeContinuationEmitsHitWithDeadline() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        tracker.onKnockShipped(A, t(10)); // STARTED (minHits 2)
        List<ComboTransition> t3 = tracker.onKnockShipped(A, t(20));
        assertEquals(ComboTransition.Kind.HIT, t3.get(0).kind());
        assertEquals(3, t3.get(0).hits());
        assertEquals(20, t3.get(0).tick().value());
        assertEquals(40, t3.get(0).gapDeadline().value());
    }

    @Test
    void developingSwitchAbortsSwitchedThenOpensTheNewChain() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        List<ComboTransition> t = tracker.onKnockShipped(B, t(5)); // in-window switch
        assertEquals(2, t.size());
        assertEquals(ComboTransition.Kind.CHAIN_ABORTED, t.get(0).kind());
        assertEquals(ComboAbortReason.SWITCHED, t.get(0).abortReason());
        assertEquals(A, t.get(0).attacker());
        assertEquals(1, t.get(0).hits());
        assertEquals(ComboTransition.Kind.CHAIN_OPENED, t.get(1).kind());
        assertEquals(B, t.get(1).attacker());
    }

    @Test
    void switchedWinsOverExpiredWhenBothCoincide() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        List<ComboTransition> t = tracker.onKnockShipped(B, t(50)); // gap lapsed AND attacker differs
        assertEquals(ComboAbortReason.SWITCHED, t.get(0).abortReason()); // §5.2 pin
    }

    @Test
    void developingGapExpiryAbortsExpiredOnTheSweep() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        assertEquals(ComboTransition.NONE, tracker.onTick(t(20), false, Double.NaN)); // at deadline: alive
        ComboTransition abort = tracker.onTick(t(21), false, Double.NaN);
        assertEquals(ComboTransition.Kind.CHAIN_ABORTED, abort.kind());
        assertEquals(ComboAbortReason.EXPIRED, abort.abortReason());
    }

    @Test
    void resetAbortsADevelopingChainWithTheMappedReason() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        ComboTransition abort = tracker.reset(ComboEndReason.DISABLED, t(3));
        assertEquals(ComboTransition.Kind.CHAIN_ABORTED, abort.kind());
        assertEquals(ComboAbortReason.DISABLED, abort.abortReason());
        assertEquals(ComboViewState.NONE, tracker.view());
    }

    @Test
    void endedCarriesFinalHitsAndTick() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        tracker.onKnockShipped(A, t(10));
        tracker.onKnockShipped(A, t(20));
        ComboTransition end = tracker.onOwnHitLanded(t(25));
        assertEquals(ComboTransition.Kind.ENDED, end.kind());
        assertEquals(3, end.hits());
        assertEquals(25, end.tick().value());
        assertEquals(ComboEndReason.RETALIATION, end.reason());
    }

    @Test
    void viewProgressesNoneDevelopingActiveAndBack() {
        ComboTracker tracker = new ComboTracker(RULES);
        assertTrue(tracker.view().none());
        tracker.onKnockShipped(A, t(10));
        ComboViewState developing = tracker.view();
        assertTrue(developing.developing());
        assertEquals(A, developing.attackerId());       // the read ComboSnapshot could never give
        assertEquals(1, developing.hits());
        assertEquals(10, developing.lastKnockTick().value());
        assertEquals(30, developing.gapDeadline().value());
        tracker.onKnockShipped(A, t(18));
        ComboViewState activeView = tracker.view();
        assertTrue(activeView.active());
        assertEquals(2, activeView.hits());
        assertEquals(38, activeView.gapDeadline().value()); // 18 + 20
        tracker.onOwnHitLanded(t(20));
        assertTrue(tracker.view().none());
    }

    @Test
    void balanceInvariantsHoldOverRandomizedSequences() {
        java.util.Random random = new java.util.Random(20260718L);
        for (int run = 0; run < 50; run++) {
            ComboTracker tracker = new ComboTracker(RULES);
            int opened = 0, openTerminals = 0, starts = 0, ends = 0;
            int tick = 0;
            java.util.List<ComboTransition> all = new java.util.ArrayList<>();
            for (int op = 0; op < 200; op++) {
                tick += random.nextInt(30);
                switch (random.nextInt(4)) {
                    case 0, 1 -> all.addAll(tracker.onKnockShipped(random.nextBoolean() ? A : B, t(tick)));
                    case 2 -> all.add(tracker.onTick(t(tick), random.nextBoolean(), Double.NaN));
                    case 3 -> all.add(tracker.onOwnHitLanded(t(tick)));
                }
            }
            all.add(tracker.reset(ComboEndReason.RETIRED, t(tick + 1)));
            for (ComboTransition t : all) {
                switch (t.kind()) {
                    case CHAIN_OPENED -> opened++;
                    case CHAIN_ABORTED -> openTerminals++;
                    case STARTED -> { starts++; openTerminals++; } // promotion terminates the developing sequence
                    case ENDED -> ends++;
                    default -> { }
                }
            }
            // Every developing sequence (except a promotion at minHits==1, impossible
            // here) opens with CHAIN_OPENED and terminates in exactly one of
            // STARTED / CHAIN_ABORTED; every STARTED gets exactly one ENDED.
            assertEquals(opened, openTerminals, "developing sequences unbalanced (run " + run + ")");
            assertEquals(starts, ends, "start/end unbalanced (run " + run + ")");
        }
    }
}

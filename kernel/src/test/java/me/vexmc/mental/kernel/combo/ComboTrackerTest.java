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
 * Hand-computed pins for the combo detector state machine (combo-hold §3.1).
 * Defaults: minHits 2, maxGapTicks 20, groundedRunTicks 10, blowoutBlocks 6.0.
 * Every boundary (activation on the second hit — the 2.4.5 retune, gap expiry at
 * exactly gap+1, attacker-switch restart, retaliation, grounded-run, blowout, NaN
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

    /** onKnockShipped returns 0+ transitions; the default-rules cases here never exceed one. */
    private static ComboTransition one(List<ComboTransition> transitions) {
        assertTrue(transitions.size() <= 1, () -> "expected at most one transition, got " + transitions);
        return transitions.isEmpty() ? ComboTransition.NONE : transitions.get(0);
    }

    @Test
    void activatesExactlyOnTheSecondHit() {
        // The 2.4.5 retune: minHits 2, so the SECOND shipped hit fires COMBO START.
        ComboTracker tracker = tracker();

        ComboTransition first = one(tracker.onKnockShipped(A, t(0)));
        assertEquals(ComboTransition.Kind.NONE, first.kind(), "hit 1 does not activate");
        assertFalse(tracker.active());
        assertNull(tracker.snapshot().attackerId(), "developing chain publishes no attacker");

        ComboTransition second = one(tracker.onKnockShipped(A, t(10)));
        assertTrue(second.started(), "hit 2 activates the combo");
        assertEquals(A, second.attacker());
        assertEquals(2, second.hits());
        assertTrue(tracker.active());
        assertEquals(A, tracker.snapshot().attackerId());
        assertEquals(t(10), tracker.snapshot().activeSince());

        // A third same-attacker hit continues the active chain — no fresh START.
        ComboTransition third = one(tracker.onKnockShipped(A, t(20)));
        assertEquals(ComboTransition.Kind.NONE, third.kind(), "hit 3 does not re-start an active combo");
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
        // A third at 40 (gap 20) continues the active chain — no transition, lastHit → 40.
        assertEquals(ComboTransition.Kind.NONE, one(tracker.onKnockShipped(A, t(40))).kind());
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
        // (END EXPIRED) and begins a fresh chain at one hit.
        ComboTransition restart = one(tracker.onKnockShipped(A, t(41)));
        assertTrue(restart.ended());
        assertEquals(ComboEndReason.EXPIRED, restart.reason());
        assertFalse(tracker.active(), "the fresh chain is developing, not active");
        // One more within the window re-activates (minHits 2: the second hit of the fresh chain).
        assertTrue(one(tracker.onKnockShipped(A, t(51))).started());
        assertTrue(tracker.active());
    }

    @Test
    void aDifferentAttackerRestartsTheChainOnThatAttacker() {
        ComboTracker tracker = active(); // A holds an active combo
        ComboTransition switched = one(tracker.onKnockShipped(B, t(25)));
        assertTrue(switched.ended(), "A's combo ends when B takes over");
        assertEquals(A, switched.attacker());
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
        assertFalse(tracker.active());
        assertNull(tracker.snapshot().attackerId());
    }

    @Test
    void ownHitOnADevelopingChainResetsSilently() {
        ComboTracker tracker = tracker();
        tracker.onKnockShipped(A, t(0)); // one hit, still developing (minHits 2 not reached)
        ComboTransition reset = tracker.onOwnHitLanded(t(12));
        assertEquals(ComboTransition.Kind.NONE, reset.kind(), "no START had fired — no END to balance");
        assertFalse(tracker.active());
        assertEquals(0, tracker.snapshot().hits());
    }

    @Test
    void groundedRunEndsExactlyOnTheTenthGroundedTick() {
        ComboTracker tracker = active(); // active, lastHit tick 20
        // Nine grounded ticks (21..29, gaps 1..9 <= maxGap) do NOT end the combo.
        for (int tick = 21; tick <= 29; tick++) {
            assertEquals(ComboTransition.Kind.NONE, tracker.onTick(t(tick), true, Double.NaN).kind(),
                    "grounded tick " + tick + " is a survivable skim");
            assertTrue(tracker.active());
        }
        // The tenth consecutive grounded tick (30, gap 10) ends it.
        ComboTransition grounded = tracker.onTick(t(30), true, Double.NaN);
        assertTrue(grounded.ended());
        assertEquals(ComboEndReason.GROUNDED, grounded.reason());
        assertFalse(tracker.active());
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
        ComboTransition retiredEnd = retired.reset(ComboEndReason.RETIRED);
        assertTrue(retiredEnd.ended());
        assertEquals(ComboEndReason.RETIRED, retiredEnd.reason());
        assertFalse(retired.active());

        ComboTracker disabled = active();
        assertEquals(ComboEndReason.DISABLED, disabled.reset(ComboEndReason.DISABLED).reason());

        // Resetting an idle tracker is a silent no-op.
        assertEquals(ComboTransition.Kind.NONE, tracker().reset(ComboEndReason.DISABLED).kind());
    }

    @Test
    void inactiveSnapshotHidesTheAttacker() {
        assertNull(ComboSnapshot.INACTIVE.attackerId());
        assertFalse(ComboSnapshot.INACTIVE.active());
        assertSame(TickStamp.NO_TICK, ComboSnapshot.INACTIVE.activeSince());
    }
}

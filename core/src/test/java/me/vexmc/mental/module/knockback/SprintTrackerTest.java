package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SprintTrackerTest {

    /** Milliseconds to the nanos the tracker speaks. */
    private static long t(long millis) {
        return millis * 1_000_000L;
    }

    @Test
    void freshnessArmsPeeksWithoutSpendingAndConsumesOnce() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();

        assertFalse(tracker.peekFresh(attacker));
        assertFalse(tracker.consumeFresh(attacker));

        tracker.arm(attacker);
        // The netty pre-send peeks — repeatedly, without spending.
        assertTrue(tracker.peekFresh(attacker));
        assertTrue(tracker.peekFresh(attacker));

        // The authoritative compute spends it exactly once.
        assertTrue(tracker.consumeFresh(attacker));
        assertFalse(tracker.peekFresh(attacker));
        assertFalse(tracker.consumeFresh(attacker));

        // Re-engaging sprint re-arms — the w-tap cycle.
        tracker.arm(attacker);
        assertTrue(tracker.consumeFresh(attacker));
    }

    @Test
    void clearDropsEveryAttacker() {
        SprintTracker tracker = new SprintTracker();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        tracker.consultWire(true);
        tracker.arm(first);
        tracker.arm(second);
        tracker.onWireSprint(first, true, t(0));

        tracker.clear();

        assertFalse(tracker.peekFresh(first));
        assertFalse(tracker.peekFresh(second));
        assertNull(tracker.peekWire(first), "the wire view is wiped too");
    }

    /* ------------------------------------------------------------------ */
    /*  The wire view (wtap-registration)                                  */
    /* ------------------------------------------------------------------ */

    @Test
    void wireRegistersTheSameTickWtap() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();
        tracker.consultWire(true);

        // Post-hit state: the bonus branch spent the sprint, and the
        // per-tick reconcile seeded the wire from the live flag.
        tracker.reconcileWire(attacker, false, t(0));

        // The fast w-tap, all inside one tick window: release, re-press —
        // the click's ATTACK packet reads next, on the same netty thread.
        tracker.onWireSprint(attacker, false, t(5));
        tracker.onWireSprint(attacker, true, t(12));

        SprintTracker.WireVerdict verdict = tracker.peekWire(attacker);
        assertNotNull(verdict);
        assertTrue(verdict.sprinting(),
                "the re-press beat the attack to the socket — it counts, era order");
        assertTrue(verdict.fresh(), "a re-engage arms wtap freshness at arrival");
    }

    @Test
    void wireDeniesTheSTapRelease() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();
        tracker.consultWire(true);

        tracker.reconcileWire(attacker, true, t(0)); // mid-chase, sprint held
        tracker.onWireSprint(attacker, false, t(5)); // S pressed: STOP wired

        SprintTracker.WireVerdict verdict = tracker.peekWire(attacker);
        assertNotNull(verdict);
        assertFalse(verdict.sprinting(),
                "a release that beat the attack denies the bonus, as the era denied it");
    }

    @Test
    void wireConsultGatesReadsNeverWrites() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();

        // Module off: the tap still writes (state stays warm), reads gate.
        tracker.onWireSprint(attacker, true, t(0));
        assertNull(tracker.peekWire(attacker));

        tracker.consultWire(true);
        SprintTracker.WireVerdict verdict = tracker.peekWire(attacker);
        assertNotNull(verdict);
        assertTrue(verdict.sprinting());

        // Never-seen players (synthetic, packetless) read null — callers
        // fall back to the tick-frozen snapshot.
        assertNull(tracker.peekWire(UUID.randomUUID()));
    }

    @Test
    void wireClearAndConsumeMirrorTheBonusBranch() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();
        tracker.consultWire(true);

        tracker.onWireSprint(attacker, true, t(0));

        // The bonus hit lands: the damage pass spends the freshness and
        // mirrors vanilla's in-attack sprint clear.
        tracker.consumeWireFresh(attacker);
        tracker.clearWireSprint(attacker, t(30));

        SprintTracker.WireVerdict after = tracker.peekWire(attacker);
        assertNotNull(after);
        assertFalse(after.sprinting(), "the era flag dropped inside the attack");
        assertFalse(after.fresh(), "freshness spent by the hit that used it");

        // The toggle-sprint re-arm: one fresh START re-opens both.
        tracker.onWireSprint(attacker, true, t(60));
        after = tracker.peekWire(attacker);
        assertNotNull(after);
        assertTrue(after.sprinting());
        assertTrue(after.fresh());
    }

    @Test
    void wireFreshnessSurvivesTheReleaseHalfOfTheTap() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();
        tracker.consultWire(true);

        tracker.onWireSprint(attacker, true, t(0));  // arms
        tracker.onWireSprint(attacker, false, t(5)); // the release never disarms

        SprintTracker.WireVerdict verdict = tracker.peekWire(attacker);
        assertNotNull(verdict);
        assertFalse(verdict.sprinting());
        assertTrue(verdict.fresh(), "armed freshness waits for the hit that spends it");
    }

    @Test
    void wireReconcileAdoptsLiveOnlyAfterSilence() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();
        tracker.consultWire(true);

        tracker.onWireSprint(attacker, false, t(1000));

        // Live disagrees (a plugin's setSprinting grant never crosses the
        // wire) — but the wire just spoke: inside the quiet window the wire
        // answer stands. That window IS the within-tick ordering the module
        // exists to preserve.
        tracker.reconcileWire(attacker, true, t(1000) + 149_000_000L);
        SprintTracker.WireVerdict held = tracker.peekWire(attacker);
        assertNotNull(held);
        assertFalse(held.sprinting());

        // Past the window the server's own flag wins the disagreement.
        tracker.reconcileWire(attacker, true, t(1000) + 151_000_000L);
        SprintTracker.WireVerdict adopted = tracker.peekWire(attacker);
        assertNotNull(adopted);
        assertTrue(adopted.sprinting());
    }

    @Test
    void attackVerdictStampsCarryFreshnessAndExpire() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();

        tracker.stampAttackVerdict(attacker, true, Boolean.TRUE, t(0));
        SprintTracker.AttackVerdict verdict =
                tracker.takeAttackVerdict(attacker, t(0) + 150_000_000L);
        assertNotNull(verdict, "the TTL boundary is inclusive");
        assertTrue(verdict.sprinting());
        assertEquals(Boolean.TRUE, verdict.fresh());
        assertNull(tracker.takeAttackVerdict(attacker, t(0)), "stamps are one-shot");

        // A stamp left by a snapshot-read registration carries no wire
        // freshness — the authoritative pass stays on its Bukkit ledger.
        tracker.stampAttackVerdict(attacker, true, null, t(0));
        SprintTracker.AttackVerdict snapshotStamp =
                tracker.takeAttackVerdict(attacker, t(10));
        assertNotNull(snapshotStamp);
        assertNull(snapshotStamp.fresh());

        // Beyond the faithful-client window the stamp is stale evidence.
        tracker.stampAttackVerdict(attacker, true, Boolean.TRUE, t(0));
        assertNull(tracker.takeAttackVerdict(attacker, t(0) + 151_000_000L));
    }

    @Test
    void attackVerdictTtlIsWidenedOnFoliaButUnchangedOnPaper() {
        // Paper keeps the era ~1-tick window; Folia widens to the pending window
        // so the netty->region damage latency cannot expire the verdict.
        assertEquals(150_000_000L, SprintTracker.attackStampTtlNanos(false));
        assertEquals(300_000_000L, SprintTracker.attackStampTtlNanos(true));

        // A Folia tracker still serves the verdict past Paper's 150ms boundary.
        SprintTracker folia = new SprintTracker(true);
        UUID attacker = UUID.randomUUID();
        folia.stampAttackVerdict(attacker, true, Boolean.TRUE, t(0));
        SprintTracker.AttackVerdict late = folia.takeAttackVerdict(attacker, t(0) + 250_000_000L);
        assertNotNull(late, "Folia keeps the verdict alive across the region-tick latency");
        assertEquals(t(0), late.nanos(), "the verdict carries its registration instant");

        // Paper drops the same stamp at 250ms.
        SprintTracker paper = new SprintTracker(false);
        paper.stampAttackVerdict(attacker, true, Boolean.TRUE, t(0));
        assertNull(paper.takeAttackVerdict(attacker, t(0) + 250_000_000L));
    }

    @Test
    void clearWireSprintNeverOverwritesANewerWirePress() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();
        tracker.consultWire(true);

        // A re-press arrives on the wire (hit-2's sprint), THEN hit-1's deferred
        // clear runs — but stamped with hit-1's earlier registration nanos.
        tracker.onWireSprint(attacker, true, t(20));
        tracker.clearWireSprint(attacker, t(10));

        SprintTracker.WireVerdict verdict = tracker.peekWire(attacker);
        assertNotNull(verdict);
        assertTrue(verdict.sprinting(),
                "a clear older than the latest wire press must not win — the re-press is newer truth");

        // A clear newer than the last wire write still clears (the common case).
        tracker.clearWireSprint(attacker, t(30));
        SprintTracker.WireVerdict cleared = tracker.peekWire(attacker);
        assertNotNull(cleared);
        assertFalse(cleared.sprinting());
    }

    @Test
    void reconcileDoesNotResurrectSprintWhileAClearIsPending() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();
        tracker.consultWire(true);

        tracker.onWireSprint(attacker, true, t(0));
        tracker.markClearPending(attacker, t(5));
        tracker.clearWireSprint(attacker, t(5)); // wire now false

        // The deferred setSprinting(false) has not landed: the live flag is
        // stale-high. Past the quiet window the reconcile would normally adopt
        // it — but the pending clear blocks that resurrection.
        tracker.reconcileWire(attacker, true, t(5) + 200_000_000L);
        SprintTracker.WireVerdict held = tracker.peekWire(attacker);
        assertNotNull(held);
        assertFalse(held.sprinting(), "a pending clear blocks live-sprint readoption");

        // Once the deferred clear resolves, a genuine later sprint reconciles in.
        tracker.resolveClearPending(attacker);
        tracker.reconcileWire(attacker, true, t(5) + 400_000_000L);
        SprintTracker.WireVerdict adopted = tracker.peekWire(attacker);
        assertNotNull(adopted);
        assertTrue(adopted.sprinting());
    }

    /* ------------------------------------------------------------------ */
    /*  Block-hitting sprint reset (1.7/1.8)                                */
    /* ------------------------------------------------------------------ */

    @Test
    void clientSprintFlagTracksRawStartStopAndSurvivesClears() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();

        assertFalse(tracker.isClientSprinting(attacker));
        tracker.onWireSprint(attacker, true, t(0));
        assertTrue(tracker.isClientSprinting(attacker));

        // The post-hit clears that wipe the wire view must NOT touch the raw
        // client flag — it is the only signal that the client is still
        // sprinting after Mental's own setSprinting(false).
        tracker.clearWireSprint(attacker, t(5));
        assertTrue(tracker.isClientSprinting(attacker), "clearWireSprint leaves the raw flag");

        tracker.onWireSprint(attacker, false, t(10)); // a real STOP clears it
        assertFalse(tracker.isClientSprinting(attacker));
    }

    @Test
    void armSprintResetReEngagesAfterAPostHitClear() {
        SprintTracker tracker = new SprintTracker();
        UUID attacker = UUID.randomUUID();
        tracker.consultWire(true);

        // Sprint, then a sprint hit clears the wire (Mental mirrors vanilla's
        // post-hit setSprinting(false)); a held-sprint follow-up reads plain.
        tracker.onWireSprint(attacker, true, t(0));
        tracker.clearWireSprint(attacker, t(5));
        SprintTracker.WireVerdict afterHit = tracker.peekWire(attacker);
        assertNotNull(afterHit);
        assertFalse(afterHit.sprinting(), "cleared after the sprint hit");

        // Block-hitting re-engages: the next hit sees sprinting + fresh again,
        // exactly as the era's block→re-sprint did.
        tracker.armSprintReset(attacker, t(10));
        SprintTracker.WireVerdict afterBlock = tracker.peekWire(attacker);
        assertNotNull(afterBlock);
        assertTrue(afterBlock.sprinting(), "block re-armed the sprint flag");
        assertTrue(afterBlock.fresh(), "block re-armed freshness too");
        assertTrue(tracker.consumeFresh(attacker), "the Bukkit freshness ledger is armed as well");
    }
}

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
}

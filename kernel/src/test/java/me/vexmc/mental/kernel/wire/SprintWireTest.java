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
}

package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import org.junit.jupiter.api.Test;

/**
 * Pins the 2.6.0 resilience layer: the SERVER_FALL latch failsafe (a published
 * server flag falling while the spend latch is open proves a client STOP
 * reached vanilla — the latch closes, degrading a missed gesture to ordinary
 * adoption instead of bricking the session), the starvation detector (a consume
 * on a ledger that never saw a START — the dead-feed alarm), and the
 * ADOPT_BLOCKED trail dedup (once per latch episode; reconcile runs per
 * movement packet and must not flood the ring).
 */
class InputLedgerFailsafeTest {

    private static final int QUIET = 3;

    private static final class Clock implements TickClock {
        int tick;

        @Override
        public TickStamp current() {
            return new TickStamp(tick);
        }
    }

    @Test
    void aServerFallingEdgeClosesTheLatchWithoutArming() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        clock.tick = 0;
        wire.onSprintStart();
        wire.reconcile(true, new TickStamp(0), QUIET);            // lastServerSprinting = true
        SprintVerdict hit = wire.verdictAt(new TickStamp(1));
        clock.tick = 2;
        wire.onServerClear(hit.wireSeq());                        // the latch opens

        // The environment ate the client's STOP (a proxy/translation layer), but
        // vanilla saw it: the published flag falls. The failsafe closes the latch —
        // WITHOUT arming anything (the wire view stays plain).
        clock.tick = 5;
        wire.reconcile(false, new TickStamp(5), QUIET);
        assertFalse(wire.verdictAt(new TickStamp(5)).sprinting(), "the failsafe never arms");
        assertTrue(wire.trail().stream().anyMatch(e -> e.kind() == InputEvent.Kind.SERVER_FALL),
                "the fall is named in the trail");

        // With the latch closed, a later server grant adopts the ORDINARY way
        // (quiet window, not fresh) — the session is not bricked.
        clock.tick = 10;
        wire.reconcile(true, new TickStamp(10), QUIET);
        SprintVerdict adopted = wire.verdictAt(new TickStamp(10));
        assertTrue(adopted.sprinting(), "ordinary adoption resumed after the failsafe");
        assertEquals(Boolean.FALSE, adopted.fresh(), "an adopt is never fresh");
    }

    @Test
    void theFallingEdgeIsInertWhileNoConsumeIsOutstanding() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        clock.tick = 0;
        wire.onSprintStart();
        wire.reconcile(true, new TickStamp(0), QUIET);
        // No consume: the flag falling is just drift the ordinary adopt handles.
        clock.tick = 5;
        wire.reconcile(false, new TickStamp(5), QUIET);           // disagree + quiet ⇒ adopt-FALSE
        assertFalse(wire.verdictAt(new TickStamp(5)).sprinting());
        assertFalse(wire.trail().stream().anyMatch(e -> e.kind() == InputEvent.Kind.SERVER_FALL),
                "no latch, no failsafe event — the adopt owns the transition");
    }

    @Test
    void aConsumeOnAStartlessLedgerReadsStarvedUntilAStartProvesTheFeed() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        // The dead-feed shape: the tap never decodes a START, but the server
        // tracks sprint — the seed makes hit 1 work, the consume spends it.
        clock.tick = 0;
        wire.reconcile(true, new TickStamp(0), QUIET);            // seed from the live flag
        SprintVerdict hit = wire.verdictAt(new TickStamp(1));
        assertTrue(hit.sprinting(), "the seeded first hit works even on a dead feed");
        clock.tick = 2;
        wire.onServerClear(hit.wireSeq());
        assertTrue(wire.starved(), "a consume with zero STARTs ever seen = the dead-feed alarm");

        // The first genuine START proves the feed alive and clears the alarm.
        clock.tick = 3;
        wire.onSprintStart();
        assertFalse(wire.starved());
    }

    @Test
    void aLiveFeedNeverReadsStarved() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        SprintVerdict hit = wire.verdictAt(new TickStamp(0));
        clock.tick = 1;
        wire.onServerClear(hit.wireSeq());
        assertFalse(wire.starved(), "the START preceded the consume — the feed is alive");
    }

    @Test
    void adoptBlockedIsNotedOncePerLatchEpisode() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        clock.tick = 0;
        wire.onSprintStart();
        SprintVerdict hit = wire.verdictAt(new TickStamp(1));
        clock.tick = 2;
        wire.onServerClear(hit.wireSeq());                        // latch opens (episode 1)

        // A held-W client: per-movement-packet reconciles against the stale-high
        // flag, all latch-blocked — the trail notes the episode ONCE.
        for (int t = 6; t <= 20; t++) {
            clock.tick = t;
            wire.reconcile(true, new TickStamp(t), QUIET);
        }
        assertEquals(1, wire.trail().stream()
                        .filter(e -> e.kind() == InputEvent.Kind.ADOPT_BLOCKED).count(),
                "one ADOPT_BLOCKED per episode — no per-packet ring flood");
        assertFalse(wire.verdictAt(new TickStamp(20)).sprinting(), "and the block itself held");

        // A new episode (gesture → new consume) notes again.
        clock.tick = 21;
        wire.onSprintStop();
        wire.onSprintStart();
        SprintVerdict hit2 = wire.verdictAt(new TickStamp(21));
        clock.tick = 22;
        wire.onServerClear(hit2.wireSeq());                       // episode 2
        for (int t = 26; t <= 32; t++) {
            clock.tick = t;
            wire.reconcile(true, new TickStamp(t), QUIET);
        }
        assertEquals(2, wire.trail().stream()
                        .filter(e -> e.kind() == InputEvent.Kind.ADOPT_BLOCKED).count(),
                "a fresh latch episode earns a fresh note");
    }
}

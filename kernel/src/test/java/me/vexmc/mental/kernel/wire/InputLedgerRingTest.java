package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import org.junit.jupiter.api.Test;

/**
 * Pins the diagnostic ring (spec §1.3/§1.8): every applied write records one
 * event in arrival order with a monotonic {@code eventSeq}, observations record
 * without touching the gesture machine, no-opped transitions record NOTHING
 * (a no-op consume must not look like a spend in the trail), and the ring is
 * bounded (oldest dropped) — it is evidence, never a verdict input.
 */
class InputLedgerRingTest {

    private static final class Clock implements TickClock {
        int tick;

        @Override
        public TickStamp current() {
            return new TickStamp(tick);
        }
    }

    @Test
    void writesRecordInArrivalOrderWithMonotonicEventSeq() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        wire.onKeyIntent(true, 0x41);   // forward + sprint bits
        wire.onSprintStop();
        wire.onAttack();
        wire.onWindowClose();

        List<InputEvent> trail = wire.trail();
        assertEquals(5, trail.size());
        assertEquals(InputEvent.Kind.SPRINT_START, trail.get(0).kind());
        assertEquals(InputEvent.Kind.KEY_INPUT, trail.get(1).kind());
        assertEquals(0x41, trail.get(1).bits(), "the raw PLAYER_INPUT byte rides the event");
        assertEquals(InputEvent.Kind.SPRINT_STOP, trail.get(2).kind());
        assertEquals(InputEvent.Kind.ATTACK, trail.get(3).kind());
        assertEquals(InputEvent.Kind.WINDOW_CLOSE, trail.get(4).kind());
        for (int i = 1; i < trail.size(); i++) {
            assertTrue(trail.get(i).eventSeq() > trail.get(i - 1).eventSeq(),
                    "eventSeq is monotonic across kinds");
        }
    }

    @Test
    void aNoOppedConsumeRecordsNothingAndAnAppliedOneRecordsOnce() {
        Clock clock = new Clock();
        clock.tick = 5;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();                 // seq 1
        wire.onSprintStop();                  // seq 2 — a gesture after the (upcoming) peek's seq
        wire.onServerClear(1L);               // 2 > 1 ⇒ no-op: the trail must NOT show a CONSUME
        List<InputEvent> afterNoOp = wire.trail();
        assertEquals(2, afterNoOp.size(), "a no-opped clear rings nothing");

        wire.onServerClear(2L);               // 2 <= 2 ⇒ applies
        List<InputEvent> afterApplied = wire.trail();
        assertEquals(3, afterApplied.size());
        assertEquals(InputEvent.Kind.CONSUME, afterApplied.get(2).kind());
    }

    @Test
    void theRingIsBoundedAndDropsTheOldest() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart(); // the event that must fall off the front
        for (int i = 0; i < InputLedger.RING_CAPACITY; i++) {
            wire.onKeyIntent((i & 1) == 0, i & 0x7F);
        }
        List<InputEvent> trail = wire.trail();
        assertEquals(InputLedger.RING_CAPACITY, trail.size(), "capacity holds");
        assertEquals(InputEvent.Kind.KEY_INPUT, trail.get(0).kind(),
                "the oldest event (the START) was evicted");
        assertEquals(InputEvent.Kind.KEY_INPUT, trail.get(trail.size() - 1).kind());
    }

    @Test
    void ticksStampIntoEventsFromTheClock() {
        Clock clock = new Clock();
        clock.tick = 7;
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        clock.tick = 9;
        wire.onAttack();

        List<InputEvent> trail = wire.trail();
        assertEquals(new TickStamp(7), trail.get(0).tick());
        assertEquals(new TickStamp(9), trail.get(1).tick());
    }

    @Test
    void aReleaseRecordsEvidenceWithoutTouchingTheMachine() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        wire.onSprintStart();
        long seqBefore = wire.verdictAt(new TickStamp(0)).wireSeq();
        wire.onBlockReleased(); // eating/bow releases ride the same lane
        assertEquals(seqBefore, wire.verdictAt(new TickStamp(0)).wireSeq(),
                "a release is an observation — the gesture seq is untouched");
        List<InputEvent> trail = wire.trail();
        assertEquals(InputEvent.Kind.RELEASE_USE_ITEM, trail.get(trail.size() - 1).kind());
    }
}

package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.kernel.model.ResetModel;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import org.junit.jupiter.api.Test;

/**
 * Pins for the reset-model wire (servo dynamic-chase spec, 2026-07-07). The phase is
 * the ticks since the attacker's last sprint (re-)engage: a w-tap re-press resets it,
 * a held sprint lets it grow (the ramp decays to steady speed), a sword block is a
 * reset point that raises the block-slow flag. UNKNOWN until a first (re-)engage.
 */
class ResetModelWireTest {

    /** A settable clock so the feed methods stamp a chosen tick. */
    private static final class MutableClock implements TickClock {
        int tick;

        @Override
        public TickStamp current() {
            return new TickStamp(tick);
        }
    }

    @Test
    void unknownBeforeAnyReengageSignal() {
        MutableClock clock = new MutableClock();
        ResetModelWire wire = new ResetModelWire(clock);
        assertEquals(ResetModel.UNKNOWN, wire.modelAt(new TickStamp(5)));
        clock.tick = 5;
        wire.onSprintStop(); // a lone STOP still gives no phase (no re-engage tick)
        assertFalse(wire.modelAt(new TickStamp(5)).known());
    }

    @Test
    void sprintStartSetsPhaseZeroThenGrows() {
        MutableClock clock = new MutableClock();
        ResetModelWire wire = new ResetModelWire(clock);
        clock.tick = 10;
        wire.onSprintStart();
        ResetModel at10 = wire.modelAt(new TickStamp(10));
        assertTrue(at10.known());
        assertTrue(at10.sprinting());
        assertEquals(0, at10.phaseTicks());
        assertEquals(5, wire.modelAt(new TickStamp(15)).phaseTicks(), "the ramp phase grows as sprint is held");
    }

    @Test
    void wTapRepressResetsThePhase() {
        MutableClock clock = new MutableClock();
        ResetModelWire wire = new ResetModelWire(clock);
        clock.tick = 10;
        wire.onSprintStart();
        assertEquals(10, wire.modelAt(new TickStamp(20)).phaseTicks());
        wire.onSprintStop();       // the tap drops sprint
        clock.tick = 20;
        wire.onSprintStart();      // ...and re-presses — the ramp restarts
        assertEquals(2, wire.modelAt(new TickStamp(22)).phaseTicks(), "a re-press resets the phase to the new engage");
    }

    @Test
    void stopDropsSprintingButKeepsThePhase() {
        MutableClock clock = new MutableClock();
        ResetModelWire wire = new ResetModelWire(clock);
        clock.tick = 10;
        wire.onSprintStart();
        wire.onSprintStop();
        ResetModel at15 = wire.modelAt(new TickStamp(15));
        assertFalse(at15.sprinting());
        assertEquals(5, at15.phaseTicks(), "the reset tick survives a stop");
    }

    @Test
    void blockRaiseMarksBlockingAndResetsThePhase() {
        MutableClock clock = new MutableClock();
        ResetModelWire wire = new ResetModelWire(clock);
        clock.tick = 10;
        wire.onSprintStart();
        clock.tick = 30;
        wire.onBlockRaise(); // a blockhit re-engage — a fresh reset point
        ResetModel at32 = wire.modelAt(new TickStamp(32));
        assertTrue(at32.blocking());
        assertEquals(2, at32.phaseTicks());
    }

    @Test
    void blockReleaseClearsBlocking() {
        MutableClock clock = new MutableClock();
        ResetModelWire wire = new ResetModelWire(clock);
        clock.tick = 30;
        wire.onBlockRaise();
        wire.onBlockRelease();
        assertFalse(wire.modelAt(new TickStamp(32)).blocking());
    }
}

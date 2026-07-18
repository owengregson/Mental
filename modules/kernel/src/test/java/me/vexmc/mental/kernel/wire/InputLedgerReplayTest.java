package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import org.junit.jupiter.api.Test;

/**
 * The replay pins — the 2.5.5 w-tap diagnosis harness scenarios made permanent
 * (the machine was proven correct against real packet cadences; these keep it
 * that way), plus the two contracts the 2026-07-11 investigation bytecode-pinned:
 * the era same-tick ATTACK-before-START order (1.8.9 {@code Minecraft.runTick}
 * clicks before {@code updateEntities}; 1.21.11 {@code handleKeybinds} before
 * the input diff — a same-tick w-tap+click ships plain, era truth on every
 * client generation) and the deferred-consume sliver the seq guard carries.
 * Cadences model a toggle-sprint client (the level-triggered {@code
 * keyPresses.sprint()} re-arm: W-release → STOP same tick, W-re-press → START
 * same tick — bytecode-verified 2026-07-11).
 */
class InputLedgerReplayTest {

    private static final int QUIET = 3; // the rim's SPRINT_WIRE_QUIET_TICKS

    private static final class Clock implements TickClock {
        int tick;

        @Override
        public TickStamp current() {
            return new TickStamp(tick);
        }
    }

    /** S1: the full w-tap trade — engage, hit 1 consumes, tap re-arms, hit 2 fresh. */
    @Test
    void s1_wtapAtOffsetReArmsThroughTheDeferredConsume() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        clock.tick = 0;
        wire.onSprintStart();                                     // engage
        wire.reconcile(true, new TickStamp(0), QUIET);            // movement packets flow
        SprintVerdict hit1 = wire.verdictAt(new TickStamp(2));    // ATTACK at tick 2
        assertTrue(hit1.sprinting());

        clock.tick = 3;
        wire.reconcile(true, new TickStamp(3), QUIET);
        wire.onServerClear(hit1.wireSeq());                       // the deferred EDBEE consume (t+1)
        assertFalse(wire.verdictAt(new TickStamp(3)).sprinting(), "consumed");

        clock.tick = 6;                                           // the w-tap: release...
        wire.onSprintStop();
        wire.reconcile(true, new TickStamp(6), QUIET);            // server flag lags the STOP
        clock.tick = 8;                                           // ...re-press
        wire.onSprintStart();
        wire.reconcile(true, new TickStamp(8), QUIET);

        SprintVerdict hit2 = wire.verdictAt(new TickStamp(10));   // the next ATTACK
        assertTrue(hit2.sprinting(), "the re-gesture re-armed");
        assertEquals(Boolean.TRUE, hit2.fresh(), "and it is fresh — the w-tap extra applies");
    }

    /**
     * S2 + the era order pin: the same-tick w-tap+click crosses STOP, ATTACK,
     * START in that arrival order (clicks precede the sprint diff inside the
     * client tick — era-identical on 1.8.9 and 1.21.11), so THAT hit reads
     * plain and the trailing START arms the NEXT one. Pure arrival order; no
     * reordering rule may ever "fix" this hit.
     */
    @Test
    void s2_sameTickAttackBeforeStartShipsPlainAndArmsTheNextHit() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        clock.tick = 0;
        wire.onSprintStart();
        SprintVerdict hit1 = wire.verdictAt(new TickStamp(1));
        clock.tick = 2;
        wire.onServerClear(hit1.wireSeq());                       // engagement spent

        clock.tick = 5;                                           // the same-tick tap+click:
        wire.onSprintStop();                                      //   STOP (the release, earlier tick or same)
        SprintVerdict tapHit = wire.verdictAt(new TickStamp(5));  //   ATTACK — crosses BEFORE the START
        wire.onSprintStart();                                     //   START — trails inside the same tick

        assertFalse(tapHit.sprinting(),
                "the same-tick click reads plain — ATTACK crossed before the START (era truth)");

        // The deferred consume for that plain hit never fires (no bonus), and the
        // trailing START armed the NEXT hit.
        SprintVerdict next = wire.verdictAt(new TickStamp(6));
        assertTrue(next.sprinting());
        assertEquals(Boolean.TRUE, next.fresh());
    }

    /** S3: START-then-ATTACK in one flush registers fresh — the fast half of the tap. */
    @Test
    void s3_sameTickStartBeforeAttackRegistersFresh() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        clock.tick = 0;
        wire.onSprintStart();
        SprintVerdict hit1 = wire.verdictAt(new TickStamp(1));
        clock.tick = 2;
        wire.onServerClear(hit1.wireSeq());

        clock.tick = 5;                                           // one flush: STOP, START, ATTACK
        wire.onSprintStop();
        wire.onSprintStart();
        SprintVerdict tapHit = wire.verdictAt(new TickStamp(5));
        assertTrue(tapHit.sprinting(), "arrival order: the START crossed first, the hit is fresh");
        assertEquals(Boolean.TRUE, tapHit.fresh());

        // And the deferred consume belongs to THIS hit's peek — it applies (no
        // later gesture) and spends exactly this engagement.
        clock.tick = 6;
        wire.onServerClear(tapHit.wireSeq());
        assertFalse(wire.verdictAt(new TickStamp(6)).sprinting());
    }

    /** S4: held-W, no gesture — one engagement, one knock; reconciles never resurrect. */
    @Test
    void s4_heldWNoGestureStaysConsumedThroughMovementReconciles() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        clock.tick = 0;
        wire.onSprintStart();
        SprintVerdict hit1 = wire.verdictAt(new TickStamp(1));
        clock.tick = 2;
        wire.onServerClear(hit1.wireSeq());

        // A held-W modern client keeps moving: one reconcile per movement packet,
        // server flag stale-high forever (its STOP lives in the cancelled ATTACK).
        for (int t = 3; t <= 30; t++) {
            clock.tick = t;
            wire.reconcile(true, new TickStamp(t), QUIET);
        }
        SprintVerdict late = wire.verdictAt(new TickStamp(30));
        assertFalse(late.sprinting(), "no gesture, no re-arm — ever");
        assertEquals(Boolean.FALSE, late.fresh());
    }

    /**
     * S5: PLAYER_INPUT noise in the deferred-clear window never vetoes the
     * consume — the eventSeq/seq partition (the 2.5.1 consume-veto lesson,
     * structurally encoded: observations ring, gestures sequence).
     */
    @Test
    void s5_playerInputNoiseNeverVetoesTheDeferredConsume() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        clock.tick = 0;
        wire.onSprintStart();
        SprintVerdict hit = wire.verdictAt(new TickStamp(1));

        // A bunny-hopping strafer: PLAYER_INPUT edges every tick of the window.
        clock.tick = 1;
        wire.onKeyIntent(true, 0x51);  // forward + jump + sprint
        wire.onKeyIntent(true, 0x45);  // forward + left + sprint
        clock.tick = 2;
        wire.onKeyIntent(true, 0x49);  // forward + right + sprint
        wire.onServerClear(hit.wireSeq());
        assertFalse(wire.verdictAt(new TickStamp(2)).sprinting(),
                "the consume applied — key samples are observations, not gestures");
        assertEquals(3, wire.trail().stream()
                        .filter(e -> e.kind() == InputEvent.Kind.KEY_INPUT).count(),
                "and every sample still rides the trail as evidence");
    }

    /**
     * The deferred-consume sliver, pinned as DOCUMENTED behavior: a second hit
     * registering before the first hit's consume lands still reads the armed
     * engagement (era vanilla's synchronous in-attack clear had no such window;
     * Mental's deferred EDBEE does). The consume then spends the engagement for
     * both — the third hit is plain.
     */
    @Test
    void theDeferredConsumeSliverIsBoundedToTheInFlightWindow() {
        Clock clock = new Clock();
        InputLedger wire = new InputLedger(clock);

        clock.tick = 0;
        wire.onSprintStart();
        SprintVerdict hit1 = wire.verdictAt(new TickStamp(1));    // ATTACK 1
        SprintVerdict hit2 = wire.verdictAt(new TickStamp(1));    // ATTACK 2, same window
        assertTrue(hit1.sprinting());
        assertTrue(hit2.sprinting(), "the sliver: hit 2 peeked before hit 1's consume landed");

        clock.tick = 2;
        wire.onServerClear(hit1.wireSeq());                       // hit 1's consume applies
        wire.onServerClear(hit2.wireSeq());                       // hit 2's consume: seq unchanged ⇒ applies too (idempotent spend)
        SprintVerdict hit3 = wire.verdictAt(new TickStamp(3));
        assertFalse(hit3.sprinting(), "past the window the engagement is spent — the sliver is bounded");
    }
}

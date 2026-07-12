package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.kernel.fx.IndicatorPlacement;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-victim damage-window state machine — ONE indicator per victim per
 * invulnerability window, carrying the final ROLLED amount. A FRESH hit opens and
 * HOLDS a marker; mid-window UPGRADE deltas FOLD into it (summed amount, OR-ed
 * crit) while it is held, or BUMP the live stand in place once it has shipped; a
 * delta never spawns its own stand. The 2.5.3 same-tick plugin-bonus aggregation
 * survives as one transition. Pure value state (no Bukkit / PacketEvents), so it
 * is asserted directly here with simulated ticks — placement is a frozen value,
 * shipping (the {@link IndicatorWindowBook.Ship} commands) is the listener's job.
 */
class IndicatorWindowBookTest {

    private static final int HOLD = 3;
    private static final int HORIZON = 20;

    private final UUID victimA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID victimB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final UUID attackerX = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private final UUID attackerY = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private final IndicatorPlacement.Spawn spawn = new IndicatorPlacement.Spawn(1.5, 65.2, -3.0, 0.75);

    private IndicatorWindowBook.FreshResult openFresh(
            IndicatorWindowBook book, UUID victim, UUID attacker, long tick, double dmg, boolean crit) {
        return book.onFresh(victim, attacker, tick, dmg, crit, HOLD, HORIZON, spawn, 64.0);
    }

    /* --------------------------- fresh: open + hold --------------------------- */

    @Test
    void freshHitOpensAndHoldsThenShipsAtTheDeadline() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        IndicatorWindowBook.FreshResult opened = openFresh(book, victimA, attackerX, 100L, 3.0, false);
        assertEquals(IndicatorWindowBook.FreshKind.OPEN_HELD, opened.kind());
        assertNull(opened.priorShip());
        assertNull(opened.action());

        assertNull(book.due(victimA, 102L), "held before the deadline");
        IndicatorWindowBook.Ship ship = book.due(victimA, 103L);
        assertNotNull(ship, "the hold elapsed — the marker ships");
        assertEquals(3.0, ship.total(), 1.0e-9);
        assertEquals(-1, ship.priorEntityId(), "a first ship is a fresh spawn");
        assertEquals(attackerX, ship.attackerId());
        assertFalse(ship.crit());
    }

    @Test
    void zeroHoldShipsTheSameTick() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        IndicatorWindowBook.FreshResult opened =
                book.onFresh(victimA, attackerX, 100L, 4.0, false, 0, HORIZON, spawn, 64.0);
        assertEquals(IndicatorWindowBook.FreshKind.OPEN_SHIP, opened.kind());
        assertNotNull(opened.action());
        assertEquals(-1, opened.action().priorEntityId());
        assertEquals(4.0, opened.action().total(), 1.0e-9);
    }

    /* --------------------------- delta: fold + bump --------------------------- */

    @Test
    void deltaFoldsIntoTheHeldWindowAndShipsTheSum() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        openFresh(book, victimA, attackerX, 100L, 7.0, false);
        IndicatorWindowBook.DeltaResult delta = book.onDelta(victimA, attackerX, 101L, 3.0, false);
        assertEquals(IndicatorWindowBook.DeltaKind.FOLD_HELD, delta.kind());
        assertNull(delta.action(), "a held fold draws nothing yet");

        IndicatorWindowBook.Ship ship = book.due(victimA, 103L);
        assertNotNull(ship);
        assertEquals(10.0, ship.total(), 1.0e-9, "the one marker carries the rolled total");
    }

    @Test
    void deltaBumpsAnAlreadyShippedWindowInPlace() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        openFresh(book, victimA, attackerX, 100L, 7.0, false);
        assertNotNull(book.due(victimA, 103L), "the hold elapsed — the marker ships");
        book.shipped(victimA, 42); // the listener records the live stand's entity id

        IndicatorWindowBook.DeltaResult delta = book.onDelta(victimA, attackerX, 105L, 2.0, false);
        assertEquals(IndicatorWindowBook.DeltaKind.BUMP, delta.kind());
        assertNotNull(delta.action());
        assertEquals(42, delta.action().priorEntityId(), "the bump replaces the live stand");
        assertEquals(9.0, delta.action().total(), 1.0e-9);
    }

    @Test
    void aShippedWindowWhoseStandNeverDrewIsUntracked() {
        // The one ship attempt failed (no client / no entity id → -1). A later delta
        // has nothing to bump, so it reads UNTRACKED (the caller ships it once).
        IndicatorWindowBook book = new IndicatorWindowBook();
        openFresh(book, victimA, attackerX, 100L, 7.0, false);
        book.due(victimA, 103L);
        book.shipped(victimA, -1); // the flush drew nothing (clientless)
        assertEquals(IndicatorWindowBook.DeltaKind.UNTRACKED,
                book.onDelta(victimA, attackerX, 104L, 3.0, false).kind());
    }

    /* --------------------------- crit OR across the fold --------------------------- */

    @Test
    void critOrsAcrossTheFold() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        openFresh(book, victimA, attackerX, 100L, 3.0, true); // the opener is a crit
        book.onDelta(victimA, attackerX, 101L, 1.0, false);
        assertTrue(book.due(victimA, 103L).crit(), "the opener's crit sticks through the fold");

        IndicatorWindowBook other = new IndicatorWindowBook();
        openFresh(other, victimB, attackerX, 100L, 3.0, false); // a plain opener
        other.onDelta(victimB, attackerX, 101L, 1.0, true); // a falling-crit upgrade
        assertTrue(other.due(victimB, 103L).crit(), "the delta's own crit posture sticks too");
    }

    /* --------------------------- same-tick plugin-bonus fold (2.5.3) --------------------------- */

    @Test
    void sameTickSameAttackerFreshFoldAccumulates() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        openFresh(book, victimA, attackerX, 100L, 3.0, false);
        IndicatorWindowBook.FreshResult second = openFresh(book, victimA, attackerX, 100L, 2.5, false);
        assertEquals(IndicatorWindowBook.FreshKind.FOLD_HELD, second.kind());
        assertEquals(5.5, book.due(victimA, 103L).total(), 1.0e-9);
    }

    @Test
    void sameTickFoldBumpsAnAlreadyShippedWindow() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        book.onFresh(victimA, attackerX, 100L, 3.0, false, 0, HORIZON, spawn, 64.0); // hold 0 → ships now
        book.shipped(victimA, 42);
        IndicatorWindowBook.FreshResult second =
                book.onFresh(victimA, attackerX, 100L, 2.0, false, 0, HORIZON, spawn, 64.0);
        assertEquals(IndicatorWindowBook.FreshKind.FOLD_BUMP, second.kind());
        assertEquals(42, second.action().priorEntityId());
        assertEquals(5.0, second.action().total(), 1.0e-9);
    }

    /* --------------------------- window boundaries --------------------------- */

    @Test
    void aDeltaWithNoWindowIsUntracked() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        assertEquals(IndicatorWindowBook.DeltaKind.UNTRACKED,
                book.onDelta(victimA, attackerX, 100L, 2.0, false).kind());
    }

    @Test
    void aDeltaPastTheExpiryHorizonIsUntracked() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        openFresh(book, victimA, attackerX, 100L, 7.0, false); // expiry = 100 + 20 = 120
        book.due(victimA, 103L);
        book.shipped(victimA, 42);
        assertEquals(IndicatorWindowBook.DeltaKind.BUMP,
                book.onDelta(victimA, attackerX, 120L, 1.0, false).kind(), "at the horizon: still in");
        assertEquals(IndicatorWindowBook.DeltaKind.UNTRACKED,
                book.onDelta(victimA, attackerX, 121L, 1.0, false).kind(), "past the horizon: out");
    }

    @Test
    void aShippedWindowShipsOnceThenIsPrunedPastExpiry() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        openFresh(book, victimA, attackerX, 100L, 7.0, false);
        assertNotNull(book.due(victimA, 103L));
        book.shipped(victimA, 42);
        assertNull(book.due(victimA, 104L), "already shipped — never ships again");
        assertNull(book.due(victimA, 121L), "past expiry — pruned, still no ship");
        assertEquals(IndicatorWindowBook.DeltaKind.UNTRACKED,
                book.onDelta(victimA, attackerX, 122L, 1.0, false).kind(), "the pruned entry is gone");
    }

    @Test
    void untrackedRememberedThenABumpFolds() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        book.rememberUntracked(victimA, attackerX, 100L, 3.0, false, HORIZON, spawn, 64.0, 99);
        IndicatorWindowBook.DeltaResult delta = book.onDelta(victimA, attackerX, 101L, 2.0, false);
        assertEquals(IndicatorWindowBook.DeltaKind.BUMP, delta.kind());
        assertEquals(99, delta.action().priorEntityId());
        assertEquals(5.0, delta.action().total(), 1.0e-9);
    }

    /* --------------------------- a newer fresh hit ships the prior --------------------------- */

    @Test
    void aNewerFreshHitShipsTheClosedPriorThenOpensAnew() {
        // Two fresh hits inside one hold (a defensive path — the invuln window normally
        // outlasts the hold): the still-held prior draws now and a new window opens.
        IndicatorWindowBook book = new IndicatorWindowBook();
        openFresh(book, victimA, attackerX, 100L, 3.0, false);
        IndicatorWindowBook.FreshResult next = openFresh(book, victimA, attackerX, 101L, 5.0, false);
        assertEquals(IndicatorWindowBook.FreshKind.OPEN_HELD, next.kind());
        assertNotNull(next.priorShip(), "the closed prior draws its held stand");
        assertEquals(3.0, next.priorShip().total(), 1.0e-9);
        assertEquals(5.0, book.due(victimA, 104L).total(), 1.0e-9, "the new window holds its own total");
    }

    /* --------------------------- death / forget / close --------------------------- */

    @Test
    void deathFlushesAHeldWindowThenDropsIt() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        openFresh(book, victimA, attackerX, 100L, 6.0, false);
        IndicatorWindowBook.Ship ship = book.onDeath(victimA);
        assertNotNull(ship, "the killing marker ships now, before an instant respawn");
        assertEquals(6.0, ship.total(), 1.0e-9);
        assertNull(book.due(victimA, 103L), "the window was dropped");
    }

    @Test
    void deathOfAnAlreadyShippedWindowIsSilent() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        book.onFresh(victimA, attackerX, 100L, 6.0, false, 0, HORIZON, spawn, 64.0);
        book.shipped(victimA, 42);
        assertNull(book.onDeath(victimA), "already on screen — nothing new to draw");
    }

    @Test
    void forgetAndCloseDropWindows() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        openFresh(book, victimA, attackerX, 100L, 3.0, false);
        book.forget(victimA);
        assertNull(book.due(victimA, 103L));

        openFresh(book, victimA, attackerX, 200L, 3.0, false);
        openFresh(book, victimB, attackerY, 200L, 3.0, false);
        book.close();
        assertNull(book.due(victimA, 203L));
        assertNull(book.due(victimB, 203L));
    }

    @Test
    void differentVictimsAreIndependent() {
        IndicatorWindowBook book = new IndicatorWindowBook();
        openFresh(book, victimA, attackerX, 100L, 3.0, false);
        assertEquals(IndicatorWindowBook.DeltaKind.UNTRACKED,
                book.onDelta(victimB, attackerX, 100L, 2.0, false).kind(),
                "victim B has no window of its own");
        openFresh(book, victimB, attackerX, 100L, 4.0, false);
        assertEquals(3.0, book.due(victimA, 103L).total(), 1.0e-9);
        assertEquals(4.0, book.due(victimB, 103L).total(), 1.0e-9);
    }
}

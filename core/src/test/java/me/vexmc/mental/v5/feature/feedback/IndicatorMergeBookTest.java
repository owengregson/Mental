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
 * Pins the same-tick indicator aggregation bookkeeping: an enchantment plugin
 * dealing its bonus as a second {@code victim.damage(bonus, attacker)} raises a
 * second EDBEE for the same (attacker, victim) pair in the same tick — that
 * event must FOLD into the first spawn (summed damage, OR-ed crit, the frozen
 * spawn geometry reused), while a different tick, a different victim, or an
 * empty book must read as a fresh independent indicator. The fold is a pure
 * query — only {@link IndicatorMergeBook#remember} commits — so a failed
 * replacement (no entity id) leaves the slot pointing at the still-alive stand.
 */
class IndicatorMergeBookTest {

    private static final double THRESHOLD = 6.0; // half-hearts (3 hearts)

    private final UUID victimA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID victimB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final IndicatorPlacement.Spawn spawn = new IndicatorPlacement.Spawn(1.5, 65.2, -3.0, 0.75);

    @Test
    void emptyBookNeverMerges() {
        IndicatorMergeBook book = new IndicatorMergeBook();
        assertNull(book.merge(victimA, 100L, 2.0, false, THRESHOLD));
    }

    @Test
    void samePairSameTickFoldsDamageAndGeometry() {
        IndicatorMergeBook book = new IndicatorMergeBook();
        book.remember(victimA, 100L, 42, 3.0, false, spawn, 64.0);

        IndicatorMergeBook.Merged merged = book.merge(victimA, 100L, 2.5, false, THRESHOLD);
        assertNotNull(merged);
        assertEquals(42, merged.priorEntityId());
        assertEquals(5.5, merged.totalDamage(), 1.0e-9);
        assertFalse(merged.crit());
        assertEquals(spawn, merged.spawn());
        assertEquals(64.0, merged.groundY(), 0.0);
    }

    @Test
    void critOrsAcrossTheFold() {
        // The first spawn's crit sticks through the merge.
        IndicatorMergeBook book = new IndicatorMergeBook();
        book.remember(victimA, 100L, 1, 3.0, true, spawn, 64.0);
        IndicatorMergeBook.Merged merged = book.merge(victimA, 100L, 1.0, false, THRESHOLD);
        assertNotNull(merged);
        assertTrue(merged.crit());

        // The folded event's own crit posture sticks too.
        book.remember(victimA, 100L, 2, 3.0, false, spawn, 64.0);
        merged = book.merge(victimA, 100L, 1.0, true, THRESHOLD);
        assertNotNull(merged);
        assertTrue(merged.crit());
    }

    @Test
    void accumulatedTotalCrossingTheThresholdReadsAsCrit() {
        // Neither event alone reaches 6.0, the sum does — a big combined hit
        // reads as a crit exactly as a big single hit would.
        IndicatorMergeBook book = new IndicatorMergeBook();
        book.remember(victimA, 100L, 7, 4.0, false, spawn, 64.0);
        IndicatorMergeBook.Merged merged = book.merge(victimA, 100L, 3.0, false, THRESHOLD);
        assertNotNull(merged);
        assertEquals(7.0, merged.totalDamage(), 1.0e-9);
        assertTrue(merged.crit());
    }

    @Test
    void differentTickStartsFresh() {
        IndicatorMergeBook book = new IndicatorMergeBook();
        book.remember(victimA, 100L, 42, 3.0, false, spawn, 64.0);
        assertNull(book.merge(victimA, 101L, 2.0, false, THRESHOLD));
    }

    @Test
    void differentVictimSameTickIsIndependent() {
        IndicatorMergeBook book = new IndicatorMergeBook();
        book.remember(victimA, 100L, 42, 3.0, false, spawn, 64.0);
        assertNull(book.merge(victimB, 100L, 2.0, false, THRESHOLD));
    }

    @Test
    void rememberOverwritesTheSlot() {
        // One slot per attacker: a fresh spawn against a second victim evicts
        // the first pair's merge target (bounded memory by design).
        IndicatorMergeBook book = new IndicatorMergeBook();
        book.remember(victimA, 100L, 42, 3.0, false, spawn, 64.0);
        book.remember(victimB, 100L, 43, 1.0, false, spawn, 64.0);
        assertNull(book.merge(victimA, 100L, 2.0, false, THRESHOLD));
        assertNotNull(book.merge(victimB, 100L, 2.0, false, THRESHOLD));
    }

    @Test
    void mergeIsAPureQueryUntilRememberCommits() {
        IndicatorMergeBook book = new IndicatorMergeBook();
        book.remember(victimA, 100L, 42, 3.0, false, spawn, 64.0);

        // Two folds without a commit both read the ORIGINAL slot: the
        // replacement failed (no entity id), so the old stand is still alive.
        assertNotNull(book.merge(victimA, 100L, 2.0, false, THRESHOLD));
        IndicatorMergeBook.Merged again = book.merge(victimA, 100L, 2.0, false, THRESHOLD);
        assertNotNull(again);
        assertEquals(42, again.priorEntityId());
        assertEquals(5.0, again.totalDamage(), 1.0e-9);
    }

    @Test
    void thirdEventFoldsAgainstTheCommittedReplacement() {
        // Spawn, merge+commit, then a third bonus in the same tick: the fold
        // targets the REPLACEMENT stand and the cumulative total.
        IndicatorMergeBook book = new IndicatorMergeBook();
        book.remember(victimA, 100L, 42, 3.0, false, spawn, 64.0);
        IndicatorMergeBook.Merged first = book.merge(victimA, 100L, 2.0, false, THRESHOLD);
        assertNotNull(first);
        book.remember(victimA, 100L, 43, first.totalDamage(), first.crit(), first.spawn(), first.groundY());

        IndicatorMergeBook.Merged second = book.merge(victimA, 100L, 1.5, false, THRESHOLD);
        assertNotNull(second);
        assertEquals(43, second.priorEntityId());
        assertEquals(6.5, second.totalDamage(), 1.0e-9);
        assertTrue(second.crit()); // 6.5 >= 6.0
    }
}

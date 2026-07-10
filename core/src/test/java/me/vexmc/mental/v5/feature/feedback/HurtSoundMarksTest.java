package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The mark ring is pure (long-tick primitives, no Bukkit/PE), so it is asserted
 * directly on the tick arithmetic the suppressor drives it with.
 */
class HurtSoundMarksTest {

    @Test
    void entityConsumeSucceedsExactlyOnce() {
        HurtSoundMarks marks = new HurtSoundMarks();
        marks.mark(42, 10, 64, -3, 100);
        assertTrue(marks.consume(42, 100), "the armed mark consumes once");
        assertFalse(marks.consume(42, 100), "a consumed mark is gone");
    }

    @Test
    void entityConsumeMissesUnmarkedId() {
        HurtSoundMarks marks = new HurtSoundMarks();
        marks.mark(42, 0, 0, 0, 5);
        assertFalse(marks.consume(7, 5), "an unmarked entity id never consumes");
    }

    @Test
    void markStaysLiveThroughTwoTicksAndExpiresOnTheThird() {
        HurtSoundMarks marks = new HurtSoundMarks();
        marks.mark(1, 0, 0, 0, 10);
        assertTrue(marks.consume(1, 12), "now - tick == 2 is still live");

        marks.mark(2, 0, 0, 0, 10);
        assertFalse(marks.consume(2, 13), "now - tick == 3 has expired");
    }

    @Test
    void positionalConsumeMatchesWithin1Point5BlocksAndMissesAt3() {
        HurtSoundMarks near = new HurtSoundMarks();
        near.mark(1, 0, 0, 0, 20);
        assertTrue(near.consumeNear(1.0, 0.0, 0.0, 20), "1.0 blocks away is within the 1.5 radius");

        HurtSoundMarks far = new HurtSoundMarks();
        far.mark(1, 0, 0, 0, 20);
        assertFalse(far.consumeNear(3.0, 0.0, 0.0, 20), "3.0 blocks away is outside the radius");
    }

    @Test
    void positionalConsumeRespectsExpiry() {
        HurtSoundMarks marks = new HurtSoundMarks();
        marks.mark(1, 5, 5, 5, 10);
        assertFalse(marks.consumeNear(5, 5, 5, 13), "an expired mark never consumes positionally");
    }

    @Test
    void capacityEvictsTheOldestPastSixtyFour() {
        HurtSoundMarks marks = new HurtSoundMarks();
        // 70 distinct entity ids at one tick: the 6 oldest (0..5) fall off the bounded ring.
        for (int id = 0; id < 70; id++) {
            marks.mark(id, 0, 0, 0, 1);
        }
        assertFalse(marks.consume(0, 1), "the oldest mark was evicted");
        assertFalse(marks.consume(5, 1), "everything below the 64-cap was evicted");
        assertTrue(marks.consume(6, 1), "the first surviving mark is still consumable");
        assertTrue(marks.consume(69, 1), "the newest mark survives");
    }
}

package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The mark ring is pure (long-tick primitives, no Bukkit/PE), so it is asserted
 * directly on the tick arithmetic the suppressor drives it with. Since 2.6.1 a
 * mark is BROADCAST-scoped, not packet-scoped: the server's hurt broadcast is
 * one packet per receiving viewer, so a live mark must answer EVERY per-viewer
 * packet for its window (the per-viewer suppression gap — bystanders past the
 * first heard raw vanilla beside the custom sound) and only expiry removes it.
 */
class HurtSoundMarksTest {

    @Test
    void aLiveMarkAnswersEveryPerViewerPacketOfItsBroadcast() {
        HurtSoundMarks marks = new HurtSoundMarks();
        marks.mark(42, 10, 64, -3, 100);
        // Three viewers = three packets of ONE broadcast: all suppressed (2.6.1 —
        // the destructive consume spent the mark on the first and leaked the rest).
        assertTrue(marks.suppresses(42, 100), "viewer 1's packet is suppressed");
        assertTrue(marks.suppresses(42, 100), "viewer 2's packet is suppressed too");
        assertTrue(marks.suppresses(42, 100), "and viewer 3's — the mark is not consumed");
    }

    @Test
    void entityMatchMissesUnmarkedId() {
        HurtSoundMarks marks = new HurtSoundMarks();
        marks.mark(42, 0, 0, 0, 5);
        assertFalse(marks.suppresses(7, 5), "an unmarked entity id never matches");
    }

    @Test
    void markStaysLiveThroughTwoTicksAndExpiresOnTheThird() {
        HurtSoundMarks marks = new HurtSoundMarks();
        marks.mark(1, 0, 0, 0, 10);
        assertTrue(marks.suppresses(1, 12), "now - tick == 2 is still live");
        assertFalse(marks.suppresses(1, 13), "now - tick == 3 has expired — expiry is the only remover");
    }

    @Test
    void positionalMatchWorksWithin1Point5BlocksAndMissesAt3() {
        HurtSoundMarks marks = new HurtSoundMarks();
        marks.mark(1, 0, 0, 0, 20);
        assertTrue(marks.suppressesNear(1.0, 0.0, 0.0, 20), "1.0 blocks away is within the 1.5 radius");
        assertTrue(marks.suppressesNear(1.0, 0.0, 0.0, 20),
                "and stays armed for the next viewer's packet (broadcast-scoped)");
        assertFalse(marks.suppressesNear(3.0, 0.0, 0.0, 20), "3.0 blocks away is outside the radius");
    }

    @Test
    void positionalMatchRespectsExpiry() {
        HurtSoundMarks marks = new HurtSoundMarks();
        marks.mark(1, 5, 5, 5, 10);
        assertFalse(marks.suppressesNear(5, 5, 5, 13), "an expired mark never matches positionally");
    }

    @Test
    void capacityEvictsTheOldestPastSixtyFour() {
        HurtSoundMarks marks = new HurtSoundMarks();
        // 70 distinct entity ids at one tick: the 6 oldest (0..5) fall off the bounded ring.
        for (int id = 0; id < 70; id++) {
            marks.mark(id, 0, 0, 0, 1);
        }
        assertFalse(marks.suppresses(0, 1), "the oldest mark was evicted");
        assertFalse(marks.suppresses(5, 1), "everything below the 64-cap was evicted");
        assertTrue(marks.suppresses(6, 1), "the first surviving mark still matches");
        assertTrue(marks.suppresses(69, 1), "the newest mark survives");
    }
}

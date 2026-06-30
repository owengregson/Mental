package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the payload match that keeps the suppressor from cancelling an unrelated
 * velocity for the victim's entity (explosion, plugin setVelocity, a second
 * overlapping hit) instead of the duplicate it armed for.
 */
class VelocityDuplicateSuppressorTest {

    @Test
    void matchesTheAdoptedVectorExactlyAndAcrossShortQuantization() {
        assertTrue(VelocityDuplicateSuppressor.payloadMatches(
                0.4, 0.3608, -0.2, 0.4, 0.3608, -0.2), "the exact adopted vector matches");
        // The velocity packet encodes motion×8000 as shorts (~1.25e-4/axis); the
        // decoded duplicate must still match the vector the pass set.
        assertTrue(VelocityDuplicateSuppressor.payloadMatches(
                0.4, 0.3608, -0.2, 0.40005, 0.36075, -0.20003),
                "short quantization stays within the tolerance");
    }

    @Test
    void rejectsAnUnrelatedKnock() {
        // Opposite direction / different magnitude — a separate knock, not the duplicate.
        assertFalse(VelocityDuplicateSuppressor.payloadMatches(
                0.4, 0.3608, -0.2, -0.4, 0.42, 0.5));
        // A single axis off beyond the tolerance is enough to let it through.
        assertFalse(VelocityDuplicateSuppressor.payloadMatches(
                0.4, 0.3608, -0.2, 0.4, 0.5, -0.2));
    }
}

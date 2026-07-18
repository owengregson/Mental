package me.vexmc.mental.v5.feature.cadence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the shared published charge view (spec §2.1): publish/read round-trip, the
 * inert defaults an unpublished player reads, and the clear/reset teardown.
 */
class Ct8cChargeViewTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    @Test
    void unpublishedPlayerReadsTheInertDefault() {
        Ct8cChargeView view = new Ct8cChargeView();
        assertEquals(0.0, view.currentScale(PLAYER), 1e-9);
        assertFalse(view.chargedReach(PLAYER));
    }

    @Test
    void publishRoundTrips() {
        Ct8cChargeView view = new Ct8cChargeView();
        view.publish(PLAYER, 1.97, true);
        assertEquals(1.97, view.currentScale(PLAYER), 1e-9);
        assertTrue(view.chargedReach(PLAYER));

        // A later sub-threshold hit clears the bonus but updates the scale.
        view.publish(PLAYER, 0.5, false);
        assertEquals(0.5, view.currentScale(PLAYER), 1e-9);
        assertFalse(view.chargedReach(PLAYER));
    }

    @Test
    void clearAndResetForget() {
        Ct8cChargeView view = new Ct8cChargeView();
        view.publish(PLAYER, 2.0, true);
        view.clear(PLAYER);
        assertEquals(0.0, view.currentScale(PLAYER), 1e-9);
        assertFalse(view.chargedReach(PLAYER));

        view.publish(PLAYER, 2.0, true);
        view.reset();
        assertEquals(0.0, view.currentScale(PLAYER), 1e-9);
        assertFalse(view.chargedReach(PLAYER));
    }
}

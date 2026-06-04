package me.vexmc.mental.module.hitreg;

import static me.vexmc.mental.module.hitreg.FeedbackBurst.BUNDLE_CLOSE;
import static me.vexmc.mental.module.hitreg.FeedbackBurst.BUNDLE_OPEN;
import static me.vexmc.mental.module.hitreg.FeedbackBurst.HURT;
import static me.vexmc.mental.module.hitreg.FeedbackBurst.VELOCITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the packet-ordering invariants of the pre-send burst — the
 * regression anchor for the wrong-order/wrong-frame hazard class
 * ("random friction", MC-52881, Paper #11055 and friends).
 */
class FeedbackBurstTest {

    @Test
    void fullBurstBundlesOnModernProtocols() {
        assertEquals(List.of(BUNDLE_OPEN, VELOCITY, HURT, BUNDLE_CLOSE),
                FeedbackBurst.plan(true, true, true));
    }

    @Test
    void velocityAlwaysPrecedesHurt() {
        for (boolean bundleWanted : new boolean[] {true, false}) {
            for (boolean bundleCapable : new boolean[] {true, false}) {
                List<FeedbackBurst> plan = FeedbackBurst.plan(true, bundleWanted, bundleCapable);
                assertTrue(plan.indexOf(VELOCITY) < plan.indexOf(HURT),
                        "velocity must lead hurt in " + plan);
            }
        }
    }

    @Test
    void legacyProtocolsAndDisabledBundlingSendBareBursts() {
        assertEquals(List.of(VELOCITY, HURT), FeedbackBurst.plan(true, true, false));
        assertEquals(List.of(VELOCITY, HURT), FeedbackBurst.plan(true, false, true));
        assertEquals(List.of(VELOCITY, HURT), FeedbackBurst.plan(true, false, false));
    }

    @Test
    void suppressedVelocityNeverProducesASinglePacketBundle() {
        // Anticheat gate, OCM ownership, or a pending resistance roll drop
        // the velocity; the hurt still ships, bare on every protocol.
        for (boolean bundleWanted : new boolean[] {true, false}) {
            for (boolean bundleCapable : new boolean[] {true, false}) {
                assertEquals(List.of(HURT), FeedbackBurst.plan(false, bundleWanted, bundleCapable));
            }
        }
    }

    @Test
    void bundleDelimitersAreAlwaysBalanced() {
        for (boolean velocity : new boolean[] {true, false}) {
            for (boolean wanted : new boolean[] {true, false}) {
                for (boolean capable : new boolean[] {true, false}) {
                    List<FeedbackBurst> plan = FeedbackBurst.plan(velocity, wanted, capable);
                    long opens = plan.stream().filter(BUNDLE_OPEN::equals).count();
                    long closes = plan.stream().filter(BUNDLE_CLOSE::equals).count();
                    assertEquals(opens, closes, "unbalanced bundle in " + plan);
                    if (opens == 1) {
                        assertEquals(0, plan.indexOf(BUNDLE_OPEN));
                        assertEquals(plan.size() - 1, plan.indexOf(BUNDLE_CLOSE));
                    }
                }
            }
        }
    }
}

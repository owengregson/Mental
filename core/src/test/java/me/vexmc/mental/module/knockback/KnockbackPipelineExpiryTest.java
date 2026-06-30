package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the pre-delivered pending lifetime against the latency that delivers a
 * player victim's {@code PlayerVelocityEvent}.
 *
 * <p>A wire pre-send records a {@code Pending} on the netty thread (T0). The
 * vector swap and the {@code VelocityDuplicateSuppressor} arming happen only
 * when the entity tracker fires {@code PlayerVelocityEvent} (T2) — that event,
 * not the deferred damage, is where {@code onPlayerVelocity} runs. On Paper the
 * whole netty→damage→entity-tracking chain completes inside one ~50&nbsp;ms
 * tick, so a fixed two-tick (100&nbsp;ms) window always covers it. On Folia the
 * netty→region-next-tick→entity-tracking-phase chain spans several region
 * ticks, so the two-tick window expires the adopted vector before T2:
 * {@code onPlayerVelocity} then neither serves Mental's vector nor arms the
 * suppressor, and vanilla's own velocity packet ships — which for an airborne
 * victim carries no vertical boost (the falling {@code y}), i.e. DOWNWARD
 * knockback on the second combo hit. The window must outlast the Folia
 * tracker latency while leaving Paper's era-faithful two ticks unchanged.</p>
 */
class KnockbackPipelineExpiryTest {

    private static final long TICK_NANOS = 50_000_000L;

    @Test
    void paperKeepsTheTwoTickEraWindow() {
        assertEquals(2 * TICK_NANOS, KnockbackPipeline.pendingExpiryNanos(false));
    }

    @Test
    void foliaToleratesTheNettyToTrackerLatency() {
        // netty pre-send -> deferred damage (next region tick) -> entity-tracking
        // phase can span ~3 region ticks before PlayerVelocityEvent fires.
        assertTrue(
                KnockbackPipeline.pendingExpiryNanos(true) >= 3 * TICK_NANOS,
                "Folia window must outlast the netty->entity-tracker latency");
    }

    @Test
    void preDeliveredAdoptionIsScopedToTheRegisteringAttacker() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // The attacker that registered the pre-send adopts its own vector.
        assertTrue(KnockbackPipeline.sameAttacker(a, a));
        // The wider Folia window keeps a consumed-late pending live longer, so a
        // DIFFERENT attacker's hit must NOT adopt it (wrong owner/direction).
        assertFalse(KnockbackPipeline.sameAttacker(a, b));
        // The connectionless pinned case carries no attacker on either side.
        assertTrue(KnockbackPipeline.sameAttacker(null, null));
        assertFalse(KnockbackPipeline.sameAttacker(a, null));
        assertFalse(KnockbackPipeline.sameAttacker(null, a));
    }

    @Test
    void foliaPreDeliveredSurvivesTheTrackerLatencyThatPaperDrops() {
        long nettyToTrackerGap = 3 * TICK_NANOS; // ~150ms: netty pre-send -> tracker on Folia
        assertTrue(
                nettyToTrackerGap <= KnockbackPipeline.pendingExpiryNanos(true),
                "Folia: the adopted wire knock must still be fresh when the tracker fires late,"
                        + " so onPlayerVelocity serves it and arms the duplicate suppressor");
        assertTrue(
                nettyToTrackerGap > KnockbackPipeline.pendingExpiryNanos(false),
                "Paper: that gap never happens on the single 20 Hz clock; the era window is unchanged");
    }
}

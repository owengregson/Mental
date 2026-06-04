package me.vexmc.mental.module.compensation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LatencyTrackerTest {

    private final LatencyTracker tracker = new LatencyTracker();
    private final UUID player = UUID.randomUUID();

    @Test
    void matchedResponseComputesRoundTrip() {
        LatencyTracker.Record record = tracker.forPlayer(player);
        record.onProbeSent(1000L, 0L);

        assertTrue(record.onResponse(1000L, 50_000_000L));
        assertEquals(50.0, record.pingMillis(), 1.0e-9);
        assertNull(record.previousPingMillis());

        record.onProbeSent(1001L, 100_000_000L);
        assertTrue(record.onResponse(1001L, 130_000_000L));
        assertEquals(30.0, record.pingMillis(), 1.0e-9);
        assertEquals(50.0, record.previousPingMillis(), 1.0e-9);
    }

    @Test
    void responsesConsumeExactlyOnce() {
        LatencyTracker.Record record = tracker.forPlayer(player);
        record.onProbeSent(7L, 0L);
        assertTrue(record.onResponse(7L, 1_000_000L));
        assertFalse(record.onResponse(7L, 2_000_000L));
    }

    @Test
    void foreignIdsNeverMutateState() {
        LatencyTracker.Record record = tracker.forPlayer(player);
        record.onProbeSent(42L, 0L);
        assertFalse(record.onResponse(43L, 1_000_000L));
        assertNull(record.pingMillis());
        assertTrue(record.onResponse(42L, 2_000_000L));
    }

    @Test
    void outstandingProbesAreBoundedByEvictingTheOldest() {
        LatencyTracker.Record record = tracker.forPlayer(player);
        for (long id = 0; id < 33; id++) {
            record.onProbeSent(id, id);
        }
        assertFalse(record.onResponse(0L, 1_000_000L)); // evicted as oldest
        assertTrue(record.onResponse(1L, 1_000_000L));
    }
}

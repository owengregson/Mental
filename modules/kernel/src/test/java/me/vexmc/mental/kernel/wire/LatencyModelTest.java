package me.vexmc.mental.kernel.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LatencyModelTest {

    private final LatencyModel tracker = new LatencyModel();
    private final UUID player = UUID.randomUUID();

    @Test
    void matchedResponseComputesRoundTrip() {
        LatencyModel.Record record = tracker.forPlayer(player);
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
        LatencyModel.Record record = tracker.forPlayer(player);
        record.onProbeSent(7L, 0L);
        assertTrue(record.onResponse(7L, 1_000_000L));
        assertFalse(record.onResponse(7L, 2_000_000L));
    }

    @Test
    void foreignIdsNeverMutateState() {
        LatencyModel.Record record = tracker.forPlayer(player);
        record.onProbeSent(42L, 0L);
        assertFalse(record.onResponse(43L, 1_000_000L));
        assertNull(record.pingMillis());
        assertTrue(record.onResponse(42L, 2_000_000L));
    }

    @Test
    void probeIdBasesOccupyDisjointUnsignedRanges() {
        // Both transports pack a 16-bit sub-id into the low half of their base and
        // hand the result to onResponse as an unsigned long. The bases differ in the
        // high 16 bits, so the full [base, base | 0xFFFF] ranges cannot overlap — a
        // window-confirmation echo can never alias a play-ping probe and vice versa.
        long pingLow = Integer.toUnsignedLong(LatencyModel.PING_ID_BASE);
        long pingHigh = Integer.toUnsignedLong(LatencyModel.PING_ID_BASE | 0xFFFF);
        long txLow = Integer.toUnsignedLong(LatencyModel.TRANSACTION_ID_BASE);
        long txHigh = Integer.toUnsignedLong(LatencyModel.TRANSACTION_ID_BASE | 0xFFFF);
        assertEquals(0x4D45_0000L, pingLow);
        assertEquals(0x4D54_0000L, txLow);
        assertTrue(pingHigh < txLow || txHigh < pingLow,
                "PING and TRANSACTION probe id ranges overlap");
    }

    @Test
    void filteredPingIsNullUntilFirstResponse() {
        assertNull(tracker.forPlayer(player).filteredPingMillis(20));
    }

    @Test
    void filteredPingReturnsTheOnlySampleWhenPreviousIsAbsent() {
        LatencyModel.Record record = tracker.forPlayer(player);
        record.onProbeSent(1L, 0L);
        record.onResponse(1L, 40_000_000L); // rtt 40.0, prev null
        assertEquals(40.0, record.filteredPingMillis(20), 1.0e-9);
    }

    @Test
    void filteredPingRejectsAOneOffSpikeAndItsBounceBack() {
        LatencyModel.Record record = tracker.forPlayer(player);
        record.onProbeSent(1L, 0L);
        record.onResponse(1L, 40_000_000L);            // 40.0
        record.onProbeSent(2L, 1_000_000_000L);
        record.onResponse(2L, 1_240_000_000L);         // 240.0, prev 40.0
        // |240 − 40| = 200 > 20 → trust the smaller; storage stays raw.
        assertEquals(40.0, record.filteredPingMillis(20), 1.0e-9);
        assertEquals(240.0, record.pingMillis(), 1.0e-9);
        record.onProbeSent(3L, 2_000_000_000L);
        record.onResponse(3L, 2_040_000_000L);         // 40.0, prev 240.0
        // |40 − 240| = 200 > 20 → the recovered sample is trusted immediately.
        assertEquals(40.0, record.filteredPingMillis(20), 1.0e-9);
    }

    @Test
    void filteredPingAdoptsASustainedShiftOnItsSecondSample() {
        LatencyModel.Record record = tracker.forPlayer(player);
        record.onProbeSent(1L, 0L);
        record.onResponse(1L, 40_000_000L);            // 40.0
        record.onProbeSent(2L, 1_000_000_000L);
        record.onResponse(2L, 1_240_000_000L);         // 240.0, prev 40.0 → filtered 40.0
        record.onProbeSent(3L, 2_000_000_000L);
        record.onResponse(3L, 2_240_000_000L);         // 240.0, prev 240.0
        // |240 − 240| = 0 ≤ 20 → the shift is now adopted.
        assertEquals(240.0, record.filteredPingMillis(20), 1.0e-9);
    }

    @Test
    void filteredPingTreatsTheThresholdAsStrictlyGreater() {
        LatencyModel.Record record = tracker.forPlayer(player);
        record.onProbeSent(1L, 0L);
        record.onResponse(1L, 40_000_000L);            // 40.0
        record.onProbeSent(2L, 1_000_000_000L);
        record.onResponse(2L, 1_060_000_000L);         // 60.0, prev 40.0
        // |60 − 40| = 20 is NOT > 20 → the reading stands.
        assertEquals(60.0, record.filteredPingMillis(20), 1.0e-9);
    }

    @Test
    void filteredPingWithNonPositiveThresholdIsDisabled() {
        LatencyModel.Record record = tracker.forPlayer(player);
        record.onProbeSent(1L, 0L);
        record.onResponse(1L, 40_000_000L);            // 40.0
        record.onProbeSent(2L, 1_000_000_000L);
        record.onResponse(2L, 1_240_000_000L);         // 240.0, prev 40.0
        assertEquals(240.0, record.filteredPingMillis(0), 1.0e-9); // filter off → raw
    }

    @Test
    void outstandingProbesAreBoundedByEvictingTheOldest() {
        LatencyModel.Record record = tracker.forPlayer(player);
        for (long id = 0; id < 33; id++) {
            record.onProbeSent(id, id);
        }
        assertFalse(record.onResponse(0L, 1_000_000L)); // evicted as oldest
        assertTrue(record.onResponse(1L, 1_000_000L));
    }
}

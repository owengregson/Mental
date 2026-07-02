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
    void outstandingProbesAreBoundedByEvictingTheOldest() {
        LatencyModel.Record record = tracker.forPlayer(player);
        for (long id = 0; id < 33; id++) {
            record.onProbeSent(id, id);
        }
        assertFalse(record.onResponse(0L, 1_000_000L)); // evicted as oldest
        assertTrue(record.onResponse(1L, 1_000_000L));
    }
}

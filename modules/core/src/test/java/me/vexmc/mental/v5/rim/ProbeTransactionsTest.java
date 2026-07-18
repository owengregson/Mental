package me.vexmc.mental.v5.rim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import me.vexmc.mental.kernel.wire.LatencyModel;
import org.junit.jupiter.api.Test;

/**
 * Hand-pins the window-confirmation probe namespace and its kernel-id mapping — the
 * one contract the send side and {@link TransactionProbeRim} both depend on. The
 * namespace must descend from -24576, stay inside [-32768, -16384], wrap without
 * leaving the window, be disjoint from vanilla's small counters, and round-trip an
 * action back to the exact model id that was sent.
 */
class ProbeTransactionsTest {

    @Test
    void sequenceDescendsFromTheStartOfTheWindow() {
        assertEquals((short) -24576, ProbeTransactions.action(0));
        assertEquals((short) -24577, ProbeTransactions.action(1));
        assertEquals((short) -24578, ProbeTransactions.action(2));
    }

    @Test
    void sequenceReachesTheFloorThenWrapsToTheCeilingStillDescending() {
        // 8192 steps below the start (-24576) is the floor; the next step wraps to the
        // ceiling and keeps descending — never leaving the [-32768, -16384] window.
        assertEquals((short) -32768, ProbeTransactions.action(8192));
        assertEquals((short) -16384, ProbeTransactions.action(8193));
        assertEquals((short) -16385, ProbeTransactions.action(8194));
    }

    @Test
    void everyGeneratedActionStaysInsideOurNamespace() {
        for (int sequence = 0; sequence < 40_000; sequence++) {
            short action = ProbeTransactions.action(sequence);
            assertTrue(ProbeTransactions.isProbeAction(action),
                    "sequence " + sequence + " -> " + action + " left the namespace");
        }
    }

    @Test
    void theWholeWindowIsCoveredExactlyOncePerCycle() {
        Set<Short> seen = new HashSet<>();
        for (int sequence = 0; sequence < ProbeTransactions.ACTION_SPAN; sequence++) {
            seen.add(ProbeTransactions.action(sequence));
        }
        assertEquals(ProbeTransactions.ACTION_SPAN, seen.size(), "the cycle skips or repeats an action");
        // And the cycle closes: one full span later returns to the start.
        assertEquals(ProbeTransactions.action(0), ProbeTransactions.action(ProbeTransactions.ACTION_SPAN));
    }

    @Test
    void namespaceIsDisjointFromVanillaSmallCounters() {
        // Vanilla container transactions climb from 0 (0, 1, 2, ...) and small negatives
        // sit just below zero — none of those are ours.
        assertFalse(ProbeTransactions.isProbeAction((short) 0));
        assertFalse(ProbeTransactions.isProbeAction((short) 1));
        assertFalse(ProbeTransactions.isProbeAction((short) 32767));
        assertFalse(ProbeTransactions.isProbeAction((short) -1));
        assertFalse(ProbeTransactions.isProbeAction((short) -16383)); // just above our ceiling
        // Boundaries are ours.
        assertTrue(ProbeTransactions.isProbeAction((short) -16384));
        assertTrue(ProbeTransactions.isProbeAction((short) -32768));
    }

    @Test
    void modelIdIsInjectiveOverTheWindowAndDisjointFromPingIds() {
        long pingLow = Integer.toUnsignedLong(LatencyModel.PING_ID_BASE);
        long pingHigh = Integer.toUnsignedLong(LatencyModel.PING_ID_BASE | 0xFFFF);
        Set<Long> ids = new HashSet<>();
        for (int sequence = 0; sequence < ProbeTransactions.ACTION_SPAN; sequence++) {
            long id = ProbeTransactions.modelId(ProbeTransactions.action(sequence));
            assertTrue(ids.add(id), "modelId collided at sequence " + sequence);
            assertTrue(id < pingLow || id > pingHigh, "transaction id " + id + " landed in the PING range");
        }
        assertEquals(ProbeTransactions.ACTION_SPAN, ids.size());
    }

    @Test
    void sendThenEchoRoundTripsThroughTheModelAndForeignActionsMissIt() {
        LatencyModel model = new LatencyModel();
        UUID player = UUID.randomUUID();
        short action = ProbeTransactions.action(7);
        long id = ProbeTransactions.modelId(action);

        model.forPlayer(player).onProbeSent(id, 0L);
        // A foreign echo (an action we never sent) must not match.
        long foreign = ProbeTransactions.modelId(ProbeTransactions.action(9));
        assertNotEquals(id, foreign);
        assertFalse(model.forPlayer(player).onResponse(foreign, 1_000_000L));
        // The genuine echo matches exactly once.
        assertTrue(model.forPlayer(player).onResponse(id, 30_000_000L));
        assertFalse(model.forPlayer(player).onResponse(id, 31_000_000L));
        assertEquals(30.0, model.forPlayer(player).pingMillis(), 1.0e-9);
    }

    @Test
    void thirtyThreeTransactionProbesEvictTheOldestThroughTheRealMapping() {
        // Extends the kernel eviction pin through the actual action->id mapping: the
        // 33rd outstanding transaction probe drops the oldest, so its late echo misses.
        LatencyModel model = new LatencyModel();
        UUID player = UUID.randomUUID();
        long[] ids = new long[33];
        for (int i = 0; i < 33; i++) {
            ids[i] = ProbeTransactions.modelId(ProbeTransactions.action(i));
            model.forPlayer(player).onProbeSent(ids[i], i);
        }
        assertFalse(model.forPlayer(player).onResponse(ids[0], 1_000_000L), "oldest should have been evicted");
        assertTrue(model.forPlayer(player).onResponse(ids[1], 1_000_000L));
    }
}

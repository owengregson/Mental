package me.vexmc.mental.v5.rim;

import me.vexmc.mental.kernel.wire.LatencyModel;

/**
 * The window-confirmation probe's action-number namespace and its mapping to the
 * kernel's opaque probe id — the single source both the send side
 * ({@code LatencyCompensationUnit}) and the receive side ({@link TransactionProbeRim})
 * share so they can never drift.
 *
 * <p><b>Namespace.</b> Our action numbers are negative shorts in
 * {@code [-32768, -16384]}, generated descending from {@code -24576} with
 * wraparound inside that window. Vanilla's own container transactions climb from
 * {@code 0}, so ours are disjoint from vanilla's small counters and from any small
 * negative near zero (everything above {@code -16384}). Disjointness is the first
 * of three match gates; the windowId-0 check and the {@link LatencyModel}
 * exact-match + 32-outstanding eviction are the other two, so a coincidental
 * collision with a foreign windowId-0 transaction still matches nothing unless we
 * actually sent that action to that player recently.</p>
 *
 * <p><b>Id mapping.</b> An action short packs into the low 16 bits of
 * {@link LatencyModel#TRANSACTION_ID_BASE}; because that base differs from
 * {@link LatencyModel#PING_ID_BASE} in the high half, transaction ids and play-ping
 * ids occupy disjoint unsigned-long ranges. The kernel stays mechanism-blind — it
 * only ever sees an opaque {@code long}.</p>
 */
public final class ProbeTransactions {

    /** Inclusive low bound of our action namespace (also {@code Short.MIN_VALUE}). */
    static final int ACTION_MIN = -32768;

    /** Inclusive high bound of our action namespace. */
    static final int ACTION_MAX = -16384;

    /** Where the descending sequence starts (centre of the window). */
    static final int ACTION_START = -24576;

    /** Window width in distinct action numbers ({@code [ACTION_MIN, ACTION_MAX]}). */
    static final int ACTION_SPAN = ACTION_MAX - ACTION_MIN + 1;

    private ProbeTransactions() {}

    /**
     * The action number for the {@code sequence}-th probe ({@code sequence >= 1};
     * a monotonically increasing counter). The sequence descends from
     * {@link #ACTION_START} and wraps within {@code [ACTION_MIN, ACTION_MAX]}.
     */
    public static short action(int sequence) {
        // Offset from the floor, descending, allowed to go negative; floorMod folds it
        // back into [0, SPAN) so the sequence cycles the whole window instead of leaving it.
        int offset = (ACTION_START - ACTION_MIN) - sequence;
        int wrapped = Math.floorMod(offset, ACTION_SPAN);
        return (short) (ACTION_MIN + wrapped);
    }

    /** True when a client-echoed action number falls inside our namespace. */
    public static boolean isProbeAction(short action) {
        return action >= ACTION_MIN && action <= ACTION_MAX;
    }

    /** Maps an action number to the kernel's opaque probe id (disjoint from PING ids). */
    public static long modelId(short action) {
        return Integer.toUnsignedLong(LatencyModel.TRANSACTION_ID_BASE | (action & 0xFFFF));
    }
}

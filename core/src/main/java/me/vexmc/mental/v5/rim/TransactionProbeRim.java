package me.vexmc.mental.v5.rim;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import me.vexmc.mental.kernel.wire.LatencyModel;

/**
 * The pre-1.17 latency probe receive side — the window-confirmation mirror of
 * {@link ProbeRim}. Below 1.17 the play PING/PONG channel does not exist on the
 * wire, so Mental's probes ride a windowId-0 transaction the vanilla client echoes
 * back (the anticheat-ecosystem standard). This rim matches those echoes against
 * the outstanding probes the latency transport sent, updates the RTT/jitter model,
 * and cancels the matched packet; foreign or third-party container transactions
 * match nothing and flow through to NMS untouched.
 *
 * <p>Netty discipline (see {@code netty-fast-path}): the type is compared by
 * REFERENCE against {@link PacketType.Play.Client#WINDOW_CONFIRMATION} — never
 * downcast blind, since listeners fire for every connection state; identity is the
 * {@link User}'s UUID; the only state read is the player's own
 * {@link LatencyModel.Record}. The match is strict on three counts — windowId 0,
 * the action inside our disjoint {@link ProbeTransactions} namespace, and the id
 * actually outstanding in the player's record — so vanilla's own container
 * transactions (any other windowId, or a small action outside our namespace) are
 * never cancelled.</p>
 */
public final class TransactionProbeRim extends PacketListenerAbstract {

    private final LatencyModel latency;

    public TransactionProbeRim(LatencyModel latency) {
        super(PacketListenerPriority.LOWEST);
        this.latency = latency;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.WINDOW_CONFIRMATION) {
            return;
        }
        User user = event.getUser();
        if (user == null || user.getUUID() == null) {
            return;
        }
        try {
            WrapperPlayClientWindowConfirmation confirmation = new WrapperPlayClientWindowConfirmation(event);
            // windowId 0 is the player-inventory / server-driven transaction slot; container
            // windows carry their own ids. The accepted flag is intentionally ignored — the
            // client always echoes a windowId-0 transaction, and the RTT is the same either
            // way; strictness comes from the namespace + the outstanding-id match below.
            if (confirmation.getWindowId() != 0) {
                return;
            }
            short action = confirmation.getActionId();
            if (!ProbeTransactions.isProbeAction(action)) {
                return;
            }
            if (latency.forPlayer(user.getUUID())
                    .onResponse(ProbeTransactions.modelId(action), System.nanoTime())) {
                event.setCancelled(true);
            }
        } catch (Throwable ignored) {
            // Never throw from a packet handler; an unmatched/foreign transaction is harmless.
        }
    }
}

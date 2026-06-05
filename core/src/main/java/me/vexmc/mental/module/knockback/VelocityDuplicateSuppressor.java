package me.vexmc.mental.module.knockback;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Cancels the one duplicate velocity packet a pre-sent knock would otherwise
 * produce.
 *
 * <p>With the fast path's pre-send, the victim's client already received the
 * knock from the netty thread; the authoritative damage that follows makes
 * vanilla emit the same velocity again at end of tick, one tick later on the
 * wire. Era servers stamped a knock's velocity exactly once — a re-stamp
 * rewinds the client's decay mid-trajectory (measured: the second stamp can
 * even disagree after API listeners or compensation run). The pipeline arms
 * this suppressor when it adopts a pre-delivered vector; the next velocity
 * packet for the victim's own entity on the victim's own connection is
 * dropped, once, within a two-tick deadline.</p>
 *
 * <p>Idle unless armed — with pre-send off (or an anticheat suppressing it)
 * nothing here ever cancels a packet.</p>
 */
public final class VelocityDuplicateSuppressor implements PacketListener {

    private static final long DEADLINE_NANOS = 100_000_000L; // 2 ticks

    private record Armed(int entityId, long stampNanos) {}

    private final ConcurrentHashMap<UUID, Armed> armed = new ConcurrentHashMap<>();

    /** Arms a one-shot suppression for the victim's own next velocity packet. */
    public void armFor(@NotNull Player victim) {
        armed.put(victim.getUniqueId(), new Armed(victim.getEntityId(), System.nanoTime()));
    }

    public void clear() {
        armed.clear();
    }

    @Override
    public void onPacketSend(@NotNull PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_VELOCITY || armed.isEmpty()) {
            return;
        }
        if (!(event.getPlayer() instanceof Player receiver)) {
            return;
        }
        Armed pending = armed.get(receiver.getUniqueId());
        if (pending == null) {
            return;
        }
        if (System.nanoTime() - pending.stampNanos() > DEADLINE_NANOS) {
            armed.remove(receiver.getUniqueId());
            return;
        }
        WrapperPlayServerEntityVelocity packet = new WrapperPlayServerEntityVelocity(event);
        if (packet.getEntityId() != pending.entityId()) {
            return; // someone else's velocity passing through the victim's connection
        }
        armed.remove(receiver.getUniqueId());
        event.setCancelled(true);
    }
}

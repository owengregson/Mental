package me.vexmc.mental.v5.rim;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import java.util.UUID;
import me.vexmc.mental.kernel.delivery.ValvePayload;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.v5.VelocityValve;

/**
 * The outbound velocity valve (spec §3.5) — the PacketEvents-independent
 * replacement for the old duplicate suppressor, registered at HIGHEST so it
 * consumes the exact ENTITY_VELOCITY the authoritative pass re-emits after the
 * desk pre-delivered that value. Keying is the exact wire encoding (motion ×
 * 8000 as shorts, integer equality, no epsilon), so only the byte-identical
 * self-velocity the victim's client already received is cancelled; foreign or
 * corrected velocities pass through.
 *
 * <p>The recipient is the packet's own {@link User} — a self-velocity packet's
 * recipient IS the victim, so its UUID keys the valve slot the desk armed;
 * third-party trackers receive the same entity's velocity under a different
 * recipient UUID and never match. In 4A1 nothing arms the valve, so this listener
 * cancels nothing (zero-touch).</p>
 */
public final class ValveListener extends PacketListenerAbstract {

    private final VelocityValve valve;

    public ValveListener(VelocityValve valve) {
        super(PacketListenerPriority.HIGHEST);
        this.valve = valve;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        try {
            if (event.getPacketType() != PacketType.Play.Server.ENTITY_VELOCITY) {
                return;
            }
            User user = event.getUser();
            if (user == null || user.getUUID() == null) {
                return;
            }
            WrapperPlayServerEntityVelocity packet = new WrapperPlayServerEntityVelocity(event);
            Vector3d velocity = packet.getVelocity();
            // Quantize through the kernel so the wire encoding lives in one place.
            ValvePayload observed = ValvePayload.of(
                    packet.getEntityId(),
                    new KnockbackVector(velocity.getX(), velocity.getY(), velocity.getZ()));
            UUID recipient = user.getUUID();
            if (valve.consume(recipient, observed.entityId(), observed.qx(), observed.qy(), observed.qz())) {
                event.setCancelled(true);
            }
        } catch (Throwable ignored) {
            // A missed consume ships a harmless duplicate; never throw from the pipeline.
        }
    }
}

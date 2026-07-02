package me.vexmc.mental.v5.feature.cadence;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import org.bukkit.entity.Player;

/**
 * The CLIENT-presentation half of attack-cooldown removal (mandate B5(b)):
 * rewrites the receiver's OWN {@code attack_speed} in every outbound
 * UPDATE_ATTRIBUTES so the client renders no charge bar / greyed swing.
 *
 * <p>Unlike the retired plugin-lifetime listener, this one is registered ONLY
 * while the {@code AttackCooldownUnit} scope is open (mandate: no split-brain —
 * the packet half dies with the scope), so it needs no config-flag guard; its
 * mere presence means the feature is enabled. It mutates ONLY the packet-local
 * wrapper's property list (B10 — never shared/live NMS state), so it is
 * Folia-safe on the netty thread and needs no teardown beyond unregistration.</p>
 *
 * <p>Mental's {@code onLoad} sets {@code reEncodeByDefault(false)}, so a mutation
 * must call {@code markForReEncode(true)} or the original bytes ship.</p>
 */
public final class CooldownSpoofListener extends PacketListenerAbstract {

    public CooldownSpoofListener() {
        super(PacketListenerPriority.NORMAL);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.UPDATE_ATTRIBUTES) {
            return;
        }
        if (!(event.getPlayer() instanceof Player receiver)) {
            return; // no Bukkit player attached yet (pre-Play or console)
        }
        try {
            WrapperPlayServerUpdateAttributes packet = new WrapperPlayServerUpdateAttributes(event);
            if (packet.getEntityId() != receiver.getEntityId()) {
                return; // another entity's attribute update routed through this connection
            }
            if (CooldownSpoof.forceFullAttackSpeed(packet)) {
                event.markForReEncode(true);
            }
        } catch (Exception ignored) {
            // Never let a parse/mutate failure propagate on the netty thread.
        }
    }
}

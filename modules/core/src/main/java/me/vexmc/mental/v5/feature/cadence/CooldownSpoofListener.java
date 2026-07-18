package me.vexmc.mental.v5.feature.cadence;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;

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
 *
 * <p>The receiver's identity comes from the PacketEvents cached {@code User},
 * never a Bukkit handle accessor: {@code Player.getEntityId()} routes through
 * {@code getHandle()} and THROWS off the owning region on Folia (netty-fast-path
 * rule), and the old {@code getPlayer().getEntityId()} read was thrown-and-
 * swallowed by the blanket catch — silently disabling this half for every
 * outbound UPDATE_ATTRIBUTES on Folia. The PE {@code User#getEntityId()} is
 * cached connection state (netty-safe), {@code -1} until PE learns it from
 * JOIN_GAME.</p>
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
        User user = event.getUser();
        if (user == null || user.getEntityId() == -1) {
            return; // no connection identity yet (pre-JOIN_GAME) — nothing to compare against
        }
        try {
            WrapperPlayServerUpdateAttributes packet = new WrapperPlayServerUpdateAttributes(event);
            if (packet.getEntityId() != user.getEntityId()) {
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

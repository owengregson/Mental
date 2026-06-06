package me.vexmc.mental.module.knockback;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Feeds the {@link GroundTransitionWatcher} the client's own movement and
 * sprint packets at arrival, on the victim's netty thread — the exact event
 * the legacy movement handler ran its jump/landing bookkeeping on, in the
 * exact order. Read-only: never cancels, never mutates, registered at plugin
 * level beside the watcher because the {@link VictimMotion} ledger needs
 * era-correct baselines even while individual modules are toggled.
 *
 * <p>Packet types are compared by reference against {@code Play.Client}
 * constants — this listener fires for every connection state, and a
 * configuration-state packet must never be downcast (see the 1.3.0
 * regression pinned by {@code ProbeListenerStateTest}).</p>
 */
public final class GroundPacketTap implements PacketListener {

    private final GroundTransitionWatcher watcher;

    public GroundPacketTap(@NotNull GroundTransitionWatcher watcher) {
        this.watcher = watcher;
    }

    @Override
    public void onPacketReceive(@NotNull PacketReceiveEvent event) {
        try {
            PacketTypeCommon type = event.getPacketType();
            boolean movement = type == PacketType.Play.Client.PLAYER_POSITION
                    || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                    || type == PacketType.Play.Client.PLAYER_ROTATION
                    || type == PacketType.Play.Client.PLAYER_FLYING;
            if (movement) {
                if (event.isCancelled() || !(event.getPlayer() instanceof Player player)) {
                    return; // a cancelled packet never reaches the server's handler
                }
                WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
                boolean hasPosition = packet.hasPositionChanged();
                boolean hasRotation = packet.hasRotationChanged();
                watcher.onClientMovement(
                        player.getUniqueId(),
                        packet.isOnGround(),
                        hasPosition, hasPosition ? packet.getLocation().getY() : 0.0,
                        hasRotation, hasRotation ? packet.getLocation().getYaw() : 0.0f,
                        System.nanoTime());
            } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
                if (event.isCancelled() || !(event.getPlayer() instanceof Player player)) {
                    return;
                }
                WrapperPlayClientEntityAction packet = new WrapperPlayClientEntityAction(event);
                if (packet.getAction() == WrapperPlayClientEntityAction.Action.START_SPRINTING) {
                    watcher.onClientSprint(player.getUniqueId(), true);
                } else if (packet.getAction() == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
                    watcher.onClientSprint(player.getUniqueId(), false);
                }
            }
        } catch (Throwable failure) {
            // An observation tap must never break the inbound pipeline.
        }
    }
}

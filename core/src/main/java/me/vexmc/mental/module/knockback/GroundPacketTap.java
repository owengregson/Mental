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
 * exact order. The same sprint packets feed the {@link SprintTracker}'s
 * wire view, which restores the era's in-order sprint read to the attack
 * fast path (the wtap-registration module). Read-only: never cancels, never
 * mutates, registered at plugin level beside the watcher because the
 * {@link VictimMotion} ledger and the wire view need era-correct baselines
 * even while individual modules are toggled.
 *
 * <p>Packet types are compared by reference against {@code Play.Client}
 * constants — this listener fires for every connection state, and a
 * configuration-state packet must never be downcast (see the 1.3.0
 * regression pinned by {@code ProbeListenerStateTest}).</p>
 */
public final class GroundPacketTap implements PacketListener {

    private final GroundTransitionWatcher watcher;
    private final SprintTracker sprintTracker;

    public GroundPacketTap(@NotNull GroundTransitionWatcher watcher,
            @NotNull SprintTracker sprintTracker) {
        this.watcher = watcher;
        this.sprintTracker = sprintTracker;
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
                    sprintTracker.onWireSprint(player.getUniqueId(), true, System.nanoTime());
                } else if (packet.getAction() == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
                    watcher.onClientSprint(player.getUniqueId(), false);
                    sprintTracker.onWireSprint(player.getUniqueId(), false, System.nanoTime());
                }
            }
        } catch (Throwable failure) {
            // An observation tap must never break the inbound pipeline.
        }
    }
}

package me.vexmc.mental.module.rules.cooldown;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import me.vexmc.mental.config.MentalConfig;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts outgoing {@code UPDATE_ATTRIBUTES} packets and rewrites the
 * player's own {@code attack_speed} attribute to
 * {@link CooldownSpoof#FULL_CHARGE_ATTACK_SPEED} so the client renders every
 * swing as fully charged — no charge bar, no greyed-out overlay.
 *
 * <p>This listener is registered for the plugin lifetime (like
 * {@code VelocityDuplicateSuppressor} and {@code GroundPacketTap}) and is
 * gated on the {@code attack-cooldown} config flag; when the module is
 * disabled every packet flows through untouched (zero-touch invariant). The
 * packet listener lifecycle lives outside the module so Netty threads can
 * read the flag from the atomic config snapshot with no synchronisation cost.
 *
 * <p>Re-encode mechanism: {@code WrapperPlayServerUpdateAttributes(event)}
 * calls {@code readEvent}, which parses the packet and registers itself as
 * {@code event.lastUsedWrapper}. After mutation we call
 * {@code event.markForReEncode(true)}; PacketEvents then serialises from that
 * wrapper before the packet exits the channel pipeline. Mental's
 * {@code onLoad} sets {@code reEncodeByDefault(false)}, so the flag must be
 * set explicitly — omitting it would send the original unmodified bytes.
 *
 * <p>Only the receiver's OWN entity is spoofed: the packet's {@code entityId}
 * is compared to {@code receiver.getEntityId()} before any work is done.
 * Attribute updates for other entities (tracked mobs visible to this player)
 * pass through unchanged. All reads are from the immutable netty-safe objects
 * ({@code int} entity id, {@code MentalConfig} atomic reference) — no live
 * entity access.
 */
public final class CooldownSpoofListener implements PacketListener {

    private final MentalConfig config;

    public CooldownSpoofListener(@NotNull MentalConfig config) {
        this.config = config;
    }

    @Override
    public void onPacketSend(@NotNull PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.UPDATE_ATTRIBUTES) {
            return;
        }
        if (!config.cooldown().enabled()) {
            return; // zero-touch when module off
        }
        if (!(event.getPlayer() instanceof Player receiver)) {
            return; // no Bukkit player attached yet (pre-Play or spectator console)
        }
        var packet = new WrapperPlayServerUpdateAttributes(event);
        if (packet.getEntityId() != receiver.getEntityId()) {
            return; // another entity's attribute update routed through this connection
        }
        if (CooldownSpoof.forceFullAttackSpeed(packet)) {
            // Mutation occurred — ask PacketEvents to re-serialise from the
            // wrapper before the bytes exit the channel. Without this flag
            // (reEncodeByDefault is false in Mental's onLoad) the original
            // unmodified buffer would be sent instead.
            event.markForReEncode(true);
        }
    }
}

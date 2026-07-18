package me.vexmc.mental.v5.feature.delivery;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * The phantom-knock corrective (2.6.0, SE-compat): when a hit whose knock was
 * already committed to the victim's client (PRE_SENT burst / PINNED vector) is
 * then killed before the authoritative pass — a plugin cancelling the EDBEE
 * (StarEnchants dodge/immune procs) or a foreign-window rejection (an SE proc
 * re-armed the victim's immunity in the freeze→apply sliver, so
 * {@code victim.damage} fires no event at all) — the client is left showing a
 * knock the server never applied: a desync that reads as "I got hit with no
 * sound." The caller withdraws the desk pending (no stale redelivery), then
 * ships ONE ENTITY_VELOCITY carrying the victim's TRUE server velocity so the
 * client corrects back to reality.
 *
 * <p>Called on the victim's region thread (both call sites are inside the EDBEE
 * dispatch / the damage task), so {@code getVelocity} is region-correct. The
 * valve cannot eat this packet: it arms only at the DeskRouter's MONITOR
 * confirm, which never ran for a cancelled/rejected hit. A victim with no
 * PacketEvents user (synthetic players, in-process bots) is skipped — no wire,
 * no phantom to correct. The send is catch-all-wrapped: a viewer
 * mid-(re)configuration can throw inside PacketEvents, and a missed correction
 * beats a surfaced pipeline exception (the {@code BurstSender} posture).</p>
 */
public final class CorrectiveVelocity {

    private CorrectiveVelocity() {}

    /** Ships the victim's true server velocity to their own client; no-op when unsendable. */
    public static void ship(Player victim) {
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(victim);
            if (user == null) {
                return; // synthetic / disconnecting — no wire carried the phantom either
            }
            Vector velocity = victim.getVelocity();
            user.sendPacket(new WrapperPlayServerEntityVelocity(
                    victim.getEntityId(),
                    new Vector3d(velocity.getX(), velocity.getY(), velocity.getZ())));
        } catch (Throwable ignored) {
            // Mid-(re)configuration or a torn pipeline: drop the correction.
        }
    }
}

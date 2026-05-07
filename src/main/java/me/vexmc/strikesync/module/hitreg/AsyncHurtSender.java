package me.vexmc.strikesync.module.hitreg;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import org.bukkit.entity.Player;

/**
 * Sends a {@code WrapperPlayServerHurtAnimation} packet to a recipient,
 * thread-safe to invoke from PacketEvents' netty event loop.
 *
 * <h2>Why this exists</h2>
 * Vanilla's hurt animation (the red flash on the victim and the screen shake
 * for the local player when they're the victim) is queued during
 * {@code LivingEntity#hurt} and broadcast via the entity tracker on the next
 * tracker pulse — the exact same delay that motivates the velocity pre-send.
 *
 * <p>By sending {@link WrapperPlayServerHurtAnimation} from the netty thread
 * the moment the hit is accepted, the visual feedback arrives at the client
 * at {@code T+RTT} alongside the pre-sent velocity packet, instead of trailing
 * behind by 25–100 ms.
 *
 * <p>Like the velocity pre-send, this is paired: the main-thread damage path
 * will queue another hurt animation via vanilla's hurt chain on the next tick
 * (broadcast through the tracker). For the victim and attacker — who already
 * received the netty-thread packet — that second broadcast is a redundant
 * copy of the same animation event, which the client de-duplicates naturally
 * via its own animation timer.
 */
public final class AsyncHurtSender {

    private AsyncHurtSender() {}

    /**
     * Send a hurt-animation packet to {@code recipient} for the entity with id
     * {@code victimEntityId}. {@code hurtYaw} is the hurt-from direction in
     * the victim's local frame (degrees).
     */
    public static void send(Player recipient, int victimEntityId, float hurtYaw) {
        WrapperPlayServerHurtAnimation packet =
                new WrapperPlayServerHurtAnimation(victimEntityId, hurtYaw);
        PacketEvents.getAPI().getPlayerManager().sendPacket(recipient, packet);
    }

    /**
     * Compute the hurt-from yaw the client expects, matching vanilla's formula
     * in {@code LivingEntity#hurt}:
     * <pre>
     *   hurtDir = atan2(attacker.z - victim.z, attacker.x - victim.x) * 180/π
     *           - victim.yaw
     * </pre>
     */
    public static float computeHurtYaw(double attackerX, double attackerZ,
                                       double victimX, double victimZ,
                                       float victimYaw) {
        double dx = attackerX - victimX;
        double dz = attackerZ - victimZ;
        return (float) (Math.atan2(dz, dx) * (180.0D / Math.PI) - victimYaw);
    }
}

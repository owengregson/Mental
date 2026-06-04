package me.vexmc.mental.module.hitreg;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Netty-thread feedback packets — the headline latency win.
 *
 * <p>Vanilla binds both the victim's velocity packet and the hurt animation
 * to the next tick plus the entity-tracker pulse (25–100 ms after the click
 * reaches the server). Shipping them straight from the netty loop moves both
 * signals to {@code T + RTT}. The main-thread damage that follows re-emits
 * them through vanilla; clients treat the duplicates as no-op corrections.</p>
 *
 * <p>The dedicated hurt-animation packet exists since protocol 1.19.4; older
 * servers get the classic entity-status 2 (hurt flash, no directional tilt).
 * Decided once at module enable from PacketEvents' server version.</p>
 */
final class FeedbackSenders {

    private static final int ENTITY_STATUS_HURT = 2;

    private final boolean hurtAnimationPacket;

    FeedbackSenders() {
        this.hurtAnimationPacket = PacketEvents.getAPI().getServerManager()
                .getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_19_4);
    }

    void sendVelocity(@NotNull Player recipient, int entityId, @NotNull Vector velocity) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(recipient,
                new WrapperPlayServerEntityVelocity(
                        entityId,
                        new Vector3d(velocity.getX(), velocity.getY(), velocity.getZ())));
    }

    void sendHurt(@NotNull Player recipient, int victimEntityId, float hurtYaw) {
        if (hurtAnimationPacket) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(recipient,
                    new WrapperPlayServerHurtAnimation(victimEntityId, hurtYaw));
        } else {
            PacketEvents.getAPI().getPlayerManager().sendPacket(recipient,
                    new WrapperPlayServerEntityStatus(victimEntityId, ENTITY_STATUS_HURT));
        }
    }

    /**
     * Vanilla's hurt-from yaw, in the victim's local frame:
     * {@code atan2(Δz, Δx) × 180/π − victimYaw}.
     */
    static float hurtYaw(double attackerX, double attackerZ, double victimX, double victimZ, float victimYaw) {
        double dx = attackerX - victimX;
        double dz = attackerZ - victimZ;
        return (float) (Math.atan2(dz, dx) * (180.0 / Math.PI) - victimYaw);
    }
}

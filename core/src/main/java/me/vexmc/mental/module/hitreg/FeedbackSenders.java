package me.vexmc.mental.module.hitreg;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Netty-thread feedback packets — the headline latency win.
 *
 * <p>Vanilla binds both the victim's velocity packet and the hurt animation
 * to the next tick plus the entity-tracker pulse (25–100 ms after the click
 * reaches the server). Shipping them straight from the netty loop moves both
 * signals to {@code T + RTT}. The main-thread damage that follows re-emits
 * them through vanilla; clients treat the duplicates as no-op corrections.</p>
 *
 * <p>The victim's burst follows a {@link FeedbackBurst} plan: velocity before
 * hurt, wrapped in bundle delimiters on 1.19.4+ so both land in one client
 * frame, written through the connection's PacketEvents user and flushed once.</p>
 *
 * <p>The dedicated hurt-animation packet exists since protocol 1.19.4 and
 * carries the attack direction (the directional tilt vanilla derives from
 * DAMAGE_EVENT's source position); older servers get the classic
 * entity-status 2 (hurt flash, no tilt). DAMAGE_EVENT itself is deliberately
 * not pre-sent: clients couple damage-type effects to it, which a later
 * authoritative re-send would double-fire — HURT_ANIMATION with an explicit
 * yaw is the idempotent equivalent. Decided once at module enable from
 * PacketEvents' server version.</p>
 */
final class FeedbackSenders {

    private static final int ENTITY_STATUS_HURT = 2;

    private final boolean modernProtocol;

    FeedbackSenders() {
        this(PacketEvents.getAPI().getServerManager()
                .getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_19_4));
    }

    FeedbackSenders(boolean modernProtocol) {
        this.modernProtocol = modernProtocol;
    }

    /**
     * The victim-bound burst: optional velocity plus the hurt animation, in
     * plan order, single flush. A null velocity means the pre-send was
     * suppressed (anticheat gate, OCM ownership, pending resistance roll) —
     * the hurt still ships, bare.
     */
    void sendVictimBurst(
            @NotNull Player victim,
            int victimEntityId,
            @Nullable Vector velocity,
            float hurtYaw,
            boolean bundleWanted) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(victim);
        if (user == null) {
            return; // no live connection (synthetic players, disconnecting)
        }
        List<FeedbackBurst> plan = FeedbackBurst.plan(velocity != null, bundleWanted, modernProtocol);
        for (FeedbackBurst element : plan) {
            user.writePacket(switch (element) {
                case BUNDLE_OPEN, BUNDLE_CLOSE -> new WrapperPlayServerBundle();
                case VELOCITY -> velocityPacket(victimEntityId, velocity);
                case HURT -> hurtPacket(victimEntityId, hurtYaw);
            });
        }
        user.flushPackets();
    }

    /** The attacker's third-person view of the victim flinching — one packet. */
    void sendHurt(@NotNull Player recipient, int victimEntityId, float hurtYaw) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(recipient,
                hurtPacket(victimEntityId, hurtYaw));
    }

    private static PacketWrapper<?> velocityPacket(int entityId, Vector velocity) {
        return new WrapperPlayServerEntityVelocity(
                entityId, new Vector3d(velocity.getX(), velocity.getY(), velocity.getZ()));
    }

    private PacketWrapper<?> hurtPacket(int victimEntityId, float hurtYaw) {
        return modernProtocol
                ? new WrapperPlayServerHurtAnimation(victimEntityId, hurtYaw)
                : new WrapperPlayServerEntityStatus(victimEntityId, ENTITY_STATUS_HURT);
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

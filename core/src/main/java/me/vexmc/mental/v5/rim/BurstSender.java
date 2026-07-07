package me.vexmc.mental.v5.rim;

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
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.wire.FeedbackPlan;

/**
 * Executes a kernel-computed {@link FeedbackPlan} through a victim's PacketEvents
 * {@link User} (spec §6; the retired {@code FeedbackSenders} burst mechanics on
 * the rim seam). Velocity leads the hurt animation, wrapped in bundle delimiters
 * on 1.19.4+ so both land in one client frame; the burst is written and flushed
 * once. A null user (synthetic/disconnecting player, in-process bot) returns
 * {@link Outcome#UNSENDABLE} so the caller pins the value instead of accounting
 * it wire-delivered.
 *
 * <p>The pre-send is HURT_ANIMATION (explicit directional yaw) on 1.19.4+ and
 * entity-status 2 below — never DAMAGE_EVENT, which clients couple type effects
 * to and the authoritative re-send would double-fire. Duplicates are fine by
 * design: the authoritative path re-emits through vanilla and clients treat them
 * as no-op corrections. A send to a reconfiguring target throws inside PE, so the
 * whole ship is wrapped and dropped (a missed burst beats a pipeline exception).</p>
 */
public final class BurstSender {

    private static final int ENTITY_STATUS_HURT = 2;

    /** Whether the pre-send burst reached the wire. */
    public enum Outcome { DELIVERED, UNSENDABLE }

    private final boolean modernProtocol;

    public BurstSender() {
        this(PacketEvents.getAPI().getServerManager()
                .getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_19_4));
    }

    public BurstSender(boolean modernProtocol) {
        this.modernProtocol = modernProtocol;
    }

    /**
     * Ships the victim burst: an optional velocity pre-send plus the hurt
     * animation, in plan order, single flush. A null {@code velocity} means the
     * velocity component was suppressed (anticheat gate, pending
     * resistance roll) — the hurt still ships, bare.
     *
     * @param user            the victim's PacketEvents user, or null (unsendable)
     * @param victimEntityId  the victim's entity id
     * @param velocity        the pre-send velocity, or null to ship hurt only
     * @param hurtYaw         the directional hurt yaw (1.19.4+ HURT_ANIMATION)
     * @param bundleWanted    the {@code bundle-feedback} setting
     */
    public Outcome ship(
            User user, int victimEntityId, KnockbackVector velocity,
            float hurtYaw, boolean bundleWanted) {
        if (user == null) {
            return Outcome.UNSENDABLE;
        }
        try {
            List<FeedbackPlan.Step> plan =
                    FeedbackPlan.plan(velocity != null, bundleWanted, modernProtocol);
            for (FeedbackPlan.Step step : plan) {
                user.writePacket(packetFor(step, victimEntityId, velocity, hurtYaw));
            }
            user.flushPackets();
            return Outcome.DELIVERED;
        } catch (Throwable reconfiguring) {
            // The target may be mid-(re)configuration where a Play packet can't
            // encode; drop the burst rather than surface a pipeline exception.
            return Outcome.UNSENDABLE;
        }
    }

    private PacketWrapper<?> packetFor(
            FeedbackPlan.Step step, int victimEntityId, KnockbackVector velocity, float hurtYaw) {
        return switch (step) {
            case BUNDLE_OPEN, BUNDLE_CLOSE -> new WrapperPlayServerBundle();
            case VELOCITY -> new WrapperPlayServerEntityVelocity(
                    victimEntityId, new Vector3d(velocity.x(), velocity.y(), velocity.z()));
            case HURT -> modernProtocol
                    ? new WrapperPlayServerHurtAnimation(victimEntityId, hurtYaw)
                    : new WrapperPlayServerEntityStatus(victimEntityId, ENTITY_STATUS_HURT);
        };
    }
}

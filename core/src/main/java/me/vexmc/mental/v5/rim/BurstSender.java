package me.vexmc.mental.v5.rim;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.sound.Sound;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.protocol.sound.Sounds;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
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
 *
 * <p>The burst is written SILENTLY ({@code writePacketSilently} →
 * {@code ChannelHelper.writeInContext(ENCODER_NAME)}) so it enters the pipeline AT
 * Mental's own relocated-PE encoder context, skipping only Mental's own send-event
 * stage — where the {@code ValveListener} watches ENTITY_VELOCITY. That listener
 * therefore sees only server-originated velocity (the vanilla tracker's dup,
 * foreign plugins) and can never consume Mental's OWN pre-send against a stale arm.
 * The bytes on the wire are identical (the same {@code transformWrappers} encode,
 * and every head-side handler — Via, compression, encryption — still runs), and
 * external anticheats / bots on their own injectors still observe the burst.</p>
 */
public final class BurstSender {

    private static final int ENTITY_STATUS_HURT = 2;

    /** Whether the pre-send burst reached the wire. */
    public enum Outcome { DELIVERED, UNSENDABLE }

    private final boolean modernProtocol;

    /**
     * The vanilla hurt sound, resolved once at construction. {@code
     * entity.player.hurt} is UNIVERSAL (1.9.4+) and a KNOWN PacketEvents sound, so
     * it carries a valid per-version id across the whole range with no era table —
     * unlike a user-configured name, which is why {@link
     * me.vexmc.mental.v5.feature.feedback.FeedbackSoundTable} is not needed here.
     */
    private final Sound hurtSound;

    public BurstSender() {
        this(PacketEvents.getAPI().getServerManager()
                .getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_19_4));
    }

    public BurstSender(boolean modernProtocol) {
        this.modernProtocol = modernProtocol;
        this.hurtSound = Sounds.getByNameOrCreate("entity.player.hurt");
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
                user.writePacketSilently(packetFor(step, victimEntityId, velocity, hurtYaw));
            }
            user.flushPackets();
            return Outcome.DELIVERED;
        } catch (Throwable reconfiguring) {
            // The target may be mid-(re)configuration where a Play packet can't
            // encode; drop the burst rather than surface a pipeline exception.
            return Outcome.UNSENDABLE;
        }
    }

    /**
     * Ships the victim's OWN {@code entity.player.hurt} to the victim alone — the
     * one client vanilla's blocked branch leaves silent (vanilla's hurt BROADCAST
     * excludes the victim, and its blocked path skips the {@code
     * ClientboundDamageEventPacket} the victim's client would derive its hurt sound
     * from). Written SILENTLY like the burst above, so it enters at Mental's own
     * encoder and BYPASSES Mental's own {@code HurtSoundSuppressor} — otherwise
     * that suppressor, armed by {@code hit-feedback} for this same hit to eat the
     * vanilla BYSTANDER broadcast, would also swallow this positional restoration
     * (the crowded-combat "flinch flashes, sound silent while sword-blocking" gap)
     * exactly as {@code HitFeedbackListener}'s own replacement is silent-written for
     * the same reason. A null user (bot / reconfiguring target) is a no-op.
     *
     * @param user     the victim's PacketEvents user, or null (no-op)
     * @param position the victim's world position (the positional sound origin)
     * @param pitch    the era hurt-sound pitch (vanilla {@code 1 + (r1 − r2) × 0.2})
     */
    public void shipHurtSound(User user, Vector3d position, float pitch) {
        if (user == null) {
            return;
        }
        try {
            user.writePacketSilently(
                    new WrapperPlayServerSoundEffect(hurtSound, SoundCategory.PLAYER, position, 1.0f, pitch));
            user.flushPackets();
        } catch (Throwable reconfiguring) {
            // A missed cosmetic beats a surfaced exception on the send path.
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

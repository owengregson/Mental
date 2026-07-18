package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.sound.Sound;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import me.vexmc.mental.kernel.port.TickClock;

/**
 * Cancels the vanilla {@code entity.player.hurt} broadcast that a melee hit the
 * {@link HitFeedbackListener} is voicing itself triggers — EVERY per-viewer
 * packet of it (2.6.1: the broadcast is one packet per receiving client, so the
 * old first-packet consume left every later bystander hearing raw vanilla
 * beside the custom replacement) — and never the hurt sound of a fall, fire, or
 * drowning outside a voiced hit's window. Copies {@link
 * me.vexmc.mental.v5.feature.cadence.AttackSoundListener}'s shape verbatim
 * ({@code PacketListenerAbstract} at NORMAL, reference-compared packet types,
 * every parse wrapped so a failure never propagates on the netty thread) but,
 * crucially, is <em>mark-scoped</em>: the packet is dropped ONLY while a
 * correlated {@link HurtSoundMarks} mark is live.
 *
 * <p>That is the whole point of the {@link HurtSoundMarks} ring. A blanket cancel
 * of {@code entity.player.hurt} would also eat the sounds of environmental damage;
 * instead the listener arms a mark keyed to the victim (by entity id for the
 * entity-attached {@code ENTITY_SOUND_EFFECT}, by world position for the positional
 * {@code SOUND_EFFECT}) the instant before the server broadcasts, and only a hurt
 * sound matching such a live mark is suppressed. An environmental hurt sound
 * outside a mark's window matches nothing and passes through (the in-window
 * collateral trade is documented on the ring).</p>
 */
public final class HurtSoundSuppressor extends PacketListenerAbstract {

    private static final String BARE = "entity.player.hurt";
    private static final String NAMESPACED = "minecraft:" + BARE;

    private final HurtSoundMarks marks;
    private final TickClock clock;

    public HurtSoundSuppressor(HurtSoundMarks marks, TickClock clock) {
        super(PacketListenerPriority.NORMAL);
        this.marks = marks;
        this.clock = clock;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        Object type = event.getPacketType();
        if (PacketType.Play.Server.ENTITY_SOUND_EFFECT.equals(type)) {
            try {
                WrapperPlayServerEntitySoundEffect wrapper = new WrapperPlayServerEntitySoundEffect(event);
                if (isPlayerHurt(wrapper.getSound())
                        && marks.suppresses(wrapper.getEntityId(), clock.current().value())) {
                    event.setCancelled(true);
                }
            } catch (Exception ignored) {
                // Never let a parse failure propagate on the netty thread.
            }
            return;
        }
        if (PacketType.Play.Server.SOUND_EFFECT.equals(type)) {
            try {
                WrapperPlayServerSoundEffect wrapper = new WrapperPlayServerSoundEffect(event);
                // getPosition() is the wire fixed-point (block ×8) already divided
                // back to real world coordinates by PE — no manual scaling here.
                Vector3d position = wrapper.getPosition();
                if (isPlayerHurt(wrapper.getSound()) && position != null
                        && marks.suppressesNear(position.getX(), position.getY(), position.getZ(),
                                clock.current().value())) {
                    event.setCancelled(true);
                }
            } catch (Exception ignored) {
                // Never let a parse failure propagate on the netty thread.
            }
        }
    }

    private static boolean isPlayerHurt(Sound sound) {
        if (sound == null) {
            return false;
        }
        ResourceLocation id = sound.getSoundId();
        if (id == null) {
            return false;
        }
        String name = id.toString();
        return BARE.equals(name) || NAMESPACED.equals(name);
    }
}

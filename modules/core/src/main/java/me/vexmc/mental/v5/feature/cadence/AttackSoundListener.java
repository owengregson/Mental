package me.vexmc.mental.v5.feature.cadence;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.sound.Sound;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;

/**
 * Suppresses the 1.9 attack-result sound family ({@code entity.player.attack.*} —
 * {@code crit}/{@code knockback}/{@code nodamage}/{@code strong}/{@code sweep}/
 * {@code weak}) so combat is silent on swing as in 1.7/1.8 (the retired
 * {@code module.rules.sound.AttackSoundListener} on the v5 seam).
 *
 * <p>Registered ONLY while the {@code AttackSoundsUnit} scope is open (no
 * split-brain — the packet half dies with the scope), so it needs no config-flag
 * guard. Both {@code SOUND_EFFECT} (positional) and {@code ENTITY_SOUND_EFFECT}
 * (entity-attached) are handled; prefix matching catches any future variant. Netty
 * thread; never throws into the pipeline.</p>
 */
public final class AttackSoundListener extends PacketListenerAbstract {

    private static final String BARE_PREFIX = "entity.player.attack.";
    private static final String NAMESPACED_PREFIX = "minecraft:" + BARE_PREFIX;

    public AttackSoundListener() {
        super(PacketListenerPriority.NORMAL);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        Object type = event.getPacketType();
        if (PacketType.Play.Server.SOUND_EFFECT.equals(type)) {
            try {
                WrapperPlayServerSoundEffect wrapper = new WrapperPlayServerSoundEffect(event);
                cancelIfAttackSound(event, wrapper.getSound());
            } catch (Exception ignored) {
                // Never let a parse failure propagate on the netty thread.
            }
            return;
        }
        if (PacketType.Play.Server.ENTITY_SOUND_EFFECT.equals(type)) {
            try {
                WrapperPlayServerEntitySoundEffect wrapper = new WrapperPlayServerEntitySoundEffect(event);
                cancelIfAttackSound(event, wrapper.getSound());
            } catch (Exception ignored) {
                // Never let a parse failure propagate on the netty thread.
            }
        }
    }

    private static void cancelIfAttackSound(PacketSendEvent event, Sound sound) {
        if (sound == null) {
            return;
        }
        ResourceLocation id = sound.getSoundId();
        if (id == null) {
            return;
        }
        String name = id.toString();
        if (name.startsWith(NAMESPACED_PREFIX) || name.startsWith(BARE_PREFIX)) {
            event.setCancelled(true);
        }
    }
}

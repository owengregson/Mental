package me.vexmc.mental.module.rules.sound;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.sound.Sound;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import me.vexmc.mental.config.MentalConfig;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts outbound sound packets and cancels the 1.9 attack-result sound
 * family ({@code entity.player.attack.*}) so combat is silent on swing, as it
 * was in 1.7.10 and 1.8.9 (neither version had these sounds at all).
 *
 * <p>This listener is registered for the plugin lifetime and is gated on the
 * {@code disable-attack-sounds} config flag; when the module is disabled every
 * packet flows through untouched (zero-touch invariant). The packet listener
 * lifecycle lives outside the module so Netty threads can read the flag from
 * the atomic config snapshot with no synchronisation cost.</p>
 *
 * <p>Packet types handled:
 * <ul>
 *   <li>{@code SOUND_EFFECT} — the positional sound packet used since 1.9
 *       (and the legacy named-sound packet on pre-1.9 clients, though attack
 *       sounds did not exist pre-1.9 so the gate is effectively inert there).
 *   <li>{@code ENTITY_SOUND_EFFECT} — the entity-attached sound packet also
 *       introduced in 1.9; some server versions route attack sounds through
 *       this type instead of (or in addition to) {@code SOUND_EFFECT}.
 * </ul>
 *
 * <p>Sound name resolution: {@link Sound#getSoundId()} returns a
 * {@link ResourceLocation} whose {@code toString()} yields
 * {@code "namespace:key"} (e.g. {@code "minecraft:entity.player.attack.sweep"}).
 * {@link AttackSounds#isSuppressedAttackSound} handles both the namespaced and
 * bare forms. If the sound object is null (possible on older PE mappings), the
 * packet is left alone rather than thrown — the netty thread must never see an
 * unchecked exception.</p>
 */
public final class AttackSoundListener implements PacketListener {

    private final MentalConfig config;

    public AttackSoundListener(@NotNull MentalConfig config) {
        this.config = config;
    }

    @Override
    public void onPacketSend(@NotNull PacketSendEvent event) {
        Object type = event.getPacketType();

        if (PacketType.Play.Server.SOUND_EFFECT.equals(type)) {
            if (!config.attackSound().enabled()) {
                return; // zero-touch when module off
            }
            try {
                WrapperPlayServerSoundEffect wrapper = new WrapperPlayServerSoundEffect(event);
                Sound sound = wrapper.getSound();
                if (sound == null) {
                    return;
                }
                ResourceLocation id = sound.getSoundId();
                if (id == null) {
                    return;
                }
                if (AttackSounds.isSuppressedAttackSound(id.toString())) {
                    event.setCancelled(true);
                }
            } catch (Exception ignored) {
                // Never let a parse failure propagate on the netty thread.
            }
            return;
        }

        if (PacketType.Play.Server.ENTITY_SOUND_EFFECT.equals(type)) {
            if (!config.attackSound().enabled()) {
                return; // zero-touch when module off
            }
            try {
                WrapperPlayServerEntitySoundEffect wrapper = new WrapperPlayServerEntitySoundEffect(event);
                Sound sound = wrapper.getSound();
                if (sound == null) {
                    return;
                }
                ResourceLocation id = sound.getSoundId();
                if (id == null) {
                    return;
                }
                if (AttackSounds.isSuppressedAttackSound(id.toString())) {
                    event.setCancelled(true);
                }
            } catch (Exception ignored) {
                // Never let a parse failure propagate on the netty thread.
            }
        }
    }
}

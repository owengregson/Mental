package me.vexmc.mental.module.rules.sweep;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import me.vexmc.mental.config.MentalConfig;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts outbound {@code PARTICLE} packets and cancels the 1.9
 * {@code sweep_attack} particle ({@code minecraft:sweep_attack}) so the
 * sword-sweep visual is suppressed on every swing, as it was in 1.7/1.8
 * (neither version had the sweep mechanic or particle).
 *
 * <p>This listener is registered for the plugin lifetime and is gated on the
 * {@code disable-sword-sweep} config flag; when the module is disabled every
 * packet flows through untouched (zero-touch invariant). The packet listener
 * lifecycle lives outside the module so Netty threads can read the flag from
 * the atomic config snapshot with no synchronisation cost.</p>
 *
 * <p>Particle type resolution: {@link Particle#getType()} returns a
 * {@code ParticleType} that implements {@code MappedEntity}; calling
 * {@code getName()} returns a {@link ResourceLocation} whose
 * {@code toString()} yields {@code "namespace:key"} (e.g.
 * {@code "minecraft:sweep_attack"}).
 * {@link SweepParticles#isSweepParticle} handles both the namespaced and
 * bare forms. If the particle object is null, the packet is left alone rather
 * than thrown — the netty thread must never see an unchecked exception.</p>
 *
 * <p>PE accessor confirmed via {@code javap} on packetevents-api-2.12.1.jar:
 * <pre>
 *   WrapperPlayServerParticle.getParticle() → Particle&lt;?&gt;
 *   Particle.getType()                       → ParticleType&lt;?&gt; extends MappedEntity
 *   MappedEntity.getName()                   → ResourceLocation (toString = "namespace:key")
 *   ParticleTypes.SWEEP_ATTACK               exists as a static field
 * </pre></p>
 */
public final class SweepParticleListener implements PacketListener {

    private final MentalConfig config;

    public SweepParticleListener(@NotNull MentalConfig config) {
        this.config = config;
    }

    @Override
    public void onPacketSend(@NotNull PacketSendEvent event) {
        if (!PacketType.Play.Server.PARTICLE.equals(event.getPacketType())) {
            return;
        }
        if (!config.sweep().enabled()) {
            return; // zero-touch when module off
        }
        try {
            WrapperPlayServerParticle wrapper = new WrapperPlayServerParticle(event);
            Particle<?> particle = wrapper.getParticle();
            if (particle == null || particle.getType() == null) {
                return;
            }
            ResourceLocation name = particle.getType().getName();
            if (name == null) {
                return;
            }
            if (SweepParticles.isSweepParticle(name.toString())) {
                event.setCancelled(true);
            }
        } catch (Exception ignored) {
            // Never let a parse failure propagate on the netty thread.
        }
    }
}

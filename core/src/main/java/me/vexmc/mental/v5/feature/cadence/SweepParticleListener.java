package me.vexmc.mental.v5.feature.cadence;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;

/**
 * The netty (client-presentation) half of sword-sweep suppression: cancels the
 * 1.9 {@code sweep_attack} particle so the sweep visual never appears, as in
 * 1.7/1.8 (the retired {@code module.rules.sweep.SweepParticleListener} on the v5
 * seam).
 *
 * <p>Registered ONLY while a scope that needs it is open (the {@code SweepUnit},
 * and {@code AttackCooldownUnit} which re-disables sweep — mandate B5(d)), so it
 * needs no config-flag guard; the particle packet flows untouched when no such
 * scope holds it. Both the namespaced and bare particle names are matched. Netty
 * thread; never throws into the pipeline.</p>
 */
public final class SweepParticleListener extends PacketListenerAbstract {

    private static final String BARE = "sweep_attack";
    private static final String NAMESPACED = "minecraft:" + BARE;

    public SweepParticleListener() {
        super(PacketListenerPriority.NORMAL);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!PacketType.Play.Server.PARTICLE.equals(event.getPacketType())) {
            return;
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
            String id = name.toString();
            if (NAMESPACED.equals(id) || BARE.equals(id)) {
                event.setCancelled(true);
            }
        } catch (Exception ignored) {
            // Never let a parse failure propagate on the netty thread.
        }
    }
}

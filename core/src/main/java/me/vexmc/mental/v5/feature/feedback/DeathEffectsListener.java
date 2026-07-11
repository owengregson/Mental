package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleType;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.ParticleSpec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Plays the death-effects cosmetics at PlayerDeathEvent MONITOR — the moment a
 * player dies of any cause. This is the Bukkit half of {@code death-effects};
 * it shares the FEEDBACK family's {@link FeedbackEmit} sound/particle machinery
 * with {@link HitFeedbackListener} and journals its decision through the same
 * {@link FeedbackTrace} ring the tester asserts against.
 *
 * <h2>Damage-free by construction</h2>
 * The strike is a single {@code SPAWN_ENTITY} packet carrying a
 * {@code LIGHTNING_BOLT} — a client-only render with NO server entity behind it,
 * so it lights, cracks, and vanishes without fire, damage, or block change. No
 * thunder sound is ever sent: audio is owned entirely by the configured sound
 * list, so an operator hears exactly what they configured and nothing more.
 *
 * <h2>The 1.19 lightning floor</h2>
 * On 1.19+ a lightning bolt spawns through the generic spawn-entity packet; below
 * 1.19 it required the dedicated (and since-removed) global/weather-entity route,
 * whose id path is unreliable across the 1.9–1.18 range. Rather than ship a
 * subtly-wrong legacy bolt, lightning gates to 1.19+ (one boot line at assemble);
 * the sound and the burst still fire on every version.
 *
 * <p>Sounds and particles resolve to PacketEvents values ONCE at construction
 * (assemble time) against the era-correct {@link FeedbackSoundTable}; a name with
 * no id here is skipped and a colored-{@code dust} spec below 1.13 degrades to
 * {@code firework} sparks — each with one boot-report line. Nothing on the death
 * path touches the version.</p>
 */
public final class DeathEffectsListener implements Listener {

    /** Lightning render distance posture — vanilla shows a bolt to players within ~48 blocks. */
    private static final double AUDIENCE_RADIUS = 48.0;

    /** The burst spawns one block above the death feet position. */
    private static final double BURST_OFFSET = 1.0;

    /** Belt-and-braces destroy delay for the cosmetic bolt; the client self-expires it anyway. */
    private static final long LIGHTNING_LIFETIME_TICKS = 20L;

    /** The signature dust scale — vanilla's redstone-dust size at 1.0. */
    private static final float DUST_SCALE = 1.0f;

    private final PlayerManager playerManager;
    private final DeathLightning lightningTasks;
    private final FeedbackTrace trace;

    private final boolean lightning;
    private final List<FeedbackEmit.ResolvedSound> sounds;
    private final List<FeedbackEmit.ResolvedParticle> particles;
    private final boolean hasEffects;

    private final String soundSummary;
    private final String particleSummary;

    private final AtomicBoolean noEntityIdWarned = new AtomicBoolean(false);
    private final Logger logger;

    public DeathEffectsListener(
            DeathEffectsSettings settings, FeedbackSoundTable table, boolean modernBlockData,
            boolean lightningSupported, DeathLightning lightningTasks, FeedbackTrace trace, Logger logger) {
        this.playerManager = PacketEvents.getAPI().getPlayerManager();
        this.lightningTasks = lightningTasks;
        this.trace = trace;
        this.logger = logger;
        this.lightning = settings.lightning() && lightningSupported;
        this.sounds = FeedbackEmit.resolveSounds(settings.sounds(), table, "death-effects", "death", logger);
        this.particles = resolveParticles(settings.particles(), modernBlockData, logger);
        this.hasEffects = this.lightning || !this.sounds.isEmpty() || !this.particles.isEmpty();
        this.soundSummary = FeedbackEmit.summariseSounds(this.sounds);
        this.particleSummary = FeedbackEmit.summariseParticles(this.particles);

        if (settings.lightning() && !lightningSupported) {
            logger.info("death-effects: cosmetic lightning needs Paper 1.19+; below it the sound"
                    + " and burst still fire, but the bolt is skipped");
        }
    }

    /**
     * Voices one player death. Freezes the death location immediately, resolves
     * the nearby audience (same world, within {@value #AUDIENCE_RADIUS} blocks, no
     * exclusions — the victim sees their own send-off), ships the strike, and
     * journals the decision keyed to the killer (or {@code null}) and the victim.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!hasEffects) {
            return; // VANILLA preset (or empty CUSTOM) — strict nothing, zero-touch
        }
        Player victim = event.getEntity();
        Location death = victim.getLocation().clone(); // freeze before anything downstream moves the body
        Player killer = victim.getKiller();
        UUID killerId = killer == null ? null : killer.getUniqueId();
        UUID victimId = victim.getUniqueId();
        World world = victim.getWorld();

        double radiusSquared = AUDIENCE_RADIUS * AUDIENCE_RADIUS;
        List<Player> audience = new ArrayList<>();
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (!world.equals(candidate.getWorld())) {
                continue;
            }
            if (candidate.getLocation().distanceSquared(death) > radiusSquared) {
                continue;
            }
            audience.add(candidate);
        }

        if (audience.isEmpty()) {
            trace.record(new FeedbackTrace.Entry(
                    "death-effects", killerId, victimId, "NO_VIEWERS",
                    "no audience within " + String.format(Locale.ROOT, "%.1f", AUDIENCE_RADIUS) + " blocks"));
            return;
        }

        boolean struckLightning = emit(death, audience);
        trace.record(new FeedbackTrace.Entry(
                "death-effects", killerId, victimId, "EMITTED", detail(struckLightning)));
    }

    /**
     * Builds each packet ONCE and ships it to the whole audience with a single
     * flush per viewer — one shared lightning entity id, one shared spawn, the
     * resolved sound layers, and the burst. Returns whether the bolt actually
     * shipped (a failed id generation degrades to sound+burst only). The send is
     * wrapped catch-Throwable: a viewer mid-(re)configuration can throw inside
     * PacketEvents, and a missed cosmetic beats a surfaced pipeline exception.
     */
    private boolean emit(Location death, List<Player> audience) {
        Vector3d at = new Vector3d(death.getX(), death.getY(), death.getZ());
        List<PacketWrapper<?>> soundPackets = soundPacketsFor(at);
        Vector3d burstAt = new Vector3d(death.getX(), death.getY() + BURST_OFFSET, death.getZ());
        List<PacketWrapper<?>> particlePackets = FeedbackEmit.particlePackets(particles, burstAt);

        int boltId = -1;
        WrapperPlayServerSpawnEntity spawn = null;
        if (lightning) {
            try {
                boltId = SpigotReflectionUtil.generateEntityId();
                spawn = lightningSpawn(boltId, at);
            } catch (IllegalStateException noId) {
                if (noEntityIdWarned.compareAndSet(false, true)) {
                    logger.warning("death-effects: could not generate a lightning entity id on this server"
                            + " — the bolt is skipped; sound and burst still fire");
                }
                spawn = null;
            }
        }

        try {
            for (Player viewer : audience) {
                User user = playerManager.getUser(viewer);
                if (user == null) {
                    continue; // synthetic / disconnecting player, in-process bot — skip this viewer
                }
                if (spawn != null) {
                    user.writePacketSilently(spawn);
                }
                for (PacketWrapper<?> packet : soundPackets) {
                    user.writePacketSilently(packet);
                }
                for (PacketWrapper<?> packet : particlePackets) {
                    user.writePacketSilently(packet);
                }
                user.flushPackets();
            }
        } catch (Throwable reconfiguring) {
            // A missed cosmetic beats a surfaced exception on the send path.
        }

        if (spawn != null) {
            int destroyId = boltId;
            List<Player> destroyAudience = new ArrayList<>(audience);
            lightningTasks.destroyAfter(LIGHTNING_LIFETIME_TICKS, () -> destroy(destroyId, destroyAudience));
            return true;
        }
        return false;
    }

    /** Ships the belt-and-braces {@code destroy-entities} for the shared bolt id to the captured audience. */
    private void destroy(int boltId, List<Player> audience) {
        WrapperPlayServerDestroyEntities destroy = new WrapperPlayServerDestroyEntities(boltId);
        try {
            for (Player viewer : audience) {
                User user = playerManager.getUser(viewer);
                if (user == null) {
                    continue; // disconnected since the strike — nothing to unrender
                }
                user.writePacketSilently(destroy);
                user.flushPackets();
            }
        } catch (Throwable reconfiguring) {
            // The client self-expires the bolt anyway; a missed destroy is harmless.
        }
    }

    private List<PacketWrapper<?>> soundPacketsFor(Vector3d position) {
        List<PacketWrapper<?>> packets = new ArrayList<>(sounds.size());
        for (FeedbackEmit.ResolvedSound sound : sounds) {
            packets.add(new WrapperPlayServerSoundEffect(
                    sound.sound(), SoundCategory.PLAYER, position, sound.volume(), sound.pitch()));
        }
        return packets;
    }

    private String detail(boolean struckLightning) {
        return "lightning=" + struckLightning
                + " sounds=[" + soundSummary + "]"
                + " particles=[" + particleSummary + "]";
    }

    // ------------------------------------------------------------------------
    // Packet shapes (package-private statics — pinned by DeathEffectsPacketsTest).
    // ------------------------------------------------------------------------

    /**
     * The cosmetic-bolt spawn: a {@code LIGHTNING_BOLT} at {@code at} with no UUID
     * and no velocity. A client-only render — there is no server entity, so it
     * cannot deal fire, damage, or block interaction.
     */
    static WrapperPlayServerSpawnEntity lightningSpawn(int entityId, Vector3d at) {
        return new WrapperPlayServerSpawnEntity(
                entityId, Optional.empty(), EntityTypes.LIGHTNING_BOLT, at,
                0.0f, 0.0f, 0.0f, 0, Optional.empty());
    }

    /**
     * Maps a {@code RRGGBB} hex string to a unit-scale dust particle. Each 0–255
     * channel rides PacketEvents' int {@code ParticleDustData} constructor, which
     * round-trips it through {@code n/255f} on the wire (so {@code 0xAA → 0.667}).
     */
    static ParticleDustData dustData(String hex) {
        DustColor color = DustColor.of(hex);
        return new ParticleDustData(DUST_SCALE, color.red(), color.green(), color.blue());
    }

    // ------------------------------------------------------------------------
    // Assemble-time particle resolution (once; never on the death path).
    // ------------------------------------------------------------------------

    private static List<FeedbackEmit.ResolvedParticle> resolveParticles(
            List<ParticleSpec> specs, boolean modernBlockData, Logger logger) {
        List<FeedbackEmit.ResolvedParticle> resolved = new ArrayList<>(specs.size());
        for (ParticleSpec spec : specs) {
            resolved.add(buildParticle(spec, modernBlockData, logger));
        }
        return resolved;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static FeedbackEmit.ResolvedParticle buildParticle(
            ParticleSpec spec, boolean modernBlockData, Logger logger) {
        String name = spec.particle() == null ? "" : spec.particle().trim().toLowerCase(Locale.ROOT);
        if ("dust".equals(name)) {
            String hex = spec.block() == null ? "" : spec.block().trim();
            if (modernBlockData && DustColor.isValid(hex)) {
                return new FeedbackEmit.ResolvedParticle(
                        new Particle<>(ParticleTypes.DUST, dustData(hex)), spec, "dust:" + hex.toLowerCase(Locale.ROOT));
            }
            // Colored dust data needs 1.13+; below it, degrade to firework sparks.
            logger.info("death-effects: colored dust '" + spec.block()
                    + "' is not resolvable on this server — using firework sparks");
            return new FeedbackEmit.ResolvedParticle(new Particle<>(ParticleTypes.FIREWORK), spec, "firework");
        }
        try {
            ParticleType<?> type = ParticleTypes.getByName(name);
            if (type != null) {
                return new FeedbackEmit.ResolvedParticle(new Particle(type), spec, name);
            }
        } catch (Throwable unresolved) {
            // Fall through to the firework degrade below.
        }
        logger.info("death-effects: particle '" + spec.particle()
                + "' is not resolvable on this server — using firework sparks");
        return new FeedbackEmit.ResolvedParticle(new Particle<>(ParticleTypes.FIREWORK), spec, "firework");
    }
}

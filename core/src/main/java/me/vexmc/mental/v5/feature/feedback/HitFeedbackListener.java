package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleBlockStateData;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleType;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.sound.SoundCategory;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.ParticleSpec;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.Preset;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Emits the replacement hit feedback at EDBEE MONITOR — the once-per-landed-melee
 * seam that fires on the victim's region thread on every delivery path. This is
 * the Bukkit half of {@code hit-feedback}; the {@link HurtSoundSuppressor} is the
 * netty half.
 *
 * <h2>The two-audience model</h2>
 * The listener plays to vanilla's <em>exact</em> hurt-sound audience — nearby
 * players in the victim's world, EXCLUDING the victim — because that is precisely
 * who the vanilla broadcast (the one the suppressor eats) would have reached, and
 * the victim never hears its own hurt sound in vanilla. Particles, however, go to
 * everyone nearby INCLUDING the victim: the victim should see the burst that
 * lands on them. Two overlapping audiences, one loop, one flush per viewer.
 *
 * <h2>Why the mark</h2>
 * Before it plays anything, the listener ARMS a {@link HurtSoundMarks} mark keyed
 * to this victim. The suppressor consumes exactly that mark to cancel the single
 * vanilla {@code entity.player.hurt} broadcast this same hit triggers — so the
 * replacement stands alone, while a fall/fire hurt sound (no mark) still plays.
 * Suppression is mark-scoped, not a blanket cancel, precisely so environmental
 * hurt sounds survive.
 *
 * <p>Sounds and particles are resolved to PacketEvents values ONCE at construction
 * (assemble time), against the era-correct {@link FeedbackSoundTable}; a name with
 * no id on this server is skipped and a block particle with unresolvable data
 * degrades to {@code crit}, each with a single boot-report line. Nothing on the
 * hot path touches the version.</p>
 */
public final class HitFeedbackListener implements Listener {

    /** The particle spawns at mid-chest — feet plus this, in blocks. */
    private static final double CHEST_OFFSET = 1.2;

    /** The era hurt-sound pitch jitter half-range: {@code 1.0 + (r1 - r2) * 0.2}. */
    private static final float VANILLA_JITTER = 0.2f;

    private final HurtSoundMarks marks;
    private final TickClock clock;
    private final FeedbackTrace trace;
    private final PlayerManager playerManager;

    private final boolean vanillaPreset;
    private final double lowHealthCeiling; // post-hit health (in half-hearts) below which the extra layer fires
    private final double audienceRadius;

    private final List<FeedbackEmit.ResolvedSound> normalSounds;
    private final List<FeedbackEmit.ResolvedSound> lowHealthSounds;
    private final List<FeedbackEmit.ResolvedParticle> particles;

    private final String soundSummary;
    private final String lowHealthSummary;
    private final String particleSummary;

    public HitFeedbackListener(
            HitFeedbackSettings settings, FeedbackSoundTable table, boolean modernBlockData,
            HurtSoundMarks marks, TickClock clock, FeedbackTrace trace, Logger logger) {
        this.marks = marks;
        this.clock = clock;
        this.trace = trace;
        this.playerManager = PacketEvents.getAPI().getPlayerManager();
        this.vanillaPreset = settings.preset() == Preset.VANILLA;
        this.lowHealthCeiling = settings.lowHealthThresholdHearts() * 2.0;
        this.normalSounds = FeedbackEmit.resolveSounds(settings.sounds(), table, "hit-feedback", "hit", logger);
        this.lowHealthSounds =
                FeedbackEmit.resolveSounds(settings.lowHealthSounds(), table, "hit-feedback", "low-health", logger);
        this.particles = resolveParticles(settings.particles(), modernBlockData, logger);
        this.audienceRadius = 16.0 * Math.max(1.0, maxVolume(this.normalSounds));
        this.soundSummary = FeedbackEmit.summariseSounds(this.normalSounds);
        this.lowHealthSummary = FeedbackEmit.summariseSounds(this.lowHealthSounds);
        this.particleSummary = FeedbackEmit.summariseParticles(this.particles);
    }

    /**
     * Voices one landed melee. Arms the suppressor, resolves vanilla's audience,
     * plays the replacement sounds to nearby players (never the victim) and the
     * particle burst to everyone nearby (the victim included), layers the extra
     * low-health sounds on a non-killing hit that drops the victim below the
     * threshold, and journals the decision.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        double finalDamage = event.getFinalDamage();
        if (finalDamage <= 0.0) {
            return;
        }

        Location loc = victim.getLocation();
        long now = clock.current().value();
        // 1. Arm the suppressor for THIS hit — the vanilla broadcast that follows is now eaten.
        marks.mark(victim.getEntityId(), loc.getX(), loc.getY(), loc.getZ(), now);

        // 2. Vanilla's exact audience, sized by the loudest replacement sound.
        double radiusSquared = audienceRadius * audienceRadius;
        World world = victim.getWorld();
        UUID victimId = victim.getUniqueId();
        List<Player> nearby = new ArrayList<>(); // particle audience (victim INCLUDED)
        boolean anySoundViewer = false;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (!world.equals(candidate.getWorld())) {
                continue;
            }
            if (candidate.getLocation().distanceSquared(loc) > radiusSquared) {
                continue;
            }
            nearby.add(candidate);
            if (!candidate.getUniqueId().equals(victimId)) {
                anySoundViewer = true;
            }
        }

        // 3. Post-hit health decides the extra low-health layer. A killing hit
        //    plays the NORMAL set but NEVER the extra — death-effects owns the moment.
        double postHitHealth = victim.getHealth() - finalDamage;
        boolean killing = postHitHealth <= 0.0;
        boolean lowHealth = !killing && postHitHealth < lowHealthCeiling;

        if (!nearby.isEmpty()) {
            emit(loc, nearby, victimId, lowHealth);
        }

        String decision = anySoundViewer ? (lowHealth ? "EMITTED+LOW_HP" : "EMITTED") : "NO_VIEWERS";
        trace.record(new FeedbackTrace.Entry(
                "hit-feedback", attacker.getUniqueId(), victimId, decision, detail(decision, lowHealth)));
    }

    /**
     * Builds each per-hit packet ONCE and ships it — sounds to the non-victim
     * viewers, particles to all, a single flush per viewer. Vanilla computes the
     * pitch jitter and particle counts once server-side and ships identical bytes
     * to every listener, so a single set of wrappers is reused across viewers.
     *
     * <p>The writes are SILENT ({@code writePacketSilently}, the {@code BurstSender}
     * seam): they enter the pipeline at Mental's own encoder, skipping Mental's own
     * PE send-event stage — where the {@link HurtSoundSuppressor} watches. Otherwise
     * the VANILLA preset's replacement (which IS {@code entity.player.hurt}) would
     * be written while this hit's mark is still armed, and the suppressor would eat
     * our OWN replacement. Silent writes keep the suppressor scoped to the server's
     * vanilla broadcast alone.</p>
     *
     * <p>The whole send is wrapped in a catch-Throwable: a viewer mid-(re)configuration
     * can throw inside PacketEvents, and a missed cosmetic beats a surfaced pipeline
     * exception (the {@code BurstSender} posture).</p>
     */
    private void emit(Location loc, List<Player> nearby, UUID victimId, boolean lowHealth) {
        Vector3d soundPosition = new Vector3d(loc.getX(), loc.getY(), loc.getZ());
        List<PacketWrapper<?>> soundPackets = soundPacketsFor(normalSounds, soundPosition);
        if (lowHealth) {
            soundPackets.addAll(soundPacketsFor(lowHealthSounds, soundPosition));
        }
        Vector3d chest = new Vector3d(loc.getX(), loc.getY() + CHEST_OFFSET, loc.getZ());
        List<PacketWrapper<?>> particlePackets = FeedbackEmit.particlePackets(particles, chest);

        try {
            for (Player viewer : nearby) {
                User user = playerManager.getUser(viewer);
                if (user == null) {
                    continue; // synthetic / disconnecting player, in-process bot — skip this viewer
                }
                if (!viewer.getUniqueId().equals(victimId)) {
                    for (PacketWrapper<?> packet : soundPackets) {
                        user.writePacketSilently(packet);
                    }
                }
                for (PacketWrapper<?> packet : particlePackets) {
                    user.writePacketSilently(packet);
                }
                user.flushPackets();
            }
        } catch (Throwable reconfiguring) {
            // A missed cosmetic beats a surfaced exception on the send path.
        }
    }

    private List<PacketWrapper<?>> soundPacketsFor(List<FeedbackEmit.ResolvedSound> sounds, Vector3d position) {
        List<PacketWrapper<?>> packets = new ArrayList<>(sounds.size());
        for (FeedbackEmit.ResolvedSound sound : sounds) {
            float pitch = vanillaPreset ? vanillaJitter() : sound.pitch();
            packets.add(new WrapperPlayServerSoundEffect(
                    sound.sound(), SoundCategory.PLAYER, position, sound.volume(), pitch));
        }
        return packets;
    }

    private static float vanillaJitter() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return 1.0f + (random.nextFloat() - random.nextFloat()) * VANILLA_JITTER;
    }

    private String detail(String decision, boolean lowHealth) {
        if ("NO_VIEWERS".equals(decision)) {
            return "no audience within " + String.format(Locale.ROOT, "%.1f", audienceRadius) + " blocks";
        }
        String extra = lowHealth && !lowHealthSummary.isEmpty() ? " +low[" + lowHealthSummary + "]" : "";
        return "sounds=[" + soundSummary + "]" + extra + " particles=[" + particleSummary + "]";
    }

    // ------------------------------------------------------------------------
    // Assemble-time resolution (once; never on the hot path).
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
    private static FeedbackEmit.ResolvedParticle buildParticle(ParticleSpec spec, boolean modernBlockData, Logger logger) {
        String name = spec.particle() == null ? "" : spec.particle().trim().toLowerCase(Locale.ROOT);
        String label = name;
        if ("block".equals(name) && spec.block() != null && !spec.block().isBlank()) {
            label = "block:" + spec.block();
            if (modernBlockData) {
                try {
                    WrappedBlockState state = WrappedBlockState.getByString(spec.block());
                    if (state != null) {
                        return new FeedbackEmit.ResolvedParticle(
                                new Particle<>(ParticleTypes.BLOCK, new ParticleBlockStateData(state)), spec, label);
                    }
                } catch (Throwable inconvertible) {
                    // Fall through to the crit degrade below.
                }
            }
            logger.info("hit-feedback: block particle '" + spec.block()
                    + "' is not resolvable on this server — using crit");
            return new FeedbackEmit.ResolvedParticle(new Particle<>(ParticleTypes.CRIT), spec, "crit");
        }
        try {
            ParticleType<?> type = ParticleTypes.getByName(name);
            if (type != null) {
                return new FeedbackEmit.ResolvedParticle(new Particle(type), spec, label);
            }
        } catch (Throwable unresolved) {
            // Fall through to the crit degrade below.
        }
        logger.info("hit-feedback: particle '" + spec.particle() + "' is not resolvable on this server — using crit");
        return new FeedbackEmit.ResolvedParticle(new Particle<>(ParticleTypes.CRIT), spec, "crit");
    }

    private static double maxVolume(List<FeedbackEmit.ResolvedSound> sounds) {
        double max = 1.0;
        for (FeedbackEmit.ResolvedSound sound : sounds) {
            max = Math.max(max, sound.volume());
        }
        return max;
    }
}

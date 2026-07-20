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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.ParticleSpec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
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
 * <h2>The audience model</h2>
 * Three overlapping sinks share one loop and one flush per viewer, split by who
 * vanilla would have reached:
 * <ul>
 *   <li>The <b>normal hit sounds</b> play to vanilla's <em>exact</em> hurt-sound
 *       audience — nearby players in the victim's world, EXCLUDING the victim —
 *       because that is precisely who the vanilla broadcast (the one the suppressor
 *       eats) would have reached, and the victim never hears its own hurt sound in
 *       vanilla.</li>
 *   <li>The <b>low-health layer</b> is a Mental-only cue, not a vanilla broadcast,
 *       so it is NOT bound by the victim-exclusion contract: it plays to everyone
 *       nearby INCLUDING the victim, so the person actually in danger hears their
 *       own heartbeat. It ships only on a non-killing hit that drops the victim
 *       below the percent threshold (death-effects owns the killing moment) and
 *       only when a layer is configured.</li>
 *   <li><b>Particles</b> go to everyone nearby INCLUDING the victim: the victim
 *       should see the burst that lands on them.</li>
 * </ul>
 * Because the victim IS an audience of the low-health layer, a low-health hit
 * whose only nearby player is the victim still reads {@code EMITTED+LOW_HP} — the
 * module DID emit (to the victim), it just had no normal-sound audience.
 *
 * <h2>Why the mark</h2>
 * Before it plays anything, the listener ARMS a {@link HurtSoundMarks} mark keyed
 * to this victim. The suppressor matches that mark to cancel the vanilla
 * {@code entity.player.hurt} broadcast this same hit triggers — every per-viewer
 * packet of it (2.6.1) — so the replacement stands alone, while a fall/fire hurt
 * sound outside a mark's window still plays. Suppression is mark-scoped, not a
 * blanket cancel, precisely so environmental hurt sounds survive.
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
    /** Per-victim last-voiced tick — the same-tick voice dedup (2.6.1). Region threads
     *  may differ per victim on Folia, hence concurrent; pruned opportunistically. */
    private final ConcurrentHashMap<UUID, Long> lastVoiceTick = new ConcurrentHashMap<>();

    private final TickClock clock;
    private final FeedbackTrace trace;
    private final PlayerManager playerManager;

    private final boolean vanillaTune;
    private final double lowHealthThresholdPercent; // percent of the victim's max health below which the layer fires
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
        // The era jitter is value-derived since the 2.5.3 preset library killed
        // the per-module preset enum: a sound set that IS the vanilla broadcast
        // (exactly entity.player.hurt at 1.0/1.0 — the vanilla preset, or any
        // preset reproducing its values) keeps vanilla's per-broadcast pitch
        // jitter instead of a robotic flat 1.0.
        this.vanillaTune = settings.vanillaTune();
        this.lowHealthThresholdPercent = settings.lowHealthThresholdPercent();
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
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return; // an armor stand / item frame has no hurt voice to replace
        }
        // Any DEALER voices the hit, not just a player: a zombie punching you, a
        // skeleton's arrow (unwrapped to its shooter for the trace) and a player's
        // sword all replace the same vanilla hurt sound. The victim may equally be a
        // mob — hitting a zombie now plays the server's configured hit chord.
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            attacker = shooter;
        }
        double finalDamage = event.getFinalDamage();
        if (finalDamage < 0.0) {
            return; // insanity guard only — a ZERO-damage melee is a CONNECTED hit (2.6.0)
        }
        // A non-cancelled 0-final-damage melee (a StarEnchants Blacksmith proc
        // zeroing the fold, a plugin's setDamage(0)) still connected: vanilla
        // still flinches/knocks it (snowball semantics), so the custom voice
        // plays and — critically — the mark below arms, eating the vanilla
        // broadcast. The old finalDamage<=0 early-return skipped BOTH: the
        // player heard the raw vanilla hurt sound exactly where the custom one
        // should be (the 2.6.0 SE-compat incoherence). The damage INDICATOR
        // keeps its own 0-guard — no "0" stands; only the voice is coherent.

        // The mid-window DELTA hit is ERA-SILENT (2.6.0 — the owner's
        // double-sound-on-crits report): a stronger hit inside the victim's
        // half-open invulnerability window deals only the difference, and
        // vanilla zeroes the fresh-hit flag that gates EVERY client effect —
        // no hurt sound, no flinch, no knockback (the compendium: "a stronger
        // hit mid-invuln deals difference damage with NO knock and no flinch").
        // Voicing it doubled the hit chord exactly when a simulate-crits 1.5×
        // (attacker falling — crit ≡ airborne) interleaved a plain hit inside
        // the window. The predicate is the ONE shared {@link UpgradeWindow} the
        // damage-indicators window book also reads (they can never drift). No
        // suppressor mark either: vanilla broadcasts nothing for a delta hit,
        // and a phantom mark would eat the NEXT legitimate broadcast. The delta
        // damage INDICATOR no longer stays as its own crit-styled ghost either
        // (2026-07-12, owner-directed — superseding the 2.5.5 "the number is
        // information" choice): the window book folds the delta into the ONE
        // rolled marker for the victim's window, so era-silence and one-marker
        // now hold together.
        if (UpgradeWindow.isDelta(victim)) {
            trace.record(new FeedbackTrace.Entry(
                    "hit-feedback", attacker.getUniqueId(), victim.getUniqueId(),
                    "ERA_SILENT_DELTA", "mid-window delta hit — vanilla/era play nothing"));
            return;
        }

        Location loc = victim.getLocation();
        long now = clock.current().value();

        // One voice per victim per tick (2.6.1 — the same-tick dedup the
        // indicators always had via the merge book, which sounds lacked): a
        // second accepted ENTITY_ATTACK EDBEE landing on this victim in the same
        // tick (a plugin clearing the window and re-dealing, same-tick bonus
        // instances) merges into the first hit's voice instead of chording
        // twice. Coherence holds without a second mark: the first hit's mark is
        // broadcast-scoped now, so it eats the merged hit's vanilla broadcast
        // too, and the indicator half is the merge book's. Recorded, never a
        // silent skip. Opportunistic prune keeps the map bounded.
        Long previousVoice = lastVoiceTick.put(victim.getUniqueId(), now);
        if (previousVoice != null && previousVoice.longValue() == now) {
            trace.record(new FeedbackTrace.Entry(
                    "hit-feedback", attacker.getUniqueId(), victim.getUniqueId(),
                    "SAME_TICK_MERGED", "second accepted hit this tick — one voice per victim per tick"));
            return;
        }
        if (lastVoiceTick.size() > 512) {
            lastVoiceTick.values().removeIf(tick -> now - tick > 40);
        }

        // 1. Arm the suppressor for THIS hit — the vanilla broadcast that follows is now eaten.
        marks.mark(victim.getEntityId(), loc.getX(), loc.getY(), loc.getZ(), now);

        // 2. Vanilla's exact audience, sized by the loudest replacement sound.
        double radiusSquared = audienceRadius * audienceRadius;
        World world = victim.getWorld();
        UUID victimId = victim.getUniqueId();
        List<Player> nearby = new ArrayList<>(); // particle + low-health audience (victim INCLUDED)
        boolean anySoundViewer = false; // any NON-victim nearby — the normal-sound audience
        boolean victimNearby = false;   // the victim itself in range — the low-health-layer audience
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (!world.equals(candidate.getWorld())) {
                continue;
            }
            if (candidate.getLocation().distanceSquared(loc) > radiusSquared) {
                continue;
            }
            nearby.add(candidate);
            if (candidate.getUniqueId().equals(victimId)) {
                victimNearby = true;
            } else {
                anySoundViewer = true;
            }
        }

        // 3. Post-hit health decides the extra low-health layer. A killing hit
        //    plays the NORMAL set but NEVER the extra — death-effects owns the
        //    moment. An EMPTY effective layer (the VANILLA preset, a bare custom)
        //    never reads as LOW_HP either: the decision describes what the module
        //    DID, and with no layer configured nothing extra was played.
        double postHitHealth = victim.getHealth() - finalDamage;
        boolean killing = postHitHealth <= 0.0;
        // The ceiling is per-hit: a PERCENT of THIS victim's own maximum health,
        // so a scaled-max-health victim gets a proportional threshold. getMaxHealth()
        // is deliberate — the Attribute enum constant (GENERIC_MAX_HEALTH → MAX_HEALTH)
        // was renamed in 1.21.2+, but this deprecated accessor is stable across the
        // whole 1.9.4 → 26.x range, so the hot path never touches the version.
        @SuppressWarnings("deprecation")
        double lowHealthCeiling = victim.getMaxHealth() * lowHealthThresholdPercent / 100.0;
        boolean lowHealth = !killing && postHitHealth < lowHealthCeiling && !lowHealthSounds.isEmpty();

        if (!nearby.isEmpty()) {
            emit(loc, nearby, victimId, lowHealth);
        }

        // The victim is an audience of the low-health layer, so a low-health hit
        // whose only nearby player is the victim still EMITTED (the layer shipped to
        // the victim). Otherwise a normal-sound audience decides EMITTED vs NO_VIEWERS,
        // and the layer marks LOW_HP when it fired.
        String decision = lowHealth && victimNearby
                ? "EMITTED+LOW_HP"
                : (anySoundViewer ? (lowHealth ? "EMITTED+LOW_HP" : "EMITTED") : "NO_VIEWERS");
        trace.record(new FeedbackTrace.Entry(
                "hit-feedback", attacker.getUniqueId(), victimId, decision, detail(decision, lowHealth)));
    }

    /**
     * Builds each per-hit packet ONCE and ships it — normal sounds to the non-victim
     * viewers, the low-health layer AND particles to everyone nearby (the victim
     * included), a single flush per viewer. Vanilla computes the pitch jitter and
     * particle counts once server-side and ships identical bytes to every listener,
     * so a single set of wrappers is reused across viewers.
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
    /**
     * The low-health WARNING layer for damage that is not a hit: fall, lava, fire,
     * drowning, freezing, poison. The combat voice deliberately does NOT extend here —
     * vanilla's cause-specific hurt sounds ({@code hurt_on_fire}, {@code hurt_drown},
     * {@code hurt_freeze}) are how a player knows WHY they are dying, and replacing
     * them with one combat chord would erase that cue (owner ruling: hit sounds are
     * for combat). The danger heartbeat is different — it is a warning about health,
     * not about the blow, so it fires on every cause.
     *
     * <p>Entity-dealt damage returns immediately: {@link #onHit} already owns it and
     * layers the same warning itself, so this never double-plays.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnvironmentalDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            return; // the combat path owns it, low-health layer included
        }
        if (lowHealthSounds.isEmpty() || !(event.getEntity() instanceof Player victim)) {
            return; // the cue is a player's; a mob has no client to warn
        }
        double finalDamage = event.getFinalDamage();
        if (finalDamage <= 0.0) {
            return;
        }
        double postHitHealth = victim.getHealth() - finalDamage;
        @SuppressWarnings("deprecation")
        double ceiling = victim.getMaxHealth() * lowHealthThresholdPercent / 100.0;
        if (postHitHealth <= 0.0 || postHitHealth >= ceiling) {
            return; // death-effects owns the killing moment; above the line, no warning
        }
        Location loc = victim.getLocation();
        emitLowHealthOnly(loc, audienceAround(loc));
        trace.record(new FeedbackTrace.Entry(
                "hit-feedback", null, victim.getUniqueId(), "LOW_HP_ENVIRONMENTAL",
                "cause=" + event.getCause() + " hp=" + postHitHealth + " " + lowHealthSummary));
    }

    /**
     * The same radius sweep the combat path performs inline, for the environmental
     * layer — everyone in range INCLUDING the victim, since the warning is theirs.
     */
    private List<Player> audienceAround(Location loc) {
        double radiusSquared = audienceRadius * audienceRadius;
        World world = loc.getWorld();
        List<Player> nearby = new ArrayList<>();
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (!world.equals(candidate.getWorld())) {
                continue;
            }
            if (candidate.getLocation().distanceSquared(loc) <= radiusSquared) {
                nearby.add(candidate);
            }
        }
        return nearby;
    }

    /** Ships ONLY the low-health layer (victim included — the danger cue is theirs). */
    private void emitLowHealthOnly(Location loc, List<Player> nearby) {
        Vector3d position = new Vector3d(loc.getX(), loc.getY(), loc.getZ());
        List<PacketWrapper<?>> packets = soundPacketsFor(lowHealthSounds, position);
        try {
            for (Player viewer : nearby) {
                User user = playerManager.getUser(viewer);
                if (user == null) {
                    continue;
                }
                for (PacketWrapper<?> packet : packets) {
                    user.writePacketSilently(packet);
                }
                user.flushPackets();
            }
        } catch (Throwable reconfiguring) {
            // A missed cosmetic beats a surfaced exception on the send path.
        }
    }

    private void emit(Location loc, List<Player> nearby, UUID victimId, boolean lowHealth) {
        Vector3d soundPosition = new Vector3d(loc.getX(), loc.getY(), loc.getZ());
        // The three sinks stay SEPARATE: normal sounds are victim-excluded (the
        // vanilla-audience contract), while the low-health layer ships to the victim
        // too (its own list, not appended to the normal set). Built once, reused.
        List<PacketWrapper<?>> normalSoundPackets = soundPacketsFor(normalSounds, soundPosition);
        List<PacketWrapper<?>> lowHealthPackets =
                lowHealth ? soundPacketsFor(lowHealthSounds, soundPosition) : List.of();
        Vector3d chest = new Vector3d(loc.getX(), loc.getY() + CHEST_OFFSET, loc.getZ());
        List<PacketWrapper<?>> particlePackets = FeedbackEmit.particlePackets(particles, chest);

        try {
            for (Player viewer : nearby) {
                User user = playerManager.getUser(viewer);
                if (user == null) {
                    continue; // synthetic / disconnecting player, in-process bot — skip this viewer
                }
                if (!viewer.getUniqueId().equals(victimId)) {
                    for (PacketWrapper<?> packet : normalSoundPackets) {
                        user.writePacketSilently(packet);
                    }
                }
                for (PacketWrapper<?> packet : lowHealthPackets) {
                    user.writePacketSilently(packet); // victim included — the danger cue is theirs
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
            float pitch = vanillaTune ? vanillaJitter() : sound.pitch();
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

package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.sound.Sound;
import com.github.retrooper.packetevents.protocol.sound.Sounds;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.Mode;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.ParticleSpec;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.SoundSpec;

/**
 * The FEEDBACK family's shared assemble-time resolution and hot-path packet
 * building — the pieces {@link HitFeedbackListener} and {@link DeathEffectsListener}
 * emit identically. Sound resolution runs against the era-correct
 * {@link FeedbackSoundTable} once at assemble; the particle burst geometry
 * (SPREAD's per-axis Gaussian σ vs EMANATE's outward speed) and its per-emit
 * count roll are identical wire shapes. Each listener keeps only what genuinely
 * differs — its particle <em>resolution</em> (block-state dust degrading to
 * {@code crit} for hit-feedback; colored dust degrading to {@code firework} for
 * death-effects) and its sound-packet pitch policy (hit-feedback jitters the
 * VANILLA replacement; death-effects plays the fixed spec pitch).
 */
final class FeedbackEmit {

    private FeedbackEmit() {
    }

    /** A sound resolved to a PacketEvents {@link Sound} plus its emit volume/pitch and final name. */
    record ResolvedSound(Sound sound, float volume, float pitch, String name) {
    }

    /** A particle resolved to a PacketEvents {@link Particle} plus its burst geometry. */
    record ResolvedParticle(
            Particle<?> particle, int countMin, int countMax, Mode mode, float speed,
            double spreadX, double spreadY, double spreadZ, String label) {

        ResolvedParticle(Particle<?> particle, ParticleSpec spec, String label) {
            this(particle, spec.countMin(), spec.countMax(), spec.mode(), spec.speed(),
                    spec.spreadX(), spec.spreadY(), spec.spreadZ(), label);
        }
    }

    /**
     * Resolves each spec's sound name against the era-correct table (once, at
     * assemble): a name with no id on this server is skipped and a fallback that
     * differs from the requested name is noted — each with a single boot-report
     * line under {@code module}. {@code kind} labels the layer in the log line.
     */
    static List<ResolvedSound> resolveSounds(
            List<SoundSpec> specs, FeedbackSoundTable table, String module, String kind, Logger logger) {
        List<ResolvedSound> resolved = new ArrayList<>(specs.size());
        for (SoundSpec spec : specs) {
            String name = table.resolve(spec.sound());
            if (name.isEmpty()) {
                logger.info(module + ": " + kind + " sound '" + spec.sound()
                        + "' has no era-correct id on this server — skipped");
                continue;
            }
            if (!name.equals(FeedbackSoundTable.normalize(spec.sound()))) {
                logger.info(module + ": " + kind + " sound '" + spec.sound()
                        + "' resolves to '" + name + "' on this server");
            }
            resolved.add(new ResolvedSound(Sounds.getByNameOrCreate(name), spec.volume(), spec.pitch(), name));
        }
        return resolved;
    }

    /**
     * Builds one particle wrapper per resolved burst at {@code at}: SPREAD writes
     * the per-axis Gaussian σ into vanilla's offset field with zero speed; EMANATE
     * bursts outward from the point at the burst speed with a zero offset. The
     * count is rolled per emit inside {@link #randomCount}.
     */
    static List<PacketWrapper<?>> particlePackets(List<ResolvedParticle> particles, Vector3d at) {
        List<PacketWrapper<?>> packets = new ArrayList<>(particles.size());
        for (ResolvedParticle particle : particles) {
            int count = randomCount(particle.countMin(), particle.countMax());
            Vector3f offset;
            float speed;
            if (particle.mode() == Mode.EMANATE) {
                offset = new Vector3f(0.0f, 0.0f, 0.0f); // burst outward from the point
                speed = particle.speed();
            } else {
                offset = new Vector3f( // SPREAD: the offset field is vanilla's per-axis Gaussian sigma
                        (float) particle.spreadX(), (float) particle.spreadY(), (float) particle.spreadZ());
                speed = 0.0f;
            }
            packets.add(new WrapperPlayServerParticle(particle.particle(), false, at, offset, speed, count));
        }
        return packets;
    }

    /** A uniform count in {@code [min, max]}, clamped non-negative and min≤max. */
    static int randomCount(int min, int max) {
        int low = Math.max(0, min);
        int high = Math.max(low, max);
        return low == high ? low : ThreadLocalRandom.current().nextInt(low, high + 1);
    }

    static String summariseSounds(List<ResolvedSound> sounds) {
        StringBuilder builder = new StringBuilder();
        for (ResolvedSound sound : sounds) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(sound.name());
        }
        return builder.toString();
    }

    static String summariseParticles(List<ResolvedParticle> particles) {
        StringBuilder builder = new StringBuilder();
        for (ResolvedParticle particle : particles) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(particle.label()).append('×')
                    .append(particle.countMin()).append('-').append(particle.countMax());
        }
        return builder.toString();
    }
}

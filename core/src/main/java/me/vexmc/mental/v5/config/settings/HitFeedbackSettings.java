package me.vexmc.mental.v5.config.settings;

import java.util.List;

/**
 * The {@code hit-feedback} module's tunables: which sounds replace the vanilla
 * melee hurt-sound broadcast (each with its own volume/pitch), which particle
 * bursts pop at the victim's mid-chest, and an optional low-health EXTRA sound
 * layer played on top of the normal sounds when the victim's post-hit health
 * drops below {@code lowHealthThresholdHearts} (in HEARTS). The {@code preset}
 * selects an in-code set (VANILLA — audibly vanilla, the parse default;
 * SIGNATURE — Mental's own layered tune) or CUSTOM, which reads the
 * {@code sounds:} / {@code particles:} / {@code low-health-sounds:} lists from
 * the section. Presets are code constants, not files — the knockback profile
 * machinery is deliberately not reused (spec: rejected alternatives).
 *
 * @param customLowHealthSounds the low-health extra layer under CUSTOM (empty for
 *        no extra layer); the runtime task plays it on top of the normal sounds
 *        when post-hit health falls below the threshold, and suppresses it on the
 *        killing hit
 * @param lowHealthThresholdHearts the post-hit health ceiling, in HEARTS, below
 *        which the extra layer fires (2 health = 1 heart)
 */
public record HitFeedbackSettings(
        Preset preset,
        List<SoundSpec> customSounds,
        List<ParticleSpec> customParticles,
        List<SoundSpec> customLowHealthSounds,
        double lowHealthThresholdHearts) {

    public enum Preset { VANILLA, SIGNATURE, CUSTOM }

    /** One replacement sound: a resource-location name plus its volume/pitch. */
    public record SoundSpec(String sound, float volume, float pitch) {}

    /**
     * One particle burst. {@code particle} is a PE particle-type name;
     * {@code block} is the block-state name for block-crack particles (empty
     * otherwise). {@code SPREAD} scatters count particles with per-axis Gaussian
     * σ; {@code EMANATE} bursts them outward from the point at {@code speed}.
     */
    public record ParticleSpec(
            String particle, String block, int countMin, int countMax,
            Mode mode, float speed, double spreadX, double spreadY, double spreadZ) {}

    public enum Mode { EMANATE, SPREAD }

    public static final float MIN_VOLUME = 0.0f;
    public static final float MAX_VOLUME = 4.0f;
    public static final float MIN_PITCH = 0.5f;
    public static final float MAX_PITCH = 2.0f;
    public static final int MAX_COUNT = 64;

    /** The signature preset's sound layers (spec: the owner's 2.5.2 ask). */
    public static final List<SoundSpec> SIGNATURE_SOUNDS = List.of(
            new SoundSpec("block.lodestone.break", 1.0f, 1.0f),
            new SoundSpec("entity.generic.hurt", 0.85f, 0.75f),
            new SoundSpec("entity.breeze.deflect", 0.75f, 1.15f));

    /** The signature preset's particles: redstone-block break burst, 6–8, emanating. */
    public static final List<ParticleSpec> SIGNATURE_PARTICLES = List.of(
            new ParticleSpec("block", "redstone_block", 6, 8, Mode.EMANATE, 0.15f, 0, 0, 0));

    /**
     * The signature preset's low-health extra layer: a glow-squid hurt chirp. The
     * emit path resolves {@code entity.glow_squid.hurt} to {@code entity.squid.hurt}
     * below 1.17 (no glow squid existed), printed once in the boot report.
     */
    public static final List<SoundSpec> SIGNATURE_LOW_HEALTH_SOUNDS =
            List.of(new SoundSpec("entity.glow_squid.hurt", 0.9f, 1.2f));

    /** The vanilla preset: the era hurt sound, era pitch jitter applied at emit time. */
    public static final List<SoundSpec> VANILLA_SOUNDS =
            List.of(new SoundSpec("entity.player.hurt", 1.0f, 1.0f));

    public static final HitFeedbackSettings DEFAULTS =
            new HitFeedbackSettings(Preset.VANILLA, List.of(), List.of(), List.of(), 4.0);

    /** The effective sound list for the selected preset. */
    public List<SoundSpec> sounds() {
        return switch (preset) {
            case VANILLA -> VANILLA_SOUNDS;
            case SIGNATURE -> SIGNATURE_SOUNDS;
            case CUSTOM -> customSounds;
        };
    }

    /** The effective particle list for the selected preset. */
    public List<ParticleSpec> particles() {
        return switch (preset) {
            case VANILLA -> List.of();
            case SIGNATURE -> SIGNATURE_PARTICLES;
            case CUSTOM -> customParticles;
        };
    }

    /**
     * The effective low-health extra sound layer for the selected preset (empty
     * for no extra layer). VANILLA never layers; SIGNATURE plays the glow-squid
     * chirp; CUSTOM plays the configured {@code low-health-sounds:} list.
     */
    public List<SoundSpec> lowHealthSounds() {
        return switch (preset) {
            case VANILLA -> List.of();
            case SIGNATURE -> SIGNATURE_LOW_HEALTH_SOUNDS;
            case CUSTOM -> customLowHealthSounds;
        };
    }
}

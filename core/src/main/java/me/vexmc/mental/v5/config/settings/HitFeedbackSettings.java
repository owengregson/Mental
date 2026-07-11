package me.vexmc.mental.v5.config.settings;

import java.util.List;

/**
 * The {@code hit-feedback} module's tunables: which sounds replace the vanilla
 * melee hurt-sound broadcast (each with its own volume/pitch), which particle
 * bursts pop at the victim's mid-chest, and an optional low-health EXTRA sound
 * layer played on top of the normal sounds when the victim's post-hit health
 * drops below {@code lowHealthThresholdHearts} (in HEARTS). Since 2.5.3 the
 * values parse from the selected Combat Effects preset file's
 * {@code hit-feedback:} section ({@code effects/presets/<name>.yml} — the
 * knockback-profile model mirrored); the old per-module {@code preset:} enum
 * knob is gone, so the record carries only effective values. The
 * {@code SIGNATURE_*}/{@code VANILLA_SOUNDS} constants remain as the pin
 * values the bundled preset files must parse to exactly.
 *
 * @param sounds the replacement broadcast (empty replaces the vanilla sound
 *        with silence — the suppressor still eats the vanilla broadcast)
 * @param particles the mid-chest bursts (empty for none)
 * @param lowHealthSounds the low-health extra layer (empty for no extra
 *        layer); the runtime plays it on top of the normal sounds when
 *        post-hit health falls below the threshold, and suppresses it on the
 *        killing hit
 * @param lowHealthThresholdHearts the post-hit health ceiling, in HEARTS,
 *        below which the extra layer fires (2 health = 1 heart)
 */
public record HitFeedbackSettings(
        List<SoundSpec> sounds,
        List<ParticleSpec> particles,
        List<SoundSpec> lowHealthSounds,
        double lowHealthThresholdHearts) {

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

    /** The signature preset's sound layers (spec: the owner's 2.5.2 ask) — a bundled-file pin. */
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

    /**
     * {@code parse(empty) == DEFAULTS} is the vanilla tune: the era hurt sound,
     * no particles, no low-health layer — with the module enabled this is
     * byte-true to what vanilla broadcast anyway (the era-exact no-op).
     */
    public static final HitFeedbackSettings DEFAULTS =
            new HitFeedbackSettings(VANILLA_SOUNDS, List.of(), List.of(), 4.0);

    /**
     * Whether the effective sound set IS the vanilla broadcast — exactly the
     * era hurt sound at 1.0/1.0. The emit path then applies vanilla's own
     * per-broadcast pitch jitter instead of the configured flat pitch, so the
     * vanilla preset (and any preset that reproduces its values) stays
     * byte-true to the era sound; the old enum's {@code VANILLA} check reduced
     * to this same value test.
     */
    public boolean vanillaTune() {
        return VANILLA_SOUNDS.equals(sounds);
    }
}

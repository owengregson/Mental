package me.vexmc.mental.v5.config.settings;

import java.util.List;

/**
 * The {@code hit-feedback} module's tunables: which sounds replace the vanilla
 * melee hurt-sound broadcast (each with its own volume/pitch), which particle
 * bursts pop at the victim's mid-chest, and an optional low-health EXTRA sound
 * layer played on top of the normal sounds when the victim's post-hit health
 * drops below {@code lowHealthThresholdPercent} PERCENT of the victim's own
 * maximum health. Since 2.5.3 the values parse from the selected Combat Effects
 * preset file's {@code hit-feedback:} section
 * ({@code effects/presets/<name>.yml} — the knockback-profile model mirrored);
 * the old per-module {@code preset:} enum knob is gone, so the record carries
 * only effective values. The {@code VANILLA_SOUNDS} constant remains as the pin
 * value the vanilla broadcast is recognised by (it powers {@link #vanillaTune}).
 *
 * @param sounds the replacement broadcast (empty replaces the vanilla sound
 *        with silence — the suppressor still eats the vanilla broadcast)
 * @param particles the mid-chest bursts (empty for none)
 * @param lowHealthSounds the low-health extra layer (empty for no extra
 *        layer); the runtime plays it on top of the normal sounds when
 *        post-hit health falls below the threshold, and suppresses it on the
 *        killing hit
 * @param lowHealthThresholdPercent the post-hit health ceiling as a PERCENT of
 *        the victim's own maximum health (0–100), below which the extra layer
 *        fires — percent-of-max, not absolute hearts, so it scales with a
 *        scaled-max-health victim; a 20-max player at 35 fires below 7 health
 *        (3.5 hearts)
 */
public record HitFeedbackSettings(
        List<SoundSpec> sounds,
        List<ParticleSpec> particles,
        List<SoundSpec> lowHealthSounds,
        double lowHealthThresholdPercent) {

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

    /** The vanilla broadcast: the era hurt sound, era pitch jitter applied at emit time. */
    public static final List<SoundSpec> VANILLA_SOUNDS =
            List.of(new SoundSpec("entity.player.hurt", 1.0f, 1.0f));

    /**
     * {@code parse(empty) == DEFAULTS} is the vanilla tune: the era hurt sound,
     * no particles, no low-health layer — with the module enabled this is
     * byte-true to what vanilla broadcast anyway (the era-exact no-op). The
     * 35-percent threshold is inert while the layer is empty (no low-health
     * sounds to fire), so the no-op holds; the value matters only once a preset
     * (signature) actually carries a low-health layer.
     */
    public static final HitFeedbackSettings DEFAULTS =
            new HitFeedbackSettings(VANILLA_SOUNDS, List.of(), List.of(), 35.0);

    /**
     * Whether the effective sound set IS the vanilla broadcast — exactly the
     * era hurt sound at 1.0/1.0. The emit path then applies vanilla's own
     * per-broadcast pitch jitter instead of the configured flat pitch, so the
     * vanilla-valued tune (any preset that reproduces its values) stays
     * byte-true to the era sound; the old enum's {@code VANILLA} check reduced
     * to this same value test.
     */
    public boolean vanillaTune() {
        return VANILLA_SOUNDS.equals(sounds);
    }
}

package me.vexmc.mental.v5.config.settings;

import java.util.List;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.ParticleSpec;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.SoundSpec;

/**
 * The {@code death-effects} module's tunables: what plays at the moment a
 * player dies (any cause — PlayerDeathEvent). The VANILLA preset is a strict
 * nothing (enabled-but-vanilla is a no-op; the toggle owns zero-touch);
 * SIGNATURE is the owner's tune — a cosmetic packet lightning bolt (never a
 * real entity: no fire, no damage, no block interaction by construction),
 * the glow-squid death sound, and a white/yellow/gold firework-style burst.
 */
public record DeathEffectsSettings(
        Preset preset,
        boolean customLightning,
        List<SoundSpec> customSounds,
        List<ParticleSpec> customParticles) {

    public enum Preset { VANILLA, SIGNATURE, CUSTOM }

    public static final List<SoundSpec> SIGNATURE_SOUNDS =
            List.of(new SoundSpec("entity.glow_squid.death", 1.0f, 0.95f));

    /**
     * The signature burst: colored dust in &f/&e/&6 (white 0xFFFFFF, yellow
     * 0xFFFF55, gold 0xFFAA00) shaped like a firework blast, plus uncolored
     * firework sparks — vanilla's firework particle is not colorable, so the
     * mix approximates the ask honestly. Encoded as three dust specs (the
     * runtime maps spec.block "dust:RRGGBB" to ParticleDustData) + one spark.
     */
    public static final List<ParticleSpec> SIGNATURE_PARTICLES = List.of(
            new ParticleSpec("dust", "ffffff", 8, 12, HitFeedbackSettings.Mode.SPREAD, 0.0f, 0.5, 0.5, 0.5),
            new ParticleSpec("dust", "ffff55", 8, 12, HitFeedbackSettings.Mode.SPREAD, 0.0f, 0.5, 0.5, 0.5),
            new ParticleSpec("dust", "ffaa00", 8, 12, HitFeedbackSettings.Mode.SPREAD, 0.0f, 0.5, 0.5, 0.5),
            new ParticleSpec("firework", "", 10, 14, HitFeedbackSettings.Mode.EMANATE, 0.12f, 0, 0, 0));

    public static final DeathEffectsSettings DEFAULTS =
            new DeathEffectsSettings(Preset.VANILLA, false, List.of(), List.of());

    public boolean lightning() {
        return switch (preset) {
            case VANILLA -> false;
            case SIGNATURE -> true;
            case CUSTOM -> customLightning;
        };
    }

    public List<SoundSpec> sounds() {
        return switch (preset) {
            case VANILLA -> List.of();
            case SIGNATURE -> SIGNATURE_SOUNDS;
            case CUSTOM -> customSounds;
        };
    }

    public List<ParticleSpec> particles() {
        return switch (preset) {
            case VANILLA -> List.of();
            case SIGNATURE -> SIGNATURE_PARTICLES;
            case CUSTOM -> customParticles;
        };
    }
}

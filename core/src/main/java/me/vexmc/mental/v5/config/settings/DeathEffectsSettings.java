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
 * the glow-squid death sound, and the REAL white/yellow/gold colored firework
 * blast (a packet-only rocket detonated by entity status 17 — vanilla's own
 * mechanism, since the firework particle itself carries no color on the wire).
 */
public record DeathEffectsSettings(
        Preset preset,
        boolean customLightning,
        List<SoundSpec> customSounds,
        List<ParticleSpec> customParticles,
        List<Integer> customFireworkColors) {

    public enum Preset { VANILLA, SIGNATURE, CUSTOM }

    public static final List<SoundSpec> SIGNATURE_SOUNDS =
            List.of(new SoundSpec("entity.glow_squid.death", 1.0f, 0.95f));

    /**
     * Empty since 2.5.3: the signature burst is the real colored firework
     * blast ({@link #SIGNATURE_FIREWORK_COLORS}), not a particle approximation.
     * The 2.5.2 tune faked it with three colored dust bursts plus uncolored
     * firework sparks because the {@code minecraft:firework} particle carries
     * no color field on the wire; the rocket's item data does, so the blast
     * now ships the vanilla way and the whole approximation is retired.
     */
    public static final List<ParticleSpec> SIGNATURE_PARTICLES = List.of();

    /**
     * The signature blast colors in &f/&e/&6 (white 0xFFFFFF, yellow 0xFFFF55,
     * gold 0xFFAA00) — the same RGB ints vanilla stores in the firework item's
     * explosion, rendered by the client exactly as a crafted burst rocket.
     */
    public static final List<Integer> SIGNATURE_FIREWORK_COLORS =
            List.of(0xFFFFFF, 0xFFFF55, 0xFFAA00);

    public static final DeathEffectsSettings DEFAULTS =
            new DeathEffectsSettings(Preset.VANILLA, false, List.of(), List.of(), List.of());

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

    /** The effective blast colors; empty means no firework ships at all. */
    public List<Integer> fireworkColors() {
        return switch (preset) {
            case VANILLA -> List.of();
            case SIGNATURE -> SIGNATURE_FIREWORK_COLORS;
            case CUSTOM -> customFireworkColors;
        };
    }
}

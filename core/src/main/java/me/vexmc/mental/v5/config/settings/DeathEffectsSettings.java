package me.vexmc.mental.v5.config.settings;

import java.util.List;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.ParticleSpec;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.SoundSpec;

/**
 * The {@code death-effects} module's tunables: what plays at the moment a
 * player dies (any cause — PlayerDeathEvent). Since 2.5.3 the values parse
 * from the selected Combat Effects preset file's {@code death-effects:}
 * section ({@code effects/presets/<name>.yml}); the old per-module
 * {@code preset:} enum knob is gone, so the record carries only effective
 * values. DEFAULTS is the vanilla tune — a strict nothing (enabled-but-vanilla
 * is a no-op; the toggle owns zero-touch). The {@code SIGNATURE_*} constants
 * remain as the pin values the bundled signature/custom preset files must
 * parse to exactly: a cosmetic packet lightning bolt (never a real entity: no
 * fire, no damage, no block interaction by construction), the glow-squid death
 * sound, and the REAL white/yellow/gold colored firework blast (a packet-only
 * rocket detonated by entity status 17 — vanilla's own mechanism, since the
 * firework particle itself carries no color on the wire).
 */
public record DeathEffectsSettings(
        boolean lightning,
        List<SoundSpec> sounds,
        List<ParticleSpec> particles,
        List<Integer> fireworkColors) {

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

    /** The vanilla tune: a strict nothing — {@code parse(empty)}'s era-exact no-op. */
    public static final DeathEffectsSettings DEFAULTS =
            new DeathEffectsSettings(false, List.of(), List.of(), List.of());
}

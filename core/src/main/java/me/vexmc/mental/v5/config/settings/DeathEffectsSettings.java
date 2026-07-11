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
 * is a no-op; the toggle owns zero-touch). The signature tune's values (a
 * cosmetic packet lightning bolt, the glow-squid death sound, and the REAL
 * white/yellow/gold colored firework blast) live in the bundled
 * {@code signature.yml} now, the KB-profile model completed — the YAML is the
 * source of truth, and {@code EffectsPresetParserTest} pins that the file
 * parses to the expected records.
 */
public record DeathEffectsSettings(
        boolean lightning,
        List<SoundSpec> sounds,
        List<ParticleSpec> particles,
        List<Integer> fireworkColors) {

    /** The vanilla tune: a strict nothing — {@code parse(empty)}'s era-exact no-op. */
    public static final DeathEffectsSettings DEFAULTS =
            new DeathEffectsSettings(false, List.of(), List.of(), List.of());
}

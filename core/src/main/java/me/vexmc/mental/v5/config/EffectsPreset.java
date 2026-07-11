package me.vexmc.mental.v5.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;

/**
 * One Combat Effects preset — one complete cosmetic tune for the FEEDBACK
 * family, the knockback-profile model mirrored (2.5.3): one file under
 * {@code effects/presets/<name>.yml} carries all three module sections
 * ({@code hit-feedback:}, {@code damage-indicators:}, {@code death-effects:}),
 * and {@code effects.yml}'s {@code effects.preset} selects which preset
 * applies server-wide. The bundled constants below are the parse pins: each
 * bundled file must parse EXACTLY to its constant
 * ({@code EffectsPresetParserTest}), so a regenerated preset can never drift.
 */
public record EffectsPreset(
        String name,
        String displayName,
        String description,
        HitFeedbackSettings hitFeedback,
        DamageIndicatorsSettings damageIndicators,
        DeathEffectsSettings deathEffects) {

    /** The shipped default selection and the loud fallback for a bad name. */
    public static final String DEFAULT_NAME = "vanilla";

    /**
     * Audibly vanilla — with the modules enabled this preset reproduces what
     * vanilla did anyway (the toggle owns zero-touch, the preset owns the
     * tune): the era hurt sound with the era pitch jitter, no particles, no
     * low-health layer, the shipped indicator feel, and a strict death-effects
     * nothing. Its three records ARE the settings DEFAULTS, which is what
     * makes the vanilla fallback the era-exact no-op.
     */
    public static final EffectsPreset VANILLA = new EffectsPreset(
            "vanilla", "Vanilla",
            "Audibly vanilla — the era hurt sound, the shipped indicator feel, no death effects.",
            HitFeedbackSettings.DEFAULTS,
            DamageIndicatorsSettings.DEFAULTS,
            DeathEffectsSettings.DEFAULTS);

    /**
     * Mental's own tune: the layered hit chord over the redstone burst with
     * the glow-squid low-health chirp, the shipped indicator feel, and the
     * full death strike — lightning, the glow-squid call, and the real
     * white/yellow/gold firework blast.
     */
    public static final EffectsPreset SIGNATURE = new EffectsPreset(
            "signature", "Signature",
            "Mental's own tune — the layered hit chord, the low-health chirp, and the full death strike.",
            new HitFeedbackSettings(
                    HitFeedbackSettings.SIGNATURE_SOUNDS,
                    HitFeedbackSettings.SIGNATURE_PARTICLES,
                    HitFeedbackSettings.SIGNATURE_LOW_HEALTH_SOUNDS,
                    4.0),
            DamageIndicatorsSettings.DEFAULTS,
            new DeathEffectsSettings(
                    true,
                    DeathEffectsSettings.SIGNATURE_SOUNDS,
                    List.of(),
                    DeathEffectsSettings.SIGNATURE_FIREWORK_COLORS));

    /**
     * The owner's editable preset — starts as a byte-wise copy of signature's
     * VALUES (the owner tunes from a complete working feel, never from
     * silence). Only the identity differs; the bundled file is never upgraded
     * in place (owner edits are sacred even against research corrections).
     */
    public static final EffectsPreset CUSTOM = new EffectsPreset(
            "custom", "Custom",
            "Yours to edit freely — starts as a copy of the signature tune.",
            SIGNATURE.hitFeedback(),
            SIGNATURE.damageIndicators(),
            SIGNATURE.deathEffects());

    /** The bundled presets by name, in shipping order — the parse-pin iteration set. */
    public static final Map<String, EffectsPreset> BUNDLED = bundled();

    private static Map<String, EffectsPreset> bundled() {
        // Collections.unmodifiableMap keeps the LinkedHashMap's shipping order
        // (Map.copyOf would scramble it).
        Map<String, EffectsPreset> byName = new LinkedHashMap<>();
        byName.put(VANILLA.name(), VANILLA);
        byName.put(SIGNATURE.name(), SIGNATURE);
        byName.put(CUSTOM.name(), CUSTOM);
        return Collections.unmodifiableMap(byName);
    }
}

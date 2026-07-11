package me.vexmc.mental.v5.config;

import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;

/**
 * One Combat Effects preset — one complete cosmetic tune for the FEEDBACK
 * family, the knockback-profile model mirrored and completed (2.5.5): one file
 * under {@code effects/presets/<name>.yml} carries all three module sections
 * ({@code hit-feedback:}, {@code damage-indicators:}, {@code death-effects:}),
 * and {@code effects.yml}'s {@code effects.preset} selects which preset applies
 * server-wide. The bundled YAML is the tune's source of truth now — there are
 * no in-code preset value constants anymore. The drift pins live in
 * {@code EffectsPresetParserTest} as test-local expected records: a regenerated
 * bundled file must still parse to those exact records, so a tune can never
 * drift silently, while production always resolves a preset from a parsed file
 * (or, for {@code signature}, the JAR resource when a torn install lost the
 * disk copy).
 *
 * <p>{@link #FALLBACK} is the ONLY in-code preset value: a DEFAULTS-valued
 * (era-exact no-op) stand-in named {@code signature}, used by
 * {@link EffectsPresetParser.Library#effective()} and {@code Snapshot.Builder}
 * as the last-ditch value when even the signature file/resource is absent
 * (tests, a torn install). Production never reaches it — the bundle ships the
 * real signature YAML and {@code ConfigStore} serves the JAR resource when the
 * disk file is missing.</p>
 */
public record EffectsPreset(
        String name,
        String displayName,
        String description,
        HitFeedbackSettings hitFeedback,
        DamageIndicatorsSettings damageIndicators,
        DeathEffectsSettings deathEffects) {

    /** The shipped default selection and the loud fallback for a bad name. */
    public static final String DEFAULT_NAME = "signature";

    /**
     * The last-ditch in-code stand-in for the default preset: DEFAULTS-valued
     * (the era-exact no-op) under the name {@code signature}. This exists ONLY
     * so a torn install (or a unit test) with no signature file and no JAR
     * resource still resolves to a valid, silent preset instead of crashing —
     * production always has the parsed bundled file (or the resource fallback
     * {@link ConfigStore} serves in its place), so this value is never the
     * server's actual signature tune.
     */
    static final EffectsPreset FALLBACK = new EffectsPreset(
            DEFAULT_NAME, "Signature",
            "Mental's own tune — the layered hit chord, the low-health chirp, and the full death strike.",
            HitFeedbackSettings.DEFAULTS,
            DamageIndicatorsSettings.DEFAULTS,
            DeathEffectsSettings.DEFAULTS);
}

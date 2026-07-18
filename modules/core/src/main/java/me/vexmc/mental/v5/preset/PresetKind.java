package me.vexmc.mental.v5.preset;

import java.util.List;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.config.ConfigStore;
import me.vexmc.mental.v5.config.EffectsPreset;
import org.jetbrains.annotations.NotNull;

/**
 * The two preset systems by name — the enum the unified GUI iterates. Carries
 * only presentation metadata and the frozen wiring constants; all resolution
 * logic lives in {@link PresetCatalog}.
 */
public enum PresetKind {
    KNOCKBACK("Knockback Presets", "BOOKSHELF",
            "Every knockback feel — the era's archived servers, Mental's own,"
            + " and yours — previewed and applied live."),
    EFFECTS("Effects Presets", "JUKEBOX",
            "Whole combat-effects tunes — hits, indicators and deaths"
            + " swapped as one.");

    private final String displayName;
    private final String iconName;
    private final String blurb;

    PresetKind(String displayName, String iconName, String blurb) {
        this.displayName = displayName;
        this.iconName = iconName;
        this.blurb = blurb;
    }

    /** The gallery title suffix ({@code Mental · Knockback Presets}) and the header-card name. */
    public @NotNull String displayName() {
        return displayName;
    }

    /**
     * The gallery hero/header icon material name — both {@code BOOKSHELF} and
     * {@code JUKEBOX} resolve verbatim on 1.9.4 → 26.x, so no alias is needed.
     */
    public @NotNull String iconName() {
        return iconName;
    }

    /** The FamilyMenu hero-tile copy, wrapped by {@code Buttons.wrap} at render. */
    public @NotNull String blurb() {
        return blurb;
    }

    /**
     * The frozen selection overlay key for this kind. These two literals are the
     * frozen selection keys the Overlay routes and Management writes
     * ({@code Management.setGlobalProfile} / {@code setEffectsPreset}); the
     * catalog never writes them itself — apply() delegates so there is exactly
     * one write path per kind.
     */
    public @NotNull String overlayKey() {
        return switch (this) {
            case KNOCKBACK -> "knockback.profile";
            case EFFECTS -> "effects.preset";
        };
    }

    /** The shipped default preset name — read from the existing single sources of truth. */
    public @NotNull String defaultName() {
        return switch (this) {
            case KNOCKBACK -> KnockbackProfile.LEGACY_17.name();
            case EFFECTS -> EffectsPreset.DEFAULT_NAME;
        };
    }

    /** The bundled preset names for this kind — delegation to the one place they are pinned. */
    public @NotNull List<String> bundledNames() {
        return switch (this) {
            case KNOCKBACK -> ConfigStore.BUNDLED_PROFILES;
            case EFFECTS -> ConfigStore.BUNDLED_EFFECTS_PRESETS;
        };
    }
}

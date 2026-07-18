package me.vexmc.mental.v5.preset;

import java.util.List;

/**
 * Everything the GUI needs to render one preset tile, for either kind — the
 * typed preview {@code ProfileMenu}/{@code EffectsPresetMenu} used to
 * hand-roll. Serving this from the catalog (instead of the raw
 * {@code KnockbackProfile}/{@code EffectsPreset}) is what lets one picker
 * screen render both kinds without a type switch, and keeps the preview
 * vocabulary in exactly one place.
 */
public record PresetInfo(
        PresetKind kind,
        String name,
        String displayName,
        String description,
        String iconName,
        boolean loaded,
        boolean bundled,
        boolean active,
        boolean modernFormula,
        List<PreviewLine> preview) {

    /** One read-only "label: value" preview row (the picker tile's lore line). */
    public record PreviewLine(String label, String value) {}

    public PresetInfo {
        preview = List.copyOf(preview);
    }
}

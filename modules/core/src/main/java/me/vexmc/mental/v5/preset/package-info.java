/**
 * The unified preset manager's read surface. Mental has exactly two preset
 * systems — knockback profiles ({@code profiles/legacy|modern/*.yml}, selected
 * by {@code knockback.profile}) and Combat Effects presets
 * ({@code effects/presets/*.yml}, selected by {@code effects.preset}) —
 * deliberately built as mirrors but wired independently at every layer. This
 * package is the ONE place the two mirrors meet: {@link
 * me.vexmc.mental.v5.preset.PresetKind} names the two systems, {@link
 * me.vexmc.mental.v5.preset.PresetCatalog} serves both through a single typed
 * surface, and {@link me.vexmc.mental.v5.preset.PresetInfo} carries everything
 * the GUI needs so no menu ever touches a {@code KnockbackProfile} or {@code
 * EffectsPreset} directly. Selection writes still flow through the existing
 * {@code Management} seam — this package adds NO new write path.
 */
package me.vexmc.mental.v5.preset;

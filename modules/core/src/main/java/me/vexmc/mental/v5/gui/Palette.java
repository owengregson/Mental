package me.vexmc.mental.v5.gui;

import me.vexmc.mental.platform.PaneColor;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.preset.PresetKind;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

/**
 * The colour identity of every screen: each {@link Family} owns a signature
 * pane colour (its chrome accent) and a {@link NamedTextColor} accent used in
 * its titles, header cards, and value text. One place, exhaustively mapped —
 * {@code PaletteTest} pins that every Family resolves, so a new family can never
 * ship colourless.
 *
 * <p>The {@link #of(Family)} switch carries no {@code default}: a newly added
 * {@link Family} constant is a compile error here rather than a colourless
 * screen at runtime.</p>
 */
final class Palette {

    /** A screen's signature colours: the chrome accent pane and the text accent. */
    record Theme(PaneColor pane, NamedTextColor accent) {}

    private Palette() {}

    /** The family's signature colours. */
    static @NotNull Theme of(@NotNull Family family) {
        return switch (family) {
            case DELIVERY -> new Theme(PaneColor.LIGHT_BLUE, NamedTextColor.AQUA);
            case KNOCKBACK -> new Theme(PaneColor.RED, NamedTextColor.RED);
            case DAMAGE -> new Theme(PaneColor.ORANGE, NamedTextColor.DARK_RED);
            case CADENCE -> new Theme(PaneColor.YELLOW, NamedTextColor.YELLOW);
            case SUSTAIN -> new Theme(PaneColor.LIME, NamedTextColor.GREEN);
            case LOADOUT -> new Theme(PaneColor.CYAN, NamedTextColor.DARK_AQUA);
            case COMBO -> new Theme(PaneColor.BLUE, NamedTextColor.BLUE);
            case POTS -> new Theme(PaneColor.MAGENTA, NamedTextColor.LIGHT_PURPLE);
            case FEEDBACK -> new Theme(PaneColor.PURPLE, NamedTextColor.DARK_PURPLE);
            case LOOT -> new Theme(PaneColor.BROWN, NamedTextColor.GOLD);
        };
    }

    /** The home screen's brand theme (gold). */
    static @NotNull Theme home() {
        return new Theme(PaneColor.ORANGE, NamedTextColor.GOLD);
    }

    /** Compatibility + Debug — the neutral system theme. */
    static @NotNull Theme system() {
        return new Theme(PaneColor.LIGHT_GRAY, NamedTextColor.WHITE);
    }

    /** Gallery chrome follows its kind: KNOCKBACK reds, EFFECTS purples. */
    static @NotNull Theme gallery(@NotNull PresetKind kind) {
        return switch (kind) {
            case KNOCKBACK -> of(Family.KNOCKBACK);
            case EFFECTS -> of(Family.FEEDBACK);
        };
    }
}

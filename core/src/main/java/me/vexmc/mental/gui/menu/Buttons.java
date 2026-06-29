package me.vexmc.mental.gui.menu;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.gui.Icon;
import me.vexmc.mental.gui.Materials;
import me.vexmc.mental.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Shared icon vocabulary so every screen speaks the same visual language:
 * a bold gold title, a wrapped grey "why", a green/red status line, and a
 * yellow action hint. Enabled toggles carry an enchant glint.
 */
final class Buttons {

    private static final int WRAP = 42;

    private Buttons() {}

    /** A module on/off toggle: glows green-lit when enabled, muted when off. */
    static @NotNull ItemStack toggle(
            @NotNull String materialName, @NotNull String title, boolean enabled, @NotNull String blurb) {
        Icon icon = title(materialName, title);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(Component.text(enabled ? "● ENABLED" : "○ DISABLED",
                enabled ? Brand.SUCCESS : Brand.FAILURE).decoration(TextDecoration.BOLD, true));
        icon.lore(Component.text("▸ Click to " + (enabled ? "disable" : "enable"), Brand.SECONDARY));
        return icon.glow(enabled).build();
    }

    /** A navigation tile that opens another screen. */
    static @NotNull ItemStack nav(
            @NotNull String materialName, @NotNull String title, @NotNull String blurb) {
        Icon icon = title(materialName, title);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(Component.text("▸ Open", Brand.SECONDARY));
        return icon.build();
    }

    static @NotNull ItemStack back() {
        return Icon.of(Materials.of("ARROW"))
                .name(Component.text("Back", Brand.SECONDARY).decoration(TextDecoration.BOLD, true))
                .lore("Return to the dashboard.", Brand.MUTED)
                .build();
    }

    /** Starts a titled icon (bold gold name) for a caller that adds its own lore. */
    static @NotNull Icon title(@NotNull String materialName, @NotNull String title) {
        return Icon.of(Materials.of(materialName))
                .name(Component.text(title, Brand.PRIMARY).decoration(TextDecoration.BOLD, true));
    }

    static @NotNull Icon title(@NotNull String materialName, @NotNull String title, @NotNull TextColor color) {
        return Icon.of(Materials.of(materialName))
                .name(Component.text(title, color).decoration(TextDecoration.BOLD, true));
    }

    /** Greedy word-wrap into ~42-char lore lines. */
    static @NotNull List<String> wrap(@NotNull String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() > 0 && current.length() + word.length() + 1 > WRAP) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }
}

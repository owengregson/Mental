package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.platform.MenuMaterials;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Shared icon vocabulary so every screen speaks the same visual language:
 * a bold gold title, a wrapped grey "why", a green/red status line, and a
 * yellow action hint. Enabled toggles carry an enchant glint.
 *
 * <p>Lifted from the retired {@code gui/menu/Buttons} reuse-ledger asset —
 * {@link #wrap} verbatim (its greedy word-wrap is pinned by {@code ButtonsTest},
 * also lifted); the rest re-expressed on the v5 {@link Brand}/{@link
 * MenuMaterials} references.</p>
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

    /**
     * A numeric stepper tile: the current value plus the click grammar every
     * stepper shares (left/right adjust by one, shift by ten). The caller's click
     * handler reads {@code event.isRightClick()}/{@code isShiftClick()}.
     */
    static @NotNull ItemStack stepper(
            @NotNull String materialName, @NotNull String title, @NotNull String value,
            @NotNull String blurb) {
        Icon icon = title(materialName, title);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(kv("Value", value));
        icon.lore(Component.text("▸ Left +1   ▸ Shift-Left +10", Brand.SECONDARY));
        icon.lore(Component.text("▸ Right −1   ▸ Shift-Right −10", Brand.SECONDARY));
        return icon.build();
    }

    /** A cycle tile: the current option plus a "click to cycle" hint. */
    static @NotNull ItemStack cycle(
            @NotNull String materialName, @NotNull String title, @NotNull String current,
            @NotNull String blurb) {
        Icon icon = title(materialName, title);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(kv("Current", current));
        icon.lore(Component.text("▸ Click to cycle", Brand.SECONDARY));
        return icon.build();
    }

    /**
     * A text-edit tile: the current value (raw {@code &} codes shown so the admin
     * edits what they see; wrapped), plus a "click to edit in chat" hint. An
     * empty value shows a muted placeholder.
     */
    static @NotNull ItemStack editText(
            @NotNull String materialName, @NotNull String title, @NotNull String value,
            @NotNull String blurb) {
        Icon icon = title(materialName, title);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        if (value.isEmpty()) {
            icon.lore(Component.text("(empty)", Brand.MUTED));
        } else {
            wrap(value).forEach(line -> icon.lore(line, Brand.ACCENT));
        }
        icon.lore(Component.text("▸ Click to edit in chat", Brand.SECONDARY));
        return icon.build();
    }

    /** A muted-label / accent-value lore line, shared by the value tiles. */
    private static @NotNull Component kv(@NotNull String label, @NotNull String value) {
        return Component.text()
                .append(Component.text(label + ": ", Brand.MUTED))
                .append(Component.text(value, Brand.ACCENT))
                .build();
    }

    static @NotNull ItemStack back() {
        return Icon.of(MenuMaterials.of("ARROW"))
                .name(Component.text("Back", Brand.SECONDARY).decoration(TextDecoration.BOLD, true))
                .lore("Return to the dashboard.", Brand.MUTED)
                .build();
    }

    /** Starts a titled icon (bold gold name) for a caller that adds its own lore. */
    static @NotNull Icon title(@NotNull String materialName, @NotNull String title) {
        return Icon.of(MenuMaterials.of(materialName))
                .name(Component.text(title, Brand.PRIMARY).decoration(TextDecoration.BOLD, true));
    }

    static @NotNull Icon title(@NotNull String materialName, @NotNull String title, @NotNull TextColor color) {
        return Icon.of(MenuMaterials.of(materialName))
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

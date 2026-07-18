package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.platform.MenuMaterials;
import me.vexmc.mental.v5.preset.PresetCatalog;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /* ------------------------------------------------------------------ */
    /*  Buttons v2 — the one click grammar (§2.4). The retired single-      */
    /*  vocabulary factories died with their last callers in the           */
    /*  screen-rewrite pass; wrap and title (below) are the only survivors  */
    /*  of the original asset.                                             */
    /* ------------------------------------------------------------------ */

    /** A muted-label / accent-value lore line — THE shared kv, killing five copies. */
    static @NotNull Component kv(@NotNull String label, @NotNull String value, @NotNull TextColor accent) {
        return Component.text()
                .append(Component.text(label + ": ", Brand.MUTED))
                .append(Component.text(value, accent))
                .build();
    }

    /** Delegates to {@link PresetCatalog#round} — one rounding rule for every preview. */
    static @NotNull String round(double value) {
        return PresetCatalog.round(value);
    }

    /** A navigation tile: wrapped grey blurb + "▸ Click to open". */
    static @NotNull ItemStack nav(
            @NotNull String materialName, @NotNull String title, @NotNull String blurb) {
        Icon icon = title(materialName, title, Brand.TEXT);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(Component.text("▸ Click to open", Brand.SECONDARY));
        return icon.build();
    }

    /**
     * A module card — the family screens' one tile per feature. Left-click
     * toggles the module; when {@code settingsHint} is non-null a second action
     * line advertises the right-click destination (settings or gallery). The name
     * reads bold {@code accent} when enabled, bold WHITE when off, and the tile
     * glows when enabled.
     */
    static @NotNull ItemStack moduleCard(
            @NotNull String materialName, @NotNull String title, @NotNull TextColor accent,
            boolean enabled, @NotNull String blurb, @Nullable String settingsHint) {
        Icon icon = title(materialName, title, enabled ? accent : Brand.TEXT);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(Component.text(enabled ? "● ENABLED" : "○ DISABLED",
                enabled ? Brand.SUCCESS : Brand.FAILURE).decoration(TextDecoration.BOLD, true));
        icon.lore(Component.text("▸ Click to " + (enabled ? "disable" : "enable"), Brand.SECONDARY));
        if (settingsHint != null) {
            icon.lore(Component.text(settingsHint, Brand.SECONDARY));
        }
        return icon.glow(enabled).build();
    }

    /** A boolean knob tile. {@code overridden} renders the ⚑ line + Q-reset hint. */
    static @NotNull ItemStack toggle(
            @NotNull String materialName, @NotNull String title,
            boolean enabled, @NotNull String blurb, boolean overridden) {
        Icon icon = title(materialName, title, Brand.TEXT);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(Component.text(enabled ? "● ON" : "○ OFF",
                enabled ? Brand.SUCCESS : Brand.FAILURE).decoration(TextDecoration.BOLD, true));
        icon.lore(Component.text("▸ Click to turn " + (enabled ? "off" : "on"), Brand.SECONDARY));
        overrideBlock(icon, overridden);
        return icon.glow(enabled).build();
    }

    /**
     * A numeric stepper tile. Grammar: "▸ Left +&lt;step&gt; · Right −&lt;step&gt;"
     * and "▸ Shift for ×10"; {@code value} already carries its unit suffix.
     */
    static @NotNull ItemStack stepper(
            @NotNull String materialName, @NotNull String title, @NotNull TextColor accent,
            @NotNull String value, @NotNull String blurb, @NotNull String step, boolean overridden) {
        Icon icon = title(materialName, title, Brand.TEXT);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(kv("Value", value, accent));
        icon.lore(Component.text("▸ Left +" + step + " · Right −" + step, Brand.SECONDARY));
        icon.lore(Component.text("▸ Shift for ×10", Brand.SECONDARY));
        overrideBlock(icon, overridden);
        return icon.build();
    }

    /**
     * A cycle tile rendering EVERY option as a radio line (● selected / ○ other),
     * so the whole option space is visible before clicking. Left cycles forward,
     * right cycles back.
     */
    static @NotNull ItemStack cycle(
            @NotNull String materialName, @NotNull String title,
            @NotNull List<String> options, @NotNull String selected, @NotNull String blurb,
            boolean overridden) {
        Icon icon = title(materialName, title, Brand.TEXT);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        for (String option : options) {
            boolean on = option.equals(selected);
            icon.lore(Component.text((on ? "● " : "○ ") + option, on ? Brand.SUCCESS : Brand.MUTED)
                    .decoration(TextDecoration.BOLD, on));
        }
        icon.blank();
        icon.lore(Component.text("▸ Click to cycle · Right-click back", Brand.SECONDARY));
        overrideBlock(icon, overridden);
        return icon.build();
    }

    /** A chat-edited text knob (raw {@code &} codes shown, "(empty)" when blank). */
    static @NotNull ItemStack editText(
            @NotNull String materialName, @NotNull String title, @NotNull TextColor accent,
            @NotNull String value, @NotNull String blurb, boolean overridden) {
        Icon icon = title(materialName, title, Brand.TEXT);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        if (value.isEmpty()) {
            icon.lore(Component.text("(empty)", Brand.MUTED));
        } else {
            wrap(value).forEach(line -> icon.lore(line, accent));
        }
        icon.lore(Component.text("▸ Click to edit in chat", Brand.SECONDARY));
        overrideBlock(icon, overridden);
        return icon.build();
    }

    /** A chat-entered NUMBER knob ("▸ Click to type a value in chat"). */
    static @NotNull ItemStack numberPrompt(
            @NotNull String materialName, @NotNull String title, @NotNull TextColor accent,
            @NotNull String value, @NotNull String blurb, boolean overridden) {
        Icon icon = title(materialName, title, Brand.TEXT);
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(kv("Value", value, accent));
        icon.lore(Component.text("▸ Click to type a value in chat", Brand.SECONDARY));
        overrideBlock(icon, overridden);
        return icon.build();
    }

    /**
     * The first-class read-only tile: "ℹ " prefix, NON-bold muted name, muted
     * body, no glow and no action line — visually unmistakable as not-clickable.
     * The caller pre-wraps {@code body} through {@link #wrap}.
     */
    static @NotNull ItemStack info(
            @NotNull String materialName, @NotNull String title, @NotNull List<String> body) {
        Icon icon = Icon.of(MenuMaterials.of(materialName))
                .name(Component.text("ℹ " + title, Brand.MUTED));
        body.forEach(line -> icon.lore(line, Brand.MUTED));
        return icon.build();
    }

    /**
     * The list-knob pointer tile: an info look plus one exact editing direction —
     * "✎ Edit in &lt;file&gt; → &lt;section&gt;" in SECONDARY, naming file and section.
     */
    static @NotNull ItemStack pointer(
            @NotNull String materialName, @NotNull String title, @NotNull String blurb,
            @NotNull String file, @NotNull String section) {
        Icon icon = Icon.of(MenuMaterials.of(materialName))
                .name(Component.text("ℹ " + title, Brand.MUTED));
        wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(Component.text("✎ Edit in " + file + " → " + section, Brand.SECONDARY));
        return icon.build();
    }

    /** Back names its actual destination: "Back" / lore "Return to &lt;destination&gt;." */
    static @NotNull ItemStack back(@NotNull String destination) {
        return Icon.of(MenuMaterials.of("ARROW"))
                .name(Component.text("Back", Brand.SECONDARY).decoration(TextDecoration.BOLD, true))
                .lore("Return to " + destination + ".", Brand.MUTED)
                .build();
    }

    /** The ⚑/Q-reset override footer, rendered only when the knob is overridden in-GUI. */
    private static void overrideBlock(@NotNull Icon icon, boolean overridden) {
        if (overridden) {
            icon.lore(Component.text("⚑ Overridden in-GUI", NamedTextColor.DARK_GRAY));
            icon.lore(Component.text("▸ Press Q to reset to the file value", NamedTextColor.DARK_GRAY));
        }
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

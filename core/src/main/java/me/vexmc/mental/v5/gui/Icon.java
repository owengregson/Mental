package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.platform.Enchantments;
import me.vexmc.mental.platform.MenuMaterials;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/**
 * A fluent, cross-version menu-icon builder (the retired {@code gui/Icon}
 * re-expressed on the v5 platform-layer resolvers).
 *
 * <p>Everything here is binary-safe on the 1.17.1 floor: the Adventure {@code
 * displayName(Component)} / {@code lore(List&lt;Component&gt;)} meta methods,
 * {@link ItemFlag#HIDE_ENCHANTS} and {@link ItemFlag#HIDE_ATTRIBUTES}. Materials
 * are resolved through {@link MenuMaterials} so a constant renamed on a future
 * year-scheme server degrades to a safe staple instead of failing to link, and
 * the glow enchant through {@link Enchantments} (renamed in 1.20.5).</p>
 *
 * <p>Names and lore have their default italic stripped — vanilla renders
 * custom item text in italics otherwise, which reads as unintended emphasis in
 * a hand-designed menu.</p>
 */
public final class Icon {

    private final ItemStack stack;
    private final ItemMeta meta;
    private final List<Component> lore = new ArrayList<>();
    private boolean glow;

    private Icon(@NotNull Material material, int amount) {
        int clamped = Math.max(1, Math.min(amount, material.getMaxStackSize()));
        this.stack = new ItemStack(material, clamped);
        this.meta = stack.getItemMeta();
    }

    public static @NotNull Icon of(@NotNull Material material) {
        return new Icon(material, 1);
    }

    public static @NotNull Icon of(@NotNull Material material, int amount) {
        return new Icon(material, amount);
    }

    public @NotNull Icon name(@NotNull Component name) {
        if (meta != null) {
            meta.displayName(clean(name));
        }
        return this;
    }

    public @NotNull Icon name(@NotNull String text, @NotNull TextColor color) {
        return name(Component.text(text, color));
    }

    public @NotNull Icon lore(@NotNull Component line) {
        lore.add(clean(line));
        return this;
    }

    public @NotNull Icon lore(@NotNull String text, @NotNull TextColor color) {
        return lore(Component.text(text, color));
    }

    /** A blank spacer line in the lore. */
    public @NotNull Icon blank() {
        lore.add(Component.empty());
        return this;
    }

    /** Adds an enchantment glint (hidden enchant line) when {@code on}. */
    public @NotNull Icon glow(boolean on) {
        this.glow = on;
        return this;
    }

    public @NotNull ItemStack build() {
        if (meta != null) {
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            if (glow) {
                Enchantment glint = Enchantments.unbreaking();
                if (glint != null) {
                    meta.addEnchant(glint, 1, true);
                }
            }
            // Weapons and armour carry attribute/enchant tooltip lines that would
            // clutter a menu icon; hide them unconditionally (also masks the
            // glow enchant).
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static @NotNull Component clean(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}

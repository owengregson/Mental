package me.vexmc.mental.v5.gui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.platform.MenuMaterials;
import me.vexmc.mental.platform.PaneColor;
import me.vexmc.mental.v5.text.TextPort;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/**
 * Builds the decorative pane stacks the {@link PanePattern} paints with. Panes
 * carry an empty display name (no tooltip flicker) through the {@link TextPort}
 * String sink, exactly like the old filler; the stacks are cached per colour and
 * never mutated after build (Bukkit copies on {@code setItem}).
 */
final class Chrome {

    // draw() runs on each viewer's OWN region thread (Folia), so two regions can
    // race the first build of a given colour; a ConcurrentHashMap (the MenuMaterials
    // .CACHE precedent) gives safe publication of the finished, never-mutated stack.
    private static final Map<PaneColor, ItemStack> CACHE = new ConcurrentHashMap<>();

    private Chrome() {}

    /** A finished, empty-named background pane of the given colour. */
    static @NotNull ItemStack pane(@NotNull PaneColor color) {
        return CACHE.computeIfAbsent(color, Chrome::build);
    }

    private static @NotNull ItemStack build(@NotNull PaneColor color) {
        ItemStack stack = MenuMaterials.pane(color);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            // The Adventure displayName(Component) meta method is absent below
            // 1.16.5, so the empty name routes through the TextPort String sink.
            TextPort.displayName(meta, Component.empty());
            stack.setItemMeta(meta);
        }
        return stack;
    }
}

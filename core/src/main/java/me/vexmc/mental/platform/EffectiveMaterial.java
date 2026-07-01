package me.vexmc.mental.platform;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The vendor-neutral cross-plugin "effective material" contract.
 *
 * <p>A plugin that display-swaps gear to a weaker material (e.g. a heroic/cosmetic reforge that shows a
 * diamond item as gold) stamps the item's <em>true</em> material name under the neutral key
 * {@code combat:effective_material}. Era-combat plugins like Mental MAY read it and treat the item as
 * that material for legacy stat computation, so a "diamond in disguise" gets diamond-era stats rather
 * than the display material's. Absent (the overwhelming common case) ⇒ the item's own type; this is a
 * pure no-op for every unmarked item.</p>
 *
 * <p>Read-only on Mental's side — the stamping plugin owns the write. The {@link #resolve} half is pure
 * (unit-pinned); {@link #of} is the thin Bukkit shell that reads the PDC.</p>
 */
public final class EffectiveMaterial {

    /** The neutral contract key. Value: a Bukkit {@link Material} name (e.g. {@code DIAMOND_SWORD}), STRING. */
    public static final NamespacedKey KEY = NamespacedKey.fromString("combat:effective_material");

    private EffectiveMaterial() {}

    /**
     * The material to use for era/legacy stat computation: the marked material if the item carries a
     * valid {@code combat:effective_material} name, else the item's own type. Never null for a non-null
     * item; {@code null} item ⇒ {@link Material#AIR} (which every legacy table treats as "not a tool").
     */
    public static @NotNull Material of(@Nullable ItemStack item) {
        if (item == null) {
            return Material.AIR;
        }
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        String marked = meta == null || KEY == null
                ? null
                : meta.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        return resolve(marked, item.getType());
    }

    /**
     * Pure resolution: an exact Bukkit {@link Material} enum name → that material; a blank, {@code null},
     * or unknown name → {@code fallback}. Never throws — an unrecognised marker degrades to the item's
     * own type rather than breaking a hit.
     */
    static @NotNull Material resolve(@Nullable String name, @NotNull Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return fallback; // a marker naming a material this version doesn't have — ignore it
        }
    }
}

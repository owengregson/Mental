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

    private EffectiveMaterial() {}

    /**
     * The neutral contract key, built lazily and exception-guarded so nothing in class-init can throw on
     * any version. The key is only ever read where {@link PersistentData#supported()} is true (Bukkit
     * 1.14+), and the two-argument {@link NamespacedKey} constructor is public from 1.12 — but the holder
     * is initialized on first read regardless, and any absence degrades to {@code null} rather than a
     * class-load failure. (The original {@code NamespacedKey.fromString} is a 1.16 API and would poison
     * this class's initializer on every server below it — the whole damage path routes through here.)
     */
    private static final class Key {
        static final @Nullable NamespacedKey INSTANCE = build();

        @SuppressWarnings("deprecation") // the (namespace,key) ctor exists from 1.12; fromString is 1.16+
        private static @Nullable NamespacedKey build() {
            try {
                return new NamespacedKey("combat", "effective_material");
            } catch (Throwable absent) {
                return null; // NamespacedKey missing (pre-1.12) — never reached, PDC is gated separately
            }
        }
    }

    /**
     * The material to use for era/legacy stat computation: the marked material if the item carries a
     * valid {@code combat:effective_material} name, else the item's own type. Never null for a non-null
     * item; {@code null} item ⇒ {@link Material#AIR} (which every legacy table treats as "not a tool").
     *
     * <p>The contract floors at 1.14 (the PersistentDataContainer API). On servers without it the marker
     * cannot be read, so every item resolves to its own type — the display-swap contract simply does not
     * apply below 1.14 (documented in {@code docs/effective-material-contract.md}). A one-time boot log
     * announces the fallback from {@code MentalPluginV5}, so the degradation is never silent.</p>
     */
    public static @NotNull Material of(@Nullable ItemStack item) {
        if (item == null) {
            return Material.AIR;
        }
        if (!PersistentData.supported()) {
            return item.getType(); // no PDC (pre-1.14) — the marker is unreadable, so use the real type
        }
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        NamespacedKey key = Key.INSTANCE;
        String marked = meta == null || key == null
                ? null
                : meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
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

package me.vexmc.mental.kernel.model;

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
 * <p>Read-only on Mental's side — the stamping plugin owns the write. This is the pure half of the
 * contract (unit-pinned); the PDC-reading shell AND the host-registry validation live in core: whether
 * a marker names a {@code Material} this server version actually has can only be answered against the
 * live Bukkit registry, so core's shell degrades a registry-unknown result to the item's own type
 * before any era table consumes it. The kernel half never throws regardless of input.</p>
 */
public final class EffectiveMaterial {

    /** The neutral contract key, as a string (the kernel has no NamespacedKey). */
    public static final String KEY = "combat:effective_material";

    private EffectiveMaterial() {}

    /**
     * Pure resolution: a present, non-blank marker value → that material name; a blank or {@code null}
     * marker → {@code fallbackTypeName}. Never throws — validation of the name against the host
     * version's material registry is core's job (see the class doc).
     */
    public static String resolve(String markerValue, String fallbackTypeName) {
        if (markerValue == null || markerValue.isBlank()) {
            return fallbackTypeName;
        }
        return markerValue;
    }
}

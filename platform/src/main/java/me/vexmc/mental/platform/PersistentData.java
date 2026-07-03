package me.vexmc.mental.platform;

import org.jetbrains.annotations.NotNull;

/**
 * Whether this server exposes the PersistentDataContainer API.
 *
 * <p>{@code org.bukkit.persistence.PersistentDataContainer} and {@code
 * ItemMeta#getPersistentDataContainer()} arrived in Bukkit 1.14. Below it — the
 * legacy backport reaches down to 1.9.4 — item NBT is not addressable through
 * Bukkit at all, so every PDC-keyed marker Mental writes (the effective-material
 * read, the temporary-shield tag, the arrow-punch stamp) must fall back to
 * in-memory state. This probe is resolved once at class load by class presence
 * (never a version parse), and callers gate their PDC path on {@link #supported()}
 * so no {@code PersistentDataContainer} / {@code NamespacedKey} reference is ever
 * executed on a version that lacks it.</p>
 */
public final class PersistentData {

    private static final boolean SUPPORTED =
            classPresent("org.bukkit.persistence.PersistentDataContainer");

    private PersistentData() {}

    /** True when the PersistentDataContainer API is present (Bukkit 1.14+). */
    public static boolean supported() {
        return SUPPORTED;
    }

    private static boolean classPresent(@NotNull String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}

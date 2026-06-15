package me.vexmc.mental.module.rules.offhand;

import java.util.Set;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pure, stateless policy that decides whether a material is allowed in the
 * off-hand slot given the current whitelist/blacklist configuration.
 *
 * <p>Era truth: the off-hand slot was introduced in 1.9.  On a 1.7/1.8 era
 * server the slot should be unreachable.  When the disable-offhand module is
 * active, this class enforces the configured item filter so operators can
 * either block every item (empty whitelist) or permit a curated set (e.g.
 * totems) while blocking the rest.</p>
 *
 * <p>{@link Material#AIR} and {@code null} represent an empty slot or cursor;
 * placing air into the off-hand merely clears it and is always permitted
 * regardless of the configured mode or list.</p>
 */
public final class OffhandPolicy {

    private OffhandPolicy() {}

    /**
     * Returns {@code true} if {@code material} is permitted in the off-hand
     * slot under the given configuration.
     *
     * <p>Decision table:
     * <ul>
     *   <li>{@code null} or {@link Material#AIR} — always allowed (clears slot).</li>
     *   <li>{@code whitelist = true} — allowed iff {@code items} contains the material.</li>
     *   <li>{@code whitelist = false} (blacklist) — allowed iff {@code items} does <em>not</em>
     *       contain the material.</li>
     * </ul>
     *
     * @param material  the material to test (may be null)
     * @param whitelist {@code true} for whitelist mode, {@code false} for blacklist
     * @param items     the configured set of materials (whitelist members or blacklist members)
     * @return {@code true} iff the material may be placed in the off-hand
     */
    public static boolean isAllowedInOffhand(
            @Nullable Material material,
            boolean whitelist,
            @NotNull Set<Material> items) {
        // Clearing the slot is always fine — never deny an empty cursor.
        if (material == null || material == Material.AIR) {
            return true;
        }
        return whitelist ? items.contains(material) : !items.contains(material);
    }
}

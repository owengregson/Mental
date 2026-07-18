package me.vexmc.mental.v5.feature.cadence;

import java.lang.reflect.Field;
import java.util.Objects;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The enchantment constants the CT8c sweep rules touch, resolved by NAME once at
 * class load (the {@code platform/Enchantments} pattern — Bukkit renamed the
 * constants to their Minecraft keys in 1.20.5, dropping the legacy names). Kept in
 * the cadence package (this cluster's ownership) rather than extending the shared
 * platform seam.
 *
 * <p>Sweeping Edge lands at 1.11; below that it resolves to {@code null} and every
 * weapon reports level 0 — which is exactly the CT8c "plain swords do not sweep"
 * gate, and immaterial there anyway because the {@code ENTITY_SWEEP_ATTACK} cause
 * itself is absent below 1.11 (the owning unit gates on {@code SweepCauses}).</p>
 */
final class Ct8cSweepEnchants {

    /** Sweeping Edge (modern) / the pre-1.20.5 constant; {@code null} below 1.11. */
    static final @Nullable Enchantment SWEEPING_EDGE = resolve("SWEEPING_EDGE", "SWEEP");
    /** Fire Aspect — stable name on both sides. */
    static final @Nullable Enchantment FIRE_ASPECT = resolve("FIRE_ASPECT");
    /** Looting (modern) / {@code LOOT_BONUS_MOBS} (legacy). */
    static final @Nullable Enchantment LOOTING = resolve("LOOTING", "LOOT_BONUS_MOBS");
    /** Knockback — stable name on both sides. */
    static final @Nullable Enchantment KNOCKBACK = resolve("KNOCKBACK");

    private Ct8cSweepEnchants() {}

    /** The Sweeping-Edge level on {@code stack} (0 when the enchant is absent on this version). */
    static int sweepingEdgeLevel(@Nullable ItemStack stack) {
        if (stack == null || SWEEPING_EDGE == null) {
            return 0;
        }
        return stack.getEnchantmentLevel(SWEEPING_EDGE);
    }

    /** The four enchants CT8c lets books apply onto axes via the anvil (spec §2.3/§2.9). */
    static boolean axeEligible(@NotNull Enchantment enchant) {
        return Objects.equals(enchant, SWEEPING_EDGE)
                || Objects.equals(enchant, FIRE_ASPECT)
                || Objects.equals(enchant, LOOTING)
                || Objects.equals(enchant, KNOCKBACK);
    }

    private static @Nullable Enchantment resolve(@NotNull String... names) {
        for (String name : names) {
            Enchantment enchant = staticField(name);
            if (enchant != null) {
                return enchant;
            }
        }
        return null;
    }

    private static @Nullable Enchantment staticField(@NotNull String name) {
        try {
            Field field = Enchantment.class.getField(name);
            Object value = field.get(null);
            return value instanceof Enchantment enchant ? enchant : null;
        } catch (NoSuchFieldException | IllegalAccessException absent) {
            return null;
        }
    }
}

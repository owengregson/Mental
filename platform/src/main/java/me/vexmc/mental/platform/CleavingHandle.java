package me.vexmc.mental.platform;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A resolved Combat Test 8c Cleaving enchantment (spec §2.9) — the seam the
 * damage cluster ({@code Ct8cDamageUnit}, {@code +1+level}) and the shield
 * cluster ({@code Ct8cShieldUnit}, {@code Ct8cShieldMath.axeDisableTicks}) read
 * a weapon's Cleaving level from without touching the registry themselves.
 *
 * <p>Produced by {@link CleavingRegistrar} once the real enchant is injected on a
 * modern-enough Paper; below the registry floor (or on any injection failure)
 * the registrar returns no handle and both consumers fold Cleaving in as level
 * {@code 0} — the documented gap.</p>
 */
public interface CleavingHandle {

    /**
     * The Cleaving enchantment level on {@code stack} (0 when absent, null, or
     * the item carries no Cleaving). Never throws — a malformed stack reads 0.
     */
    int levelOf(@Nullable ItemStack stack);
}

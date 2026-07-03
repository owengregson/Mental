package me.vexmc.mental.platform;

import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Cross-version item-cooldown capability.
 *
 * <p>The item-cooldown API — {@code HumanEntity#getCooldown/setCooldown/hasCooldown(Material)} — first
 * appears at 1.11.2 (javap-verified absent on 1.9.4/1.10.2). This is not merely a mapping gap: vanilla
 * {@code <=} 1.10 has <em>no</em> ender-pearl throw cooldown at all, so the mechanic
 * {@code disable-enderpearl-cooldown} exists to <em>restore</em> is already native there. Callers use this
 * presence probe to render the feature a documented, loud no-op below 1.11 rather than throw a
 * {@code NoSuchMethodError} clearing a cooldown the server never sets.</p>
 *
 * <p>Presence is resolved ONCE at class load (a method probe, never a version parse).</p>
 */
public final class Cooldowns {

    private static final boolean ITEM_COOLDOWN = resolve();

    private Cooldowns() {}

    /** True when {@code HumanEntity#setCooldown(Material,int)} exists on this server (1.11.2+). */
    public static boolean itemCooldownSupported() {
        return ITEM_COOLDOWN;
    }

    /** For the boot report: the era-truthful description of the item-cooldown state. */
    public static @NotNull String describe() {
        return ITEM_COOLDOWN
                ? "item-cooldown API present (1.11.2+)"
                : "no item-cooldown API (era-native pre-1.11 — pearl cooldown feature is a no-op)";
    }

    private static boolean resolve() {
        try {
            HumanEntity.class.getMethod("setCooldown", Material.class, int.class);
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }
}

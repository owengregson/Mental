package me.vexmc.mental.v5.feature.damage;

import java.util.concurrent.ThreadLocalRandom;
import me.vexmc.mental.kernel.math.ArmourDurabilityMath;
import me.vexmc.mental.platform.Enchantments;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Restores the 1.8 armour-durability Unbreaking skip (the retired
 * {@code module.damage.ArmourDurabilityModule} on the v5 seams), so worn armour
 * survives more hits. The per-hit wear <em>magnitude</em> is byte-identical
 * 1.8↔modern, so it is never touched; only the era Unbreaking skip probability
 * ({@code 60 + 40/(level+1)}%, {@link ArmourDurabilityMath}) differs — on a skip
 * the whole {@link PlayerItemDamageEvent} is suppressed with {@code setDamage(0)}.
 * Fires on the player's region thread ({@link EventPriority#LOWEST}); the OCM
 * explosion-dedup timer is deliberately omitted (no global scheduler).
 */
public final class ArmourDurabilityUnit implements FeatureUnit, Listener {

    private static final Enchantment UNBREAKING = Enchantments.unbreaking();

    @Override
    public Feature descriptor() {
        return Feature.ARMOUR_DURABILITY;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemDamage(@NotNull PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        if (!isWornArmour(player, item)) {
            return; // not a currently-worn armour piece — leave vanilla wear
        }
        int unbreakingLevel = UNBREAKING == null ? 0 : item.getEnchantmentLevel(UNBREAKING);
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (ArmourDurabilityMath.skipsDamage(unbreakingLevel, roll)) {
            event.setDamage(0); // skip this hit's wear; vanilla magnitude discarded
        }
        // Otherwise leave event.getDamage() — the unchanged vanilla magnitude.
    }

    private static boolean isWornArmour(@NotNull Player player, @NotNull ItemStack item) {
        Material type = item.getType();
        if (type == Material.ELYTRA) {
            return false; // elytra provides no protection — not era armour wear
        }
        for (ItemStack worn : player.getInventory().getArmorContents()) {
            if (worn != null && worn.getType() == type) {
                return true;
            }
        }
        return false;
    }
}

package me.vexmc.mental.module.rules.crafting;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Prevents players from crafting items on the blocked list (default: SHIELD).
 *
 * <p>Era truth: the shield was introduced in 1.9 as an off-hand slot item.
 * On 1.7/1.8 era servers there is no off-hand, but on modern Paper the crafting
 * table output slot can still place a shield into a player's inventory even when
 * the off-hand module is disabled.  Blocking the crafting result is the
 * complementary layer that closes this gap without touching any inventory
 * post-the-fact.</p>
 *
 * <p>Mechanism (Bukkit event): {@link PrepareItemCraftEvent} fires every time
 * the crafting grid changes and the server recalculates the result.  If the
 * result's material is in the blocked set we clear it to null, making the output
 * slot appear empty and preventing the crafting action entirely.  This is the
 * same approach used by OldCombatMechanics' ModuleDisableCrafting, and the one
 * that Bukkit's own API documents as the intended pattern for blocking recipes.</p>
 *
 * <p>Folia safety: {@code PrepareItemCraftEvent} fires on the crafting player's
 * owning region thread, so clearing the result inline requires no scheduling hop
 * and is region-safe.</p>
 *
 * <p>Zero-touch: when disabled (the default), this module registers no listeners
 * and has no effect on the game whatsoever.</p>
 */
public final class DisableCraftingModule extends CombatModule implements Listener {

    public DisableCraftingModule(@NotNull MentalServices services) {
        super(services,
                "disable-crafting",
                "Crafting Disable",
                "Prevents crafting of blocked items (default: SHIELD), complementing the "
                        + "disable-offhand module to fully remove the 1.9 shield from era play.",
                DebugCategory.CONFIG);
    }

    @Override
    public boolean configEnabled() {
        return services.config().crafting().enabled();
    }

    @Override
    protected void onEnable() {
        // Register this class as a Bukkit listener; CombatModule.listen()
        // auto-unregisters it when the module is disabled.
        listen(this);
    }

    @Override
    protected void onDisable() {
        // Bukkit listener teardown is handled automatically by CombatModule;
        // nothing else to clean up.
    }

    /**
     * Clears the crafting result whenever the output would be a blocked material.
     *
     * <p>The event fires before the player can actually take the item, so
     * setting the result to null is a clean, no-side-effect prevention.
     * {@code ignoreCancelled = true} avoids double-work when another plugin
     * has already cancelled the same event.</p>
     */
    @EventHandler(ignoreCancelled = true)
    public void onPrepareCraft(@NotNull PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result != null
                && services.config().crafting().blocked().contains(result.getType())) {
            event.getInventory().setResult(null);
            debug.log(() -> "crafting blocked: " + result.getType().name()
                    + " for " + event.getViewers().stream()
                            .map(e -> e.getName())
                            .findFirst()
                            .orElse("unknown"));
        }
    }
}

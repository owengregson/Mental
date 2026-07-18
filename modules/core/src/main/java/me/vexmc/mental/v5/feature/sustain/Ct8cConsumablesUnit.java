package me.vexmc.mental.v5.feature.sustain;

import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.platform.Ct8cConsumableAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Combat Test 8c consumables ({@code ct8c-consumables}, design spec
 * §2.7/§2.8/§2.10) — 20-tick drink durations, drinkable potions stacking to 16,
 * and snowballs to 64, via the 1.20.5+ item-component seam
 * ({@link Ct8cConsumableAdapter}).
 *
 * <p>Whole-feature version gate: the unit resolves the adapter at enable and, when
 * the component model is absent (below 1.20.5), logs one loud degrade line and
 * registers nothing — a genuine no-op (zero-touch). Where supported, it stamps
 * matching items through the {@code AttackRangeAdapter} write-back pattern (mutate
 * the stack's NMS handle, write the slot back only when changed) on enable, on
 * join, and as items are picked up, and strips them again on disable so a
 * toggled-off feature leaves no components behind.</p>
 *
 * <p>Stamping is per stack, not per prototype (the plan's model): two like
 * potions merge into a 16-stack only once both carry the component — a
 * not-yet-handled brewed potion merges after it is next picked up or the owner
 * rejoins. The bounded surface (enable/join/pickup) keeps the inventory writes
 * cheap.</p>
 */
public final class Ct8cConsumablesUnit implements FeatureUnit, Listener {

    private Scheduling scheduling;
    private Ct8cConsumableAdapter adapter;

    @Override
    public Feature descriptor() {
        return Feature.CT8C_CONSUMABLES;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        MentalPluginV5 plugin = (MentalPluginV5) JavaPlugin.getProvidingPlugin(getClass());
        this.scheduling = plugin.scheduling();
        this.adapter = Ct8cConsumableAdapter.probe(plugin.environment(), plugin.getLogger()::warning);

        if (!adapter.supported()) {
            // Loud degrade (B10): below 1.20.5 there is no component model, so the
            // whole feature is a no-op — register nothing (zero-touch).
            plugin.getLogger().warning("ct8c-consumables: the item-component model is 1.20.5+; on "
                    + plugin.environment().describe()
                    + " the feature is a no-op (stack sizes and drink durations unchanged).");
            return;
        }

        scope.listen(this);
        scope.task(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Player captured = player;
                scheduling.runOn(captured, () -> stampInventory(captured), () -> {});
            }
            return () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    Player captured = player;
                    scheduling.runOn(captured, () -> stripInventory(captured), () -> {});
                }
            };
        });
    }

    /* ------------------------------ listeners ----------------------------- */

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        stampInventory(event.getPlayer());
    }

    @EventHandler
    public void onPickup(@NotNull EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return; // only player inventories carry the CT8c stack sizes
        }
        ItemStack stack = event.getItem().getItemStack();
        if (stampStack(stack)) {
            event.getItem().setItemStack(stack);
        }
    }

    /* ------------------------------- stamping ----------------------------- */

    /** Stamps every matching item in a player's inventory — owning-thread only. */
    private void stampInventory(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && stampStack(item)) {
                inventory.setItem(slot, item);
            }
        }
    }

    /** Strips the CT8c components from every item in a player's inventory (disable restore). */
    private void stripInventory(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && adapter.strip(item)) {
                inventory.setItem(slot, item);
            }
        }
    }

    /** Applies the CT8c components a stack's material calls for; true when modified. */
    private boolean stampStack(@Nullable ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Material material = stack.getType();
        if (Ct8cConsumableAdapter.isDrinkablePotion(material)) {
            boolean changed = adapter.stampStackSize(stack, Ct8cConsumableAdapter.POTION_STACK_SIZE);
            return adapter.stampDrinkDuration(stack) | changed;
        }
        if (Ct8cConsumableAdapter.isSnowball(material)) {
            return adapter.stampStackSize(stack, Ct8cConsumableAdapter.SNOWBALL_STACK_SIZE);
        }
        if (Ct8cConsumableAdapter.isDrink(material)) {
            return adapter.stampDrinkDuration(stack);
        }
        return false;
    }
}

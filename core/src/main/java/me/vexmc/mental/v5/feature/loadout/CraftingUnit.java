package me.vexmc.mental.v5.feature.loadout;

import java.util.function.Supplier;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.CraftingSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Makes the configured items uncraftable (SHIELD by default) — the retired
 * {@code module.rules.crafting.DisableCraftingModule} on the v5 seam.
 *
 * <p>Era truth: the shield arrived in 1.9 as the off-hand's flagship item. On a
 * 1.7/1.8 era server there is no shield; even with the off-hand blocked, a modern
 * crafting table can still produce one into the main inventory. This rule closes
 * that gap by clearing the crafting RESULT whenever it would be a blocked
 * material — the same {@link PrepareItemCraftEvent} approach the Bukkit docs
 * prescribe. The recipe is not
 * removed from the server registry: blocking the result is fully reversible and
 * touches no persistent state, which is exactly the zero-touch restore this
 * feature needs — when the scope closes (disable/reload-off) the listener
 * unregisters and every recipe produces its result again, byte-for-byte.</p>
 *
 * <p>The blocked set is read LIVE from the snapshot on each event (never captured
 * at assemble), so a reload that changes {@code disable-crafting.blocked} while
 * the feature stays enabled takes effect immediately — matching the retired
 * module's live {@code services.config().crafting()} read.</p>
 *
 * <p>Folia: {@code PrepareItemCraftEvent} fires on the crafting player's owning
 * region thread, so clearing the result inline is region-safe with no scheduling
 * hop. Zero-touch: disabled (the default) the unit registers nothing.</p>
 */
public final class CraftingUnit implements FeatureUnit, Listener {

    private final Supplier<Snapshot> snapshot;

    public CraftingUnit(@NotNull Supplier<Snapshot> snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public Feature descriptor() {
        return Feature.CRAFTING;
    }

    @Override
    public void assemble(Scope scope, Snapshot ignored) {
        scope.listen(this);
    }

    @SuppressWarnings("unchecked")
    private CraftingSettings settings() {
        return snapshot.get().settings(
                (SettingsKey<CraftingSettings>) Feature.CRAFTING.settingsKey());
    }

    /**
     * Clears the crafting result whenever the output would be a blocked material.
     * The event fires before the player can take the item, so nulling the result
     * is a clean, side-effect-free prevention. {@code ignoreCancelled = true}
     * avoids double-work when another plugin already cancelled the same event.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPrepareCraft(@NotNull PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result != null && settings().blocked().contains(result.getType())) {
            event.getInventory().setResult(null);
        }
    }
}

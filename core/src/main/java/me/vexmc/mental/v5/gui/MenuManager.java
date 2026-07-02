package me.vexmc.mental.v5.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Always-on routing for Mental's management menus (the retired
 * {@code gui/MenuManager}, unchanged in substance).
 *
 * <p>This is infrastructure, not a combat module: it is registered directly in
 * the bootstrap for the plugin's lifetime and never touches the game, so it
 * honours zero-touch trivially. A single holder-identity check — {@code
 * getHolder() instanceof Menu} — routes every click, drag and close to the
 * owning menu; any interaction with a Mental menu open is cancelled, so its
 * decorative icons can never be removed.</p>
 */
public final class MenuManager implements Listener {

    private final MenuContext context;

    public MenuManager(@NotNull MenuContext context) {
        this.context = context;
    }

    public @NotNull MenuContext context() {
        return context;
    }

    /** Opens the dashboard for a player — the single GUI entry point. */
    public void openDashboard(@NotNull Player player) {
        new DashboardMenu(context).open(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            menu.handleClick(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            menu.handleClose(event);
        }
    }

    /**
     * Closes any open Mental menu and unregisters routing on plugin disable, so
     * no inert (un-cancelling) menu is ever left interactive. Best-effort per
     * player: on Folia, reading another player's open inventory off their region
     * thread throws, and the plugin is disabling anyway — the menu items are
     * decorative, so a left-open menu on shutdown is harmless.
     */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
                if (holder instanceof Menu) {
                    player.closeInventory();
                }
            } catch (Throwable ignored) {
                // off-region read on Folia, or a closing race — nothing to do.
            }
        }
        HandlerList.unregisterAll(this);
    }
}

package me.vexmc.mental.module.rules.offhand;

import java.util.Map;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.OffhandSettings;
import me.vexmc.mental.engine.CombatModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Blocks players from placing or using items in the off-hand slot (slot 40).
 *
 * <p>Era truth: the off-hand slot was introduced in 1.9.  On a 1.7/1.8 era
 * server the slot does not exist for players.  This module intercepts every
 * path through which an item can reach the off-hand on modern Paper:</p>
 * <ul>
 *   <li>{@link PlayerSwapHandItemsEvent} — the F key.</li>
 *   <li>{@link InventoryClickEvent} — direct click on slot 40,
 *       {@link ClickType#NUMBER_KEY} hotbar-key swap that targets slot 40,
 *       {@link ClickType#SWAP_OFFHAND} (F-key inside an open inventory, 1.16+),
 *       and shift-click when the item would land in the off-hand.</li>
 *   <li>{@link InventoryDragEvent} — dragging items across slot 40.</li>
 *   <li>{@link PlayerJoinEvent} and {@link PlayerChangedWorldEvent} — strip
 *       any disallowed item already in the off-hand so it cannot persist
 *       across sessions or world changes.</li>
 * </ul>
 *
 * <p>Filter semantics mirror OCM's ModuleDisableOffHand: in whitelist mode
 * only the configured materials are permitted; in blacklist mode the listed
 * materials are denied and everything else is allowed.  {@link Material#AIR}
 * (an empty slot or cursor) is always allowed.</p>
 *
 * <p>Folia safety: all entity/inventory mutation runs through
 * {@link me.vexmc.mental.common.scheduling.Scheduling#runOn} so it executes
 * on the player's owning region thread.  Event handlers that cancel events
 * inline require no scheduling hop because Bukkit guarantees they fire on the
 * correct thread already.  The strip-on-enable pass uses {@code runOn} for
 * every online player.</p>
 *
 * <p>Zero-touch: when disabled (the default), this module registers no
 * listeners and has no effect on the game whatsoever.</p>
 */
public final class OffhandModule extends CombatModule implements Listener {

    /** The off-hand slot index inside a {@link PlayerInventory}. */
    private static final int OFFHAND_SLOT = 40;

    public OffhandModule(@NotNull MentalServices services) {
        super(services,
                "disable-offhand",
                "Off-hand Disable",
                "Blocks use of the 1.9 off-hand slot with a configurable whitelist/blacklist "
                        + "filter — restoring the single-hand inventory of 1.7/1.8 era play.",
                DebugCategory.CONFIG);
    }

    @Override
    public boolean configEnabled() {
        return services.config().offhand().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);

        // Strip any disallowed off-hand items from players who are already
        // online when the module is enabled (e.g. /mental reload).
        for (Player player : Bukkit.getOnlinePlayers()) {
            // runOn ensures the inventory mutation runs on the player's owning
            // region thread (Folia-correct; on Paper it's the main thread).
            services.scheduling().runOn(
                    player,
                    () -> stripOffhandIfDisallowed(player),
                    () -> {} /* player left before the task ran — nothing to do */);
        }
    }

    @Override
    protected void onDisable() {
        // Bukkit listener teardown is handled automatically by CombatModule.
    }

    /* ------------------------------------------------------------------ */
    /*  Event handlers                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * F-key swap: if the item that would go into the off-hand is disallowed,
     * cancel the swap.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHandItems(@NotNull PlayerSwapHandItemsEvent event) {
        if (isItemDisallowed(event.getOffHandItem())) {
            event.setCancelled(true);
            sendDenied(event.getPlayer());
        }
    }

    /**
     * Inventory click: intercept the various ways an item can be moved into
     * the off-hand slot.
     *
     * <p>Cases handled (mirroring OCM's condition set, modern API only — no
     * pre-1.16 reflection path; Mental's floor is 1.17.1):</p>
     * <ol>
     *   <li>{@link ClickType#SWAP_OFFHAND} (F key inside an open inventory,
     *       introduced in 1.16) — always targets the off-hand regardless of
     *       which slot is focused.</li>
     *   <li>Slot 40 directly — left/right click, number-key swap
     *       ({@link ClickType#NUMBER_KEY}) placing a hotbar item into slot 40,
     *       or a cursor drop onto slot 40.</li>
     *   <li>Shift-click on a SHIELD when the view is the player's own
     *       inventory (CRAFTING type) — on vanilla Paper, shift-clicking a
     *       shield in the player inventory sends it to the off-hand slot
     *       rather than a regular hotbar slot.  We mirror OCM's guard here:
     *       shield only, since other items shift-click to a hotbar slot
     *       rather than the off-hand.</li>
     * </ol>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        HumanEntity who = event.getWhoClicked();
        if (!(who instanceof Player player)) {
            return;
        }

        ClickType click = event.getClick();

        // Case 1: F-key-style swap targeting the off-hand, available in any
        // open inventory since 1.16. ClickType.SWAP_OFFHAND is defined from
        // 1.16; Mental's floor is 1.17.1, so no try/catch needed.
        if (click == ClickType.SWAP_OFFHAND) {
            // The item that will land in the off-hand is the cursor item for
            // SWAP_OFFHAND clicks; if cursor is empty the item currently
            // focused (getCurrentItem) moves to the off-hand.
            ItemStack toOffhand = event.getCursor();
            if (toOffhand == null || toOffhand.getType() == Material.AIR) {
                toOffhand = event.getCurrentItem();
            }
            if (isItemDisallowed(toOffhand)) {
                event.setResult(org.bukkit.event.Event.Result.DENY);
                sendDenied(player);
            }
            return;
        }

        // For all remaining cases the clicked inventory must be the player's
        // own inventory (InventoryType.PLAYER) to involve slot 40.
        if (event.getClickedInventory() == null) {
            return;
        }
        if (event.getClickedInventory().getType() != InventoryType.PLAYER) {
            return;
        }

        // Case 2: NUMBER_KEY (hotbar digit) while hovering over slot 40.
        // getHotbarButton() gives the hotbar index (0-8).
        if (click == ClickType.NUMBER_KEY && event.getSlot() == OFFHAND_SLOT) {
            ItemStack fromHotbar = player.getInventory().getItem(event.getHotbarButton());
            if (isItemDisallowed(fromHotbar)) {
                event.setResult(org.bukkit.event.Event.Result.DENY);
                sendDenied(player);
            }
            return;
        }

        // Case 3: any click directly on the off-hand slot (cursor → slot).
        if (event.getSlot() == OFFHAND_SLOT && isItemDisallowed(event.getCursor())) {
            event.setResult(org.bukkit.event.Event.Result.DENY);
            sendDenied(player);
            return;
        }

        // Case 4: shift-click on a SHIELD while in the player inventory view
        // (InventoryType.CRAFTING — the default "no chest open" view).
        // On vanilla Paper, shift-click on a shield routes it to the off-hand
        // slot. Other items shift-click to a hotbar/main slot, so we limit
        // the guard to shields to avoid over-blocking.
        if (event.isShiftClick()
                && event.getSlot() != OFFHAND_SLOT
                && event.getCurrentItem() != null
                && event.getCurrentItem().getType() == Material.SHIELD
                && isItemDisallowed(event.getCurrentItem())) {
            // Only intercept when both the clicked inventory and the player's
            // own view are the CRAFTING (default) inventory (no chest open).
            InventoryType top = event.getView().getTopInventory().getType();
            InventoryType bottom = event.getView().getBottomInventory().getType();
            if (top == InventoryType.CRAFTING || bottom == InventoryType.CRAFTING) {
                event.setResult(org.bukkit.event.Event.Result.DENY);
                sendDenied(player);
            }
        }
    }

    /**
     * Drag: if any dragged portion would land in the off-hand slot, cancel.
     *
     * <p>Dragging is a distinct interaction from clicking: the player spreads
     * the cursor across multiple slots.  We cancel when the dragged set
     * includes slot 40 and the dragged item is disallowed.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        HumanEntity who = event.getWhoClicked();
        if (!(who instanceof Player player)) {
            return;
        }
        // Only care when the drag spans the player inventory (CRAFTING view).
        if (event.getInventory().getType() != InventoryType.CRAFTING) {
            return;
        }
        if (!event.getInventorySlots().contains(OFFHAND_SLOT)) {
            return;
        }
        if (isItemDisallowed(event.getOldCursor())) {
            event.setResult(org.bukkit.event.Event.Result.DENY);
            sendDenied(player);
        }
    }

    /**
     * Join: strip a disallowed off-hand item that persisted in the player's
     * saved data.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Folia: run on the player's owning region thread.
        services.scheduling().runOn(
                player,
                () -> stripOffhandIfDisallowed(player),
                () -> {});
    }

    /**
     * World change: strip a disallowed off-hand item that would otherwise
     * carry across worlds (e.g. acquired in a world with the module off).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        // Folia: run on the player's owning region thread.
        services.scheduling().runOn(
                player,
                () -> stripOffhandIfDisallowed(player),
                () -> {});
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Checks whether an item is disallowed in the off-hand under the current
     * configuration. {@code null} and {@link Material#AIR} are always allowed
     * (clearing the slot is harmless).
     */
    private boolean isItemDisallowed(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        OffhandSettings cfg = services.config().offhand();
        return !OffhandPolicy.isAllowedInOffhand(item.getType(), cfg.whitelist(), cfg.items());
    }

    /**
     * If the player's current off-hand item is disallowed, clears the slot
     * and returns the item to their inventory (dropping overflow at their
     * feet).
     *
     * <p><b>Must run on the player's owning region thread.</b>  All callers
     * go through {@link me.vexmc.mental.common.scheduling.Scheduling#runOn}.</p>
     */
    private void stripOffhandIfDisallowed(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack offhand = inventory.getItemInOffHand();
        if (offhand.getType() == Material.AIR || !isItemDisallowed(offhand)) {
            return;
        }

        // Clear the off-hand slot first, then give the item back.
        inventory.setItemInOffHand(new ItemStack(Material.AIR));
        Map<Integer, ItemStack> leftover = inventory.addItem(offhand);
        if (!leftover.isEmpty()) {
            // Inventory is full — drop the remainder at the player's location.
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }

        sendDenied(player);
        debug.log(() -> "stripped off-hand item " + offhand.getType().name()
                + " from " + player.getName());
    }

    /**
     * Sends the configured denied message to the player if the message is
     * non-empty. The '&amp;' colour code prefix is translated via the Adventure
     * legacy serializer, matching the operator convention used by OCM and the
     * rest of the plugin ecosystem.  An empty (or blank) message is silently
     * suppressed so operators can disable notifications by setting
     * {@code denied-message: ""}.
     */
    private void sendDenied(@NotNull HumanEntity player) {
        String raw = services.config().offhand().deniedMessage();
        if (raw.trim().isEmpty()) {
            return;
        }
        // Translate '&' colour codes into Adventure components using the
        // legacy ampersand serializer (included in Paper's bundled Adventure).
        Component message = LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
        player.sendMessage(message);
    }
}

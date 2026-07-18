package me.vexmc.mental.v5.feature.loadout;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.kernel.math.OffhandPolicy;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.OffhandSettings;
import me.vexmc.mental.v5.feature.EphemeralDecoration;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.text.TextPort;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
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
 * Blocks items from the 1.9 off-hand slot (slot 40) — the retired
 * {@code module.rules.offhand.OffhandModule} on the v5 seam.
 *
 * <p>Era truth: the off-hand slot arrived in 1.9; on a 1.7/1.8 era server it does
 * not exist for players. This rule intercepts every path an item can reach the
 * off-hand on modern Paper — the F-key swap ({@link PlayerSwapHandItemsEvent}),
 * the in-inventory swap / number-key / cursor-drop / shield shift-click
 * ({@link InventoryClickEvent}), a spanning drag ({@link InventoryDragEvent}) —
 * and strips a disallowed item that persisted from a saved profile or was carried
 * across a world boundary ({@link PlayerJoinEvent}, {@link PlayerChangedWorldEvent},
 * and the enable pass over online players).</p>
 *
 * <p>The filter is the kernel {@link OffhandPolicy} (whitelist/blacklist over a
 * String-keyed material set): whitelist mode with an empty set — the era default —
 * blocks every item; {@link Material#AIR} (an empty slot or cursor) is always
 * permitted. Settings are read LIVE from the snapshot, so a reload that changes
 * the filter takes effect immediately.</p>
 *
 * <p>The strip returns the item to the player's inventory (dropping any overflow
 * at their feet) — a one-shot inventory correction, not a temporary decoration, so
 * there is no revert web to reuse the {@code EphemeralDecoration} service for
 * (this feature never injects a temp item). Folia: inventory mutation runs through
 * {@link Scheduling#runOn} on the player's owning region thread; inline event
 * cancellation needs no hop. Zero-touch: disabled (the default) the unit registers
 * nothing.</p>
 */
public final class OffhandUnit implements FeatureUnit, Listener {

    /** The off-hand slot index inside a {@link PlayerInventory}. */
    private static final int OFFHAND_SLOT = 40;

    private final Supplier<Snapshot> snapshot;
    private final Scheduling scheduling;

    /**
     * The sword-block decoration service — consulted read-only so the strip never
     * eats Mental's OWN injected temp shield (interaction audit: SWORD_BLOCKING ×
     * OFFHAND). The enable/reload pass runs over every online player; a mid-block
     * player's off-hand holds the PDC-marked temp shield while their REAL off-hand
     * item sits in the decoration's in-memory store — stripping the shield to
     * storage makes the release poll find nothing to revert and silently discard
     * the stored original (permanent item loss + a conjured marked shield). The
     * decoration's own exit web reverts the slot; the off-hand policy then applies
     * to the RESTORED item on its next join/world-change pass.
     */
    private final EphemeralDecoration swordBlock;

    public OffhandUnit(
            @NotNull Supplier<Snapshot> snapshot, @NotNull Scheduling scheduling,
            @NotNull EphemeralDecoration swordBlock) {
        this.snapshot = snapshot;
        this.scheduling = scheduling;
        this.swordBlock = swordBlock;
    }

    @Override
    public Feature descriptor() {
        return Feature.OFFHAND;
    }

    @Override
    public void assemble(Scope scope, Snapshot ignored) {
        scope.listen(this);
        // Strip any disallowed off-hand item from players already online when the
        // feature is enabled (e.g. a reload that flips it on). Runs on the enable
        // (converge) thread; each mutation hops to the player's region thread.
        scope.task(() -> {
            for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                scheduling.runOn(player, () -> stripOffhandIfDisallowed(player), () -> {});
            }
            // No teardown: stripping is a one-shot correction with nothing to
            // restore. The listener unregisters via the sibling scope.listen(this).
            return () -> {};
        });
    }

    @SuppressWarnings("unchecked")
    private OffhandSettings settings() {
        return snapshot.get().settings(
                (SettingsKey<OffhandSettings>) Feature.OFFHAND.settingsKey());
    }

    /* ------------------------------------------------------------------ */
    /*  Event handlers                                                     */
    /* ------------------------------------------------------------------ */

    /** F-key swap: cancel when the would-be off-hand item is disallowed. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHandItems(@NotNull PlayerSwapHandItemsEvent event) {
        if (isItemDisallowed(event.getOffHandItem())) {
            event.setCancelled(true);
            sendDenied(event.getPlayer());
        }
    }

    /**
     * Inventory click: intercept every in-inventory route into the off-hand —
     * the {@link ClickType#SWAP_OFFHAND} F-key (1.16+), a number-key or cursor
     * drop onto slot 40, and a shield shift-click (which vanilla routes to the
     * off-hand). Mirrors the retired module's condition set exactly.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ClickType click = event.getClick();

        // Case 1: the F-key-style swap targets the off-hand from any open inventory.
        if (click == ClickType.SWAP_OFFHAND) {
            ItemStack toOffhand = event.getCursor();
            if (toOffhand == null || toOffhand.getType() == Material.AIR) {
                toOffhand = event.getCurrentItem();
            }
            if (isItemDisallowed(toOffhand)) {
                event.setResult(Event.Result.DENY);
                sendDenied(player);
            }
            return;
        }

        // The remaining cases require the clicked inventory to be the player's own.
        if (event.getClickedInventory() == null
                || event.getClickedInventory().getType() != InventoryType.PLAYER) {
            return;
        }

        // Case 2: NUMBER_KEY (hotbar digit) while hovering over slot 40.
        if (click == ClickType.NUMBER_KEY && event.getSlot() == OFFHAND_SLOT) {
            ItemStack fromHotbar = player.getInventory().getItem(event.getHotbarButton());
            if (isItemDisallowed(fromHotbar)) {
                event.setResult(Event.Result.DENY);
                sendDenied(player);
            }
            return;
        }

        // Case 3: any click that places the cursor directly onto the off-hand slot.
        if (event.getSlot() == OFFHAND_SLOT && isItemDisallowed(event.getCursor())) {
            event.setResult(Event.Result.DENY);
            sendDenied(player);
            return;
        }

        // Case 4: shift-click on a SHIELD in the default (CRAFTING) inventory view,
        // which vanilla routes to the off-hand. Limited to shields; other items
        // shift-click to a hotbar/main slot, so a wider guard would over-block.
        if (event.isShiftClick()
                && event.getSlot() != OFFHAND_SLOT
                && event.getCurrentItem() != null
                && event.getCurrentItem().getType() == Material.SHIELD
                && isItemDisallowed(event.getCurrentItem())) {
            InventoryType top = event.getView().getTopInventory().getType();
            InventoryType bottom = event.getView().getBottomInventory().getType();
            if (top == InventoryType.CRAFTING || bottom == InventoryType.CRAFTING) {
                event.setResult(Event.Result.DENY);
                sendDenied(player);
            }
        }
    }

    /** Drag: cancel when a dragged portion would land in the off-hand slot. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getType() != InventoryType.CRAFTING) {
            return;
        }
        if (!event.getInventorySlots().contains(OFFHAND_SLOT)) {
            return;
        }
        if (isItemDisallowed(event.getOldCursor())) {
            event.setResult(Event.Result.DENY);
            sendDenied(player);
        }
    }

    /** Join: strip a disallowed off-hand item that persisted in the saved profile. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        scheduling.runOn(player, () -> stripOffhandIfDisallowed(player), () -> {});
    }

    /** World change: strip a disallowed off-hand item carried across worlds. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        scheduling.runOn(player, () -> stripOffhandIfDisallowed(player), () -> {});
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Whether {@code item} is disallowed in the off-hand under the live config.
     * {@code null} and {@link Material#AIR} are always allowed (clearing the slot
     * is harmless). Delegates the whitelist/blacklist decision to the kernel
     * {@link OffhandPolicy} over the material names.
     */
    private boolean isItemDisallowed(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        OffhandSettings cfg = settings();
        Set<String> names = cfg.items().stream().map(Material::name).collect(Collectors.toSet());
        return !OffhandPolicy.isAllowedInOffhand(item.getType().name(), cfg.whitelist(), names);
    }

    /**
     * If the player's current off-hand item is disallowed, clears the slot and
     * returns the item to their inventory (dropping overflow at their feet).
     * <b>Must run on the player's owning region thread.</b>
     */
    private void stripOffhandIfDisallowed(@NotNull Player player) {
        // Mental's own temp shield is always allowed (see the field javadoc): the
        // decoration owns that slot for the block's duration and reverts it on
        // every exit path — stripping it here would orphan the stored original.
        if (swordBlock.isBlockingWithTempShield(player)) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack offhand = inventory.getItemInOffHand();
        if (offhand.getType() == Material.AIR || !isItemDisallowed(offhand)) {
            return;
        }
        inventory.setItemInOffHand(new ItemStack(Material.AIR));
        Map<Integer, ItemStack> leftover = inventory.addItem(offhand);
        if (!leftover.isEmpty()) {
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
        sendDenied(player);
    }

    /**
     * Sends the configured denied message (translating {@code &} colour codes),
     * suppressing an empty/blank message so operators can silence notifications
     * with {@code denied-message: ""}.
     */
    private void sendDenied(@NotNull HumanEntity player) {
        String raw = settings().deniedMessage();
        if (raw.trim().isEmpty()) {
            return;
        }
        Component message = LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
        // Component sink routed through TextPort → sendMessage(String): the Paper
        // sendMessage(Component) audience method is absent below 1.16.5. HumanEntity
        // IS a CommandSender on the modern API but NOT on 1.9–1.15 (there it extends
        // Permissible), so the reference is widened through Object to force a genuine
        // runtime check — a real player is always a CommandSender at runtime, on
        // every version; a non-sender HumanEntity just gets no cosmetic denial line.
        Object recipient = player;
        if (recipient instanceof CommandSender sender) {
            TextPort.send(sender, message);
        }
    }
}

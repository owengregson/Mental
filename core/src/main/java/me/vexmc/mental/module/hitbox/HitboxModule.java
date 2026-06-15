package me.vexmc.mental.module.hitbox;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

/**
 * Restores the 1.7/1.8-era melee reach and hitbox margin as far as the modern
 * server allows, using whichever per-version lever the server actually exposes
 * (resolved once by capability — class/field/attribute presence — never by a
 * version literal).
 *
 * <h2>The hard limit (why this is a ceiling, not parity)</h2>
 * <p>The CLIENT picks the melee target and sends a fixed entity id in
 * {@code ServerboundInteractPacket}; the server resolves it verbatim with NO
 * server-side raytrace on the attack path. True 1.7 target-selection geometry is
 * therefore unreachable server-side. This module can only tune the reach GATE and
 * the entity-AABB targeting MARGIN; target selection stays whatever the modern
 * client does (already close to 1.7 for melee). Net achievable = era reach
 * distance + a small hitbox margin (1.21.5+).</p>
 *
 * <h2>Per-version levers</h2>
 * <ul>
 *   <li><b>1.17.1–1.20.4</b> — NO interaction-range attribute and NO
 *       {@code ATTACK_RANGE} component exist; the gate is a hardcoded 6 blocks,
 *       already more lenient than era {@code 3.0}, with no safe per-player lever.
 *       This module is a complete <b>no-op</b> here (both drivers report
 *       unsupported, so no attribute and no component is ever written).</li>
 *   <li><b>1.20.5+</b> — the {@code ENTITY_INTERACTION_RANGE} attribute (era base
 *       {@code 3.0}). {@link EntityInteractionRange} pins the base to era and
 *       restores the original on quit/disable. The default base is already
 *       {@code 3.0}, so the meaningful effect is resetting any third-party
 *       inflation; the server's leniency on top cannot be removed from this
 *       surface.</li>
 *   <li><b>1.21.5+</b> — additionally, the {@code ATTACK_RANGE} item data
 *       component (era {@code max_reach=3.0}, {@code hitbox_margin=0.1}). The
 *       held weapon gets the era component via {@link AttackRangeComponents},
 *       reconciled on join/hotbar/swap/drop and stripped on
 *       drop/death/world/quit/disable.</li>
 * </ul>
 *
 * <h2>Reversibility (zero-touch)</h2>
 * <p>When disabled (default) or on an unsupported tier the module registers
 * nothing and writes nothing. When enabled it strips every {@code ATTACK_RANGE}
 * component from online players' hands and restores every captured attribute base
 * on disable, and per-player on quit — so neither a stray component nor a tightened
 * attribute is ever left behind.</p>
 *
 * <h2>Threading (Folia)</h2>
 * <p>All attribute and item writes go through {@code services.scheduling().runOn(
 * player, …)} (the player's region thread) — never the Bukkit global scheduler.</p>
 */
public final class HitboxModule extends CombatModule implements Listener {

    private final AttackRangeComponents components;
    private final EntityInteractionRange interactionRange;

    public HitboxModule(@NotNull MentalServices services) {
        super(services,
                "old-hitboxes",
                "Old Hitboxes",
                "Era melee reach (3.0) + hitbox margin (0.1): ENTITY_INTERACTION_RANGE "
                        + "attribute (1.20.5+) and ATTACK_RANGE item component (1.21.5+); "
                        + "no-op on 1.17.1-1.20.4 (client picks the target).",
                DebugCategory.CONFIG);
        this.components = new AttackRangeComponents();
        this.interactionRange = new EntityInteractionRange(services, debug);
    }

    @Override
    public boolean configEnabled() {
        // Enable whenever the config wants it; the drivers each self-gate on their
        // capability, so on a tier with neither lever this stays a clean no-op.
        return services.config().hitbox().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
        debug.log(() -> "old-hitboxes active: attribute=" + interactionRange.supported()
                + " component=" + components.describe());
        // Apply to already-online players so toggling the module on takes effect now.
        for (Player player : Bukkit.getOnlinePlayers()) {
            interactionRange.apply(player);
            applyToHeld(player);
        }
    }

    @Override
    protected void onDisable() {
        // Zero-touch: restore every captured attribute base and strip every
        // ATTACK_RANGE component from online players' hands.
        interactionRange.disableAll();
        for (Player player : Bukkit.getOnlinePlayers()) {
            stripHands(player);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                          */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        interactionRange.apply(player);
        applyToHeld(player);
    }

    /**
     * Hotbar slot change: strip the era component from the slot being left, then
     * apply it to the newly-held weapon. The strips/applies run on the player's
     * region thread so they observe the post-change inventory.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldChange(@NotNull PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!components.supported()) {
            return; // No component lever — nothing to reconcile (the attribute is per-player).
        }
        ItemStack prev = player.getInventory().getItem(event.getPreviousSlot());
        if (components.strip(prev)) {
            player.getInventory().setItem(event.getPreviousSlot(), prev);
        }
        // The new slot's item is the one the event is switching TO; reconcile it on
        // the region thread so getItemInMainHand() reflects the completed switch.
        services.scheduling().runOn(player, () -> applyToHeld(player), () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(@NotNull PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!components.supported()) {
            return;
        }
        // The swap moves the weapon between hands; re-read on the region thread and
        // strip the off-hand / (re)apply to the new main hand (Folia-correct).
        services.scheduling().runOn(player, () -> {
            PlayerInventory inventory = player.getInventory();
            ItemStack off = inventory.getItemInOffHand();
            if (off.getType() != Material.AIR && components.strip(off)) {
                inventory.setItemInOffHand(off);
            }
            applyToHeld(player);
        }, () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        if (!components.supported()) {
            return;
        }
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (components.strip(dropped)) {
            event.getItemDrop().setItemStack(dropped);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        if (!components.supported()) {
            return;
        }
        // Strip the component from every drop AND from the hands (keep-inventory
        // worlds keep the held item), so a respawned/looted weapon is clean.
        for (ItemStack drop : event.getDrops()) {
            components.strip(drop);
        }
        stripHands(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        // The attribute is per-entity and survives a world change, but re-apply to
        // be safe; re-reconcile the held weapon's component.
        interactionRange.apply(player);
        applyToHeld(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Restore the attribute base, and strip the component so a saved inventory
        // never persists a Mental-modified weapon.
        interactionRange.restore(player);
        stripHands(player);
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /** Applies the era component to the player's currently-held main-hand weapon. */
    private void applyToHeld(@NotNull Player player) {
        if (!components.supported()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack main = inventory.getItemInMainHand();
        if (main.getType() == Material.AIR) {
            return;
        }
        if (AttackRangeComponents.isWeapon(main.getType())) {
            if (components.apply(main)) {
                inventory.setItemInMainHand(main);
            }
        } else if (components.strip(main)) {
            // A non-weapon in the main hand should never carry our component.
            inventory.setItemInMainHand(main);
        }
    }

    /** Strips the era component from both of a player's hands (write-back guarded). */
    private void stripHands(@NotNull Player player) {
        if (!components.supported()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack main = inventory.getItemInMainHand();
        if (components.strip(main)) {
            inventory.setItemInMainHand(main);
        }
        ItemStack off = inventory.getItemInOffHand();
        if (off.getType() != Material.AIR && components.strip(off)) {
            inventory.setItemInOffHand(off);
        }
    }
}

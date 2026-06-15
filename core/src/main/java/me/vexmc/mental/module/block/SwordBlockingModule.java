package me.vexmc.mental.module.block;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

/**
 * 1.7-style right-click sword blocking via modern item data components.
 *
 * <p>Right-clicking with a sword in the main hand raises it to block, using the
 * highest data-component capability the server supports (resolved once by
 * {@link SwordBlockComponents}):</p>
 *
 * <ul>
 *   <li><b>Tier A</b> ({@code BLOCKS_ATTACKS} present, ~1.21.5+): the native
 *       component reproduces the 1.8 {@code (damage-1)*0.5} reduction AND makes
 *       the server report {@code isBlocking()} and run the blocked-damage
 *       pipeline. This module applies <em>no</em> software reduction on Tier A —
 *       doing so would double-count.</li>
 *   <li><b>Tier B</b> ({@code CONSUMABLE} only, 1.21.0–1.21.4): the component
 *       grants the block pose / active-use state but does not reduce damage, so
 *       this module applies the 1.8 reduction in software in
 *       {@link #onDamage(EntityDamageByEntityEvent)}.</li>
 *   <li><b>Tier C</b> (neither, 1.17.1–1.20.6): {@link SwordBlockComponents} is
 *       unsupported; this module registers no listeners and is a complete no-op.
 *       The legacy offhand-shield fallback is a separate module.</li>
 * </ul>
 *
 * <h2>Trigger and lifecycle</h2>
 * <p>{@link PlayerInteractEvent} (RIGHT_CLICK_AIR/BLOCK, sword in main hand,
 * with the off-hand double-fire de-duplicated) applies the component to the
 * inventory-backed stack and calls {@code startUsingItem(HAND)} so the server
 * enters the active-use state. The component is stripped on every way the held
 * sword can leave the hand or the use can end — held-slot change, off-hand swap,
 * drop, death, world change, quit — and on disable for all online players, so a
 * sword is never left carrying a stray block component (zero-touch).</p>
 *
 * <h2>Knockback (era truth)</h2>
 * <p>A blocked hit still knocks the victim FULL — only the DAMAGE is reduced.
 * Mental owns knockback; this module never cancels the event or touches velocity.</p>
 *
 * <h2>Honest limit</h2>
 * <p>This cannot be byte-identical to the 1.7 CLIENT (the lowered-sword pose is a
 * client asset). It delivers server BEHAVIOURAL parity (right-click block,
 * {@code (dmg-1)*0.5}, full knockback) plus a version-appropriate block pose.</p>
 *
 * <h2>Threading (Folia)</h2>
 * <p>Every handler runs on the relevant entity's region thread (interact / item /
 * death / quit on the player's; the damage event on the victim's), so the item
 * reads/writes and the damage adjustment are all safe inline. Where a deferred
 * re-read is needed it goes through {@link me.vexmc.mental.common.scheduling.Scheduling#runOn}
 * — never the Bukkit global scheduler.</p>
 */
public final class SwordBlockingModule extends CombatModule implements Listener {

    private final SwordBlockComponents components;

    public SwordBlockingModule(@NotNull MentalServices services) {
        super(services,
                "sword-blocking",
                "Sword Blocking",
                "1.7-style right-click sword blocking via modern data components "
                        + "(1.21+): block pose + (damage-1)*0.5 reduction, full knockback.",
                DebugCategory.CONFIG);
        this.components = new SwordBlockComponents();
    }

    @Override
    public boolean configEnabled() {
        // Tier C / no component model → zero-touch: never enable, never register.
        return services.config().swordBlocking().enabled() && components.supported();
    }

    @Override
    protected void onEnable() {
        listen(this);
        debug.log(() -> "sword-blocking active: " + components.describe());
    }

    @Override
    protected void onDisable() {
        // Zero-touch: strip any in-flight block component from every online player's
        // hands so a sword is never left tainted after the module is turned off.
        for (Player player : Bukkit.getOnlinePlayers()) {
            stripHands(player);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Trigger                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Right-click with a sword → apply the block component and enter active use.
     *
     * <p>The interact event double-fires (once per hand) for RIGHT_CLICK_AIR; we
     * only act on the main-hand fire so a single right-click does not apply twice.
     * RIGHT_CLICK_BLOCK against an interactive block may consume the main-hand
     * fire, but the sword is in the main hand either way, so we accept both
     * actions and gate on {@code getHand() == HAND}.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // Dedupe the per-hand double-fire: act only on the main-hand event.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        ItemStack mainHand = inventory.getItemInMainHand();
        if (!SwordBlockComponents.isSword(mainHand.getType())) {
            return;
        }

        // Re-read the inventory-backed CraftItemStack and patch the real server-side
        // item, then write it back so the component sticks on the live stack.
        if (components.apply(mainHand)) {
            inventory.setItemInMainHand(mainHand);
        }
        // Enter the active-use state so the block pose shows and isBlocking() is true.
        // (startUsingItem is reflective — absent on the 1.17.1 compile floor.)
        components.startUsing(player);
        debug.log(() -> "sword-blocking start for " + player.getName() + " (" + components.tier() + ")");
    }

    /* ------------------------------------------------------------------ */
    /*  Reduction (Tier B only — Tier A reduces natively)                  */
    /* ------------------------------------------------------------------ */

    /**
     * Applies the 1.8 {@code (damage-1)*0.5} reduction when the victim is blocking
     * a melee hit with a sword. <b>Tier A is skipped entirely</b> — its
     * {@code BLOCKS_ATTACKS} component already reduces the damage natively, so
     * adding the software reduction would double-count.
     *
     * <p>Only DAMAGE is adjusted; the event is never cancelled and knockback is
     * left FULL (era truth — Mental owns knockback). The event fires on the
     * victim's region thread, so reading the victim's active item and writing the
     * damage are safe inline.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageByEntityEvent event) {
        if (components.nativeReduction()) {
            return; // Tier A: native BLOCKS_ATTACKS reduction — never double-reduce.
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!components.isBlockingSword(victim)) {
            return;
        }

        double incoming = event.getDamage();
        double reduction = SwordBlockReduction.blockedDamage(incoming);
        if (reduction <= 0.0) {
            return;
        }
        // Reduce DAMAGE only; do NOT cancel — a blocked hit still knocks full.
        event.setDamage(Math.max(0.0, incoming - reduction));
        debug.log(() -> "sword-blocking (Tier B) reduced " + incoming + " by " + reduction
                + " for " + victim.getName());
    }

    /* ------------------------------------------------------------------ */
    /*  Strip lifecycle                                                    */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldChange(@NotNull PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        // The previously-held slot may carry the component — strip it.
        ItemStack prev = player.getInventory().getItem(event.getPreviousSlot());
        if (components.strip(prev)) {
            player.getInventory().setItem(event.getPreviousSlot(), prev);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(@NotNull PlayerSwapHandItemsEvent event) {
        // The swap moves the sword between hands; the component must not survive it.
        // Re-read after the swap on the player's region thread so we strip whatever
        // actually landed in each hand (Folia-correct, never the Bukkit scheduler).
        Player player = event.getPlayer();
        services.scheduling().runOn(player, () -> stripHands(player), () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (components.strip(dropped)) {
            event.getItemDrop().setItemStack(dropped);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        // Strip the component from every drop AND from the player's hands (keep-inventory
        // worlds restore the held item directly), so a respawned/looted sword is clean.
        for (ItemStack drop : event.getDrops()) {
            components.strip(drop);
        }
        stripHands(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        stripHands(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        stripHands(event.getPlayer());
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /** Strips the block component from both of a player's hands (write-back guarded). */
    private void stripHands(@NotNull LivingEntity entity) {
        if (!(entity instanceof Player player)) {
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

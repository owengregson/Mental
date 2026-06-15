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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
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
 *   <li><b>Tier C</b> (neither component, 1.17.1–1.20.6): there is no in-place
 *       sword pose, so the era technique is emulated by {@link OffhandShieldBlocking}
 *       — a real {@link Material#SHIELD} is temporarily injected into the off-hand
 *       (native {@code isBlocking()} works on every version with a shield) and the
 *       hit is software-reduced. This is the only path that mutates the off-hand;
 *       the component tiers never touch it.</li>
 * </ul>
 *
 * <p>The two paths are mutually exclusive by tier — exactly one is live on any
 * given server — so the software {@code (dmg-1)*0.5} reduction can never
 * double-count: Tier A reduces natively, Tier B reduces in software on the held
 * sword, Tier C reduces in software on the off-hand shield.</p>
 *
 * <h2>Trigger and lifecycle</h2>
 * <p>{@link PlayerInteractEvent} (RIGHT_CLICK_AIR/BLOCK, sword in main hand,
 * with the off-hand double-fire de-duplicated) dispatches to the live tier. The
 * component tiers apply the component to the inventory-backed stack and call
 * {@code startUsingItem(HAND)}; Tier C injects the off-hand shield and starts a
 * per-player release poll. The component is stripped (tiers A/B) or the shield is
 * restored (tier C) on every way the held sword can leave the hand or the use can
 * end — held-slot change, off-hand swap, drop, death, world change, quit, and an
 * inventory click on the off-hand slot (tier C) — and on disable for all online
 * players, so neither a stray component nor a stuck temp shield is ever left
 * behind (zero-touch).</p>
 *
 * <h2>Knockback (era truth)</h2>
 * <p>A blocked hit still knocks the victim FULL — only the DAMAGE is reduced.
 * Mental owns knockback; this module never cancels the event or touches velocity.</p>
 *
 * <h2>Honest limit</h2>
 * <p>This cannot be byte-identical to the 1.7 CLIENT (the lowered-sword pose is a
 * client asset). The component tiers deliver server BEHAVIOURAL parity plus a
 * version-appropriate block pose; Tier C delivers the same behaviour but shows
 * the SHIELD pose (the cost of emulating the block without a component model).</p>
 *
 * <h2>Threading (Folia)</h2>
 * <p>Every handler runs on the relevant entity's region thread (interact / item /
 * death / quit on the player's; the damage event on the victim's), so the item
 * reads/writes and the damage adjustment are all safe inline. The tier-C off-hand
 * mutations and release poll go through
 * {@link me.vexmc.mental.common.scheduling.Scheduling#runOn} /
 * {@link me.vexmc.mental.common.scheduling.Scheduling#repeatOn} — never the Bukkit
 * global scheduler (which OCM's legacy path uses and which is not Folia-safe).</p>
 */
public final class SwordBlockingModule extends CombatModule implements Listener {

    private final SwordBlockComponents components;
    private final OffhandShieldBlocking offhandShield;

    public SwordBlockingModule(@NotNull MentalServices services) {
        super(services,
                "sword-blocking",
                "Sword Blocking",
                "1.7-style right-click sword blocking: modern data components on 1.21+, "
                        + "off-hand-shield fallback on 1.17.1-1.20.6; (damage-1)*0.5 reduction, "
                        + "full knockback.",
                DebugCategory.CONFIG);
        this.components = new SwordBlockComponents();
        this.offhandShield = new OffhandShieldBlocking(services, debug);
    }

    @Override
    public boolean configEnabled() {
        // Enable on EVERY tier: the component path drives 1.21+, the off-hand-shield
        // path drives 1.17.1-1.20.6. The handlers dispatch internally by tier.
        return services.config().swordBlocking().enabled();
    }

    /** Whether this server runs the off-hand-shield fallback (no component model). */
    private boolean offhandTier() {
        return !components.supported();
    }

    @Override
    protected void onEnable() {
        listen(this);
        debug.log(() -> "sword-blocking active: "
                + (offhandTier() ? "offhand-shield (tier C)" : components.describe()));
    }

    @Override
    protected void onDisable() {
        // Zero-touch: strip any in-flight block component from every online player's
        // hands (component tiers) and restore every injected off-hand shield (tier C),
        // so neither a tainted sword nor a stuck temp shield is left behind.
        for (Player player : Bukkit.getOnlinePlayers()) {
            stripHands(player);
        }
        offhandShield.disableAll();
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

        if (offhandTier()) {
            // Tier C: inject the off-hand shield (the only path that touches the off-hand).
            offhandShield.onRightClick(player);
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
     * a melee hit. The block-state read is tier-dependent and the tiers are
     * mutually exclusive, so the reduction can never double-count:
     *
     * <ul>
     *   <li><b>Tier A</b> is skipped entirely — its {@code BLOCKS_ATTACKS} component
     *       already reduces the damage natively.</li>
     *   <li><b>Tier B</b> reduces when the victim is actively using a sword
     *       (the {@code CONSUMABLE} block animation, via
     *       {@link SwordBlockComponents#isBlockingSword(Player)}).</li>
     *   <li><b>Tier C</b> reduces when the victim is blocking with Mental's
     *       PDC-marked off-hand shield
     *       (via {@link OffhandShieldBlocking#isBlockingWithTempShield(Player)}).</li>
     * </ul>
     *
     * <p>Only DAMAGE is adjusted; the event is never cancelled and knockback is
     * left FULL (era truth — Mental owns knockback). The event fires on the
     * victim's region thread, so reading the victim's state and writing the damage
     * are safe inline.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageByEntityEvent event) {
        if (components.nativeReduction()) {
            return; // Tier A: native BLOCKS_ATTACKS reduction — never double-reduce.
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        boolean blocking = offhandTier()
                ? offhandShield.isBlockingWithTempShield(victim) // Tier C: off-hand shield.
                : components.isBlockingSword(victim);            // Tier B: consumable block.
        if (!blocking) {
            return;
        }

        double incoming = event.getDamage();
        double reduction = SwordBlockReduction.blockedDamage(incoming);
        if (reduction <= 0.0) {
            return;
        }
        // Reduce DAMAGE only; do NOT cancel — a blocked hit still knocks full.
        event.setDamage(Math.max(0.0, incoming - reduction));
        String tier = offhandTier() ? "Tier C" : "Tier B";
        debug.log(() -> "sword-blocking (" + tier + ") reduced " + incoming + " by " + reduction
                + " for " + victim.getName());
    }

    /* ------------------------------------------------------------------ */
    /*  Strip lifecycle                                                    */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldChange(@NotNull PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (offhandTier()) {
            // Tier C: changing the hotbar slot ends the block — restore the off-hand.
            offhandShield.restore(player);
            return;
        }
        // The previously-held slot may carry the component — strip it.
        ItemStack prev = player.getInventory().getItem(event.getPreviousSlot());
        if (components.strip(prev)) {
            player.getInventory().setItem(event.getPreviousSlot(), prev);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(@NotNull PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (offhandTier()) {
            // Tier C: a swap would carry our temp shield into the MAIN hand — and a
            // deferred restore would then miss it (the off-hand no longer holds the
            // shield). Cancel the swap (mirroring OCM) and restore inline so the
            // shield never escapes the off-hand. The event is on the player's region
            // thread, so the synchronous off-hand write is safe.
            if (offhandShield.isBlockingWithTempShield(player)) {
                event.setCancelled(true);
                offhandShield.restore(player);
            }
            return;
        }
        // The swap moves the sword between hands; the component must not survive it.
        // Re-read after the swap on the player's region thread so we strip whatever
        // actually landed in each hand (Folia-correct, never the Bukkit scheduler).
        services.scheduling().runOn(player, () -> stripHands(player), () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        if (offhandTier()) {
            // Tier C: a shield drop may be ours leaving the off-hand. Do NOT cancel
            // (it may be an unrelated shield) — just end the block state so the
            // stored item is restored and the poll stops.
            if (event.getItemDrop().getItemStack().getType() == Material.SHIELD) {
                offhandShield.restore(event.getPlayer());
            }
            return;
        }
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (components.strip(dropped)) {
            event.getItemDrop().setItemStack(dropped);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!offhandTier()) {
            return; // Component tiers never put an item in the off-hand to click on.
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!offhandShield.isBlockingWithTempShield(player)) {
            return;
        }
        // A SWAP_OFFHAND keypress or a direct click on the off-hand slot (40) would
        // move our temp shield; cancel it and restore so the shield never escapes.
        if (isSwapOffhandClick(event) || isOffhandSlotClick(event)) {
            event.setCancelled(true);
            offhandShield.restore(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        if (offhandTier()) {
            // Tier C: rewrite the drop list (keyed by the PDC marker) so the temp
            // shield is replaced by the stored item / removed, and handle the
            // keep-inventory branch — never leave a temp shield behind.
            offhandShield.onDeath(event.getEntity(), event.getKeepInventory(), event.getDrops());
            return;
        }
        // Strip the component from every drop AND from the player's hands (keep-inventory
        // worlds restore the held item directly), so a respawned/looted sword is clean.
        for (ItemStack drop : event.getDrops()) {
            components.strip(drop);
        }
        stripHands(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        if (offhandTier()) {
            offhandShield.restore(event.getPlayer());
            return;
        }
        stripHands(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        if (offhandTier()) {
            // Quit: the off-hand restore must land before the inventory is saved, so
            // do it inline on the player's region thread (still valid in the quit
            // event) rather than deferring — a deferred write would miss the save.
            offhandShield.restore(event.getPlayer());
            return;
        }
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

    /** Whether a click is the SWAP_OFFHAND keypress (the 'F' hotkey, would move our shield). */
    private static boolean isSwapOffhandClick(@NotNull InventoryClickEvent event) {
        return event.getClick() == ClickType.SWAP_OFFHAND;
    }

    /** Whether a click targets the player's off-hand slot (40) directly. */
    private static boolean isOffhandSlotClick(@NotNull InventoryClickEvent event) {
        return event.getClickedInventory() != null
                && event.getClickedInventory().getType() == InventoryType.PLAYER
                && event.getSlot() == 40;
    }
}

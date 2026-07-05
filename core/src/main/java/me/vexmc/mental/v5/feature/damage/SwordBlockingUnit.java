package me.vexmc.mental.v5.feature.damage;

import java.util.UUID;
import java.util.function.Supplier;
import me.vexmc.mental.kernel.math.SwordBlockReduction;
import me.vexmc.mental.kernel.wire.SprintWire;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.EphemeralDecoration;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.platform.SwordBlockAdapter;
import me.vexmc.mental.v5.rim.ConnectionDomains;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 1.7-style right-click sword blocking (the retired {@code module.block.SwordBlockingModule}
 * on the v5 seams). Right-clicking a main-hand sword raises the tier-appropriate
 * block decoration (component pose on 1.21+, off-hand shield on ≤1.20.6, via
 * {@link EphemeralDecoration}); a blocked melee hit is reduced by the 1.8
 * {@code (damage-1)*0.5} (kernel {@link SwordBlockReduction}) at
 * {@link EventPriority#HIGH}, while knockback is left FULL — the event is never
 * cancelled and velocity is never touched, so a blocked hit knocks full (era
 * truth; Mental owns knockback). The native BLOCKS_ATTACKS tier reduces the hit
 * itself, so software reduction is skipped there (never double-reduce). On the
 * off-hand tier a raised temp shield vanilla-FULL-blocks (it is a real
 * {@code SHIELD}); that hit's negative BLOCKING modifier is rewritten to the era
 * value so the half damage lands instead of vanilla's zero (audit C1) — the
 * matching knock exemption lives in the {@code KnockbackUnit}.
 *
 * <p>The one server-side reconstruction the era needs (era-accuracy skill): the
 * block-hit sprint reset. Starting a block dropped the attacker's sprint in
 * 1.7/1.8 and the re-engage re-earned the sprint knockback bonus; modern clients
 * keep the sprint flag through an item-use block, so that STOP/START never
 * crosses the wire. {@link #resetSprintForBlock} re-arms the wire freshness on
 * the right-click, gated on the RAW client sprint flag (the {@link SprintWire}'s
 * view — never the server flag), so a stationary defensive block gains no phantom
 * bonus, and never touching sprint particles (client-authoritative).</p>
 */
public final class SwordBlockingUnit implements FeatureUnit, Listener {

    private final ConnectionDomains domains;
    private final EphemeralDecoration decoration;
    private final Supplier<Snapshot> snapshot;

    public SwordBlockingUnit(
            ConnectionDomains domains, EphemeralDecoration decoration, Supplier<Snapshot> snapshot) {
        this.domains = domains;
        this.decoration = decoration;
        this.snapshot = snapshot;
    }

    @Override
    public Feature descriptor() {
        return Feature.SWORD_BLOCKING;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
        // Eager component application on enable (a plain sword must already carry
        // the block component or the client swallows the first air right-click),
        // and guaranteed teardown of every decoration on disable (B12).
        scope.task(() -> {
            decoration.enableAll();
            return decoration::disableAll;
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Trigger                                                            */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // dedupe the per-hand double-fire: act only on the main-hand fire
        }
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!SwordBlockAdapter.isSword(mainHand.getType())) {
            return;
        }
        resetSprintForBlock(player);
        decoration.begin(player);
    }

    /* ------------------------------------------------------------------ */
    /*  Reduction (software tiers only — native BLOCKS_ATTACKS reduces itself) */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageByEntityEvent event) {
        if (decoration.nativeReduction()) {
            return; // native reduction — never double-reduce
        }
        if (!(event.getEntity() instanceof Player victim) || !decoration.blocking(victim)) {
            return;
        }
        applyBlockedDamage(event, armourStrengthActive());
    }

    /**
     * The blocked-hit damage shape, extracted pure-over-the-event for the unit pin.
     *
     * <p>Two granular concerns compose here (interaction audit C1 + the
     * block×armour drift):</p>
     * <ul>
     *   <li><b>Vanilla-full-blocked hits</b> (the off-hand tier's injected shield is
     *       a REAL vanilla shield, so a raised frontal hit arrives with a negative
     *       BLOCKING modifier negating the whole hit): the total setter cannot
     *       un-zero them — Bukkit's {@code setDamage(double)} re-applies the stored
     *       BLOCKING function, which negates whatever base it is given — so the era
     *       value is written straight into the BLOCKING modifier:
     *       {@code final = incoming − (incoming−1)×0.5}. When the era reduction is
     *       0 (incoming ≤ 1.0) the write clears the full negate so the era-full
     *       damage still lands. Never cancelled — the era knock ships through the
     *       {@code KnockbackUnit}'s blocked-knock redelivery.</li>
     *   <li><b>The era armour order</b>: 1.7/1.8 shaped the block BEFORE armour
     *       ({@code EntityHuman.damageEntity} halves, then
     *       {@code applyArmorCalculations}). With ARMOUR_STRENGTH active the block
     *       therefore writes the granular BLOCKING modifier and re-runs the shared
     *       era cascade over the blocked pre-armour damage — the total setter would
     *       instead shift the era-written defensive modifiers by MODERN-formula
     *       deltas (a hybrid neither era nor vanilla, drifting wherever the modern
     *       armour slope differs from the era 4%/point). With ARMOUR_STRENGTH off,
     *       the unblocked-signature path keeps the total setter so the block
     *       composes with vanilla's own model exactly as before; the
     *       vanilla-full-block path cannot use it (see above), so its armoured
     *       corner under a MODERN armour model is a documented gap — pair
     *       sword-blocking with old-armour-strength for era damage end to end.</li>
     * </ul>
     */
    @SuppressWarnings("deprecation") // the granular BLOCKING setter is the only un-zero / era-order lever
    static void applyBlockedDamage(@NotNull EntityDamageByEntityEvent event, boolean eraArmourCascade) {
        double incoming = event.getDamage();
        double reduction = SwordBlockReduction.blockedDamage(incoming);
        boolean vanillaBlocked = event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0.0;
        if (vanillaBlocked) {
            event.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, -reduction);
            if (eraArmourCascade) {
                ArmourStrengthUnit.applyEraCascade(event);
            }
            return;
        }
        if (reduction <= 0.0) {
            return;
        }
        if (eraArmourCascade && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
            // Era order: the block shapes the damage BEFORE armour sees it — the
            // BLOCKING modifier carries the reduction and the shared cascade
            // recomputes the era defences off the blocked pre-armour value.
            event.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, -reduction);
            ArmourStrengthUnit.applyEraCascade(event);
            return;
        }
        // Reduce DAMAGE only; never cancel — a blocked hit still knocks full.
        event.setDamage(Math.max(0.0, incoming - reduction));
    }

    /** Whether the era armour cascade owns the defensive modifiers this hit (live snapshot read). */
    private boolean armourStrengthActive() {
        Snapshot current = snapshot.get();
        return current != null && current.enabled(Feature.ARMOUR_STRENGTH);
    }

    /* ------------------------------------------------------------------ */
    /*  Exit triggers (guaranteed revert)                                  */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldChange(@NotNull PlayerItemHeldEvent event) {
        decoration.onHeldChange(event.getPlayer(), event.getPreviousSlot(), event.getNewSlot());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(@NotNull PlayerSwapHandItemsEvent event) {
        if (decoration.onSwapHands(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        decoration.onDrop(event.getPlayer(), dropped,
                () -> event.getItemDrop().setItemStack(dropped));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if ((isSwapOffhandClick(event) || isOffhandSlotClick(event)) && decoration.onOffhandClick(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        decoration.onDeath(event.getEntity(), event.getKeepInventory(), event.getDrops());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        decoration.onWorldChange(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        decoration.onQuit(event.getPlayer()); // inline revert — pre-save hook (B12)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        decoration.onJoin(event.getPlayer());
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Re-arms the sprint knockback bonus a 1.7/1.8 sword block earned, gated on the
     * RAW client sprint flag (the wire's view — the only signal that survives
     * Mental's post-hit {@code setSprinting(false)}); a packetless player has no
     * wire, so the reset never fires for it. Re-syncs the server flag so the
     * snapshot read sees it when {@code wtap-registration} is off; touches no
     * sprint particles (client-authoritative).
     */
    private void resetSprintForBlock(@NotNull Player player) {
        UUID id = player.getUniqueId();
        if (!domains.has(id)) {
            return; // no live connection wire (a synthetic/packetless player)
        }
        SprintWire wire = domains.domainFor(id).sprint();
        if (!wire.clientSprinting()) {
            // Gate on the RAW client sprint flag — the only signal that survives
            // Mental's own post-hit setSprinting(false)/onServerClear (F3, restoring
            // the documented pre-v5 contract). verdictAt().sprinting() is the very
            // flag those clears drop, so a real sprinter mid-combo would read false
            // and lose the block-hit re-arm; a defensive block still earns no bonus.
            return;
        }
        wire.onSprintStart(); // the block-release re-engage IS the w-tap signal — re-arm freshness
        if (!player.isSprinting()) {
            player.setSprinting(true);
        }
    }

    private static boolean isSwapOffhandClick(@NotNull InventoryClickEvent event) {
        return event.getClick() == ClickType.SWAP_OFFHAND;
    }

    private static boolean isOffhandSlotClick(@NotNull InventoryClickEvent event) {
        return event.getClickedInventory() != null
                && event.getClickedInventory().getType() == InventoryType.PLAYER
                && event.getSlot() == 40;
    }
}

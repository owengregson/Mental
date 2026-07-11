package me.vexmc.mental.v5.feature.damage;

import java.util.function.Supplier;
import me.vexmc.mental.kernel.math.SwordBlockReduction;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.EphemeralDecoration;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.knockback.BlockResetTap;
import me.vexmc.mental.v5.platform.SwordBlockAdapter;
import me.vexmc.mental.v5.rim.ConnectionDomains;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
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
import org.bukkit.event.player.PlayerInteractEntityEvent;
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
 * <p>The block-hit sprint reset (the one server-side reconstruction era-accuracy
 * mandates) is NOT here since 2.6.0: it is knockback semantics and lives
 * always-on in the {@code BlockResetTap} (knockback family) with the release
 * lane in the rim's RELEASE_USE_ITEM tap — a default config's shield block
 * re-arms without this feature. This unit only CONTRIBUTES its decorated-sword
 * test to that door's item gate while enabled ({@link #assemble}), so the
 * restored 1.7 sword block keeps re-arming exactly as before, and stops
 * contributing on close (the door's shield base stays).</p>
 */
public final class SwordBlockingUnit implements FeatureUnit, Listener {

    private final ConnectionDomains domains;
    private final EphemeralDecoration decoration;
    private final Supplier<Snapshot> snapshot;
    private final BlockResetTap blockDoor;

    public SwordBlockingUnit(
            ConnectionDomains domains, EphemeralDecoration decoration, Supplier<Snapshot> snapshot,
            BlockResetTap blockDoor) {
        this.domains = domains;
        this.decoration = decoration;
        this.snapshot = snapshot;
        this.blockDoor = blockDoor;
    }

    @Override
    public Feature descriptor() {
        return Feature.SWORD_BLOCKING;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
        // Contribute the decorated-sword test to the always-on block door while
        // this feature is enabled: the door's base gate (shields) knows nothing
        // about restored 1.7 sword blocks. Retracted on close — the door itself
        // stays registered for the plugin's lifetime.
        scope.task(() -> {
            blockDoor.featureGate(item -> SwordBlockAdapter.isSword(item.getType()));
            return () -> blockDoor.featureGate(null);
        });
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (!engagesBlock(event.getAction(), event.getHand(), event.useItemInHand())) {
            return;
        }
        beginBlock(event.getPlayer());
    }

    /**
     * A victim-aimed right-click fires {@link PlayerInteractEntityEvent}, NOT
     * {@link PlayerInteractEvent} — so without this handler the blockhit re-arm never
     * fired while the crosshair sat on the target (real combat's norm). Same guards
     * as the interact fire: the main-hand event only (the off-hand is a separate
     * fire), a sword in hand, and the feature gate (this listener is registered only
     * while SWORD_BLOCKING is enabled).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        if (!engagesBlockEntity(event.getHand())) {
            return;
        }
        beginBlock(event.getPlayer());
    }

    /**
     * The shared sword-in-hand gate + decoration engage for both interact fires.
     * The sprint re-arm is NOT here (2.6.0): the always-on {@code BlockResetTap}
     * hears the same interacts and re-arms through the door's item gate, which
     * this feature's decorated-sword contribution widens while enabled.
     */
    private void beginBlock(@NotNull Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!SwordBlockAdapter.isSword(mainHand.getType())) {
            return;
        }
        decoration.begin(player);
    }

    /**
     * Whether a {@link PlayerInteractEvent} should engage a sword block. A RIGHT_CLICK
     * air/block on the MAIN hand (dedupe the per-hand double-fire) that the item-use
     * result did not veto. The listener is {@code ignoreCancelled=false} on purpose: a
     * bare-hand/air right-click is BORN cancelled on Paper with
     * {@code useItemInHand=ALLOW}, so the old {@code ignoreCancelled=true} silently
     * dropped EVERY air right-click and the blockhit re-arm never fired (the second
     * half of the modern-client sprint latch — the SWORD_BLOCKING re-arm was the only
     * reconstruction path and it could not reach a born-cancelled event). Filtering on
     * {@code useItemInHand() != DENY} reaches the born-cancelled air click while still
     * yielding to a protection plugin's explicit item-use DENY.
     */
    static boolean engagesBlock(Action action, EquipmentSlot hand, Event.Result useItemInHand) {
        return (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)
                && hand == EquipmentSlot.HAND
                && useItemInHand != Event.Result.DENY;
    }

    /** The main-hand dedupe for the entity-interact fire (the off-hand event is a separate fire). */
    static boolean engagesBlockEntity(EquipmentSlot hand) {
        return hand == EquipmentSlot.HAND;
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
        // Switching the held slot ends any sword block, but the RELEASE_USE_ITEM packet
        // is not guaranteed on a slot change — clear the reset-model blocking flag here
        // too so a stuck flag can't keep the dynamic chase deferred. Non-creating peek;
        // onBlockRelease is idempotent (a no-op when not blocking).
        ConnectionDomains.Domain heldDomain = domains.peek(event.getPlayer().getUniqueId());
        if (heldDomain != null) {
            heldDomain.resetModel().onBlockRelease();
        }
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

    private static boolean isSwapOffhandClick(@NotNull InventoryClickEvent event) {
        return event.getClick() == ClickType.SWAP_OFFHAND;
    }

    private static boolean isOffhandSlotClick(@NotNull InventoryClickEvent event) {
        return event.getClickedInventory() != null
                && event.getClickedInventory().getType() == InventoryType.PLAYER
                && event.getSlot() == 40;
    }
}

package me.vexmc.mental.v5.feature.knockback;

import java.util.function.Predicate;
import me.vexmc.mental.v5.rim.ConnectionDomains;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * The always-on block-hit sprint-reset door (2.6.0 — moved out of the
 * default-OFF SWORD_BLOCKING feature, where its absence on default configs was
 * half the owner's "resets never re-arm" report). The block-hit reset is
 * KNOCKBACK semantics, not a damage rule: it is the one server-side
 * reconstruction era-accuracy mandates (starting a block dropped the era
 * client's sprint and the re-engage re-earned the sprint bonus; modern clients
 * keep the flag through item use, so that STOP/START never crosses the wire),
 * and it must exist wherever Mental owns knockback — which is always.
 *
 * <p>Registered for the plugin's lifetime next to the delivery routers. The
 * engage gesture is the born-cancelled {@code RIGHT_CLICK_AIR} interact (the
 * listener is {@code ignoreCancelled=false}, self-filtering on
 * {@code useItemInHand() != DENY} — a bare right-click is BORN cancelled on
 * Paper, and the old {@code ignoreCancelled=true} silently dropped every air
 * click) or the victim-aimed {@link PlayerInteractEntityEvent} (real combat's
 * norm). The USED hand's item must be able to BLOCK: a {@code SHIELD} (the
 * modern vanilla block, all supported versions — the only blocking gesture a
 * default config has), or whatever the {@link #featureGate} contributes
 * (SWORD_BLOCKING ORs in its decorated-sword test while enabled, so the
 * restored 1.7 sword block keeps re-arming exactly as before). Non-blockable
 * item uses never reach the ledger.</p>
 *
 * <p>Always-on justification (zero-touch): the re-arm changes nothing unless a
 * sprint-bonus hit already consumed an engagement — {@code onBlockSprintReset}
 * on an armed ledger is the state it already holds — and the entry gate
 * ({@code blockReArmEligible}: the raw client sprint flag, key-intent
 * corroborated) refuses stationary defensive blocks, so no phantom bonus
 * exists to grant. Packetless players are peeked out (never materialise a
 * domain by a read — the 2.4.4 trap). The {@code setSprinting(true)} re-sync
 * keeps the server flag honest for view-fallback reads, exactly as the
 * SWORD_BLOCKING-scoped door did since 2.5.x.</p>
 *
 * <p>Threading: interact events fire on the attacker's region thread — a
 * sanctioned cross-thread ledger writer (the CAS keeps it atomic; see the
 * {@link ConnectionDomains} licensing note). The release half of the cycle
 * lives in the rim ({@code PacketTap}'s RELEASE_USE_ITEM lane, netty thread) —
 * pure trail evidence, since the one-hit grant is spent by the hit's consume,
 * not the release.</p>
 */
public final class BlockResetTap implements Listener {

    private static final Predicate<ItemStack> NO_FEATURE_GATE = item -> false;

    private final ConnectionDomains domains;

    /**
     * The feature-contributed widening of the blockable-item test. Volatile: the
     * reconciler writes it on SWORD_BLOCKING enable/disable (global thread) and
     * interact handlers read it on region threads — a single reference, so
     * volatility is a coherent atomic read. Defaults to contributing nothing;
     * the shield test below is the always-on base.
     */
    private volatile Predicate<ItemStack> featureGate = NO_FEATURE_GATE;

    public BlockResetTap(ConnectionDomains domains) {
        this.domains = domains;
    }

    /** SWORD_BLOCKING's assemble contributes its decorated-sword test; close restores the base. */
    public void featureGate(Predicate<ItemStack> gate) {
        this.featureGate = gate == null ? NO_FEATURE_GATE : gate;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!engages(event.getAction(), event.useItemInHand())) {
            return;
        }
        reArm(event.getPlayer(), event.getHand());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        reArm(event.getPlayer(), event.getHand());
    }

    /**
     * A right-click (air or block) the item-use result did not veto. Deliberately
     * hand-agnostic — unlike the sword decoration, the door must hear an OFF-hand
     * shield raise (the default-config blocking gesture); {@link #reArm} tests the
     * used hand's own item, so the per-hand double-fire cannot double-grant a
     * different item's gesture. Pure for the unit pin.
     */
    static boolean engages(Action action, Event.Result useItemInHand) {
        return (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)
                && useItemInHand != Event.Result.DENY;
    }

    /** Whether {@code item} can block on this server: a shield, or a feature-decorated item. */
    private boolean canBlock(ItemStack item) {
        return item != null
                && (item.getType() == Material.SHIELD || featureGate.test(item));
    }

    /**
     * The relocated {@code resetSprintForBlock} (verbatim semantics): gate on the
     * used hand's blockable item, the live connection domain (non-creating), and
     * {@code blockReArmEligible} — the RAW client sprint flag (the only signal
     * that survives the ledger's own post-hit clear), key-intent corroborated for
     * the one blocked-tick STOP a key-holder ever crosses. A stationary defensive
     * block earns no bonus. Re-syncs the server flag so view-fallback reads see
     * it; touches no sprint particles (client-authoritative).
     */
    private void reArm(Player player, EquipmentSlot hand) {
        ItemStack used = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!canBlock(used)) {
            return;
        }
        if (!domains.has(player.getUniqueId())) {
            return; // no live connection ledger (a synthetic/packetless player)
        }
        ConnectionDomains.Domain domain = domains.domainFor(player.getUniqueId());
        if (!domain.sprint().blockReArmEligible()) {
            return;
        }
        domain.sprint().onBlockSprintReset();
        domain.resetModel().onBlockRaise(); // a blockhit re-engage — a dynamic-chase reset point
        if (!player.isSprinting()) {
            player.setSprinting(true);
        }
    }
}

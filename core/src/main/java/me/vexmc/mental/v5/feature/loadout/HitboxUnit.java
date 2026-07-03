package me.vexmc.mental.v5.feature.loadout;

import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.platform.AttackRangeAdapter;
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
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Restores the 1.7/1.8-era melee reach (3.0) and hitbox margin as far as the
 * modern server allows — the retired {@code module.hitbox.HitboxModule} on the v5
 * seam. Both levers are resolved by capability (attribute presence, boot-probed
 * component), never a version literal.
 *
 * <h2>The hard limit (why this is a ceiling, not parity)</h2>
 * <p><b>The true 1.7 client-side target-selection geometry is IRRECOVERABLE.</b>
 * The CLIENT picks the melee target and sends a fixed entity id in
 * {@code ServerboundInteractPacket}; the server resolves it verbatim with NO
 * server-side raytrace on the attack path. The server can only widen or narrow the
 * validation WINDOW and the targeting MARGIN — it cannot reproduce the wider 1.7
 * client targeting. This unit therefore promises era reach DISTANCE plus a small
 * era hitbox margin (1.21.5+), never client parity.</p>
 *
 * <h2>One reach truth</h2>
 * <p>Every reach value here comes from the kernel {@code EraReach} constants (via
 * {@link EraReachAttribute} and {@link AttackRangeAdapter}) — the same single
 * source the fast path's optional rewound-reach validation defaults to. This unit
 * tunes the vanilla server melee GATE (attribute) and the weapon targeting MARGIN
 * (component); it does not touch the fast path's own wide server-authoritative
 * sanity ring (a deliberately lenient clamp for a different purpose), so the two
 * never disagree on the era-reach source.</p>
 *
 * <h2>Per-version levers</h2>
 * <ul>
 *   <li><b>1.9.4–1.16.5</b> (legacy backport) and <b>1.17.1–1.20.4</b> — neither
 *       the interaction-range attribute nor the {@code ATTACK_RANGE} component
 *       exists, so both drivers report unsupported and the unit is a complete
 *       <b>no-op</b>. This is era-benign: on 1.9–1.16 the vanilla survival reach is
 *       already the era-adjacent ~3.0, so there is nothing to pull back down. The
 *       unit still ENABLES cleanly (it is pure Bukkit — see below), it simply
 *       writes nothing.</li>
 *   <li><b>1.20.5+</b> — the {@code ENTITY_INTERACTION_RANGE} attribute (era base
 *       3.0), pinned per player and restored on quit / disable.</li>
 *   <li><b>1.21.5+</b> — additionally the {@code ATTACK_RANGE} item component (era
 *       {@code max_reach=3.0}, {@code hitbox_margin=0.1}) on the held weapon,
 *       reconciled on join / hotbar / swap / world change and stripped on
 *       drop / death / world / quit / disable.</li>
 * </ul>
 *
 * <h2>No NMS surface (version-safe by construction)</h2>
 * <p>This unit touches <b>no NMS directly</b>: both levers are capability-gated
 * Bukkit surfaces — {@link EraReachAttribute} over the {@code Attributes} handle
 * (present-probed) and {@link AttackRangeAdapter} over its own boot-probed NMS
 * component. Every event it listens on ({@code PlayerJoin}, {@code PlayerItemHeld},
 * {@code PlayerSwapHandItems}, {@code PlayerDropItem}, {@code PlayerDeath},
 * {@code PlayerChangedWorld}, {@code PlayerQuit}) exists across the whole 1.9.4→
 * modern range, so the feature enables on every legacy revision with no per-revision
 * work; below 1.20.5 it is simply an era-benign no-op.</p>
 *
 * <p>Zero-touch: disabled (the default) or on an unsupported tier the unit writes
 * nothing; enabled it restores every captured base and strips every applied
 * component on the scope close (disable / reload-off).</p>
 */
public final class HitboxUnit implements FeatureUnit, Listener {

    private final Plugin plugin;
    private final Scheduling scheduling;
    private final AttackRangeAdapter component;
    private final EraReachAttribute attribute;

    public HitboxUnit(
            @NotNull Plugin plugin, @NotNull Scheduling scheduling, @NotNull AttackRangeAdapter component) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.component = component;
        this.attribute = new EraReachAttribute(plugin, scheduling);
    }

    @Override
    public Feature descriptor() {
        return Feature.HITBOX;
    }

    @Override
    public void assemble(Scope scope, Snapshot ignored) {
        scope.listen(this);
        // Apply both levers to online players on enable; restore every captured
        // attribute base and strip every applied component on the scope close
        // (B8/zero-touch). The teardown runs INLINE on the disabling thread.
        scope.task(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                attribute.apply(player);
                applyToHeld(player);
            }
            return () -> {
                attribute.disableAll();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    stripHands(player);
                }
            };
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                          */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        attribute.apply(player);
        applyToHeld(player);
    }

    /**
     * Hotbar slot change: strip the era component from the slot being left, then
     * re-apply to the newly-held weapon on the region thread (so the post-change
     * inventory is observed).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldChange(@NotNull PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!component.supported()) {
            return; // no component lever — the attribute is per-player, nothing to reconcile
        }
        ItemStack prev = player.getInventory().getItem(event.getPreviousSlot());
        if (component.strip(prev)) {
            player.getInventory().setItem(event.getPreviousSlot(), prev);
        }
        scheduling.runOn(player, () -> applyToHeld(player), () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(@NotNull PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!component.supported()) {
            return;
        }
        scheduling.runOn(player, () -> {
            PlayerInventory inventory = player.getInventory();
            ItemStack off = inventory.getItemInOffHand();
            if (off.getType() != Material.AIR && component.strip(off)) {
                inventory.setItemInOffHand(off);
            }
            applyToHeld(player);
        }, () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        if (!component.supported()) {
            return;
        }
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (component.strip(dropped)) {
            event.getItemDrop().setItemStack(dropped);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        if (!component.supported()) {
            return;
        }
        // Strip the component from every drop AND the hands (keep-inventory worlds
        // keep the held item), so a respawned / looted weapon is clean.
        for (ItemStack drop : event.getDrops()) {
            component.strip(drop);
        }
        stripHands(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        // The attribute is per-entity and survives a world change, but re-apply to
        // be safe and re-reconcile the held weapon's component.
        attribute.apply(player);
        applyToHeld(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Restore the attribute base and strip the component so a saved inventory
        // never persists a Mental-modified weapon.
        attribute.restore(player);
        stripHands(player);
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /** Applies the era component to the player's currently-held main-hand weapon. */
    private void applyToHeld(@NotNull Player player) {
        if (!component.supported()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack main = inventory.getItemInMainHand();
        if (main.getType() == Material.AIR) {
            return;
        }
        if (AttackRangeAdapter.isWeapon(main.getType())) {
            if (component.apply(main)) {
                inventory.setItemInMainHand(main);
            }
        } else if (component.strip(main)) {
            // A non-weapon in the main hand should never carry our component.
            inventory.setItemInMainHand(main);
        }
    }

    /** Strips the era component from both of a player's hands (write-back guarded). */
    private void stripHands(@NotNull Player player) {
        if (!component.supported()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack main = inventory.getItemInMainHand();
        if (component.strip(main)) {
            inventory.setItemInMainHand(main);
        }
        ItemStack off = inventory.getItemInOffHand();
        if (off.getType() != Material.AIR && component.strip(off)) {
            inventory.setItemInOffHand(off);
        }
    }
}

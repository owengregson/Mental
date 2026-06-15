package me.vexmc.mental.module.block;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugLog;
import me.vexmc.mental.common.scheduling.TaskHandle;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The legacy off-hand-shield fallback for 1.7-style sword blocking — Tier C, the
 * versions (1.17.1–1.20.6) with NO data-component model where the in-place sword
 * pose of {@link SwordBlockComponents} is impossible.
 *
 * <p>On those servers the era technique is emulated the way OldCombatMechanics
 * does it: when a player right-clicks with a sword in the main hand we
 * temporarily inject a real {@link Material#SHIELD} into the OFF-HAND, store the
 * item that was there, and call {@code startUsingItem(OFF_HAND)} so the native
 * {@code isBlocking()} / {@code isHandRaised()} state lights up (a shield works
 * on every version). A blocked melee hit is reduced in software by
 * {@link SwordBlockReduction#blockedDamage(double)} — there is no native
 * {@code (dmg-1)*0.5} on these versions — and, era truth, knockback is left FULL.</p>
 *
 * <h2>Honest client-fidelity limit</h2>
 * <p>Because the technique injects a shield, the client shows the SHIELD pose,
 * not the lowered-sword pose (the 1.7 pose is a client asset that cannot be
 * reproduced server-side on these versions). It delivers the era BEHAVIOUR
 * (right-click block, {@code (dmg-1)*0.5}, full knockback); the pose is the cost
 * of doing it without a component model.</p>
 *
 * <h2>The stuck-shield guarantee (critical)</h2>
 * <p>A temp shield left in a player's inventory would corrupt it, so it is
 * removed on EVERY exit: the release poll (player stops blocking or the
 * restore-delay elapses), hotbar change, off-hand swap, inventory click on the
 * off-hand slot, a related shield drop, death (keep-inventory branch + the
 * drop-list rewrite, keyed by the PDC marker so only OUR shield is replaced),
 * world change, quit, and {@link #disableAll()} on module shutdown. The shield
 * carries a {@link NamespacedKey} PDC marker ({@code temporary_legacy_shield}
 * BYTE=1) so death/drop logic can tell Mental's shield from a real one.</p>
 *
 * <h2>Threading (Folia)</h2>
 * <p>OCM drives this with a global {@code BukkitScheduler} timer; that is not
 * Folia-safe. Here every off-hand read/write happens inside
 * {@code services.scheduling().runOn(player, …)} (the player's region thread)
 * and the release poll is a per-player {@code repeatOn(player, …)} — region
 * correct on Folia, main-thread on Paper, identical code. Per-player state lives
 * in a {@link ConcurrentHashMap} keyed by UUID (the stored off-hand item + the
 * poll handle); polls are cancelled and shields restored on disable.</p>
 */
final class OffhandShieldBlocking {

    /** OCM's default restore-delay: the temp shield is force-restored after this many ticks. */
    private static final long RESTORE_DELAY_TICKS = 40L;
    /** First blocking re-check after the shield raise (OCM: ~10 ticks). */
    private static final long INITIAL_CHECK_TICKS = 10L;
    /** Subsequent blocking re-check cadence (OCM: every ~2 ticks). */
    private static final long CHECK_PERIOD_TICKS = 2L;

    private final MentalServices services;
    private final DebugLog.Scoped debug;
    private final NamespacedKey markerKey;

    /** Per-player block state: the stored off-hand item and the running release poll. */
    private final ConcurrentHashMap<UUID, BlockState> states = new ConcurrentHashMap<>();

    OffhandShieldBlocking(@NotNull MentalServices services, @NotNull DebugLog.Scoped debug) {
        this.services = services;
        this.debug = debug;
        this.markerKey = new NamespacedKey(services.plugin(), "temporary_legacy_shield");
    }

    /* ------------------------------------------------------------------ */
    /*  Trigger                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Right-click with a sword in the main hand → inject the marked shield into
     * the off-hand and start using it. The off-hand mutation runs on the player's
     * region thread; the release poll is started there too so it always observes a
     * shield already in place. A no-op if the player is already blocking with our
     * shield (the poll keeps the restore deadline fresh instead).
     */
    void onRightClick(@NotNull Player player) {
        UUID id = player.getUniqueId();
        if (states.containsKey(id)) {
            // Already blocking with our shield — extend, don't re-inject.
            extendRestoreDeadline(player);
            return;
        }
        services.scheduling().runOn(player, () -> beginBlock(player), () -> {});
    }

    private void beginBlock(@NotNull Player player) {
        UUID id = player.getUniqueId();
        PlayerInventory inventory = player.getInventory();
        ItemStack mainHand = inventory.getItemInMainHand();
        if (!SwordBlockComponents.isSword(mainHand.getType())) {
            return;
        }
        // Never stack a shield on a shield: if the off-hand already holds one
        // (a real shield, or a leftover ours) leave the player blocking with it.
        if (inventory.getItemInOffHand().getType() == Material.SHIELD) {
            return;
        }
        if (states.containsKey(id)) {
            return; // Raced another trigger onto the region thread.
        }

        ItemStack stored = inventory.getItemInOffHand();
        inventory.setItemInOffHand(createTemporaryShield());
        // Force an inventory update so the client never shows a ghost off-hand item.
        player.updateInventory();
        startUsingOffHand(player);

        TaskHandle poll = startReleasePoll(player);
        states.put(id, new BlockState(stored, poll));
        debug.log(() -> "offhand-shield block start for " + player.getName()
                + " (stored " + stored.getType() + ")");
    }

    /* ------------------------------------------------------------------ */
    /*  Release poll                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * The per-player release poll. Runs on the player's region thread every
     * {@link #CHECK_PERIOD_TICKS} ticks starting at {@link #INITIAL_CHECK_TICKS}
     * (the grace window while the shield first raises); restores + cancels itself
     * once the player stops blocking or {@link #RESTORE_DELAY_TICKS} elapse. This
     * replaces OCM's single shared global timer with a Folia-correct per-player
     * task — the off-hand it touches is always the player's own region's.
     */
    private TaskHandle startReleasePoll(@NotNull Player player) {
        UUID id = player.getUniqueId();
        long[] elapsed = {0L};
        return services.scheduling().repeatOn(
                player, INITIAL_CHECK_TICKS, CHECK_PERIOD_TICKS,
                () -> {
                    elapsed[0] += CHECK_PERIOD_TICKS;
                    // Released the block, or the force-restore delay elapsed → restore.
                    if (!isBlocking(player) || elapsed[0] >= RESTORE_DELAY_TICKS) {
                        restore(player);
                    }
                },
                () -> {
                    // The player left their region (quit / removed) before release;
                    // drop the tracked state so nothing leaks. The shield cannot be
                    // written to an absent player here — quit/disable handle that.
                    states.remove(id);
                });
    }

    /** Pushes the force-restore deadline back by resetting the running poll. */
    private void extendRestoreDeadline(@NotNull Player player) {
        // Restart the poll so the elapsed counter resets to a fresh window. The
        // off-hand already holds our shield, so no inventory mutation is needed.
        BlockState state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.poll().cancel();
        TaskHandle poll = startReleasePoll(player);
        states.computeIfPresent(player.getUniqueId(),
                (uuid, existing) -> new BlockState(existing.stored(), poll));
    }

    /** Whether the player is actively raising the shield (native, all-version). */
    private boolean isBlocking(@NotNull Player player) {
        return player.isBlocking() || player.isHandRaised();
    }

    /* ------------------------------------------------------------------ */
    /*  Restore                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Restores the player's original off-hand item and cancels the release poll.
     * Idempotent. The off-hand write is done on the CURRENT thread, so every caller
     * MUST already be on the player's region thread — true for all of them: the
     * release poll (via {@code repeatOn(player, …)}) and every lifecycle event
     * (hotbar / swap / drop / inventory-click / world-change / quit) fire on the
     * player's region thread.
     *
     * <p>This is deliberately INLINE rather than deferred through {@code runOn}: a
     * deferred write lands a tick later, by which point a swap/drop could already
     * have carried the temp shield out of the off-hand — the deferred write would
     * then miss it and leave the shield stuck. Writing on the same tick the block
     * ends is the only way to keep the never-stuck guarantee. Because the caller is
     * already on the owning region thread, the synchronous write is region-correct
     * (no cross-region entity access).</p>
     */
    void restore(@NotNull Player player) {
        BlockState state = states.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.poll().cancel();
        PlayerInventory inventory = player.getInventory();
        // Only overwrite if our temp shield is still in the off-hand: a swap/drop may
        // already have moved it, and we must never clobber a real item.
        if (isTemporaryShield(inventory.getItemInOffHand())) {
            inventory.setItemInOffHand(state.stored());
            player.updateInventory();
        }
        debug.log(() -> "offhand-shield restore for " + player.getName());
    }

    /** Whether this player currently has Mental's temp shield injected. */
    boolean isBlockingWithTempShield(@NotNull Player player) {
        if (!states.containsKey(player.getUniqueId())) {
            return false;
        }
        return isTemporaryShield(player.getInventory().getItemInOffHand());
    }

    /* ------------------------------------------------------------------ */
    /*  Death-drop rewrite                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Death handler: replace the dropped temp shield with the stored off-hand item
     * (or remove it if the off-hand was empty), keyed by the PDC marker so only
     * OUR shield is ever rewritten. On keep-inventory worlds the held shield stays
     * in the off-hand, so restore it directly. Never leaves a temp shield behind.
     *
     * @param player        the dead player
     * @param keepInventory whether the death keeps inventory (drops are empty)
     * @param drops         the mutable death-drop list (rewritten in place)
     */
    void onDeath(@NotNull Player player, boolean keepInventory, @NotNull List<ItemStack> drops) {
        UUID id = player.getUniqueId();
        BlockState state = states.remove(id);
        if (state == null) {
            return;
        }
        state.poll().cancel();

        if (keepInventory) {
            // The off-hand item survives death — swap our shield back out directly.
            services.scheduling().runOn(player,
                    () -> {
                        PlayerInventory inventory = player.getInventory();
                        if (isTemporaryShield(inventory.getItemInOffHand())) {
                            inventory.setItemInOffHand(state.stored());
                        }
                    },
                    () -> {});
            return;
        }

        // Drops branch: find OUR shield in the drop list by the PDC marker and
        // replace it with the stored item (or remove it for an empty off-hand).
        for (int i = 0; i < drops.size(); i++) {
            if (isTemporaryShield(drops.get(i))) {
                if (state.stored().getType() == Material.AIR) {
                    drops.remove(i);
                } else {
                    drops.set(i, state.stored());
                }
                return;
            }
        }
        // The marked shield was not in the drops (e.g. it had already been
        // restored, or a synthetic death event) — if the stored item is real and
        // is not already present, add it so the player does not lose it.
        if (state.stored().getType() != Material.AIR) {
            drops.add(state.stored());
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Disable                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Zero-touch shutdown: cancel every poll and restore every injected shield
     * for all currently-blocking players, so the module never leaves a temp shield
     * behind when it is turned off.
     *
     * <p>The off-hand write is done INLINE (not deferred through {@code runOn}):
     * on disable the scheduler may already be stopping, so a deferred task could
     * never run and would leave the temp shield stuck. Disable runs on the main /
     * global thread, where this synchronous inventory access is safe.</p>
     */
    void disableAll() {
        // Snapshot the keys: this loop mutates the map.
        for (UUID id : List.copyOf(states.keySet())) {
            BlockState state = states.remove(id);
            if (state == null) {
                continue;
            }
            state.poll().cancel();
            Player player = services.plugin().getServer().getPlayer(id);
            if (player == null) {
                continue; // Offline: nothing to write; the state is already gone.
            }
            PlayerInventory inventory = player.getInventory();
            if (isTemporaryShield(inventory.getItemInOffHand())) {
                inventory.setItemInOffHand(state.stored());
                player.updateInventory();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Temp-shield identity (PDC marker)                                  */
    /* ------------------------------------------------------------------ */

    private @NotNull ItemStack createTemporaryShield() {
        ItemStack shield = new ItemStack(Material.SHIELD);
        ItemMeta meta = shield.getItemMeta();
        if (meta != null) {
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(markerKey, PersistentDataType.BYTE, (byte) 1);
            shield.setItemMeta(meta);
        }
        return shield;
    }

    /** Whether {@code item} is a shield carrying Mental's temp-shield PDC marker. */
    boolean isTemporaryShield(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.SHIELD) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    /**
     * Asks the server to start using the off-hand item so blocking becomes visible
     * immediately. {@code startUsingItem(EquipmentSlot)} is absent on the 1.17.1
     * compile floor, so it is resolved reflectively; a no-op when unresolved (the
     * client still raises the shield from the off-hand item itself).
     */
    private void startUsingOffHand(@NotNull Player player) {
        try {
            player.getClass()
                    .getMethod("startUsingItem", org.bukkit.inventory.EquipmentSlot.class)
                    .invoke(player, org.bukkit.inventory.EquipmentSlot.OFF_HAND);
        } catch (Throwable ignored) {
            // Best-effort: the off-hand shield alone still drives the block state.
        }
    }

    /** Per-player block state: the original off-hand item and the running release poll. */
    private record BlockState(@NotNull ItemStack stored, @NotNull TaskHandle poll) {}
}

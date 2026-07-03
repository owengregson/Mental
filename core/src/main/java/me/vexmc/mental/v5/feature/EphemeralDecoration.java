package me.vexmc.mental.v5.feature;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.platform.HandStates;
import me.vexmc.mental.platform.PersistentData;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import me.vexmc.mental.v5.platform.SwordBlockAdapter;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The ephemeral sword-block decoration service (B12; the union of the retired
 * {@code SwordBlockComponents} eager-apply and {@code OffhandShieldBlocking} on
 * the v5 seams). It applies a temporary, purely-cosmetic block decoration and
 * <strong>guarantees its revert on every exit path</strong> — hand-leave,
 * off-hand swap, drop, inventory click, death (+drops), world change, quit,
 * disable — so neither a stray component nor a stuck temp shield is ever left in
 * a player's inventory (zero-touch).
 *
 * <p>Two tiers, mutually exclusive per server (resolved by {@link SwordBlockAdapter}):</p>
 * <ul>
 *   <li><b>Component</b> (BLOCKS_ATTACKS / CONSUMABLE, 1.21+): the block component
 *       is eagerly applied to a held sword (so the first air right-click raises
 *       the block) and stripped on every exit.</li>
 *   <li><b>Off-hand shield</b> (≤1.20.6): a PDC-marked {@link Material#SHIELD} is
 *       injected into the off-hand on the right-click, tracked per player, and
 *       restored on every exit — never clobbering a real item (the marker
 *       distinguishes Mental's shield).</li>
 * </ul>
 *
 * <p>The "pre-save hook" is realized as the INLINE (non-deferred) revert on quit
 * and disable: the write lands on the current region tick, before Bukkit
 * serializes the inventory — a deferred write would land a tick late, after a
 * swap/drop could have carried the decoration out (the never-stuck guarantee).</p>
 */
public final class EphemeralDecoration {

    private static final long RESTORE_DELAY_TICKS = 40L;
    private static final long INITIAL_CHECK_TICKS = 10L;
    private static final long CHECK_PERIOD_TICKS = 2L;

    private final Plugin plugin;
    private final Scheduling scheduling;
    private final SwordBlockAdapter adapter;

    /**
     * The PDC marker distinguishing Mental's injected shield from a real one — {@code null} below Bukkit
     * 1.14 (no PersistentDataContainer, so {@link NamespacedKey} would also not construct). Without it the
     * temp shield is identified in-memory: {@link #offhandStates} tracks whom Mental injected into, and the
     * injection guard refuses to overwrite an existing shield, so any off-hand SHIELD held by a tracked
     * player is ours. Same lifecycle, no item NBT (the marker is ephemeral by definition anyway).
     */
    private final @Nullable NamespacedKey markerKey;

    /** Off-hand tier per-player state: the stored off-hand item and its release poll. */
    private final ConcurrentHashMap<UUID, BlockState> offhandStates = new ConcurrentHashMap<>();

    public EphemeralDecoration(
            @NotNull Plugin plugin, @NotNull Scheduling scheduling, @NotNull SwordBlockAdapter adapter) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.adapter = adapter;
        // NamespacedKey is a 1.12 API and PDC a 1.14 one; construct the marker only where PDC is present,
        // else the in-memory shield identity above carries the never-stuck guarantee (boot log in the plugin).
        this.markerKey = PersistentData.supported()
                ? new NamespacedKey(plugin, "temporary_legacy_shield")
                : null;
    }

    /** Whether this server runs the off-hand-shield fallback (no component model). */
    public boolean offhandTier() {
        return !adapter.supported();
    }

    /** Whether the active reduction is native (Tier A) — no software reduction must be applied. */
    public boolean nativeReduction() {
        return adapter.nativeReduction();
    }

    /* ------------------------------------------------------------------ */
    /*  Begin / block-state (the trigger + reduction gate)                 */
    /* ------------------------------------------------------------------ */

    /** Right-click with a main-hand sword → raise the tier-appropriate block decoration. */
    public void begin(@NotNull Player player) {
        if (offhandTier()) {
            beginOffhand(player);
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack mainHand = inventory.getItemInMainHand();
        if (adapter.apply(mainHand)) {
            inventory.setItemInMainHand(mainHand);
        }
        adapter.startUsing(player);
    }

    /** Whether {@code victim} is blocking (tier-appropriate) — the software-reduction gate. */
    public boolean blocking(@NotNull Player victim) {
        return offhandTier() ? isBlockingWithTempShield(victim) : adapter.isBlockingSword(victim);
    }

    /* ------------------------------------------------------------------ */
    /*  Enable / disable (eager apply + guaranteed teardown)               */
    /* ------------------------------------------------------------------ */

    /** Eager-applies the block component to every online player's held sword (component tier only). */
    public void enableAll() {
        if (offhandTier()) {
            return; // the off-hand shield is injected on the interact — nothing to pre-apply
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            scheduling.runOn(player, () -> applyMainHand(player), () -> {});
        }
    }

    /**
     * Zero-touch shutdown: strip any in-flight component from every online player's
     * hands (component tiers) and restore every injected off-hand shield (off-hand
     * tier) — INLINE, since the scheduler may already be stopping on disable.
     */
    public void disableAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            stripHands(player);
        }
        for (UUID id : List.copyOf(offhandStates.keySet())) {
            BlockState state = offhandStates.remove(id);
            if (state == null) {
                continue;
            }
            state.poll().cancel();
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                continue;
            }
            PlayerInventory inventory = player.getInventory();
            if (isTemporaryShield(inventory.getItemInOffHand())) {
                inventory.setItemInOffHand(state.stored());
                player.updateInventory();
            }
        }
    }

    /** Drop a player's tracked off-hand state without an inventory write (retire/forget). */
    public void forget(@NotNull UUID id) {
        BlockState state = offhandStates.remove(id);
        if (state != null) {
            state.poll().cancel();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Exit triggers                                                      */
    /* ------------------------------------------------------------------ */

    /** Hotbar slot change: component tier strips the old slot + eager-applies the new; off-hand restores. */
    public void onHeldChange(@NotNull Player player, int previousSlot, int newSlot) {
        if (offhandTier()) {
            restore(player);
            return;
        }
        ItemStack prev = player.getInventory().getItem(previousSlot);
        if (adapter.strip(prev)) {
            player.getInventory().setItem(previousSlot, prev);
        }
        ItemStack next = player.getInventory().getItem(newSlot);
        if (adapter.apply(next)) {
            player.getInventory().setItem(newSlot, next);
        }
    }

    /**
     * Off-hand swap. Off-hand tier: cancel and restore inline so the temp shield
     * never escapes to the main hand (returns true → cancel). Component tier:
     * reconcile hands on the region thread (returns false).
     */
    public boolean onSwapHands(@NotNull Player player) {
        if (offhandTier()) {
            if (isBlockingWithTempShield(player)) {
                restore(player);
                return true; // caller cancels the event
            }
            return false;
        }
        scheduling.runOn(player, () -> reconcileHands(player), () -> {});
        return false;
    }

    /** A drop: off-hand tier ends the block on any shield drop; component tier strips the dropped stack. */
    public void onDrop(@NotNull Player player, @NotNull ItemStack dropped, @NotNull Runnable writeBack) {
        if (offhandTier()) {
            if (dropped.getType() == Material.SHIELD) {
                restore(player);
            }
            return;
        }
        if (adapter.strip(dropped)) {
            writeBack.run();
        }
    }

    /** Off-hand-slot inventory click while blocking with the temp shield → true (caller cancels + restore). */
    public boolean onOffhandClick(@NotNull Player player) {
        if (!offhandTier() || !isBlockingWithTempShield(player)) {
            return false;
        }
        restore(player);
        return true;
    }

    /** Death: off-hand tier rewrites the drop list / keep-inventory; component tier strips drops + hands. */
    public void onDeath(@NotNull Player player, boolean keepInventory, @NotNull List<ItemStack> drops) {
        if (offhandTier()) {
            onDeathOffhand(player, keepInventory, drops);
            return;
        }
        for (ItemStack drop : drops) {
            adapter.strip(drop);
        }
        stripHands(player);
    }

    /** World change: strip hands (component) or restore the off-hand shield. */
    public void onWorldChange(@NotNull Player player) {
        if (offhandTier()) {
            restore(player);
        } else {
            stripHands(player);
        }
    }

    /** Quit: the revert must land before the inventory save, so it is INLINE (pre-save hook). */
    public void onQuit(@NotNull Player player) {
        if (offhandTier()) {
            restore(player);
        } else {
            stripHands(player);
        }
    }

    /** Join: pre-apply the block component to a held sword (component tier). */
    public void onJoin(@NotNull Player player) {
        if (!offhandTier()) {
            scheduling.runOn(player, () -> applyMainHand(player), () -> {});
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Component-tier helpers                                             */
    /* ------------------------------------------------------------------ */

    private void applyMainHand(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack main = inventory.getItemInMainHand();
        if (adapter.apply(main)) {
            inventory.setItemInMainHand(main);
        }
    }

    private void reconcileHands(@NotNull Player player) {
        applyMainHand(player);
        PlayerInventory inventory = player.getInventory();
        ItemStack off = inventory.getItemInOffHand();
        if (off.getType() != Material.AIR && adapter.strip(off)) {
            inventory.setItemInOffHand(off);
        }
    }

    private void stripHands(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack main = inventory.getItemInMainHand();
        if (adapter.strip(main)) {
            inventory.setItemInMainHand(main);
        }
        ItemStack off = inventory.getItemInOffHand();
        if (off.getType() != Material.AIR && adapter.strip(off)) {
            inventory.setItemInOffHand(off);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Off-hand-tier shield injection + release poll                      */
    /* ------------------------------------------------------------------ */

    private void beginOffhand(@NotNull Player player) {
        UUID id = player.getUniqueId();
        if (offhandStates.containsKey(id)) {
            extendRestoreDeadline(player);
            return;
        }
        scheduling.runOn(player, () -> injectShield(player), () -> {});
    }

    private void injectShield(@NotNull Player player) {
        UUID id = player.getUniqueId();
        PlayerInventory inventory = player.getInventory();
        if (!SwordBlockAdapter.isSword(inventory.getItemInMainHand().getType())) {
            return;
        }
        if (inventory.getItemInOffHand().getType() == Material.SHIELD) {
            return; // never stack a shield on a shield
        }
        if (offhandStates.containsKey(id)) {
            return; // raced another trigger onto the region thread
        }
        ItemStack stored = inventory.getItemInOffHand();
        inventory.setItemInOffHand(createTemporaryShield());
        player.updateInventory();
        startUsingOffHand(player);
        TaskHandle poll = startReleasePoll(player);
        offhandStates.put(id, new BlockState(stored, poll));
    }

    private TaskHandle startReleasePoll(@NotNull Player player) {
        UUID id = player.getUniqueId();
        long[] elapsed = {0L};
        return scheduling.repeatOn(
                player, INITIAL_CHECK_TICKS, CHECK_PERIOD_TICKS,
                () -> {
                    elapsed[0] += CHECK_PERIOD_TICKS;
                    if (!isHandRaised(player) || elapsed[0] >= RESTORE_DELAY_TICKS) {
                        restore(player);
                    }
                },
                () -> offhandStates.remove(id));
    }

    private void extendRestoreDeadline(@NotNull Player player) {
        BlockState state = offhandStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.poll().cancel();
        TaskHandle poll = startReleasePoll(player);
        offhandStates.computeIfPresent(player.getUniqueId(),
                (uuid, existing) -> new BlockState(existing.stored(), poll));
    }

    private boolean isHandRaised(@NotNull Player player) {
        // HandStates.isHandRaised, not player.isHandRaised(): the Bukkit accessor floors at 1.10.2 (absent
        // on 1.9.4), where a direct call throws when sword-blocking is enabled. isBlocking() is present on
        // 1.9.4, so the condition collapses to the shield-block state there (loses only the sub-block-delay
        // raise window). The resolver uses the native method verbatim on 1.10.2+.
        return player.isBlocking() || HandStates.isHandRaised(player);
    }

    /** Restores the original off-hand item and cancels the poll. INLINE — the caller is on the region thread. */
    private void restore(@NotNull Player player) {
        BlockState state = offhandStates.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.poll().cancel();
        PlayerInventory inventory = player.getInventory();
        if (isTemporaryShield(inventory.getItemInOffHand())) {
            inventory.setItemInOffHand(state.stored());
            player.updateInventory();
        }
    }

    /** Whether this player currently has Mental's temp shield injected. */
    public boolean isBlockingWithTempShield(@NotNull Player player) {
        return offhandStates.containsKey(player.getUniqueId())
                && isTemporaryShield(player.getInventory().getItemInOffHand());
    }

    private void onDeathOffhand(@NotNull Player player, boolean keepInventory, @NotNull List<ItemStack> drops) {
        BlockState state = offhandStates.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.poll().cancel();
        if (keepInventory) {
            scheduling.runOn(player,
                    () -> {
                        PlayerInventory inventory = player.getInventory();
                        if (isTemporaryShield(inventory.getItemInOffHand())) {
                            inventory.setItemInOffHand(state.stored());
                        }
                    },
                    () -> {});
            return;
        }
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
        if (state.stored().getType() != Material.AIR) {
            drops.add(state.stored());
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Temp-shield identity (PDC marker)                                  */
    /* ------------------------------------------------------------------ */

    private @NotNull ItemStack createTemporaryShield() {
        ItemStack shield = new ItemStack(Material.SHIELD);
        if (markerKey == null) {
            return shield; // no PDC (pre-1.14) — identity is carried in-memory (see markerKey doc)
        }
        ItemMeta meta = shield.getItemMeta();
        if (meta != null) {
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(markerKey, PersistentDataType.BYTE, (byte) 1);
            shield.setItemMeta(meta);
        }
        return shield;
    }

    private boolean isTemporaryShield(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.SHIELD) {
            return false;
        }
        if (markerKey == null) {
            // Pre-1.14 in-memory identity: a SHIELD in a tracked player's off-hand is Mental's, because
            // injectShield refuses to overwrite an existing shield. Callers pair this with an offhandStates
            // membership check (isBlockingWithTempShield); the death/disable paths only reach here for a
            // player we injected into, so treating a bare SHIELD as ours is correct there too.
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    private void startUsingOffHand(@NotNull Player player) {
        try {
            player.getClass()
                    .getMethod("startUsingItem", org.bukkit.inventory.EquipmentSlot.class)
                    .invoke(player, org.bukkit.inventory.EquipmentSlot.OFF_HAND);
        } catch (Throwable ignored) {
            // Best-effort — the off-hand shield alone still drives the block state.
        }
    }

    /** Per-player off-hand state: the original off-hand item and the running release poll. */
    private record BlockState(@NotNull ItemStack stored, @NotNull TaskHandle poll) {}
}

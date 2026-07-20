package me.vexmc.mental.v5.feature.sustain;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.HandStates;
import me.vexmc.mental.platform.NaturalRegen;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.sustain.Ct8cRegenDriver.Outcome;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Combat Test 8c food/regen ({@code ct8c-regen}, design spec §2.7) — the
 * 1.8-style {@code FoodData}. Mirrors {@code RegenUnit}'s structure with the CT8c
 * cadence ({@link Ct8cRegen}/{@link Ct8cRegenDriver}, kernel {@link
 * me.vexmc.mental.kernel.math.Ct8cRegenMath}):
 *
 * <ul>
 *   <li>the modern {@code SATIATED} natural-regen path is suppressed for players
 *       (both the fast and slow satiated heals), so only the era cadence heals;</li>
 *   <li>a per-player 1-tick task drives the {@link Ct8cRegenDriver}: on every
 *       40-tick boundary it heals 1 HP while {@code foodLevel > 6} and hurt, with
 *       a 50% chance to drain 1 hunger per heal (a seeded {@link DoubleSupplier}
 *       hook — {@code ThreadLocalRandom} live);</li>
 *   <li>an eat/drink in progress is interrupted when the player is hit by a
 *       player or a mob ({@link Ct8cRegen#interruptsConsume}, applied through the
 *       {@link ConsumeInterrupt} seam — 1.20.5+, loud degrade below).</li>
 * </ul>
 *
 * <p><b>Sprint gate.</b> CT8c requires {@code foodLevel > 6} to sprint, which is
 * exactly vanilla across the range — no enforcement is needed (a documented
 * vanilla-equal no-op).</p>
 *
 * <p><b>Starvation.</b> Left vanilla-owned. CT8c's 40-tick starvation cadence
 * diverges from vanilla's, but reimplementing it means suppressing vanilla's
 * difficulty-correct starvation damage and reproducing the per-difficulty health
 * floors — disproportionate for the fidelity gained. The regen cadence (the
 * primary, well-defined CT8c change) is what this unit owns; the divergence is a
 * documented limitation (spec §7 gap list territory).</p>
 *
 * <p>Zero-touch when disabled: the reconciler never assembles a disabled unit,
 * every per-player task is cancelled on quit and on scope close, and the {@code
 * NATURAL_REGENERATION} gamerule is still honoured (a server that turns natural
 * regen off keeps it off — the {@code RegenUnit} posture).</p>
 */
public final class Ct8cRegenUnit implements FeatureUnit, Listener {

    private final ConcurrentHashMap<UUID, TaskHandle> handles = new ConcurrentHashMap<>();
    private final DoubleSupplier drainRoll = () -> ThreadLocalRandom.current().nextDouble();

    private Scheduling scheduling;
    private ConsumeInterrupt interrupt;

    /**
     * The naturalRegeneration-gamerule read, resolved ONCE at assemble by the
     * platform presence probe and cached as a predicate so the per-tick hot path
     * never re-resolves the typed-vs-legacy decision (and never names the 1.13+
     * {@code org.bukkit.GameRule} type in this listener class — the 2.4.1 GAP-1
     * rule; the typed constant lives only behind {@link NaturalRegen}).
     */
    private Predicate<World> naturalRegen;

    @Override
    public Feature descriptor() {
        return Feature.CT8C_REGEN;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // The skeleton is registered no-arg (Task B owns registerUnits), so the
        // scheduling/interrupt dependencies are resolved from the plugin singleton
        // here at enable rather than injected through the constructor.
        MentalPluginV5 plugin = (MentalPluginV5) JavaPlugin.getProvidingPlugin(getClass());
        this.scheduling = plugin.scheduling();
        this.interrupt = ConsumeInterrupt.probe(plugin.environment(), plugin.getLogger()::warning);
        // Resolve the gamerule read once, here, and cache it as a predicate — the
        // per-tick heal loop calls the cached seam, never a per-tick re-probe. The
        // boot-selected path is logged honestly (B10): typed on 1.13+, the String
        // overload below, where a direct GameRule read would NoClassDefFoundError.
        this.naturalRegen = NaturalRegen::isEnabled;
        plugin.getLogger().info(() -> "ct8c-regen: naturalRegeneration gamerule read via " + NaturalRegen.describe());

        scope.listen(this);
        scope.task(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                startTask(player);
            }
            return this::cancelAll;
        });
    }

    /* ------------------------------ suppression --------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNaturalRegen(@NotNull EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player
                && event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            event.setCancelled(true); // the per-player driver owns the 1.8 cadence
        }
    }

    /* ------------------------------ interrupt ----------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatHit(@NotNull EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        boolean damagerLiving = event.getDamager() instanceof LivingEntity;
        if (Ct8cRegen.interruptsConsume(isConsuming(victim), damagerLiving)) {
            interrupt.interrupt(victim);
        }
    }

    /** {@code LivingEntity#getItemInUse()} — Paper 1.20.5+, resolved once; {@code null} where absent. */
    private static final Method ITEM_IN_USE = resolveItemInUse();

    private static Method resolveItemInUse() {
        try {
            return Class.forName("org.bukkit.entity.LivingEntity").getMethod("getItemInUse");
        } catch (ReflectiveOperationException absent) {
            return null; // pre-1.20.5 / non-Paper — the interrupt seam is a no-op there anyway
        }
    }

    /**
     * Whether the victim is actively EATING or DRINKING — <em>not</em> raising a
     * shield, drawing a bow, using a spyglass or charging a trident. 8c interrupts
     * only the consume animations ({@code LivingEntity.hurt}:
     * {@code getUseItem().getUseAnimation() ∈ {EAT, DRINK}}); the pre-2.10 gate
     * read the bare {@code isHandRaised()} and so wrongly dropped a raised shield
     * or a drawn bow on any hit. The item in use is read through Paper's
     * {@code getItemInUse()} (the same 1.20.5+ band the interrupt seam needs);
     * where absent it degrades to the coarse hand-raised read — moot, since the
     * interrupt itself is a no-op there.
     */
    private static boolean isConsuming(@NotNull Player victim) {
        if (ITEM_IN_USE == null) {
            return HandStates.isHandRaised(victim);
        }
        try {
            Object using = ITEM_IN_USE.invoke(victim);
            return using instanceof ItemStack stack && isConsumable(stack.getType());
        } catch (ReflectiveOperationException failed) {
            return HandStates.isHandRaised(victim);
        }
    }

    /**
     * Whether a material is eaten or drunk (the EAT/DRINK animations): any edible
     * item (foods, stews, honey), or the two drink-only items ({@code POTION},
     * {@code MILK_BUCKET}) that {@code Material#isEdible()} does not cover. Shields,
     * bows, spyglasses and tridents are none of these.
     */
    private static boolean isConsumable(@NotNull Material material) {
        return material.isEdible() || material == Material.POTION || material == Material.MILK_BUCKET;
    }

    /* ------------------------------ lifecycle ----------------------------- */

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        startTask(event.getPlayer());
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        cancelTask(event.getPlayer().getUniqueId());
    }

    /* --------------------------- task management -------------------------- */

    private void startTask(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (handles.containsKey(uuid)) {
            return; // already tracked
        }
        Ct8cRegenDriver driver = new Ct8cRegenDriver(drainRoll);
        TaskHandle[] holder = new TaskHandle[1];
        holder[0] = scheduling.repeatOn(
                player, 1L, 1L,
                () -> tick(player, driver),
                () -> {
                    if (holder[0] != null) {
                        handles.remove(uuid, holder[0]);
                    }
                });
        handles.put(uuid, holder[0]);
    }

    private void cancelTask(@NotNull UUID uuid) {
        TaskHandle handle = handles.remove(uuid);
        if (handle != null) {
            handle.cancel();
        }
    }

    private void cancelAll() {
        handles.values().forEach(TaskHandle::cancel);
        handles.clear();
    }

    /** One CT8c regen tick — owning-thread only (the repeatOn callback). */
    private void tick(@NotNull Player player, @NotNull Ct8cRegenDriver driver) {
        int foodLevel = player.getFoodLevel();
        double health = player.getHealth();
        double maxHealth = Attributes.valueOr(player, Attributes.maxHealth(), 20.0);
        boolean naturalRegenOn = naturalRegen.test(player.getWorld());
        Outcome outcome = driver.tick(foodLevel, health, maxHealth, naturalRegenOn);
        if (outcome == Outcome.NONE) {
            return;
        }
        player.setHealth(Math.min(health + 1.0, maxHealth));
        if (outcome == Outcome.HEAL_AND_DRAIN) {
            player.setFoodLevel(Math.max(0, foodLevel - 1));
        }
    }
}

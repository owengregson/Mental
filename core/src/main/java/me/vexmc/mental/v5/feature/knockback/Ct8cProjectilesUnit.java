package me.vexmc.mental.v5.feature.knockback;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import me.vexmc.mental.platform.Cooldowns;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * Combat Test 8c ranged rules ({@code ct8c-projectiles}, design spec §2.10) — the
 * default-OFF rule feature that supplies the CT8c projectile policy the {@link
 * Ct8cProjectilePolicy} pins. Four behaviours, all zero-touch when disabled (the
 * scope registers nothing until enabled):
 *
 * <ul>
 *   <li><b>Snowball/egg 0-damage knock.</b> The full 0.4 knock (§2.5) is
 *       <em>not</em> shipped here: it rides the always-on {@code
 *       projectile-knockback} desk path, which under the {@code ct8c} knockback
 *       profile (base 0.4, vertical-cap 0.4) delivers exactly the CT8c 0.4
 *       through the velocity pipeline. Re-submitting it would duplicate that
 *       path, so this unit deliberately does not — it never calls
 *       {@code setVelocity} for knockback. The thrown hit's damage is likewise
 *       nulled by that always-on path (era-native 0 on 1.21.2+, the
 *       negligible-substitution keep-alive below).</li>
 *   <li><b>4-tick throw gate.</b> CT8c's client {@code rightClickDelay} between
 *       thrown-projectile releases, emulated through the item-cooldown API
 *       ({@link Cooldowns}) — a documented no-op below 1.11.2 where the API is
 *       absent (era-native: no throw cooldown existed).</li>
 *   <li><b>Bow fatigue.</b> A bow draw held &gt; 3s (§2.10) can no longer be a
 *       critical arrow and takes a 0.25-inaccuracy launch spread. Draw-start is
 *       stamped per player from the right-click interact and consumed at the
 *       shot — the region-thread state feeding the fatigue verdict.</li>
 *   <li><b>Aim-direction-only momentum.</b> A snowball/egg's launch velocity is
 *       rewritten so the shooter's momentum is inherited only along the aim, the
 *       vertical inherited component zeroed (§2.10) — a launch-event velocity
 *       rewrite, distinct from the knockback delivery above.</li>
 *   <li><b>Loyalty void-return.</b> CT8c returns a void-thrown Loyalty trident to
 *       its owner (§2.9). Mainline Java Edition never does this — MC-125755 is
 *       resolved "Works As Intended", so void-thrown tridents are permanently
 *       lost on <em>every</em> mainline version (verified through 1.21.x); the
 *       return is a Combat-Test-only change. So there is no vanilla-parity
 *       version to no-op against: the feature acts wherever tridents exist
 *       (1.13+, the {@link TridentShape} presence probe) and is a no-op below
 *       that. If a future mainline version restores it, add a version ceiling at
 *       the {@link #assemble} decision.</li>
 * </ul>
 *
 * <p>Projectile i-frames are NOT this unit's — they live in {@code ct8c-iframes}
 * (Task D). This unit only shapes launch velocities and the throw gate; the
 * knockback and its i-frame-free repeat-hits stay with the delivery core.</p>
 */
public final class Ct8cProjectilesUnit implements FeatureUnit, Listener {

    private static final long LOYALTY_PERIOD_TICKS = 10L;
    private static final double VOID_MARGIN = 64.0;

    /** A draw held longer than this is stale (never a real bow draw) and swept from the map. */
    private static final long DRAW_STALE_NANOS = 30_000_000_000L;
    private static final int DRAW_SWEEP_THRESHOLD = 64;

    /** {@code World#getMinHeight()} (1.17+), for the void floor; {@code null} below where the world floor is 0. */
    private static final @Nullable Method MIN_HEIGHT = probeMinHeight();

    private final Plugin plugin;
    private final Scheduling scheduling;
    private final TridentShape tridentShape = TridentShape.resolve();

    /** Per-player bow-draw start (nanos), stamped on the right-click interact and consumed at the shot. */
    private final ConcurrentHashMap<UUID, Long> drawStart = new ConcurrentHashMap<>();

    /** Live per-trident void-watch tasks, cancelled on return / on disable (zero-touch). */
    private final ConcurrentHashMap<UUID, TaskHandle> loyaltyTasks = new ConcurrentHashMap<>();

    /** Frozen at assemble: whether the Loyalty void-return watch runs on this server (tridents + item accessor). */
    private boolean loyaltyActive;

    public Ct8cProjectilesUnit(Plugin plugin, Scheduling scheduling) {
        this.plugin = plugin;
        this.scheduling = scheduling;
    }

    @Override
    public Feature descriptor() {
        return Feature.CT8C_PROJECTILES;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // The throw gate needs the item-cooldown API (1.11.2+); below it there is no throw cooldown to set.
        if (!Cooldowns.itemCooldownSupported()) {
            plugin.getLogger().info("ct8c-projectiles: the item-cooldown API is absent below 1.11.2 — the "
                    + "4-tick snowball/egg throw gate is a no-op here (era-native: no throw cooldown existed). "
                    + "Momentum and bow fatigue remain active.");
        }
        // The Loyalty void-return decision, made ONCE here. Mainline Java never returns void-thrown Loyalty
        // tridents (MC-125755 = Works As Intended; the return is Combat-Test-only), so there is no
        // vanilla-parity version to no-op against — the only gate is trident presence and a readable item.
        this.loyaltyActive = tridentShape.canReadLoyalty();
        if (!tridentShape.present()) {
            plugin.getLogger().info("ct8c-projectiles: tridents are absent below 1.13 — Loyalty void-return "
                    + "is a no-op on this version (nothing to return).");
        } else if (!loyaltyActive) {
            plugin.getLogger().info("ct8c-projectiles: the trident item accessor or the Loyalty enchant did "
                    + "not resolve on this server — Loyalty void-return is degraded off here (mandate B10). "
                    + "The throw gate, momentum, and bow fatigue remain active.");
        } else {
            plugin.getLogger().info("ct8c-projectiles: Loyalty void-return active — mainline Java never "
                    + "returns void-thrown Loyalty tridents (Combat-Test-only), so it is restored on this "
                    + "server; void-lost Loyalty tridents fly back to their owner.");
        }
        scope.listen(this);
        // Disable tears down every in-flight void-watch and clears the draw ledger (zero-touch).
        scope.task(() -> (AutoCloseable) () -> {
            loyaltyTasks.values().forEach(TaskHandle::cancel);
            loyaltyTasks.clear();
            drawStart.clear();
        });
    }

    /* ------------------------------------ bow fatigue ------------------------------------ */

    /** Stamp the bow-draw start; the release computes held time against it. */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getMaterial() != Material.BOW) {
            return;
        }
        long now = System.nanoTime();
        if (drawStart.size() > DRAW_SWEEP_THRESHOLD) {
            drawStart.values().removeIf(stamp -> now - stamp > DRAW_STALE_NANOS);
        }
        drawStart.put(event.getPlayer().getUniqueId(), now);
    }

    /**
     * A bow shot released after a &gt;3s draw is fatigued: it cannot be a critical
     * arrow and its launch direction spreads by 0.25 inaccuracy (§2.10). MONITOR
     * so any cancellation / bow-swap decision has already settled; we only reshape
     * the projectile that actually launched.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter)) {
            return;
        }
        ItemStack bow = event.getBow();
        if (bow == null || bow.getType() != Material.BOW) {
            return; // bows only — a crossbow's fixed load is not the held-draw fatigue mechanic
        }
        Long start = drawStart.remove(shooter.getUniqueId());
        if (start == null || !Ct8cProjectilePolicy.fatigued(System.nanoTime() - start)) {
            return;
        }
        Entity projectile = event.getProjectile();
        // Body reference only (never a descriptor): Arrow is 1.9-universal, so this links on every version.
        if (projectile instanceof Arrow arrow) {
            arrow.setCritical(false);
        }
        Vector velocity = projectile.getVelocity();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double[] spread = Ct8cProjectilePolicy.applySpread(
                velocity.getX(), velocity.getY(), velocity.getZ(),
                random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
        projectile.setVelocity(new Vector(spread[0], spread[1], spread[2]));
    }

    /* --------------------------- throw gate + momentum + loyalty --------------------------- */

    @EventHandler(ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        ProjectileSource source = projectile.getShooter();
        if (!(source instanceof Player shooter)) {
            return;
        }
        if (projectile instanceof Snowball || projectile instanceof Egg) {
            rewriteMomentum(projectile, shooter);
            applyThrowGate(shooter, projectile);
        } else if (loyaltyActive && tridentShape.isTrident(projectile)) {
            trackLoyaltyTrident(projectile, shooter);
        }
    }

    /** Aim-direction-only momentum, vertical inherited zeroed (§2.10) — a pure launch-velocity rewrite. */
    @SuppressWarnings("deprecation") // Player#isOnGround() is the client-reported flag CT8c's launch physics
    // wants (the same one the knockback/shield gates use) — not the server truth; the deprecation is Bukkit
    // steering API users toward the server-side check, which is the wrong ground state here.
    private void rewriteMomentum(Projectile projectile, Player shooter) {
        Vector projectileVelocity = projectile.getVelocity();
        Vector shooterVelocity = shooter.getVelocity();
        double[] rewritten = Ct8cProjectilePolicy.applyMomentum(
                projectileVelocity.getX(), projectileVelocity.getY(), projectileVelocity.getZ(),
                shooterVelocity.getX(), shooterVelocity.getY(), shooterVelocity.getZ(),
                shooter.isOnGround());
        projectile.setVelocity(new Vector(rewritten[0], rewritten[1], rewritten[2]));
    }

    /** The 4-tick snowball/egg throw gate via the item-cooldown API (§2.10) — Folia-routed to the shooter. */
    private void applyThrowGate(Player shooter, Projectile projectile) {
        if (!Cooldowns.itemCooldownSupported()) {
            return; // the boot report already announced the pre-1.11.2 no-op
        }
        Material material = projectile instanceof Snowball ? Material.SNOWBALL : Material.EGG;
        scheduling.runOn(shooter,
                () -> shooter.setCooldown(material, Ct8cProjectilePolicy.THROW_GATE_TICKS), () -> {});
    }

    /* --------------------------------- Loyalty void-return --------------------------------- */

    /**
     * Watches a launched Loyalty trident: once it falls into the void it is
     * returned to its owner (§2.9). Non-Loyalty tridents are ignored — only
     * Loyalty ones return, matching the Combat-Test change this restores. One
     * region-correct per-trident task; the retired hook fires when the trident is
     * gone by the normal Loyalty return / despawn.
     */
    private void trackLoyaltyTrident(Projectile trident, Player shooter) {
        if (tridentShape.loyaltyLevel(trident) <= 0) {
            return;
        }
        UUID tridentId = trident.getUniqueId();
        UUID ownerId = shooter.getUniqueId();
        TaskHandle handle = scheduling.repeatOn(trident, LOYALTY_PERIOD_TICKS, LOYALTY_PERIOD_TICKS,
                () -> checkVoid(trident, tridentId, ownerId),
                () -> loyaltyTasks.remove(tridentId));
        loyaltyTasks.put(tridentId, handle);
    }

    /** Runs on the trident's region: below the void floor, cancel the watch and fly the trident home. */
    private void checkVoid(Projectile trident, UUID tridentId, UUID ownerId) {
        if (!trident.isValid()) {
            return; // the retired hook will drop the tracking entry
        }
        if (trident.getLocation().getY() >= voidFloor(trident.getWorld())) {
            return; // not in the void yet
        }
        TaskHandle handle = loyaltyTasks.remove(tridentId);
        if (handle != null) {
            handle.cancel();
        }
        ItemStack item = tridentShape.itemStack(trident);
        trident.remove();
        if (item == null) {
            return;
        }
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null) {
            return; // owner offline — the trident is lost exactly as vanilla loses a void-thrown one
        }
        ItemStack returned = item.clone();
        scheduling.runOn(owner, () -> giveOrDrop(owner, returned), () -> {});
    }

    /** Runs on the owner's region: the returned trident lands in the inventory, or at their feet if full. */
    private static void giveOrDrop(Player owner, ItemStack item) {
        Map<Integer, ItemStack> leftover = owner.getInventory().addItem(item);
        for (ItemStack extra : leftover.values()) {
            owner.getWorld().dropItem(owner.getLocation(), extra);
        }
    }

    private static double voidFloor(World world) {
        if (MIN_HEIGHT != null) {
            try {
                return ((Number) MIN_HEIGHT.invoke(world)).intValue() - VOID_MARGIN;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Probed present; fall through to the pre-1.17 floor on any slip.
            }
        }
        return -VOID_MARGIN; // pre-1.17 the world floor is 0, so -64 is safely into the void
    }

    private static @Nullable Method probeMinHeight() {
        try {
            return World.class.getMethod("getMinHeight");
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    /**
     * The version-neutral trident classifier and item reader, resolved reflectively so no trident type is
     * ever named in a field, method signature, or listener descriptor (the reason this unit registers
     * cleanly below 1.14 where {@code AbstractArrow} is absent — the same discipline as
     * {@code ProjectileKnockbackUnit}'s {@code ArrowShape}). {@code Trident} lands at 1.13; below that the
     * classifier is empty and every Loyalty path is a natural no-op.
     */
    private static final class TridentShape {

        private final @Nullable Class<?> tridentType;
        private final @Nullable Method itemAccessor;
        private final @Nullable Enchantment loyalty;

        private TridentShape(
                @Nullable Class<?> tridentType, @Nullable Method itemAccessor, @Nullable Enchantment loyalty) {
            this.tridentType = tridentType;
            this.itemAccessor = itemAccessor;
            this.loyalty = loyalty;
        }

        static TridentShape resolve() {
            Class<?> type = classOrNull("org.bukkit.entity.Trident");
            Method accessor = type == null ? null : itemAccessor(type);
            Enchantment loyalty = type == null ? null : loyaltyOrNull();
            return new TridentShape(type, accessor, loyalty);
        }

        boolean present() {
            return tridentType != null;
        }

        /** Whether Loyalty void-return can run: tridents exist AND the item + enchant resolved to read Loyalty. */
        boolean canReadLoyalty() {
            return tridentType != null && itemAccessor != null && loyalty != null;
        }

        boolean isTrident(@Nullable Object entity) {
            return tridentType != null && tridentType.isInstance(entity);
        }

        int loyaltyLevel(Entity trident) {
            ItemStack item = itemStack(trident);
            return item == null || loyalty == null ? 0 : item.getEnchantmentLevel(loyalty);
        }

        @Nullable ItemStack itemStack(Entity trident) {
            if (itemAccessor == null) {
                return null;
            }
            try {
                Object value = itemAccessor.invoke(trident);
                return value instanceof ItemStack stack ? stack : null;
            } catch (ReflectiveOperationException | RuntimeException absent) {
                return null;
            }
        }

        private static @Nullable Method itemAccessor(Class<?> tridentType) {
            for (String candidate : new String[] {"getItemStack", "getItem"}) {
                try {
                    Method method = tridentType.getMethod(candidate);
                    if (ItemStack.class.isAssignableFrom(method.getReturnType())) {
                        return method;
                    }
                } catch (NoSuchMethodException ignored) {
                    // try the next candidate name
                }
            }
            return null;
        }

        private static @Nullable Enchantment loyaltyOrNull() {
            try {
                return Enchantment.getByKey(NamespacedKey.minecraft("loyalty"));
            } catch (Throwable absent) {
                return null; // getByKey / NamespacedKey below their floor — defensive (tridents imply 1.13+)
            }
        }

        private static @Nullable Class<?> classOrNull(String className) {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException absent) {
                return null;
            }
        }
    }
}

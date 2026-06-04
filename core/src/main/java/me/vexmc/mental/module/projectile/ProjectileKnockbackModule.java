package me.vexmc.mental.module.projectile;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.ProjectileKnockbackSettings;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.module.knockback.EntityState;
import me.vexmc.mental.module.knockback.KnockbackEngine;
import me.vexmc.mental.module.knockback.KnockbackPipeline;
import me.vexmc.mental.module.knockback.KnockbackVector;
import me.vexmc.mental.module.ocm.OcmMechanic;
import me.vexmc.mental.platform.Enchantments;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 1.7.10 projectile knockback, computed and applied by Mental on every
 * version so the era semantics hold uniformly:
 *
 * <ul>
 *   <li><b>Direction is positional.</b> Legacy base knockback pushed away
 *       from where the <em>shooter stood</em> ({@code damageSource.getEntity()}),
 *       never along the projectile's flight — modern servers changed that
 *       in 1.21.2, and Mental changes it back.</li>
 *   <li><b>Snowballs, eggs, ender pearls</b> were full zero-damage hits.
 *       The negligible damage substitution keeps the vanilla hurt pipeline
 *       (i-frames, protection-plugin vetoes) on versions that drop
 *       zero-damage hits; the vector itself comes from the engine and the
 *       victim's residual ledger.</li>
 *   <li><b>Arrows</b> add Punch exactly as 1.7.10 did: {@code 0.6/level}
 *       along the arrow's horizontal flight plus {@code 0.1} vertical,
 *       additive after the base knock and never resistance-scaled. The
 *       level is stamped onto the arrow at shoot time, so dispenser
 *       arrows carry none and skeleton bows carry theirs.</li>
 * </ul>
 */
public final class ProjectileKnockbackModule extends CombatModule implements Listener {

    private static final String CITIZENS_NPC_METADATA = "NPC";
    private static final double PUNCH_HORIZONTAL_PER_LEVEL = 0.6;
    private static final double PUNCH_VERTICAL = 0.1;

    private record Flight(@NotNull Vector velocity, long stampNanos) {}

    private static final long FLIGHT_STALE_NANOS = 10_000_000_000L;
    private static final int FLIGHT_SWEEP_THRESHOLD = 64;

    private final KnockbackPipeline pipeline;
    private final NamespacedKey punchKey;
    private final ConcurrentHashMap<UUID, Flight> arrowFlight = new ConcurrentHashMap<>();

    public ProjectileKnockbackModule(@NotNull MentalServices services, @NotNull KnockbackPipeline pipeline) {
        super(services, "projectile-knockback", "Projectile Knockback",
                "1.7.10 knockback from snowballs, eggs, ender pearls, and arrows.",
                DebugCategory.PROJECTILE);
        this.pipeline = pipeline;
        this.punchKey = new NamespacedKey(services.plugin(), "punch-level");
    }

    @Override
    public boolean configEnabled() {
        return services.config().projectileKnockback().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
    }

    @Override
    protected void onDisable() {
        arrowFlight.clear();
    }

    /** Stamps the bow's Punch level onto the arrow; hit-time item state is unreliable. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShootBow(@NotNull EntityShootBowEvent event) {
        ProjectileKnockbackSettings settings = services.config().projectileKnockback();
        if (!settings.enabled() || !settings.arrows()
                || !(event.getProjectile() instanceof AbstractArrow arrow) || arrow instanceof Trident) {
            return;
        }
        ItemStack bow = event.getBow();
        Enchantment punch = Enchantments.punch();
        int level = bow == null || punch == null ? 0 : bow.getEnchantmentLevel(punch);
        if (level > 0) {
            arrow.getPersistentDataContainer().set(punchKey, PersistentDataType.INTEGER, level);
        }
    }

    /**
     * Thrown projectiles: the vector is computed at hit time — before the
     * hurt pipeline runs — because 1.21.2+ servers apply their own (flight
     * directed) knockback for these without firing any damage event Mental
     * could veto from.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(@NotNull ProjectileHitEvent event) {
        ProjectileKnockbackSettings settings = services.config().projectileKnockback();
        if (!settings.enabled()) {
            return;
        }
        if (event.getEntity() instanceof AbstractArrow arrow && !(arrow instanceof Trident)) {
            if (settings.arrows() && event.getHitEntity() instanceof Player) {
                long now = System.nanoTime();
                if (arrowFlight.size() > FLIGHT_SWEEP_THRESHOLD) {
                    arrowFlight.values().removeIf(flight -> now - flight.stampNanos() > FLIGHT_STALE_NANOS);
                }
                arrowFlight.put(arrow.getUniqueId(), new Flight(arrow.getVelocity(), now));
            }
            return;
        }
        Projectile projectile = event.getEntity();
        if (!isThrownProjectile(projectile) || !(event.getHitEntity() instanceof Player victim)) {
            return;
        }
        // OCM's projectile-knockback follows the defender's modeset (the
        // damager is never human). Arrows above are exempt: OCM has no arrow
        // module, so Mental's Punch handling keeps working alongside it.
        if (services.ocmGate().handles(OcmMechanic.PROJECTILE_KNOCKBACK, victim)) {
            debug.log(() -> "OCM owns projectile knockback against " + victim.getName() + " — yielding");
            return;
        }
        if (!isKnockableVictim(victim, projectile.getShooter())) {
            return;
        }

        Location source = shooterPosition(projectile);
        EntityState victimState = EntityState.captureVictim(victim, pipeline.ledger(), System.nanoTime());
        KnockbackVector vector = KnockbackEngine.computeBase(
                victimState, source.getX(), source.getZ(),
                services.knockbackProfiles().resolve(victim), null, ThreadLocalRandom.current());

        pipeline.submit(victim, vector, shooterEntity(projectile), KnockbackPipeline.Cause.PROJECTILE);
        pipeline.ensureDelivery(victim);
        debug.log(() -> event.getEntity().getType() + " hit " + victim.getName()
                + (vector == null
                        ? " — legacy resistance cancelled the knock"
                        : " -> (" + vector.x() + ", " + vector.y() + ", " + vector.z() + ")"));
    }

    /** Zero-damage substitution keeps the vanilla hurt pipeline alive on versions that drop it. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // DamageModifier is the only absorption-zeroing API Bukkit has
    public void onProjectileDamage(@NotNull EntityDamageByEntityEvent event) {
        ProjectileKnockbackSettings settings = services.config().projectileKnockback();
        if (!settings.enabled() || event.getDamage() != 0.0) {
            return;
        }

        double substitute;
        if (event.getDamager() instanceof Snowball) {
            substitute = settings.snowballDamage();
        } else if (event.getDamager() instanceof Egg) {
            substitute = settings.eggDamage();
        } else if (event.getDamager() instanceof EnderPearl) {
            substitute = settings.enderPearlDamage();
        } else {
            return;
        }
        if (services.ocmGate().handles(OcmMechanic.PROJECTILE_KNOCKBACK,
                event.getEntity() instanceof Player victim ? victim : null)) {
            debug.log(() -> "OCM owns the " + event.getDamager().getType()
                    + " damage substitution — yielding");
            return;
        }

        event.setDamage(substitute);
        if (event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)) {
            event.setDamage(EntityDamageEvent.DamageModifier.ABSORPTION, 0);
        }
        debug.log(() -> event.getDamager().getType() + " hit "
                + event.getEntity().getType() + " — substituted " + substitute + " damage");
    }

    /** Arrows knock at damage time: positional base plus the legacy Punch addition. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowDamage(@NotNull EntityDamageByEntityEvent event) {
        ProjectileKnockbackSettings settings = services.config().projectileKnockback();
        if (!settings.enabled() || !settings.arrows()
                || !(event.getDamager() instanceof AbstractArrow arrow)
                || arrow instanceof Trident
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Flight flight = arrowFlight.remove(arrow.getUniqueId());
        if (!isKnockableVictim(victim, arrow.getShooter())) {
            return;
        }

        Location source = shooterPosition(arrow);
        EntityState victimState = EntityState.captureVictim(victim, pipeline.ledger(), System.nanoTime());
        KnockbackVector vector = KnockbackEngine.computeBase(
                victimState, source.getX(), source.getZ(),
                services.knockbackProfiles().resolve(victim), null, ThreadLocalRandom.current());

        if (vector != null) {
            vector = withPunch(vector, flight != null ? flight.velocity() : arrow.getVelocity(),
                    punchLevel(arrow));
        }
        pipeline.submit(victim, vector, shooterEntity(arrow), KnockbackPipeline.Cause.ARROW);
        KnockbackVector logged = vector;
        debug.log(() -> "arrow hit " + victim.getName()
                + (logged == null
                        ? " — legacy resistance cancelled the knock"
                        : " -> (" + logged.x() + ", " + logged.y() + ", " + logged.z() + ")"));
    }

    /** A protection plugin cancelling the hit also withdraws the queued knock. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileDamageCancelled(@NotNull EntityDamageByEntityEvent event) {
        if (!event.isCancelled() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (event.getDamager() instanceof AbstractArrow arrow) {
            arrowFlight.remove(arrow.getUniqueId());
            pipeline.withdraw(victim);
        } else if (isThrownProjectile(event.getDamager())) {
            pipeline.withdraw(victim);
        }
    }

    private @NotNull KnockbackVector withPunch(KnockbackVector base, Vector flight, int level) {
        if (level <= 0) {
            return base;
        }
        double horizontal = Math.hypot(flight.getX(), flight.getZ());
        if (horizontal <= 1.0e-4) {
            return base;
        }
        return KnockbackEngine.clamp(
                base.x() + flight.getX() / horizontal * PUNCH_HORIZONTAL_PER_LEVEL * level,
                base.y() + PUNCH_VERTICAL,
                base.z() + flight.getZ() / horizontal * PUNCH_HORIZONTAL_PER_LEVEL * level);
    }

    private int punchLevel(AbstractArrow arrow) {
        Integer stamped = arrow.getPersistentDataContainer().get(punchKey, PersistentDataType.INTEGER);
        return stamped != null ? stamped : 0;
    }

    private boolean isKnockableVictim(Player victim, @Nullable ProjectileSource shooter) {
        if (victim.getGameMode() == GameMode.CREATIVE || victim.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (victim.hasMetadata(CITIZENS_NPC_METADATA)) {
            return false;
        }
        if (shooter instanceof Player shooterPlayer) {
            if (shooterPlayer.getUniqueId().equals(victim.getUniqueId())) {
                return false;
            }
            if (!victim.getWorld().getPVP()) {
                return false;
            }
        }
        if (victim.getNoDamageTicks() > victim.getMaximumNoDamageTicks() / 2.0) {
            debug.log(() -> victim.getName() + " inside invulnerability window — projectile knock skipped");
            return false;
        }
        return true;
    }

    private static boolean isThrownProjectile(Object entity) {
        return entity instanceof Snowball || entity instanceof Egg || entity instanceof EnderPearl;
    }

    private static @NotNull Location shooterPosition(Projectile projectile) {
        ProjectileSource shooter = projectile.getShooter();
        if (shooter instanceof LivingEntity living) {
            return living.getLocation();
        }
        if (shooter instanceof BlockProjectileSource block) {
            return block.getBlock().getLocation().add(0.5, 0.5, 0.5);
        }
        return projectile.getLocation();
    }

    private static @Nullable LivingEntity shooterEntity(Projectile projectile) {
        return projectile.getShooter() instanceof LivingEntity living ? living : null;
    }
}

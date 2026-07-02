package me.vexmc.mental.v5.feature.knockback;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.math.PunchMath;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.platform.Enchantments;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.Vectors;
import me.vexmc.mental.v5.coexist.OcmBinding;
import me.vexmc.mental.v5.config.settings.ProjectileKnockbackSettings;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.delivery.HitIds;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.session.SessionService;
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
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * 1.7.10 projectile knockback (the retired {@code ProjectileKnockbackModule} on
 * the v5 seams). Snowballs, eggs and ender pearls were full zero-damage hits and
 * arrows added Punch, all knocking away from where the <em>shooter stood</em> —
 * the positional direction modern servers changed in 1.21.2 and Mental changes
 * back. The negligible-damage substitution keeps the vanilla hurt pipeline on
 * versions that drop zero-damage hits; the vector itself is the engine's, from
 * the shooter position and the victim's residual ledger. The 1.21.2+ substitution
 * stays a no-op (the thrown vector is computed at hit time regardless of whether a
 * damage event follows).
 */
public final class ProjectileKnockbackUnit implements FeatureUnit, Listener {

    private static final String CITIZENS_NPC_METADATA = "NPC";
    private static final long FLIGHT_STALE_NANOS = 10_000_000_000L;
    private static final int FLIGHT_SWEEP_THRESHOLD = 64;

    private record Flight(Vector velocity, long stampNanos) {}

    private final SessionService sessions;
    private final OcmBinding ocmBinding;
    private final Scheduling scheduling;
    private final Supplier<Snapshot> snapshot;
    private final HitIds ids;
    private final TickClock clock;
    private final NamespacedKey punchKey;

    /**
     * True on 1.21.2+ where vanilla restored projectile-KB-vs-players (mandate
     * §10 / MC-2110). Boot-resolved from {@link
     * me.vexmc.mental.v5.platform.PlatformProfile#projectileKnockbackRestored()},
     * frozen for the unit's life.
     */
    private final boolean projectileKnockbackRestored;

    private final ConcurrentHashMap<UUID, Flight> arrowFlight = new ConcurrentHashMap<>();

    public ProjectileKnockbackUnit(
            Plugin plugin, SessionService sessions, OcmBinding ocmBinding, Scheduling scheduling,
            Supplier<Snapshot> snapshot, HitIds ids, TickClock clock,
            boolean projectileKnockbackRestored) {
        this.sessions = sessions;
        this.ocmBinding = ocmBinding;
        this.scheduling = scheduling;
        this.snapshot = snapshot;
        this.ids = ids;
        this.clock = clock;
        this.punchKey = new NamespacedKey(plugin, "punch-level");
        this.projectileKnockbackRestored = projectileKnockbackRestored;
    }

    /**
     * Whether Mental still substitutes the era thrown-projectile knockback on
     * this platform. Below 1.21.2 vanilla dropped projectile-KB-vs-players, so
     * Mental substitutes (the era positional knock + the negligible-damage keep-
     * alive); on 1.21.2+ vanilla restored it (mandate §10 / MC-2110), so every
     * thrown-projectile substitution path is a NO-OP — vanilla knocks, and Mental
     * must not double it. Arrows (Punch) are always Mental's and are unaffected.
     */
    static boolean substitutesThrownKnockback(boolean projectileKnockbackRestored) {
        return !projectileKnockbackRestored;
    }

    @Override
    public Feature descriptor() {
        return Feature.PROJECTILE_KNOCKBACK;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    @SuppressWarnings("unchecked")
    private ProjectileKnockbackSettings settings() {
        return snapshot.get().settings(
                (SettingsKey<ProjectileKnockbackSettings>) Feature.PROJECTILE_KNOCKBACK.settingsKey());
    }

    /** Stamps the bow's Punch level onto the arrow; hit-time item state is unreliable. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        ProjectileKnockbackSettings settings = settings();
        if (!settings.arrows()
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        ProjectileKnockbackSettings settings = settings();
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
        if (!isThrown(projectile) || !(event.getHitEntity() instanceof Player victim)) {
            return;
        }
        // 1.21.2+ restored vanilla projectile-KB-vs-players — the era substitution
        // is a NO-OP there (vanilla knocks; Mental must not double it). Below
        // 1.21.2 the substitution stands, byte-identical to the era.
        if (!substitutesThrownKnockback(projectileKnockbackRestored)) {
            return;
        }
        if (!ocmBinding.mentalOwns(MechanicToken.PROJECTILE_KNOCKBACK, victim.getUniqueId())) {
            return;
        }
        if (!isKnockable(victim, projectile.getShooter())) {
            return;
        }
        CombatSession session = sessions.sessionFor(victim.getUniqueId());
        if (session == null) {
            return;
        }
        Location source = shooterPosition(projectile);
        KnockbackProfile profile = snapshot.get().profileFor(victim.getWorld().getName());
        KnockbackVector vector = KnockbackEngine.computeBase(
                EntityStates.captureVictim(victim, session.ledger()),
                source.getX(), source.getZ(), profile, null, ThreadLocalRandom.current());

        HitTransaction tx = mint(new HitSource.Thrown(projectile.getType().name()),
                shooterId(projectile), victim.getUniqueId());
        session.desk().submit(tx, vector);
        session.desk().awaitVelocityEvent(tx);
        scheduleEnsure(victim, session, tx);
    }

    /** Zero-damage substitution keeps the vanilla hurt pipeline alive on versions that drop it. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // DamageModifier is the only absorption-zeroing API Bukkit has
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        ProjectileKnockbackSettings settings = settings();
        // 1.21.2+ restored vanilla projectile knockback — the negligible-damage
        // keep-alive substitution is a NO-OP there (the vanilla hurt pipeline is
        // no longer dropped for thrown projectiles).
        if (!substitutesThrownKnockback(projectileKnockbackRestored) || event.getDamage() != 0.0) {
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
        UUID decider = event.getEntity() instanceof Player victim ? victim.getUniqueId() : null;
        if (decider != null && !ocmBinding.mentalOwns(MechanicToken.PROJECTILE_KNOCKBACK, decider)) {
            return;
        }
        event.setDamage(substitute);
        if (event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)) {
            event.setDamage(EntityDamageEvent.DamageModifier.ABSORPTION, 0);
        }
    }

    /** Arrows knock at damage time: positional base plus the legacy Punch addition. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent event) {
        ProjectileKnockbackSettings settings = settings();
        if (!settings.arrows()
                || !(event.getDamager() instanceof AbstractArrow arrow)
                || arrow instanceof Trident
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Flight flight = arrowFlight.remove(arrow.getUniqueId());
        if (!isKnockable(victim, arrow.getShooter())) {
            return;
        }
        CombatSession session = sessions.sessionFor(victim.getUniqueId());
        if (session == null) {
            return;
        }
        Location source = shooterPosition(arrow);
        KnockbackProfile profile = snapshot.get().profileFor(victim.getWorld().getName());
        KnockbackVector vector = KnockbackEngine.computeBase(
                EntityStates.captureVictim(victim, session.ledger()),
                source.getX(), source.getZ(), profile, null, ThreadLocalRandom.current());
        int punch = punchLevel(arrow);
        if (vector != null && punch > 0) {
            Vector velocity = flight != null ? flight.velocity() : arrow.getVelocity();
            vector = PunchMath.withPunch(vector, velocity.getX(), velocity.getZ(), punch);
        }
        HitTransaction tx = mint(new HitSource.Arrow(punch), shooterId(arrow), victim.getUniqueId());
        session.desk().submit(tx, vector);
        session.desk().awaitVelocityEvent(tx);
    }

    /** A protection plugin cancelling the hit also withdraws the queued knock. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamageCancelled(EntityDamageByEntityEvent event) {
        if (!event.isCancelled() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        boolean projectile = event.getDamager() instanceof AbstractArrow || isThrown(event.getDamager());
        if (!projectile) {
            return;
        }
        if (event.getDamager() instanceof AbstractArrow arrow) {
            arrowFlight.remove(arrow.getUniqueId());
        }
        CombatSession session = sessions.sessionFor(victim.getUniqueId());
        if (session != null) {
            HitTransaction tx = session.currentEventTransaction();
            if (tx != null) {
                session.desk().withdraw(tx.context().id());
            }
        }
    }

    private HitTransaction mint(HitSource source, UUID shooterId, UUID victimId) {
        HitContext context = new HitContext(
                ids.next(), source, shooterId, victimId,
                new SprintVerdict(false, null, clock.current()), false, false, null, clock.current());
        return new HitTransaction(context);
    }

    /**
     * Force the knock next tick when no vanilla velocity event delivered it (a
     * snowball deals no vanilla knockback, so nothing resolves the desk). A bare
     * {@code setVelocity} would ship the value one physics tick decayed — the
     * tracker fires {@code PlayerVelocityEvent} only after the entity ticks — where
     * the era wire is the FULL tracker stamp. So we re-submit fresh (stamped this
     * tick, surviving this tick's sweep) and trigger the event: the {@code
     * DeskRouter} overrides it to the full stamp, exactly as the melee path does.
     */
    private void scheduleEnsure(Player victim, CombatSession session, HitTransaction tx) {
        scheduling.runOn(victim, () -> {
            KnockbackVector base = session.desk().pendingVectorFor(tx.context().id());
            if (base == null) {
                return; // a vanilla velocity event already resolved (and shipped) it
            }
            HitTransaction fresh = mint(
                    tx.context().source(), tx.context().attackerId(), victim.getUniqueId());
            session.desk().submit(fresh, base);
            session.desk().awaitVelocityEvent(fresh);
            victim.setVelocity(Vectors.toBukkit(base));
        }, () -> {});
    }

    private boolean isKnockable(Player victim, @Nullable ProjectileSource shooter) {
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
        return victim.getNoDamageTicks() <= victim.getMaximumNoDamageTicks() / 2.0;
    }

    private int punchLevel(AbstractArrow arrow) {
        Integer stamped = arrow.getPersistentDataContainer().get(punchKey, PersistentDataType.INTEGER);
        return stamped != null ? stamped : 0;
    }

    private static boolean isThrown(Object entity) {
        return entity instanceof Snowball || entity instanceof Egg || entity instanceof EnderPearl;
    }

    private static Location shooterPosition(Projectile projectile) {
        ProjectileSource shooter = projectile.getShooter();
        if (shooter instanceof LivingEntity living) {
            return living.getLocation();
        }
        if (shooter instanceof BlockProjectileSource block) {
            return block.getBlock().getLocation().add(0.5, 0.5, 0.5);
        }
        return projectile.getLocation();
    }

    private static @Nullable UUID shooterId(Projectile projectile) {
        return projectile.getShooter() instanceof LivingEntity living ? living.getUniqueId() : null;
    }
}

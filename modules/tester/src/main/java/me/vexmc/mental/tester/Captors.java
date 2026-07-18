package me.vexmc.mental.tester;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.api.event.KnockbackApplyEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** MONITOR-priority observers for the values modules actually produced. */
public final class Captors implements Listener {

    /**
     * {@code ProjectileHitEvent#getHitEntity()} is absent below 1.10.2 (javap-verified). Probing it once
     * keeps the captor from throwing a {@code NoSuchMethodError} into every ProjectileHitEvent on 1.9.4 —
     * the tester must not throw where the feature it observes cannot resolve the target either (the
     * thrown-projectile suites skip on that version by design).
     */
    private static final boolean HIT_ENTITY_SUPPORTED = probeHitEntity();

    private static boolean probeHitEntity() {
        try {
            ProjectileHitEvent.class.getMethod("getHitEntity");
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    private final Map<UUID, Vector> velocities = new ConcurrentHashMap<>();
    private final Map<UUID, Double> damages = new ConcurrentHashMap<>();
    private final Map<UUID, Double> finalDamages = new ConcurrentHashMap<>();
    private final Map<UUID, String> projectileHits = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> knockbackApplies = new ConcurrentHashMap<>();

    public static @NotNull Captors register(@NotNull Plugin plugin) {
        Captors captors = new Captors();
        plugin.getServer().getPluginManager().registerEvents(captors, plugin);
        return captors;
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(@NotNull PlayerVelocityEvent event) {
        velocities.put(event.getPlayer().getUniqueId(), event.getVelocity().clone());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageByEntityEvent event) {
        damages.put(event.getEntity().getUniqueId(), event.getDamage());
        // getDamage() is the BASE; getFinalDamage() is after every modifier
        // (armour, resistance, enchant, absorption) — the value a defensive
        // module like old-armour-strength actually shapes.
        finalDamages.put(event.getEntity().getUniqueId(), event.getFinalDamage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(@NotNull ProjectileHitEvent event) {
        // getHitEntity() is absent below 1.10.2 — no target resolution there, so the captor records nothing
        // (the thrown-projectile suites skip on that version, matching Mental's own getHitEntity degrade).
        if (!HIT_ENTITY_SUPPORTED) {
            return;
        }
        if (event.getHitEntity() != null) {
            projectileHits.put(event.getHitEntity().getUniqueId(), event.getEntity().getType().name());
        }
    }

    /**
     * Mental fires this for every knockback it applies — the discriminator the
     * suites assert Mental owns and ships the era knock.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockbackApply(@NotNull KnockbackApplyEvent event) {
        knockbackApplies.merge(event.getVictim().getUniqueId(), 1, Integer::sum);
    }

    public @Nullable Vector velocityOf(@NotNull UUID player) {
        return velocities.get(player);
    }

    public @Nullable Double damageOf(@NotNull UUID entity) {
        return damages.get(entity);
    }

    public @Nullable Double finalDamageOf(@NotNull UUID entity) {
        return finalDamages.get(entity);
    }

    public @Nullable String projectileHitOn(@NotNull UUID entity) {
        return projectileHits.get(entity);
    }

    public int knockbackAppliesTo(@NotNull UUID victim) {
        return knockbackApplies.getOrDefault(victim, 0);
    }

    public void reset() {
        velocities.clear();
        damages.clear();
        finalDamages.clear();
        knockbackApplies.clear();
    }
}

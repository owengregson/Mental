package me.vexmc.strikesync.module.hitreg;

import me.vexmc.strikesync.config.HitRegSettings;
import me.vexmc.strikesync.core.StrikeSyncService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Main-thread (or owning-region thread) damage application for the fast hit
 * registration path.
 *
 * <h2>Why this is needed</h2>
 * The async listener cancels the original {@code INTERACT_ENTITY} packet so
 * vanilla never sees it, which means vanilla never invokes
 * {@code Player#attack(Entity)}. We have to apply the hit ourselves on a
 * thread that owns the target's region.
 *
 * <h2>What it does</h2>
 * <ol>
 *   <li>Re-resolve attacker and target by id (state may have shifted between
 *       the async accept and now).</li>
 *   <li>Re-validate (still online, alive, attackable, in range) — the async
 *       checks ran against possibly-stale data.</li>
 *   <li>Compute damage via {@link DamageCalculator} (1.8-style).</li>
 *   <li>Call {@code damageable.damage(amount, attacker)} — this is the Bukkit
 *       wrapper around vanilla's {@code hurt} chain. It fires
 *       {@code EntityDamageByEntityEvent} (which the knockback module hooks),
 *       applies armor reduction, plays the hurt sound and animation, and
 *       calls vanilla's knockback path which fires
 *       {@code PlayerVelocityEvent} (which the knockback module overrides).</li>
 *   <li>Optionally reset the attacker's attack-cooldown attribute, replicating
 *       what vanilla {@code Player#attack} does at the end.</li>
 * </ol>
 *
 * <h2>What it does NOT do</h2>
 * Sweep attack, weapon durability damage, hunger cost, statistics, advancement
 * triggers, critical-hit particle, sweep particle. These are deliberate
 * omissions for the 1.8-feel target audience; they can be added incrementally.
 */
public final class HitApplier {

    private static final double VANILLA_REACH = 6.0D;     // entity reach attribute default
    private static final double REACH_LENIENCY = 1.0D;    // tolerate slight position drift

    private final StrikeSyncService service;

    public HitApplier(StrikeSyncService service) {
        this.service = service;
    }

    /** Look up the attacker and target by id, then re-validate and apply. */
    public void apply(UUID attackerUuid, int targetEntityId) {
        Player attacker = Bukkit.getPlayer(attackerUuid);
        if (attacker == null || !attacker.isOnline()) return;
        if (attacker.getGameMode() == GameMode.SPECTATOR) return;

        Entity entity = lookupEntity(attacker, targetEntityId);
        if (!(entity instanceof Damageable damageable)) return;
        if (damageable.isDead()) return;
        if (!isStillAttackable(attacker, damageable)) return;
        if (!isInReach(attacker, damageable)) return;

        HitRegSettings settings = service.config().hitReg();
        double amount = damageable instanceof LivingEntity living
                ? DamageCalculator.calculate(attacker, living, settings.simulateCrits())
                : 1.0D;

        // Bukkit's damage(double, Entity) drives vanilla's hurt path:
        //   - fires EntityDamageByEntityEvent (knockback module hooks here)
        //   - armor reduction, invulnerability ticks
        //   - vanilla knockback → PlayerVelocityEvent (knockback module overrides)
        //   - hurt animation + sound packets
        damageable.damage(amount, attacker);

        if (settings.resetAttackCooldown()) {
            attacker.resetCooldown();
        }
    }

    /**
     * Walk the attacker's world looking for an entity with this id. There is no
     * public {@code World#getEntity(int)} on Bukkit, so iteration is the
     * portable option. The cost is bounded (one world's entity list, run on
     * the owning thread) and the path is cold-ish (gated by CPS limiter).
     */
    private static Entity lookupEntity(Player attacker, int entityId) {
        for (Entity e : attacker.getWorld().getEntities()) {
            if (e.getEntityId() == entityId) return e;
        }
        return null;
    }

    private static boolean isStillAttackable(Player attacker, Damageable target) {
        if (attacker.getWorld() != target.getWorld()) return false;
        if (target instanceof Player tp) {
            if (tp.getGameMode() == GameMode.CREATIVE) return false;
            if (tp.getGameMode() == GameMode.SPECTATOR) return false;
            Boolean pvp = attacker.getWorld().getGameRuleValue(GameRules.PVP);
            if (pvp != null && !pvp) return false;
        }
        return true;
    }

    private static boolean isInReach(Player attacker, Damageable target) {
        // Loose check using bounding-box centers; tight enough to reject
        // teleport-cheat hits without false-positives on legitimate combat.
        double dx = attacker.getLocation().getX() - target.getLocation().getX();
        double dy = attacker.getLocation().getY() - target.getLocation().getY();
        double dz = attacker.getLocation().getZ() - target.getLocation().getZ();
        double sq = dx * dx + dy * dy + dz * dz;
        double max = VANILLA_REACH + REACH_LENIENCY;
        return sq <= max * max;
    }
}

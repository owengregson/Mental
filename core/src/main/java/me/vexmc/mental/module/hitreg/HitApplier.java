package me.vexmc.mental.module.hitreg;

import java.util.UUID;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.module.ocm.OcmMechanic;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Owning-thread damage application for the fast path.
 *
 * <p>The cancelled attack packet means vanilla never runs
 * {@code Player#attack}; this re-resolves both parties, re-validates against
 * fresh state, and drives the hit through Bukkit's {@code damage(amount,
 * attacker)} — which fires the full event chain (damage events, armor,
 * invulnerability, vanilla knockback, hurt feedback) exactly as a vanilla hit
 * would. Sweep, durability, statistics and hunger are deliberate omissions
 * for the 1.7.10 target feel.</p>
 */
public final class HitApplier {

    private static final double VANILLA_REACH = 6.0;
    private static final double REACH_LENIENCY = 1.0;
    private static final double MAX_REACH_SQUARED =
            (VANILLA_REACH + REACH_LENIENCY) * (VANILLA_REACH + REACH_LENIENCY);

    private final MentalServices services;

    public HitApplier(@NotNull MentalServices services) {
        this.services = services;
    }

    public void apply(@NotNull UUID attackerUuid, int targetEntityId) {
        Player attacker = Bukkit.getPlayer(attackerUuid);
        if (attacker == null || !attacker.isOnline() || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Entity entity = lookupEntity(attacker, targetEntityId);
        if (!(entity instanceof Damageable damageable)
                || damageable.isDead()
                || !isStillAttackable(attacker, damageable)
                || !isInReach(attacker, damageable)) {
            return;
        }

        var settings = services.config().hitReg();
        // Where OCM's damage modules govern this attacker, hand the hurt
        // pipeline vanilla-shaped damage: OCM decomposes the event into
        // vanilla components and substitutes its configured values, so it —
        // not Mental — is the damage model for the hit.
        boolean ocmShapesDamage =
                services.ocmGate().handles(OcmMechanic.TOOL_DAMAGE, attacker)
                || services.ocmGate().handles(OcmMechanic.CRITICAL_HITS, attacker);
        double amount = damageable instanceof LivingEntity living
                ? DamageCalculator.calculate(
                        attacker, living, settings.simulateCrits(), settings.legacyToolDamage(),
                        ocmShapesDamage)
                : 1.0;

        damageable.damage(amount, attacker);
    }

    /**
     * Bukkit has no public {@code World#getEntity(int)}; a bounded scan of the
     * attacker's world on the owning thread is the portable lookup, and the
     * path is already CPS-gated.
     */
    private static @Nullable Entity lookupEntity(Player attacker, int entityId) {
        for (Entity entity : attacker.getWorld().getEntities()) {
            if (entity.getEntityId() == entityId) {
                return entity;
            }
        }
        return null;
    }

    private static boolean isStillAttackable(Player attacker, Damageable target) {
        if (attacker.getWorld() != target.getWorld()) {
            return false;
        }
        if (target instanceof Player victim) {
            return victim.getGameMode() != GameMode.CREATIVE
                    && victim.getGameMode() != GameMode.SPECTATOR
                    && attacker.getWorld().getPVP();
        }
        return true;
    }

    private static boolean isInReach(Player attacker, Damageable target) {
        double dx = attacker.getLocation().getX() - target.getLocation().getX();
        double dy = attacker.getLocation().getY() - target.getLocation().getY();
        double dz = attacker.getLocation().getZ() - target.getLocation().getZ();
        return dx * dx + dy * dy + dz * dz <= MAX_REACH_SQUARED;
    }
}

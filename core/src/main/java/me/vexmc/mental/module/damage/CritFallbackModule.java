package me.vexmc.mental.module.damage;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.module.hitreg.DamageCalculator;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.jetbrains.annotations.NotNull;

/**
 * Applies the 1.8 era crit rule (×1.5 on BASE damage) for player melee hits
 * that vanilla missed because the 1.9 sprint / attack-cooldown requirements
 * were not satisfied.
 *
 * <h2>Scope: fast-path-OFF only</h2>
 * <p>When Mental's fast path is ON, {@code DamageCalculator.calculate} already
 * injects the era crit multiplier using {@link DamageCalculator#isLegacyCritical}
 * before calling {@code Damageable#damage}; the fast-path hit never reaches the
 * normal Bukkit {@link EntityDamageByEntityEvent} path. This module therefore
 * acts only when {@code hitReg.fastPath()} is {@code false} — vanilla delivered
 * the hit through its own {@code Player#attack}, which uses the 1.9 crit rules
 * (adding the sprint-exclusion and cooldown-threshold checks) and may have
 * missed a crit the era would have landed.</p>
 *
 * <h2>The era gap</h2>
 * <p>Era crit (1.8): attacker is falling, airborne, off ladder, out of water,
 * sighted, unmounted. Sprinting never excluded a crit; there was no cooldown.
 * Vanilla 1.9+: same preconditions PLUS {@code attackCooldown > 0.9} AND
 * {@code !isSprinting()}. This module multiplies the BASE damage by ×1.5 when
 * the era rule fires but the 1.9 rule did not — i.e., when the attacker was
 * sprinting or the cooldown was below threshold at the moment of the hit.</p>
 *
 * <h2>Sharpness caveat</h2>
 * <p>Multiplying the BASE modifier by ×1.5 also scales any Sharpness bonus
 * baked into BASE by vanilla (vanilla folds Sharpness into the BASE modifier).
 * In the era, the crit multiplied weapon+Strength BEFORE the Sharpness additive
 * [wn.java:761-764]; multiplying BASE here over-crits Sharpness slightly.
 * This is an accepted approximation for the fast-path-off edge case: the fast
 * path itself handles the precise ordering, and this path is a minority case on
 * any server running Mental's hit registration.</p>
 *
 * <h2>Folia note</h2>
 * <p>{@link EntityDamageByEntityEvent} fires on the victim's region thread. For
 * melee, attacker and victim are within 4 blocks — virtually always the same
 * region — so reading basic attacker state (fallDistance, isOnGround, sprinting,
 * cooldown, potion effects, vehicle) is consistent with the same reads Mental's
 * fast path makes. A strict guard is omitted because Folia does not guarantee
 * an {@code isOwnedByCurrentRegion(Entity)} overload on all supported versions
 * and because out-of-region attacker reads at melee range are an extreme edge
 * case; the precondition reads are read-only and the worst outcome if stale is a
 * missed crit, which is identical to the vanilla baseline.</p>
 *
 * <h2>Zero-touch</h2>
 * <p>When disabled (the default), this module registers no listeners and leaves
 * vanilla damage completely untouched.</p>
 */
public final class CritFallbackModule extends CombatModule implements Listener {

    public CritFallbackModule(@NotNull MentalServices services) {
        super(services,
                "old-critical-hits",
                "Old Critical Hits",
                "Applies the 1.8 era crit (×1.5 on BASE) for fast-path-off player melee "
                        + "hits vanilla missed due to the 1.9 sprint/cooldown exclusions.",
                DebugCategory.HITREG);
    }

    @Override
    public boolean configEnabled() {
        return services.config().crit().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
    }

    @Override
    protected void onDisable() {
        // Zero-touch: listeners are unregistered by CombatModule; vanilla damage
        // resumes immediately.
    }

    /* ------------------------------------------------------------------ */
    /*  The override                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Applies the era ×1.5 crit multiplier to the BASE modifier when vanilla
     * missed a crit that the 1.8 rule would have landed.
     *
     * <p>Guards:</p>
     * <ul>
     *   <li>Module enabled AND fast path OFF — fast path already handled it.</li>
     *   <li>Cause is {@code ENTITY_ATTACK} (player melee, not projectiles or mobs).</li>
     *   <li>Attacker is a {@link Player}.</li>
     *   <li>Target is a {@link LivingEntity} (era rule required {@code instanceof LivingEntity}).</li>
     *   <li>Era predicate fires ({@link DamageCalculator#isLegacyCritical}) but vanilla's
     *       1.9 sprint/cooldown requirements were not both satisfied — meaning vanilla
     *       did NOT apply a crit on this hit.</li>
     * </ul>
     */
    @SuppressWarnings("deprecation") // DamageModifier.BASE + setDamage(modifier, value): modifier always present
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(@NotNull EntityDamageByEntityEvent event) {
        // Only player melee hits (not projectiles or mob attacks).
        if (event.getCause() != DamageCause.ENTITY_ATTACK) {
            return;
        }
        Entity damager = event.getDamager();
        if (!(damager instanceof Player attacker)) {
            return;
        }
        // Era rule required target instanceof LivingEntity [wn.java:760].
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        // This module covers only the fast-path-OFF case. When the fast path is ON,
        // HitApplier → DamageCalculator already injects the era crit and the hit
        // never arrives here through the normal vanilla event path.
        if (services.config().hitReg().fastPath()) {
            return;
        }

        // Evaluate both predicates using the shared, single source of truth.
        boolean eraCrit = DamageCalculator.isLegacyCritical(attacker, event.getEntity());
        if (!eraCrit) {
            // Era would not have critted either — nothing to correct.
            return;
        }

        // The 1.9 crit rule vanilla applied: same era preconditions PLUS
        // cooldown > 0.9 AND not sprinting. If vanilla ALREADY critted (both
        // conditions met), the ×1.5 is already in the event — leave it alone.
        boolean vanillaCritConditions =
                attacker.getAttackCooldown() > 0.9f && !attacker.isSprinting();
        if (vanillaCritConditions) {
            // Era and vanilla agree — crit already applied by vanilla.
            return;
        }

        // Era would have critted but vanilla's 1.9 sprint/cooldown gate blocked it.
        // Multiply the BASE modifier (which carries the raw weapon damage; sharpness
        // is also folded into BASE by vanilla — see class doc for the caveat).
        double base = event.getDamage(DamageModifier.BASE);
        event.setDamage(DamageModifier.BASE, base * 1.5);

        debug.log(() -> "old-critical-hits: applied era ×1.5 crit on "
                + attacker.getName() + " → " + event.getEntityType()
                + " (sprint=" + attacker.isSprinting()
                + ", cooldown=" + attacker.getAttackCooldown()
                + ", fallDist=" + attacker.getFallDistance()
                + ", base=" + base + "→" + (base * 1.5) + ")");
    }
}

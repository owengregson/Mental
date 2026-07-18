package me.vexmc.mental.v5.feature.damage;

import me.vexmc.mental.kernel.math.Ct8cTables;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Combat Test 8c invulnerability frames (spec §2.4). Melee shrinks the vanilla
 * hurt window to {@code min(attackerAttackDelay, 10)} ticks — a fast sword (delay
 * 7) re-hits far sooner than vanilla's 20 — while every projectile source drops
 * to a {@code 0}-tick window so snowballs and eggs re-hit freely.
 *
 * <h2>Why {@code setMaximumNoDamageTicks}, not {@code setNoDamageTicks}</h2>
 * <p>The i-frame WINDOW is the {@code maximumNoDamageTicks} field: after a hit
 * registers, the server assigns the current counter from it
 * ({@code noDamageTicks = maximumNoDamageTicks}), and the {@code >max/2} entry
 * guard divides it. Writing the window in this pre-apply event handler makes the
 * server land THIS hit's resulting window on the CT8c value — no counter write is
 * needed. That deliberately sidesteps the 1.16.5–1.20.6 total-invuln trap
 * entirely (the {@code SpawnInvulnerability} companion arms only off a POSITIVE
 * {@code setNoDamageTicks}, the counter setter — never touched here), which is
 * exactly the "NEVER a raw positive {@code setNoDamageTicks}" mandate satisfied by
 * construction. Writing the counter positive here would instead read as
 * already-invulnerable and drop the very hit we are shaping.</p>
 *
 * <p>Zero-touch: assembled only when enabled; every other damage cause keeps its
 * vanilla window (this handler touches only player-melee and projectile hits).</p>
 */
public final class Ct8cIframesUnit implements FeatureUnit, Listener {

    /** The CT8c hard cap on the melee i-frame window (spec §2.4: {@code min(delay, 10)}). */
    private static final int MELEE_IFRAME_CAP = 10;

    @Override
    public Feature descriptor() {
        return Feature.CT8C_IFRAMES;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    /**
     * The melee i-frame window for an attacker of {@code attackSpeed}: {@code
     * min(attackDelayTicks(attackSpeed), 10)} (spec §2.4). Pure and unit-pinned
     * (sword 4.5 → 7; axe/hoe 3.5 → 10; a slow weapon clamps to 10).
     */
    public static int iframeTicks(double attackSpeed) {
        return Math.min(Ct8cTables.attackDelayTicks(attackSpeed), MELEE_IFRAME_CAP);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
            // Every projectile source → a 0-tick window, so the next projectile
            // is not blocked by this one's aftermath (spec §2.4; the consecutive
            // snowball/egg double-hit the tester asserts). i-frames from a prior
            // MELEE window still gate a projectile's ENTRY — that bypass would
            // need a pre-hit counter clear (Task G's projectile path), out of
            // this unit's lane.
            victim.setMaximumNoDamageTicks(0);
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getDamager() instanceof Player attacker) {
            double attackSpeed = DamageShaper.ct8cAttackSpeed(attacker.getInventory().getItemInMainHand());
            victim.setMaximumNoDamageTicks(iframeTicks(attackSpeed));
        }
    }
}

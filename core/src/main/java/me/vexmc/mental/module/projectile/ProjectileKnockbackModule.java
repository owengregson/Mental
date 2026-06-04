package me.vexmc.mental.module.projectile;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.ProjectileKnockbackSettings;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.module.knockback.KnockbackPipeline;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Restores 1.8 knockback from harmless projectiles. 1.9 stopped applying
 * knockback for zero-damage hits, so snowballs, eggs and ender pearls lost
 * their push. Substituting a negligible damage value — only when the hit
 * dealt exactly zero — re-engages vanilla's own projectile knockback with no
 * custom velocity math and therefore zero anticheat surface. The absorption
 * modifier is zeroed so golden-apple hearts cannot eat the trigger.
 */
public final class ProjectileKnockbackModule extends CombatModule implements Listener {

    private final KnockbackPipeline pipeline;

    public ProjectileKnockbackModule(@NotNull MentalServices services, @NotNull KnockbackPipeline pipeline) {
        super(services, "projectile-knockback", "Projectile Knockback",
                "1.7.10 knockback from snowballs, eggs, ender pearls, and arrows.",
                DebugCategory.PROJECTILE);
        this.pipeline = pipeline;
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
    protected void onDisable() {}

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

        event.setDamage(substitute);
        if (event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)) {
            event.setDamage(EntityDamageEvent.DamageModifier.ABSORPTION, 0);
        }
        debug.log(() -> event.getDamager().getType() + " hit "
                + event.getEntity().getType() + " — substituted " + substitute + " damage");
    }
}

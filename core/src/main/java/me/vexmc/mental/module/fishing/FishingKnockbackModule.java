package me.vexmc.mental.module.fishing;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.FishingKnockbackSettings;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.module.knockback.KnockbackPipeline;
import me.vexmc.mental.module.knockback.KnockbackVector;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * 1.8 fishing rod combat: a hook that strikes a living entity deals the
 * configured (negligible) damage through the normal damage pipeline — so
 * region and protection plugins keep their veto — and applies the 1.8 rod
 * knockback vector. Vanilla's reel-in pull is suppressed per policy by
 * cancelling the catch and removing the hook.
 *
 * <p>The hook is identified by {@code instanceof FishHook}, never by entity
 * type constant — the enum spelling changed across the supported range while
 * the interface did not. Velocity is applied directly after the damage call,
 * matching OCM's proven semantics for rod hits.</p>
 */
public final class FishingKnockbackModule extends CombatModule implements Listener {

    private static final String CITIZENS_NPC_METADATA = "NPC";

    private final KnockbackPipeline pipeline;

    public FishingKnockbackModule(@NotNull MentalServices services, @NotNull KnockbackPipeline pipeline) {
        super(services, "fishing-knockback", "Fishing Knockback",
                "1.7.10 rod combat: hooks damage and knock back what they hit.",
                DebugCategory.FISHING);
        this.pipeline = pipeline;
    }

    @Override
    public boolean configEnabled() {
        return services.config().fishingKnockback().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
    }

    @Override
    protected void onDisable() {}

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(@NotNull ProjectileHitEvent event) {
        FishingKnockbackSettings settings = services.config().fishingKnockback();
        if (!settings.enabled()
                || !(event.getEntity() instanceof FishHook hook)
                || !(hook.getShooter() instanceof Player rodder)
                || !(event.getHitEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (victim.getUniqueId().equals(rodder.getUniqueId())
                || rodder.getGameMode() == GameMode.CREATIVE
                || victim.hasMetadata(CITIZENS_NPC_METADATA)) {
            return;
        }
        if (!(victim instanceof Player) && !settings.knockbackNonPlayerEntities()) {
            return;
        }
        if (victim instanceof Player victimPlayer
                && (victimPlayer.getGameMode() == GameMode.CREATIVE
                        || victimPlayer.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }
        if (victim.getNoDamageTicks() > victim.getMaximumNoDamageTicks() / 2.0) {
            debug.log(() -> victim.getName() + " inside invulnerability window — rod hit skipped");
            return;
        }

        victim.damage(settings.damage(), rodder);

        Vector velocity = victim.getVelocity();
        Location hookLocation = hook.getLocation();
        Location victimLocation = victim.getLocation();
        KnockbackVector vector = RodKnockbackMath.knockback(
                velocity.getX(), velocity.getY(), velocity.getZ(),
                hookLocation.getX(), hookLocation.getY(), hookLocation.getZ(),
                victimLocation.getX(), victimLocation.getZ());
        victim.setVelocity(vector.toBukkit());

        debug.log(() -> rodder.getName() + " hooked " + describe(victim)
                + " -> (" + vector.x() + ", " + vector.y() + ", " + vector.z() + ")");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReelIn(@NotNull PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) {
            return;
        }
        FishingKnockbackSettings settings = services.config().fishingKnockback();
        Entity caught = event.getCaught();
        if (!settings.enabled() || caught == null || !settings.cancelDraggingIn().cancels(caught)) {
            return;
        }
        event.setCancelled(true);
        event.getHook().remove();
        debug.log(() -> "suppressed reel-in pull on " + describe(caught));
    }

    private static String describe(Entity entity) {
        return entity instanceof Player player
                ? player.getName()
                : entity.getType().toString().toLowerCase(java.util.Locale.ROOT);
    }
}

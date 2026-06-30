package me.vexmc.mental.module.fishing;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.FishingKnockbackSettings;
import me.vexmc.mental.config.ReelInPolicy;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.module.knockback.EntityState;
import me.vexmc.mental.module.knockback.KnockbackEngine;
import me.vexmc.mental.module.knockback.KnockbackPipeline;
import me.vexmc.mental.module.knockback.KnockbackVector;
import me.vexmc.mental.module.knockback.MeleeReentryGuard;
import me.vexmc.mental.module.ocm.OcmMechanic;
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
 * 1.7.10 rod combat. A bobber that strikes a living entity is a real
 * zero-damage hit: the negligible damage runs through the normal damage
 * pipeline (region and protection plugins keep their veto, the 20-tick
 * hurt window arms — which is why a rod hit suppresses the knockback of a
 * melee hit that follows within ten ticks), and the knock itself is the
 * engine's bare base 0.4/0.4 <em>away from where the angler stands</em>,
 * exactly the legacy {@code causeThrownDamage} direction. The hook's own
 * position never enters the math.
 *
 * <p>Reeling in honors {@link ReelInPolicy}: the legacy pull
 * ({@code motion += Δ × 0.1} plus {@code √distance × 0.08} of lift),
 * a hard cancel, or untouched vanilla.</p>
 */
public final class FishingKnockbackModule extends CombatModule implements Listener {

    private static final String CITIZENS_NPC_METADATA = "NPC";
    private static final double REEL_PULL_FACTOR = 0.1;
    private static final double REEL_LIFT_FACTOR = 0.08;

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
        try {
            handleRodHit(event);
        } catch (Throwable offRegionOrOther) {
            // On Folia a long rod cast can land the hit on the victim's region
            // while the rodder is owned by another: rodder.getGameMode() and
            // victim.damage(amount, rodder) then trip the off-region guard
            // (CraftEntity.getHandle -> ensureTickThread). Degrade to no rod knock
            // for that cast rather than letting an uncaught exception spam the log
            // and leave a half-applied hit. Paper never throws here — byte-identical.
            if (services.capabilities().folia()) {
                // Expected on Folia for a cross-region cast — keep it quiet.
                debug.log(() -> "rod hit skipped (off-region rodder on Folia): " + offRegionOrOther);
            } else {
                // Paper never throws here: a throw is a genuine fault, surface it.
                services.plugin().getLogger().warning("Rod hit handler failed: " + offRegionOrOther);
            }
        }
    }

    private void handleRodHit(@NotNull ProjectileHitEvent event) {
        FishingKnockbackSettings settings = services.config().fishingKnockback();
        if (!settings.enabled()
                || !(event.getEntity() instanceof FishHook)
                || !(event.getEntity().getShooter() instanceof Player rodder)
                || !(event.getHitEntity() instanceof LivingEntity victim)) {
            return;
        }
        // OCM's old-fishing-knockback is a whole-mechanic owner: it damages,
        // knocks and handles reel-in itself. The rodder's modeset decides.
        if (services.ocmGate().handles(OcmMechanic.FISHING_KNOCKBACK, rodder)) {
            debug.log(() -> "OCM owns rod combat for " + rodder.getName() + " — yielding");
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

        // Dealt through the vanilla pipeline as ENTITY_ATTACK by the rodder — the
        // re-entry guard keeps the melee knockback module from mistaking it for a
        // melee hit and applying the rodder's attacker self-slow / sprint clear /
        // a stray MELEE pending. The rod's own ROD-cause submit follows below.
        MeleeReentryGuard.during(() -> victim.damage(settings.damage(), rodder));
        if (victim.getNoDamageTicks() <= victim.getMaximumNoDamageTicks() / 2.0) {
            debug.log(() -> "rod hit on " + describe(victim) + " was cancelled — no knockback");
            return;
        }

        Location angler = rodder.getLocation();
        EntityState victimState = EntityState.captureVictim(victim, pipeline.ledger(), System.nanoTime());
        KnockbackVector vector = KnockbackEngine.computeBase(
                victimState, angler.getX(), angler.getZ(),
                services.knockbackProfiles().resolve(victim), null, ThreadLocalRandom.current());

        if (victim instanceof Player victimPlayer) {
            pipeline.submit(victimPlayer, vector, rodder, KnockbackPipeline.Cause.ROD);
            pipeline.ensureDelivery(victimPlayer);
        } else if (vector != null) {
            victim.setVelocity(vector.toBukkit());
        }
        debug.log(() -> rodder.getName() + " hooked " + describe(victim)
                + (vector == null
                        ? " — legacy resistance cancelled the knock"
                        : " -> (" + vector.x() + ", " + vector.y() + ", " + vector.z() + ")"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReelIn(@NotNull PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) {
            return;
        }
        FishingKnockbackSettings settings = services.config().fishingKnockback();
        Entity caught = event.getCaught();
        if (!settings.enabled() || caught == null || settings.reelIn() == ReelInPolicy.VANILLA) {
            return;
        }
        if (services.ocmGate().handles(OcmMechanic.FISHING_KNOCKBACK, event.getPlayer())) {
            debug.log(() -> "OCM owns reel-in for " + event.getPlayer().getName() + " — yielding");
            return;
        }

        event.setCancelled(true);
        event.getHook().remove();
        if (settings.reelIn() == ReelInPolicy.CANCEL) {
            debug.log(() -> "suppressed reel-in pull on " + describe(caught));
            return;
        }

        Vector pull = legacyPull(event.getPlayer().getLocation(), caught.getLocation());
        Vector current = caught instanceof LivingEntity living
                ? currentMotion(living)
                : caught.getVelocity();
        caught.setVelocity(current.add(pull));
        debug.log(() -> "legacy reel pull on " + describe(caught)
                + " -> (" + pull.getX() + ", " + pull.getY() + ", " + pull.getZ() + ")");
    }

    /** {@code Δ × 0.1} per axis plus {@code √distance × 0.08} of lift — handleHookRetraction, 1.7.10. */
    static @NotNull Vector legacyPull(@NotNull Location angler, @NotNull Location caught) {
        double deltaX = angler.getX() - caught.getX();
        double deltaY = angler.getY() - caught.getY();
        double deltaZ = angler.getZ() - caught.getZ();
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        return new Vector(
                deltaX * REEL_PULL_FACTOR,
                deltaY * REEL_PULL_FACTOR + Math.sqrt(distance) * REEL_LIFT_FACTOR,
                deltaZ * REEL_PULL_FACTOR);
    }

    private @NotNull Vector currentMotion(LivingEntity living) {
        EntityState state = EntityState.captureVictim(living, pipeline.ledger(), System.nanoTime());
        return new Vector(state.vx(), state.vy(), state.vz());
    }

    private static String describe(Entity entity) {
        return entity instanceof Player player
                ? player.getName()
                : entity.getType().toString().toLowerCase(Locale.ROOT);
    }
}

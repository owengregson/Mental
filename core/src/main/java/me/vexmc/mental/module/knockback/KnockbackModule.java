package me.vexmc.mental.module.knockback;

import java.util.concurrent.ThreadLocalRandom;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.KnockbackProfile;
import me.vexmc.mental.config.KnockbackSettings;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.module.ocm.OcmMechanic;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 1.7.10 melee knockback.
 *
 * <p>The vector is computed synchronously at {@code MONITOR} damage priority
 * (after the compensation module's {@code HIGHEST} hint pass) from the
 * victim's {@link VictimMotion} residual — the legacy server's motion fields
 * — and submitted to the {@link KnockbackPipeline}, which swaps it into the
 * velocity event later the same tick. Always server-authoritative: the
 * velocity the client receives is the one the server's own pipeline
 * produced, which is what movement-prediction anticheats verify against.</p>
 */
public final class KnockbackModule extends CombatModule implements Listener {

    private final VictimMotion ledger;
    private final KnockbackPipeline pipeline;
    private volatile KnockbackHints hints = KnockbackHints.NONE;

    public KnockbackModule(
            @NotNull MentalServices services,
            @NotNull VictimMotion ledger,
            @NotNull KnockbackPipeline pipeline) {
        super(services, "knockback", "Knockback",
                "1.7.10 melee knockback: friction on the residual, sprint and enchant bonuses, combos.",
                DebugCategory.KNOCKBACK);
        this.ledger = ledger;
        this.pipeline = pipeline;
    }

    @Override
    public boolean configEnabled() {
        return services.config().knockback().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
    }

    @Override
    protected void onDisable() {}

    /** Wired by the bootstrap once the compensation module exists. */
    public void hints(@NotNull KnockbackHints hints) {
        this.hints = hints;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // DamageModifier is the only shield-absorption signal Bukkit has
    public void onEntityDamageByEntity(@NotNull EntityDamageByEntityEvent event) {
        KnockbackSettings settings = services.config().knockback();
        if (!settings.enabled()
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof LivingEntity attacker)
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        KnockbackProfile profile = services.knockbackProfiles().resolve(victim);
        if (profile.shieldBlockingCancels()
                && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0) {
            debug.log(() -> victim.getName() + " blocked with a shield — knockback skipped");
            return;
        }
        // OCM's ownership rule for offensive mechanics: the attacker's modeset
        // decides (the victim's when a mob attacks). Where OCM's
        // old-player-knockback governs this hit, its LOWEST velocity handler
        // applies the 1.8 knock unopposed — Mental submits nothing.
        Player decider = attacker instanceof Player attackerPlayer ? attackerPlayer : victim;
        if (services.ocmGate().handles(OcmMechanic.MELEE_KNOCKBACK, decider)) {
            debug.log(() -> "OCM owns melee knockback for " + decider.getName() + " — yielding");
            return;
        }

        Double victimYOverride = hints.takeYOverride(victim.getUniqueId());
        EntityState victimState = EntityState.captureVictim(victim, ledger, System.nanoTime());
        // The authoritative read spends the attacker's sprint freshness — the
        // pre-send only peeked, so both paths see the same answer this tick.
        boolean freshSprint = attacker instanceof Player attackerPlayer
                && attackerPlayer.isSprinting()
                && services.sprintTracker().consumeFresh(attackerPlayer.getUniqueId());
        KnockbackVector vector = KnockbackEngine.compute(
                EntityState.capture(attacker), victimState, profile, victimYOverride,
                ThreadLocalRandom.current(), freshSprint);

        pipeline.submit(victim, vector, attacker, KnockbackPipeline.Cause.MELEE);
        debug.log(() -> vector == null
                ? "legacy resistance cancelled knockback for " + victim.getName()
                : "queued for " + victim.getName()
                        + (victimYOverride != null ? " [compensated vy=" + victimYOverride + "]" : "")
                        + " residual=(" + victimState.vx() + ", " + victimState.vy() + ", " + victimState.vz() + ")"
                        + " -> (" + vector.x() + ", " + vector.y() + ", " + vector.z() + ")");
    }
}

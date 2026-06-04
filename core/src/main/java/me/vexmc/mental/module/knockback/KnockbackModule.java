package me.vexmc.mental.module.knockback;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.api.event.KnockbackApplyEvent;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.KnockbackSettings;
import me.vexmc.mental.engine.CombatModule;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 1.8.x melee knockback.
 *
 * <p>The vector is computed synchronously at {@code MONITOR} damage priority
 * (after the compensation module's {@code HIGHEST} hint pass) and stashed
 * per-victim; {@code PlayerVelocityEvent} later in the same tick swaps it in.
 * Always server-authoritative — the velocity the client receives is the one
 * the server's own pipeline produced, which is what movement-prediction
 * anticheats verify against.</p>
 *
 * <p>Stashed vectors expire after two ticks: a hit whose velocity event never
 * fired (plugin cancellation, dimension change) must not leak onto the next
 * unrelated knockback.</p>
 */
public final class KnockbackModule extends CombatModule implements Listener {

    private static final long PENDING_EXPIRY_NANOS = 100_000_000L; // 2 ticks

    private record Pending(@NotNull KnockbackVector vector, @Nullable LivingEntity attacker, long stampNanos) {

        boolean expired(long nowNanos) {
            return nowNanos - stampNanos > PENDING_EXPIRY_NANOS;
        }
    }

    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private volatile KnockbackHints hints = KnockbackHints.NONE;

    public KnockbackModule(@NotNull MentalServices services) {
        super(services, "knockback", "Knockback",
                "1.8-style melee knockback with friction, sprint and enchant bonuses.",
                DebugCategory.KNOCKBACK);
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
    protected void onDisable() {
        pending.clear();
    }

    /** Wired by the bootstrap once the compensation module exists. */
    public void hints(@NotNull KnockbackHints hints) {
        this.hints = hints;
    }

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
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
        if (settings.shieldBlockingCancels()
                && event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0) {
            debug.log(() -> victim.getName() + " blocked with a shield — knockback skipped");
            return;
        }

        Double victimYOverride = hints.takeYOverride(victim.getUniqueId());
        KnockbackVector vector = KnockbackEngine.compute(
                EntityState.capture(attacker), EntityState.capture(victim), settings, victimYOverride);

        pending.put(victim.getUniqueId(), new Pending(vector, attacker, System.nanoTime()));
        debug.log(() -> "queued for " + victim.getName()
                + (victimYOverride != null ? " [compensated vy=" + victimYOverride + "]" : "")
                + " -> (" + vector.x() + ", " + vector.y() + ", " + vector.z() + ")");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerVelocity(@NotNull PlayerVelocityEvent event) {
        if (!services.config().knockback().enabled()) {
            return;
        }
        Pending stored = pending.remove(event.getPlayer().getUniqueId());
        if (stored == null) {
            return;
        }
        if (stored.expired(System.nanoTime())) {
            debug.log(() -> "dropped expired vector for " + event.getPlayer().getName());
            return;
        }

        KnockbackApplyEvent apply = new KnockbackApplyEvent(
                event.getPlayer(), stored.attacker(), stored.vector().toBukkit());
        if (!apply.callEvent()) {
            debug.log(() -> "apply event cancelled for " + event.getPlayer().getName());
            return;
        }
        event.setVelocity(apply.velocity());
        debug.log(() -> "applied to " + event.getPlayer().getName());
    }
}

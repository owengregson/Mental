package me.vexmc.mental.module.knockback;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.api.event.KnockbackApplyEvent;
import me.vexmc.mental.common.debug.DebugCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The single owner of knockback delivery and of the {@link VictimMotion}
 * ledger. Combat modules are pure vector computers: they {@link #submit} and
 * this pipeline swaps the vector into the victim's velocity event, fires the
 * {@link KnockbackApplyEvent} API, and records what actually shipped.
 *
 * <p>Centralizing the apply step is what keeps sources composable: a rod hit
 * runs through the damage pipeline (which the melee module also observes),
 * and whichever module submitted <em>last</em> for the victim wins the tick —
 * matching vanilla, where one hit produces exactly one knockback.</p>
 *
 * <p>The ledger records at {@code MONITOR} for <em>every</em> uncancelled
 * velocity event — Mental's applies, vanilla knockback Mental left alone,
 * other plugins' velocities — because the legacy server fields this ledger
 * replicates were mutated by all of them too. The one deliberate exception
 * implements the era switch: with {@code combos} off, melee applies are not
 * recorded, which is precisely 1.8.9's send-then-revert (rod and projectile
 * residuals still feed the next melee hit; melee no longer feeds itself).</p>
 *
 * <p>Pending vectors expire after two ticks: a hit whose velocity event never
 * fired (plugin cancellation, dimension change) must not leak onto the next
 * unrelated knockback.</p>
 */
public final class KnockbackPipeline implements Listener {

    /** What computed a pending vector; decides ledger semantics per era. */
    public enum Cause {
        MELEE,
        ROD,
        PROJECTILE,
        ARROW
    }

    private static final long PENDING_EXPIRY_NANOS = 100_000_000L; // 2 ticks
    private static final long APPLIED_TAG_TTL_NANOS = 25_000_000L; // half a tick

    private record Pending(
            @Nullable KnockbackVector vector,
            @Nullable LivingEntity attacker,
            @NotNull Cause cause,
            long stampNanos) {

        boolean expired(long nowNanos) {
            return nowNanos - stampNanos > PENDING_EXPIRY_NANOS;
        }
    }

    private record AppliedTag(@NotNull Cause cause, long stampNanos) {}

    private final MentalServices services;
    private final VictimMotion ledger;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AppliedTag> applied = new ConcurrentHashMap<>();

    public KnockbackPipeline(@NotNull MentalServices services, @NotNull VictimMotion ledger) {
        this.services = services;
        this.ledger = ledger;
    }

    public @NotNull VictimMotion ledger() {
        return ledger;
    }

    /**
     * Queues a vector for the victim's next velocity event this tick. A null
     * vector means "suppress the knockback entirely" — the legacy resistance
     * roll succeeded, so the event is cancelled and no packet leaves.
     */
    public void submit(
            @NotNull Player victim,
            @Nullable KnockbackVector vector,
            @Nullable LivingEntity attacker,
            @NotNull Cause cause) {
        pending.put(victim.getUniqueId(), new Pending(vector, attacker, cause, System.nanoTime()));
    }

    /**
     * Safety net for sources vanilla never arms a velocity event for (modern
     * ender pearls): one tick later, if the submission is still pending, set
     * the velocity directly — which arms the event this pipeline then serves.
     */
    public void ensureDelivery(@NotNull Player victim) {
        UUID victimId = victim.getUniqueId();
        services.scheduling().runOn(
                victim,
                () -> {
                    Pending stored = pending.get(victimId);
                    if (stored != null && stored.vector() != null && !stored.expired(System.nanoTime())) {
                        victim.setVelocity(stored.vector().toBukkit());
                    }
                },
                () -> pending.remove(victimId));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerVelocity(@NotNull PlayerVelocityEvent event) {
        UUID victimId = event.getPlayer().getUniqueId();
        Pending stored = pending.remove(victimId);
        if (stored == null) {
            return;
        }
        long now = System.nanoTime();
        if (stored.expired(now)) {
            debug(() -> "dropped expired " + stored.cause() + " vector for " + event.getPlayer().getName());
            return;
        }
        if (stored.vector() == null) {
            event.setCancelled(true);
            debug(() -> "suppressed knockback for " + event.getPlayer().getName()
                    + " (legacy resistance roll)");
            return;
        }

        KnockbackApplyEvent apply = new KnockbackApplyEvent(
                event.getPlayer(), stored.attacker(), stored.vector().toBukkit());
        if (!apply.callEvent()) {
            debug(() -> "apply event cancelled for " + event.getPlayer().getName());
            return;
        }
        event.setVelocity(apply.velocity());
        applied.put(victimId, new AppliedTag(stored.cause(), now));
        debug(() -> "applied " + stored.cause() + " knockback to " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocityRecord(@NotNull PlayerVelocityEvent event) {
        UUID victimId = event.getPlayer().getUniqueId();
        long now = System.nanoTime();

        AppliedTag tag = applied.remove(victimId);
        Cause cause = tag != null && now - tag.stampNanos() < APPLIED_TAG_TTL_NANOS ? tag.cause() : null;
        if (cause == Cause.MELEE && !services.config().knockback().combos()) {
            return; // 1.8.9 send-then-revert: the melee residual never persists
        }

        Vector velocity = event.getVelocity();
        ledger.record(victimId, velocity.getX(), velocity.getY(), velocity.getZ(), now);
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(@NotNull PlayerDeathEvent event) {
        forget(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onRespawn(@NotNull PlayerRespawnEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    public void clear() {
        pending.clear();
        applied.clear();
        ledger.clear();
    }

    private void forget(UUID victimId) {
        pending.remove(victimId);
        applied.remove(victimId);
        ledger.forget(victimId);
    }

    private void debug(@NotNull java.util.function.Supplier<String> message) {
        services.debug().log(DebugCategory.KNOCKBACK, message);
    }
}

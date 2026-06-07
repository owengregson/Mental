package me.vexmc.mental.module.knockback;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.api.event.KnockbackApplyEvent;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.KnockbackDelivery;
import me.vexmc.mental.config.KnockbackProfile;
import me.vexmc.mental.platform.Attributes;
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
            @Nullable KnockbackVector preDelivered,
            boolean wireDelivered,
            @Nullable LivingEntity attacker,
            @NotNull Cause cause,
            boolean groundedAtSubmit,
            long stampNanos) {

        boolean expired(long nowNanos) {
            return nowNanos - stampNanos > PENDING_EXPIRY_NANOS;
        }
    }

    /** {@code grounded} is the launch state captured at SUBMIT — the era's pre-move friction state. */
    private record AppliedTag(@NotNull Cause cause, boolean grounded, long stampNanos) {}

    private final MentalServices services;
    private final VictimMotion ledger;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AppliedTag> applied = new ConcurrentHashMap<>();
    private volatile @Nullable VelocityDuplicateSuppressor suppressor;

    public KnockbackPipeline(@NotNull MentalServices services, @NotNull VictimMotion ledger) {
        this.services = services;
        this.ledger = ledger;
    }

    /** Wired by the bootstrap once PacketEvents is initialized. */
    public void suppressor(@Nullable VelocityDuplicateSuppressor suppressor) {
        this.suppressor = suppressor;
    }

    public @NotNull VictimMotion ledger() {
        return ledger;
    }

    /**
     * Queues a vector for the victim's next velocity event this tick. A null
     * vector means "suppress the knockback entirely" — the legacy resistance
     * roll succeeded, so the event is cancelled and no packet leaves.
     */
    @SuppressWarnings("deprecation") // Entity#isOnGround: the hit-time state drives the era delivery decay
    public void submit(
            @NotNull Player victim,
            @Nullable KnockbackVector vector,
            @Nullable LivingEntity attacker,
            @NotNull Cause cause) {
        // Ground state is captured NOW — from the machine's own view: the
        // velocity event fires after the victim's own physics tick may have
        // lifted them (always, for the tester's fake players) and modern
        // servers flip the flag on the hit tick itself, but the era tracker
        // decayed with the victim's state AT the knock.
        pending.put(victim.getUniqueId(), new Pending(
                vector, null, false, attacker, cause,
                ledger.groundedView(victim.getUniqueId(), victim.isOnGround()),
                System.nanoTime()));
    }

    /**
     * Queues a vector whose wire delivery already happened (the netty
     * pre-send): {@code vector} is the formula value the API sees,
     * {@code preDelivered} the delivery-decayed velocity the client received.
     * If no API listener modifies the apply event, the velocity event adopts
     * {@code preDelivered} and the duplicate outbound packet is suppressed —
     * one wire stamp per hit, exactly like the era servers.
     */
    @SuppressWarnings("deprecation") // Entity#isOnGround (see submit)
    public void submitPreDelivered(
            @NotNull Player victim,
            @NotNull KnockbackVector vector,
            @NotNull KnockbackVector preDelivered,
            @Nullable LivingEntity attacker) {
        pending.put(victim.getUniqueId(), new Pending(
                vector, preDelivered, true, attacker, Cause.MELEE,
                ledger.groundedView(victim.getUniqueId(), victim.isOnGround()),
                System.nanoTime()));
    }

    /**
     * Queues a registration-time vector for a victim with no connection
     * (in-process bots, synthetic players): nothing was pre-sent — there is
     * no wire to carry it — but the registration-time compute read the
     * victim's snapshot at the era's in-order processing moment, so its
     * values are the era answer the authoritative pass must adopt rather
     * than recompute against post-landing state. The velocity event ships
     * {@code shipped} normally; no duplicate exists to suppress.
     */
    @SuppressWarnings("deprecation") // Entity#isOnGround (see submit)
    public void submitPinned(
            @NotNull Player victim,
            @NotNull KnockbackVector vector,
            @NotNull KnockbackVector shipped,
            @Nullable LivingEntity attacker) {
        pending.put(victim.getUniqueId(), new Pending(
                vector, shipped, false, attacker, Cause.MELEE,
                ledger.groundedView(victim.getUniqueId(), victim.isOnGround()),
                System.nanoTime()));
    }

    /** Drops a pending vector — a protection plugin cancelled the hit it belonged to. */
    public void withdraw(@NotNull Player victim) {
        pending.remove(victim.getUniqueId());
    }

    /**
     * Whether a live pre-delivered vector is queued for the victim — the
     * authoritative damage pass adopts it instead of recomputing, so both
     * stamps of one hit can never disagree.
     */
    public boolean hasFreshPreDelivered(@NotNull Player victim) {
        Pending stored = pending.get(victim.getUniqueId());
        return stored != null && stored.preDelivered() != null && !stored.expired(System.nanoTime());
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
    @SuppressWarnings("deprecation") // Entity#isOnGround: the client-reported value selects the delivery decay
    public void onPlayerVelocity(@NotNull PlayerVelocityEvent event) {
        Player victim = event.getPlayer();
        UUID victimId = victim.getUniqueId();
        Pending stored = pending.remove(victimId);
        if (stored == null) {
            return;
        }
        long now = System.nanoTime();
        if (stored.expired(now)) {
            debug(() -> "dropped expired " + stored.cause() + " vector for " + victim.getName());
            return;
        }
        if (stored.vector() == null) {
            event.setCancelled(true);
            debug(() -> "suppressed knockback for " + victim.getName()
                    + " (legacy resistance roll)");
            return;
        }

        KnockbackApplyEvent apply = new KnockbackApplyEvent(
                victim, stored.attacker(), stored.vector().toBukkit());
        if (!apply.callEvent()) {
            debug(() -> "apply event cancelled for " + victim.getName());
            return;
        }

        Vector shipped;
        if (stored.preDelivered() != null && apply.velocity().equals(stored.vector().toBukkit())) {
            // The registration-time value wins: for wire-delivered knocks the
            // client already has it and the duplicate outbound packet is
            // cancelled — one stamp per hit, like the era servers; for pinned
            // knocks (no connection to pre-send on) the same era-moment value
            // ships through this event normally, nothing to suppress. An API
            // listener changing the vector falls through to a corrective send.
            shipped = stored.preDelivered().toBukkit();
            if (stored.wireDelivered() && suppressor != null) {
                suppressor.armFor(victim);
            }
        } else {
            shipped = deliveryAdjusted(
                    victim, apply.velocity(), stored.cause(), stored.groundedAtSubmit());
        }
        event.setVelocity(shipped);
        applied.put(victimId, new AppliedTag(stored.cause(), stored.groundedAtSubmit(), now));
        debug(() -> "applied " + stored.cause() + " knockback to " + victim.getName()
                + " -> (" + shipped.getX() + ", " + shipped.getY() + ", " + shipped.getZ() + ")");
    }

    /**
     * The opt-in later-joiner wire ({@link KnockbackDelivery#TRACKER_DECAYED}):
     * the vector ships one victim physics tick late, so it decays once with
     * friction from the victim's ground state at the knock. TRACKER ships the
     * full stamp — vanilla's tracker only decayed when the victim's connection
     * slot ran between the hit and the send (join-order bimodal, measured).
     */
    private @NotNull Vector deliveryAdjusted(
            Player victim, Vector velocity, Cause cause, boolean groundedAtSubmit) {
        KnockbackProfile profile = services.knockbackProfiles().resolve(victim);
        KnockbackDelivery delivery = cause == Cause.MELEE
                ? profile.meleeDelivery()
                : profile.projectileDelivery();
        if (delivery != KnockbackDelivery.TRACKER_DECAYED) {
            return velocity;
        }
        VictimMotion.Motion decayed = VictimMotion.decayOnce(
                velocity.getX(), velocity.getY(), velocity.getZ(),
                groundedAtSubmit,
                GroundFriction.under(victim),
                Attributes.valueOr(victim, Attributes.gravity(), VictimMotion.DEFAULT_GRAVITY));
        return new Vector(decayed.vx(), decayed.vy(), decayed.vz());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // Entity#isOnGround: the client-reported value selects the segment drag
    public void onPlayerVelocityRecord(@NotNull PlayerVelocityEvent event) {
        UUID victimId = event.getPlayer().getUniqueId();
        long now = System.nanoTime();

        AppliedTag tag = applied.remove(victimId);
        boolean tagged = tag != null && now - tag.stampNanos() < APPLIED_TAG_TTL_NANOS;
        Cause cause = tagged ? tag.cause() : null;
        if (cause == Cause.MELEE
                && !services.knockbackProfiles().resolve(event.getPlayer()).combos()) {
            return; // 1.8.9 send-then-revert: the melee residual never persists
        }

        Vector velocity = event.getVelocity();
        // The residual's grounded segment decays at the block-under-feet
        // slipperiness (the era ground physics): a knock recorded on packed
        // ice survives at ×0.8918/tick and compounds into the next hit —
        // measured on real 1.7.10, ice raises chain hits 0.4 → 0.48 → 0.49
        // where stone re-kills the residual every flight. The launch state
        // comes from SUBMIT time (the era pre-move friction: the victim had
        // not moved yet) — the live flag has already flipped, and the
        // machine view can lose the race against the client's first risen
        // packet on the netty tap.
        boolean grounded = tagged
                ? tag.grounded()
                : ledger.groundedView(victimId, event.getPlayer().isOnGround());
        ledger.record(victimId, velocity.getX(), velocity.getY(), velocity.getZ(),
                grounded,
                GroundFriction.under(event.getPlayer()),
                now, VictimMotion.NO_TICK);
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

    private void debug(@NotNull Supplier<String> message) {
        services.debug().log(DebugCategory.KNOCKBACK, message);
    }
}

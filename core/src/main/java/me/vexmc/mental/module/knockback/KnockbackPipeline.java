package me.vexmc.mental.module.knockback;

import java.util.UUID;
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

    /**
     * The pre-delivered pending must live from the netty pre-send until the
     * entity tracker fires {@code PlayerVelocityEvent} — the event where
     * {@link #onPlayerVelocity} serves the adopted vector and arms the
     * duplicate suppressor. On Paper that whole netty→damage→entity-tracking
     * chain completes inside one ~50&nbsp;ms tick, so two ticks always covers
     * it. On Folia the netty→region-next-tick→entity-tracking-phase chain spans
     * several region ticks; with the Paper window the adopted vector expires
     * before the tracker fires, {@code onPlayerVelocity} drops it without
     * serving or suppressing, and vanilla's own velocity packet ships — which
     * for an airborne victim carries no vertical boost (the falling {@code y}),
     * i.e. DOWNWARD knockback on the second combo hit. A wider Folia window
     * keeps the wire stamp authoritative without changing Paper's era timing.
     */
    private static final long PAPER_PENDING_EXPIRY_NANOS = 100_000_000L; // 2 ticks
    private static final long FOLIA_PENDING_EXPIRY_NANOS = 300_000_000L; // ~6 ticks of headroom

    /** The pending lifetime for this platform; see the constants above. */
    static long pendingExpiryNanos(boolean folia) {
        return folia ? FOLIA_PENDING_EXPIRY_NANOS : PAPER_PENDING_EXPIRY_NANOS;
    }

    private final MentalServices services;
    private final VictimMotion ledger;
    private final long pendingExpiryNanos;
    private final PendingStore pending = new PendingStore();
    private final AppliedTagStore applied = new AppliedTagStore();
    private volatile @Nullable VelocityDuplicateSuppressor suppressor;

    public KnockbackPipeline(@NotNull MentalServices services, @NotNull VictimMotion ledger) {
        this.services = services;
        this.ledger = ledger;
        this.pendingExpiryNanos = pendingExpiryNanos(services.capabilities().folia());
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
        enqueue(victim.getUniqueId(), new Pending(
                vector, null, false, attacker, cause,
                ledger.groundedView(victim.getUniqueId(), victim.isOnGround()),
                System.nanoTime()));
    }

    /**
     * Appends a pending to the victim's FIFO and logs an over-cap eviction (the
     * cap only bites a stuck victim that never fires a velocity event).
     */
    private void enqueue(@NotNull UUID victimId, @NotNull Pending p) {
        Pending evicted = pending.enqueue(victimId, p);
        if (evicted != null) {
            Cause droppedCause = evicted.cause();
            debug(() -> "evicted oldest " + droppedCause + " pending for " + victimId
                    + " (cap " + PendingStore.CAP + " unconsumed knocks queued)");
        }
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
        enqueue(victim.getUniqueId(), new Pending(
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
        enqueue(victim.getUniqueId(), new Pending(
                vector, shipped, false, attacker, Cause.MELEE,
                ledger.groundedView(victim.getUniqueId(), victim.isOnGround()),
                System.nanoTime()));
    }

    /** Drops every pending vector — a protection plugin cancelled the hit it belonged to. */
    public void withdraw(@NotNull Player victim) {
        pending.withdrawAll(victim.getUniqueId());
    }

    /**
     * Drops only the pendings queued by {@code onlyIfCause} — so a melee cancel
     * does not evict a concurrent projectile/arrow knock sharing the victim's
     * FIFO.
     */
    public void withdraw(@NotNull Player victim, @NotNull Cause onlyIfCause) {
        pending.withdrawCause(victim.getUniqueId(), onlyIfCause);
    }

    /**
     * Whether a live pre-delivered vector registered by {@code attacker} is
     * queued for the victim — the authoritative damage pass adopts it instead of
     * recomputing, so both stamps of one hit can never disagree. Scoped to the
     * registering attacker: the wider Folia window keeps a consumed-late pending
     * live longer, so a lingering pending must never be adopted by a DIFFERENT
     * attacker's hit (which would ship hit-1's owner/direction for hit-2). The
     * connectionless pinned case carries no attacker to scope on.
     */
    public boolean hasFreshPreDelivered(@NotNull Player victim, @Nullable LivingEntity attacker) {
        UUID hitAttacker = attacker != null ? attacker.getUniqueId() : null;
        return pending.hasFreshPreDelivered(
                victim.getUniqueId(), System.nanoTime(), pendingExpiryNanos, hitAttacker);
    }

    /** Whether a pre-delivered pending registered by {@code stored} is adoptable by {@code hit}. */
    static boolean sameAttacker(@Nullable UUID stored, @Nullable UUID hit) {
        if (stored == null || hit == null) {
            return stored == hit;
        }
        return stored.equals(hit);
    }

    /**
     * Safety net for sources vanilla never arms a velocity event for (modern
     * ender pearls, thrown projectiles): one tick later, if the submission for
     * {@code cause} is still pending, set the velocity directly — which arms the
     * event this pipeline then serves.
     *
     * <p>It targets the newest pending of {@code cause} rather than the FIFO
     * head, promoting it so the velocity event below consumes exactly it: an
     * unrelated lingering knock (e.g. a pre-sent melee invuln then absorbed)
     * could otherwise sit at the head and the projectile knock would be orphaned
     * unserved. It is not gated on the adoption-window expiry — on Folia the
     * next-region-tick defer can exceed that window and the knock would silently
     * vanish; leak protection is the bounded FIFO, the withdraw() on cancel, and
     * the retired callback, not a clock.</p>
     */
    public void ensureDelivery(@NotNull Player victim, @NotNull Cause cause) {
        UUID victimId = victim.getUniqueId();
        services.scheduling().runOn(
                victim,
                () -> {
                    Pending target = pending.promoteNewestOfCause(victimId, cause);
                    if (target != null && target.vector() != null) {
                        victim.setVelocity(target.vector().toBukkit());
                    }
                },
                () -> pending.withdrawAll(victimId));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerVelocity(@NotNull PlayerVelocityEvent event) {
        Player victim = event.getPlayer();
        UUID victimId = victim.getUniqueId();
        // First action: drop any tag a prior event left (one cancelled between
        // HIGH and MONITOR). This victim's MONITOR below then reads only what
        // THIS event sets, which retires the wall-clock TTL a GC pause could
        // outlast.
        applied.clearFor(victimId);
        long now = System.nanoTime();
        // Take the OLDEST live pending, dropping any expired heads ahead of it:
        // overlapping combo hits each pair with their own velocity event in
        // arrival order. A lone pending (the universal era-config case) makes
        // this exactly the old single-slot take.
        Pending stored = pending.pollLive(victimId, now, pendingExpiryNanos,
                n -> debug(() -> "dropped " + n + " expired pending(s) for " + victim.getName()));
        if (stored == null) {
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
                suppressor.armFor(victim, shipped);
            }
        } else {
            shipped = deliveryAdjusted(
                    victim, apply.velocity(), stored.cause(), stored.groundedAtSubmit());
        }
        event.setVelocity(shipped);
        applied.setFor(victimId, new AppliedTag(stored.cause(), stored.groundedAtSubmit()));
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

        // The HIGH handler cleared this slot on entry and set it only if it
        // applied a vector for THIS event, so a present tag is unambiguously
        // ours — no staleness window, no clock.
        AppliedTag tag = applied.takeFor(victimId);
        boolean tagged = tag != null;
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
        pending.forget(victimId);
        applied.forget(victimId);
        ledger.forget(victimId);
    }

    private void debug(@NotNull Supplier<String> message) {
        services.debug().log(DebugCategory.KNOCKBACK, message);
    }
}

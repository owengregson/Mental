package me.vexmc.mental.module.knockback;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The attacker's sprint state, kept two ways: the {@code wtap-extra}
 * freshness ledger (Bukkit toggle events, every path) and the <b>wire
 * view</b> — the flag replayed in packet-arrival order for the
 * {@code wtap-registration} module.
 *
 * <p><b>Freshness</b>: an attacker is "fresh" when they have (re-)engaged
 * sprint since their last sprint hit: the START_SPRINTING that follows a
 * w-tap (or the server's own post-hit sprint reset) arms the flag, and the
 * next sprint hit consumes it. A sprint held continuously across hits
 * therefore reads as not-fresh — exactly WindSpigot's
 * {@code isExtraKnockback} branch, derived from the only honest server-side
 * signal there is.</p>
 *
 * <p><b>The wire view</b>: the era server applied inbound packets in arrival
 * order, so an attack always read its attacker's sprint flag with every
 * earlier STOP/START already applied — a w-tap registered no matter how
 * little wall-clock separated the re-press from the click. The fast path
 * registers attacks mid-tick, ahead of the main thread's packet
 * application, so the tick-frozen snapshot it would otherwise read is up to
 * a tick OLDER than the era contract: a fast w-tap shipped plain, an s-tap
 * kept a bonus the era denied. The wire view replays the entity-action
 * packets at arrival (fed by the ground tap, written and read on the
 * attacker's own netty thread — program order with their ATTACK by
 * construction) and mirrors vanilla's in-attack sprint clear, restoring the
 * in-order read at any tap speed. Taps shorter than one client tick never
 * produce packets at all — that floor is the client's, identical on era
 * servers.</p>
 *
 * <p>Bukkit toggle events deliberately never write the wire view: they fire
 * at packet <em>application</em>, so a boundary-applied STOP would overwrite
 * a newer wire START with older information. Server-initiated
 * {@code setSprinting} drift is instead adopted by {@link #reconcileWire}
 * once the wire has been quiet — fresh wire writes always win the
 * within-tick window they exist for.</p>
 *
 * <p>Observation only: with every profile's {@code wtap-extra} disabled and
 * the {@code wtap-registration} module off this tracker changes nothing,
 * preserving the zero-touch guarantee. Writes happen on owning threads
 * (Bukkit events, reconcile) and netty threads (the tap); the concurrent
 * maps make the cross-thread reads safe.</p>
 */
public final class SprintTracker implements Listener {

    /**
     * How long a registration-time attack verdict stays usable. On Paper a
     * faithful client's post-attack sprint drop lands within ~a tick, so the
     * netty→main damage chain always completes inside the window. On Folia that
     * chain spans several region ticks — the same latency the pre-delivered
     * pending window was widened for ({@code KnockbackPipeline.FOLIA_PENDING_EXPIRY_NANOS})
     * — so the verdict must outlive it, or {@link #takeAttackVerdict} returns
     * {@code null} and the authoritative pass falls back to the already-cleared
     * live sprint flag, skipping the attacker self-slow and leaking unspent
     * freshness into the next trade hit.
     */
    private static final long PAPER_ATTACK_STAMP_TTL_NANOS = 150_000_000L; // ~a tick
    private static final long FOLIA_ATTACK_STAMP_TTL_NANOS = 300_000_000L; // matches the Folia pending window

    /** The attack-verdict lifetime for this platform; see the constants above. */
    static long attackStampTtlNanos(boolean folia) {
        return folia ? FOLIA_ATTACK_STAMP_TTL_NANOS : PAPER_ATTACK_STAMP_TTL_NANOS;
    }

    private final long attackStampTtlNanos;

    /** Paper-default tracker (used by the unit tests). */
    public SprintTracker() {
        this(false);
    }

    public SprintTracker(boolean folia) {
        this.attackStampTtlNanos = attackStampTtlNanos(folia);
    }

    /**
     * Wire silence after which the server's own flag wins a disagreement —
     * long enough that every within-tick ordering the wire exists to
     * preserve stays untouched, short enough that plugin-granted sprint
     * (which never crosses the wire) converges within a few ticks.
     */
    static final long WIRE_QUIET_NANOS = 150_000_000L;

    /**
     * The sprint answer a registered attack saw, carried from the netty
     * registration to the owning-thread damage pass. {@code fresh} is the
     * wire-ordered freshness, or {@code null} when no wire view existed at
     * registration — the authoritative pass then falls back to consuming
     * the Bukkit-armed ledger, today's behavior.
     */
    public record AttackVerdict(boolean sprinting, @Nullable Boolean fresh, long nanos) {}

    /** The wire view at one instant: the in-order flag plus armed freshness. */
    public record WireVerdict(boolean sprinting, boolean fresh) {}

    private record AttackStamp(boolean sprinting, @Nullable Boolean fresh, long nanos) {}

    private record WireState(boolean sprinting, boolean armed, long nanos) {}

    private final Set<UUID> fresh = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, AttackStamp> attackStamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, WireState> wire = new ConcurrentHashMap<>();
    /**
     * Attackers whose post-hit {@code setSprinting(false)} has been issued but
     * not yet applied. On Folia that clear is deferred to the attacker's region
     * and lands several ticks late, leaving the live sprint flag stale-high in
     * the meantime; {@link #reconcileWire} consults this so it never readopts
     * that stale flag and resurrects a sprint the hit already cleared. The value
     * is the clear's registration nanos (unused today beyond presence).
     */
    private final ConcurrentHashMap<UUID, Long> pendingClear = new ConcurrentHashMap<>();
    /**
     * The client's RAW sprint flag, straight from its START/STOP entity-action
     * packets — never touched by the post-hit clears the wire view and the
     * freshness ledger apply. It is the only signal that survives Mental's own
     * {@code setSprinting(false)} after a sprint hit, so it alone can answer "is
     * this client actually sprinting right now?" — the sword-block sprint reset
     * consults it so a stationary, defensive block never grants a phantom bonus.
     */
    private final Set<UUID> clientSprinting = ConcurrentHashMap.newKeySet();

    /** Flipped by the wtap-registration module; gates reads, never writes. */
    private volatile boolean consultWire;

    @EventHandler
    public void onToggleSprint(@NotNull PlayerToggleSprintEvent event) {
        if (event.isSprinting()) {
            fresh.add(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        fresh.remove(event.getPlayer().getUniqueId());
        attackStamps.remove(event.getPlayer().getUniqueId());
        wire.remove(event.getPlayer().getUniqueId());
        clientSprinting.remove(event.getPlayer().getUniqueId());
        pendingClear.remove(event.getPlayer().getUniqueId());
    }

    /* ------------------------------------------------------------------ */
    /*  The wire view (wtap-registration)                                  */
    /* ------------------------------------------------------------------ */

    /** Gates {@link #peekWire} on the module's live state. */
    public void consultWire(boolean enabled) {
        this.consultWire = enabled;
    }

    /**
     * One entity-action packet, at arrival on the player's netty thread.
     * A START both sets the flag and arms freshness (the re-engage IS the
     * w-tap signal); a STOP only drops the flag — freshness, once armed,
     * is spent by a hit, never by the release half of the tap.
     */
    public void onWireSprint(@NotNull UUID player, boolean sprinting, long nanos) {
        wire.compute(player, (id, state) -> new WireState(
                sprinting,
                sprinting || (state != null && state.armed()),
                nanos));
        if (sprinting) {
            clientSprinting.add(player);
        } else {
            clientSprinting.remove(player);
        }
    }

    /** Whether the client's raw sprint flag (its last START/STOP) is set. */
    public boolean isClientSprinting(@NotNull UUID player) {
        return clientSprinting.contains(player);
    }

    /**
     * Re-engages sprint the way a 1.7/1.8 sword block did — a deliberate sprint
     * reset, driven from the owning thread rather than a wire packet. Sets the
     * wire flag and arms both freshness ledgers, standing in for the
     * START_SPRINTING a modern client omits around an item-use block (it keeps
     * the sprint flag through the block, so the era STOP/START never crosses the
     * wire and block-hitting silently stopped re-arming the sprint bonus). The
     * caller (the sword-block module) gates this on {@link #isClientSprinting}
     * so only a genuinely sprinting attacker is re-armed; a later real
     * STOP_SPRINTING still overrides it.
     */
    public void armSprintReset(@NotNull UUID player, long nanos) {
        wire.compute(player, (id, state) -> new WireState(true, true, nanos));
        fresh.add(player);
    }

    /**
     * The owning-thread per-tick reconcile: seeds first-sighted players from
     * the live flag and adopts server-initiated changes (plugin
     * {@code setSprinting}, which never crosses the wire) once the wire has
     * been quiet for {@link #WIRE_QUIET_NANOS}. A fresh wire write always
     * wins the within-tick disagreement window — that window is the module.
     */
    public void reconcileWire(@NotNull UUID player, boolean liveSprinting, long nanos) {
        wire.compute(player, (id, state) -> {
            if (state == null) {
                return new WireState(liveSprinting, false, nanos);
            }
            if (state.sprinting() != liveSprinting && nanos - state.nanos() > WIRE_QUIET_NANOS) {
                // A post-hit setSprinting(false) issued but not yet applied (the
                // Folia-deferred clear) keeps the live flag stale-high — adopting
                // it here would readopt the very sprint the hit cleared and grant a
                // non-w-tapped follow-up the era-denied bonus. A genuine re-sprint
                // arrives on the wire (onWireSprint) instead, so suppressing the
                // live-flag adoption while a clear is pending loses nothing.
                if (liveSprinting && pendingClear.containsKey(player)) {
                    return state;
                }
                return new WireState(liveSprinting, state.armed(), nanos);
            }
            return state;
        });
    }

    /**
     * Records that a post-hit {@code setSprinting(false)} has been issued for the
     * attacker but not yet applied (the Folia-deferred clear), so
     * {@link #reconcileWire} does not readopt the stale-high live flag until the
     * clear lands. Paired with {@link #resolveClearPending}, called when the
     * deferred task runs (or the entity retires).
     */
    public void markClearPending(@NotNull UUID player, long nanos) {
        pendingClear.put(player, nanos);
    }

    /** The deferred sprint clear has landed (or the attacker retired). */
    public void resolveClearPending(@NotNull UUID player) {
        pendingClear.remove(player);
    }

    /**
     * The in-order read for a registering attack; {@code null} when the
     * module is off or the player has never been seen (synthetic players
     * send no packets and get no reconcile) — callers fall back to the
     * tick-frozen snapshot, the pre-module behavior.
     */
    public @Nullable WireVerdict peekWire(@NotNull UUID player) {
        if (!consultWire) {
            return null;
        }
        WireState state = wire.get(player);
        return state == null ? null : new WireVerdict(state.sprinting(), state.armed());
    }

    /**
     * Mirrors vanilla's in-attack {@code setSprinting(false)} into the wire
     * view, beside the live-flag clear the knockback module already issues.
     * Stamped with {@code nanos} so the reconcile's quiet window measures
     * from this write — the not-yet-cleared live flag must not win it back.
     */
    public void clearWireSprint(@NotNull UUID player, long nanos) {
        wire.computeIfPresent(player, (id, state) ->
                // Stamped with the hit's REGISTRATION nanos (not the deferred
                // clear's wall-clock), so a wire re-press that arrived after the
                // hit registered — newer information — is never overwritten by a
                // clear that lands late on Folia. The era cleared inside the
                // attack; a STOP/START after that point still wins.
                nanos < state.nanos()
                        ? state
                        : new WireState(false, state.armed(), nanos));
    }

    /** Spends the wire-armed freshness, beside {@link #consumeFresh}. */
    public void consumeWireFresh(@NotNull UUID player) {
        wire.computeIfPresent(player, (id, state) -> state.armed()
                ? new WireState(state.sprinting(), false, state.nanos())
                : state);
    }

    /* ------------------------------------------------------------------ */
    /*  Registration stamps                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Stamps the sprint answer the cancelled attack packet saw — the wire
     * view when the module supplied one, the tick-frozen snapshot otherwise.
     * Vanilla read the flag INSIDE {@code Player.attack} — ahead of the
     * client's own post-attack STOP_SPRINTING sync — while the fast path's
     * deferred damage runs after the inbound queue and would lose that race:
     * a perfectly-timed sprint hit would ship plain.
     */
    public void stampAttackVerdict(
            @NotNull UUID attacker, boolean sprinting, @Nullable Boolean fresh, long nanos) {
        attackStamps.put(attacker, new AttackStamp(sprinting, fresh, nanos));
    }

    /**
     * One-shot read of the registration-time verdict; {@code null} when no
     * fast-path stamp exists (vanilla-path hits read the live flag, which
     * vanilla's inline attack already evaluated correctly).
     */
    public @Nullable AttackVerdict takeAttackVerdict(@NotNull UUID attacker, long nowNanos) {
        AttackStamp stamp = attackStamps.remove(attacker);
        if (stamp == null || nowNanos - stamp.nanos() > attackStampTtlNanos) {
            return null;
        }
        return new AttackVerdict(stamp.sprinting(), stamp.fresh(), stamp.nanos());
    }

    /* ------------------------------------------------------------------ */
    /*  Freshness (every path)                                             */
    /* ------------------------------------------------------------------ */

    /** Arms freshness directly — the toggle-event path, exposed for tests. */
    public void arm(@NotNull UUID attacker) {
        fresh.add(attacker);
    }

    /** Read-only check for the netty pre-send prediction. */
    public boolean peekFresh(@NotNull UUID attacker) {
        return fresh.contains(attacker);
    }

    /** The authoritative read: reports freshness and spends it. */
    public boolean consumeFresh(@NotNull UUID attacker) {
        return fresh.remove(attacker);
    }

    public void clear() {
        fresh.clear();
        attackStamps.clear();
        wire.clear();
        clientSprinting.clear();
        pendingClear.clear();
    }
}

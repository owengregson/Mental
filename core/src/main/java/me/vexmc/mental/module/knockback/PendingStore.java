package me.vexmc.mental.module.knockback;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import org.jetbrains.annotations.Nullable;

/**
 * The per-victim queue of pending knockbacks, owned by {@link KnockbackPipeline}.
 *
 * <p>A victim can carry more than one live pending only in a narrow window: a
 * pre-sent hit whose authoritative damage is then absorbed by invulnerability
 * leaves its pending un-consumed, and a second hit can arrive before it expires
 * (a non-era lowered-invulnerability config, or knock-drop under multi-tick
 * server lag). A single slot drops one of the two — the very lost-pending bug
 * that ships a vanilla downward velocity on the second combo hit. A per-victim
 * FIFO pairs each hit's velocity event with its own pending in order.</p>
 *
 * <p><b>Threading.</b> The pending is written from the netty loop
 * (pre-send/pinned submits) and from the victim's owning region thread
 * (authoritative submits, the velocity-event poll, withdraw, the ensure-delivery
 * promote). The single-slot design was race-free because every operation was one
 * atomic {@code ConcurrentHashMap} call over an immutable value; a bare
 * {@code Deque} value would be an unsynchronized read-modify-write on that same
 * hot path and could corrupt or lose the live pending — re-opening the bug. So
 * EVERY mutation here runs inside {@code compute}/{@code computeIfPresent}: the
 * inner {@link ArrayDeque} is only ever touched while the map holds the
 * per-victim bin lock, making each operation a single linearization point, just
 * like the old single put/remove. The local holder arrays are written under that
 * lock and read after it on the same thread.</p>
 */
final class PendingStore {

    /** Bounded so a stuck victim (never any velocity event) cannot grow without limit. */
    static final int CAP = 4;

    private final ConcurrentHashMap<UUID, ArrayDeque<Pending>> pending = new ConcurrentHashMap<>();

    /**
     * Appends {@code p}; if that exceeds {@link #CAP}, evicts and returns the
     * OLDEST pending (for the caller to log — no silent cap), else {@code null}.
     */
    @Nullable Pending enqueue(UUID victim, Pending p) {
        Pending[] evicted = {null};
        pending.compute(victim, (id, dq) -> {
            ArrayDeque<Pending> queue = dq == null ? new ArrayDeque<>() : dq;
            queue.offerLast(p);
            if (queue.size() > CAP) {
                evicted[0] = queue.pollFirst();
            }
            return queue;
        });
        return evicted[0];
    }

    /**
     * Removes and returns the OLDEST non-expired pending, dropping any expired
     * heads ahead of it; {@code droppedExpired} is told how many were dropped
     * (called at most once, only when non-zero). Returns {@code null} when the
     * victim has no live pending.
     */
    @Nullable Pending pollLive(UUID victim, long nowNanos, long expiryNanos, IntConsumer droppedExpired) {
        Pending[] taken = {null};
        int[] dropped = {0};
        pending.compute(victim, (id, dq) -> {
            if (dq == null) {
                return null;
            }
            Pending p;
            while ((p = dq.pollFirst()) != null) {
                if (p.expired(nowNanos, expiryNanos)) {
                    dropped[0]++;
                } else {
                    taken[0] = p;
                    break;
                }
            }
            return dq.isEmpty() ? null : dq;
        });
        if (dropped[0] > 0) {
            droppedExpired.accept(dropped[0]);
        }
        return taken[0];
    }

    /**
     * Returns the OLDEST non-expired pending WITHOUT removing anything — the
     * {@code EntityKnockbackEvent} mirror peeks what {@link #pollLive} will hand
     * the next velocity event so an observer of vanilla's knockback event (an
     * anticheat, SimpleBoxer) sees Mental's value instead. Expired heads are
     * skipped here but only ever dropped by {@link #pollLive} at the
     * authoritative apply, so the peek and the consume agree on the head.
     */
    @Nullable Pending peekLiveHead(UUID victim, long nowNanos, long expiryNanos) {
        Pending[] head = {null};
        pending.computeIfPresent(victim, (id, dq) -> {
            for (Pending p : dq) { // ArrayDeque iterates head -> tail: oldest first
                if (!p.expired(nowNanos, expiryNanos)) {
                    head[0] = p;
                    break;
                }
            }
            return dq;
        });
        return head[0];
    }

    /**
     * Whether a non-expired pre-delivered pending registered by {@code attacker}
     * is queued — the authoritative pass adopts that wire stamp instead of
     * recomputing. Scoped to the registering attacker so the wider Folia window
     * cannot let a different attacker's hit adopt a lingering vector.
     */
    boolean hasFreshPreDelivered(UUID victim, long nowNanos, long expiryNanos, @Nullable UUID attacker) {
        boolean[] found = {false};
        pending.computeIfPresent(victim, (id, dq) -> {
            for (Pending p : dq) {
                if (p.preDelivered() != null && !p.expired(nowNanos, expiryNanos)) {
                    UUID stored = p.attacker() != null ? p.attacker().getUniqueId() : null;
                    if (KnockbackPipeline.sameAttacker(stored, attacker)) {
                        found[0] = true;
                        break;
                    }
                }
            }
            return dq;
        });
        return found[0];
    }

    /**
     * Moves the NEWEST deliverable pending of {@code cause} to the head and
     * returns it, so the manual {@code ensureDelivery} velocity event consumes
     * exactly that pending rather than the FIFO head (which could be an unrelated
     * lingering knock — e.g. a pre-sent melee invuln then absorbed). Returns
     * {@code null} (and promotes nothing) when there is no such pending or its
     * vector is null (resistance-cancelled). Any older pending stays queued for
     * its own velocity event.
     */
    @Nullable Pending promoteNewestOfCause(UUID victim, KnockbackPipeline.Cause cause) {
        Pending[] target = {null};
        pending.compute(victim, (id, dq) -> {
            if (dq == null) {
                return null;
            }
            Pending newest = null;
            Iterator<Pending> it = dq.descendingIterator();
            while (it.hasNext()) {
                Pending p = it.next();
                if (p.cause() == cause) {
                    newest = p;
                    break;
                }
            }
            if (newest != null && newest.vector() != null) {
                dq.remove(newest);
                dq.offerFirst(newest);
                target[0] = newest;
            }
            return dq.isEmpty() ? null : dq;
        });
        return target[0];
    }

    /** Drops every pending for the victim — a protection plugin cancelled the hit. */
    void withdrawAll(UUID victim) {
        pending.remove(victim);
    }

    /** Drops only the victim's pendings queued by {@code cause}. */
    void withdrawCause(UUID victim, KnockbackPipeline.Cause cause) {
        pending.computeIfPresent(victim, (id, dq) -> {
            dq.removeIf(p -> p.cause() == cause);
            return dq.isEmpty() ? null : dq;
        });
    }

    void forget(UUID victim) {
        pending.remove(victim);
    }

    void clear() {
        pending.clear();
    }

    /** Test-only: the victim's current queue depth. */
    int depth(UUID victim) {
        int[] size = {0};
        pending.computeIfPresent(victim, (id, dq) -> {
            size[0] = dq.size();
            return dq;
        });
        return size[0];
    }
}

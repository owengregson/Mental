package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-victim FIFO pending store that replaced the single slot.
 *
 * <p>The store must (1) degenerate to exactly the old single-slot behavior when
 * a victim never carries more than one pending — the universal era-config path,
 * so the measured wire values are untouched — (2) pair overlapping hits with
 * their own velocity event in arrival order, (3) never lose or duplicate a
 * pending under the netty-vs-region concurrency that a bare deque would corrupt,
 * and (4) deliver a projectile/rod through {@code ensureDelivery} without
 * orphaning it behind a lingering melee pending.</p>
 */
class PendingStoreTest {

    private static final long NEVER = Long.MAX_VALUE;

    private static Pending plain(KnockbackPipeline.Cause cause, long stampNanos) {
        return new Pending(new KnockbackVector(1.0, 0.4, 0.0), null, false, null, cause, true, stampNanos);
    }

    private static Pending suppressed(KnockbackPipeline.Cause cause, long stampNanos) {
        return new Pending(null, null, false, null, cause, true, stampNanos);
    }

    @Test
    void enqueueThenPollDegeneratesToASingleSlot() {
        PendingStore store = new PendingStore();
        UUID victim = UUID.randomUUID();
        Pending only = plain(KnockbackPipeline.Cause.MELEE, 1L);
        assertNull(store.enqueue(victim, only), "a first enqueue evicts nothing");
        assertEquals(1, store.depth(victim));
        assertSame(only, store.pollLive(victim, 2L, NEVER, n -> {}));
        assertEquals(0, store.depth(victim), "consuming the only pending empties the victim slot");
        assertNull(store.pollLive(victim, 3L, NEVER, n -> {}));
    }

    @Test
    void overlappingHitsPollInArrivalOrder() {
        PendingStore store = new PendingStore();
        UUID victim = UUID.randomUUID();
        Pending hit1 = plain(KnockbackPipeline.Cause.MELEE, 1L);
        Pending hit2 = plain(KnockbackPipeline.Cause.MELEE, 2L);
        store.enqueue(victim, hit1);
        store.enqueue(victim, hit2);
        // hit-1's velocity event consumes hit-1's pending, hit-2's consumes hit-2's.
        assertSame(hit1, store.pollLive(victim, 3L, NEVER, n -> {}));
        assertSame(hit2, store.pollLive(victim, 3L, NEVER, n -> {}));
    }

    @Test
    void pollSkipsAndCountsExpiredHeads() {
        PendingStore store = new PendingStore();
        UUID victim = UUID.randomUUID();
        Pending stale = plain(KnockbackPipeline.Cause.MELEE, 0L);
        Pending live = plain(KnockbackPipeline.Cause.MELEE, 100L);
        store.enqueue(victim, stale);
        store.enqueue(victim, live);
        int[] dropped = {0};
        // expiry window 50ns: at now=120 the stale head (stamp 0 -> age 120) is
        // expired, the live one (stamp 100 -> age 20) is not.
        assertSame(live, store.pollLive(victim, 120L, 50L, n -> dropped[0] = n));
        assertEquals(1, dropped[0], "the expired head was dropped and reported, not served");
    }

    @Test
    void capEvictsTheOldestAndReportsIt() {
        PendingStore store = new PendingStore();
        UUID victim = UUID.randomUUID();
        Pending oldest = plain(KnockbackPipeline.Cause.MELEE, 0L);
        store.enqueue(victim, oldest);
        for (long i = 1; i < PendingStore.CAP; i++) {
            assertNull(store.enqueue(victim, plain(KnockbackPipeline.Cause.MELEE, i)), "no eviction while under cap");
        }
        assertEquals(PendingStore.CAP, store.depth(victim));
        Pending evicted = store.enqueue(victim, plain(KnockbackPipeline.Cause.MELEE, PendingStore.CAP));
        assertSame(oldest, evicted, "the cap evicts the oldest pending and hands it back for logging");
        assertEquals(PendingStore.CAP, store.depth(victim));
    }

    @Test
    void withdrawCauseLeavesOtherCauses() {
        PendingStore store = new PendingStore();
        UUID victim = UUID.randomUUID();
        store.enqueue(victim, plain(KnockbackPipeline.Cause.MELEE, 1L));
        Pending projectile = plain(KnockbackPipeline.Cause.PROJECTILE, 2L);
        store.enqueue(victim, projectile);
        store.withdrawCause(victim, KnockbackPipeline.Cause.MELEE);
        assertSame(projectile, store.pollLive(victim, 3L, NEVER, n -> {}), "only the melee pending was withdrawn");
    }

    @Test
    void ensureDeliveryPromotesItsCauseOverALingeringHead() {
        PendingStore store = new PendingStore();
        UUID victim = UUID.randomUUID();
        // A pre-sent melee that invuln then absorbed lingers at the head; a
        // projectile then lands. ensureDelivery must deliver the PROJECTILE, not
        // the stale melee, and must not orphan the projectile.
        Pending lingeringMelee = plain(KnockbackPipeline.Cause.MELEE, 1L);
        Pending projectile = plain(KnockbackPipeline.Cause.PROJECTILE, 2L);
        store.enqueue(victim, lingeringMelee);
        store.enqueue(victim, projectile);
        assertSame(projectile, store.promoteNewestOfCause(victim, KnockbackPipeline.Cause.PROJECTILE));
        // The manual velocity event consumes the promoted projectile first.
        assertSame(projectile, store.pollLive(victim, 3L, NEVER, n -> {}));
        // The lingering melee is preserved behind it, not discarded.
        assertSame(lingeringMelee, store.pollLive(victim, 3L, NEVER, n -> {}));
    }

    @Test
    void promoteSkipsAResistanceCancelledPending() {
        PendingStore store = new PendingStore();
        UUID victim = UUID.randomUUID();
        store.enqueue(victim, suppressed(KnockbackPipeline.Cause.PROJECTILE, 1L));
        assertNull(store.promoteNewestOfCause(victim, KnockbackPipeline.Cause.PROJECTILE),
                "a null-vector (resistance-cancelled) pending is not delivered");
    }

    @Test
    void concurrentEnqueueAndPollNeverLoseOrDuplicateAPending() throws InterruptedException {
        // The blocker the panel flagged: the single-slot store was race-free only
        // because each op was one atomic ConcurrentHashMap call. A bare deque
        // value is an unsynchronized read-modify-write across the netty submit and
        // the region poll — it would CME or silently drop the live hit-1 pending,
        // shipping the vanilla downward velocity the fix is meant to remove. The
        // compute()-atomic store must conserve every pending exactly once.
        PendingStore store = new PendingStore();
        UUID victim = UUID.randomUUID();
        int total = 20_000;
        Set<Long> enqueued = ConcurrentHashMap.newKeySet();
        Set<Long> seen = ConcurrentHashMap.newKeySet(); // polled or evicted
        AtomicBoolean duplicate = new AtomicBoolean(false);
        AtomicBoolean producing = new AtomicBoolean(true);

        Thread consumer = new Thread(() -> {
            while (producing.get() || store.depth(victim) > 0) {
                Pending got = store.pollLive(victim, 0L, NEVER, n -> {});
                if (got != null && !seen.add(got.stampNanos())) {
                    duplicate.set(true);
                }
            }
        });
        consumer.start();

        for (long i = 0; i < total; i++) {
            enqueued.add(i);
            Pending evicted = store.enqueue(victim, plain(KnockbackPipeline.Cause.MELEE, i));
            if (evicted != null && !seen.add(evicted.stampNanos())) {
                duplicate.set(true);
            }
        }
        producing.set(false);
        consumer.join();

        Pending tail;
        while ((tail = store.pollLive(victim, 0L, NEVER, n -> {})) != null) {
            if (!seen.add(tail.stampNanos())) {
                duplicate.set(true);
            }
        }

        assertFalse(duplicate.get(), "no pending was polled or evicted twice");
        assertEquals(enqueued, seen, "every enqueued pending was polled or evicted exactly once — none lost");
    }
}

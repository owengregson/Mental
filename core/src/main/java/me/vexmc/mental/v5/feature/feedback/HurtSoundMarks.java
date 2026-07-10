package me.vexmc.mental.v5.feature.feedback;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Predicate;

/**
 * The correlation ring between the Bukkit half (the {@link HitFeedbackListener},
 * on the victim's region thread) and the netty half (the {@link HurtSoundSuppressor},
 * on the send pipeline): the listener ARMS a mark for a hit it is about to voice
 * itself, and the suppressor CONSUMES that mark to cancel exactly the one vanilla
 * {@code entity.player.hurt} broadcast the same hit triggers. This is why
 * suppression is mark-scoped rather than a blanket cancel of the hurt sound — a
 * fall/fire/drown hurt sound carries no mark, finds nothing to consume, and passes
 * through untouched.
 *
 * <p>Deliberately pure: long-tick primitives, no Bukkit and no PacketEvents
 * types, so it is a plain data structure the netty thread can read without
 * touching the game. It is a bounded ({@value #CAPACITY}) FIFO of marks that
 * expire {@value #EXPIRY_TICKS} ticks after they are armed — a hit's vanilla
 * sound ships within the same tick, so a short window suffices and a mark that is
 * never consumed (no client nearby, an anticheat that ate the broadcast) simply
 * ages out. Every method is {@code synchronized}: the two writers are different
 * threads.</p>
 */
public final class HurtSoundMarks {

    /** The mark window: a hit's vanilla broadcast lands the same tick; two ticks is slack. */
    static final int EXPIRY_TICKS = 2;

    /** Bounded so a burst of hits (or a suppressor that never fires) cannot grow the ring. */
    static final int CAPACITY = 64;

    /** One armed hit: the victim's entity id and world position, stamped with the arming tick. */
    private record Mark(int entityId, double x, double y, double z, long tick) {}

    private final ArrayDeque<Mark> marks = new ArrayDeque<>();

    /**
     * Arms a mark for a hit the listener is about to voice. Oldest-first eviction
     * keeps the ring at {@value #CAPACITY}.
     *
     * @param victimEntityId the victim's entity id (the entity-attached sound key)
     * @param x the victim world x (the positional sound key)
     * @param y the victim world y
     * @param z the victim world z
     * @param now the arming tick (the caller passes {@code clock.current().value()})
     */
    public synchronized void mark(int victimEntityId, double x, double y, double z, long now) {
        if (marks.size() == CAPACITY) {
            marks.removeFirst();
        }
        marks.addLast(new Mark(victimEntityId, x, y, z, now));
    }

    /**
     * Consumes the first live mark for {@code entityId} (the
     * {@code ENTITY_SOUND_EFFECT} path — the sound is entity-attached), removing
     * it. Returns false when no unexpired mark for that id is armed.
     */
    public synchronized boolean consume(int entityId, long now) {
        return consumeMatching(now, mark -> mark.entityId() == entityId);
    }

    /**
     * Consumes the first live mark within {@value #RADIUS} blocks of
     * {@code (x, y, z)} (the {@code SOUND_EFFECT} path — the sound carries only a
     * fixed-point world position, no entity id), removing it. The vanilla broadcast
     * sits within a 1/8-block quantization of the victim's position, so a 1.5-block
     * radius clears it comfortably without straying into a neighbour.
     */
    public synchronized boolean consumeNear(double x, double y, double z, long now) {
        return consumeMatching(now, mark -> {
            double dx = mark.x() - x;
            double dy = mark.y() - y;
            double dz = mark.z() - z;
            return dx * dx + dy * dy + dz * dz <= RADIUS_SQUARED;
        });
    }

    /** The positional-consume radius, in blocks. */
    private static final double RADIUS = 1.5;
    private static final double RADIUS_SQUARED = RADIUS * RADIUS;

    /**
     * Drops expired heads, then removes and returns true for the first surviving
     * mark that satisfies {@code test}. Marks are armed in non-decreasing tick
     * order, so expiry is a front sweep; the per-mark expiry re-check keeps a match
     * honest even if a clock ever went backwards.
     */
    private boolean consumeMatching(long now, Predicate<Mark> test) {
        sweepExpired(now);
        for (Iterator<Mark> it = marks.iterator(); it.hasNext(); ) {
            Mark mark = it.next();
            if (isLive(mark, now) && test.test(mark)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private void sweepExpired(long now) {
        // Drop only strictly-expired heads (age past the window); a future-tick head
        // — a transient clock reversal — is left in place, never mistaken for stale.
        while (!marks.isEmpty() && now - marks.peekFirst().tick() > EXPIRY_TICKS) {
            marks.removeFirst();
        }
    }

    private static boolean isLive(Mark mark, long now) {
        long age = now - mark.tick();
        return age >= 0 && age <= EXPIRY_TICKS;
    }
}

package me.vexmc.mental.v5.feature.feedback;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import org.bukkit.entity.LivingEntity;

/**
 * The non-player analogue of the per-player session tick.
 *
 * <p>Players get their roll-hold flush and heal sampling from
 * {@code SessionService}'s per-player tick — but a {@code CombatSession} exists
 * only for players, so a MOB victim has no such tick. Two things then break once
 * indicators cover mobs: a held indicator window would open and never ship (the
 * default {@code roll-hold-ticks} is 3, so essentially every mob hit would be
 * silently swallowed), and a mob's heals would never be sampled at all.
 *
 * <p>This is that missing tick, armed per mob and only while it matters. A player
 * hitting a mob arms it; the task runs on THAT MOB's region (Folia-correct — it
 * reads the mob's own health) and each tick flushes any due window and samples the
 * health delta. Every fresh hit refreshes the deadline; once a mob has gone
 * {@link #IDLE_TICKS} without one, both its window horizon and its heal attribution
 * have lapsed, so the task cancels itself and the entry is dropped.
 *
 * <p>So the cost is bounded by RECENT COMBAT, not by mob count: an idle farm ticks
 * nothing. Sampling rather than listening to {@code EntityRegainHealthEvent} is
 * deliberate and mirrors the player path — a plugin healing a mob through
 * {@code setHealth} fires no event, and those heals are exactly the ones a combat
 * server wants to see.
 */
final class MobFeedbackTicker {

    /**
     * How long after its last hit a mob keeps ticking. Matches the heal-attribution
     * horizon: past it there is no window left to flush and no attacker to attribute
     * a heal to, so there is nothing the tick could produce.
     */
    static final long IDLE_TICKS = 200L;

    /** One mob's live tick: the running task and the tick past which it stops. */
    private static final class Handle {
        TaskHandle task;
        volatile long deadline;

        Handle(long deadline) {
            this.deadline = deadline;
        }
    }

    private final Scheduling scheduling;
    private final TickClock clock;
    private final Work work;

    /** The mobs currently ticking. Entries are added on the mob's region and removed by its own task. */
    private final Map<UUID, Handle> ticking = new ConcurrentHashMap<>();

    /** What one mob tick does — supplied by the unit so this class owns only the lifecycle. */
    @FunctionalInterface
    interface Work {
        void tick(LivingEntity mob, long now);
    }

    MobFeedbackTicker(Scheduling scheduling, TickClock clock, Work work) {
        this.scheduling = scheduling;
        this.clock = clock;
        this.work = work;
    }

    /**
     * Arms (or refreshes) the tick for {@code mob}. Called from the damage event on the
     * mob's own region thread, so the map write and the task start are serialised per mob.
     */
    void arm(LivingEntity mob) {
        UUID id = mob.getUniqueId();
        long deadline = clock.current().value() + IDLE_TICKS;
        Handle handle = ticking.computeIfAbsent(id, key -> new Handle(deadline));
        handle.deadline = deadline; // a refresh on an already-running tick
        if (handle.task != null) {
            return;
        }
        handle.task = scheduling.repeatOn(mob, 1L, 1L, () -> {
            long now = clock.current().value();
            if (now > handle.deadline || !mob.isValid()) {
                cancel(id, handle);
                return;
            }
            work.tick(mob, now);
        }, () -> cancel(id, handle)); // retired (chunk unload / death) — drop the entry
    }

    private void cancel(UUID id, Handle handle) {
        ticking.remove(id, handle);
        TaskHandle task = handle.task;
        handle.task = null;
        if (task != null) {
            task.cancel();
        }
    }

    /** Scope teardown: stop every mob tick — a disabled feature leaves no residue. */
    void close() {
        ticking.forEach((id, handle) -> {
            TaskHandle task = handle.task;
            handle.task = null;
            if (task != null) {
                task.cancel();
            }
        });
        ticking.clear();
    }
}

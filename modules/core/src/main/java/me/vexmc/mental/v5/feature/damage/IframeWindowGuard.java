package me.vexmc.mental.v5.feature.damage;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import me.vexmc.mental.platform.Scheduling;
import org.bukkit.entity.LivingEntity;

/**
 * A capture-and-restore guard over a victim's {@code maximumNoDamageTicks}
 * window — the shared mechanism behind CT8c i-frames and the timing-override
 * fast-re-hit path. Both shrink the WINDOW field (never the {@code noDamageTicks}
 * counter, so the 1.16.5–1.20.6 spawn-invulnerability trap stays structurally
 * unreachable) in a pre-apply damage handler and let the server land the hit's
 * resulting window on the shrunk value, then restore the victim's prior window
 * once the window it shaped has served its purpose.
 *
 * <h2>Why the restore exists (the v2.9.0 leak)</h2>
 * <p>{@code maximumNoDamageTicks} is a PERSISTENT entity field every damage cause
 * consults; a bare shrink with no restore left a victim with a permanently short
 * window, so fire / cactus / drowning re-applied <em>every tick</em> forever. Each
 * shrink therefore records the victim's PRIOR window ONCE per episode (a re-hit
 * while still shrunk must never capture the shrunk value as "prior") and schedules
 * the hand-back. A retired victim just drops its entry.
 *
 * <h2>Why the hand-back is generation-keyed, not counter-keyed (the v2.9.1 leak)</h2>
 * <p>The v2.9.1 restore waited on the LIVE hurt counter — while any window was still
 * draining it re-scheduled itself, so as not to cut short the gating a re-hit had
 * just armed. But that counter is re-armed by EVERY damage cause, not just ours: a
 * victim standing in lava or a sweet-berry bush has it re-assigned every few ticks,
 * so the "counter still live" reschedule never drained and the shrunken window —
 * with its 2–3× faster environmental cadence — stayed pinned for as long as the
 * hazard lasted (the reported lava/berry damage spam).
 *
 * <p>The hand-back keys off a per-shrink GENERATION stamp instead. Every shrink
 * advances the monotonic {@link #shrinkClock} and schedules a restore after the
 * window's own duration, which fires UNLESS a later shrink — which re-enters
 * {@link #shrink}, advancing the generation again — has taken ownership of a fresh,
 * later hand-back. That keeps the property the counter-keyed version was reaching
 * for (a genuine re-hit's window is never cut short, because that re-hit owns a
 * later restore) while dropping the one that caused the leak: a hazard tick re-arms
 * the counter but never re-enters {@code shrink}, so it can never defer the restore.
 * Environmental exposure is bounded to the shaped window's own duration, exactly
 * once, no matter how long the hazard lasts.
 *
 * <p>Each caller owns its OWN guard instance (separate {@code priors} map and
 * clock): the CT8c unit and the timing-override fallback never write the field at
 * the same time (the fallback stands down whenever CT8c owns the write), so there
 * is no two-writer race on one field.
 */
public final class IframeWindowGuard {

    /**
     * A victim's pre-shrink window, the live handle the disable flush restores
     * through, and the {@link #shrinkClock} generation that owns its hand-back.
     */
    private record Prior(int window, WeakReference<LivingEntity> victim, long generation) {}

    private final Scheduling scheduling;

    /**
     * A monotonic per-shrink stamp. Only a genuine (re-)shrink advances it — never a
     * hazard tick — so it, not the live hurt counter, is what a scheduled hand-back
     * waits on (the lava/berry starvation fix).
     */
    private final AtomicLong shrinkClock = new AtomicLong();

    /**
     * The victims whose window this guard has shrunk, keyed by UUID — written on
     * the victim's owning region thread, drained by the region-thread restores
     * and the global-thread disable flush (hence concurrent).
     */
    private final Map<UUID, Prior> priors = new ConcurrentHashMap<>();

    public IframeWindowGuard(Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    /**
     * The victim's PRE-shrink (baseline) window, captured once per shrink episode.
     * A window computed relative to this baseline (the override fallback's
     * {@code round(baseline * factor)}) is re-hit-safe: every hit in the episode
     * prices off the SAME captured baseline, never the already-shrunk live field, so
     * the window can never spiral downward. The entry this may create carries no
     * hand-back of its own (generation {@code 0}) — the {@link #shrink} that always
     * follows it advances the generation and takes ownership.
     */
    public int baseline(LivingEntity victim) {
        return priors.computeIfAbsent(victim.getUniqueId(),
                id -> new Prior(victim.getMaximumNoDamageTicks(), new WeakReference<>(victim), 0L)).window();
    }

    /**
     * Writes {@code window} as the victim's max pre-apply and arms the
     * generation-keyed hand-back. The prior window is captured once per episode (the
     * {@code existing == null} branch — a re-hit while still shrunk must never
     * capture our own value as "vanilla"), while every shrink advances the generation
     * so the LATEST shrink owns the restore. Shrinks for a given victim run on that
     * victim's owning region thread, so the read-window-then-store is serialized per
     * victim.
     */
    public void shrink(LivingEntity victim, int window) {
        long generation = shrinkClock.incrementAndGet();
        priors.compute(victim.getUniqueId(), (id, existing) -> new Prior(
                existing == null ? victim.getMaximumNoDamageTicks() : existing.window(),
                new WeakReference<>(victim), generation));
        victim.setMaximumNoDamageTicks(window);
        scheduleRestore(victim, window + 1L, generation);
    }

    /**
     * Restores the victim's prior window on their owning thread once this shrink's
     * own window has elapsed. The hand-back fires unless a LATER shrink has advanced
     * the generation past {@code generation} — that shrink scheduled its own, later
     * hand-back, which now owns the entry, so this stale one steps aside rather than
     * cutting the newer window short.
     */
    private void scheduleRestore(LivingEntity victim, long delayTicks, long generation) {
        UUID id = victim.getUniqueId();
        scheduling.runOnLater(victim, delayTicks, () -> {
            Prior prior = priors.get(id);
            if (prior == null || prior.generation() != generation) {
                return; // already restored/flushed, or a later shrink owns a later hand-back
            }
            victim.setMaximumNoDamageTicks(prior.window());
            priors.remove(id);
        }, () -> priors.remove(id));
    }

    /** The disable/hand-off flush: every still-shrunk victim gets its window back, region-correct. */
    public void restoreAll() {
        priors.forEach((id, prior) -> {
            LivingEntity victim = prior.victim().get();
            if (victim != null && victim.isValid()) {
                scheduling.runOn(victim,
                        () -> victim.setMaximumNoDamageTicks(prior.window()), () -> {});
            }
        });
        priors.clear();
    }
}

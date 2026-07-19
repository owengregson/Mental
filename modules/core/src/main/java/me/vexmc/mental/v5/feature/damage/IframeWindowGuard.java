package me.vexmc.mental.v5.feature.damage;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.platform.Scheduling;
import org.bukkit.entity.LivingEntity;

/**
 * A capture-and-restore guard over a victim's {@code maximumNoDamageTicks}
 * window — the shared mechanism behind CT8c i-frames and the timing-override
 * fast-re-hit path. Both shrink the WINDOW field (never the {@code noDamageTicks}
 * counter, so the 1.16.5–1.20.6 spawn-invulnerability trap stays structurally
 * unreachable) in a pre-apply damage handler and let the server land the hit's
 * resulting window on the shrunk value, then restore the victim's prior window
 * once it drains.
 *
 * <h2>Why the restore, and why drain-timed (the v2.9.0 leak fixed)</h2>
 * <p>{@code maximumNoDamageTicks} is a PERSISTENT entity field every damage cause
 * consults; a bare shrink with no restore left a victim with a permanent short
 * window, so fire / cactus / drowning re-applied <em>every tick</em>. Each shrink
 * records the victim's PRIOR window ONCE per episode ({@code putIfAbsent} — a
 * re-hit while still shrunk must never capture the shrunk value as "prior") and
 * schedules a restore on the victim's owning thread for when the counter has
 * drained. While any window is still live — a re-hit re-armed it, or an
 * environmental hit re-assigned the counter — the restore re-schedules itself, so
 * the gating a hit just armed is never cut short and the entry is never left
 * behind. A retired victim just drops its entry.
 *
 * <p>Each caller owns its OWN guard instance (separate {@code priors} map): the
 * CT8c unit and the timing-override fallback never write the field at the same
 * time (the fallback stands down whenever CT8c owns the write), so there is no
 * two-writer race on one field.
 */
public final class IframeWindowGuard {

    /** A victim's pre-shrink window and the live handle the disable flush restores through. */
    private record Prior(int window, WeakReference<LivingEntity> victim) {}

    private final Scheduling scheduling;

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
     * prices off the SAME captured baseline, never the already-shrunk live field,
     * so the window can never spiral downward.
     */
    public int baseline(LivingEntity victim) {
        return priors.computeIfAbsent(victim.getUniqueId(),
                id -> new Prior(victim.getMaximumNoDamageTicks(), new WeakReference<>(victim))).window();
    }

    /**
     * Writes {@code window} as the victim's max pre-apply and arms the drain-time
     * restore. The prior window is captured once per episode ({@code putIfAbsent}).
     */
    public void shrink(LivingEntity victim, int window) {
        priors.putIfAbsent(victim.getUniqueId(),
                new Prior(victim.getMaximumNoDamageTicks(), new WeakReference<>(victim)));
        victim.setMaximumNoDamageTicks(window);
        scheduleRestore(victim, window + 1L);
    }

    private void scheduleRestore(LivingEntity victim, long delayTicks) {
        UUID id = victim.getUniqueId();
        scheduling.runOnLater(victim, delayTicks, () -> {
            Prior prior = priors.get(id);
            if (prior == null) {
                return; // already restored (a parallel chain won the drain, or the disable flush ran)
            }
            int counter = victim.getNoDamageTicks();
            if (counter > 0) {
                scheduleRestore(victim, counter + 1L);
                return;
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

package me.vexmc.mental.v5.feature.damage;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import me.vexmc.mental.kernel.math.Ct8cTables;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Combat Test 8c invulnerability frames (spec §2.4). Melee shrinks the vanilla
 * hurt window to {@code min(attackerAttackDelay, 10)} ticks — a fast sword (delay
 * 7) re-hits far sooner than vanilla's 20 — while every projectile source drops
 * to a {@code 0}-tick window so snowballs and eggs re-hit freely.
 *
 * <h2>Why {@code setMaximumNoDamageTicks}, not {@code setNoDamageTicks}</h2>
 * <p>The i-frame WINDOW is the {@code maximumNoDamageTicks} field: after a hit
 * registers, the server assigns the current counter from it
 * ({@code noDamageTicks = maximumNoDamageTicks}), and the {@code >max/2} entry
 * guard divides it. Writing the window in this pre-apply event handler makes the
 * server land THIS hit's resulting window on the CT8c value — no counter write is
 * needed. That deliberately sidesteps the 1.16.5–1.20.6 total-invuln trap
 * entirely (the {@code SpawnInvulnerability} companion arms only off a POSITIVE
 * {@code setNoDamageTicks}, the counter setter — never touched here), which is
 * exactly the "NEVER a raw positive {@code setNoDamageTicks}" mandate satisfied by
 * construction. Writing the counter positive here would instead read as
 * already-invulnerable and drop the very hit we are shaping.</p>
 *
 * <h2>The window is handed back on a generation deadline, not a live-counter drain</h2>
 * <p>{@code maximumNoDamageTicks} is a PERSISTENT, all-causes entity field, so while
 * it is shrunk EVERY damage source re-hits on the shrunken cadence — the shrink must
 * be handed back the instant its melee/projectile window has served its purpose. The
 * v2.9.0 unit wrote it and never restored (one snowball left a permanent 0-tick
 * window, and fire / cactus / berries then re-applied <em>every tick</em> forever);
 * the v2.9.1 restore keyed the hand-back off the LIVE hurt counter, which a victim
 * standing in a hazard has re-armed to the shrunken window every few ticks — so the
 * "counter still live" reschedule NEVER drained and the shrunken window (with its
 * 2–3× faster environmental cadence) persisted for the whole time the player was in
 * lava / a berry bush (the reported spam). The hand-back now keys off a per-shrink
 * GENERATION stamp instead: each shrink records the victim's prior window once,
 * advances the {@link #shrinkClock}, and schedules a restore after {@code window + 1}
 * ticks that fires UNLESS a later CT8c hit — which re-enters {@link #shrink},
 * advancing the generation again — has taken ownership of a fresh, later hand-back. A
 * hazard tick never re-enters {@code shrink}, so it can never defer the restore; the
 * environmental exposure is thereby bounded to the window's own duration, exactly
 * once, no matter how long the hazard lasts. Disabling the feature restores every
 * still-tracked victim immediately — a disabled feature leaves NO residue
 * (zero-touch).</p>
 *
 * <p>Zero-touch: assembled only when enabled; every other damage cause keeps its
 * vanilla window (this handler touches only player-melee and projectile hits,
 * and returns the field once the window it shaped has drained).</p>
 */
public final class Ct8cIframesUnit implements FeatureUnit, Listener {

    /** The CT8c hard cap on the melee i-frame window (spec §2.4: {@code min(delay, 10)}). */
    private static final int MELEE_IFRAME_CAP = 10;

    /**
     * A victim's pre-shrink window, the live handle the disable flush restores
     * through, and the {@link #shrinkClock} generation that owns its hand-back.
     */
    private record Prior(int window, WeakReference<LivingEntity> victim, long generation) {}

    private final Scheduling scheduling;

    /**
     * A monotonic per-shrink stamp. Only a genuine CT8c (re-)shrink advances it —
     * never a hazard tick — so it, not the live hurt counter, is what a scheduled
     * hand-back waits on (the lava/berry starvation fix).
     */
    private final AtomicLong shrinkClock = new AtomicLong();

    /**
     * The victims whose window is currently shrunken, keyed by UUID — written on
     * the victim's owning region thread, drained by the region-thread restores
     * and the global-thread disable flush (hence concurrent).
     */
    private final Map<UUID, Prior> priors = new ConcurrentHashMap<>();

    public Ct8cIframesUnit(Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    @Override
    public Feature descriptor() {
        return Feature.CT8C_IFRAMES;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
        // Closing the scope (disable/reload-off) restores every still-shrunken
        // window immediately — the residue rule.
        scope.task(() -> this::restoreAll);
    }

    /**
     * The melee i-frame window for an attacker of {@code attackSpeed}: {@code
     * min(attackDelayTicks(attackSpeed), 10)} (spec §2.4). Pure and unit-pinned
     * (sword 4.5 → 7; axe/hoe 3.5 → 10; a slow weapon clamps to 10).
     */
    public static int iframeTicks(double attackSpeed) {
        return Math.min(Ct8cTables.attackDelayTicks(attackSpeed), MELEE_IFRAME_CAP);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
            // Every projectile source → a 0-tick window, so the next projectile
            // is not blocked by this one's aftermath (spec §2.4; the consecutive
            // snowball/egg double-hit the tester asserts). i-frames from a prior
            // MELEE window still gate a projectile's ENTRY — that bypass would
            // need a pre-hit counter clear (Task G's projectile path), out of
            // this unit's lane.
            shrink(victim, 0);
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getDamager() instanceof Player attacker) {
            double attackSpeed = DamageShaper.ct8cAttackSpeed(attacker.getInventory().getItemInMainHand());
            shrink(victim, iframeTicks(attackSpeed));
        }
    }

    /**
     * Writes the CT8c window pre-apply and arms the generation-keyed hand-back. The
     * prior window is captured once per shrink episode (the {@code existing == null}
     * branch — a re-hit while still shrunken must never capture our own value as
     * "vanilla"), and every shrink advances the {@link #shrinkClock} so the LATEST
     * hit owns the restore. Shrinks for a given victim run on that victim's owning
     * region thread, so the read-window-then-store is serialized per victim.
     */
    private void shrink(LivingEntity victim, int window) {
        UUID id = victim.getUniqueId();
        long generation = shrinkClock.incrementAndGet();
        priors.compute(id, (key, existing) -> new Prior(
                existing == null ? victim.getMaximumNoDamageTicks() : existing.window(),
                new WeakReference<>(victim), generation));
        victim.setMaximumNoDamageTicks(window);
        scheduleRestore(victim, window + 1L, generation);
    }

    /**
     * Restores the victim's prior window on their owning thread once this shrink's
     * own window has elapsed ({@code window + 1} ticks). The hand-back fires unless a
     * LATER CT8c hit has advanced the generation past {@code generation} — that hit
     * re-entered {@link #shrink} and scheduled its own, later hand-back, which now
     * owns the entry, so this stale one steps aside (never cutting the newer window
     * short). Crucially the wait is on the generation, NOT the live hurt counter: an
     * environmental hit (lava, a berry bush, fire) re-arms the counter but never
     * re-enters {@code shrink}, so it can never defer this restore — the window is
     * always handed back after its own duration. A retired victim just drops its entry.
     */
    private void scheduleRestore(LivingEntity victim, long delayTicks, long generation) {
        UUID id = victim.getUniqueId();
        scheduling.runOnLater(victim, delayTicks, () -> {
            Prior prior = priors.get(id);
            if (prior == null || prior.generation() != generation) {
                return; // already restored/flushed, or a later CT8c re-hit owns a later hand-back
            }
            victim.setMaximumNoDamageTicks(prior.window());
            priors.remove(id);
        }, () -> priors.remove(id));
    }

    /** The disable flush: every still-shrunken victim gets its window back, region-correct. */
    private void restoreAll() {
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

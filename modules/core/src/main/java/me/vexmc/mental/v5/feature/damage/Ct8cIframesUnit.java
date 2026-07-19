package me.vexmc.mental.v5.feature.damage;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * <h2>The window is restored once it drains (the v2.9.0 leak fixed)</h2>
 * <p>{@code maximumNoDamageTicks} is a PERSISTENT entity field, and every damage
 * cause consults it — the shipped unit wrote it and never restored, so one
 * snowball left the victim with a permanent 0-tick window and fire / cactus /
 * drowning then re-applied <em>every tick</em> until an unrelated melee hit
 * happened to raise it (and even that left 7–10, never the vanilla 20). Each
 * write now records the victim's PRIOR window once and schedules a restore on
 * the victim's owning thread for when the counter has drained (re-checking while
 * any window is still live, so a re-hit keeps the CT8c gating it just armed);
 * the environmental exposure is thereby bounded to the few ticks of the window
 * itself instead of forever. Disabling the feature restores every still-tracked
 * victim immediately — a disabled feature leaves NO residue (zero-touch).</p>
 *
 * <p>Zero-touch: assembled only when enabled; every other damage cause keeps its
 * vanilla window (this handler touches only player-melee and projectile hits,
 * and returns the field once the window it shaped has drained).</p>
 */
public final class Ct8cIframesUnit implements FeatureUnit, Listener {

    /** The CT8c hard cap on the melee i-frame window (spec §2.4: {@code min(delay, 10)}). */
    private static final int MELEE_IFRAME_CAP = 10;

    /** A victim's pre-shrink window and the live handle the disable flush restores through. */
    private record Prior(int window, WeakReference<LivingEntity> victim) {}

    private final Scheduling scheduling;

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
     * Writes the CT8c window pre-apply and arms the drain-time restore. The
     * prior window is captured once per shrink episode ({@code putIfAbsent} — a
     * re-hit while still shrunken must never capture our own value as "vanilla").
     */
    private void shrink(LivingEntity victim, int window) {
        priors.putIfAbsent(victim.getUniqueId(),
                new Prior(victim.getMaximumNoDamageTicks(), new WeakReference<>(victim)));
        victim.setMaximumNoDamageTicks(window);
        scheduleRestore(victim, window + 1L);
    }

    /**
     * Restores the victim's prior window on their owning thread once the hurt
     * counter has drained. While any window is still live — a CT8c re-hit re-armed
     * it, or an environmental hit re-assigned the counter — the check re-schedules
     * itself for the drain, so the gating a hit just armed is never cut short and
     * the entry can never be left behind. A retired victim just drops its entry.
     */
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

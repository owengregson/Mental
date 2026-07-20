package me.vexmc.mental.v5.feature.damage;

import me.vexmc.mental.kernel.math.Ct8cTables;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.timing.OverrideTable;
import me.vexmc.mental.kernel.timing.WindowPricing;
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
 * Combat Test 8c invulnerability frames (spec §2.4). 8c's {@code hurt} makes the
 * WHOLE i-frame window difference-damage — its gate is {@code invulnerableTime > 0}
 * over a window of {@code min(attackerAttackDelay, 10)} ticks (0 for every
 * projectile, so snowballs and eggs re-hit freely). A stronger re-hit inside the
 * window deals only the difference with no knockback and no flinch; a fresh full
 * hit is allowed only once the window has entirely elapsed.
 *
 * <h2>Why {@code 2×} the window is written, and the field not the counter</h2>
 * <p>The window lives in the {@code maximumNoDamageTicks} field: after a hit the
 * server assigns {@code noDamageTicks = maximumNoDamageTicks}, and CraftBukkit's
 * re-hit gate is the HALF-window {@code noDamageTicks > maximumNoDamageTicks/2} —
 * difference-damage only in the FIRST half of the window, a fresh full hit in the
 * second. 8c's gate is the WHOLE window. Writing {@code 2 ×} the 8c window makes
 * that half-window gate reproduce 8c's full window EXACTLY — {@code W} ticks of
 * difference-damage, then a fresh hit — with no reimplementation of the server's
 * own gate (verified tick-for-tick against the {@code hurt} binary). And every
 * OTHER consumer of the field reads it through the same {@code /2} (the fast-path
 * adopt/reject, the three knockback immunity gates, {@code WindowJudge}, the
 * feedback windows, the published {@code PlayerView}), so the doubled field lands
 * the 8c window {@code W} uniformly across all of them for free — no consumer is
 * touched. The doubling only ever applies to a CT8c-shrunken victim, so nothing
 * outside this feature moves (legacy 1.7/1.8, which correctly want {@code /2},
 * never have it on). A slow weapon (delay 10 → {@code 2 × 10 = 20}) writes exactly
 * the vanilla default — 8c's own {@code min(10,10)=10} window equals vanilla's
 * half of 20, so that is faithful, not a no-op accident.</p>
 *
 * <p>The field (never a positive {@code setNoDamageTicks}, the counter setter) is
 * written so the server lands THIS hit's window on the CT8c value with no counter
 * write, sidestepping the 1.16.5–1.20.6 total-invuln trap by construction — the
 * {@code SpawnInvulnerability} companion arms only off a POSITIVE counter write,
 * never touched here. Writing the counter positive would instead read as
 * already-invulnerable and drop the very hit we are shaping. The capture-and-restore
 * of the prior window — persistent-field hygiene, and the generation-keyed hand-back
 * that stops the shrink leaking into environmental damage — lives in the shared
 * {@link IframeWindowGuard}.</p>
 *
 * <h2>Timing-override composition (spec §2.4 of the override doc, S1)</h2>
 * <p>When a temporary {@code HitTimingOverrides} re-pricing is live for THIS hit's
 * (victim, attacker) pair, the per-hit LOGICAL window composes as
 * {@code round(min(attackDelay, 10) * factor)} — the {@code WindowPricing} scale of
 * the 8c window, priced off the attack-speed table (never the live field), so a
 * re-hit never spirals. The {@code 2 ×} transport is applied AFTER that scaling: the
 * override re-prices the 8c window a player actually experiences, and the doubling
 * is purely how that window is carried through CraftBukkit's half-gate. Because CT8c
 * re-writes the window on EVERY hit keyed to THAT hit's attacker, a third attacker's
 * hit writes the un-scaled CT8c window: the acceleration is priced per-hit-attacker
 * with nothing global erased. With no override in force the factor is 1.0 and the
 * write is byte-identical to plain CT8c.</p>
 *
 * <p>Zero-touch: assembled only when enabled; every other damage cause keeps its
 * vanilla window (this handler touches only player-melee and projectile hits,
 * and returns the field once the window it shaped has drained).</p>
 */
public final class Ct8cIframesUnit implements FeatureUnit, Listener {

    /** The CT8c hard cap on the melee i-frame window (spec §2.4: {@code min(delay, 10)}). */
    private static final int MELEE_IFRAME_CAP = 10;

    private final OverrideTable overrides;
    private final TickClock clock;
    private final IframeWindowGuard guard;

    public Ct8cIframesUnit(Scheduling scheduling, OverrideTable overrides, TickClock clock) {
        this.overrides = overrides;
        this.clock = clock;
        this.guard = new IframeWindowGuard(scheduling);
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
        scope.task(() -> guard::restoreAll);
    }

    /**
     * The 8c LOGICAL melee i-frame window for an attacker of {@code attackSpeed}:
     * {@code min(attackDelayTicks(attackSpeed), 10)} (spec §2.4). Pure and
     * unit-pinned (sword 4.5 → 7; axe/hoe 3.5 → 10; a slow weapon clamps to 10).
     * This is 8c's {@code invulnerableTime} value — the number of ticks a re-hit is
     * difference-damage. The {@code maximumNoDamageTicks} field actually written is
     * {@link #windowField} of this (2×), which reproduces the full 8c window through
     * CraftBukkit's half-window gate.
     */
    public static int iframeTicks(double attackSpeed) {
        return Math.min(Ct8cTables.attackDelayTicks(attackSpeed), MELEE_IFRAME_CAP);
    }

    /**
     * The {@code maximumNoDamageTicks} field value for an 8c logical window of
     * {@code logicalWindow} ticks: {@code 2 × logicalWindow}. CraftBukkit's re-hit
     * gate (and every Mental consumer of the field) reads the window through
     * {@code /2}, so the doubled field makes them all reproduce 8c's full window
     * {@code logicalWindow} — {@code W} ticks of difference-damage, then a fresh hit.
     * A projectile's {@code 0} stays {@code 0} (no i-frames).
     */
    public static int windowField(int logicalWindow) {
        return 2 * logicalWindow;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
            // Every projectile source → a 0-tick window, so the next projectile
            // is not blocked by this one's aftermath (spec §2.4; the consecutive
            // snowball/egg double-hit the tester asserts). A 0-window is 0 at any
            // override factor and 0 doubled, so no pair read is needed here.
            guard.shrink(victim, windowField(0));
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getDamager() instanceof Player attacker) {
            double attackSpeed = DamageShaper.ct8cAttackSpeed(attacker.getInventory().getItemInMainHand());
            // Compose the CT8c LOGICAL window with any live timing override for THIS
            // (victim, attacker) pair, then carry it through the half-gate as 2×.
            // Priced off the attack-speed table (absolute, not the live field), so
            // re-hits are safe; factor 1.0 (no override) is byte-identical CT8c.
            double factor = overrides.factorFor(victim.getUniqueId(), attacker.getUniqueId(), clock.current());
            int logical = WindowPricing.price(iframeTicks(attackSpeed), factor);
            guard.shrink(victim, windowField(logical));
        }
    }
}

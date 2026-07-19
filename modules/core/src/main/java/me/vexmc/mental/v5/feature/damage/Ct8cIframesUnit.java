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
 * entirely, exactly the "NEVER a raw positive {@code setNoDamageTicks}" mandate
 * satisfied by construction. The capture-and-restore of the prior window (the
 * v2.9.0 leak fix) lives in the shared {@link IframeWindowGuard}.
 *
 * <h2>Timing-override composition (spec §2.4 of the override doc, S1)</h2>
 * <p>When a temporary {@code HitTimingOverrides} re-pricing is live for THIS hit's
 * (victim, attacker) pair, the per-hit window composes as
 * {@code round(min(attackDelay, 10) * factor)} — the {@code WindowPricing} scale
 * of the CT8c window, priced off the attack-speed table (never the live field), so
 * a re-hit never spirals. Because CT8c re-writes the window on EVERY hit keyed to
 * THAT hit's attacker, a third attacker's hit writes the un-scaled CT8c window: the
 * acceleration is priced per-hit-attacker with nothing global erased. With no
 * override in force the factor is 1.0 and the write is byte-identical to plain
 * CT8c.
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
            // snowball/egg double-hit the tester asserts). A 0-window is 0 at any
            // override factor, so no pair read is needed here.
            guard.shrink(victim, 0);
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getDamager() instanceof Player attacker) {
            double attackSpeed = DamageShaper.ct8cAttackSpeed(attacker.getInventory().getItemInMainHand());
            // Compose the CT8c window with any live timing override for THIS
            // (victim, attacker) pair. Priced off the attack-speed table (absolute,
            // not the live field), so re-hits are safe; factor 1.0 (no override) is
            // byte-identical CT8c.
            double factor = overrides.factorFor(victim.getUniqueId(), attacker.getUniqueId(), clock.current());
            guard.shrink(victim, WindowPricing.price(iframeTicks(attackSpeed), factor));
        }
    }
}

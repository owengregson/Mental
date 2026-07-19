package me.vexmc.mental.v5.api;

import java.util.UUID;
import java.util.function.Supplier;
import me.vexmc.mental.api.timing.HitTimingOverrides;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.timing.OverrideTable;
import me.vexmc.mental.kernel.timing.WindowPricing;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.damage.IframeWindowGuard;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The always-on implementation of the public {@link HitTimingOverrides} service
 * (registered on the {@code ServicesManager} while Mental is enabled — the
 * registration IS the capability). It fronts the kernel {@link OverrideTable} (the
 * pair registry the admission seams read), keeps the victim side of death/quit
 * hygiene, and — on profiles that do NOT already shrink the hurt window — owns the
 * per-hit window write that turns a live override into a real fast re-hit.
 *
 * <h2>Where the acceleration actually happens</h2>
 * <p>Vanilla's hurt window is one GLOBAL {@code maximumNoDamageTicks} field: a hit
 * re-hits faster only when the field is small at the moment the hit resets it. So
 * per-(victim, attacker) pricing is achieved by writing that field per hit, keyed
 * to THAT hit's attacker — a third attacker's hit writes the un-scaled window, so
 * nothing global is persistently erased.
 * <ul>
 *   <li><b>CT8c profile:</b> {@code Ct8cIframesUnit} already re-writes the window
 *       every hit; it scales its own write by the pair factor. This service's
 *       fallback stands down entirely ({@code CT8C_IFRAMES} enabled ⇒ early
 *       return) so the field has exactly one writer.</li>
 *   <li><b>Plain profile:</b> nothing else shrinks the window, so this service's
 *       {@link #onDamage} pre-apply handler prices it —
 *       {@code round(baseline * factor)} off the victim's captured baseline (a MAX
 *       write, never a counter write, so the spawn-invulnerability trap stays
 *       unreachable), restored when the window drains via the shared
 *       {@link IframeWindowGuard}.</li>
 * </ul>
 * The frozen fast-path gate and the region adopt/reject predicates read the live
 * (now-scaled) window and follow automatically — no separate override read is
 * needed there, and no double-count is possible.
 */
public final class HitTimingService implements HitTimingOverrides, Listener {

    private final OverrideTable overrides;
    private final TickClock clock;
    private final Supplier<Snapshot> snapshot;
    /** The fallback window guard — active only when no window-shrinking profile owns the field. */
    private final IframeWindowGuard fallbackGuard;

    public HitTimingService(OverrideTable overrides, TickClock clock, Scheduling scheduling,
            Supplier<Snapshot> snapshot) {
        this.overrides = overrides;
        this.clock = clock;
        this.snapshot = snapshot;
        this.fallbackGuard = new IframeWindowGuard(scheduling);
    }

    /* ------------------------------- the API ------------------------------- */

    @Override
    public void overrideWindow(UUID victim, UUID attacker, double factor, int durationTicks) {
        overrides.register(victim, attacker, factor, durationTicks, clock.current());
    }

    @Override
    public boolean isActive(UUID victim, UUID attacker) {
        return overrides.isActive(victim, attacker, clock.current());
    }

    @Override
    public void clear(UUID victim, UUID attacker) {
        overrides.clear(victim, attacker);
    }

    @Override
    public void clearVictim(UUID victim) {
        overrides.clearVictim(victim);
    }

    /* ---------------------- death/quit hygiene (S5) ------------------------ */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        overrides.clearVictim(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        overrides.clearVictim(event.getEntity().getUniqueId());
    }

    /* ----------------- the plain-profile fallback writer ------------------- */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (snapshot.get().enabled(Feature.CT8C_IFRAMES)) {
            return; // CT8c owns and scales the per-hit window write; do not double-write the field
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getEntity() instanceof LivingEntity victim)
                || !(event.getDamager() instanceof Player attacker)) {
            return;
        }
        double factor = overrides.factorFor(victim.getUniqueId(), attacker.getUniqueId(), clock.current());
        if (factor >= WindowPricing.MAX_FACTOR) {
            return; // zero-touch: no accelerating override for this pair
        }
        // Price this hit's resulting window off the CAPTURED baseline (never the
        // already-shrunk live field), so a re-hit within the accelerated window
        // never spirals. A MAX write only — the counter is never touched.
        guardShrink(victim, factor);
    }

    private void guardShrink(LivingEntity victim, double factor) {
        int scaled = WindowPricing.price(fallbackGuard.baseline(victim), factor);
        fallbackGuard.shrink(victim, scaled);
    }

    /* ------------------------------ lifecycle ------------------------------ */

    /** The periodic tick sweep of lapsed pairs (S3), driven from the plugin's global task. */
    public void sweep() {
        overrides.sweep(clock.current());
    }

    /** Disable flush: restore every window the fallback still holds (zero residue). */
    public void shutdown() {
        fallbackGuard.restoreAll();
    }
}

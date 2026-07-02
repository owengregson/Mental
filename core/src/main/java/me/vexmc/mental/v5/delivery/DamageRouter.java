package me.vexmc.mental.v5.delivery;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

/**
 * Routes an {@code EntityDamageByEntityEvent} to the victim's transaction
 * (spec §3.4). It reads the session's {@code activeInbound} slot — present (a
 * fast-path or synthetic hit) ⇒ dispatch by that transaction's {@link HitSource};
 * absent ⇒ mint a {@code Vanilla} transaction with an owning-thread-built context.
 *
 * <p>Registered at LOWEST so the transaction is established at the entry of the
 * damage pass, before the damage shapers (4B) contribute and the knockback unit
 * (4A2, MONITOR) reads. 4A1 routes only — no feature consumes the transaction, so
 * the router mints the Vanilla transaction to exercise the seam but modifies the
 * event in NO way (zero-touch). The shared "DamageRouter slot" the knockback unit
 * reads lands with the knockback family (4A2.1); here the mint is local.</p>
 */
public final class DamageRouter implements Listener {

    private final SessionService sessions;
    private final TickClock clock;
    private final AtomicLong ids = new AtomicLong();

    public DamageRouter(SessionService sessions, TickClock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return; // player victims only — non-player targets keep the vanilla path
        }
        CombatSession session = sessions.sessionFor(victim.getUniqueId());
        if (session == null) {
            return;
        }
        HitTransaction active = session.activeInbound();
        if (active != null) {
            // A fast-path/synthetic hit is in flight — its HitSource is already
            // typed. 4A1 has no consumer; the dispatch seam lands in 4A2/4B.
            return;
        }
        // Vanilla source: mint the transaction (no fast path set the slot). Not
        // submitted anywhere in 4A1 — the DamageShaper consumes it in 4B.
        mintVanilla(sourceFor(null, event.getCause()), attackerId(event), victim.getUniqueId());
    }

    /** The typed source of this hit: an in-flight transaction's, or a fresh Vanilla one. */
    static HitSource sourceFor(HitTransaction active, DamageCause cause) {
        return active != null ? active.context().source() : new HitSource.Vanilla(cause.name());
    }

    /** Mints a REGISTERED Vanilla transaction with an owning-thread-built context. */
    HitTransaction mintVanilla(HitSource source, UUID attackerId, UUID victimId) {
        HitContext context = new HitContext(
                new HitId(ids.incrementAndGet()), source, attackerId, victimId,
                new SprintVerdict(false, null, clock.current()), false, false, null, clock.current());
        return new HitTransaction(context);
    }

    private static UUID attackerId(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        return damager == null ? null : damager.getUniqueId();
    }
}

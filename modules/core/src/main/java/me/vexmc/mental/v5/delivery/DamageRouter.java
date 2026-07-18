package me.vexmc.mental.v5.delivery;

import java.util.UUID;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.model.HitContext;
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
    private final HitIds ids;

    public DamageRouter(SessionService sessions, TickClock clock, HitIds ids) {
        this.sessions = sessions;
        this.clock = clock;
        this.ids = ids;
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
            // typed (Melee, RodPull, …). Establish it as this event's transaction
            // for the knockback unit (MONITOR) to read.
            session.beginEvent(active);
            return;
        }
        // No fast path set the slot: mint a Vanilla transaction with an
        // owning-thread-built context and establish it for this event. A
        // server-side melee (a mob, another plugin, or the tester's server-side
        // attack) is Vanilla(ENTITY_ATTACK) — the knockback unit treats that as
        // melee; a RodPull is never re-derived here (B6).
        session.beginEvent(mintVanilla(
                new HitSource.Vanilla(event.getCause().name()), attackerId(event), victim.getUniqueId(),
                attackerSprinting(event)));
    }

    /** The typed source of this hit: an in-flight transaction's, or a fresh Vanilla one. */
    static HitSource sourceFor(HitTransaction active, DamageCause cause) {
        return active != null ? active.context().source() : new HitSource.Vanilla(cause.name());
    }

    /**
     * Mints a REGISTERED Vanilla transaction with an owning-thread-built context. The
     * {@code attackerSprinting} value is the SAME live {@code isSprinting()} the
     * knockback engine reads for a Vanilla-source hit (the region thread, ahead of the
     * MONITOR engine read within the one event dispatch — {@code isSprinting()} cannot
     * change between them), carried into the sprint verdict PURELY so the delivery
     * journal tells the truth: the engine reads live, so a hardcoded
     * {@code SprintVerdict(false)} printed {@code sprint=f} for hits that shipped
     * sprint-scale (S4). {@code fresh} stays null — no wire view exists for a Vanilla
     * mint (the journal prints {@code -}) — and the engine still re-reads live for a
     * Vanilla source, so which value the ENGINE consumes is unchanged.
     */
    HitTransaction mintVanilla(HitSource source, UUID attackerId, UUID victimId, boolean attackerSprinting) {
        HitContext context = new HitContext(
                ids.next(), source, attackerId, victimId,
                new SprintVerdict(attackerSprinting, null, clock.current()), false, null, clock.current());
        return new HitTransaction(context);
    }

    private static UUID attackerId(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        return damager == null ? null : damager.getUniqueId();
    }

    /** The attacker's live sprint flag — the same read the Vanilla-source engine uses; false for a non-player damager. */
    private static boolean attackerSprinting(EntityDamageByEntityEvent event) {
        return event.getDamager() instanceof Player player && player.isSprinting();
    }
}

package me.vexmc.mental.v5.delivery;

import me.vexmc.mental.api.event.KnockbackApplyEvent;
import me.vexmc.mental.kernel.combo.ComboTracker;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.Directive;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.VelocityValve;
import me.vexmc.mental.v5.Vectors;
import me.vexmc.mental.v5.feature.combo.ComboEvents;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

/**
 * The sole {@code PlayerVelocityEvent} writer (spec §3.5): one global listener
 * routing to the victim's desk on the victim's owning region thread. Apply and
 * record are one desk call — there is no HIGH/MONITOR pair (B4).
 *
 * <p>When the victim's desk has no pending decision the event is left exactly as
 * it stands (foreign/vanilla velocity), so with the knockback feature off — and
 * in all of 4A1, where nothing submits to the desk — this listener does nothing
 * (zero-touch). With a decision pending it fires {@link KnockbackApplyEvent} (API
 * shape verbatim), then either withdraws Mental's decision on a third-party
 * cancel or resolves the desk with the post-listener velocity and executes the
 * returned {@link Directive}.</p>
 */
public final class DeskRouter implements Listener {

    private final SessionService sessions;
    private final VelocityValve valve;
    private final TickClock clock;
    private final ComboEvents comboEvents;

    public DeskRouter(SessionService sessions, VelocityValve valve, TickClock clock, ComboEvents comboEvents) {
        this.sessions = sessions;
        this.valve = valve;
        this.clock = clock;
        this.comboEvents = comboEvents;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        Player victim = event.getPlayer();
        CombatSession session = sessions.sessionFor(victim.getUniqueId());
        if (session == null) {
            return;
        }
        DeliveryDesk desk = session.desk();
        KnockbackVector formula = desk.pendingFormula();
        if (formula == null) {
            return; // no Mental decision — leave the vanilla velocity (zero-touch)
        }
        HitContext context = desk.pendingContext();
        KnockbackApplyEvent api = new KnockbackApplyEvent(
                victim, resolveAttacker(context), Vectors.toBukkit(formula));
        Bukkit.getPluginManager().callEvent(api);
        if (api.isCancelled()) {
            // A third party wants vanilla velocity to stand: withdraw the exact
            // decision (by HitId — never withdraw-all, B4) and leave the event.
            if (context != null) {
                desk.withdraw(context.id());
            }
            return;
        }
        Vector velocity = api.velocity();
        Directive directive = desk.resolve(velocity.getX(), velocity.getY(), velocity.getZ());
        DirectiveExecutor.apply(directive, sinkFor(event), victim.getUniqueId(), valve);
        if (context != null && directive.ship() != null) {
            Deliveries.recordDelivered(session, context.source(), directive.ship());
            // The combo detector's shipped-hit feed (combo-hold §3.1): a melee knock
            // that actually shipped to this victim advances the chain on its attacker.
            // This is the ONE ship seam every melee delivery funnels through — the
            // region path, the pre-sent/pinned adopt, and the blocked re-delivery all
            // resolve here — so it needs no per-path duplication.
            feedComboOnShip(session, victim, context);
        }
    }

    /** Feeds the victim's combo tracker one shipped melee knock; fires any transition. Owning thread. */
    private void feedComboOnShip(CombatSession session, Player victim, HitContext context) {
        ComboTracker tracker = session.comboTracker();
        if (tracker == null) {
            return; // module off — zero-touch
        }
        if (!isMelee(context.source()) || context.attackerId() == null) {
            return; // rods/projectiles/self-launches never form combos
        }
        comboEvents.fire(victim, tracker.onKnockShipped(context.attackerId(), clock.current()));
    }

    private static boolean isMelee(HitSource source) {
        return source instanceof HitSource.Melee
                || (source instanceof HitSource.Vanilla vanilla && "ENTITY_ATTACK".equals(vanilla.damageCause()));
    }

    private static VelocitySink sinkFor(PlayerVelocityEvent event) {
        return new VelocitySink() {
            @Override public void ship(KnockbackVector velocity) {
                event.setVelocity(Vectors.toBukkit(velocity));
            }
            @Override public void cancel() {
                event.setCancelled(true);
            }
        };
    }

    /** Best-effort attacker resolution for the API event; null when unavailable. */
    private static LivingEntity resolveAttacker(HitContext context) {
        if (context == null || context.attackerId() == null) {
            return null;
        }
        try {
            Entity entity = Bukkit.getEntity(context.attackerId());
            return entity instanceof LivingEntity living ? living : null;
        } catch (Throwable offRegion) {
            return null; // a cross-region attacker read is best-effort; the API attacker is nullable
        }
    }
}

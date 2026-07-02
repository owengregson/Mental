package me.vexmc.mental.v5.delivery;

import me.vexmc.mental.api.event.KnockbackApplyEvent;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.Directive;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.VelocityValve;
import me.vexmc.mental.v5.Vectors;
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

    public DeskRouter(SessionService sessions, VelocityValve valve) {
        this.sessions = sessions;
        this.valve = valve;
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

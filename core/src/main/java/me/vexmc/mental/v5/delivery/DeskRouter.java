package me.vexmc.mental.v5.delivery;

import java.util.List;
import java.util.UUID;
import me.vexmc.mental.api.event.KnockbackApplyEvent;
import me.vexmc.mental.kernel.combo.ComboTracker;
import me.vexmc.mental.kernel.combo.ComboTransition;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.Directive;
import me.vexmc.mental.kernel.delivery.ValvePayload;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.TickStamp;
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

    /** One velocity dispatch's arm intent, stashed by HIGH and confirmed by MONITOR. */
    private record PendingArm(PlayerVelocityEvent event, UUID victim, ValvePayload payload) {}

    /**
     * One velocity dispatch's combo-feed intent, stashed at HIGH and fed at the
     * MONITOR confirm — the D1 ruling: a chain advances only for a knock the
     * client actually received (the dead-arm lesson, applied to the feed).
     */
    private record PendingFeed(PlayerVelocityEvent event, CombatSession session, Player victim,
                               UUID attackerId, TickStamp tick) {}

    /**
     * The HIGH handler's arm intent, awaiting the MONITOR confirm. A ThreadLocal
     * because nested {@code PlayerVelocityEvent} dispatches (a plugin calling
     * {@code setVelocity} inside our {@code KnockbackApplyEvent}) fully complete
     * before the intent is set, and Folia dispatches concurrent velocity events on
     * different region threads — the ThreadLocal plus the event-identity check make
     * both safe (one entry per region thread, overwritten by the next intent).
     */
    private final ThreadLocal<PendingArm> pendingArm = new ThreadLocal<>();

    /**
     * The HIGH handler's combo-feed intent, awaiting the MONITOR confirm. Its own
     * ThreadLocal beside {@link #pendingArm} for the same nested-dispatch reason:
     * a re-entrant velocity dispatch (e.g. the blocked path's authoritative
     * {@code setVelocity}) gets its own identity-checked stash, one entry per
     * region thread.
     */
    private final ThreadLocal<PendingFeed> pendingFeed = new ThreadLocal<>();

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
                victim, resolveAttacker(context), Vectors.toBukkit(formula),
                context == null ? null : context.attackerId(), sourceOf(context));
        Bukkit.getPluginManager().callEvent(api);
        if (api.getOutcome() == KnockbackApplyEvent.Outcome.YIELDED) {
            // Yield: vanilla's own velocity stands; withdraw so the desk forgets the
            // decision (by HitId — never withdraw-all, B4) and leave the event. A
            // yielded knock never advances a combo chain — Mental did not ship it.
            if (context != null) {
                desk.withdraw(context.id());
            }
            return;
        }
        // SUPPRESSED ships an explicit zero vector (distinct from YIELDED — a
        // zero-velocity melee still shipped, so it advances the chain, §8); SHIP
        // ships the last-written vector.
        Vector velocity = api.getOutcome() == KnockbackApplyEvent.Outcome.SUPPRESSED
                ? new Vector(0, 0, 0)
                : api.velocity();
        Directive directive = desk.resolve(velocity.getX(), velocity.getY(), velocity.getZ());
        ValvePayload armIntent = DirectiveExecutor.apply(directive, sinkFor(event));
        if (armIntent != null) {
            // Do NOT arm here (HIGH): a HIGHEST/MONITOR foreign listener may still
            // cancel/modify this event, leaving a dead arm that aliases the next
            // byte-identical hit's duplicate. Stash the intent and confirm at MONITOR.
            pendingArm.set(new PendingArm(event, victim.getUniqueId(), armIntent));
        }
        if (context != null && directive.ship() != null) {
            Deliveries.recordDelivered(session, context.source(), directive.ship());
            // The combo feed (gen-3 D1): stashed here, fed at the MONITOR confirm so a
            // chain only advances for a knock that survived every listener. This is
            // still the ONE ship seam every melee delivery funnels through — the
            // region path, the pre-sent/pinned adopt, AND the blocked re-delivery
            // (KnockbackUnit.deliverBlockedKnock's authoritative setVelocity fires a
            // real PlayerVelocityEvent that re-enters this handler) — so it needs no
            // per-path duplication. The ledger record and desk journal above remain
            // decision-time (HIGH) by design; the valve and the combo feed are the
            // two confirmed-ship artifacts.
            if (session.comboTracker() != null && isMelee(context.source()) && context.attackerId() != null) {
                pendingFeed.set(new PendingFeed(event, session, victim, context.attackerId(), clock.current()));
            }
        }
    }

    /**
     * The arm confirmation (F3): the valve is armed only after the velocity event has
     * survived every listener priority — not cancelled, final velocity still quantizing
     * to the pre-sent payload — because vanilla emits the matching ENTITY_VELOCITY
     * duplicate only for a surviving event. Arming at HIGH left a dead arm behind any
     * HIGHEST/MONITOR foreign cancel/modify, and a dead arm aliases the next
     * byte-identical hit's duplicate. Read-only on the event: the desk (HIGH) stays the
     * sole PlayerVelocityEvent writer.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerVelocityConfirm(PlayerVelocityEvent event) {
        // The combo feed (D1), consumed first and independently of the arm confirm:
        // both are identity-checked stashes keyed on THIS dispatch. The feed gate is
        // !isCancelled() ONLY — a foreign MODIFY still ships a knock (unlike the arm,
        // which also demands the wire encoding still quantize to the planned payload).
        PendingFeed feed = pendingFeed.get();
        if (feed != null && feed.event() == event) {
            pendingFeed.remove();
            if (!event.isCancelled()) {
                ComboTracker tracker = feed.session().comboTracker();
                if (tracker != null) {
                    List<ComboTransition> transitions = tracker.onKnockShipped(feed.attackerId(), feed.tick());
                    comboEvents.fire(feed.victim(), tracker.view(), transitions);
                }
            }
        }
        PendingArm intent = pendingArm.get();
        if (intent == null || intent.event() != event) {
            return; // no arm planned for THIS dispatch (identity, not equality)
        }
        pendingArm.remove();
        Vector velocity = event.getVelocity();
        if (confirmsArm(event.isCancelled(),
                velocity.getX(), velocity.getY(), velocity.getZ(), intent.payload())) {
            valve.arm(intent.victim(), intent.payload(), clock.current());
        }
    }

    /** Pure confirm predicate (unit-pinned): survive + still quantize to the planned wire encoding. */
    static boolean confirmsArm(boolean cancelled, double x, double y, double z, ValvePayload planned) {
        return !cancelled
                && ValvePayload.of(planned.entityId(), new KnockbackVector(x, y, z)).equals(planned);
    }

    private static boolean isMelee(HitSource source) {
        return source instanceof HitSource.Melee
                || (source instanceof HitSource.Vanilla vanilla && "ENTITY_ATTACK".equals(vanilla.damageCause()));
    }

    /**
     * The api {@link KnockbackApplyEvent.Source} for this hit's kernel provenance,
     * mapped exhaustively over the real {@link HitSource} constants: everything
     * {@link #isMelee} already treats as a melee ship (a direct {@code Melee} or a
     * vanilla {@code ENTITY_ATTACK}) is MELEE; the two fishing-rod sources
     * ({@code RodPull}, {@code Bobber}) are ROD; the projectiles ({@code Arrow},
     * {@code Thrown}) are PROJECTILE; every other vanilla/environmental cause is
     * OTHER. A null context (no Mental provenance) degrades to OTHER.
     */
    private static KnockbackApplyEvent.Source sourceOf(HitContext context) {
        if (context == null) {
            return KnockbackApplyEvent.Source.OTHER;
        }
        HitSource source = context.source();
        if (isMelee(source)) {
            return KnockbackApplyEvent.Source.MELEE;
        }
        if (source instanceof HitSource.RodPull || source instanceof HitSource.Bobber) {
            return KnockbackApplyEvent.Source.ROD;
        }
        if (source instanceof HitSource.Arrow || source instanceof HitSource.Thrown) {
            return KnockbackApplyEvent.Source.PROJECTILE;
        }
        return KnockbackApplyEvent.Source.OTHER;
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

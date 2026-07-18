package me.vexmc.mental.v5.feature.combo;

import java.util.List;
import java.util.UUID;
import me.vexmc.mental.api.event.ComboChainAbortEvent;
import me.vexmc.mental.api.event.ComboChainEvent;
import me.vexmc.mental.api.event.ComboEndEvent;
import me.vexmc.mental.api.event.ComboHitEvent;
import me.vexmc.mental.api.event.ComboStartEvent;
import me.vexmc.mental.kernel.combo.ComboAbortReason;
import me.vexmc.mental.kernel.combo.ComboEndReason;
import me.vexmc.mental.kernel.combo.ComboTransition;
import me.vexmc.mental.kernel.combo.ComboViewState;
import me.vexmc.mental.kernel.model.TickStamp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Fires the public generation-3 combo events for a kernel {@link ComboTransition}
 * — the one place the kernel's Bukkit-free transition signal becomes a Bukkit
 * event (combo-hold §4, widened for gen-3). Called on the victim's owning region
 * thread by every site that mutates a tracker (the session sweep, the delivery
 * fold, the retaliation stamp), so the events always fire on the region thread
 * and never async.
 *
 * <p>Each {@code fire} first {@link ComboViewBook#publish publishes} the
 * post-mutation view, THEN dispatches the events — so an on-thread
 * {@code comboOn} from inside a handler observes the post-transition state (§6).
 * The MANDATORY pattern at every call site is mutate-then-fire: capture the
 * transition(s) in a local, call {@code tracker.view()}, then {@code fire(...)};
 * inlining the mutation into the {@code fire(...)} argument list would publish
 * the pre-mutation view (Java evaluates arguments left-to-right).</p>
 *
 * <p>This same transition point also drives the reach handicap (design §1): the
 * handicap must be applied on combo START and removed on EVERY active end reason,
 * so it rides {@link #fireOne} rather than a separate seam — one instance shared
 * across all three transition-firing call sites keeps the apply/remove leg exactly
 * coincident with the api events, on the same owning thread. The handicap
 * self-gates (off, or below 1.20.5, it does nothing), so a module with the
 * sub-feature disabled pays only one boolean read per transition. Aborts, chain
 * advances, and continuation hits never touch the handicap or the servo memory —
 * those side effects stay bound to STARTED/ENDED alone.</p>
 *
 * <p>The attacker id is resolved to a {@link LivingEntity} best-effort: an
 * off-region read on Folia yields null, which the api events allow — the victim
 * is always present.</p>
 */
public final class ComboEvents {

    private final ComboReachHandicap reachHandicap;
    private final ComboViewBook views;

    public ComboEvents(ComboReachHandicap reachHandicap, ComboViewBook views) {
        this.reachHandicap = reachHandicap;
        this.views = views;
    }

    /** The published-view map — SessionService forgets a retired victim's view through it. */
    public ComboViewBook views() {
        return views;
    }

    /**
     * Publishes {@code view} then fires the api event for {@code transition}, or
     * nothing for {@link ComboTransition#NONE} / null (the view is left as it was —
     * a no-op transition changed no state).
     */
    public void fire(Player victim, ComboViewState view, ComboTransition transition) {
        if (transition == null || transition.kind() == ComboTransition.Kind.NONE) {
            return;
        }
        views.publish(victim.getUniqueId(), view);
        fireOne(victim, transition);
    }

    /**
     * Publishes {@code view} then fires every transition in order (the balanced
     * END-then-START pair a {@code minHits == 1} restart produces, or a single
     * transition). A list of only NONE transitions publishes nothing and fires
     * nothing.
     */
    public void fire(Player victim, ComboViewState view, List<ComboTransition> transitions) {
        if (transitions == null || transitions.isEmpty()) {
            return;
        }
        boolean any = false;
        for (ComboTransition transition : transitions) {
            if (transition != null && transition.kind() != ComboTransition.Kind.NONE) {
                any = true;
            }
        }
        if (!any) {
            return;
        }
        views.publish(victim.getUniqueId(), view);
        for (ComboTransition transition : transitions) {
            fireOne(victim, transition);
        }
    }

    /** Fires the matching api event for {@code transition}, or nothing for {@link ComboTransition#NONE}. */
    private void fireOne(Player victim, ComboTransition transition) {
        if (transition == null || transition.kind() == ComboTransition.Kind.NONE) {
            return;
        }
        switch (transition.kind()) {
            case STARTED -> {
                // Drive the reach handicap at the SAME transition the api events fire
                // from (design §1, leg 1): apply on start.
                reachHandicap.onComboStart(victim);
                Bukkit.getPluginManager().callEvent(new ComboStartEvent(
                        victim, resolve(transition.attacker()), transition.attacker(),
                        transition.hits(), tickValue(transition.tick())));
            }
            case ENDED -> {
                reachHandicap.onComboEnd(victim);
                // Clear the pocket-servo memory at the combo END so the NEXT combo (a
                // different attacker, or a re-engaged chain) starts memoryless as designed
                // — never seeding the V2 dynamic target's cross-hit smoothing from a prior
                // combo's/attacker's closing state. Forget on END, never START: within a
                // combo the memory must persist hit-to-hit. Idempotent and empty under the
                // shipped ANCHOR default (the memory is unwritten there).
                ComboPredictor.forget(victim.getUniqueId());
                Bukkit.getPluginManager().callEvent(new ComboEndEvent(
                        victim, resolve(transition.attacker()), transition.attacker(),
                        map(transition.reason()), transition.hits(), tickValue(transition.tick())));
            }
            case CHAIN_OPENED, CHAIN_ADVANCED -> Bukkit.getPluginManager().callEvent(new ComboChainEvent(
                    victim, resolve(transition.attacker()), transition.attacker(),
                    transition.hits(), tickValue(transition.gapDeadline())));
            case CHAIN_ABORTED -> Bukkit.getPluginManager().callEvent(new ComboChainAbortEvent(
                    victim, transition.attacker(), transition.hits(), mapAbort(transition.abortReason())));
            case HIT -> Bukkit.getPluginManager().callEvent(new ComboHitEvent(
                    victim, transition.attacker(), transition.hits(),
                    tickValue(transition.tick()), tickValue(transition.gapDeadline())));
            case NONE -> { }
        }
    }

    /** The api-sentinel translation for a transition tick — kernel NO_TICK must never leak. */
    private static long tickValue(TickStamp stamp) {
        return ComboViewBook.tickValue(stamp);
    }

    /** Best-effort entity resolution; null off-region (Folia) or when the entity is gone / non-living. */
    private static LivingEntity resolve(UUID attackerId) {
        if (attackerId == null) {
            return null;
        }
        try {
            Entity entity = Bukkit.getEntity(attackerId);
            return entity instanceof LivingEntity living ? living : null;
        } catch (Throwable offRegion) {
            return null;
        }
    }

    /** Maps the kernel's end reason onto the public enum (kept separate so the kernel stays api-free). */
    private static ComboEndEvent.Reason map(ComboEndReason reason) {
        return switch (reason) {
            case EXPIRED -> ComboEndEvent.Reason.EXPIRED;
            case RETALIATION -> ComboEndEvent.Reason.RETALIATION;
            case GROUNDED -> ComboEndEvent.Reason.GROUNDED;
            case BLOWOUT -> ComboEndEvent.Reason.BLOWOUT;
            case RETIRED -> ComboEndEvent.Reason.RETIRED;
            case DISABLED -> ComboEndEvent.Reason.DISABLED;
        };
    }

    /** Maps the kernel's developing-chain abort reason onto the public enum. */
    private static ComboChainAbortEvent.Reason mapAbort(ComboAbortReason reason) {
        return switch (reason) {
            case EXPIRED -> ComboChainAbortEvent.Reason.EXPIRED;
            case SWITCHED -> ComboChainAbortEvent.Reason.SWITCHED;
            case RETALIATION -> ComboChainAbortEvent.Reason.RETALIATION;
            case RETIRED -> ComboChainAbortEvent.Reason.RETIRED;
            case DISABLED -> ComboChainAbortEvent.Reason.DISABLED;
        };
    }
}

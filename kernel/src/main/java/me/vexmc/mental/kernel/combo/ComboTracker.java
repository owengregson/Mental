package me.vexmc.mental.kernel.combo;

import java.util.UUID;
import me.vexmc.mental.kernel.model.TickStamp;

/**
 * The combo detector (combo-hold §3.1) — a pure per-victim state machine,
 * mutated only by the owning session thread (D2). It consumes immutable signals
 * and never reads a live entity: the delivery fold feeds it every shipped melee
 * knock with attacker identity ({@link #onKnockShipped}); the victim's own
 * accepted melee feeds it a retaliation ({@link #onOwnHitLanded}); the session
 * tick feeds it grounded state and pair separation ({@link #onTick}). It holds
 * no Bukkit, no clock, and no config beyond an immutable {@link ComboRules}.
 *
 * <p>A chain with attacker {@code A} is <b>active</b> once {@code A} has landed
 * {@code minHits} melee hits with each inter-hit gap {@code <= maxGapTicks}. It
 * ends on: gap expiry (checked lazily at reads AND every {@link #onTick}); the
 * victim landing any melee hit (retaliation); {@code groundedRunTicks}
 * consecutive grounded ticks; separation past {@code blowoutBlocks}; or an
 * explicit {@link #reset} (retire/disable). A hit from a DIFFERENT attacker
 * abandons the old chain and restarts on the new one.</p>
 *
 * <p>Each mutation returns a {@link ComboTransition} so the core can fire the
 * balanced api start/end events without inspecting internals. A false positive
 * is the design's worst case and costs only a clamped KB nudge, so the
 * thresholds are deliberately conservative.</p>
 */
public final class ComboTracker {

    private final ComboRules rules;

    /** The current chain's attacker, or null when there is no chain. */
    private UUID attackerId;
    /** The chain length so far (developing until it reaches {@code minHits}). */
    private int hits;
    /** The tick of the most recent shipped hit — the gap clock. */
    private TickStamp lastHitTick = TickStamp.NO_TICK;
    /** Consecutive grounded ticks since the last knock (a fresh knock re-launches → 0). */
    private int groundedRun;
    /** The tick the chain became active, or {@link TickStamp#NO_TICK}. */
    private TickStamp activeSince = TickStamp.NO_TICK;
    /** Cached {@code hits >= minHits}: the chain has crossed into an active combo. */
    private boolean active;

    public ComboTracker(ComboRules rules) {
        this.rules = rules;
    }

    /** The rules this tracker was built with — the reconciler compares to detect a config change. */
    public ComboRules rules() {
        return rules;
    }

    /** Whether an active combo is being held right now. */
    public boolean active() {
        return active;
    }

    /** The attacker of the ACTIVE combo (for the separation lookup), or null. */
    public UUID activeAttacker() {
        return active ? attackerId : null;
    }

    /** An immutable read for the view publish — {@link ComboSnapshot#attackerId()} null unless active. */
    public ComboSnapshot snapshot() {
        return active
                ? new ComboSnapshot(attackerId, hits, activeSince)
                : new ComboSnapshot(null, hits, TickStamp.NO_TICK);
    }

    /**
     * A melee knock from {@code attacker} shipped to this victim at {@code tick}
     * (fed from the delivery fold). Continues the chain when the attacker is the
     * same and the gap holds; a different attacker or an expired gap abandons the
     * old chain (an END if it was active) and starts a fresh one on {@code
     * attacker}. Returns the STARTED transition when this hit makes the chain
     * active, or the ENDED transition for the abandoned old combo, else NONE.
     */
    public ComboTransition onKnockShipped(UUID attacker, TickStamp tick) {
        if (attacker == null) {
            return ComboTransition.NONE;
        }
        ComboEndReason abandonedReason = null;
        UUID abandonedAttacker = attackerId;
        if (attackerId != null) {
            boolean expired = gapExceeded(tick);
            boolean switched = !attacker.equals(attackerId);
            if (expired || switched) {
                // The old chain is over; note the END only if it had gone active
                // (so a developing chain drops silently and events stay balanced).
                if (active) {
                    abandonedReason = ComboEndReason.EXPIRED;
                }
                resetChain();
            }
        }
        if (attackerId == null) {
            attackerId = attacker;
            hits = 0;
        }
        hits++;
        lastHitTick = tick;
        groundedRun = 0; // a fresh knock re-launches the victim
        ComboTransition start = ComboTransition.NONE;
        if (!active && hits >= rules.minHits()) {
            active = true;
            activeSince = tick;
            start = ComboTransition.started(attackerId, hits);
        }
        // A single hit cannot both end an old active combo AND re-activate a new
        // one (the new chain is at one hit), so report at most one transition.
        if (abandonedReason != null) {
            return ComboTransition.ended(abandonedAttacker, abandonedReason);
        }
        return start;
    }

    /**
     * The victim (this tracker's owner) landed a melee hit of their own — a
     * retaliation that ends any combo held against them (combo-hold §3.1). Resets
     * the chain unconditionally; returns ENDED(RETALIATION) only when a combo was
     * active (to balance a prior START), else NONE. The tick is unused today but
     * kept for signal symmetry.
     */
    public ComboTransition onOwnHitLanded(TickStamp tick) {
        boolean wasActive = active;
        UUID endedAttacker = attackerId;
        resetChain();
        return wasActive ? ComboTransition.ended(endedAttacker, ComboEndReason.RETALIATION) : ComboTransition.NONE;
    }

    /**
     * One session tick: applies the time-driven end conditions in priority order
     * — gap expiry (lazy, checked whether active or developing), grounded run,
     * then blowout. {@code separation} is the pair's horizontal distance, or
     * {@link Double#NaN} when unknown (a NaN separation never triggers blowout).
     * Returns the ENDED transition for whichever condition fired, else NONE.
     */
    public ComboTransition onTick(TickStamp now, boolean grounded, double separation) {
        // 1. Gap expiry — a stale chain (active or developing) is abandoned.
        if (attackerId != null && gapExceeded(now)) {
            boolean wasActive = active;
            UUID endedAttacker = attackerId;
            resetChain();
            return wasActive ? ComboTransition.ended(endedAttacker, ComboEndReason.EXPIRED) : ComboTransition.NONE;
        }
        // 2. Grounded run — count consecutive grounded ticks; a real touchdown ends it.
        if (grounded) {
            groundedRun++;
        } else {
            groundedRun = 0;
        }
        if (active && groundedRun >= rules.groundedRunTicks()) {
            UUID endedAttacker = attackerId;
            resetChain();
            return ComboTransition.ended(endedAttacker, ComboEndReason.GROUNDED);
        }
        // 3. Blowout — a knock past the reach envelope ends it (NaN never does).
        if (active && !Double.isNaN(separation) && separation > rules.blowoutBlocks()) {
            UUID endedAttacker = attackerId;
            resetChain();
            return ComboTransition.ended(endedAttacker, ComboEndReason.BLOWOUT);
        }
        return ComboTransition.NONE;
    }

    /**
     * An explicit end for a reason the tracker cannot observe on its own —
     * {@link ComboEndReason#RETIRED} (session forget/quit) or
     * {@link ComboEndReason#DISABLED} (module turned off). Resets the chain;
     * returns ENDED only when a combo was active, else NONE.
     */
    public ComboTransition reset(ComboEndReason reason) {
        boolean wasActive = active;
        UUID endedAttacker = attackerId;
        resetChain();
        return wasActive ? ComboTransition.ended(endedAttacker, reason) : ComboTransition.NONE;
    }

    /* ------------------------------------------------------------------ */

    /** True when both stamps are known and the gap strictly exceeds {@code maxGapTicks}. */
    private boolean gapExceeded(TickStamp now) {
        if (!lastHitTick.known() || !now.known()) {
            return false; // an unknown clock never expires a chain (degrade to no-op)
        }
        return now.value() - lastHitTick.value() > rules.maxGapTicks();
    }

    private void resetChain() {
        attackerId = null;
        hits = 0;
        groundedRun = 0;
        active = false;
        activeSince = TickStamp.NO_TICK;
        lastHitTick = TickStamp.NO_TICK;
    }
}

package me.vexmc.mental.kernel.combo;

import java.util.List;
import java.util.UUID;
import me.vexmc.mental.kernel.math.PocketServo;
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
 * consecutive grounded ticks — scaled up to the chain's OBSERVED cadence once one
 * is measured ({@link #effectiveGroundedRunTicks()}, servo-lab 2.4.5), so a
 * slower-rhythm combo no longer dies mid-gap on its legitimate between-hit ground
 * time; separation past {@code blowoutBlocks}; or an explicit {@link #reset}
 * (retire/disable). A hit from a DIFFERENT attacker abandons the old chain and
 * restarts on the new one.</p>
 *
 * <p>Each mutation returns the {@link ComboTransition}(s) it produced so the core
 * can fire the balanced api start/end events without inspecting internals — a single
 * transition for the time-driven ends, and up to a balanced END-then-START pair for
 * {@link #onKnockShipped} (a restart at {@code minHits == 1} does both at once). A
 * false positive is the design's worst case and costs only a clamped KB nudge, so
 * the thresholds are deliberately conservative.</p>
 */
public final class ComboTracker {

    /**
     * The grounded-run jitter slack (ticks) added onto the observed cadence
     * (servo-lab 2.4.5): the lab's within-cell cadence spread is ±2 ticks (gap
     * histograms 12–16 and 17–19), so a rhythm-legitimate grounded stretch can run
     * two ticks past the smoothed cadence before it means anything.
     */
    private static final int CADENCE_RUN_SLACK = 2;

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
    /**
     * The per-chain EMA of observed inter-hit gaps ({@link Double#NaN} until the
     * chain's second hit) — the observed cadence the grounded-run threshold scales
     * with (servo-lab 2.4.5). Continuation gaps are structurally ≤ {@code
     * maxGapTicks} (a longer gap resets the chain before it is measured).
     */
    private double cadenceEmaTicks = Double.NaN;

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
     * The full immutable view of this tracker — the gen-3 publish value (unlike
     * {@link #snapshot()}, the DEVELOPING attacker and the gap clock are visible).
     */
    public ComboViewState view() {
        if (attackerId == null) {
            return ComboViewState.NONE;
        }
        return new ComboViewState(attackerId, hits, active, lastHitTick, gapDeadline());
    }

    /**
     * A melee knock from {@code attacker} shipped to this victim at {@code tick}
     * (fed from the delivery fold). Continues the chain when the attacker is the
     * same and the gap holds; a different attacker or an expired gap abandons the
     * old chain and starts a fresh one on {@code attacker}. Every knock now yields
     * an advancing transition — CHAIN_OPENED on a chain's first hit, CHAIN_ADVANCED
     * on each pre-activation hit after it, STARTED at the min-hits promotion, HIT on
     * every active continuation — so no developing edge is silent (gen-3 §5).
     *
     * <p>Returns the transitions this hit produced in fire order: an abandoned
     * chain's terminal first (ENDED if it was active, CHAIN_ABORTED if it was still
     * developing — SWITCHED-wins per §5.2), then the fresh chain's advancing
     * transition. The result is a single advance normally, or a balanced
     * terminal-then-advance pair when this hit abandons a prior chain (including the
     * {@code minHits == 1} restart, where the pair is terminal-then-STARTED). The
     * core's ComboEvents fires per transition, so the pair keeps the api
     * events balanced and re-applies the reach handicap on the new combo.</p>
     */
    public List<ComboTransition> onKnockShipped(UUID attacker, TickStamp tick) {
        if (attacker == null) {
            return List.of();
        }
        ComboTransition terminal = null;
        if (attackerId != null) {
            boolean expired = gapExceeded(tick);
            boolean switched = !attacker.equals(attackerId);
            if (expired || switched) {
                if (active) {
                    // The public end vocabulary is frozen (gen-3 §5.5): an active
                    // takeover still reports EXPIRED, matching every release before it.
                    terminal = ComboTransition.ended(attackerId, hits, ComboEndReason.EXPIRED, tick);
                } else {
                    // Developing chains DO distinguish the cause — SWITCHED wins
                    // whenever the terminating hit's attacker differs (§5.2 pin),
                    // so the "new attacker after the gap already lapsed" hit is stable.
                    terminal = ComboTransition.chainAborted(attackerId, hits,
                            switched ? ComboAbortReason.SWITCHED : ComboAbortReason.EXPIRED, tick);
                }
                resetChain();
            }
        }
        boolean opened = false;
        if (attackerId == null) {
            attackerId = attacker;
            hits = 0;
            opened = true;
        } else if (lastHitTick.known() && tick.known() && tick.value() > lastHitTick.value()) {
            // A continuation hit (the reset block above kept the chain): fold its
            // gap into the observed cadence. Gaps here are structurally within
            // maxGapTicks — a longer one already reset the chain.
            cadenceEmaTicks = PocketServo.cadenceEma(cadenceEmaTicks, tick.value() - lastHitTick.value());
        }
        hits++;
        lastHitTick = tick;
        groundedRun = 0; // a fresh knock re-launches the victim
        ComboTransition advance;
        if (!active && hits >= rules.minHits()) {
            active = true;
            activeSince = tick;
            advance = ComboTransition.started(attackerId, hits, tick, gapDeadline());
        } else if (!active) {
            advance = opened
                    ? ComboTransition.chainOpened(attackerId, tick, gapDeadline())
                    : ComboTransition.chainAdvanced(attackerId, hits, tick, gapDeadline());
        } else {
            advance = ComboTransition.hit(attackerId, hits, tick, gapDeadline());
        }
        return terminal == null ? List.of(advance) : List.of(terminal, advance);
    }

    /**
     * The victim (this tracker's owner) landed a melee hit of their own — a
     * retaliation that ends any chain held against them (combo-hold §3.1). Resets
     * the chain unconditionally; returns ENDED(RETALIATION) for an active combo (to
     * balance a prior START), CHAIN_ABORTED(RETALIATION) for a developing chain (the
     * gen-3 surface — this edge used to drop silently), else NONE. The tick stamps
     * the terminal.
     */
    public ComboTransition onOwnHitLanded(TickStamp tick) {
        boolean wasActive = active;
        boolean wasDeveloping = attackerId != null && !active;
        UUID endedAttacker = attackerId;
        int endedHits = hits;
        resetChain();
        if (wasActive) {
            return ComboTransition.ended(endedAttacker, endedHits, ComboEndReason.RETALIATION, tick);
        }
        if (wasDeveloping) {
            return ComboTransition.chainAborted(endedAttacker, endedHits, ComboAbortReason.RETALIATION, tick);
        }
        return ComboTransition.NONE;
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
            int endedHits = hits;
            resetChain();
            return wasActive
                    ? ComboTransition.ended(endedAttacker, endedHits, ComboEndReason.EXPIRED, now)
                    : ComboTransition.chainAborted(endedAttacker, endedHits, ComboAbortReason.EXPIRED, now);
        }
        // 2. Grounded run — count consecutive grounded ticks; a real touchdown ends it.
        if (grounded) {
            groundedRun++;
        } else {
            groundedRun = 0;
        }
        if (active && groundedRun >= effectiveGroundedRunTicks()) {
            UUID endedAttacker = attackerId;
            int endedHits = hits;
            resetChain();
            return ComboTransition.ended(endedAttacker, endedHits, ComboEndReason.GROUNDED, now);
        }
        // 3. Blowout — a knock past the reach envelope ends it (NaN never does).
        if (active && !Double.isNaN(separation) && separation > rules.blowoutBlocks()) {
            UUID endedAttacker = attackerId;
            int endedHits = hits;
            resetChain();
            return ComboTransition.ended(endedAttacker, endedHits, ComboEndReason.BLOWOUT, now);
        }
        return ComboTransition.NONE;
    }

    /**
     * An explicit end for a reason the tracker cannot observe on its own —
     * {@link ComboEndReason#RETIRED} (session forget/quit) or
     * {@link ComboEndReason#DISABLED} (module turned off). Resets the chain;
     * returns ENDED for an active combo, CHAIN_ABORTED with the mapped reason for a
     * developing chain (the gen-3 surface), else NONE. The tick stamps the terminal.
     */
    public ComboTransition reset(ComboEndReason reason, TickStamp tick) {
        boolean wasActive = active;
        boolean wasDeveloping = attackerId != null && !active;
        UUID endedAttacker = attackerId;
        int endedHits = hits;
        resetChain();
        if (wasActive) {
            return ComboTransition.ended(endedAttacker, endedHits, reason, tick);
        }
        if (wasDeveloping) {
            return ComboTransition.chainAborted(endedAttacker, endedHits, abortFor(reason), tick);
        }
        return ComboTransition.NONE;
    }

    private static ComboAbortReason abortFor(ComboEndReason reason) {
        return switch (reason) {
            case RETIRED -> ComboAbortReason.RETIRED;
            case DISABLED -> ComboAbortReason.DISABLED;
            case RETALIATION -> ComboAbortReason.RETALIATION;
            // GROUNDED/BLOWOUT can never reach a developing chain (active-only
            // guards); EXPIRED is the defensive mapping, not a reachable path.
            case EXPIRED, GROUNDED, BLOWOUT -> ComboAbortReason.EXPIRED;
        };
    }

    /** The chain's observed inter-hit cadence EMA, or {@link Double#NaN} before the second hit. */
    public double cadenceTicks() {
        return cadenceEmaTicks;
    }

    /* ------------------------------------------------------------------ */

    /**
     * The grounded-run threshold scaled to the OBSERVED cadence (servo-lab 2.4.5).
     * The rule's purpose is "a real touchdown ends it, brief skims survive" — but a
     * victim launched at cadence {@code c} on an era ~10-tick flight legitimately
     * sits grounded for {@code c − airTime} ticks before the next re-launch, so a
     * fixed threshold kills every slower-rhythm combo mid-gap (the lab's 17–20t
     * cells died GROUNDED at 57% coverage; the reach handicap rides combo state, so
     * it died with them). Derivation of the bound: the observed cadence plus the
     * lab's ±2-tick within-cell jitter ({@link #CADENCE_RUN_SLACK}) is the longest
     * rhythm-legitimate grounded stretch; past {@code maxGapTicks} the gap-expiry
     * rule owns the end (the two clocks stay ordered — grounded-run never outlives
     * a gap the chain cannot survive anyway); and the CONFIGURED threshold remains
     * the floor, so an unmeasured cadence — or an operator's wider setting — is
     * byte-identical to the pre-round behaviour.
     */
    private int effectiveGroundedRunTicks() {
        if (Double.isNaN(cadenceEmaTicks)) {
            return rules.groundedRunTicks();
        }
        int scaled = (int) Math.round(cadenceEmaTicks) + CADENCE_RUN_SLACK;
        return Math.max(rules.groundedRunTicks(), Math.min(rules.maxGapTicks(), scaled));
    }

    /** The tick by which the next qualifying knock must ship, or NO_TICK with no chain clock. */
    private TickStamp gapDeadline() {
        return lastHitTick.known()
                ? new TickStamp(lastHitTick.value() + rules.maxGapTicks())
                : TickStamp.NO_TICK;
    }

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
        cadenceEmaTicks = Double.NaN;
    }
}

package me.vexmc.mental.kernel.wire;

import me.vexmc.mental.kernel.model.TickStamp;

/**
 * One entry in an {@link InputLedger}'s diagnostic event ring: what the wire
 * (or the reconcile/consume machinery acting on it) did, in arrival order.
 * The ring exists for the journal's {@code trail=} token, the tester, and live
 * diagnosis — verdicts NEVER scan it (the derived state answers those in O(1)),
 * so a dropped-oldest entry can never change gameplay.
 *
 * <p>{@code eventSeq} is the ledger's all-events counter — deliberately a
 * DIFFERENT currency from the derived state's {@code seq} (the reset-gesture
 * sequence the deferred consume guards against). Folding them was the 2.5.1
 * consume-veto bug: PLAYER_INPUT fires on any of its 7 bits flipping, so an
 * observation stream sharing the gesture counter handed every strafe reverse a
 * veto over the engagement spend. Observations bump {@code eventSeq} only.</p>
 *
 * <p>{@code bits} carries the raw PLAYER_INPUT byte for {@link Kind#KEY_INPUT}
 * (forward 0x01, backward 0x02, left 0x04, right 0x08, jump 0x10, shift 0x20,
 * sprint 0x40 — the wire codec's packing), zero for every other kind.</p>
 */
public record InputEvent(Kind kind, long eventSeq, TickStamp tick, int bits) {

    /** The event vocabulary — spec §1.3 (2026-07-11 input-ledger design). */
    public enum Kind {
        /** A client START_SPRINTING entity action — a reset gesture. */
        SPRINT_START,
        /** A client STOP_SPRINTING entity action — a reset gesture. */
        SPRINT_STOP,
        /** A PLAYER_INPUT snapshot (1.21.2+) — evidence, never a gesture. */
        KEY_INPUT,
        /** The client released an item use — drops a held block credit. */
        RELEASE_USE_ITEM,
        /** The client closed a window — GUI-cycle evidence. */
        WINDOW_CLOSE,
        /** The block-hit door re-armed sprint and granted the one-shot credit. */
        BLOCK_RESET,
        /** Reconcile seeded an unseen wire from the published server flag. */
        SEED,
        /** Reconcile adopted a server-granted sprint after the quiet window. */
        ADOPT_TRUE,
        /** Reconcile adopted a genuine external un-sprint. */
        ADOPT_FALSE,
        /** Reconcile refused a stale-high adopt — the spend latch held (noted once per episode). */
        ADOPT_BLOCKED,
        /** The published server flag fell while the latch was open — the failsafe closed it. */
        SERVER_FALL,
        /** An accepted bonus hit spent the engagement (an APPLIED clear; no-ops never ring). */
        CONSUME,
        /** An ATTACK was registered against this ledger's verdict — trail context. */
        ATTACK
    }
}

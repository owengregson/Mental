package me.vexmc.mental.kernel.model;

import java.util.UUID;

/**
 * Compute-once decision inputs for one hit (R5, spec §3.1): prediction and
 * truth call the same decision function over this immutable context, and only
 * <em>when</em> the packet ships differs. Phase 2 carries the delivery-relevant
 * subset; Phase 3 extends it with the full arbiter verdicts, world rules and
 * damage inputs.
 *
 * @param sprint                 the attack-time sprint answer (from the D1 SprintWire);
 *                               {@code fresh} may be null when no wire view existed.
 * @param victimHasWire          whether the victim has a live PacketEvents user
 *                               (a pinned path when false).
 * @param compensationY          the per-hit vertical override, or null when none applies.
 * @param registeredAt           the tick the hit registered — the sweep's causal clock.
 * @param attackerYaw            the attacker's click-flush yaw at registration (the
 *                               connection's last movement-packet yaw, the value the
 *                               pre-send directs the sprint/enchant extra along), or
 *                               null when the hit never had a netty registration —
 *                               the recompute then reads the live capture.
 */
public record HitContext(HitId id, HitSource source,
                         UUID attackerId, UUID victimId,
                         SprintVerdict sprint,
                         boolean victimHasWire, Double compensationY,
                         TickStamp registeredAt, Float attackerYaw) {

    /** The pre-yaw-stamp arity: no registration yaw was frozen (Vanilla-source
     *  mints, projectile/rod contexts, packetless attackers) — the recompute
     *  reads the live capture, byte-identical to the pre-fix behavior. */
    public HitContext(HitId id, HitSource source, UUID attackerId, UUID victimId,
                      SprintVerdict sprint, boolean victimHasWire, Double compensationY,
                      TickStamp registeredAt) {
        this(id, source, attackerId, victimId, sprint, victimHasWire, compensationY,
                registeredAt, null);
    }
}

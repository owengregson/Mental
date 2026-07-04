package me.vexmc.mental.kernel.combo;

import java.util.UUID;
import me.vexmc.mental.kernel.model.TickStamp;

/**
 * An immutable read of the {@link ComboTracker}'s state, taken on the owning
 * thread and safe to publish into the frozen {@code PlayerView} (combo-hold
 * §3.1 — the {@code moveSpeedAttr} additive-component precedent). Only the
 * <em>active</em> attacker crosses the boundary: {@link #attackerId()} is null
 * for an idle or still-developing chain, exactly the "null when inactive" the
 * view's {@code comboAttackerId} component promises, so the netty pre-send and
 * the region compute read one truth for the servo-application gate.
 *
 * @param attackerId  the attacker holding an ACTIVE combo, or null when none is.
 * @param hits        the current chain length (may be non-zero while developing).
 * @param activeSince the tick the chain became active, or {@link TickStamp#NO_TICK}.
 */
public record ComboSnapshot(UUID attackerId, int hits, TickStamp activeSince) {

    /** The idle read — no chain of any kind. */
    public static final ComboSnapshot INACTIVE = new ComboSnapshot(null, 0, TickStamp.NO_TICK);

    /** True only when an attacker holds an active combo (the view's gate). */
    public boolean active() {
        return attackerId != null;
    }
}

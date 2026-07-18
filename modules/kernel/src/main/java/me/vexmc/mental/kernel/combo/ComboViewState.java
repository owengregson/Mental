package me.vexmc.mental.kernel.combo;

import java.util.UUID;
import me.vexmc.mental.kernel.model.TickStamp;

/**
 * The tracker's full published-view value — unlike {@link ComboSnapshot} it
 * surfaces the DEVELOPING attacker and the gap clock, which the gen-3 query
 * surface needs. Read on the session thread right after a mutation and
 * published as an immutable value; never a live tracker reference.
 */
public record ComboViewState(UUID attackerId, int hits, boolean active,
                             TickStamp lastKnockTick, TickStamp gapDeadline) {

    public static final ComboViewState NONE =
            new ComboViewState(null, 0, false, TickStamp.NO_TICK, TickStamp.NO_TICK);

    /** No chain at all (the map-removal shape). */
    public boolean none() {
        return attackerId == null;
    }

    /** A chain below min-hits: attacker set, not yet active. */
    public boolean developing() {
        return attackerId != null && !active;
    }
}

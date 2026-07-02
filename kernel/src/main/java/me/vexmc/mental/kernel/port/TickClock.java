package me.vexmc.mental.kernel.port;

import me.vexmc.mental.kernel.model.TickStamp;

/**
 * The kernel's only clock (spec §2). Core implements it — Paper wraps
 * {@code Bukkit.getCurrentTick()} (netty-safe on Paper); Folia advances a
 * global-region counter initialised to {@link TickStamp#NO_TICK}. Returns a
 * value type so callers never compare raw ints.
 */
public interface TickClock {
    TickStamp current();
}

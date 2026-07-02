package me.vexmc.mental.v5;

import java.util.concurrent.atomic.AtomicInteger;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;

/**
 * The Folia {@link TickClock}: a counter a global-region 1-tick task advances
 * (wired in Phase 4). It starts at {@link TickStamp#NO_TICK} — before the task
 * runs, the tick is genuinely unknown, so stale-view degradation kicks in
 * rather than a false match. The {@link AtomicInteger} makes it readable from
 * any thread.
 */
public final class CounterTickClock implements TickClock {

    private final AtomicInteger tick = new AtomicInteger(Integer.MIN_VALUE);

    /** Advance one tick: the first advance starts at 0, then increments. */
    public void advance() {
        tick.updateAndGet(value -> value == Integer.MIN_VALUE ? 0 : value + 1);
    }

    @Override
    public TickStamp current() {
        return new TickStamp(tick.get());
    }
}

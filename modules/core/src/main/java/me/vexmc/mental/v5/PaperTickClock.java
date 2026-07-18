package me.vexmc.mental.v5;

import java.util.function.IntSupplier;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;

/**
 * The Paper {@link TickClock}: wraps an {@link IntSupplier} — in production
 * {@code Bukkit::getCurrentTick} (netty-safe on Paper), in tests a counter. Not
 * yet wired into the plugin (Phase 4).
 */
public final class PaperTickClock implements TickClock {

    private final IntSupplier currentTick;

    public PaperTickClock(IntSupplier currentTick) {
        this.currentTick = currentTick;
    }

    @Override
    public TickStamp current() {
        return new TickStamp(currentTick.getAsInt());
    }
}

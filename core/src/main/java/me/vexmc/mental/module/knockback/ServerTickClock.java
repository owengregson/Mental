package me.vexmc.mental.module.knockback;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.common.scheduling.TaskHandle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A monotonic server-tick value the netty thread may read, for the era
 * attack-ordering exclusion ({@link VictimMotion#currentExcludingTick}).
 *
 * <p>On Paper this is {@code Bukkit.getCurrentTick()} — the authoritative tick,
 * a plain static counter safe to read from any thread — so the exclusion is
 * byte-identical to before. On Folia {@code Bukkit.getCurrentTick()} routes to
 * {@code RegionizedServer.getCurrentTick()}, which throws off a region thread
 * (and the netty loop is never a region thread), so a plugin-maintained global
 * counter stands in: a single {@link #start global-region task} advances it once
 * per tick, and both the packet stamp ({@code GroundTransitionWatcher}) and the
 * snapshot read ({@code PlayerStateCache}) consult the SAME counter so their tick
 * values are comparable.</p>
 *
 * <p>It is initialised to {@link VictimMotion#NO_TICK}, not 0, on purpose: before
 * the first global tick (or if the task never starts) both sites read NO_TICK,
 * which the exclusion treats as "never exclude" — so a stuck counter degrades to
 * today's inclusive (slightly floaty) view, NOT a permanent server-wide
 * exclusion. The first tick moves it to 0 and it counts up from there.</p>
 */
public final class ServerTickClock {

    private final boolean folia;
    private final IntSupplier paperTick;
    private final AtomicInteger foliaTick = new AtomicInteger(VictimMotion.NO_TICK);
    private volatile @Nullable TaskHandle task;

    public ServerTickClock(boolean folia, @NotNull IntSupplier paperTick) {
        this.folia = folia;
        this.paperTick = paperTick;
    }

    /**
     * Begins advancing the Folia global counter, one step per server tick. A
     * no-op on Paper (which reads {@code Bukkit.getCurrentTick()} directly).
     * Should run before the packet feed registers so the counter is live when
     * the first movement packet arrives.
     */
    public void start(@NotNull Scheduling scheduling) {
        if (folia && task == null) {
            task = scheduling.repeatGlobal(1L, 1L, this::tick);
        }
    }

    public void stop() {
        TaskHandle current = task;
        if (current != null) {
            current.cancel();
            task = null;
        }
    }

    /** Advances the Folia global counter one tick ({@link VictimMotion#NO_TICK} → 0 on the first). */
    void tick() {
        foliaTick.updateAndGet(value -> value == VictimMotion.NO_TICK ? 0 : value + 1);
    }

    /** The current tick, readable from the netty thread on both platforms. */
    public int currentTick() {
        return folia ? foliaTick.get() : paperTick.getAsInt();
    }
}

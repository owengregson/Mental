package me.vexmc.mental.common.scheduling;

import java.time.Duration;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * The one scheduling surface Mental code is allowed to touch.
 *
 * <p>On Paper this delegates to the classic {@code BukkitScheduler}; on Folia
 * it routes through the region-aware schedulers. Module code therefore never
 * needs to know which platform it is running on, and is region-correct by
 * construction: entity work follows the entity, location work runs on the
 * owning region, global work runs on the global tick.</p>
 */
public interface Scheduling {

    void runGlobal(@NotNull Runnable task);

    void runAt(@NotNull Location location, @NotNull Runnable task);

    /**
     * Runs on the thread that owns {@code entity}. If the entity is removed
     * before execution, {@code retired} runs instead (possibly immediately).
     */
    void runOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired);

    void runAsync(@NotNull Runnable task);

    @NotNull TaskHandle repeatGlobal(long initialTicks, long periodTicks, @NotNull Runnable task);

    @NotNull TaskHandle repeatOn(
            @NotNull Entity entity,
            long initialTicks,
            long periodTicks,
            @NotNull Runnable task,
            @NotNull Runnable retired);

    @NotNull TaskHandle repeatAsync(@NotNull Duration initial, @NotNull Duration period, @NotNull Runnable task);

    /**
     * Runs {@code task} on the thread that owns {@code entity}, after a delay of
     * {@code delayTicks} server ticks. If the entity is removed before the task
     * fires, {@code retired} runs instead (possibly immediately). On Folia the
     * EntityScheduler is used so the callback lands on the entity's owning region
     * thread; on Paper it collapses to the main thread after the delay.
     *
     * <p>This is identical to {@link #runOn} but deferred by {@code delayTicks}.
     * A delay of {@code 0} is treated as {@code 1} tick on Folia (which rejects
     * delays below 1) and runs on the next tick on Paper; callers should prefer
     * {@link #runOn} for fire-now semantics.</p>
     */
    void runOnLater(
            @NotNull Entity entity,
            long delayTicks,
            @NotNull Runnable task,
            @NotNull Runnable retired);

    @NotNull String describe();
}

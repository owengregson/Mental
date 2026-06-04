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

    @NotNull String describe();
}

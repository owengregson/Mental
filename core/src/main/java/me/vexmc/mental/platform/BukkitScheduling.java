package me.vexmc.mental.platform;

import java.time.Duration;
import java.util.Objects;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.common.scheduling.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * Classic single-threaded scheduling: every non-Folia server, 1.17 through
 * current. "Global", "at location" and "on entity" all collapse onto the main
 * thread; entity targeting still honors the retired callback so callers get
 * identical semantics on both platforms.
 */
public final class BukkitScheduling implements Scheduling {

    private final Plugin plugin;

    public BukkitScheduling(@NotNull Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void runGlobal(@NotNull Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runAt(@NotNull Location location, @NotNull Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull Entity entity) {
        // One region: the main thread owns every entity. Callers reach this only
        // from the owning thread, so the reads they are gating are region-correct.
        return true;
    }

    @Override
    public void runOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (entity.isValid()) {
                task.run();
            } else {
                retired.run();
            }
        });
    }

    @Override
    public void runAsync(@NotNull Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public @NotNull TaskHandle repeatGlobal(long initialTicks, long periodTicks, @NotNull Runnable task) {
        return new BukkitHandle(Bukkit.getScheduler().runTaskTimer(plugin, task, initialTicks, periodTicks));
    }

    @Override
    public @NotNull TaskHandle repeatOn(
            @NotNull Entity entity,
            long initialTicks,
            long periodTicks,
            @NotNull Runnable task,
            @NotNull Runnable retired) {
        BukkitHandle[] holder = new BukkitHandle[1];
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (entity.isValid()) {
                task.run();
                return;
            }
            holder[0].cancel();
            retired.run();
        }, initialTicks, periodTicks);
        holder[0] = new BukkitHandle(bukkitTask);
        return holder[0];
    }

    @Override
    public void runOnLater(
            @NotNull Entity entity, long delayTicks, @NotNull Runnable task, @NotNull Runnable retired) {
        // On Paper all threads collapse to main; after delayTicks the entity is
        // re-checked for validity, mirroring runOn's semantics with a delay.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (entity.isValid()) {
                task.run();
            } else {
                retired.run();
            }
        }, Math.max(1, delayTicks));
    }

    @Override
    public @NotNull TaskHandle repeatAsync(@NotNull Duration initial, @NotNull Duration period, @NotNull Runnable task) {
        long initialTicks = toTicks(initial);
        long periodTicks = Math.max(1, toTicks(period));
        return new BukkitHandle(
                Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, initialTicks, periodTicks));
    }

    @Override
    public @NotNull String describe() {
        return "bukkit";
    }

    private static long toTicks(Duration duration) {
        return Math.max(0, duration.toMillis() / 50);
    }

    private record BukkitHandle(@NotNull BukkitTask task) implements TaskHandle {

        @Override
        public void cancel() {
            task.cancel();
        }

        @Override
        public boolean cancelled() {
            return task.isCancelled();
        }
    }
}

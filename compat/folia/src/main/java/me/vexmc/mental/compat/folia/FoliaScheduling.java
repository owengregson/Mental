package me.vexmc.mental.compat.folia;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.common.scheduling.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Region-aware scheduling for Folia.
 *
 * <p>Compiled against paper-api 1.20.4 as Java 17 bytecode so it loads on
 * every Folia build since 1.19.4. Only constructed when the Folia capability
 * is detected; on regular Paper this class is never touched.</p>
 *
 * <p>Folia rejects tick delays below one, so delays are clamped rather than
 * letting semantically-valid "this tick" requests throw.</p>
 */
public final class FoliaScheduling implements Scheduling {

    private final Plugin plugin;

    public FoliaScheduling(@NotNull Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void runGlobal(@NotNull Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    @Override
    public void runAt(@NotNull Location location, @NotNull Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, location, task);
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull Entity entity) {
        // The real region-ownership check: true iff the region executing on this
        // thread owns the entity, so the caller may read its live state safely.
        return Bukkit.getServer().isOwnedByCurrentRegion(entity);
    }

    @Override
    public void runOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
        ScheduledTask scheduled = entity.getScheduler().run(plugin, ignored -> task.run(), retired);
        if (scheduled == null) {
            // The entity was already retired at schedule time: Folia's run()
            // returns null WITHOUT invoking the retired callback (verified by
            // javap — EntityScheduler.schedule returns false at tickCount==-1
            // without firing the consumer), unlike repeatOn/runOnLater below.
            // Fire it here so callers' cleanup (e.g. the pipeline's pending
            // removal) always runs, matching BukkitScheduling's contract.
            retired.run();
        }
    }

    @Override
    public void runAsync(@NotNull Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run());
    }

    @Override
    public @NotNull TaskHandle repeatGlobal(long initialTicks, long periodTicks, @NotNull Runnable task) {
        ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, ignored -> task.run(), clampTicks(initialTicks), clampTicks(periodTicks));
        return new FoliaHandle(scheduled);
    }

    @Override
    public @NotNull TaskHandle repeatOn(
            @NotNull Entity entity,
            long initialTicks,
            long periodTicks,
            @NotNull Runnable task,
            @NotNull Runnable retired) {
        ScheduledTask scheduled = entity.getScheduler()
                .runAtFixedRate(plugin, ignored -> task.run(), retired, clampTicks(initialTicks), clampTicks(periodTicks));
        if (scheduled == null) {
            retired.run();
            return RetiredHandle.INSTANCE;
        }
        return new FoliaHandle(scheduled);
    }

    @Override
    public void runOnLater(
            @NotNull Entity entity, long delayTicks, @NotNull Runnable task, @NotNull Runnable retired) {
        // Folia rejects delays below 1 — clamp, matching the convention in repeatOn/repeatGlobal.
        // runDelayed lands on the entity's owning region thread, so the callback is region-correct
        // (important for player inventory/potion mutations).
        ScheduledTask scheduled = entity.getScheduler()
                .runDelayed(plugin, scheduledTask -> task.run(), retired, clampTicks(delayTicks));
        if (scheduled == null) {
            // Entity already removed — fire retired immediately.
            retired.run();
        }
    }

    @Override
    public @NotNull TaskHandle repeatAsync(@NotNull Duration initial, @NotNull Duration period, @NotNull Runnable task) {
        ScheduledTask scheduled = Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                ignored -> task.run(),
                Math.max(0, initial.toMillis()),
                Math.max(1, period.toMillis()),
                TimeUnit.MILLISECONDS);
        return new FoliaHandle(scheduled);
    }

    @Override
    public @NotNull String describe() {
        return "folia";
    }

    private static long clampTicks(long ticks) {
        return Math.max(1, ticks);
    }

    private record FoliaHandle(@NotNull ScheduledTask task) implements TaskHandle {

        @Override
        public void cancel() {
            task.cancel();
        }

        @Override
        public boolean cancelled() {
            return task.isCancelled();
        }
    }

    private enum RetiredHandle implements TaskHandle {
        INSTANCE;

        @Override
        public void cancel() {}

        @Override
        public boolean cancelled() {
            return true;
        }
    }
}

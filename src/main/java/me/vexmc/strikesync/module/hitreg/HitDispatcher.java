package me.vexmc.strikesync.module.hitreg;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Schedules a damage-application task on the appropriate thread for the target
 * entity, using the Folia-aware Paper API.
 *
 * <p>On standard Paper this delegates to the entity scheduler, which on
 * non-Folia is functionally equivalent to {@code Bukkit.getScheduler().runTask}
 * but more semantically correct (entity-tied retire callback, future Folia
 * compat). On Folia, the entity scheduler runs the task on the entity's
 * owning region thread.
 *
 * <p>If the entity has been retired (removed) between scheduling and
 * execution, the task is silently dropped via the entity scheduler's retire
 * callback — no NPE risk for the caller.
 */
public final class HitDispatcher {

    private final JavaPlugin plugin;

    public HitDispatcher(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Run {@code task} on the thread that owns {@code target}'s region. Falls
     * back to the Bukkit scheduler if the entity scheduler is unavailable for
     * any reason.
     */
    public void dispatch(Entity target, Runnable task) {
        try {
            target.getScheduler().run(plugin, scheduled -> task.run(), null);
        } catch (Throwable t) {
            // Defensive — entity scheduler should always be present on Paper 1.20+.
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}

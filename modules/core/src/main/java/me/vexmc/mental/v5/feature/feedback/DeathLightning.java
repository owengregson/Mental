package me.vexmc.mental.v5.feature.feedback;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;

/**
 * The scope-owned registry of pending cosmetic-lightning destroy tasks. Each
 * death that spawns a packet {@code LIGHTNING_BOLT} schedules its matching
 * {@code destroy-entities} to fire once after a short delay — belt-and-braces,
 * since clients self-expire the bolt render on their own. Every pending destroy
 * is tracked here and cancelled on {@link #close()} (scope teardown / feature
 * disable), so a disabled feature leaves nothing scheduled — zero-touch.
 *
 * <p>Registered through {@code scope.task(() -> registry)} so the scope closes it
 * as a unit. Writers are region threads (the death listener); the pending set is
 * concurrent. There is no per-player state: a destroy task forgets itself the
 * moment it fires.</p>
 */
final class DeathLightning implements AutoCloseable {

    private final Scheduling scheduling;
    private final Set<TaskHandle> pending = ConcurrentHashMap.newKeySet();

    DeathLightning(Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    /**
     * Runs {@code destroy} once after {@code delayTicks} on the global tick, then
     * forgets it. Uses a self-cancelling {@code repeatGlobal} — the delay is well
     * above zero, so the handle is always recorded before the task can fire.
     */
    void destroyAfter(long delayTicks, Runnable destroy) {
        TaskHandle[] holder = new TaskHandle[1];
        holder[0] = scheduling.repeatGlobal(delayTicks, delayTicks, () -> {
            try {
                destroy.run();
            } finally {
                TaskHandle self = holder[0];
                if (self != null) {
                    self.cancel();
                    pending.remove(self);
                }
            }
        });
        pending.add(holder[0]);
    }

    @Override
    public void close() {
        pending.forEach(TaskHandle::cancel);
        pending.clear();
    }
}

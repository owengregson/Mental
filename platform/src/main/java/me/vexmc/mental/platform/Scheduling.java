package me.vexmc.mental.platform;

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
 *
 * <h2>The retired-callback contract</h2>
 *
 * <p>The entity-scoped methods ({@link #runOn}, {@link #repeatOn},
 * {@link #runOnLater}) take a {@code retired} callback that fires when the target
 * entity is gone before the work could run. The two backends historically
 * diverged on when and where it fires: Bukkit re-checks validity on the main
 * thread on the next tick and runs {@code retired} there; Folia's
 * {@code EntityScheduler} fires its retired hook <em>inline on the calling
 * thread</em> when the entity is already retired at submission time. To stay
 * honest across both, the contract is the common denominator — and it is exactly
 * what the Scheduling TCK asserts:</p>
 *
 * <ul>
 *   <li><b>Either thread.</b> {@code retired} may run on the owning/main thread
 *       <em>or</em> inline on the caller thread. Callers MUST NOT assume any
 *       thread affinity for it (in particular, do not touch live-entity or
 *       session state from {@code retired} without re-establishing the owning
 *       thread).</li>
 *   <li><b>Exactly once.</b> For a single submission, exactly one of
 *       {@code task} / {@code retired} runs, and it runs exactly once. A
 *       repeating task that retires cancels itself and fires {@code retired} a
 *       single time; it never fires again on later ticks.</li>
 *   <li><b>May be immediate.</b> {@code retired} may run before the submitting
 *       call returns (Folia inline case) or on a later tick (Bukkit case) — both
 *       satisfy the contract.</li>
 * </ul>
 */
public interface Scheduling {

    void runGlobal(@NotNull Runnable task);

    void runAt(@NotNull Location location, @NotNull Runnable task);

    /**
     * Runs on the thread that owns {@code entity}. If the entity is removed
     * before execution, {@code retired} runs instead (possibly immediately) —
     * see the retired-callback contract on the type javadoc (either thread,
     * exactly once, no affinity assumption).
     */
    void runOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired);

    /**
     * Runs {@code task} on the thread that owns {@code entity} — INLINE and
     * synchronously when the current thread already owns it (a damage handler on
     * the victim's own region, or the single Paper main thread), otherwise
     * deferred exactly like {@link #runOn}. The inline path removes the one-tick
     * hop for work that is already on the right thread, which is era-consistent:
     * vanilla knockback applies during the attack pass, not a tick later.
     *
     * <p>MUST be called from a region/owning thread (like
     * {@link #isOwnedByCurrentRegion}) — never the netty loop — since the inline
     * branch runs {@code task} on the caller's thread. A live entity owned by the
     * caller never retires, so the inline branch never invokes {@code retired};
     * the deferred branch honours the {@link #runOn} retired contract (either
     * thread, exactly once).</p>
     */
    default void ensureOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
        if (entity.isValid() && isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            runOn(entity, task, retired);
        }
    }

    /**
     * Whether {@code entity} is owned by the region executing on the CURRENT
     * thread. On Paper there is a single region — the main thread owns
     * everything — so this is always {@code true}; on Folia it is the real
     * region-ownership check. It gates live-entity reads that would otherwise
     * throw {@code ensureTickThread} when the entity belongs to another region
     * (a region-boundary straddle, or an entity that moved regions in the
     * dispatch tick). Call it from a region/owning thread (a damage handler),
     * never the netty loop.
     */
    boolean isOwnedByCurrentRegion(@NotNull Entity entity);

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

package me.vexmc.mental.module.knockback;

import org.jetbrains.annotations.NotNull;

/**
 * A thread-local "this {@code ENTITY_ATTACK} is not a real melee hit" flag.
 *
 * <p>The fishing module delivers its rod knock through
 * {@code victim.damage(amount, rodder)}, which raises an {@code ENTITY_ATTACK}
 * {@link org.bukkit.event.entity.EntityDamageByEntityEvent} whose damager is the
 * rodder — indistinguishable, by cause and damager type, from a melee hit the
 * rodder threw. Left alone, {@code KnockbackModule} would treat it as one:
 * applying the attacker self-slow and the post-hit sprint clear to the rodder,
 * and queueing a stray {@code MELEE} pending the rod's own submit then clobbers
 * (or, if the rod hit is cancelled, strands).</p>
 *
 * <p>The dispatching module wraps its {@code damage()} call in {@link #during};
 * the melee handler runs synchronously inside that call on the same thread, so
 * {@link #active()} sees the flag and skips the hit. The flag restores on the way
 * out (including on exception) so a genuine later melee hit is never skipped.</p>
 */
public final class MeleeReentryGuard {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private MeleeReentryGuard() {}

    /** Runs {@code damage} with the re-entry flag set, restoring it afterward. */
    public static void during(@NotNull Runnable damage) {
        boolean prior = ACTIVE.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            damage.run();
        } finally {
            ACTIVE.set(prior);
        }
    }

    /** Whether the current thread is inside a re-entrant module-dealt damage. */
    public static boolean active() {
        return ACTIVE.get();
    }
}

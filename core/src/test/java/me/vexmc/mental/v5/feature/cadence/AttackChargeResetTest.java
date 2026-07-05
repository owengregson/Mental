package me.vexmc.mental.v5.feature.cadence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import org.bukkit.Location;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;

/**
 * The quit-path pre-save shape (interaction audit, quit-path conflict): {@code
 * restore} is invoked from the quit listener, whose thread OWNS the quitting
 * player — the write must therefore land INLINE, before the listener returns and
 * the disconnect serializes the player. The old {@code runOn} deferral was dead on
 * arrival there (Bukkit re-checks validity on the NEXT tick, after the player is
 * gone), so the spoofed {@code 1024.0} base persisted into the save on every
 * quit-mid-feature — permanent once the feature was later disabled.
 *
 * <p>The fake {@link Scheduling} makes the deferral observable: {@code runOn}
 * queues and never runs (exactly what a disconnect does to the deferred task),
 * while the interface's own {@code ensureOn} default runs inline for an owned,
 * valid entity. If {@code restore} regressed to {@code runOn}, the assertion that
 * the base is back BEFORE the queue drains fails.</p>
 */
class AttackChargeResetTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    /** Deferred tasks queue here and are NEVER run — the disconnect-kill stand-in. */
    private static final class QueueingScheduling implements Scheduling {
        final List<Runnable> deferred = new ArrayList<>();

        private static final TaskHandle NOOP_HANDLE = new TaskHandle() {
            @Override
            public void cancel() {}

            @Override
            public boolean cancelled() {
                return true;
            }
        };

        @Override
        public void runGlobal(@NotNull Runnable task) {
            deferred.add(task);
        }

        @Override
        public void runAt(@NotNull Location location, @NotNull Runnable task) {
            deferred.add(task);
        }

        @Override
        public void runOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
            deferred.add(task);
        }

        @Override
        public boolean isOwnedByCurrentRegion(@NotNull Entity entity) {
            return true; // the quit listener runs on the owning thread
        }

        @Override
        public void runAsync(@NotNull Runnable task) {
            deferred.add(task);
        }

        @Override
        public @NotNull TaskHandle repeatGlobal(long initialTicks, long periodTicks, @NotNull Runnable task) {
            return NOOP_HANDLE;
        }

        @Override
        public @NotNull TaskHandle repeatOn(
                @NotNull Entity entity, long initialTicks, long periodTicks,
                @NotNull Runnable task, @NotNull Runnable retired) {
            return NOOP_HANDLE;
        }

        @Override
        public @NotNull TaskHandle repeatAsync(
                @NotNull Duration initial, @NotNull Duration period, @NotNull Runnable task) {
            return NOOP_HANDLE;
        }

        @Override
        public void runOnLater(
                @NotNull Entity entity, long delayTicks, @NotNull Runnable task, @NotNull Runnable retired) {
            deferred.add(task);
        }

        @Override
        public @NotNull String describe() {
            return "queueing-test";
        }

        void drain() {
            List<Runnable> tasks = new ArrayList<>(deferred);
            deferred.clear();
            for (Runnable task : tasks) {
                task.run();
            }
        }
    }

    @Test
    void quitPathRestoreWritesTheBaseBackInlineBeforeAnyDeferredTaskCouldRun() {
        QueueingScheduling scheduling = new QueueingScheduling();
        AttackChargeReset reset = new AttackChargeReset(scheduling);
        double[] base = {4.0};
        Player player = player(PLAYER, base);

        // Apply is allowed to defer (the player is alive and staying) — drain it.
        reset.apply(player);
        scheduling.drain();
        assertEquals(CooldownSpoof.FULL_CHARGE_ATTACK_SPEED, base[0],
                "apply must raise the base to the full-charge spoof value");

        // The quit-time restore: must write back INLINE (pre-save). The deferred
        // queue is deliberately NOT drained — a disconnect never runs it.
        reset.restore(player);
        assertEquals(4.0, base[0],
                "restore must write the captured original back before returning — a deferred "
                        + "write dies with the disconnect and the 1024.0 spoof persists into the save");
        assertTrue(scheduling.deferred.isEmpty()
                        || scheduling.deferred.stream().noneMatch(task -> base[0] != 4.0),
                "no restore work may be left on the deferred queue");
    }

    @Test
    void restoreOfAnUntrackedPlayerIsANoOp() {
        QueueingScheduling scheduling = new QueueingScheduling();
        AttackChargeReset reset = new AttackChargeReset(scheduling);
        double[] base = {4.0};
        Player player = player(PLAYER, base);

        reset.restore(player); // never applied — nothing captured, nothing written
        assertEquals(4.0, base[0], "an untracked restore must not touch the attribute");
        assertTrue(scheduling.deferred.isEmpty(), "an untracked restore must schedule nothing");
    }

    /* ------------------------------------------------------------------ */
    /*  Interface stubs (dynamic proxies) — no Bukkit server required.     */
    /* ------------------------------------------------------------------ */

    /** A player whose attack-speed attribute instance is backed by {@code base[0]}. */
    private static Player player(UUID id, double[] base) {
        Object instance = Proxy.newProxyInstance(
                AttackChargeResetTest.class.getClassLoader(), new Class[]{AttributeInstance.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBaseValue" -> base[0];
                    case "setBaseValue" -> {
                        base[0] = (double) args[0];
                        yield null;
                    }
                    default -> defaultValue(method, proxy, args);
                });
        return (Player) Proxy.newProxyInstance(
                AttackChargeResetTest.class.getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getAttribute" -> instance;
                    case "isValid" -> true; // still valid inside the quit event — the inline window
                    default -> defaultValue(method, proxy, args);
                });
    }

    /** Sensible defaults for un-stubbed proxy calls (Object identity + typed zeroes). */
    private static Object defaultValue(java.lang.reflect.Method method, Object proxy, Object[] args) {
        switch (method.getName()) {
            case "toString": return method.getDeclaringClass().getSimpleName() + "-stub";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals":   return proxy == args[0];
            default:         return primitiveZero(method.getReturnType());
        }
    }

    private static Object primitiveZero(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}

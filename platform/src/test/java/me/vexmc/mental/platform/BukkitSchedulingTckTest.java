package me.vexmc.mental.platform;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * The {@link SchedulingTck} run against {@link BukkitScheduling} with a stubbed
 * {@link BukkitScheduler}. The stub runs one-shot/sync tasks and timer fires on a
 * single "main" thread the {@link #tick()} hook advances deterministically, and
 * async tasks on a separate thread — enough to prove the whole Scheduling
 * contract (including the retired-callback common denominator) without a live
 * server. FoliaScheduling shares this suite; its live run is Task 5.6.
 */
class BukkitSchedulingTckTest extends SchedulingTck {

    private static StubScheduler stub;
    private static Plugin plugin;

    @BeforeAll
    static void installServer() {
        stub = new StubScheduler();
        plugin = proxy(Plugin.class, (p, m, a) -> objectMethodOr(p, m, a, () -> defaultValue(m.getReturnType())));
        if (Bukkit.getServer() == null) {
            Server server = proxy(Server.class, (p, m, a) -> switch (m.getName()) {
                case "getScheduler" -> stub.asBukkitScheduler();
                case "getLogger" -> Logger.getLogger("scheduling-tck-stub");
                case "getName", "getVersion", "getBukkitVersion" -> "SchedulingTckStub";
                case "isPrimaryThread" -> stub.onMain();
                default -> objectMethodOr(p, m, a, () -> defaultValue(m.getReturnType()));
            });
            Bukkit.setServer(server);
        }
    }

    @AfterAll
    static void shutdown() {
        if (stub != null) {
            stub.shutdown();
        }
    }

    @BeforeEach
    void freshScheduler() {
        stub.reset();
    }

    @Override
    protected @NotNull Scheduling scheduling() {
        return new BukkitScheduling(plugin);
    }

    @Override
    protected void tick() {
        stub.tick();
    }

    @Override
    protected void awaitAsyncIdle() {
        stub.awaitAsyncIdle();
    }

    @Override
    protected @NotNull Entity ownedEntity() {
        return entity(true);
    }

    @Override
    protected @NotNull Entity retiredEntity() {
        return entity(false);
    }

    @Override
    protected boolean onOwningThread() {
        return stub.onMain();
    }

    /* ------------------------------------------------------------------ */
    /*  Fake entities                                                      */
    /* ------------------------------------------------------------------ */

    private static Entity entity(boolean valid) {
        return proxy(Entity.class, (p, m, a) -> switch (m.getName()) {
            case "isValid" -> valid;
            case "isDead" -> !valid;
            default -> objectMethodOr(p, m, a, () -> defaultValue(m.getReturnType()));
        });
    }

    /* ------------------------------------------------------------------ */
    /*  The stubbed BukkitScheduler                                        */
    /* ------------------------------------------------------------------ */

    /**
     * A single-threaded "main" executor drives sync one-shots and timer fires;
     * a separate thread drives async work. State is deterministic: {@link #tick()}
     * drains the sync queue and advances every live timer one period, blocking
     * until that tick's work has run so callers can assert synchronously.
     */
    private static final class StubScheduler {

        private final AtomicReference<Thread> mainThread = new AtomicReference<>();
        private final ExecutorService main = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stub-main");
            mainThread.set(thread);
            return thread;
        });
        private final ExecutorService async = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "stub-async"));

        private final List<Runnable> pendingSync = new CopyOnWriteArrayList<>();
        private final List<StubTimer> timers = new CopyOnWriteArrayList<>();

        void reset() {
            pendingSync.clear();
            timers.clear();
        }

        boolean onMain() {
            return Thread.currentThread() == mainThread.get();
        }

        void tick() {
            await(main.submit(() -> {
                List<Runnable> due = new ArrayList<>(pendingSync);
                pendingSync.clear();
                for (Runnable runnable : due) {
                    runnable.run();
                }
                for (StubTimer timer : new ArrayList<>(timers)) {
                    timer.tickOnce();
                }
            }));
        }

        void awaitAsyncIdle() {
            await(async.submit(() -> {}));
        }

        void shutdown() {
            main.shutdownNow();
            async.shutdownNow();
        }

        BukkitScheduler asBukkitScheduler() {
            return proxy(BukkitScheduler.class, (p, m, a) -> {
                switch (m.getName()) {
                    case "runTask":
                    case "runTaskLater":
                        if (a.length >= 2 && a[1] instanceof Runnable runnable) {
                            pendingSync.add(runnable);
                        }
                        return null; // BukkitScheduling ignores the one-shot handle
                    case "runTaskAsynchronously":
                        if (a.length >= 2 && a[1] instanceof Runnable runnable) {
                            async.submit(runnable);
                        }
                        return null;
                    case "runTaskTimer":
                    case "runTaskTimerAsynchronously":
                        if (a.length >= 4 && a[1] instanceof Runnable runnable) {
                            StubTimer timer = new StubTimer(runnable, (Long) a[2], (Long) a[3]);
                            timers.add(timer);
                            return timerTask(timer);
                        }
                        return null;
                    default:
                        return objectMethodOr(p, m, a, () -> defaultValue(m.getReturnType()));
                }
            });
        }

        private BukkitTask timerTask(StubTimer timer) {
            return proxy(BukkitTask.class, (p, m, a) -> switch (m.getName()) {
                case "cancel" -> {
                    timer.cancelled = true;
                    timers.remove(timer);
                    yield null;
                }
                case "isCancelled" -> timer.cancelled;
                case "getTaskId" -> 0;
                case "isSync" -> true;
                default -> objectMethodOr(p, m, a, () -> defaultValue(m.getReturnType()));
            });
        }
    }

    /** A repeating task with a tick countdown; fired on the main thread by {@link StubScheduler#tick()}. */
    private static final class StubTimer {
        private final Runnable runnable;
        private final long period;
        private long ticksUntilFire;
        private volatile boolean cancelled;

        StubTimer(Runnable runnable, long initial, long period) {
            this.runnable = runnable;
            this.ticksUntilFire = Math.max(0, initial);
            this.period = Math.max(0, period);
        }

        void tickOnce() {
            if (cancelled) {
                return;
            }
            if (ticksUntilFire <= 0) {
                runnable.run();
                ticksUntilFire = period;
            }
            ticksUntilFire--;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Reflection helpers                                                 */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, handler);
    }

    /** Handle the three {@link Object} methods with identity semantics; else defer to {@code fallback}. */
    private static Object objectMethodOr(
            Object proxy, Method method, Object[] args, java.util.function.Supplier<Object> fallback) {
        return switch (method.getName()) {
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "stub:" + method.getDeclaringClass().getSimpleName();
            default -> fallback.get();
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == double.class) {
            return 0.0d;
        }
        return 0.0f;
    }

    private static void await(java.util.concurrent.Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting stub scheduler", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}

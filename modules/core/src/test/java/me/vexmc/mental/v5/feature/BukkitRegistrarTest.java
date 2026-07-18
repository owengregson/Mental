package me.vexmc.mental.v5.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The unit-testable parts of {@link BukkitRegistrar}: {@code task} invokes the
 * starter and its closeable cancels the wrapped handle exactly once.
 * {@code bukkit}/{@code packets} route through Bukkit/PacketEvents statics that
 * only exist on a live server, so they are covered by the live boot suite.
 */
class BukkitRegistrarTest {

    private static BukkitRegistrar registrar() {
        // task touches neither the plugin nor Bukkit, so a null plugin is
        // sufficient for this path.
        return new BukkitRegistrar(null);
    }

    @Test
    void taskInvokesTheStarterAndTheCloseableCancelsTheHandle() throws Exception {
        AtomicInteger starterCalls = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        AutoCloseable cancel = cancels::incrementAndGet;

        AutoCloseable registration = registrar().task(() -> {
            starterCalls.incrementAndGet();
            return cancel;
        });

        assertEquals(1, starterCalls.get(), "the starter runs exactly once at registration");
        assertSame(cancel, registration, "task returns the starter's own cancel closeable");
        registration.close();
        assertEquals(1, cancels.get(), "closing the registration cancels the task handle once");
    }
}

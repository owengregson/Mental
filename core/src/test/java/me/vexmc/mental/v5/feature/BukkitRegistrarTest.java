package me.vexmc.mental.v5.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.v5.coexist.OcmBinding;
import org.junit.jupiter.api.Test;

/**
 * The unit-testable parts of {@link BukkitRegistrar}: {@code task} invokes the
 * starter and its closeable cancels the wrapped handle exactly once, and
 * {@code rule} runs the handler registration but rejects a null token (B14).
 * {@code bukkit}/{@code packets} route through Bukkit/PacketEvents statics that
 * only exist on a live server, so they are covered by the live boot suite.
 */
class BukkitRegistrarTest {

    private static BukkitRegistrar registrar() {
        // task/rule touch neither the plugin nor the arbiter, so a null plugin
        // and an absent-mode binding are sufficient for these paths.
        return new BukkitRegistrar(null, new OcmBinding());
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

    @Test
    void ruleRunsTheHandlerRegistrationAndClosesCleanly() throws Exception {
        AtomicInteger registered = new AtomicInteger();
        AutoCloseable registration = registrar().rule(
                MechanicToken.CRAFTING, registered::incrementAndGet);

        assertEquals(1, registered.get(), "the handler registration runs once");
        registration.close(); // no-op teardown in this sub-phase; must not throw
    }

    @Test
    void ruleWithoutATokenIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> registrar().rule(null, () -> {}));
    }
}

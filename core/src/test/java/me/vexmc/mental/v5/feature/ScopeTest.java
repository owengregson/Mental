package me.vexmc.mental.v5.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import org.junit.jupiter.api.Test;

/**
 * The scope's transactional lifecycle over a recording stub {@link Registrar}:
 * reverse-order close, per-close exception isolation with a single summary
 * throw, the partial-enable "acquisition throws → nothing leaks" contract, and
 * idempotent double-close. That {@code rule} cannot be registered without a
 * token is a compile-time property (there is no token-less overload).
 */
class ScopeTest {

    /** Records registration and close order, and can be told which closes throw. */
    private static final class RecordingRegistrar implements Registrar {
        final List<String> events = new ArrayList<>();
        final List<String> throwOnClose = new ArrayList<>();
        final List<String> throwOnAcquire = new ArrayList<>();

        private AutoCloseable track(String id) {
            if (throwOnAcquire.contains(id)) {
                events.add("acquire-fail:" + id);
                throw new IllegalStateException("acquisition failed: " + id);
            }
            events.add("acquire:" + id);
            return () -> {
                events.add("close:" + id);
                if (throwOnClose.contains(id)) {
                    throw new IllegalStateException("close failed: " + id);
                }
            };
        }

        @Override
        public AutoCloseable bukkit(Object listener) {
            return track(String.valueOf(listener));
        }

        @Override
        public AutoCloseable packets(Object peListener) {
            return track(String.valueOf(peListener));
        }

        @Override
        public AutoCloseable task(Supplier<AutoCloseable> starter) {
            AutoCloseable running = starter.get();
            return track("task");
        }

        @Override
        public AutoCloseable rule(MechanicToken token, Runnable handlerRegistration) {
            handlerRegistration.run();
            return track("rule:" + token);
        }
    }

    @Test
    void closesInReverseRegistrationOrder() {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Scope scope = new Scope(registrar);
        scope.listen("A");
        scope.packets("B");
        scope.rule(MechanicToken.REGEN, () -> registrar.events.add("register-rule"));

        scope.close();

        assertEquals(List.of(
                "acquire:A", "acquire:B", "register-rule", "acquire:rule:REGEN",
                "close:rule:REGEN", "close:B", "close:A"), registrar.events);
    }

    @Test
    void aThrowingCloseIsIsolatedAndTheRestStillClose() {
        RecordingRegistrar registrar = new RecordingRegistrar();
        registrar.throwOnClose.add("B");
        Scope scope = new Scope(registrar);
        scope.listen("A");
        scope.listen("B");
        scope.listen("C");

        RuntimeException summary = assertThrows(RuntimeException.class, scope::close);

        // Every close ran despite B throwing (reverse order C, B, A).
        assertEquals(List.of("acquire:A", "acquire:B", "acquire:C",
                "close:C", "close:B", "close:A"), registrar.events);
        // The failure is collected and reported, not swallowed.
        assertEquals(1, summary.getSuppressed().length);
        assertTrue(summary.getSuppressed()[0].getMessage().contains("close failed: B"));
    }

    @Test
    void acquisitionThrowLeavesEarlierRegistrationsCloseableAndPropagates() {
        RecordingRegistrar registrar = new RecordingRegistrar();
        registrar.throwOnAcquire.add("C");
        Scope scope = new Scope(registrar);
        scope.listen("A");
        scope.listen("B");

        // The reconciler's role: the acquisition throw propagates out of the scope call.
        assertThrows(IllegalStateException.class, () -> scope.listen("C"));

        // Nothing C acquired (it failed); A and B are held and close cleanly.
        scope.close();
        assertEquals(List.of("acquire:A", "acquire:B", "acquire-fail:C",
                "close:B", "close:A"), registrar.events);
    }

    @Test
    void doubleCloseIsANoOp() {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Scope scope = new Scope(registrar);
        scope.listen("A");

        scope.close();
        scope.close();

        assertEquals(List.of("acquire:A", "close:A"), registrar.events);
    }

    @Test
    void taskStarterIsInvokedAtRegistration() {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Scope scope = new Scope(registrar);
        boolean[] started = {false};
        scope.task(() -> {
            started[0] = true;
            return () -> {};
        });
        assertTrue(started[0], "the task starter runs when registered");
        scope.close();
        assertTrue(registrar.events.contains("close:task"));
        assertFalse(registrar.events.isEmpty());
    }
}

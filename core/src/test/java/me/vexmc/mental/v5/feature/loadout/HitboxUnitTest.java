package me.vexmc.mental.v5.feature.loadout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import me.vexmc.mental.platform.ServerEnvironment;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.v5.feature.Registrar;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.combo.ComboReachHandicap;
import me.vexmc.mental.v5.platform.AttackRangeAdapter;
import org.junit.jupiter.api.Test;

/**
 * The zero-touch teardown guard (B8) for the era-reach hitbox unit: both surfaces
 * it acquires — the lifecycle listener AND the apply/restore task (which pins the
 * interaction-range attribute and applies the ATTACK_RANGE component, and whose
 * closer restores every captured base + strips every applied component) — must
 * register through the single unit scope and die with it. If the restore task
 * survived a toggle-off, a tightened attribute or a stray component would be left
 * behind (the exact leak a plugin-lifetime design risks); this proves every
 * registration is closed on {@code scope.close()}.
 */
class HitboxUnitTest {

    /** Records every registration by kind and confirms each is closed on scope close. */
    private static final class RecordingRegistrar implements Registrar {
        final List<String> registered = new ArrayList<>();
        final List<String> closed = new ArrayList<>();

        private AutoCloseable track(String tag) {
            registered.add(tag);
            return () -> closed.add(tag);
        }

        @Override
        public AutoCloseable bukkit(Object listener) {
            return track("bukkit:" + listener.getClass().getSimpleName());
        }

        @Override
        public AutoCloseable packets(Object peListener) {
            return track("packets:" + peListener.getClass().getSimpleName());
        }

        @Override
        public AutoCloseable task(Supplier<AutoCloseable> starter) {
            // Deliberately does NOT invoke the starter: the production starter reads
            // Bukkit.getOnlinePlayers (unavailable in a plain unit test). The scope
            // still records + closes this registration, which is what we assert.
            return track("task");
        }

        @Override
        public AutoCloseable rule(MechanicToken token, Runnable handlerRegistration) {
            handlerRegistration.run();
            return track("rule:" + token);
        }
    }

    @Test
    void everySurfaceIncludingTheRestoreTaskDiesWithTheScope() {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Scope scope = new Scope(registrar);
        // No component model resolves off-server (probe finds DataComponents absent);
        // plugin/scheduling are only reached from the task starter the recording
        // registrar never invokes, so null is never dereferenced.
        AttackRangeAdapter component =
                AttackRangeAdapter.probe(ServerEnvironment.parse("1.20.6-R0.1-SNAPSHOT"), message -> {});
        // The handicap coordination is only consulted from the combo-event handlers
        // (never during assemble/close), so a bare instance with inert deps suffices.
        HitboxUnit unit = new HitboxUnit(null, null, component,
                new ComboReachHandicap(null, null, () -> null));

        try {
            unit.assemble(scope, null);
        } catch (Exception failure) {
            throw new AssertionError("assemble must not throw", failure);
        }

        assertTrue(registrar.registered.contains("bukkit:HitboxUnit"),
                "the lifecycle listener must register: " + registrar.registered);
        assertTrue(registrar.registered.contains("task"),
                "the apply/restore task must register: " + registrar.registered);

        scope.close();

        assertEquals(registrar.registered.size(), registrar.closed.size(),
                "every registration must close with the scope; registered=" + registrar.registered
                        + " closed=" + registrar.closed);
        assertTrue(registrar.closed.contains("task"),
                "the restore task must die with the scope (no leaked attribute/component): "
                        + registrar.closed);
    }
}

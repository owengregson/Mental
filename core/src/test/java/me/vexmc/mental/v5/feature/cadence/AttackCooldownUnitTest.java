package me.vexmc.mental.v5.feature.cadence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import me.vexmc.mental.platform.ServerEnvironment;
import me.vexmc.mental.v5.feature.Registrar;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.platform.WeaponTooltipAdapter;
import org.junit.jupiter.api.Test;

/**
 * The mandate's split-brain guard (B5): attack-cooldown removal is ONE contract,
 * so every facet — the server-rule lifecycle listener, the sweep re-disable
 * listener, AND the three netty packet halves (spoof, tooltip, sweep particle) —
 * must register through the single unit scope and die with it. If any packet half
 * were registered outside the scope it would survive a toggle-off (the exact bug
 * the old plugin-lifetime + config-flag design risked); this test proves every
 * registration the unit makes is closed on {@code scope.close()}.
 */
class AttackCooldownUnitTest {

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
            // Deliberately does NOT invoke the starter: the production starter calls
            // Bukkit.getOnlinePlayers (unavailable in a plain unit test). The scope
            // still records+closes this registration, which is what we assert.
            return track("task");
        }
    }

    @Test
    void everyFacetIncludingTheTwoPacketHalvesDiesWithTheScope() {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Scope scope = new Scope(registrar);
        WeaponTooltipAdapter tooltip =
                WeaponTooltipAdapter.probe(ServerEnvironment.parse("1.21.1-R0.1-SNAPSHOT"), message -> {});
        // Scheduling is only reached from the task starter, which the recording
        // registrar deliberately never invokes — and the plugin logger only from the
        // sub-1.11 sweep degrade line, which cannot fire here (the unit-test classpath
        // is the modern API, where SweepCauses resolves present) — so neither null is
        // ever dereferenced.
        AttackCooldownUnit unit = new AttackCooldownUnit(null, null, tooltip);

        try {
            unit.assemble(scope, null);
        } catch (Exception failure) {
            throw new AssertionError("assemble must not throw", failure);
        }

        // All four facets registered: server-rule lifecycle listener + task,
        // both packet halves (spoof + tooltip), and the sweep re-disable pair.
        assertTrue(registrar.registered.contains("bukkit:AttackCooldownUnit"),
                "the server-rule lifecycle listener must register: " + registrar.registered);
        assertTrue(registrar.registered.contains("task"),
                "the server-rule apply/restore task must register: " + registrar.registered);
        assertTrue(registrar.registered.contains("packets:CooldownSpoofListener"),
                "the client-presentation spoof packet half must register: " + registrar.registered);
        assertTrue(registrar.registered.contains("packets:CooldownTooltipListener"),
                "the tooltip-hider packet half must register: " + registrar.registered);
        assertTrue(registrar.registered.contains("bukkit:SweepDamageListener"),
                "the sweep re-disable event half must register: " + registrar.registered);
        assertTrue(registrar.registered.contains("packets:SweepParticleListener"),
                "the sweep re-disable particle packet half must register: " + registrar.registered);

        scope.close();

        // Every registration — crucially the packet halves — is closed with the scope.
        assertEquals(registrar.registered.size(), registrar.closed.size(),
                "every registration must close with the scope; registered=" + registrar.registered
                        + " closed=" + registrar.closed);
        assertTrue(registrar.closed.contains("packets:CooldownSpoofListener")
                        && registrar.closed.contains("packets:CooldownTooltipListener")
                        && registrar.closed.contains("packets:SweepParticleListener"),
                "the packet halves must die with the scope (no split-brain): " + registrar.closed);
    }
}

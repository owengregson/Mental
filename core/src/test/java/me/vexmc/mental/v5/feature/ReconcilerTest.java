package me.vexmc.mental.v5.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import me.vexmc.mental.v5.config.ConfigStore;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.SnapshotParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The reconciler converges open scopes to (enabled && registered) with
 * zero-touch by construction and per-unit exception isolation. Truth is the set
 * of open scopes; there is no boolean flag.
 */
class ReconcilerTest {

    /* ------------------------------ test doubles ------------------------------ */

    private static class RecordingRegistrar implements Registrar {
        final List<String> events = new ArrayList<>();
        final Map<String, Integer> closeCounts = new HashMap<>();

        private AutoCloseable track(String id) {
            events.add("acquire:" + id);
            return () -> {
                events.add("close:" + id);
                closeCounts.merge(id, 1, Integer::sum);
            };
        }

        @Override public AutoCloseable bukkit(Object listener) { return track(String.valueOf(listener)); }
        @Override public AutoCloseable packets(Object peListener) { return track(String.valueOf(peListener)); }
        @Override public AutoCloseable task(Supplier<AutoCloseable> starter) { return track("task"); }
    }

    private static final class RecordingUnit implements FeatureUnit {
        final Feature feature;
        int registrations;
        boolean throwOnAssemble;
        boolean rebuildOnSettingsChange;
        int assembleCount;

        RecordingUnit(Feature feature, int registrations) {
            this.feature = feature;
            this.registrations = registrations;
        }

        @Override public Feature descriptor() { return feature; }

        @Override public boolean rebuildOnSettingsChange() { return rebuildOnSettingsChange; }

        @Override public void assemble(Scope scope, Snapshot snapshot) {
            assembleCount++;
            for (int i = 0; i < registrations; i++) {
                scope.listen(feature.name() + "-" + i);
            }
            if (throwOnAssemble) {
                throw new IllegalStateException("assemble failed: " + feature);
            }
        }
    }

    private static Snapshot snapshot(String modulesYaml) throws Exception {
        YamlConfiguration main = new YamlConfiguration();
        main.loadFromString(modulesYaml);
        YamlConfiguration empty = new YamlConfiguration();
        return SnapshotParser.parse(ConfigStore.Sources.of(main, empty, empty, empty, Map.of())).snapshot();
    }

    /**
     * A snapshot with a Combat Effects selection (2.5.3: the FEEDBACK settings
     * come from the selected preset file, so a settings CHANGE is staged by
     * switching presets). "signature" resolves an in-memory preset whose
     * hit-feedback sounds differ from the vanilla defaults; "vanilla" is the
     * absent-file fallback — the in-code defaults.
     */
    private static Snapshot snapshot(String modulesYaml, String selectedPreset) throws Exception {
        YamlConfiguration main = new YamlConfiguration();
        main.loadFromString(modulesYaml);
        YamlConfiguration effects = new YamlConfiguration();
        effects.loadFromString("effects:\n  preset: " + selectedPreset + "\n");
        YamlConfiguration signature = new YamlConfiguration();
        signature.loadFromString("""
                hit-feedback:
                  sounds:
                    - sound: block.anvil.land
                """);
        YamlConfiguration empty = new YamlConfiguration();
        return SnapshotParser.parse(new ConfigStore.Sources(
                main, empty, empty, empty,
                new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                effects, Map.of("signature", signature), Map.of())).snapshot();
    }

    /* --------------------------------- tests --------------------------------- */

    @Test
    void disabledUnitsNeverAssembleAcrossEveryDescriptor() throws Exception {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Reconciler reconciler = new Reconciler(registrar, message -> {});
        Map<Feature, RecordingUnit> units = new HashMap<>();
        for (Feature feature : Feature.values()) {
            RecordingUnit unit = new RecordingUnit(feature, 1);
            units.put(feature, unit);
            reconciler.register(unit);
        }

        reconciler.converge(snapshot("")); // default enablement

        for (Feature feature : Feature.values()) {
            boolean expectedActive = feature.defaultEnabled();
            assertEquals(expectedActive, units.get(feature).assembleCount > 0,
                    () -> feature + " assemble must match its default enablement (zero-touch)");
            assertEquals(expectedActive, reconciler.active(feature),
                    () -> feature + " active must reflect an open scope");
        }
    }

    @Test
    void duplicateDescriptorRegistrationThrows() {
        Reconciler reconciler = new Reconciler(new RecordingRegistrar(), message -> {});
        reconciler.register(new RecordingUnit(Feature.KNOCKBACK, 1));
        assertThrows(IllegalStateException.class,
                () -> reconciler.register(new RecordingUnit(Feature.KNOCKBACK, 1)));
    }

    @Test
    void convergeIsIdempotentAndReloadClosesTheDisabledScope() throws Exception {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Reconciler reconciler = new Reconciler(registrar, message -> {});
        RecordingUnit hitReg = new RecordingUnit(Feature.HIT_REGISTRATION, 2);
        reconciler.register(hitReg);

        reconciler.converge(snapshot(""));               // enabled by default → assembled once
        reconciler.converge(snapshot(""));               // idempotent → no re-assemble
        assertEquals(1, hitReg.assembleCount, "converge is idempotent");
        assertTrue(reconciler.active(Feature.HIT_REGISTRATION));

        reconciler.converge(snapshot("modules:\n  hit-registration: false\n")); // disable → close
        assertFalse(reconciler.active(Feature.HIT_REGISTRATION));
        assertEquals(1, registrar.closeCounts.get("HIT_REGISTRATION-0"), "closed exactly once");
        assertEquals(1, registrar.closeCounts.get("HIT_REGISTRATION-1"), "closed exactly once");
    }

    @Test
    void aThrowingAssembleIsIsolatedAndItsPartialScopeIsClosed() throws Exception {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Reconciler reconciler = new Reconciler(registrar, message -> {});
        RecordingUnit knockback = new RecordingUnit(Feature.KNOCKBACK, 2);
        knockback.throwOnAssemble = true;
        RecordingUnit fishing = new RecordingUnit(Feature.FISHING_KNOCKBACK, 1);
        RecordingUnit rod = new RecordingUnit(Feature.ROD_VELOCITY, 1);
        reconciler.register(knockback);
        reconciler.register(fishing);
        reconciler.register(rod);

        reconciler.converge(snapshot("")); // all three enabled by default

        // The throwing unit stays OFF and its partial registrations were closed.
        assertFalse(reconciler.active(Feature.KNOCKBACK));
        assertEquals(1, registrar.closeCounts.getOrDefault("KNOCKBACK-0", 0));
        assertEquals(1, registrar.closeCounts.getOrDefault("KNOCKBACK-1", 0));
        // The others converged normally.
        assertTrue(reconciler.active(Feature.FISHING_KNOCKBACK));
        assertTrue(reconciler.active(Feature.ROD_VELOCITY));
        assertEquals(1, fishing.assembleCount);
        assertEquals(1, rod.assembleCount);
    }

    @Test
    void closeAllClosesEveryOpenScopeEvenWhenOneCloseThrows() throws Exception {
        RecordingRegistrar registrar = new RecordingRegistrar() {
            @Override public AutoCloseable bukkit(Object listener) {
                String id = String.valueOf(listener);
                events.add("acquire:" + id);
                return () -> {
                    events.add("close:" + id);
                    closeCounts.merge(id, 1, Integer::sum);
                    if (id.startsWith("FISHING_KNOCKBACK")) {
                        throw new IllegalStateException("close failed: " + id);
                    }
                };
            }
        };
        Reconciler reconciler = new Reconciler(registrar, message -> {});
        reconciler.register(new RecordingUnit(Feature.KNOCKBACK, 1));
        reconciler.register(new RecordingUnit(Feature.FISHING_KNOCKBACK, 1));
        reconciler.register(new RecordingUnit(Feature.ROD_VELOCITY, 1));

        reconciler.converge(snapshot(""));
        reconciler.closeAll();

        // Every scope closed despite the fishing close throwing.
        assertEquals(1, registrar.closeCounts.get("KNOCKBACK-0"));
        assertEquals(1, registrar.closeCounts.get("FISHING_KNOCKBACK-0"));
        assertEquals(1, registrar.closeCounts.get("ROD_VELOCITY-0"));
        assertFalse(reconciler.active(Feature.KNOCKBACK));
        assertFalse(reconciler.active(Feature.FISHING_KNOCKBACK));
        assertFalse(reconciler.active(Feature.ROD_VELOCITY));
    }

    /* --------------- settings-change re-assembly (the 2.5.2 report) --------------- */

    /**
     * The FEEDBACK units bake resolved settings into their assembled listeners,
     * so a reload that changes those settings must bounce the unit — otherwise a
     * preset edit on an already-enabled module silently never lands (the 2.5.2
     * "signature preset plays nothing" report: hit-feedback stayed audibly
     * vanilla and death-effects stayed a strict nothing until a restart).
     */
    @Test
    void aSettingsChangeReassemblesAnOptedInUnitExactlyOnce() throws Exception {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Reconciler reconciler = new Reconciler(registrar, message -> {});
        RecordingUnit unit = new RecordingUnit(Feature.HIT_FEEDBACK, 1);
        unit.rebuildOnSettingsChange = true;
        reconciler.register(unit);

        String enabled = "modules:\n  hit-feedback: true\n";

        reconciler.converge(snapshot(enabled, "vanilla"));
        assertEquals(1, unit.assembleCount, "enabled once");

        reconciler.converge(snapshot(enabled, "signature"));
        assertEquals(2, unit.assembleCount, "changed settings bounce the unit");
        assertEquals(1, registrar.closeCounts.get("HIT_FEEDBACK-0"), "the old scope closed exactly once");
        assertTrue(reconciler.active(Feature.HIT_FEEDBACK), "the unit is re-assembled, not left off");

        reconciler.converge(snapshot(enabled, "signature"));
        assertEquals(2, unit.assembleCount,
                "an identical re-parse never bounces — settings records compare by value");
    }

    /** A unit that reads the live snapshot (the default) is never bounced by a settings change. */
    @Test
    void aSettingsChangeLeavesADefaultUnitUntouched() throws Exception {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Reconciler reconciler = new Reconciler(registrar, message -> {});
        RecordingUnit unit = new RecordingUnit(Feature.HIT_FEEDBACK, 1);
        reconciler.register(unit);

        reconciler.converge(snapshot("modules:\n  hit-feedback: true\n", "vanilla"));
        reconciler.converge(snapshot("modules:\n  hit-feedback: true\n", "signature"));

        assertEquals(1, unit.assembleCount, "no opt-in → no bounce");
        assertTrue(reconciler.active(Feature.HIT_FEEDBACK));
    }

    /** The bounce path still honours the toggle: disabled in the same snapshot just closes. */
    @Test
    void aDisableInTheSameSnapshotAsASettingsChangeJustCloses() throws Exception {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Reconciler reconciler = new Reconciler(registrar, message -> {});
        RecordingUnit unit = new RecordingUnit(Feature.HIT_FEEDBACK, 1);
        unit.rebuildOnSettingsChange = true;
        reconciler.register(unit);

        reconciler.converge(snapshot("modules:\n  hit-feedback: true\n", "vanilla"));
        reconciler.converge(snapshot("modules:\n  hit-feedback: false\n", "signature"));

        assertEquals(1, unit.assembleCount, "disable wins — no re-assemble");
        assertFalse(reconciler.active(Feature.HIT_FEEDBACK));
        assertEquals(1, registrar.closeCounts.get("HIT_FEEDBACK-0"));
    }

    @Test
    void anEnabledFeatureWithNoRegisteredUnitIsInactive() throws Exception {
        Reconciler reconciler = new Reconciler(new RecordingRegistrar(), message -> {});
        // Register nothing; converge with everything at defaults.
        reconciler.converge(snapshot(""));
        for (Feature feature : Feature.values()) {
            assertFalse(reconciler.active(feature), () -> feature + " has no unit → inactive");
        }
    }
}

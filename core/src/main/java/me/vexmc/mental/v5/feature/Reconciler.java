package me.vexmc.mental.v5.feature;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import me.vexmc.mental.v5.config.Snapshot;

/**
 * Converges the registered {@link FeatureUnit}s against a {@link Snapshot}: it
 * opens a {@link Scope} for every enabled unit that is not yet active, closes
 * the scope of every unit that is no longer enabled, and leaves the rest
 * untouched. Truth is the set of open scopes — there is no boolean flag.
 *
 * <p>Zero-touch holds by construction: {@code assemble} is called only on the
 * disabled → enabled transition, so a disabled unit's assemble never runs.
 * Per-unit exception isolation: a throwing assemble closes its own partial
 * scope, logs, and leaves that unit OFF without disturbing the others; a
 * throwing close during disable/teardown is logged and the scope still drops.
 * Open scopes are tracked in enable order so {@link #closeAll} unwinds in
 * reverse.</p>
 */
public final class Reconciler {

    private final Registrar registrar;
    private final Consumer<String> log;
    private final Set<Feature> platformDisabled;
    private final Map<Feature, FeatureUnit> units = new EnumMap<>(Feature.class);
    private final Map<Feature, Scope> open = new java.util.LinkedHashMap<>();

    public Reconciler(Registrar registrar, Consumer<String> log) {
        this(registrar, log, Set.of());
    }

    /**
     * As {@link #Reconciler(Registrar, Consumer)} but with a platform veto: a
     * feature a {@link me.vexmc.mental.v5.platform.PlatformProfile} Required miss
     * disabled stays off no matter what the config says (the loud log already
     * fired at profile resolve). Empty on every supported version.
     */
    public Reconciler(Registrar registrar, Consumer<String> log, Set<Feature> platformDisabled) {
        this.registrar = registrar;
        this.log = log;
        this.platformDisabled = Set.copyOf(platformDisabled);
    }

    /** Registers a unit; a second unit for the same descriptor is a bug and throws. */
    public void register(FeatureUnit unit) {
        Feature descriptor = unit.descriptor();
        if (units.containsKey(descriptor)) {
            throw new IllegalStateException("duplicate feature unit for " + descriptor);
        }
        units.put(descriptor, unit);
    }

    /** Converges open scopes to (snapshot.enabled && a unit is registered). */
    public void converge(Snapshot snapshot) {
        for (Map.Entry<Feature, FeatureUnit> entry : units.entrySet()) {
            Feature feature = entry.getKey();
            boolean desired = snapshot.enabled(feature) && !platformDisabled.contains(feature);
            boolean active = open.containsKey(feature);
            if (desired && !active) {
                enable(entry.getValue(), snapshot);
            } else if (!desired && active) {
                disable(feature);
            }
            // desired && active, or !desired && !active: nothing to do (idempotent).
        }
    }

    private void enable(FeatureUnit unit, Snapshot snapshot) {
        Feature feature = unit.descriptor();
        Scope scope = new Scope(registrar);
        try {
            unit.assemble(scope, snapshot);
            open.put(feature, scope);
        } catch (Exception failure) {
            // Unwind the partial scope; the unit stays OFF. Isolate a close throw too.
            try {
                scope.close();
            } catch (RuntimeException closeFailure) {
                log.accept("closing the partial scope for " + feature + " failed: " + closeFailure);
            }
            log.accept("enabling " + feature + " failed — feature left off: " + failure);
        }
    }

    private void disable(Feature feature) {
        Scope scope = open.remove(feature);
        if (scope == null) {
            return;
        }
        try {
            scope.close();
        } catch (RuntimeException closeFailure) {
            log.accept("closing the scope for " + feature + " failed: " + closeFailure);
        }
    }

    /** Closes every open scope in reverse enable order, isolating each failure. */
    public void closeAll() {
        List<Feature> ordered = new ArrayList<>(open.keySet());
        for (int i = ordered.size() - 1; i >= 0; i--) {
            disable(ordered.get(i));
        }
    }

    /** Truth: the feature has an open scope right now. */
    public boolean active(Feature feature) {
        return open.containsKey(feature);
    }
}

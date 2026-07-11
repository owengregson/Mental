package me.vexmc.mental.v5.feature;

import me.vexmc.mental.v5.config.Snapshot;

/**
 * What a Phase 4 feature implements: its {@link Feature} descriptor, and an
 * {@code assemble} that acquires every resource it needs through the given
 * {@link Scope}. The reconciler calls {@code assemble} exactly when the feature
 * transitions from disabled to enabled; a throw closes the partial scope and
 * leaves the feature OFF (zero-touch on failure).
 */
public interface FeatureUnit {

    /** The descriptor this unit implements — its identity in the reconciler. */
    Feature descriptor();

    /** Acquire everything through {@code scope}; a throw is isolated and unwound. */
    void assemble(Scope scope, Snapshot snapshot) throws Exception;

    /**
     * Whether this unit resolves its settings INTO the assembled state (final
     * fields on constructed listeners) rather than reading the live snapshot
     * per event. The reconciler re-assembles an opted-in unit when a reload
     * changes its settings — otherwise an edit to an already-enabled module
     * silently never lands until the module is toggled or the server restarts
     * (the 2.5.2 hit-feedback/death-effects "signature preset plays nothing"
     * report: the preset was baked at enable time and reload was a no-op).
     * Units that read the snapshot live (the {@code Supplier<Snapshot>}
     * pattern) keep the default and are never bounced.
     */
    default boolean rebuildOnSettingsChange() {
        return false;
    }
}

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
}

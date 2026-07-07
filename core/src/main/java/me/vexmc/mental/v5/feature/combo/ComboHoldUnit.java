package me.vexmc.mental.v5.feature.combo;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.session.SessionService;

/**
 * The combo-hold module (combo-hold §4) — the pocket servo. The whole feature is
 * session-side: the detector lives in each {@code CombatSession}, fed by the
 * delivery fold and the per-tick sweep, and the servo is applied at the knockback
 * engine seam the netty pre-send and the region compute share. So this unit does
 * exactly one thing — open combo tracking while its scope is held, and close it
 * whole when the scope closes.
 *
 * <p>Zero-touch by construction: with no combo keeper holding detection the
 * {@link SessionService} installs no trackers and its per-tick combo sweep
 * short-circuits (no cost); when the last keeper releases, every tracker is dropped
 * on each session's next tick, firing one {@code ComboEndEvent(DISABLED)} on the
 * owning thread. The detector thresholds and servo knobs are read live from the
 * snapshot at their use sites, so a reload that only re-tunes them takes effect
 * without a scope cycle.</p>
 *
 * <p>Combo DETECTION is a shared substrate since the 2.4.5 detection/servo split:
 * this unit and the {@code ComboReachHandicapUnit} each {@link
 * SessionService#retainCombo() retain} it on assemble and {@link
 * SessionService#releaseCombo() release} it on close, so detection runs whenever
 * EITHER keeper is enabled. The SERVO application ({@code KnockbackUnit} /
 * {@code HitRegistrationUnit}) still self-gates on this module's own flag — with
 * only the handicap on, detection runs but the servo returns σ = 1.</p>
 */
public final class ComboHoldUnit implements FeatureUnit {

    private final SessionService sessions;

    public ComboHoldUnit(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public Feature descriptor() {
        return Feature.COMBO_HOLD;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Retain combo detection while this scope is held; the returned closer
        // releases it. Detection is reference-counted (the reach handicap is the
        // other keeper), so it stays live until every keeper releases. The
        // reconciler runs this on the enable thread and the closer on disable — the
        // per-session reconcile then does the tracker teardown on each owning thread
        // (Folia-correct with no hop here).
        scope.task(() -> {
            sessions.retainCombo();
            return sessions::releaseCombo;
        });
    }
}

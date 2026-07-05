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
 * <p>Zero-touch by construction: with the scope closed the {@link SessionService}
 * installs no trackers and its per-tick combo sweep short-circuits (no cost); a
 * disable/reload-off drops every tracker on each session's next tick, firing one
 * {@code ComboEndEvent(DISABLED)} on the owning thread. The detector thresholds
 * and servo knobs are read live from the snapshot at their use sites, so a reload
 * that only re-tunes them takes effect without a scope cycle.</p>
 *
 * <p>The scope also owns the {@link ComboReachHandicap} lifecycle (design §1, leg
 * 2/3): on enable it registers the handicap's join-sweep listener and sweeps every
 * online player of a crash-leaked modifier (printing the one loud degrade line when
 * the sub-feature is enabled below 1.20.5), and on close it restores every online
 * player INLINE. The handicap's apply/remove itself rides the combo transition
 * through {@link ComboEvents}, not this unit.</p>
 */
public final class ComboHoldUnit implements FeatureUnit {

    private final SessionService sessions;
    private final ComboReachHandicap reachHandicap;

    public ComboHoldUnit(SessionService sessions, ComboReachHandicap reachHandicap) {
        this.sessions = sessions;
        this.reachHandicap = reachHandicap;
    }

    @Override
    public Feature descriptor() {
        return Feature.COMBO_HOLD;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // The reach handicap's stale-sweep on join (leg 2) — registered only while the
        // module holds a scope, and a complete no-op below 1.20.5 / with the sub-feature
        // off (the listener self-gates). Its method descriptors carry no sub-floor type,
        // so it registers cleanly on the whole range.
        scope.listen(reachHandicap);
        // Opening the scope enables session-side tracking; the returned closer
        // disables it. The reconciler runs this on the enable thread and the closer
        // on disable — the per-session reconcile then does the tracker teardown on
        // each owning thread (Folia-correct with no hop here).
        scope.task(() -> {
            sessions.enableCombo();
            // Leg 2: sweep online players of any stale handicap and report the degrade
            // line if enabled-but-unsupported. Leg 3: the closer restores every online
            // player inline (belt-and-suspenders to the per-combo DISABLED-end removal).
            reachHandicap.enable();
            return () -> {
                sessions.disableCombo();
                reachHandicap.disable();
            };
        });
    }
}

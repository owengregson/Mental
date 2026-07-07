package me.vexmc.mental.v5.feature.combo;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.session.SessionService;

/**
 * The combo reach-handicap module (design §1), promoted to its own feature in 2.4.4
 * so it surfaces and toggles in the management GUI (field report 2 fix). It owns the
 * {@link ComboReachHandicap} lifecycle (legs 2/3): on enable it registers the
 * handicap's join-sweep listener and sweeps every online player of a crash-leaked
 * modifier (printing the one loud degrade line when enabled below 1.20.5), and on
 * close it restores every online player INLINE.
 *
 * <p><b>Combo detection is a shared substrate (the 2.4.5 detection/servo split).</b>
 * The handicap's per-combo apply/remove rides the combo transition through
 * {@link ComboEvents}, which only fires while the {@code SessionService} runs combo
 * detection. So this unit {@link SessionService#retainCombo() retains} detection on
 * assemble and {@link SessionService#releaseCombo() releases} it on close, exactly
 * like the {@code ComboHoldUnit} — detection runs whenever EITHER keeper is on, so
 * the handicap engages STANDALONE, with the pocket servo off. It no longer depends
 * on combo-hold (the parser no longer warns), and it self-gates on its own enabled
 * bit, so a disabled module does nothing. Zero-touch when the scope is closed: no
 * listener registered, no sweep run, no detection retained.</p>
 */
public final class ComboReachHandicapUnit implements FeatureUnit {

    private final ComboReachHandicap reachHandicap;
    private final SessionService sessions;

    public ComboReachHandicapUnit(ComboReachHandicap reachHandicap, SessionService sessions) {
        this.reachHandicap = reachHandicap;
        this.sessions = sessions;
    }

    @Override
    public Feature descriptor() {
        return Feature.COMBO_REACH_HANDICAP;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Leg 2 stale-sweep on join — registered only while the module holds a scope,
        // and a complete no-op below 1.20.5 (the listener self-gates). Its method
        // descriptors carry no sub-floor type, so it registers cleanly on the whole range.
        scope.listen(reachHandicap);
        scope.task(() -> {
            // Retain combo detection so the transitions the handicap rides fire even
            // with the pocket servo off (the 2.4.5 detection/servo split); the closer
            // releases it. Leg 2: sweep online players of any stale handicap and report
            // the degrade line if enabled-but-unsupported. Leg 3: the closer restores
            // every online player inline (belt-and-suspenders to the per-combo
            // DISABLED-end removal). The retain is paired with a release on EVERY exit
            // path: a throwing enable() must not leak the detection ref-count (a leaked
            // keeper would hold detection live forever, breaking zero-touch), and the
            // closer always releases even if disable() throws.
            sessions.retainCombo();
            try {
                reachHandicap.enable();
            } catch (RuntimeException | Error enableFailed) {
                sessions.releaseCombo();
                throw enableFailed;
            }
            return () -> {
                try {
                    reachHandicap.disable();
                } finally {
                    sessions.releaseCombo();
                }
            };
        });
    }
}

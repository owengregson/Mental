package me.vexmc.mental.v5.feature.combo;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * The combo reach-handicap module (design §1), promoted to its own feature in 2.4.4
 * so it surfaces and toggles in the management GUI (field report 2 fix). It owns the
 * {@link ComboReachHandicap} lifecycle (legs 2/3): on enable it registers the
 * handicap's join-sweep listener and sweeps every online player of a crash-leaked
 * modifier (printing the one loud degrade line when enabled below 1.20.5), and on
 * close it restores every online player INLINE.
 *
 * <p>The handicap's per-combo apply/remove itself rides the combo transition through
 * {@link ComboEvents}, not this unit — and self-gates on this module's enabled bit,
 * so a disabled module does nothing. It only ever engages while a combo is held, so
 * it depends on COMBO_HOLD (the parser warns loudly when this is on and combo-hold is
 * off). Zero-touch when the scope is closed: no listener registered, no sweep run.</p>
 */
public final class ComboReachHandicapUnit implements FeatureUnit {

    private final ComboReachHandicap reachHandicap;

    public ComboReachHandicapUnit(ComboReachHandicap reachHandicap) {
        this.reachHandicap = reachHandicap;
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
            // Leg 2: sweep online players of any stale handicap and report the degrade
            // line if enabled-but-unsupported. Leg 3: the closer restores every online
            // player inline (belt-and-suspenders to the per-combo DISABLED-end removal).
            reachHandicap.enable();
            return reachHandicap::disable;
        });
    }
}

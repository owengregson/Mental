package me.vexmc.mental.v5.feature.combo;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.session.SessionService;

/**
 * The vertical-trim module (COMBO_VERTICAL) — the third combo keeper, shaping the
 * FRESH vertical knock (the {@code VerticalTrim} kernel piece) exactly as the pocket
 * servo shapes the fresh horizontal. Like {@link ComboHoldUnit} the whole feature is
 * session-side: the shaper is applied at the knockback engine seam (netty pre-send +
 * region compute), gated on {@code snapshot.enabled(COMBO_VERTICAL)}. So this unit
 * does exactly one thing — retain the shared combo detection while its scope is held,
 * and release it when the scope closes.
 *
 * <p><b>INTEGRATION (feature/combo-vertical-trim, parallel-safe).</b> The two keepers
 * (combo-hold and this) must share ONE detector so a "vertical-only" configuration
 * still detects combos and publishes the victim's combo attacker. On this base
 * {@link SessionService#enableCombo()}/{@link SessionService#disableCombo()} are
 * REF-COUNTED retain/release (converted from the single boolean combo-hold used), so
 * detection stays live while EITHER keeper holds it. The concurrent 2.4.5 combo-substrate
 * rebuild is expected to land the same retain/release seam (per the workstream brief);
 * reconcile the two implementations at merge — this unit simply retains on assemble and
 * releases on close, the same contract {@link ComboHoldUnit} uses.</p>
 *
 * <p>Zero-touch by construction: with BOTH keepers' scopes closed the retain count is
 * zero, so {@link SessionService} installs no trackers and its per-tick combo sweep
 * short-circuits (no cost). The trim knobs are read live from the snapshot at the
 * engine seam, so a reload that only re-tunes them takes effect without a scope cycle.</p>
 */
public final class ComboVerticalUnit implements FeatureUnit {

    private final SessionService sessions;

    public ComboVerticalUnit(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public Feature descriptor() {
        return Feature.COMBO_VERTICAL;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Retain the shared combo detection while this module holds a scope; the
        // returned closer releases it. The reconciler runs this on the enable thread and
        // the closer on disable — detection stays live as long as any keeper retains it.
        scope.task(() -> {
            sessions.enableCombo();
            return sessions::disableCombo;
        });
    }
}

package me.vexmc.mental.v5.delivery;

import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.v5.CombatSession;

/**
 * Shared delivery-side ledger recording (spec §3.5 step 3). Both the velocity
 * event resolution ({@code DeskRouter}) and the ensure-delivery path (rod / thrown
 * self-launch) fold the FINAL delivered value into the victim's motion ledger
 * under the same combo-era rules, so a hit records exactly once whichever path
 * ships it.
 */
public final class Deliveries {

    private Deliveries() {}

    /**
     * Records the delivered {@code shipped} value into the victim's ledger when the
     * source's combo rule says it feeds the next hit: melee only when the profile's
     * {@code combos} is on (1.8.9's send-then-revert leaves melee out); rod, arrow
     * and thrown always. The launch ground state comes from the published view.
     */
    public static void recordDelivered(CombatSession session, HitSource source, KnockbackVector shipped) {
        if (shipped == null || source == null) {
            return;
        }
        PlayerView view = session.view();
        if (view == null) {
            return;
        }
        boolean melee = source instanceof HitSource.Melee
                || (source instanceof HitSource.Vanilla vanilla && "ENTITY_ATTACK".equals(vanilla.damageCause()));
        boolean record = melee
                ? view.profile().combos()
                : source instanceof HitSource.RodPull
                        || source instanceof HitSource.Arrow
                        || source instanceof HitSource.Thrown
                        || source instanceof HitSource.Bobber;
        if (record) {
            session.ledger().record(shipped.x(), shipped.y(), shipped.z(),
                    view.grounded(), view.slipperiness(), view.at());
        }
    }
}

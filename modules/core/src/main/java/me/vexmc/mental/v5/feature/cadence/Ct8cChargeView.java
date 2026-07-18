package me.vexmc.mental.v5.feature.cadence;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The published charge view (CT8c decompile, spec §2.1) — the one seam the
 * combat-core cluster shares across its otherwise-independent, no-arg feature
 * units. {@code ChargedAttackUnit} owns the D2 charge ledger and PUBLISHES the
 * current attack scale (and the ≥195% charged-reach flag) here on each melee
 * primary; the cluster's own consumers — {@code Ct8cSweepUnit} (the ≥195% sweep
 * gate) and {@code Ct8cReachUnit} (the charged reach bonus) — READ it.
 *
 * <p>The publish rides the primary {@code ENTITY_ATTACK} event, which vanilla
 * processes strictly before the {@code ENTITY_SWEEP_ATTACK} secondaries, so a
 * sweep read always sees the current hit's scale with no priority coordination
 * between the units. Reads default to {@code 0.0} scale / no bonus when nothing is
 * published (charged-attacks disabled, or a player who has not swung) — the
 * era-safe "not charged" answer.</p>
 *
 * <p>A process-wide singleton because the units are constructed independently and
 * hold no reference to one another; when {@code charged-attacks} is disabled the
 * publisher clears it, so it decays to the inert default.</p>
 */
public final class Ct8cChargeView {

    /** The shared instance the cluster's units publish to / read from. */
    public static final Ct8cChargeView INSTANCE = new Ct8cChargeView();

    private final Map<UUID, Double> scale = new ConcurrentHashMap<>();
    private final Set<UUID> chargedReach = ConcurrentHashMap.newKeySet();

    Ct8cChargeView() {}

    /** Publishes the current attack scale and charged-reach flag for {@code id} (the publisher only). */
    void publish(UUID id, double currentScale, boolean bonusReach) {
        scale.put(id, currentScale);
        if (bonusReach) {
            chargedReach.add(id);
        } else {
            chargedReach.remove(id);
        }
    }

    /** The last-published attack scale for {@code id}, or {@code 0.0} when nothing is published. */
    public double currentScale(UUID id) {
        Double value = scale.get(id);
        return value != null ? value : 0.0;
    }

    /** Whether {@code id}'s last melee was a ≥195% charged hit that earns the +1.0 reach (spec §2.1). */
    public boolean chargedReach(UUID id) {
        return chargedReach.contains(id);
    }

    /** Forgets a player (quit). */
    void clear(UUID id) {
        scale.remove(id);
        chargedReach.remove(id);
    }

    /** Drops all published state (the publisher's scope close). */
    void reset() {
        scale.clear();
        chargedReach.clear();
    }
}

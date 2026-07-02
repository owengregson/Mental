package me.vexmc.mental.v5.feature.cadence;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * Disables the 1.9 sword sweep (the retired {@code module.rules.sweep.SweepModule}
 * on the v5 seam): swords hit a single target with no sweep particle, as in
 * 1.7/1.8. Both halves register through the unit's scope so neither survives the
 * feature (no split-brain):
 *
 * <ul>
 *   <li>the event / vanilla-path half — {@link SweepDamageListener} cancels the
 *       {@code ENTITY_SWEEP_ATTACK} damage (and its knockback);</li>
 *   <li>the netty / client half — {@link SweepParticleListener} cancels the
 *       {@code sweep_attack} particle.</li>
 * </ul>
 *
 * <p>Distinct from {@code AttackCooldownUnit}'s own sweep re-disable (which fires
 * because a full charge restores sweep on the vanilla path — mandate B5(d)):
 * enabling both simply double-suppresses, which is idempotent.</p>
 */
public final class SweepUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.SWEEP;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(new SweepDamageListener());
        scope.packets(new SweepParticleListener());
    }
}

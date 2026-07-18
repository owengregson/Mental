package me.vexmc.mental.v5.feature.cadence;

import me.vexmc.mental.platform.SweepCauses;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

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
 *
 * <p>Below 1.11 the {@code ENTITY_SWEEP_ATTACK} cause does not exist (the 2.4.1
 * GAP-2 finding: a direct constant reference is a sticky per-event
 * {@code NoSuchFieldError} the bus swallows) and sweep splash arrives as plain
 * {@code ENTITY_ATTACK} — indistinguishable by cause, and a same-tick raw-1.0
 * heuristic is rejected as a zero-touch risk. The feature is a documented no-op
 * there: {@link SweepCauses} decides ONCE at assemble, NEITHER half registers
 * (a particle-only cancel would land sweep damage invisibly — worse than
 * vanilla), and the degrade line prints. Vanilla sword sweep remains.</p>
 */
public final class SweepUnit implements FeatureUnit {

    private final Plugin plugin;

    public SweepUnit(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Feature descriptor() {
        return Feature.SWEEP;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        if (!SweepCauses.present()) {
            plugin.getLogger().info("disable-sword-sweep: the ENTITY_SWEEP_ATTACK damage cause is "
                    + "absent on this version (lands 1.11) — sweep splash arrives as plain "
                    + "ENTITY_ATTACK and cannot be discriminated, so this feature is a documented "
                    + "no-op here; vanilla sword sweep remains.");
            return;
        }
        scope.listen(new SweepDamageListener());
        scope.packets(new SweepParticleListener());
    }
}

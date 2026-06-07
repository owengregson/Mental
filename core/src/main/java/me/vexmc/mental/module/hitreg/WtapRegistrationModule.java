package me.vexmc.mental.module.hitreg;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.jetbrains.annotations.NotNull;

/**
 * In-order sprint reads for the fast path.
 *
 * <p>The era server applied inbound packets in arrival order: an attack
 * always saw its attacker's sprint flag with every earlier STOP/START
 * already applied, so a w-tap registered no matter how little wall-clock
 * separated the re-press from the click. Mental's fast path registers the
 * attack mid-tick — <em>faster</em> than the era — but against a snapshot
 * frozen at the tick boundary, which is sprint state up to a tick
 * <em>older</em> than the era contract: a fast w-tap shipped plain and an
 * s-tap kept a bonus the era denied. This module flips the
 * {@link me.vexmc.mental.module.knockback.SprintTracker}'s wire view on,
 * letting registration read the attacker's entity-action packets replayed
 * at arrival (the attacker's own netty thread — program order with their
 * ATTACK by construction). The sibling of the ground packet tap's
 * within-tick attack ordering for victims, applied to the attacker.</p>
 *
 * <p>All state lives in the always-on tracker and tap; enable/disable only
 * gates whether the fast path consults it — disabled is byte-identical to
 * the pre-module pipeline, and synthetic players (who send no packets)
 * always fall back to the snapshot either way.</p>
 */
public final class WtapRegistrationModule extends CombatModule {

    public WtapRegistrationModule(@NotNull MentalServices services) {
        super(services, "wtap-registration", "W-tap Registration",
                "Sprint state for fast-path hits read in packet-arrival order — "
                        + "w-taps and s-taps register at any tap speed, like the era queue.",
                DebugCategory.HITREG);
    }

    @Override
    public boolean configEnabled() {
        return services.config().wtap().enabled();
    }

    @Override
    protected void onEnable() {
        services.sprintTracker().consultWire(true);
    }

    @Override
    protected void onDisable() {
        services.sprintTracker().consultWire(false);
    }
}

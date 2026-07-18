package me.vexmc.mental.v5.delivery;

import me.vexmc.mental.kernel.model.KnockbackVector;

/**
 * The write surface a {@link me.vexmc.mental.kernel.delivery.Directive} executes
 * against — the tiny adapter over a {@code PlayerVelocityEvent}. Extracting it
 * lets the directive-to-action mapping ({@link DirectiveExecutor}) be unit-tested
 * with a stub instead of a live Bukkit event.
 */
public interface VelocitySink {

    /** Set the shipped velocity (SHIP / SHIP_AND_ARM_VALVE). */
    void ship(KnockbackVector velocity);

    /** Cancel the velocity event (a suppressed hit — legacy resistance roll). */
    void cancel();
}

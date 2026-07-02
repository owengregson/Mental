package me.vexmc.mental.kernel.delivery;

import me.vexmc.mental.kernel.model.KnockbackVector;

/**
 * What the core shell must do after a velocity-event resolution — the kernel's
 * decision, executed by the Bukkit-facing rim (spec §3.5).
 *
 * <ul>
 *   <li>{@code SHIP} — set the velocity to {@link #ship}; no valve.</li>
 *   <li>{@code SHIP_AND_ARM_VALVE} — set {@link #ship} and arm {@link #arm} so
 *       the following ENTITY_VELOCITY send is consumed once (dedup).</li>
 *   <li>{@code CANCEL_EVENT} — cancel the velocity event (a suppressed hit).</li>
 *   <li>{@code PASS_THROUGH} — foreign velocity; leave the event untouched.</li>
 * </ul>
 */
public record Directive(Action action, KnockbackVector ship, ValvePayload arm) {

    public enum Action { SHIP, SHIP_AND_ARM_VALVE, CANCEL_EVENT, PASS_THROUGH }
}

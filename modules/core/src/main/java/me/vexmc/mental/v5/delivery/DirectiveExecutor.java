package me.vexmc.mental.v5.delivery;

import me.vexmc.mental.kernel.delivery.Directive;
import me.vexmc.mental.kernel.delivery.ValvePayload;

/**
 * Executes a kernel {@link Directive} against a {@link VelocitySink} — the pure
 * decision-to-action mapping the desk resolution hands the rim (spec §3.5).
 * Kept side-effect-narrow (velocity sink only) so the mapping is unit-pinned:
 *
 * <ul>
 *   <li>{@code SHIP} — set the shipped velocity; no valve (returns null).</li>
 *   <li>{@code SHIP_AND_ARM_VALVE} — set it and RETURN the {@link ValvePayload}
 *       arm intent; the caller confirms and arms at MONITOR, after the velocity
 *       event has survived every listener priority.</li>
 *   <li>{@code CANCEL_EVENT} — cancel (a suppressed hit); returns null.</li>
 *   <li>{@code PASS_THROUGH} — foreign velocity; leave the event untouched; null.</li>
 * </ul>
 *
 * <p>The returned payload is an ARM INTENT, not an executed arm: vanilla emits
 * the matching ENTITY_VELOCITY duplicate only for a SURVIVING event, so arming
 * here (HIGH-time) left a dead arm whenever a HIGHEST/MONITOR foreign listener
 * cancelled or modified the event — and a dead arm aliases the next
 * byte-identical hit's duplicate. The caller confirms at MONITOR instead.</p>
 */
public final class DirectiveExecutor {

    private DirectiveExecutor() {}

    public static ValvePayload apply(Directive directive, VelocitySink sink) {
        switch (directive.action()) {
            case SHIP -> sink.ship(directive.ship());
            case SHIP_AND_ARM_VALVE -> {
                sink.ship(directive.ship());
                return directive.arm();
            }
            case CANCEL_EVENT -> sink.cancel();
            case PASS_THROUGH -> {
                // Foreign velocity — leave the event exactly as it stands.
            }
        }
        return null;
    }
}

package me.vexmc.mental.v5.delivery;

import java.util.UUID;
import me.vexmc.mental.kernel.delivery.Directive;
import me.vexmc.mental.v5.VelocityValve;

/**
 * Executes a kernel {@link Directive} against a {@link VelocitySink} — the pure
 * decision-to-action mapping the desk resolution hands the rim (spec §3.5).
 * Kept side-effect-narrow (velocity sink + valve) so the mapping is unit-pinned:
 *
 * <ul>
 *   <li>{@code SHIP} — set the shipped velocity; no valve.</li>
 *   <li>{@code SHIP_AND_ARM_VALVE} — set it and arm the valve so the following
 *       ENTITY_VELOCITY send is consumed once (dedup of the pre-delivered value).</li>
 *   <li>{@code CANCEL_EVENT} — cancel (a suppressed hit).</li>
 *   <li>{@code PASS_THROUGH} — foreign velocity; leave the event untouched.</li>
 * </ul>
 */
public final class DirectiveExecutor {

    private DirectiveExecutor() {}

    public static void apply(Directive directive, VelocitySink sink, UUID victim, VelocityValve valve) {
        switch (directive.action()) {
            case SHIP -> sink.ship(directive.ship());
            case SHIP_AND_ARM_VALVE -> {
                sink.ship(directive.ship());
                valve.arm(victim, directive.arm());
            }
            case CANCEL_EVENT -> sink.cancel();
            case PASS_THROUGH -> {
                // Foreign velocity — leave the event exactly as it stands.
            }
        }
    }
}

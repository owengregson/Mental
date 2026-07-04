package me.vexmc.mental.v5.feature.combo;

import java.util.UUID;
import me.vexmc.mental.api.event.ComboEndEvent;
import me.vexmc.mental.api.event.ComboStartEvent;
import me.vexmc.mental.kernel.combo.ComboEndReason;
import me.vexmc.mental.kernel.combo.ComboTransition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Fires the public {@code ComboStartEvent}/{@code ComboEndEvent} for a kernel
 * {@link ComboTransition} — the one place the kernel's Bukkit-free transition
 * signal becomes a Bukkit event (combo-hold §4). Called on the victim's owning
 * region thread by every site that mutates a tracker (the session sweep, the
 * delivery fold, the retaliation stamp), so the events always fire on the region
 * thread and never async.
 *
 * <p>The attacker id is resolved to a {@link LivingEntity} best-effort: an
 * off-region read on Folia yields null, which the api events allow — the victim
 * is always present.</p>
 */
public final class ComboEvents {

    private ComboEvents() {}

    /** Fires the matching api event for {@code transition}, or nothing for {@link ComboTransition#NONE}. */
    public static void fire(Player victim, ComboTransition transition) {
        if (transition == null || transition.kind() == ComboTransition.Kind.NONE) {
            return;
        }
        LivingEntity attacker = resolve(transition.attacker());
        if (transition.started()) {
            Bukkit.getPluginManager().callEvent(
                    new ComboStartEvent(victim, attacker, transition.hits()));
        } else {
            Bukkit.getPluginManager().callEvent(
                    new ComboEndEvent(victim, attacker, map(transition.reason())));
        }
    }

    /** Best-effort entity resolution; null off-region (Folia) or when the entity is gone / non-living. */
    private static LivingEntity resolve(UUID attackerId) {
        if (attackerId == null) {
            return null;
        }
        try {
            Entity entity = Bukkit.getEntity(attackerId);
            return entity instanceof LivingEntity living ? living : null;
        } catch (Throwable offRegion) {
            return null;
        }
    }

    /** Maps the kernel's end reason onto the public enum (kept separate so the kernel stays api-free). */
    private static ComboEndEvent.Reason map(ComboEndReason reason) {
        return switch (reason) {
            case EXPIRED -> ComboEndEvent.Reason.EXPIRED;
            case RETALIATION -> ComboEndEvent.Reason.RETALIATION;
            case GROUNDED -> ComboEndEvent.Reason.GROUNDED;
            case BLOWOUT -> ComboEndEvent.Reason.BLOWOUT;
            case RETIRED -> ComboEndEvent.Reason.RETIRED;
            case DISABLED -> ComboEndEvent.Reason.DISABLED;
        };
    }
}

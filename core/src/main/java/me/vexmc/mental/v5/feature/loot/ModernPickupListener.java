package me.vexmc.mental.v5.feature.loot;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;

/**
 * The 1.12+ pickup gate. Cancels a protected drop's pickup by anyone but the
 * killer — the victim, a third party, or a mob. Kept in its own class so the
 * {@link EntityPickupItemEvent} type (added in 1.12) is never referenced on a
 * pre-1.12 server, where {@link LegacyPickupListener} handles the older
 * player-only event instead.
 */
final class ModernPickupListener implements Listener {

    private final DropProtectionState state;

    ModernPickupListener(DropProtectionState state) {
        this.state = state;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        int entityId = event.getItem().getEntityId();
        if (!state.isProtected(entityId)) {
            return; // untracked item — vanilla pickup
        }
        if (event.getEntity() instanceof Player player && state.mayPickup(entityId, player.getUniqueId())) {
            state.forget(entityId); // the killer collected their reserved loot
            return;
        }
        event.setCancelled(true); // reserved to the killer for now
    }
}

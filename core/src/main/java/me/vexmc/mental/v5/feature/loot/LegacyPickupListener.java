package me.vexmc.mental.v5.feature.loot;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPickupItemEvent;

/**
 * The pre-1.12 pickup gate, using the deprecated-but-universal
 * {@link PlayerPickupItemEvent} (the only pickup event that exists below 1.12).
 * Its own class so the modern {@code EntityPickupItemEvent} type is never
 * referenced on an old server; {@link ModernPickupListener} handles 1.12+.
 */
@SuppressWarnings("deprecation")
final class LegacyPickupListener implements Listener {

    private final DropProtectionState state;

    LegacyPickupListener(DropProtectionState state) {
        this.state = state;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        int entityId = event.getItem().getEntityId();
        if (!state.isProtected(entityId)) {
            return; // untracked item — vanilla pickup
        }
        if (state.mayPickup(entityId, event.getPlayer().getUniqueId())) {
            state.forget(entityId); // the killer collected their reserved loot
            return;
        }
        event.setCancelled(true); // reserved to the killer for now
    }
}

package me.vexmc.mental.module.knockback;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Sprint freshness per attacker — the signal behind a profile's
 * {@code wtap-extra} split.
 *
 * <p>An attacker is "fresh" when they have (re-)engaged sprint since their
 * last sprint hit: the START_SPRINTING that follows a w-tap (or the server's
 * own post-hit sprint reset) arms the flag, and the next sprint hit consumes
 * it. A sprint held continuously across hits therefore reads as not-fresh —
 * exactly WindSpigot's {@code isExtraKnockback} branch, derived from the only
 * honest server-side signal there is.</p>
 *
 * <p>Observation only: with every profile's {@code wtap-extra} disabled this
 * tracker changes nothing, preserving the zero-touch guarantee. Writes happen
 * on owning threads (Bukkit events); the netty pre-send merely peeks, which
 * the concurrent set makes safe.</p>
 */
public final class SprintTracker implements Listener {

    private final Set<UUID> fresh = ConcurrentHashMap.newKeySet();

    @EventHandler
    public void onToggleSprint(@NotNull PlayerToggleSprintEvent event) {
        if (event.isSprinting()) {
            fresh.add(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        fresh.remove(event.getPlayer().getUniqueId());
    }

    /** Arms freshness directly — the toggle-event path, exposed for tests. */
    public void arm(@NotNull UUID attacker) {
        fresh.add(attacker);
    }

    /** Read-only check for the netty pre-send prediction. */
    public boolean peekFresh(@NotNull UUID attacker) {
        return fresh.contains(attacker);
    }

    /** The authoritative read: reports freshness and spends it. */
    public boolean consumeFresh(@NotNull UUID attacker) {
        return fresh.remove(attacker);
    }

    public void clear() {
        fresh.clear();
    }
}

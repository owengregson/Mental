package me.vexmc.mental.debug;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.common.debug.DebugLog;
import me.vexmc.mental.text.Brand;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Streams debug lines to admins who opted in via {@code /mental debug subscribe}. */
public final class PlayerDebugSink implements DebugLog.Sink {

    private final Set<UUID> subscribers = ConcurrentHashMap.newKeySet();

    /** @return true when the player is now subscribed. */
    public boolean toggle(@NotNull UUID player) {
        if (subscribers.remove(player)) {
            return false;
        }
        subscribers.add(player);
        return true;
    }

    public void forget(@NotNull UUID player) {
        subscribers.remove(player);
    }

    @Override
    public void accept(@NotNull DebugCategory category, @NotNull String message) {
        if (subscribers.isEmpty()) {
            return;
        }
        Component line = Brand.line(Component.text()
                .append(Component.text("debug:" + category.key() + " ", Brand.MUTED))
                .append(Component.text(message, Brand.TEXT))
                .build());
        for (UUID id : subscribers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                player.sendMessage(line);
            }
        }
    }
}

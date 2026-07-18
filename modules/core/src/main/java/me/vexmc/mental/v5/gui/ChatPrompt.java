package me.vexmc.mental.v5.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.text.Brand;
import me.vexmc.mental.v5.text.TextPort;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * The one-shot chat input the in-GUI value editors use. There is no anvil or
 * sign framework in the codebase; a chat prompt is the cross-version choice
 * ({@link AsyncPlayerChatEvent} spans the whole 1.9.4 → 26.x runtime range). A
 * screen calls {@link #request}: the menu closes, the player is asked to type a
 * value (or {@code cancel}), and the FIRST line they send is captured — the
 * event cancelled so it never hits public chat — and handed to the callback on
 * the global thread (where a config write and a menu reopen are both safe).
 *
 * <p>Always-on GUI infrastructure, registered for the plugin's lifetime beside
 * {@link MenuManager}; it never touches the game (zero-touch holds trivially).
 * A pending prompt is dropped on quit, and a chat line with no pending prompt is
 * ordinary chat.</p>
 */
public final class ChatPrompt implements Listener {

    private record Pending(@NotNull Consumer<String> onInput, @NotNull Runnable onCancel) {}

    private final Scheduling scheduling;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(@NotNull Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    /**
     * Asks {@code player} to type a value. The current menu is closed and
     * {@code label} is shown; the next chat line runs {@code onInput} (trimmed,
     * never blank), or {@code onCancel} if they type {@code cancel}. Both run on
     * the global thread. A prompt already pending for the player is replaced.
     */
    public void request(
            @NotNull Player player, @NotNull String label,
            @NotNull Consumer<String> onInput, @NotNull Runnable onCancel) {
        pending.put(player.getUniqueId(), new Pending(onInput, onCancel));
        scheduling.runOn(player, () -> {
            player.closeInventory();
            // Route the Component through TextPort → the universal String
            // sendMessage overload; sendMessage(Component) is absent below 1.16.5.
            player.sendMessage(TextPort.legacy(prompt(label)));
        }, () -> pending.remove(player.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onChat(@NotNull AsyncPlayerChatEvent event) {
        Pending waiting = pending.remove(event.getPlayer().getUniqueId());
        if (waiting == null) {
            return; // ordinary chat — no prompt is waiting on this player
        }
        event.setCancelled(true); // the captured line never reaches public chat
        String message = event.getMessage().trim();
        if (message.isEmpty() || message.equalsIgnoreCase("cancel")) {
            scheduling.runGlobal(waiting.onCancel());
            return;
        }
        scheduling.runGlobal(() -> waiting.onInput().accept(message));
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    /** Drops every pending prompt and unregisters on plugin disable. */
    public void shutdown() {
        pending.clear();
        HandlerList.unregisterAll(this);
    }

    private static @NotNull Component prompt(@NotNull String label) {
        return Component.text()
                .append(Component.text("✎ ", Brand.SECONDARY))
                .append(Component.text(label, Brand.TEXT))
                .append(Component.text("  — type a value in chat, or ", Brand.MUTED))
                .append(Component.text("cancel", Brand.SECONDARY).decoration(TextDecoration.BOLD, true))
                .append(Component.text(".", Brand.MUTED))
                .build();
    }
}

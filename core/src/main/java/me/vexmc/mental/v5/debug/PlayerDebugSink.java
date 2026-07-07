package me.vexmc.mental.v5.debug;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.debug.DebugCategory;
import me.vexmc.mental.platform.debug.DebugLog;
import me.vexmc.mental.v5.text.Brand;
import me.vexmc.mental.v5.text.TextPort;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The player-facing verbose-logging sink: streams each rendered debug line to the
 * admins who opted in via {@code /mental debug subscribe} (or the GUI tile). This
 * is the second half of the design's two-sink model (rewrite design §4.7 — "sinks:
 * console … and opted-in admins, permission-gated"); the v5 rewrite shipped only
 * the console sink, so subscribers never saw anything until this landed.
 *
 * <p><b>Threading.</b> {@link #accept} is invoked wherever a scoped {@code
 * DebugLog.log(...)} fires — that includes the netty read thread and the region
 * threads. It therefore obeys the netty discipline: it resolves the target through
 * {@code Bukkit.getPlayer(UUID)} (the accepted off-thread handle lookup, as in the
 * latency and hit-registration units) but NEVER touches live-entity state itself —
 * every {@code sendMessage} is hopped onto the player's owning region thread via
 * {@link Scheduling#runOn} (Folia-correct; the retired runnable covers the quit
 * race). No Adventure {@link Component} ever crosses a Bukkit boundary: the branded
 * line is serialized to a legacy {@code §} string inside {@link TextPort#send}.</p>
 *
 * <p><b>Zero-touch.</b> With nobody subscribed, {@link #accept} early-returns on the
 * empty-set check before rendering, allocating, or scheduling anything — so a live
 * debug channel with no subscribers is byte-identical to having no player sink at
 * all. Subscriptions are cleared on quit through the session forget hook.</p>
 */
public final class PlayerDebugSink implements DebugLog.Sink {

    private final Scheduling scheduling;
    /** Opted-in admins, keyed by UUID — a concurrent set because {@link #accept} reads it off-thread. */
    private final Set<UUID> subscribers = ConcurrentHashMap.newKeySet();

    public PlayerDebugSink(@NotNull Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    /**
     * Toggles {@code player}'s subscription and returns the NEW state: {@code true}
     * when this call subscribed them, {@code false} when it unsubscribed them.
     */
    public boolean toggle(@NotNull Player player) {
        UUID id = player.getUniqueId();
        if (subscribers.remove(id)) {
            return false;
        }
        subscribers.add(id);
        return true;
    }

    public boolean isSubscribed(@NotNull UUID id) {
        return subscribers.contains(id);
    }

    public boolean isSubscribed(@NotNull Player player) {
        return isSubscribed(player.getUniqueId());
    }

    /** Drops a subscription — wired to the per-quit session forget hook ("cleared on quit"). */
    public void forget(@NotNull UUID id) {
        subscribers.remove(id);
    }

    @Override
    public void accept(@NotNull DebugCategory category, @NotNull String message) {
        // Zero-touch: no subscribers ⇒ no render, no allocation, no scheduling. This
        // is the byte-identical-when-off guarantee for the player-facing half.
        if (subscribers.isEmpty()) {
            return;
        }
        // Rendered once (the same line goes to every subscriber). Building the
        // Component is pure and thread-safe on any thread; the Bukkit boundary is
        // only ever crossed inside the region-thread hop below.
        Component line = brandedLine(category, message);
        for (UUID id : subscribers) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                // A stale UUID (quit between the empty check and here) is harmless —
                // the quit forget hook removes it; skip it this pass.
                continue;
            }
            // Hop onto the player's owning region thread: sendMessage off the owning
            // thread is unsafe on Folia. The retired runnable is a no-op — a debug
            // line lost to a mid-hop quit is not worth chasing.
            scheduling.runOn(player, () -> TextPort.send(player, line), () -> {});
        }
    }

    private static @NotNull Component brandedLine(@NotNull DebugCategory category, @NotNull String message) {
        return Brand.line(Component.text()
                .append(Component.text("[" + category.key() + "] ", Brand.ACCENT))
                .append(Component.text(message, Brand.MUTED))
                .build());
    }
}

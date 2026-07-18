package me.vexmc.mental.v5.feature;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import java.util.function.Supplier;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * The production {@link Registrar} — the seam a {@link Scope} routes through
 * (spec §7). Every method returns an {@link AutoCloseable} that exactly undoes
 * the registration it performed, so a feature's scope tears itself down by
 * closing what it collected.
 *
 * <ul>
 *   <li>{@link #bukkit} — {@code PluginManager.registerEvents}; the closeable
 *       is {@code HandlerList.unregisterAll(listener)}.</li>
 *   <li>{@link #packets} — the PacketEvents event manager returns a handle; the
 *       closeable {@code unregisterListener}s it. Rim/feature listeners carry
 *       their own priority (they extend {@code PacketListenerAbstract}), so no
 *       priority is threaded through the seam.</li>
 *   <li>{@link #task} — invokes the starter (which wraps
 *       {@code Scheduling.repeat*}); the closeable cancels the {@code TaskHandle}
 *       the starter returned.</li>
 * </ul>
 */
public final class BukkitRegistrar implements Registrar {

    private final Plugin plugin;

    public BukkitRegistrar(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public AutoCloseable bukkit(Object listener) {
        Listener bukkitListener = (Listener) listener;
        plugin.getServer().getPluginManager().registerEvents(bukkitListener, plugin);
        return () -> HandlerList.unregisterAll(bukkitListener);
    }

    @Override
    public AutoCloseable packets(Object peListener) {
        PacketListenerCommon handle = PacketEvents.getAPI().getEventManager()
                .registerListener((PacketListenerCommon) peListener);
        return () -> PacketEvents.getAPI().getEventManager().unregisterListener(handle);
    }

    @Override
    public AutoCloseable task(Supplier<AutoCloseable> starter) {
        // The starter wraps a Scheduling.repeat* call and returns a closeable
        // that cancels the TaskHandle. Invoked here, torn down on scope close.
        return starter.get();
    }
}

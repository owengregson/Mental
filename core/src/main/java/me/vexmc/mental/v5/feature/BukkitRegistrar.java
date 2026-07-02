package me.vexmc.mental.v5.feature;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import java.util.function.Supplier;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.v5.coexist.OcmBinding;
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
 *   <li>{@link #rule} — the token-gated effectful-rule registration (B14): a
 *       feature cannot register a rule without naming the {@link MechanicToken}
 *       it effects, so the arbiter can never be forgotten on one of a feature's
 *       paths. The {@link OcmBinding} the gate resolves through is held here.</li>
 * </ul>
 */
public final class BukkitRegistrar implements Registrar {

    private final Plugin plugin;
    private final OcmBinding ocmBinding;

    public BukkitRegistrar(Plugin plugin, OcmBinding ocmBinding) {
        this.plugin = plugin;
        this.ocmBinding = ocmBinding;
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

    /**
     * Registers a token-gated effectful rule. The {@link MechanicToken} binds
     * the rule to the arbiter (B14: a feature without a token cannot reach this
     * method), and the live {@link OcmBinding} is the gate a historical-six rule
     * consults per run. 4A1 registers no rules — the rule features land in the
     * cadence/loadout sub-phases (4C/4D), which complete the per-run gate and the
     * paired teardown; the seam and its token contract exist now so the reconciler
     * and arbiter accounting are structurally complete.
     */
    @Override
    public AutoCloseable rule(MechanicToken token, Runnable handlerRegistration) {
        if (token == null) {
            throw new IllegalArgumentException("a rule must name the MechanicToken it effects (B14)");
        }
        handlerRegistration.run();
        // No rule feature exists yet; the registration performs its own effect and
        // the scope-level teardown of any listeners/tasks it acquired flows through
        // the sibling bukkit()/packets()/task() calls the rule feature makes.
        return () -> {};
    }

    /** The arbiter binding a rule handler resolves ownership through. */
    public OcmBinding ocmBinding() {
        return ocmBinding;
    }
}

package me.vexmc.mental.v5.feature;

import java.util.function.Supplier;
import me.vexmc.mental.kernel.coexist.MechanicToken;

/**
 * The seam a {@link Scope} registers through. The production implementation
 * (Phase 4) wires Bukkit event registration, PacketEvents listeners, and
 * {@code Scheduling} tasks; unit tests stub it. Every method returns an
 * {@link AutoCloseable} that exactly undoes the registration it performed, so
 * a scope can tear itself down by closing what it collected.
 *
 * <p>Rule registration is TOKEN-GATED at the framework level (B14): a rule
 * handler cannot be registered without naming the {@link MechanicToken} it
 * effects, so the arbiter's ownership gate can never be forgotten on one of a
 * feature's paths.</p>
 */
public interface Registrar {

    /** Register a Bukkit listener; the closeable unregisters it. */
    AutoCloseable bukkit(Object listener);

    /** Register a PacketEvents listener; the closeable removes it. */
    AutoCloseable packets(Object peListener);

    /** Start a task from {@code starter}; the closeable cancels it. */
    AutoCloseable task(Supplier<AutoCloseable> starter);

    /** Register a token-gated effectful rule handler; the closeable unregisters it. */
    AutoCloseable rule(MechanicToken token, Runnable handlerRegistration);
}

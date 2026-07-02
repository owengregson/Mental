package me.vexmc.mental.v5.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import me.vexmc.mental.kernel.coexist.MechanicToken;

/**
 * Every resource a feature acquires, closed as a unit on any exit (B8/R6).
 * A feature's {@code assemble} registers through the scope; enabling that
 * throws part-way closes whatever was already acquired, and a reload disable
 * closes the scope whole.
 *
 * <p>Each {@code listen}/{@code packets}/{@code task}/{@code rule} call routes
 * to the {@link Registrar} seam and records the returned {@link AutoCloseable}.
 * If acquisition throws, the throw propagates and nothing is recorded for that
 * call — the earlier registrations remain held, so the caller (the reconciler)
 * still closes them. {@link #close()} closes in reverse registration order; a
 * throw in one close never skips the rest, and all such failures are collected
 * as suppressed onto one summary exception thrown at the end. Double-close is
 * a no-op — the scope forgets its registrations once closed.</p>
 */
public final class Scope implements AutoCloseable {

    private final Registrar registrar;
    private final List<AutoCloseable> registrations = new ArrayList<>();
    private boolean closed;

    public Scope(Registrar registrar) {
        this.registrar = registrar;
    }

    public void listen(Object bukkitListener) {
        registrations.add(registrar.bukkit(bukkitListener));
    }

    public void packets(Object peListener) {
        registrations.add(registrar.packets(peListener));
    }

    public void task(Supplier<AutoCloseable> starter) {
        registrations.add(registrar.task(starter));
    }

    public void rule(MechanicToken token, Runnable handlerRegistration) {
        registrations.add(registrar.rule(token, handlerRegistration));
    }

    /**
     * Closes every registration in reverse order, once. Failures are collected
     * and rethrown as one summary with the individual throwables suppressed;
     * a second close does nothing.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<Throwable> failures = new ArrayList<>();
        for (int i = registrations.size() - 1; i >= 0; i--) {
            try {
                registrations.get(i).close();
            } catch (Exception failure) {
                failures.add(failure);
            }
        }
        registrations.clear();
        if (!failures.isEmpty()) {
            RuntimeException summary = new RuntimeException(
                    "scope close encountered " + failures.size() + " failure(s)");
            failures.forEach(summary::addSuppressed);
            throw summary;
        }
    }
}

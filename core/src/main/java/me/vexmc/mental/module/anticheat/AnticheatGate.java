package me.vexmc.mental.module.anticheat;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The live anticheat policy, decoupled from module construction order.
 *
 * <p>The anticheat module writes this; the hit-registration fast path reads
 * it on every packet. Two independent behaviors are governed: the velocity
 * pre-send (the one packet a movement-prediction anticheat could
 * mispredict) and Mental's own rewound-reach validation (redundant — and
 * potentially disagreeing — while a dedicated anticheat owns reach). Until
 * the module evaluates, the gate is permissive — evaluation happens during
 * enable, before any player can land a hit.</p>
 */
public final class AnticheatGate {

    private volatile boolean allowVelocityPreSend = true;
    private volatile boolean allowReachValidation = true;
    private volatile List<String> detected = List.of();

    public boolean allowVelocityPreSend() {
        return allowVelocityPreSend;
    }

    public boolean allowReachValidation() {
        return allowReachValidation;
    }

    public @NotNull List<String> detected() {
        return detected;
    }

    void update(boolean allowVelocityPreSend, boolean allowReachValidation, @NotNull List<String> detected) {
        this.allowVelocityPreSend = allowVelocityPreSend;
        this.allowReachValidation = allowReachValidation;
        this.detected = List.copyOf(detected);
    }

    public @NotNull String describe() {
        return (allowVelocityPreSend ? "velocity pre-send permitted" : "velocity pre-send suppressed")
                + (allowReachValidation ? "" : ", reach validation deferred")
                + (detected.isEmpty() ? ", no anticheat detected" : ", detected: " + String.join(", ", detected));
    }
}

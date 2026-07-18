package me.vexmc.mental.v5.coexist;

import java.util.List;

/**
 * The live anticheat coexistence policy (the retired {@code AnticheatGate}),
 * decoupled from feature construction order. The {@code AnticheatCompatUnit}
 * writes it; the hit-registration fast path reads it on every attack packet.
 *
 * <p>Two independent netty-thread behaviours are governed: the velocity
 * pre-send (the one packet a movement-prediction anticheat could mispredict)
 * and Mental's own rewound-reach validation (redundant, and potentially
 * disagreeing, while a dedicated anticheat owns reach). Until the unit
 * evaluates, the policy is permissive — evaluation happens during enable,
 * before any player can land a hit. All fields are volatile: the netty thread
 * reads what the owning-thread evaluation last wrote.</p>
 */
public final class AnticheatPolicy {

    private volatile boolean allowVelocityPreSend = true;
    private volatile boolean allowReachValidation = true;
    private volatile List<String> detected = List.of();

    public boolean allowVelocityPreSend() {
        return allowVelocityPreSend;
    }

    public boolean allowReachValidation() {
        return allowReachValidation;
    }

    public List<String> detected() {
        return detected;
    }

    public void update(boolean allowVelocityPreSend, boolean allowReachValidation, List<String> detected) {
        this.allowVelocityPreSend = allowVelocityPreSend;
        this.allowReachValidation = allowReachValidation;
        this.detected = List.copyOf(detected);
    }

    public String describe() {
        return (allowVelocityPreSend ? "velocity pre-send permitted" : "velocity pre-send suppressed")
                + (allowReachValidation ? "" : ", reach validation deferred")
                + (detected.isEmpty() ? ", no anticheat detected" : ", detected: " + String.join(", ", detected));
    }
}

package me.vexmc.mental.module.ocm;

import java.lang.invoke.MethodHandle;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The live OldCombatMechanics coordination state, decoupled from module
 * construction order — the OCM compat module writes it, combat modules read
 * it at event time.
 *
 * <p>Two resolution modes. With OCM's service API bound, ownership is decided
 * <em>per player</em>: the decider's modeset (plus per-player overrides)
 * answers exactly the question OCM's own modules ask, so mixed-mode servers
 * — one arena on OCM's 1.8, another on Mental's 1.7.10 — coordinate
 * hit-by-hit. Without the API (older OCM builds), the static verdicts parsed
 * from OCM's config decide globally and conservatively: if OCM <em>could</em>
 * be handling a mechanic anywhere, Mental yields it everywhere, because a
 * doubled knockback is worse than a deferred one.</p>
 *
 * <p>{@link #handles} is called from owning threads (Paper: main thread —
 * OCM's API contract; Folia never binds because OCM cannot load there) and
 * from per-player snapshot tasks. The bound handle invocation is a map read
 * inside OCM; cost is nanoseconds.</p>
 */
public final class OcmGate {

    /** How ownership questions are being answered right now. */
    public enum Mode {
        /** OCM absent or coordination disabled — Mental owns everything. */
        ABSENT,
        /** Service API bound — per-player modeset precision. */
        BOUND,
        /** OCM present without the API — global verdicts from its config file. */
        CONFIG
    }

    private record State(
            @NotNull Mode mode,
            @Nullable MethodHandle isModuleEnabledForPlayer,
            @NotNull Set<OcmMechanic> knownToOcm,
            @NotNull Set<OcmMechanic> staticVerdicts) {

        static final State ABSENT = new State(
                Mode.ABSENT, null, EnumSet.noneOf(OcmMechanic.class), EnumSet.noneOf(OcmMechanic.class));
    }

    private volatile State state = State.ABSENT;

    public @NotNull Mode mode() {
        return state.mode();
    }

    /**
     * Whether OldCombatMechanics handles {@code mechanic} for an interaction
     * decided by {@code decider}. A null decider (non-player parties, e.g. a
     * mob victim of a thrown projectile) falls back to the static verdict —
     * the conservative global answer.
     */
    public boolean handles(@NotNull OcmMechanic mechanic, @Nullable Player decider) {
        State current = state;
        if (current.mode() == Mode.ABSENT) {
            return false;
        }
        MethodHandle handle = current.isModuleEnabledForPlayer();
        if (current.mode() == Mode.BOUND && handle != null && decider != null) {
            if (!current.knownToOcm().contains(mechanic)) {
                return false; // this OCM build has no such module
            }
            try {
                return (boolean) handle.invoke(decider, mechanic.ocmName());
            } catch (Throwable failure) {
                // Never let a coordination read break a hit; fall back to config.
                return current.staticVerdicts().contains(mechanic);
            }
        }
        return current.staticVerdicts().contains(mechanic);
    }

    void bind(
            @NotNull MethodHandle isModuleEnabledForPlayer,
            @NotNull Set<OcmMechanic> knownToOcm,
            @NotNull Set<OcmMechanic> staticVerdicts) {
        state = new State(Mode.BOUND, isModuleEnabledForPlayer,
                copy(knownToOcm), copy(staticVerdicts));
    }

    void configOnly(@NotNull Set<OcmMechanic> staticVerdicts) {
        state = new State(Mode.CONFIG, null, EnumSet.noneOf(OcmMechanic.class), copy(staticVerdicts));
    }

    void clear() {
        state = State.ABSENT;
    }

    /** The mechanics OCM may own under the current state (for logs and status). */
    public @NotNull Set<OcmMechanic> coordinated() {
        State current = state;
        return switch (current.mode()) {
            case ABSENT -> Set.of();
            case BOUND -> Set.copyOf(current.knownToOcm());
            case CONFIG -> Set.copyOf(current.staticVerdicts());
        };
    }

    public @NotNull String describe() {
        State current = state;
        return switch (current.mode()) {
            case ABSENT -> "no OldCombatMechanics coordination";
            case BOUND -> "OldCombatMechanics service bound — modesets decide per player: "
                    + names(current.knownToOcm());
            case CONFIG -> "OldCombatMechanics config (no service API) — yielding globally: "
                    + names(current.staticVerdicts());
        };
    }

    private static @NotNull Set<OcmMechanic> copy(@NotNull Set<OcmMechanic> source) {
        return source.isEmpty() ? EnumSet.noneOf(OcmMechanic.class) : EnumSet.copyOf(source);
    }

    private static @NotNull String names(@NotNull Set<OcmMechanic> mechanics) {
        if (mechanics.isEmpty()) {
            return "none";
        }
        Map<OcmMechanic, String> ordered = new EnumMap<>(OcmMechanic.class);
        for (OcmMechanic mechanic : mechanics) {
            ordered.put(mechanic, mechanic.ocmName());
        }
        return String.join(", ", ordered.values());
    }
}

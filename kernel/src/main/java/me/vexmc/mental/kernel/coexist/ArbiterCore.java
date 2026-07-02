package me.vexmc.mental.kernel.coexist;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Pure ownership resolution — the decision half of the retired {@code OcmGate},
 * lifted into the kernel so netty-realm code can consult it over frozen views.
 * It answers exactly one question: does MENTAL own {@code token} for the party
 * identified by {@code decider}?
 *
 * <p>The three modes mirror the old gate. ABSENT: OCM is gone, Mental owns
 * everything. BOUND: OCM's service API is available, so per-player modesets
 * decide the arbitrated six that this build ships ({@code knownToOcm}) — but
 * only with a real player {@code decider}; a null decider (non-player parties)
 * and any resolver failure fall back to the conservative global verdict.
 * CONFIG: no API, so the global verdicts parsed from OCM's config decide, and
 * "OCM could be handling it anywhere" means Mental yields everywhere (a doubled
 * knockback is worse than a deferred one).</p>
 *
 * <p>Non-arbitrated tokens are Mental-owned in every mode — the ported rules
 * are OCM-agnostic; they are peer-detected by {@link CoexistWarnings}, never
 * yielded. The instance is immutable: {@code OcmBinding} (Task 3.6) constructs
 * a fresh {@code ArbiterCore} on every state change and swaps the reference.</p>
 */
public final class ArbiterCore {

    /** How ownership questions are answered right now. */
    public enum Mode { ABSENT, BOUND, CONFIG }

    /** Per-player BOUND answers — a seam over OCM's {@code isModuleEnabledForPlayer}. */
    public interface BoundResolver {
        boolean ocmEnabledFor(UUID decider, String ocmKey);
    }

    private final Mode mode;
    private final Set<MechanicToken> knownToOcm;
    private final Set<MechanicToken> staticVerdicts;
    private final BoundResolver resolver;

    public ArbiterCore(
            Mode mode,
            Set<MechanicToken> knownToOcm,
            Set<MechanicToken> staticVerdicts,
            BoundResolver resolver) {
        this.mode = mode;
        this.knownToOcm = copy(knownToOcm);
        this.staticVerdicts = copy(staticVerdicts);
        this.resolver = resolver;
    }

    /** True when MENTAL owns {@code token} for {@code decider} (null decider ⇒ static). */
    public boolean mentalOwns(MechanicToken token, UUID decider) {
        // Ported rules are Mental's on every version — peer-detected, never yielded.
        if (!token.arbitrated()) {
            return true;
        }
        return switch (mode) {
            case ABSENT -> true;
            case BOUND -> mentalOwnsBound(token, decider);
            case CONFIG -> !staticVerdicts.contains(token);
        };
    }

    private boolean mentalOwnsBound(MechanicToken token, UUID decider) {
        if (resolver != null && decider != null) {
            if (!knownToOcm.contains(token)) {
                return true; // this OCM build ships no such module
            }
            try {
                return !resolver.ocmEnabledFor(decider, token.ocmKey());
            } catch (RuntimeException coordinationFailure) {
                // Never let a coordination read break a hit; fall back to config.
                return !staticVerdicts.contains(token);
            }
        }
        // No player decider (or no bound handle): the conservative global verdict.
        return !staticVerdicts.contains(token);
    }

    private static Set<MechanicToken> copy(Set<MechanicToken> source) {
        return source == null || source.isEmpty()
                ? EnumSet.noneOf(MechanicToken.class)
                : EnumSet.copyOf(source);
    }
}

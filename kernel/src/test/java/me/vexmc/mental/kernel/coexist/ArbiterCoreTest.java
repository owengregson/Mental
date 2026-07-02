package me.vexmc.mental.kernel.coexist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The pure ownership decisions ported from {@code OcmGate.handles}, inverted to
 * "does MENTAL own it?". ABSENT ⇒ Mental owns everything; non-arbitrated tokens
 * are always Mental's (they only warn); BOUND consults the resolver for the
 * arbitrated six that this OCM build actually ships (knownToOcm) with a real
 * decider, and falls back to the conservative global verdict otherwise; CONFIG
 * yields wherever a static verdict says OCM could be handling it.
 */
class ArbiterCoreTest {

    private static final UUID DECIDER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static ArbiterCore absent() {
        return new ArbiterCore(ArbiterCore.Mode.ABSENT,
                EnumSet.noneOf(MechanicToken.class), EnumSet.noneOf(MechanicToken.class), null);
    }

    @Test
    void absentModeMeansMentalOwnsEverything() {
        ArbiterCore arbiter = absent();
        for (MechanicToken token : MechanicToken.values()) {
            assertTrue(arbiter.mentalOwns(token, DECIDER), () -> "ABSENT must own " + token);
            assertTrue(arbiter.mentalOwns(token, null), () -> "ABSENT must own " + token + " (static)");
        }
    }

    @Test
    void nonArbitratedTokensAreAlwaysMentalOwnedRegardlessOfMode() {
        // Even if a static verdict or a resolver claims OCM handles it, a
        // non-arbitrated token is Mental's — it is peer-detected, never yielded.
        ArbiterCore config = new ArbiterCore(ArbiterCore.Mode.CONFIG,
                EnumSet.noneOf(MechanicToken.class),
                EnumSet.of(MechanicToken.REGEN, MechanicToken.SWEEP), null);
        assertTrue(config.mentalOwns(MechanicToken.REGEN, DECIDER));
        assertTrue(config.mentalOwns(MechanicToken.SWEEP, null));

        ArbiterCore bound = new ArbiterCore(ArbiterCore.Mode.BOUND,
                EnumSet.allOf(MechanicToken.class),
                EnumSet.allOf(MechanicToken.class),
                (decider, key) -> true);
        assertTrue(bound.mentalOwns(MechanicToken.ARROW_KNOCKBACK, DECIDER));
        assertTrue(bound.mentalOwns(MechanicToken.OFFHAND, DECIDER));
    }

    @Test
    void boundConsultsResolverForKnownArbitratedTokens() {
        ArbiterCore bound = new ArbiterCore(ArbiterCore.Mode.BOUND,
                EnumSet.of(MechanicToken.MELEE_KNOCKBACK),
                EnumSet.noneOf(MechanicToken.class),
                (decider, key) -> MechanicToken.MELEE_KNOCKBACK.ocmKey().equals(key));
        // Resolver says OCM owns it → Mental does not.
        assertFalse(bound.mentalOwns(MechanicToken.MELEE_KNOCKBACK, DECIDER));
    }

    @Test
    void boundResolverDenyingMeansMentalOwns() {
        ArbiterCore bound = new ArbiterCore(ArbiterCore.Mode.BOUND,
                EnumSet.of(MechanicToken.MELEE_KNOCKBACK),
                EnumSet.noneOf(MechanicToken.class),
                (decider, key) -> false);
        assertTrue(bound.mentalOwns(MechanicToken.MELEE_KNOCKBACK, DECIDER));
    }

    @Test
    void boundTokenUnknownToThisOcmBuildIsMentalOwned() {
        // knownToOcm excludes CRITICAL_HITS → this OCM build has no such module.
        ArbiterCore bound = new ArbiterCore(ArbiterCore.Mode.BOUND,
                EnumSet.of(MechanicToken.MELEE_KNOCKBACK),
                EnumSet.of(MechanicToken.CRITICAL_HITS),
                (decider, key) -> true);
        assertTrue(bound.mentalOwns(MechanicToken.CRITICAL_HITS, DECIDER),
                "a module OCM does not ship cannot be owned by OCM");
    }

    @Test
    void boundWithNullDeciderFallsBackToStaticVerdict() {
        ArbiterCore bound = new ArbiterCore(ArbiterCore.Mode.BOUND,
                EnumSet.of(MechanicToken.PROJECTILE_KNOCKBACK),
                EnumSet.of(MechanicToken.PROJECTILE_KNOCKBACK),
                (decider, key) -> false);
        // No player decider → the conservative global verdict decides: present ⇒ OCM owns.
        assertFalse(bound.mentalOwns(MechanicToken.PROJECTILE_KNOCKBACK, null));

        ArbiterCore clear = new ArbiterCore(ArbiterCore.Mode.BOUND,
                EnumSet.of(MechanicToken.PROJECTILE_KNOCKBACK),
                EnumSet.noneOf(MechanicToken.class),
                (decider, key) -> false);
        assertTrue(clear.mentalOwns(MechanicToken.PROJECTILE_KNOCKBACK, null));
    }

    @Test
    void boundResolverThrowFallsBackToStaticVerdict() {
        BoundThrows throwing = new BoundThrows();
        ArbiterCore withVerdict = new ArbiterCore(ArbiterCore.Mode.BOUND,
                EnumSet.of(MechanicToken.TOOL_DAMAGE),
                EnumSet.of(MechanicToken.TOOL_DAMAGE),
                throwing);
        // Resolver blows up → the static verdict (OCM owns) is used: Mental does not own.
        assertFalse(withVerdict.mentalOwns(MechanicToken.TOOL_DAMAGE, DECIDER));

        ArbiterCore withoutVerdict = new ArbiterCore(ArbiterCore.Mode.BOUND,
                EnumSet.of(MechanicToken.TOOL_DAMAGE),
                EnumSet.noneOf(MechanicToken.class),
                throwing);
        assertTrue(withoutVerdict.mentalOwns(MechanicToken.TOOL_DAMAGE, DECIDER));
    }

    @Test
    void configModeUsesStaticVerdictsConservatively() {
        ArbiterCore config = new ArbiterCore(ArbiterCore.Mode.CONFIG,
                EnumSet.noneOf(MechanicToken.class),
                EnumSet.of(MechanicToken.FISHING_KNOCKBACK),
                null);
        // Verdict present ⇒ OCM owns ⇒ Mental does not (with or without a decider).
        assertFalse(config.mentalOwns(MechanicToken.FISHING_KNOCKBACK, DECIDER));
        assertFalse(config.mentalOwns(MechanicToken.FISHING_KNOCKBACK, null));
        // Verdict absent ⇒ Mental owns.
        assertTrue(config.mentalOwns(MechanicToken.FISHING_ROD_VELOCITY, DECIDER));
    }

    /** A resolver that always throws — proves the never-break-a-hit fallback. */
    private static final class BoundThrows implements ArbiterCore.BoundResolver {
        @Override
        public boolean ocmEnabledFor(UUID decider, String ocmKey) {
            throw new IllegalStateException("coordination read failed");
        }
    }

    @Test
    void staticVerdictSetsAreDefensivelyCopied() {
        EnumSet<MechanicToken> verdicts = EnumSet.of(MechanicToken.MELEE_KNOCKBACK);
        ArbiterCore config = new ArbiterCore(ArbiterCore.Mode.CONFIG,
                EnumSet.noneOf(MechanicToken.class), verdicts, null);
        verdicts.clear();
        assertFalse(config.mentalOwns(MechanicToken.MELEE_KNOCKBACK, null),
                "the arbiter must snapshot its verdicts, not alias the caller's set");
    }

    @Test
    void knownSetHelperCompiles() {
        // Documents the intended construction shape used by OcmBinding (Task 3.6).
        Set<MechanicToken> arbitrated = EnumSet.noneOf(MechanicToken.class);
        for (MechanicToken token : MechanicToken.values()) {
            if (token.arbitrated()) {
                arbitrated.add(token);
            }
        }
        assertTrue(arbitrated.contains(MechanicToken.MELEE_KNOCKBACK));
        assertFalse(arbitrated.contains(MechanicToken.SWEEP));
    }
}

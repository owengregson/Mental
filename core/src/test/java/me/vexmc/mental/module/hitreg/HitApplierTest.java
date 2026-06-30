package me.vexmc.mental.module.hitreg;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the region-safety guard around the owning-thread player damage apply.
 *
 * <p>{@code applyPlayer} runs on the VICTIM's owning region thread (the region
 * that owns the deferred damage event) and reads the ATTACKER —
 * {@code getGameMode}, the attribute/enchant reads inside
 * {@code DamageCalculator}, and the knockback-direction read inside
 * {@code damage(amount, attacker)}. On Folia the attacker may have left the
 * victim's region between the netty snapshot and this deferred task (a
 * region-boundary straddle, or a pearl/teleport in the dispatch tick); those
 * reads then throw {@code ensureTickThread} off the owning region. A
 * cross-region attacker is no longer within reach, so the era-correct outcome
 * is to drop the hit — degrade to a logged skip, not an uncaught throw the
 * scheduler surfaces as an error. On Paper there are no regions, so a throw is
 * a genuine bug and must propagate unmasked (byte-identical to the pre-guard
 * code).</p>
 */
class HitApplierTest {

    @Test
    void foliaSwallowsACrossRegionApplyFailure() {
        AtomicReference<IllegalStateException> swallowed = new AtomicReference<>();
        IllegalStateException offRegion = new IllegalStateException(
                "Accessing entity state off owning region's thread");
        HitApplier.applyGuarded(true, () -> {
            throw offRegion;
        }, swallowed::set);
        assertSame(offRegion, swallowed.get(),
                "on Folia a cross-region apply throw degrades to a logged skip");
    }

    @Test
    void paperPropagatesAnApplyFailureUnmasked() {
        IllegalStateException bug = new IllegalStateException("a genuine Paper bug");
        assertThrows(IllegalStateException.class, () -> HitApplier.applyGuarded(
                false,
                () -> {
                    throw bug;
                },
                e -> fail("Paper has no regions; an apply throw must propagate, never be swallowed")));
    }

    @Test
    void foliaStillSurfacesAGenuineBug() {
        // The catch is scoped to the off-region IllegalStateException; a real
        // logic bug (here an NPE) must NOT be swallowed even on Folia — the one
        // platform with no combat test coverage.
        assertThrows(NullPointerException.class, () -> HitApplier.applyGuarded(
                true,
                () -> {
                    throw new NullPointerException("a genuine bug, not an off-region read");
                },
                e -> fail("a non-off-region throw must surface, not be masked as a region skip")));
    }

    @Test
    void aSuccessfulApplyNeverInvokesTheSkipHandler() {
        boolean[] ran = {false};
        HitApplier.applyGuarded(true, () -> ran[0] = true, e -> fail("there was no failure to swallow"));
        assertTrue(ran[0], "the body runs to completion when it does not throw");
    }
}

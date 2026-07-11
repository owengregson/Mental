package me.vexmc.mental.v5.feature.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.kernel.delivery.HitTransaction;
import org.junit.jupiter.api.Test;

/**
 * The F1 boundary-adoption decision at the {@link HitRegistrationUnit} region-apply
 * seam (docs/superpowers/plans/2026-07-10-mental-255beta-feedback-coherence.md). When
 * the netty fast path has ALREADY committed a knock to the victim's client (a PRE_SENT
 * wire burst or a PINNED velocity-event vector) and the LIVE victim would window-reject
 * the region hit in the ≤1-tick race sliver, the apply clamps the immunity to the
 * boundary so the hit lands as the boundary-fresh hit the pre-send promised — realigning
 * knock ⇔ damage ⇔ EDBEE ⇔ cosmetics into one truth. The decision is pure, so the era
 * contract is pinned here with hand-computed cases (the matrix cannot reach it: fakes
 * swing via the NMS attack path with no packets, so no committed pre-send exists there).
 */
class BoundaryAdoptionTest {

    /* ---------------- committedKnock: which transaction states count ---------------- */

    @Test
    void onlyPreSentAndPinnedAreCommittedKnocks() {
        // PRE_SENT (wire burst rode the client) and PINNED (era vector pinned to ship
        // on the genuine velocity event — connectionless victim OR wire-failed burst,
        // which pinnedWireFailed downgrades INTO PINNED) both promised the victim a
        // knock at plan time (submitFromWire). Nothing else has committed a knock.
        assertTrue(HitRegistrationUnit.committedKnock(HitTransaction.State.PRE_SENT));
        assertTrue(HitRegistrationUnit.committedKnock(HitTransaction.State.PINNED));

        assertFalse(HitRegistrationUnit.committedKnock(HitTransaction.State.REGISTERED),
                "hurt-only / suppressed / paced-out / no-view: no knock shipped, era silence stays");
        assertFalse(HitRegistrationUnit.committedKnock(HitTransaction.State.PLANNED),
                "PLANNED is transient pre-commit — no knock has shipped yet");
        assertFalse(HitRegistrationUnit.committedKnock(HitTransaction.State.ADOPTED));
        assertFalse(HitRegistrationUnit.committedKnock(HitTransaction.State.SUPPRESSED));
        assertFalse(HitRegistrationUnit.committedKnock(HitTransaction.State.RETRACTED));
        assertFalse(HitRegistrationUnit.committedKnock(HitTransaction.State.DROPPED));
        assertFalse(HitRegistrationUnit.committedKnock(HitTransaction.State.ENSURED));
        assertFalse(HitRegistrationUnit.committedKnock(HitTransaction.State.RECORDED));
    }

    @Test
    void committedKnockCoversEveryStateExactlyOnce() {
        // Guard against a new State constant silently defaulting to "committed": the
        // committed set is exactly {PRE_SENT, PINNED} and nothing else, forever.
        long committed = 0;
        for (HitTransaction.State state : HitTransaction.State.values()) {
            if (HitRegistrationUnit.committedKnock(state)) {
                committed++;
            }
        }
        assertEquals(2, committed, "exactly PRE_SENT and PINNED are committed knocks");
    }

    /* ---------------------------- adoptBoundary: the decision ---------------------------- */

    @Test
    void committedInsideWindowWithEqualAmountAdopts() {
        // Standard max=20 → boundary max/2=10. noDamageTicks 15 > 10 (inside the
        // window), amount 6.0 <= lastDamage 6.0 (a same-strength re-hit vanilla would
        // reject) → adopt: land the boundary-fresh hit the pre-send committed.
        assertTrue(HitRegistrationUnit.adoptBoundary(true, 15, 20, 6.0, 6.0));
    }

    @Test
    void committedInsideWindowWithLesserAmountAdopts() {
        // amount 4.0 <= lastDamage 6.0 (a weaker re-hit vanilla would reject) → adopt.
        assertTrue(HitRegistrationUnit.adoptBoundary(true, 15, 20, 4.0, 6.0));
    }

    @Test
    void committedUpgradeNeverAdopts() {
        // amount 8.0 > lastDamage 6.0: vanilla ACCEPTS this as a delta hit and
        // subtracts only 8.0 − 6.0 = 2.0. Clamping the immunity here would make
        // victim.damage(8.0) deal the FULL 8.0 — over-damaging by the difference.
        // The upgrade branch must NEVER clamp, even deep inside the window.
        assertFalse(HitRegistrationUnit.adoptBoundary(true, 19, 20, 8.0, 6.0));
    }

    @Test
    void uncommittedNeverAdopts() {
        // No knock was shipped (REGISTERED at region time). An era window-rejection
        // here is era-correct silence and must stay silent — regardless of window or
        // amount, including a would-be same-strength re-hit that otherwise qualifies.
        assertFalse(HitRegistrationUnit.adoptBoundary(false, 15, 20, 4.0, 6.0));
        assertFalse(HitRegistrationUnit.adoptBoundary(false, 15, 20, 6.0, 6.0));
        assertFalse(HitRegistrationUnit.adoptBoundary(false, 19, 20, 8.0, 6.0));
    }

    @Test
    void exactBoundaryDoesNotAdopt() {
        // noDamageTicks == max/2 (10 for max=20): vanilla's gate is strict `>`, so it
        // ACCEPTS at exactly the boundary — nothing to adopt, no clamp.
        assertFalse(HitRegistrationUnit.adoptBoundary(true, 10, 20, 6.0, 6.0));
    }

    @Test
    void outsideWindowDoesNotAdopt() {
        // Below the boundary (5 <= 10) and freshly clear (0): vanilla accepts, so the
        // region hit lands on its own; no adoption needed.
        assertFalse(HitRegistrationUnit.adoptBoundary(true, 5, 20, 6.0, 6.0));
        assertFalse(HitRegistrationUnit.adoptBoundary(true, 0, 20, 6.0, 6.0));
    }

    @Test
    void oddMaxUsesIntegerTruncatedBoundary() {
        // max=21 → integer max/2 = 10 (truncated, NOT rounded to 11). So the first
        // in-window tick is 11 (11 > 10), and 10 is exactly at/below the boundary.
        // A rounded boundary of 11 would flip both of these — this pins truncation.
        assertTrue(HitRegistrationUnit.adoptBoundary(true, 11, 21, 6.0, 6.0));
        assertFalse(HitRegistrationUnit.adoptBoundary(true, 10, 21, 6.0, 6.0));
    }

    /* ---------------------------- boundaryAdopted: the F9 stamp ---------------------------- */

    @Test
    void boundaryAdoptedAppendsToPriorDisposition() {
        // A committed hit always carries a prior disposition; appending discriminates
        // the adopted hit in the journal Capture / JOURNAL debug channel.
        assertEquals("wire+boundary-adopted", HitRegistrationUnit.boundaryAdopted("wire"));
        assertEquals("pinned+boundary-adopted", HitRegistrationUnit.boundaryAdopted("pinned"));
        assertEquals("unsendable-downgrade+boundary-adopted",
                HitRegistrationUnit.boundaryAdopted("unsendable-downgrade"));
    }

    @Test
    void boundaryAdoptedNullPriorIsBareStamp() {
        // Defensive only (a committed hit never has a null presend) — never a
        // "null+boundary-adopted" string.
        assertEquals("boundary-adopted", HitRegistrationUnit.boundaryAdopted(null));
    }
}

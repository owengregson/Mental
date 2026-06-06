package me.vexmc.mental.module.hitreg;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.vexmc.mental.config.KnockbackProfile;
import org.junit.jupiter.api.Test;

/**
 * Pins the snapshot's invulnerability predicate at the combo boundary.
 *
 * <p>The snapshot's {@code noDamageTicks} froze at the start of the tick,
 * before that tick's decrement — so a legal perfect-cadence combo hit (the
 * spam norm: thrown the instant the hurt window halves) reads one tick high
 * here while vanilla's own judgment at processing reads exactly
 * {@code max/2}. Without the staleness allowance every boundary hit skips
 * its pre-send and falls to the authoritative next-tick send computed from
 * post-landing state — measured on the wire as the floaty-combo signature
 * (grounded 0.3608 verticals where the era ships the pre-landing ~0.25).</p>
 */
class PlayerStateCacheTest {

    private static PlayerStateCache.Snapshot snapshotWithNoDamageTicks(int noDamageTicks) {
        return new PlayerStateCache.Snapshot(
                0.0, 64.0, 0.0, 0.0f,
                0.0, -0.0784, 0.0,
                true, false, 0.0, 0,
                noDamageTicks, 20,
                false, 1, KnockbackProfile.LEGACY_17, 0, false, 3.0,
                0.08);
    }

    @Test
    void boundaryCadenceHitIsNotImmune() {
        // A hit thrown exactly when the window halves snapshots nd = 11 on a
        // 20-tick window; vanilla reads 10 at processing and applies full
        // knockback — the pre-send must not skip it.
        assertFalse(snapshotWithNoDamageTicks(11).isDamageImmune());
    }

    @Test
    void subWindowSpamStaysImmune() {
        // One tick faster than legal: vanilla rejects the knockback, so the
        // pre-send must keep skipping (a shipped phantom never corrects).
        assertTrue(snapshotWithNoDamageTicks(12).isDamageImmune());
    }

    @Test
    void freshHitIsImmune() {
        assertTrue(snapshotWithNoDamageTicks(20).isDamageImmune());
    }

    @Test
    void openWindowIsNotImmune() {
        assertFalse(snapshotWithNoDamageTicks(0).isDamageImmune());
    }
}

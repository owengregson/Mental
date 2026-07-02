package me.vexmc.mental.kernel.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-tick player view's two derived predicates: the +1 staleness
 * allowance of {@code damageImmune} (ported verbatim from
 * {@code PlayerStateCache.Snapshot.isDamageImmune}) and the ≤4-tick freshness
 * gate that degrades a stale view to no-exclusion / fast-path decline.
 */
class PlayerViewTest {

    private static final KinematicState KINEMATICS = new KinematicState(64.0, 0.0, true);

    private static PlayerView view(TickStamp at, int noDamageTicks, int maxNoDamageTicks) {
        return new PlayerView(
                UUID.randomUUID(), 1, at, Decay.Motion.ZERO, true, 0.6, 0.08,
                0.42, -1, false, false, true, noDamageTicks, maxNoDamageTicks,
                0.0, false, KnockbackProfile.LEGACY_17, 0, KINEMATICS);
    }

    @Test
    void damageImmuneHonoursThePlusOneBoundary() {
        // max=20, ndt=11: 11 > 20/2+1 (== 11) is false — the boundary-cadence
        // hit is admitted (vanilla reads 10 at processing and applies full).
        assertFalse(view(new TickStamp(0), 11, 20).damageImmune());
        // ndt=12 crosses the boundary — sub-window spam stays immune.
        assertTrue(view(new TickStamp(0), 12, 20).damageImmune());
        // A fresh full window is immune; an open window is not.
        assertTrue(view(new TickStamp(0), 20, 20).damageImmune());
        assertFalse(view(new TickStamp(0), 0, 20).damageImmune());
    }

    @Test
    void freshWithinFourTicksAndStaleBeyond() {
        PlayerView at100 = view(new TickStamp(100), 0, 20);
        assertTrue(at100.fresh(new TickStamp(100)), "distance 0 is fresh");
        assertTrue(at100.fresh(new TickStamp(104)), "distance 4 (inclusive) is fresh");
        assertFalse(at100.fresh(new TickStamp(105)), "distance 5 is stale");
        // An unknown stamp on either side is never fresh (NO_TICK degradation).
        assertFalse(view(TickStamp.NO_TICK, 0, 20).fresh(new TickStamp(100)));
        assertFalse(at100.fresh(TickStamp.NO_TICK));
    }
}

package me.vexmc.mental.v5.feature.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The CT8c reach-gate selection (Task INT wire 2a/2b): the per-weapon max reach the
 * fast path's rewound reach validation enforces when {@code ct8c-reach} is on, plus
 * the {@code +1.0} a ≥195% charged hit earns when {@code charged-attacks} is on too.
 *
 * <p>The per-weapon selection reads {@link me.vexmc.mental.v5.feature.loadout.Ct8cReachTable}
 * (spec §2.2: base 2.5 / sword 3.0 / hoe+trident 3.5). The netty gate cannot read the
 * attacker's held weapon off-thread (Folia), so it passes a null material and gets the
 * per-weapon CEILING (3.5) — the right bound for a lenient blatant-reach filter (never
 * over-reject a legit long-reach weapon; honest per-weapon precision is the client's
 * interaction-range attribute, set by {@code Ct8cReachUnit}).</p>
 */
class Ct8cReachGateTest {

    @Test
    void selectsThePerWeaponReachFromTheCt8cTable() {
        assertEquals(3.5, Ct8cReachGate.gateReach("DIAMOND_HOE", false), 1e-9, "hoe reaches 3.5");
        assertEquals(3.5, Ct8cReachGate.gateReach("TRIDENT", false), 1e-9, "trident reaches 3.5");
        assertEquals(3.0, Ct8cReachGate.gateReach("DIAMOND_SWORD", false), 1e-9, "sword reaches 3.0");
        assertEquals(2.5, Ct8cReachGate.gateReach("DIAMOND_PICKAXE", false), 1e-9, "pickaxe is the 2.5 base");
        assertEquals(2.5, Ct8cReachGate.gateReach("AIR", false), 1e-9, "the empty hand is the 2.5 base");
    }

    @Test
    void aChargedHitAddsExactlyOneBlockOfReach() {
        // Spec §2.1: a ≥195% charged hit earns +1.0 reach.
        assertEquals(4.5, Ct8cReachGate.gateReach("DIAMOND_HOE", true), 1e-9, "3.5 + 1.0");
        assertEquals(4.0, Ct8cReachGate.gateReach("DIAMOND_SWORD", true), 1e-9, "3.0 + 1.0");
        assertEquals(1.0, Ct8cReachGate.CHARGED_REACH_BONUS, 1e-9);
    }

    @Test
    void anUnknownWeaponFallsToThePerWeaponCeiling() {
        // The netty-thread reality: the held weapon is not readable off-region, so the
        // gate uses the table ceiling (the longest legit reach, 3.5) plus any charged bonus.
        assertEquals(3.5, Ct8cReachGate.weaponCeiling(), 1e-9, "the greatest CT8c reach is the 3.5 hoe/trident");
        assertEquals(3.5, Ct8cReachGate.gateReach(null, false), 1e-9);
        assertEquals(4.5, Ct8cReachGate.gateReach(null, true), 1e-9);
    }
}

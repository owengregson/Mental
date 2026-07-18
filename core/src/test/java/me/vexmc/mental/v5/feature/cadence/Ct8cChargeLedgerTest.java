package me.vexmc.mental.v5.feature.cadence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.vexmc.mental.v5.feature.cadence.Ct8cChargeLedger.Decision;
import org.junit.jupiter.api.Test;

/**
 * Pins the CT8c charge ledger against the kernel gates (spec §2.1). A sword's
 * effective ATTACK_SPEED is 4.5, so its full delay is {@code attackDelayTicks(4.5)
 * · 2 = 14} ticks — 100% at 7 ticks, 200% at 14.
 */
class Ct8cChargeLedgerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000c8");
    private static final double SWORD_SPEED = 4.5;

    @Test
    void fullDelayIsGetAttackDelayTimesTwo() {
        assertEquals(14.0, Ct8cChargeLedger.fullDelayTicks(4.5)); // sword: delay 7 → 14
        assertEquals(16.0, Ct8cChargeLedger.fullDelayTicks(4.0)); // fist:  delay 8 → 16
        assertEquals(20.0, Ct8cChargeLedger.fullDelayTicks(3.5)); // axe:   delay 10 → 20
    }

    @Test
    void aFreshPlayersFirstHitLandsFullyCharged() {
        Ct8cChargeLedger ledger = new Ct8cChargeLedger();
        Decision first = ledger.onAttack(PLAYER, 100L, SWORD_SPEED, true);
        assertTrue(first.allowed(), "a never-attacked player is fully charged");
        assertEquals(2.0, first.scale(), 1e-9);
    }

    @Test
    void anEarlyHitIsRejectedAndDoesNotRestartTheCharge() {
        Ct8cChargeLedger ledger = new Ct8cChargeLedger();
        ledger.onAttack(PLAYER, 100L, SWORD_SPEED, true); // lands, resets at 100

        // Same tick: 0% charge → rejected, and the reject must NOT reset the timer.
        assertFalse(ledger.onAttack(PLAYER, 100L, SWORD_SPEED, true).allowed());
        // 3 ticks in: still below 100% (3/7) → rejected. Because the reject at t=100
        // did not restart, the reset is still 100, so this reads 3 ticks of charge.
        Decision atThree = ledger.onAttack(PLAYER, 103L, SWORD_SPEED, true);
        assertFalse(atThree.allowed());
        assertEquals(6.0 / 14.0, atThree.scale(), 1e-9);
        // 7 ticks in: exactly 100% → lands.
        Decision atSeven = ledger.onAttack(PLAYER, 107L, SWORD_SPEED, true);
        assertTrue(atSeven.allowed());
        assertEquals(1.0, atSeven.scale(), 1e-9);
    }

    @Test
    void requireFullChargeOffLandsEveryHit() {
        Ct8cChargeLedger ledger = new Ct8cChargeLedger();
        ledger.onAttack(PLAYER, 100L, SWORD_SPEED, false);
        Decision immediate = ledger.onAttack(PLAYER, 100L, SWORD_SPEED, false);
        assertTrue(immediate.allowed(), "the gate is off — even a 0% hit lands");
        assertEquals(0.0, immediate.scale(), 1e-9);
    }

    @Test
    void missRecoveryOpensTheLenientFourTickLane() {
        Ct8cChargeLedger ledger = new Ct8cChargeLedger();
        ledger.onAirSwing(PLAYER, 100L); // arm recovery, reset at 100

        // 3 ticks: below 100% AND not past the 4-tick lane → rejected.
        assertFalse(ledger.onAttack(PLAYER, 103L, SWORD_SPEED, true).allowed());
        // 5 ticks (> 4): the recovery lane opens even though scale (5/7) < 1.
        Decision recovered = ledger.onAttack(PLAYER, 105L, SWORD_SPEED, true);
        assertTrue(recovered.allowed());
        assertEquals(10.0 / 14.0, recovered.scale(), 1e-9);

        // A landed hit consumes recovery: the next early hit has no lane and is rejected.
        assertFalse(ledger.onAttack(PLAYER, 106L, SWORD_SPEED, true).allowed());
    }

    @Test
    void scaleAtIsAReadOnlyProbe() {
        Ct8cChargeLedger ledger = new Ct8cChargeLedger();
        ledger.onAttack(PLAYER, 100L, SWORD_SPEED, true); // reset at 100
        // Probing does not reset — two probes at the same instant read the same value.
        assertEquals(1.0, ledger.scaleAt(PLAYER, 107L, SWORD_SPEED), 1e-9);
        assertEquals(1.0, ledger.scaleAt(PLAYER, 107L, SWORD_SPEED), 1e-9);
        // A charged (>195%) probe past the full delay.
        assertEquals(2.0, ledger.scaleAt(PLAYER, 200L, SWORD_SPEED), 1e-9);
    }

    @Test
    void forgetClearsState() {
        Ct8cChargeLedger ledger = new Ct8cChargeLedger();
        ledger.onAttack(PLAYER, 100L, SWORD_SPEED, true);
        ledger.forget(PLAYER);
        // Back to never-attacked: the next hit is fully charged.
        assertTrue(ledger.onAttack(PLAYER, 101L, SWORD_SPEED, true).allowed());
    }
}

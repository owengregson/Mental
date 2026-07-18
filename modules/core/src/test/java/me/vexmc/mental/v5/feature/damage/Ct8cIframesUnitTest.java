package me.vexmc.mental.v5.feature.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The CT8c melee i-frame mapping (spec §2.4): {@code invulnerableTime =
 * min(attackerAttackDelay, 10)}, the attack delay itself read from the CT8c
 * weapon attack-speed table. Projectiles are 0 (handled in the unit's event
 * routing, not this pure mapping).
 */
class Ct8cIframesUnitTest {

    @Test
    void meleeIframesAreTheAttackDelayCappedAtTen() {
        // The plan's pins: a sword (attr speed 4.5) → delay 7 → i-frames 7;
        // an axe/hoe at attr 3.5 → delay 10 → i-frames 10 (at the cap). Fist
        // 4.0 → 8; a fast hoe 5.0 → 6.
        assertEquals(7, Ct8cIframesUnit.iframeTicks(4.5), "sword: min(7, 10) = 7");
        assertEquals(10, Ct8cIframesUnit.iframeTicks(3.5), "axe/hoe: min(10, 10) = 10");
        assertEquals(8, Ct8cIframesUnit.iframeTicks(4.0), "fist/pickaxe: min(8, 10) = 8");
        assertEquals(6, Ct8cIframesUnit.iframeTicks(5.0), "fast hoe: min(6, 10) = 6");
    }

    @Test
    void aVerySlowWeaponIsClampedToTheTenTickCeiling() {
        // A weapon whose raw attack delay exceeds 10 (attr 2.0 → delay 40) still
        // caps at 10 — the min(..., 10) is the whole point of the cap.
        assertEquals(10, Ct8cIframesUnit.iframeTicks(2.0), "min(40, 10) = 10");
    }
}

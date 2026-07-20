package me.vexmc.mental.v5.feature.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The CT8c melee i-frame mapping (spec §2.4): the 8c logical window
 * {@code invulnerableTime = min(attackerAttackDelay, 10)}, the attack delay itself
 * read from the CT8c weapon attack-speed table, and the {@code maximumNoDamageTicks}
 * FIELD written for it ({@code 2 ×} that, so CraftBukkit's half-window gate
 * reproduces 8c's full-window difference-damage). Projectiles are 0 (handled in the
 * unit's event routing, not this pure mapping).
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

    @Test
    void theWrittenFieldIsTwiceTheLogicalWindowSoTheHalfGateBecomesTheFullWindow() {
        // 8c's hurt gate is invulnerableTime > 0 (the WHOLE window is difference-
        // damage); CraftBukkit's is noDamageTicks > maxNoDamageTicks/2 (the first
        // HALF). Writing 2× the 8c window makes the half-gate span the full window.
        assertEquals(14, Ct8cIframesUnit.windowField(7), "sword: 2 × 7 = 14 (half-gate ⇒ 7-tick full window)");
        assertEquals(20, Ct8cIframesUnit.windowField(10), "delay-10 weapon: 2 × 10 = 20 = the vanilla default (faithful)");
        assertEquals(0, Ct8cIframesUnit.windowField(0), "a projectile's 0 stays 0 — no i-frames");
    }
}

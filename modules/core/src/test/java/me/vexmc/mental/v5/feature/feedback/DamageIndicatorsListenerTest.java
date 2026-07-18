package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the overkill clamp (F3) — the pure per-event helper that decides the damage
 * an indicator actually shows: the health the victim genuinely lost, never the full
 * swing. A killing hit renders only its pre-hit red hearts; a normal hit passes
 * through unchanged; a victim already at (or below) zero — a same-tick death race —
 * shows nothing, which the listener reads as "skip the stand". The helper is pure, so
 * it is asserted directly here without a live server or a constructed listener.
 */
class DamageIndicatorsListenerTest {

    @Test
    void overkillClampsToPreHitHealth() {
        // A 10-damage swing against a victim with 6 health left renders the 6 lost,
        // not the full 10 — the missing 4 never touched a red heart.
        assertEquals(6.0, DamageIndicatorsListener.displayedDamage(10.0, 6.0), 1.0e-9);
    }

    @Test
    void normalHitPassesThrough() {
        // A non-killing hit against a healthy victim shows its full final damage.
        assertEquals(4.0, DamageIndicatorsListener.displayedDamage(4.0, 20.0), 1.0e-9);
    }

    @Test
    void exactlyLethalHitShowsThePool() {
        // The swing equals the remaining health: min leaves it unchanged (the clamp
        // does not bite at the boundary), so the last hearts are still rendered.
        assertEquals(6.0, DamageIndicatorsListener.displayedDamage(6.0, 6.0), 1.0e-9);
    }

    @Test
    void zeroHealthShowsNothing() {
        // A victim already at 0 (a same-tick death race) lost nothing visible.
        assertEquals(0.0, DamageIndicatorsListener.displayedDamage(5.0, 0.0), 1.0e-9);
    }

    @Test
    void negativeHealthShowsNothing() {
        // Defensive: a below-zero pre-hit read still clamps to a skipped stand.
        assertEquals(0.0, DamageIndicatorsListener.displayedDamage(5.0, -3.0), 1.0e-9);
    }
}

package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the re-entry flag the fishing module raises around its
 * {@code victim.damage(victim, rodder)} so the melee knockback module skips the
 * resulting ENTITY_ATTACK instead of mistaking it for a melee hit by the rodder.
 */
class MeleeReentryGuardTest {

    @Test
    void inactiveByDefault() {
        assertFalse(MeleeReentryGuard.active());
    }

    @Test
    void activeOnlyDuringTheDamageCall() {
        boolean[] seenInside = {false};
        MeleeReentryGuard.during(() -> seenInside[0] = MeleeReentryGuard.active());
        assertTrue(seenInside[0], "the flag is set while the damage runs");
        assertFalse(MeleeReentryGuard.active(), "and cleared on the way out");
    }

    @Test
    void restoresEvenWhenTheDamageThrows() {
        assertThrows(RuntimeException.class,
                () -> MeleeReentryGuard.during(() -> {
                    throw new RuntimeException("damage vetoed");
                }));
        assertFalse(MeleeReentryGuard.active(), "a throwing damage call must not latch the flag");
    }

    @Test
    void nestsWithoutClearingTheOuterFlag() {
        boolean[] inner = {false};
        boolean[] afterInner = {false};
        MeleeReentryGuard.during(() -> {
            MeleeReentryGuard.during(() -> inner[0] = MeleeReentryGuard.active());
            afterInner[0] = MeleeReentryGuard.active();
        });
        assertTrue(inner[0]);
        assertTrue(afterInner[0], "a nested during() restores the outer active state, not false");
        assertFalse(MeleeReentryGuard.active());
    }
}

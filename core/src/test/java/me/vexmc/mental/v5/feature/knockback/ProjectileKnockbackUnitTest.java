package me.vexmc.mental.v5.feature.knockback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The 1.21.2 projectile-knockback boundary (mandate §10 / MC-2110): below
 * 1.21.2 Mental substitutes the era thrown-projectile knock; on 1.21.2+ vanilla
 * restored projectile-KB-vs-players, so the substitution is a NO-OP — Mental
 * must not double vanilla's knock. Both sides of the flag are pinned here; the
 * flag's own version derivation is pinned in {@code PlatformProfileTest}.
 */
class ProjectileKnockbackUnitTest {

    @Test
    void substitutesBelow1212() {
        assertTrue(ProjectileKnockbackUnit.substitutesThrownKnockback(false),
                "below 1.21.2 vanilla drops projectile-KB-vs-players — the era substitution stands");
    }

    @Test
    void noOpsWhenVanillaRestoredProjectileKnockback() {
        assertFalse(ProjectileKnockbackUnit.substitutesThrownKnockback(true),
                "1.21.2+ restored vanilla projectile knockback — the substitution is a no-op");
    }
}

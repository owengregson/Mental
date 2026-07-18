package me.vexmc.mental.v5.feature.knockback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The 1.21.2 projectile boundary (mandate §10): below 1.21.2 the
 * negligible-damage substitution keeps the vanilla hurt pipeline alive for
 * zero-damage thrown hits; on 1.21.2+ — where vanilla restored projectile
 * knockback — the substitution is a NO-OP and the hit keeps its era-true zero
 * damage. The positional VECTOR substitution is deliberately ungated: it is the
 * era restoration itself (the live suite pins it on every version), and the
 * desk override replaces vanilla's knock rather than adding to it, so the
 * restored platforms cannot double-knock. Both sides of the flag are pinned
 * here; the flag's own version derivation is pinned in
 * {@code PlatformProfileTest}.
 */
class ProjectileKnockbackUnitTest {

    @Test
    void substitutesZeroDamageBelow1212() {
        assertTrue(ProjectileKnockbackUnit.substitutesZeroDamage(false),
                "below 1.21.2 the zero-damage hit is dropped by vanilla — the substitution keeps it alive");
    }

    @Test
    void damageSubstitutionNoOpsWhereVanillaRestoredProjectileKnockback() {
        assertFalse(ProjectileKnockbackUnit.substitutesZeroDamage(true),
                "1.21.2+ restored vanilla projectile knockback — the damage substitution is a no-op");
    }
}

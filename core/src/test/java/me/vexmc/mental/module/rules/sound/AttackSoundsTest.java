package me.vexmc.mental.module.rules.sound;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@link AttackSounds#isSuppressedAttackSound} contract.
 *
 * <p>The 1.9 attack-result sounds are the {@code entity.player.attack.*} family
 * ({@code crit}, {@code knockback}, {@code nodamage}, {@code strong},
 * {@code sweep}, {@code weak}).  None existed pre-1.9 — suppressing them on
 * modern servers restores the era silence on swing.</p>
 */
class AttackSoundsTest {

    // ── suppressed family ────────────────────────────────────────────────

    @Test
    void namespaced_sweep_isSuppressed() {
        assertTrue(AttackSounds.isSuppressedAttackSound("minecraft:entity.player.attack.sweep"),
                "minecraft:entity.player.attack.sweep must be suppressed");
    }

    @Test
    void namespaced_crit_isSuppressed() {
        assertTrue(AttackSounds.isSuppressedAttackSound("minecraft:entity.player.attack.crit"));
    }

    @Test
    void namespaced_knockback_isSuppressed() {
        assertTrue(AttackSounds.isSuppressedAttackSound("minecraft:entity.player.attack.knockback"));
    }

    @Test
    void namespaced_nodamage_isSuppressed() {
        assertTrue(AttackSounds.isSuppressedAttackSound("minecraft:entity.player.attack.nodamage"));
    }

    @Test
    void namespaced_strong_isSuppressed() {
        assertTrue(AttackSounds.isSuppressedAttackSound("minecraft:entity.player.attack.strong"));
    }

    @Test
    void namespaced_weak_isSuppressed() {
        assertTrue(AttackSounds.isSuppressedAttackSound("minecraft:entity.player.attack.weak"));
    }

    @Test
    void bare_sweep_isSuppressed() {
        // getSoundId().toString() on a vanilla-registered sound may omit the
        // "minecraft:" prefix on some PE versions — both forms must match.
        assertTrue(AttackSounds.isSuppressedAttackSound("entity.player.attack.sweep"),
                "bare entity.player.attack.sweep must also be suppressed");
    }

    @Test
    void bare_strong_isSuppressed() {
        assertTrue(AttackSounds.isSuppressedAttackSound("entity.player.attack.strong"));
    }

    // ── not-suppressed ───────────────────────────────────────────────────

    @Test
    void playerHurt_notSuppressed() {
        assertFalse(AttackSounds.isSuppressedAttackSound("minecraft:entity.player.hurt"),
                "entity.player.hurt is not an attack sound");
    }

    @Test
    void blockStoneBreak_notSuppressed() {
        assertFalse(AttackSounds.isSuppressedAttackSound("block.stone.break"));
    }

    @Test
    void null_notSuppressed() {
        assertFalse(AttackSounds.isSuppressedAttackSound(null),
                "null must return false without throwing");
    }

    @Test
    void empty_notSuppressed() {
        assertFalse(AttackSounds.isSuppressedAttackSound(""),
                "empty string must return false");
    }
}

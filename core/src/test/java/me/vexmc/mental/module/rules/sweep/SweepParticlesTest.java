package me.vexmc.mental.module.rules.sweep;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@link SweepParticles#isSweepParticle} contract.
 *
 * <p>The sweep-attack particle resource location is {@code sweep_attack}
 * (namespaced: {@code minecraft:sweep_attack}).  Both forms must be recognised
 * because {@code ParticleType.getName().toString()} may return either
 * depending on PE version.  Every other particle must pass through
 * untouched.</p>
 */
class SweepParticlesTest {

    // ── sweep particle — must match ──────────────────────────────────────

    @Test
    void namespaced_sweepAttack_isMatch() {
        assertTrue(SweepParticles.isSweepParticle("minecraft:sweep_attack"),
                "minecraft:sweep_attack must be recognised as the sweep particle");
    }

    @Test
    void bare_sweepAttack_isMatch() {
        // getName().toString() may omit the "minecraft:" prefix on some PE
        // versions — the bare form must also be caught.
        assertTrue(SweepParticles.isSweepParticle("sweep_attack"),
                "bare sweep_attack must also be recognised");
    }

    // ── other particles — must not match ────────────────────────────────

    @Test
    void namespaced_crit_notMatch() {
        assertFalse(SweepParticles.isSweepParticle("minecraft:crit"),
                "minecraft:crit is not the sweep particle");
    }

    @Test
    void bare_flame_notMatch() {
        assertFalse(SweepParticles.isSweepParticle("flame"),
                "flame is not the sweep particle");
    }

    // ── null / empty guards ──────────────────────────────────────────────

    @Test
    void null_notMatch() {
        assertFalse(SweepParticles.isSweepParticle(null),
                "null must return false without throwing");
    }

    @Test
    void empty_notMatch() {
        assertFalse(SweepParticles.isSweepParticle(""),
                "empty string must return false");
    }
}

package me.vexmc.mental.module.rules.sweep;

import org.jetbrains.annotations.Nullable;

/**
 * Pure helper: identifies the 1.9 sweep-attack particle by resource name.
 *
 * <p>The sweep-attack particle resource location is {@code sweep_attack}
 * (namespaced: {@code minecraft:sweep_attack}). It was added in 1.9 together
 * with the sword sweep mechanic and has never existed in 1.7.10 or 1.8.9.</p>
 *
 * <p>Both namespaced ({@code minecraft:sweep_attack}) and bare
 * ({@code sweep_attack}) forms are recognised because
 * {@code ParticleType.getName().toString()} may return either depending on
 * whether the type is resolved from the static registry or sent as a raw
 * resource location — mirrors the same defensive pattern used by
 * {@link me.vexmc.mental.module.rules.sound.AttackSounds}.</p>
 */
public final class SweepParticles {

    private static final String BARE_NAME = "sweep_attack";
    private static final String NAMESPACED_NAME = "minecraft:" + BARE_NAME;

    private SweepParticles() {
        // utility class
    }

    /**
     * Returns {@code true} when {@code particleName} is the 1.9 sweep-attack
     * particle that should be suppressed, {@code false} for any other input
     * (including {@code null} and empty strings).
     *
     * @param particleName the resource-location string of the particle type,
     *                     as returned by
     *                     {@code ParticleType.getName().toString()}; may be
     *                     namespaced or bare
     */
    public static boolean isSweepParticle(@Nullable String particleName) {
        if (particleName == null || particleName.isEmpty()) {
            return false;
        }
        return NAMESPACED_NAME.equals(particleName) || BARE_NAME.equals(particleName);
    }
}

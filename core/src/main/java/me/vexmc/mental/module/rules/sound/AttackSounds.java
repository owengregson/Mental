package me.vexmc.mental.module.rules.sound;

import org.jetbrains.annotations.Nullable;

/**
 * Pure helper: classifies sound resource names as 1.9 attack-result sounds.
 *
 * <p>The 1.9 attack-result sound family is {@code entity.player.attack.*}
 * ({@code crit}, {@code knockback}, {@code nodamage}, {@code strong},
 * {@code sweep}, {@code weak}).  None of these sounds existed in 1.7.10 or
 * 1.8.9; suppressing them on modern servers restores the era's silence on
 * every swing.</p>
 *
 * <p>Both namespaced ({@code minecraft:entity.player.attack.sweep}) and bare
 * ({@code entity.player.attack.sweep}) forms are recognised because
 * PacketEvents' {@code Sound.getSoundId().toString()} may return either
 * depending on whether the sound was resolved from the static registry or
 * sent as a raw resource location.</p>
 */
public final class AttackSounds {

    /**
     * Path prefix shared by the entire 1.9 attack-result sound family.
     *
     * <p>Matching by prefix rather than an exhaustive set means a future
     * Mojang addition of another {@code entity.player.attack.*} variant is
     * caught automatically — which is the right conservative default for a
     * module whose purpose is "no attack sounds at all".</p>
     */
    private static final String BARE_PREFIX = "entity.player.attack.";
    private static final String NAMESPACED_PREFIX = "minecraft:" + BARE_PREFIX;

    private AttackSounds() {
        // utility class
    }

    /**
     * Returns {@code true} when {@code soundName} is a 1.9 attack-result
     * sound that should be suppressed, {@code false} for any other input
     * (including {@code null} and empty strings).
     *
     * @param soundName the resource-location string of the sound, as returned
     *                  by {@code Sound.getSoundId().toString()}; may be
     *                  namespaced or bare, must not be {@code null} to match
     */
    public static boolean isSuppressedAttackSound(@Nullable String soundName) {
        if (soundName == null || soundName.isEmpty()) {
            return false;
        }
        return soundName.startsWith(NAMESPACED_PREFIX) || soundName.startsWith(BARE_PREFIX);
    }
}

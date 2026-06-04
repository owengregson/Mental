package me.vexmc.mental.api;

import java.util.OptionalDouble;
import java.util.Set;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Mental's public surface for other plugins.
 *
 * <pre>{@code
 * MentalApi mental = Mental.get();
 * if (mental != null && mental.moduleEnabled("knockback")) {
 *     // a practice core pinning a duel to the kohi feel:
 *     mental.setKnockbackProfile(victim, "kohi");
 * }
 * }</pre>
 */
public final class Mental {

    /** Implemented by the Mental plugin and published here while enabled. */
    public interface MentalApi {

        boolean moduleEnabled(@NotNull String moduleId);

        /** Probe-measured round trip, when one has been taken for this player. */
        @NotNull OptionalDouble pingMillis(@NotNull Player player);

        /**
         * The knockback profile currently governing knocks against
         * {@code player}: their override if set, else their world's mapping,
         * else the server default. Call from the player's owning thread.
         */
        @NotNull String knockbackProfile(@NotNull Player player);

        /** The player's explicit profile override, or null when none is set. */
        @Nullable String knockbackProfileOverride(@NotNull Player player);

        /**
         * Sets (or clears, with null) a player's knockback-profile override
         * and fires {@code PlayerKnockbackProfileChangeEvent} on change.
         * Returns false — changing nothing — when no such profile exists.
         * Overrides survive world changes and clear on quit; call from the
         * player's owning thread.
         */
        boolean setKnockbackProfile(@NotNull Player player, @Nullable String profile);

        /** Every loaded knockback profile name. */
        @NotNull Set<String> knockbackProfiles();

        @NotNull String version();
    }

    private static volatile MentalApi instance;

    private Mental() {}

    public static @Nullable MentalApi get() {
        return instance;
    }

    /** Internal — called by the Mental plugin on enable/disable. */
    public static void register(@Nullable MentalApi api) {
        instance = api;
    }
}

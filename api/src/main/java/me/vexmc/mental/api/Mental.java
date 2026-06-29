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
 *     // pin the whole server to the kohi feel:
 *     mental.setKnockbackProfile("kohi");
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
         * {@code player}: their world's mapping if one is set, else the
         * server-wide default. Knockback is global — there is no per-player
         * assignment. Call from the player's owning thread.
         */
        @NotNull String knockbackProfile(@NotNull Player player);

        /** The server-wide default knockback profile name. */
        @NotNull String knockbackProfile();

        /**
         * Sets the server-wide knockback profile, persisting it to
         * knockback.yml and reloading the configuration; fires
         * {@code KnockbackProfileChangeEvent} on change. Returns false —
         * changing nothing — when no profile by that name is loaded. Reloads
         * the configuration, so call from the main thread (the global region
         * thread on Folia).
         */
        boolean setKnockbackProfile(@NotNull String profile);

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

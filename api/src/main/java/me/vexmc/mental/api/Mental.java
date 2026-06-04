package me.vexmc.mental.api;

import java.util.OptionalDouble;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Mental's public surface for other plugins.
 *
 * <pre>{@code
 * MentalApi mental = Mental.get();
 * if (mental != null && mental.moduleEnabled("knockback")) {
 *     OptionalDouble ping = mental.pingMillis(player);
 * }
 * }</pre>
 */
public final class Mental {

    /** Implemented by the Mental plugin and published here while enabled. */
    public interface MentalApi {

        boolean moduleEnabled(@NotNull String moduleId);

        /** Probe-measured round trip, when one has been taken for this player. */
        @NotNull OptionalDouble pingMillis(@NotNull Player player);

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

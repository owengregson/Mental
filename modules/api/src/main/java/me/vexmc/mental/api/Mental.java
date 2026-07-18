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

        /**
         * The API generation. The pre-rewrite surface is generation 1; the v5
         * facade returned 2; the generation-3 surface (capabilities, the
         * {@link MentalCombat} query service, the chain/hit events, knockback
         * outcomes) returns 3. The default stays 1 by design — the conservative
         * value is the graceful-degradation contract: an implementation
         * compiled against an earlier interface answers 1, so consumers never
         * mistake an old facade for a newer one. New behaviour is probed via
         * {@link #has(Capability)}, not inferred from this number.
         */
        default int apiVersion() {
            return 1;
        }

        /**
         * Fine-grained capability probe. Additive evolution contract: new
         * behaviour always arrives behind a new constant, and an
         * implementation compiled against an earlier interface answers
         * {@code false} for constants it has never heard of — so consumers
         * probe, never version-sniff. Capability truth is static per
         * implementation: {@link Capability#COMBO_QUERY} stays {@code true}
         * even while the combo modules are toggled off ({@link #combat()}
         * answering null is the runtime signal).
         */
        default boolean has(@NotNull Capability capability) {
            return false;
        }

        /**
         * The combat-state query service (generation 3). Null when the
         * capability is absent OR while no combo-family module is enabled
         * (combo detection not running). Re-fetch per decision rather than
         * caching — it is one volatile read — though a held handle degrades
         * safely: after a module toggle-off or plugin disable it answers the
         * NONE/false shapes, never an exception, never stale ACTIVE state.
         */
        default @Nullable MentalCombat combat() {
            return null;
        }

        /** Generation-3 capability constants (see {@link #has(Capability)}). */
        enum Capability {
            COMBO_EVENTS,
            COMBO_CHAIN_EVENTS,
            COMBO_HIT_EVENTS,
            COMBO_QUERY,
            WINDOW_QUERY,
            KNOCKBACK_OUTCOMES,
            MITIGATION_PREVIEW
        }
    }

    private static volatile MentalApi instance;

    private Mental() {}

    public static @Nullable MentalApi get() {
        return instance;
    }

    /**
     * Internal — called by the Mental plugin on enable/disable. Registered on
     * enable <em>before</em> any generation-3 event can fire; nulled on disable
     * <em>after</em> the last balanced terminal event (every open sequence's
     * ComboEnd / ComboChainAbort) has already fired.
     */
    public static void register(@Nullable MentalApi api) {
        instance = api;
    }
}

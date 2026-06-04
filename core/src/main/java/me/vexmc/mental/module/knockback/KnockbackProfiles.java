package me.vexmc.mental.module.knockback;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.api.event.PlayerKnockbackProfileChangeEvent;
import me.vexmc.mental.config.KnockbackProfile;
import me.vexmc.mental.config.KnockbackSettings;
import me.vexmc.mental.config.MentalConfig;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Profile resolution: per-player override → per-world map → default.
 *
 * <p>The profile that governs a knock is the <em>victim's</em> — knockback
 * is the victim's motion, so a duel arena assigns both fighters the same
 * profile and a spectator wandering in keeps the world's. Overrides are
 * runtime state for practice cores (set per kit or per match through the
 * API or {@code /mental kb set}); they survive world changes, are validated
 * against the live profile set, and clear on quit.</p>
 *
 * <p>{@link #resolve} touches {@code getWorld()} and must run on the
 * entity's owning thread; the netty fast path reads the profile its
 * per-tick snapshot resolved instead. Override reads are concurrent-safe
 * everywhere.</p>
 */
public final class KnockbackProfiles {

    private final MentalConfig config;
    private final ConcurrentHashMap<UUID, String> overrides = new ConcurrentHashMap<>();

    public KnockbackProfiles(@NotNull MentalConfig config) {
        this.config = config;
    }

    /** The profile governing knocks against {@code victim}, owner thread only. */
    public @NotNull KnockbackProfile resolve(@NotNull LivingEntity victim) {
        KnockbackSettings settings = config.knockback();
        if (victim instanceof Player player) {
            String override = overrides.get(player.getUniqueId());
            if (override != null) {
                KnockbackProfile profile = settings.byName(override);
                if (profile != null) {
                    return profile;
                }
            }
        }
        String world = settings.perWorld().get(victim.getWorld().getName());
        if (world != null) {
            KnockbackProfile profile = settings.byName(world);
            if (profile != null) {
                return profile;
            }
        }
        return settings.profile();
    }

    /** The explicit per-player override, or null when none is set. */
    public @Nullable String override(@NotNull UUID player) {
        return overrides.get(player);
    }

    /**
     * Sets or clears (null) a player's override, firing
     * {@link PlayerKnockbackProfileChangeEvent} when something actually
     * changed. Returns false — and changes nothing — when the named profile
     * does not exist. Call from the player's owning thread.
     */
    public boolean setOverride(@NotNull Player player, @Nullable String profile) {
        UUID id = player.getUniqueId();
        String before = overrides.get(id);
        if (!setOverrideName(id, profile)) {
            return false;
        }
        String after = overrides.get(id);
        if (!Objects.equals(before, after)) {
            new PlayerKnockbackProfileChangeEvent(player, before, after).callEvent();
        }
        return true;
    }

    /** The validation-only core of {@link #setOverride}; never fires events. */
    boolean setOverrideName(@NotNull UUID player, @Nullable String profile) {
        if (profile == null) {
            overrides.remove(player);
            return true;
        }
        String name = profile.trim().toLowerCase(Locale.ROOT);
        if (config.knockback().byName(name) == null) {
            return false;
        }
        overrides.put(player, name);
        return true;
    }

    /** Every loaded profile, by name. */
    public @NotNull Map<String, KnockbackProfile> all() {
        return config.knockback().profiles();
    }

    public @NotNull Set<String> names() {
        return config.knockback().profiles().keySet();
    }

    public void forget(@NotNull UUID player) {
        overrides.remove(player);
    }

    /**
     * Drops overrides whose profile vanished in a reload; returns the
     * affected players' ids so the caller can report them.
     */
    public @NotNull List<UUID> clearStaleOverrides() {
        List<UUID> stale = new ArrayList<>();
        Set<String> names = names();
        overrides.forEach((player, profile) -> {
            if (!names.contains(profile)) {
                stale.add(player);
            }
        });
        stale.forEach(overrides::remove);
        return stale;
    }

    public void clear() {
        overrides.clear();
    }
}

package me.vexmc.mental.module.knockback;

import java.util.Map;
import java.util.Set;
import me.vexmc.mental.config.KnockbackProfile;
import me.vexmc.mental.config.KnockbackSettings;
import me.vexmc.mental.config.MentalConfig;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Profile resolution: per-world map → server default.
 *
 * <p>Knockback in Mental is <em>global</em>: one profile governs the whole
 * server, set through the management GUI (which writes {@code
 * knockback.profile} in knockback.yml and reloads). The optional per-world map
 * still overrides the default for specific worlds — a duel world can run a
 * different feel — but there is no per-player assignment: a profile is the
 * victim's by world, or the server default. (Per-player overrides existed
 * through 1.x; they were dropped when management moved to the GUI, so the
 * active feel is always exactly what an operator selected, server-wide.)</p>
 *
 * <p>{@link #resolve} touches {@code getWorld()} and must run on the victim's
 * owning thread; the netty fast path reads the profile its per-tick snapshot
 * already resolved instead.</p>
 */
public final class KnockbackProfiles {

    private final MentalConfig config;

    public KnockbackProfiles(@NotNull MentalConfig config) {
        this.config = config;
    }

    /** The profile governing knocks against {@code victim}; owner thread only. */
    public @NotNull KnockbackProfile resolve(@NotNull LivingEntity victim) {
        KnockbackSettings settings = config.knockback();
        String world = settings.perWorld().get(victim.getWorld().getName());
        if (world != null) {
            KnockbackProfile profile = settings.byName(world);
            if (profile != null) {
                return profile;
            }
        }
        return settings.profile();
    }

    /** The server-wide default profile name. */
    public @NotNull String defaultProfileName() {
        return config.knockback().defaultProfile();
    }

    /** Every loaded profile, by name. */
    public @NotNull Map<String, KnockbackProfile> all() {
        return config.knockback().profiles();
    }

    public @NotNull Set<String> names() {
        return config.knockback().profiles().keySet();
    }
}

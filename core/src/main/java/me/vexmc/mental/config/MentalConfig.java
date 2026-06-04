package me.vexmc.mental.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Typed, atomically-swapped configuration.
 *
 * <p>A reload parses the whole file into one immutable {@link Snapshot} and
 * publishes it with a single reference store: any code path observes either
 * the old configuration or the new one in full, never a torn mix mid-hit.</p>
 */
public final class MentalConfig {

    public record Snapshot(
            @NotNull HitRegSettings hitReg,
            @NotNull KnockbackSettings knockback,
            @NotNull CompensationSettings compensation,
            @NotNull FishingKnockbackSettings fishingKnockback,
            @NotNull RodVelocitySettings rodVelocity,
            @NotNull ProjectileKnockbackSettings projectileKnockback,
            @NotNull AnticheatSettings anticheat,
            @NotNull CompatibilitySettings compatibility,
            @NotNull DebugSettings debug) {

        static @NotNull Snapshot defaults() {
            return new Snapshot(
                    HitRegSettings.DEFAULTS,
                    KnockbackSettings.DEFAULTS,
                    CompensationSettings.DEFAULTS,
                    FishingKnockbackSettings.DEFAULTS,
                    RodVelocitySettings.DEFAULTS,
                    ProjectileKnockbackSettings.DEFAULTS,
                    AnticheatSettings.DEFAULTS,
                    CompatibilitySettings.DEFAULTS,
                    DebugSettings.DEFAULTS);
        }
    }

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.defaults());

    /** Parses {@code source} and atomically publishes the result; returns any warnings. */
    public @NotNull List<String> reload(@NotNull FileConfiguration source) {
        ConfigIssues issues = new ConfigIssues();
        ConfigurationSection modules = source.getConfigurationSection("modules");
        Snapshot next = new Snapshot(
                HitRegSettings.parse(module(modules, "hit-registration", issues)),
                KnockbackSettings.parse(module(modules, "knockback", issues)),
                CompensationSettings.parse(module(modules, "latency-compensation", issues)),
                FishingKnockbackSettings.parse(module(modules, "fishing-knockback", issues)),
                RodVelocitySettings.parse(module(modules, "rod-velocity", issues)),
                ProjectileKnockbackSettings.parse(module(modules, "projectile-knockback", issues)),
                AnticheatSettings.parse(reader(source, "anticheat", issues)),
                CompatibilitySettings.parse(reader(source, "compatibility", issues)),
                DebugSettings.parse(reader(source, "debug", issues)));
        snapshot.set(next);
        return issues.all();
    }

    public @NotNull Snapshot snapshot() {
        return snapshot.get();
    }

    public @NotNull HitRegSettings hitReg() {
        return snapshot.get().hitReg();
    }

    public @NotNull KnockbackSettings knockback() {
        return snapshot.get().knockback();
    }

    public @NotNull CompensationSettings compensation() {
        return snapshot.get().compensation();
    }

    public @NotNull FishingKnockbackSettings fishingKnockback() {
        return snapshot.get().fishingKnockback();
    }

    public @NotNull RodVelocitySettings rodVelocity() {
        return snapshot.get().rodVelocity();
    }

    public @NotNull ProjectileKnockbackSettings projectileKnockback() {
        return snapshot.get().projectileKnockback();
    }

    public @NotNull AnticheatSettings anticheat() {
        return snapshot.get().anticheat();
    }

    public @NotNull CompatibilitySettings compatibility() {
        return snapshot.get().compatibility();
    }

    public @NotNull DebugSettings debug() {
        return snapshot.get().debug();
    }

    private static @NotNull ConfigReader module(
            ConfigurationSection modules, @NotNull String id, @NotNull ConfigIssues issues) {
        ConfigurationSection section = modules == null ? null : modules.getConfigurationSection(id);
        return new ConfigReader(section, "modules." + id, issues);
    }

    private static @NotNull ConfigReader reader(
            @NotNull FileConfiguration source, @NotNull String path, @NotNull ConfigIssues issues) {
        return new ConfigReader(source.getConfigurationSection(path), path, issues);
    }
}

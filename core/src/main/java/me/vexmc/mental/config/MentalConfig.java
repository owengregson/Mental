package me.vexmc.mental.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

/**
 * Typed, atomically-swapped configuration.
 *
 * <p>A reload parses every file in {@link ConfigSources} into one immutable
 * {@link Snapshot} and publishes it with a single reference store: any code
 * path observes either the old configuration or the new one in full, never
 * a torn mix mid-hit. Module on/off switches live in config.yml's
 * {@code modules} map; each mechanic's tunables live in its own file.</p>
 */
public final class MentalConfig {

    public record Snapshot(
            @NotNull HitRegSettings hitReg,
            @NotNull WtapSettings wtap,
            @NotNull KnockbackSettings knockback,
            @NotNull CompensationSettings compensation,
            @NotNull FishingKnockbackSettings fishingKnockback,
            @NotNull RodVelocitySettings rodVelocity,
            @NotNull ProjectileKnockbackSettings projectileKnockback,
            @NotNull AnticheatSettings anticheat,
            @NotNull CompatibilitySettings compatibility,
            @NotNull DebugSettings debug,
            @NotNull CooldownSettings cooldown,
            @NotNull AttackSoundSettings attackSound) {

        static @NotNull Snapshot defaults() {
            return new Snapshot(
                    HitRegSettings.DEFAULTS,
                    WtapSettings.DEFAULTS,
                    KnockbackSettings.DEFAULTS,
                    CompensationSettings.DEFAULTS,
                    FishingKnockbackSettings.DEFAULTS,
                    RodVelocitySettings.DEFAULTS,
                    ProjectileKnockbackSettings.DEFAULTS,
                    AnticheatSettings.DEFAULTS,
                    CompatibilitySettings.DEFAULTS,
                    DebugSettings.DEFAULTS,
                    CooldownSettings.DEFAULTS,
                    AttackSoundSettings.DEFAULTS);
        }
    }

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.defaults());

    /** Parses {@code sources} and atomically publishes the result; returns any warnings. */
    public @NotNull List<String> reload(@NotNull ConfigSources sources) {
        ConfigIssues issues = new ConfigIssues();
        ConfigReader modules = new ConfigReader(
                sources.main().getConfigurationSection("modules"), "config.yml: modules", issues);
        Snapshot next = new Snapshot(
                HitRegSettings.parse(
                        modules.flag("hit-registration", true),
                        reader(sources.hitReg(), "hit-registration", ConfigStore.HIT_REG_FILE, issues)),
                new WtapSettings(modules.flag("wtap-registration", true)),
                KnockbackSettings.parse(
                        modules.flag("knockback", true),
                        reader(sources.knockback(), "knockback", ConfigStore.KNOCKBACK_FILE, issues),
                        sources.profiles(),
                        issues),
                CompensationSettings.parse(
                        modules.flag("latency-compensation", true),
                        reader(sources.latency(), "latency-compensation", ConfigStore.LATENCY_FILE, issues)),
                FishingKnockbackSettings.parse(
                        modules.flag("fishing-knockback", true),
                        reader(sources.knockback(), "fishing-knockback", ConfigStore.KNOCKBACK_FILE, issues)),
                new RodVelocitySettings(modules.flag("rod-velocity", true)),
                ProjectileKnockbackSettings.parse(
                        modules.flag("projectile-knockback", true),
                        reader(sources.knockback(), "projectile-knockback", ConfigStore.KNOCKBACK_FILE, issues)),
                AnticheatSettings.parse(reader(sources.main(), "anticheat", "config.yml", issues)),
                CompatibilitySettings.parse(reader(sources.main(), "compatibility", "config.yml", issues)),
                DebugSettings.parse(reader(sources.main(), "debug", "config.yml", issues)),
                new CooldownSettings(modules.flag("attack-cooldown", false)),
                new AttackSoundSettings(modules.flag("disable-attack-sounds", false)));
        snapshot.set(next);
        return issues.all();
    }

    public @NotNull Snapshot snapshot() {
        return snapshot.get();
    }

    public @NotNull HitRegSettings hitReg() {
        return snapshot.get().hitReg();
    }

    public @NotNull WtapSettings wtap() {
        return snapshot.get().wtap();
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

    public @NotNull CooldownSettings cooldown() {
        return snapshot.get().cooldown();
    }

    public @NotNull AttackSoundSettings attackSound() {
        return snapshot.get().attackSound();
    }

    private static @NotNull ConfigReader reader(
            @NotNull ConfigurationSection root,
            @NotNull String path,
            @NotNull String file,
            @NotNull ConfigIssues issues) {
        return new ConfigReader(root.getConfigurationSection(path), file + ": " + path, issues);
    }
}

package me.vexmc.mental.v5.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import me.vexmc.mental.kernel.profile.KnockbackDelivery;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Friction;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Limits;
import me.vexmc.mental.kernel.profile.KnockbackProfile.Push;
import me.vexmc.mental.kernel.profile.KnockbackProfile.RangeReduction;
import me.vexmc.mental.kernel.profile.KnockbackProfile.WtapExtra;
import me.vexmc.mental.kernel.profile.ModernKnockback;
import me.vexmc.mental.kernel.profile.PaceScaling;
import me.vexmc.mental.kernel.profile.ResistancePolicy;
import me.vexmc.mental.kernel.profile.VerticalMode;
import me.vexmc.mental.kernel.profile.VerticalShape;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The parsing half of the knockback profile schema, left behind in Phase 1
 * when the schema moved to the kernel. This re-attaches the retired
 * {@code config.KnockbackProfile.parse} logic to the kernel record: an empty
 * section yields {@link KnockbackProfile#LEGACY_17} byte-identical (the
 * era-exact no-op pin), and every out-of-range knob warns once through the
 * reader and falls back per key.
 */
public final class ProfileParser {

    /** The parsed profiles plus the resolved server-wide default and per-world map. */
    public record Profiles(
            String defaultProfile,
            Map<String, String> perWorld,
            Map<String, KnockbackProfile> byName) {}

    private ProfileParser() {}

    /** Parses one profile's {@code knockback} block. Missing keys fall back to LEGACY_17. */
    public static KnockbackProfile parse(
            String name, String displayName, String description, ConfigReader reader) {
        KnockbackProfile legacy = KnockbackProfile.LEGACY_17;
        RangeReduction disabled = RangeReduction.DISABLED;
        ConfigReader base = reader.sub("base");
        ConfigReader extra = reader.sub("extra");
        ConfigReader wtap = reader.sub("wtap-extra");
        ConfigReader friction = reader.sub("friction");
        ConfigReader limits = reader.sub("limits");
        ConfigReader air = reader.sub("air");
        ConfigReader add = reader.sub("add");
        ConfigReader range = reader.sub("range-reduction");
        ConfigReader modifiers = reader.sub("modifiers");
        ConfigReader delivery = reader.sub("delivery");
        ConfigReader speed = reader.sub("speed-scaling");
        ConfigReader modern = reader.sub("modern");
        ConfigReader residual = modern.sub("residual");
        PaceScaling pace = legacy.paceScaling();
        ModernKnockback offModern = legacy.modern();
        return new KnockbackProfile(
                name,
                displayName,
                description,
                new Push(
                        base.numberAtLeast("horizontal", legacy.base().horizontal(), 0),
                        base.numberAtLeast("vertical", legacy.base().vertical(), 0)),
                reader.oneOf("vertical-mode", legacy.verticalMode(), VerticalMode.class),
                new Push(
                        extra.numberAtLeast("horizontal", legacy.extra().horizontal(), 0),
                        extra.numberAtLeast("vertical", legacy.extra().vertical(), 0)),
                new WtapExtra(
                        wtap.flag("enabled", legacy.wtapExtra().enabled()),
                        wtap.numberAtLeast("horizontal", legacy.wtapExtra().horizontal(), 0),
                        wtap.numberAtLeast("vertical", legacy.wtapExtra().vertical(), 0)),
                new Friction(
                        friction.numberAtLeast("x", legacy.friction().x(), 0),
                        friction.numberAtLeast("y", legacy.friction().y(), 0),
                        friction.numberAtLeast("z", legacy.friction().z(), 0)),
                new Limits(
                        limits.number("vertical", legacy.limits().vertical()),
                        limits.number("vertical-min", legacy.limits().verticalMin()),
                        limits.number("horizontal", legacy.limits().horizontal())),
                new Push(
                        air.numberAtLeast("horizontal", legacy.air().horizontal(), 0),
                        air.numberAtLeast("vertical", legacy.air().vertical(), 0)),
                new Push(
                        add.numberAtLeast("horizontal", legacy.add().horizontal(), 0),
                        add.numberAtLeast("vertical", legacy.add().vertical(), 0)),
                new RangeReduction(
                        range.flag("enabled", disabled.enabled()),
                        range.numberAtLeast("start-distance", disabled.startDistance(), 0),
                        range.numberAtLeast("factor", disabled.factor(), 0),
                        range.numberAtLeast("offset", disabled.offset(), 0),
                        range.numberAtLeast("max-reduction", disabled.maxReduction(), 0)),
                modifiers.numberAtLeast("sprint", legacy.sprintFactor(), 0),
                modifiers.flag("combos", legacy.combos()),
                delivery.oneOf("melee", legacy.meleeDelivery(), KnockbackDelivery.class),
                delivery.oneOf("projectile", legacy.projectileDelivery(), KnockbackDelivery.class),
                modifiers.oneOf("armor-resistance", legacy.resistance(), ResistancePolicy.class),
                modifiers.flag("shield-blocking-cancels", legacy.shieldBlockingCancels()),
                new PaceScaling(
                        paceMode(speed, pace.mode()),
                        speed.numberAtLeast("exponent", pace.exponent(), 0),
                        speed.numberAtLeast("min", pace.min(), 0),
                        speed.numberAtLeast("max", pace.max(), 0)),
                new ModernKnockback(
                        modernFormula(reader, offModern.enabled()),
                        modern.numberAtLeast("base-strength", offModern.baseStrength(), 0),
                        modern.numberAtLeast("sprint-bonus", offModern.sprintBonus(), 0),
                        modern.numberAtLeast("enchant-bonus", offModern.enchantBonus(), 0),
                        residual.numberAtLeast("horizontal", offModern.residualHorizontal(), 0),
                        residual.numberAtLeast("vertical", offModern.residualVertical(), 0),
                        modern.number("vertical-cap", offModern.verticalCap()),
                        modern.flag("downward-knockback", offModern.downwardKnockback()),
                        modern.oneOf("vertical-shape", offModern.verticalShape(), VerticalShape.class),
                        modern.numberAtLeast(
                                "vertical-grounded-factor", offModern.groundedVerticalFactor(), 0),
                        modern.numberAtLeast(
                                "vertical-airborne-factor", offModern.airborneVerticalFactor(), 0)));
    }

    /**
     * Reads the {@code formula} switch — {@code legacy} (the fallback) or
     * {@code modern} — into {@link ModernKnockback#enabled}. Unlike
     * {@code speed-scaling.mode} there is no YAML-1.1 boolean trap ({@code legacy}
     * and {@code modern} are not reserved words), so a plain string read with a
     * loud warn-and-fallback on any other value suffices.
     */
    private static boolean modernFormula(ConfigReader reader, boolean fallback) {
        ConfigurationSection section = reader.section();
        if (section == null || !section.isSet("formula")) {
            return fallback;
        }
        String raw = String.valueOf(section.getString("formula", "")).trim().toLowerCase(Locale.ROOT);
        String path = reader.prefix().isEmpty() ? "formula" : reader.prefix() + ".formula";
        return switch (raw) {
            case "legacy" -> false;
            case "modern" -> true;
            default -> {
                reader.issues().warn(path,
                        "expected legacy/modern, found '" + section.getString("formula") + "'",
                        fallback ? "modern" : "legacy");
                yield fallback;
            }
        };
    }

    /**
     * Reads {@code speed-scaling.mode} tolerantly of the YAML 1.1 boolean trap:
     * SnakeYAML (Bukkit's parser) resolves an unquoted {@code off} to the boolean
     * {@code false}, so the documented default {@code mode: off} arrives as a
     * Boolean. Map {@code false → OFF} silently (it IS off) and {@code true →}
     * a loud warn + fallback (nonsensical); anything else parses as the enum,
     * which warns loudly on an unknown value per the config conventions.
     */
    private static PaceScaling.Mode paceMode(ConfigReader speed, PaceScaling.Mode fallback) {
        ConfigurationSection section = speed.section();
        if (section == null || !section.isSet("mode")) {
            return fallback;
        }
        if (section.isBoolean("mode")) {
            if (!section.getBoolean("mode")) {
                return PaceScaling.Mode.OFF; // `mode: off` booleanized by YAML
            }
            speed.issues().warn(
                    speed.prefix() + ".mode", "expected off/attacker, found 'on/true'", fallback);
            return fallback;
        }
        return speed.oneOf("mode", fallback, PaceScaling.Mode.class);
    }

    /**
     * Parses every profile file, resolves the server-wide default and the
     * per-world map (the retired {@code KnockbackSettings.parse} logic). The
     * built-in {@code legacy-1.7} is always present so resolution can never
     * come up empty; an unknown selection warns once and falls back.
     *
     * @param knockback reader over knockback.yml's {@code knockback} section
     * @param profileSources profiles/&lt;name&gt;.yml roots keyed by file stem
     */
    public static Profiles parseSection(
            ConfigReader knockback,
            Map<String, ? extends ConfigurationSection> profileSources,
            ConfigIssues issues) {

        Map<String, KnockbackProfile> profiles = new TreeMap<>();
        for (Map.Entry<String, ? extends ConfigurationSection> entry : profileSources.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            ConfigReader root = new ConfigReader(
                    entry.getValue(), "profiles/" + entry.getKey() + ".yml", issues);
            profiles.put(name, parse(
                    name,
                    root.text("display-name", name),
                    root.text("description", ""),
                    root.sub("knockback")));
        }
        profiles.putIfAbsent(KnockbackProfile.LEGACY_17.name(), KnockbackProfile.LEGACY_17);

        String selected = knockback.text("profile", KnockbackProfile.LEGACY_17.name())
                .toLowerCase(Locale.ROOT);
        if (!profiles.containsKey(selected)) {
            issues.warn(knockback.prefix() + ".profile",
                    "unknown profile '" + selected + "' (available: " + profiles.keySet() + ")",
                    KnockbackProfile.LEGACY_17.name());
            selected = KnockbackProfile.LEGACY_17.name();
        }

        Map<String, String> perWorld = new LinkedHashMap<>();
        ConfigurationSection worlds = knockback.section() == null
                ? null
                : knockback.section().getConfigurationSection("per-world");
        if (worlds != null) {
            for (String world : worlds.getKeys(false)) {
                String profileName = String.valueOf(worlds.getString(world, ""))
                        .trim().toLowerCase(Locale.ROOT);
                if (profiles.containsKey(profileName)) {
                    perWorld.put(world, profileName);
                } else {
                    issues.warn(knockback.prefix() + ".per-world." + world,
                            "unknown profile '" + profileName + "'", "entry dropped");
                }
            }
        }
        return new Profiles(selected, Map.copyOf(perWorld), Map.copyOf(profiles));
    }
}

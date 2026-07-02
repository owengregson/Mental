package me.vexmc.mental.v5.config;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.config.settings.AnticheatSettings;
import me.vexmc.mental.v5.config.settings.DebugSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;

/**
 * The immutable configuration snapshot (spec §10): built whole by the
 * {@link SnapshotParser} through the parser-owned {@link Builder}, swapped by
 * one reference so no hit ever observes a torn mix. Reads are typed: module
 * enablement by {@link Feature}, per-feature settings by {@link SettingsKey}
 * identity, the resolved profile by world name, and the plugin-wide sections
 * by dedicated accessors. There is no positional mega-constructor and no
 * setter surface.
 */
public final class Snapshot {

    private final Map<Feature, Boolean> enabled;
    private final Map<SettingsKey<?>, Object> settings;
    private final String defaultProfile;
    private final Map<String, String> perWorld;
    private final Map<String, KnockbackProfile> profiles;
    private final AnticheatSettings anticheat;
    private final DebugSettings debug;
    private final OcmCoordination ocmCoordination;

    private Snapshot(Builder builder) {
        this.enabled = new EnumMap<>(builder.enabled);
        this.settings = new IdentityHashMap<>(builder.settings);
        this.defaultProfile = builder.defaultProfile;
        this.perWorld = Map.copyOf(builder.perWorld);
        this.profiles = Map.copyOf(builder.profiles);
        this.anticheat = builder.anticheat;
        this.debug = builder.debug;
        this.ocmCoordination = builder.ocmCoordination;
    }

    /** Whether the module toggle for {@code feature} is on (modules.* / infra always on). */
    public boolean enabled(Feature feature) {
        Boolean value = enabled.get(feature);
        return value != null ? value : feature.defaultEnabled();
    }

    /** The feature's parsed settings — never null (the parser guarantees a value per key). */
    @SuppressWarnings("unchecked")
    public <S> S settings(SettingsKey<S> key) {
        Object value = settings.get(key);
        if (value == null) {
            throw new IllegalStateException("no settings registered for " + key);
        }
        return (S) value;
    }

    /**
     * The knockback profile for {@code worldName}: the per-world override if
     * one is set, otherwise the server default, falling back to LEGACY_17 when
     * the selected name resolves to no loaded profile.
     */
    public KnockbackProfile profileFor(String worldName) {
        String name = perWorld.getOrDefault(worldName, defaultProfile);
        KnockbackProfile profile = profiles.get(name);
        return profile != null ? profile : KnockbackProfile.LEGACY_17;
    }

    public AnticheatSettings anticheat() {
        return anticheat;
    }

    public DebugSettings debug() {
        return debug;
    }

    public OcmCoordination ocmCoordination() {
        return ocmCoordination;
    }

    /** Built by the parser; every field is set before {@link #build()}. */
    static final class Builder {
        private final Map<Feature, Boolean> enabled = new EnumMap<>(Feature.class);
        private final Map<SettingsKey<?>, Object> settings = new IdentityHashMap<>();
        private String defaultProfile = KnockbackProfile.LEGACY_17.name();
        private Map<String, String> perWorld = Map.of();
        private Map<String, KnockbackProfile> profiles =
                Map.of(KnockbackProfile.LEGACY_17.name(), KnockbackProfile.LEGACY_17);
        private AnticheatSettings anticheat = AnticheatSettings.DEFAULTS;
        private DebugSettings debug = DebugSettings.DEFAULTS;
        private OcmCoordination ocmCoordination = OcmCoordination.AUTO;

        Builder enable(Feature feature, boolean on) {
            enabled.put(feature, on);
            return this;
        }

        Builder put(SettingsKey<?> key, Object value) {
            settings.put(key, value);
            return this;
        }

        Builder profiles(ProfileParser.Profiles resolved) {
            this.defaultProfile = resolved.defaultProfile();
            this.perWorld = resolved.perWorld();
            this.profiles = resolved.byName();
            return this;
        }

        Builder anticheat(AnticheatSettings anticheat) {
            this.anticheat = anticheat;
            return this;
        }

        Builder debug(DebugSettings debug) {
            this.debug = debug;
            return this;
        }

        Builder ocmCoordination(OcmCoordination ocmCoordination) {
            this.ocmCoordination = ocmCoordination;
            return this;
        }

        Snapshot build() {
            return new Snapshot(this);
        }
    }
}

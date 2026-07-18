package me.vexmc.mental.v5.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import me.vexmc.mental.v5.config.ConfigStore;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.SnapshotParser;
import me.vexmc.mental.v5.feature.Feature;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The descriptor registry's structural pins — the same philosophy as
 * {@code DashboardModelTest}, applied to the settings layer. Every knob carries
 * renderable copy, every group fits the chrome frame, every writable key routes
 * to a known overlay root, every reader resolves against a DEFAULTS snapshot with
 * the type its widget expects, and the configured/toggle-only partition is
 * exhaustive against the feature enum.
 */
class SettingsCatalogTest {

    /** The overlay roots {@code Overlay.route} already handles (GUI §4.3). */
    private static final Set<String> KNOWN_ROOTS = Set.of(
            "hit-registration", "latency-compensation", "fishing-knockback", "projectile-knockback",
            "combo-hold", "combo-reach-handicap", "pot-fill", "fast-pots", "disable-offhand",
            "disable-crafting", "drop-protection", "charged-attacks", "effects", "anticheat",
            "debug", "modules");

    /** The toggle-only features (GUI §4.2) — no settings page, by design. */
    private static final List<Feature> TOGGLE_ONLY = List.of(
            Feature.WTAP_REGISTRATION, Feature.KNOCKBACK, Feature.ROD_VELOCITY,
            Feature.ARMOUR_STRENGTH, Feature.ARMOUR_DURABILITY, Feature.CRIT_FALLBACK,
            Feature.TOOL_DURABILITY, Feature.SWORD_BLOCKING, Feature.ATTACK_COOLDOWN,
            Feature.ATTACK_SOUNDS, Feature.SWEEP, Feature.GOLDEN_APPLES,
            Feature.ENDER_PEARL_COOLDOWN, Feature.REGEN, Feature.POTION_DURATIONS,
            Feature.POTION_VALUES, Feature.HITBOX,
            // Combat Test 8c rule features that are era-fixed toggles (the two with
            // tunables — WEAPON_ATTACK_SPEEDS, CHARGED_ATTACKS — own settings pages).
            Feature.CT8C_PROJECTILES, Feature.CT8C_DAMAGE, Feature.CT8C_CRITS,
            Feature.CT8C_IFRAMES, Feature.CT8C_SHIELDS, Feature.CLEAVING,
            Feature.CT8C_SWEEP, Feature.CT8C_REGEN, Feature.CT8C_CONSUMABLES,
            Feature.CT8C_POTIONS, Feature.CT8C_REACH);

    /** An all-empty parse — every settings record is its DEFAULTS (parse(empty) contract). */
    private static Snapshot defaults() {
        ConfigStore.Sources sources = new ConfigStore.Sources(
                new MemoryConfiguration(), new MemoryConfiguration(),
                new MemoryConfiguration(), new MemoryConfiguration(),
                new MemoryConfiguration(), new MemoryConfiguration(),
                new MemoryConfiguration(), new MemoryConfiguration(),
                new MemoryConfiguration(), Map.of(), Map.of());
        return SnapshotParser.parse(sources).snapshot();
    }

    private static void forEachKnob(BiConsumer<Feature, SettingsCatalog.Knob> action) {
        for (Feature feature : SettingsCatalog.configuredFeatures()) {
            for (List<SettingsCatalog.Knob> group : SettingsCatalog.pageFor(feature).orElseThrow().groups()) {
                for (SettingsCatalog.Knob knob : group) {
                    action.accept(feature, knob);
                }
            }
        }
    }

    @Test
    void everyConfiguredFeatureIsNonInfrastructure() {
        for (Feature feature : SettingsCatalog.configuredFeatures()) {
            assertFalse(feature.infrastructure(), feature + " is infrastructure but owns a page");
        }
    }

    @Test
    void everyPageBelongsToItsFeature() {
        for (Feature feature : SettingsCatalog.configuredFeatures()) {
            assertEquals(feature, SettingsCatalog.pageFor(feature).orElseThrow().feature());
        }
    }

    @Test
    void everyKnobCarriesRenderableCopy() {
        forEachKnob((feature, knob) -> {
            assertFalse(knob.label().isBlank(), feature + " has a knob with a blank label");
            assertFalse(knob.materialName().isBlank(),
                    feature + " knob '" + knob.label() + "' has a blank material");
            assertFalse(knob.blurb().isBlank(),
                    feature + " knob '" + knob.label() + "' has a blank blurb");
        });
    }

    @Test
    void everyGroupFitsAContentRow() {
        for (Feature feature : SettingsCatalog.configuredFeatures()) {
            List<List<SettingsCatalog.Knob>> groups = SettingsCatalog.pageFor(feature).orElseThrow().groups();
            assertTrue(groups.size() >= 1 && groups.size() <= 3,
                    feature + " has " + groups.size() + " groups (must be 1..3)");
            for (List<SettingsCatalog.Knob> group : groups) {
                assertTrue(group.size() >= 1 && group.size() <= 7,
                        feature + " has a group of " + group.size() + " tiles (must be 1..7)");
            }
        }
    }

    @Test
    void everyWritableKnobKeyRoutesToAKnownRoot() {
        forEachKnob((feature, knob) -> {
            if (knob.key() == null) {
                return; // POINTER / INFO write nothing.
            }
            int dot = knob.key().indexOf('.');
            String root = dot >= 0 ? knob.key().substring(0, dot) : knob.key();
            assertTrue(KNOWN_ROOTS.contains(root),
                    feature + " knob key '" + knob.key() + "' routes to unknown root '" + root + "'");
        });
    }

    @Test
    void stepperBoundsAreOrderedAndDefaultsSitInside() {
        Snapshot defaults = defaults();
        forEachKnob((feature, knob) -> {
            if (knob.kind() != SettingsCatalog.Kind.STEP_INT
                    && knob.kind() != SettingsCatalog.Kind.STEP_DOUBLE
                    && knob.kind() != SettingsCatalog.Kind.NUMBER) {
                return;
            }
            assertTrue(knob.min() < knob.max(),
                    feature + " knob '" + knob.label() + "' has min >= max");
            double value = ((Number) knob.reader().apply(defaults)).doubleValue();
            assertTrue(value >= knob.min() && value <= knob.max(),
                    feature + " knob '" + knob.label() + "' default " + value
                            + " outside [" + knob.min() + ", " + knob.max() + "]");
        });
    }

    @Test
    void readersResolveAgainstTheDefaultsSnapshot() {
        Snapshot defaults = defaults();
        forEachKnob((feature, knob) -> {
            if (knob.kind() == SettingsCatalog.Kind.POINTER || knob.kind() == SettingsCatalog.Kind.INFO) {
                return;
            }
            Object value = knob.reader().apply(defaults);
            assertNotNull(value, feature + " knob '" + knob.label() + "' reader returned null");
            switch (knob.kind()) {
                case TOGGLE -> assertInstanceOf(Boolean.class, value,
                        feature + " TOGGLE '" + knob.label() + "' must read a Boolean");
                case STEP_INT, STEP_DOUBLE, NUMBER -> assertInstanceOf(Number.class, value,
                        feature + " numeric '" + knob.label() + "' must read a Number");
                case CYCLE, TEXT -> assertInstanceOf(String.class, value,
                        feature + " '" + knob.label() + "' must read a String");
                default -> { }
            }
        });
    }

    @Test
    void cycleReadersReturnAListedOption() {
        Snapshot defaults = defaults();
        forEachKnob((feature, knob) -> {
            if (knob.kind() != SettingsCatalog.Kind.CYCLE) {
                return;
            }
            Object value = knob.reader().apply(defaults);
            assertTrue(knob.options().contains(value),
                    feature + " CYCLE '" + knob.label() + "' default " + value
                            + " is not one of " + knob.options());
        });
    }

    @Test
    void toggleOnlyFeaturesHaveNoPage() {
        for (Feature feature : TOGGLE_ONLY) {
            assertTrue(SettingsCatalog.pageFor(feature).isEmpty(),
                    feature + " should be toggle-only (no page)");
        }
        // Exhaustive: every non-infrastructure feature is either configured or toggle-only.
        Set<Feature> accounted = new HashSet<>(SettingsCatalog.configuredFeatures());
        accounted.addAll(TOGGLE_ONLY);
        for (Feature feature : Feature.values()) {
            if (feature.infrastructure()) {
                continue;
            }
            assertTrue(accounted.contains(feature),
                    feature + " is neither configured nor toggle-only");
        }
    }
}

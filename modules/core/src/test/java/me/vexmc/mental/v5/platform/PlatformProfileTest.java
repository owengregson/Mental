package me.vexmc.mental.v5.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import me.vexmc.mental.platform.Capabilities;
import me.vexmc.mental.platform.ServerEnvironment;
import me.vexmc.mental.v5.feature.Feature;
import org.junit.jupiter.api.Test;

/**
 * The {@link PlatformProfile} manifest (R10/B10): a Required miss disables ONLY
 * its owning feature (or, engine-critical, fails the boot); an OptionalSince
 * absence yields the declared fallback; and every entry carries a self-describing
 * name/fallback, never a magic literal.
 */
class PlatformProfileTest {

    @Test
    void aRequiredMissDisablesOnlyItsOwningFeature() {
        List<String> logged = new CopyOnWriteArrayList<>();
        List<ManifestEntry> manifest = List.of(
                Required.owned("attribute:knockback_resistance", Feature.KNOCKBACK, () -> null),
                Required.owned("attribute:max_health", Feature.REGEN, () -> "present"),
                OptionalSince.resolve("flag:x", "1.21.2", Boolean.FALSE, "note", () -> null));

        Set<Feature> disabled = PlatformProfile.resolveDisabled(manifest, logged::add);

        assertEquals(Set.of(Feature.KNOCKBACK), disabled, "only the failing entry's owner is disabled");
        assertFalse(disabled.contains(Feature.REGEN), "a present Required must not disable its owner");
        assertEquals(1, logged.size(), "exactly one loud log for the one mapping break");
        assertTrue(logged.get(0).contains("knockback_resistance") && logged.get(0).contains("KNOCKBACK"));
    }

    @Test
    void anAbsentArmorToughnessDoesNotDisableArmourStrength() {
        // Item 1 (Phase 4): the era 1.8 flat model has no toughness term, so the
        // GENERIC_ARMOR_TOUGHNESS attribute (absent below 1.11.2) is an OptionalSince, not a
        // Required handle. Its absence must be a quiet typed outcome — never a feature-disable —
        // so ARMOUR_STRENGTH keeps composing the era armour values on 1.9.4/1.10.2.
        List<String> logged = new CopyOnWriteArrayList<>();
        List<ManifestEntry> manifest = List.of(
                Required.owned("attribute:armor", Feature.ARMOUR_STRENGTH, () -> "present"),
                OptionalSince.resolve("attribute:armor_toughness", "1.11.2", null,
                        "the era 1.8 flat model ignores toughness", () -> null));

        Set<Feature> disabled = PlatformProfile.resolveDisabled(manifest, logged::add);

        assertFalse(disabled.contains(Feature.ARMOUR_STRENGTH),
                "an absent toughness attribute must not disable ARMOUR_STRENGTH — the era model ignores it");
        assertTrue(disabled.isEmpty(), "an OptionalSince miss disables nothing");
        assertTrue(logged.isEmpty(), "an OptionalSince miss is quiet — no loud mapping-break log");
    }

    @Test
    void armorToughnessResolvesAsOptionalSinceOnTheFloorApi() {
        // On the modern floor (1.17.1) the attribute IS present, so the entry resolves; the point
        // pinned here is that it is modelled as OptionalSince (a version-gated handle), so that a
        // legacy server missing it degrades quietly rather than disabling the feature.
        PlatformProfile profile = PlatformProfile.resolve(
                ServerEnvironment.parse("1.17.1-R0.1-SNAPSHOT"), Capabilities.detect(), message -> {});
        ManifestEntry toughness = profile.entries().stream()
                .filter(e -> e.name().equals("attribute:armor_toughness"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("armor_toughness entry missing from the manifest"));
        assertTrue(toughness instanceof OptionalSince<?>,
                "armor_toughness must be OptionalSince (version-gated), not a Required feature-disabler");
        assertFalse(profile.disabledFeatures().contains(Feature.ARMOUR_STRENGTH),
                "ARMOUR_STRENGTH must never be platform-disabled where the attribute is present either");
    }

    @Test
    void anEngineCriticalMissFailsTheBoot() {
        List<ManifestEntry> manifest = List.of(Required.engineCritical("engine:core_handle", () -> null));
        assertThrows(
                IllegalStateException.class,
                () -> PlatformProfile.resolveDisabled(manifest, message -> {}));
    }

    @Test
    void projectileKnockbackRestoredFollowsThe1212Boundary() {
        // Below 1.21.2 vanilla dropped projectile-KB-vs-players — Mental substitutes.
        PlatformProfile below = PlatformProfile.resolve(
                ServerEnvironment.parse("1.21.1-R0.1-SNAPSHOT"), Capabilities.detect(), message -> {});
        assertFalse(below.projectileKnockbackRestored(),
                "below 1.21.2 the era projectile-KB substitution stands");
        // 1.21.2+ restored it (mandate §10 / MC-2110) — the substitution is a no-op.
        PlatformProfile restored = PlatformProfile.resolve(
                ServerEnvironment.parse("1.21.2-R0.1-SNAPSHOT"), Capabilities.detect(), message -> {});
        assertTrue(restored.projectileKnockbackRestored(),
                "1.21.2+ restored vanilla projectile knockback");
    }

    @Test
    void anOptionalSinceAbsenceYieldsTheDeclaredFallback() {
        OptionalSince<Boolean> absent = OptionalSince.resolve(
                "flag:projectile_kb_restored", "1.21.2", Boolean.FALSE, "Mental substitutes it", () -> null);
        assertFalse(absent.present());
        assertTrue(absent.value().isEmpty());
        assertEquals(Boolean.FALSE, absent.orFallback(), "absence yields the declared fallback");

        OptionalSince<Boolean> present = OptionalSince.resolve(
                "flag:projectile_kb_restored", "1.21.2", Boolean.FALSE, "Mental substitutes it", () -> Boolean.TRUE);
        assertTrue(present.present());
        assertEquals(Boolean.TRUE, present.orFallback(), "presence yields the resolved value, not the fallback");
    }

    @Test
    void everyResolvedEntryIsSelfDescribingNotAMagicLiteral() {
        PlatformProfile profile = PlatformProfile.resolve(
                ServerEnvironment.parse("1.17.1-R0.1-SNAPSHOT"), Capabilities.detect(), message -> {});

        assertFalse(profile.entries().isEmpty(), "the manifest is non-empty");
        assertTrue(profile.disabledFeatures().isEmpty(),
                "every Required handle resolves on the floor API — no feature is platform-disabled");
        for (ManifestEntry entry : profile.entries()) {
            assertFalse(entry.name().isBlank(), "every entry names itself");
            assertFalse(entry.describe().isBlank(), "every entry describes itself");
            if (entry instanceof OptionalSince<?> optional) {
                assertFalse(optional.since().isBlank(), entry.name() + " declares a since-version");
                assertFalse(optional.fallbackNote().isBlank(), entry.name() + " declares a fallback note");
            }
        }

        String report = profile.bootReport();
        assertFalse(report.isBlank(), "the boot report is a real summary");
        assertFalse(report.contains("\n"), "the boot report is a single line");
    }
}

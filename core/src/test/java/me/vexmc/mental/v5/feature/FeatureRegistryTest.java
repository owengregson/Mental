package me.vexmc.mental.v5.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import org.junit.jupiter.api.Test;

/**
 * The descriptor registry is the single source of truth for feature identity.
 * These pins guard the operator contract (the exact {@code modules.*} key set),
 * the no-silent-facet-gap invariant (B5), and settings-key identity.
 */
class FeatureRegistryTest {

    /**
     * The exact {@code modules.flag(...)} keys mined from the retired
     * {@code MentalConfig.reload} — the operator contract, written literally.
     */
    private static final Set<String> OPERATOR_CONTRACT_KEYS = Set.of(
            "hit-registration",
            "wtap-registration",
            "knockback",
            "latency-compensation",
            "fishing-knockback",
            "rod-velocity",
            "projectile-knockback",
            "attack-cooldown",
            "disable-attack-sounds",
            "disable-sword-sweep",
            "disable-crafting",
            "disable-offhand",
            "old-golden-apples",
            "disable-enderpearl-cooldown",
            "old-player-regen",
            "old-armour-strength",
            "old-armour-durability",
            "old-potion-durations",
            "old-potion-values",
            "old-critical-hits",
            "old-tool-durability",
            "sword-blocking",
            "old-hitboxes",
            "combo-hold",
            "combo-reach-handicap",
            "pot-fill",
            "fast-pots");

    @Test
    void yamlKeysAreExactlyTheOperatorContractSetUniqueAndNonNull() {
        Set<String> keys = new HashSet<>();
        for (Feature feature : Feature.values()) {
            if (feature.infrastructure()) {
                assertNull(feature.yamlKey(), () -> feature + " is infra and must have no yaml key");
                continue;
            }
            String key = feature.yamlKey();
            assertNotNull(key, () -> feature + " must have a yaml key");
            assertTrue(keys.add(key), () -> "duplicate yaml key: " + key);
        }
        assertEquals(OPERATOR_CONTRACT_KEYS, keys,
                "the module toggle set must match MentalConfig.reload exactly (operator contract)");
    }

    @Test
    void featuresDeclaringAnOcmMappedTokenHaveFacetsFullyDeclared() {
        for (Feature feature : Feature.values()) {
            boolean hasOcmMappedToken = feature.tokens().stream()
                    .anyMatch(token -> token.ocmKey() != null);
            if (hasOcmMappedToken) {
                assertFacetsFullyDeclared(feature);
            }
        }
    }

    @Test
    void everyNonInfraFeatureDeclaresAllFourFacetsExplicitly() {
        for (Feature feature : Feature.values()) {
            if (feature.infrastructure()) {
                assertNull(feature.facets(), () -> feature + " is infra and declares no facets");
                continue;
            }
            assertFacetsFullyDeclared(feature);
        }
    }

    private static void assertFacetsFullyDeclared(Feature feature) {
        Facets facets = feature.facets();
        assertNotNull(facets, () -> feature + " must declare facets");
        assertNotNull(facets.serverRule(), () -> feature + " serverRule facet");
        assertNotNull(facets.clientPresentation(), () -> feature + " clientPresentation facet");
        assertNotNull(facets.fastPathDamage(), () -> feature + " fastPathDamage facet");
        assertNotNull(facets.vanillaPathDamage(), () -> feature + " vanillaPathDamage facet");
    }

    @Test
    void noTwoFeaturesShareASettingsKey() {
        IdentityHashMap<SettingsKey<?>, Feature> seen = new IdentityHashMap<>();
        for (Feature feature : Feature.values()) {
            SettingsKey<?> key = feature.settingsKey();
            assertNotNull(key, () -> feature + " must have a settings key");
            Feature previous = seen.put(key, feature);
            assertNull(previous, () -> feature + " shares a settings key with " + previous);
        }
        assertEquals(Feature.values().length, seen.size());
    }

    @Test
    void engineFeaturesDefaultOnAndPortedRulesDefaultOff() {
        Set<Feature> defaultOn = Set.of(
                Feature.HIT_REGISTRATION, Feature.WTAP_REGISTRATION, Feature.KNOCKBACK,
                Feature.LATENCY_COMPENSATION, Feature.FISHING_KNOCKBACK, Feature.ROD_VELOCITY,
                Feature.PROJECTILE_KNOCKBACK, Feature.ANTICHEAT_COMPAT, Feature.OCM_COMPAT);
        for (Feature feature : Feature.values()) {
            assertEquals(defaultOn.contains(feature), feature.defaultEnabled(),
                    () -> feature + " default enablement");
        }
    }

    @Test
    void everyArbitratedTokenIsDeclaredByExactlyOneFeature() {
        for (MechanicToken token : MechanicToken.values()) {
            if (!token.arbitrated()) {
                continue;
            }
            long owners = java.util.Arrays.stream(Feature.values())
                    .filter(feature -> feature.tokens().contains(token))
                    .count();
            assertEquals(1, owners, () -> token + " must be declared by exactly one feature");
        }
    }

    @Test
    void everyTokenIsDeclaredBySomeFeature() {
        Set<MechanicToken> declared = new HashSet<>();
        for (Feature feature : Feature.values()) {
            declared.addAll(feature.tokens());
        }
        assertEquals(Set.of(MechanicToken.values()), declared,
                "every mechanic token must be owned by at least one feature");
    }
}

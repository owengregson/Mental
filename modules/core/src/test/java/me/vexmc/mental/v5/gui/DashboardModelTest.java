package me.vexmc.mental.v5.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.feature.Feature;
import org.junit.jupiter.api.Test;

/**
 * The catalog-is-the-registry proof (Task 6.0 constraint 1). {@link
 * DashboardMenu} and {@link FamilyMenu} render their sections and entries off
 * exactly {@link DashboardModel}; these assertions therefore pin the real render
 * path. The load-bearing one is {@link #everyNonInfraFeatureIsSurfaced()}: a new
 * {@link Feature} constant is surfaced in the GUI with ZERO edits to any GUI
 * class — if it ever were not, this test fails.
 */
class DashboardModelTest {

    /** Every non-infrastructure feature, derived independently of the model under test. */
    private static Set<Feature> expectedNonInfra() {
        return Arrays.stream(Feature.values())
                .filter(feature -> !feature.infrastructure())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Feature.class)));
    }

    @Test
    void everyNonInfraFeatureIsSurfaced() {
        // The proof: the union of every dashboard section's entries is EXACTLY
        // the set of non-infrastructure descriptors — nothing missing, nothing
        // extra. Adding a Feature constant makes this hold with no GUI edit;
        // failing to route one would drop it from this equality.
        assertEquals(expectedNonInfra(), DashboardModel.allSurfaced(),
                "the dashboard must surface every non-infrastructure feature — a new "
                        + "descriptor is unreachable in the GUI otherwise");
    }

    @Test
    void sectionsAreExactlyTheFamilies() {
        assertEquals(List.of(Family.values()), DashboardModel.sections());
    }

    @Test
    void categoryRowsCoverEveryCategoryExactlyOnce() {
        // The home-layer reachability guarantee, tier one: every Category appears
        // on exactly one home row (three then two — the five-tile home shape), so
        // a new category constant forces a home-row edit rather than silently
        // vanishing from the dashboard.
        List<Category> flat = DashboardModel.categoryRows().stream().flatMap(List::stream).toList();
        assertEquals(EnumSet.allOf(Category.class),
                EnumSet.copyOf(flat),
                "every category must appear on a home row");
        assertEquals(Category.values().length, flat.size(),
                "no category may appear on two home rows");
        assertEquals(List.of(3, 2),
                DashboardModel.categoryRows().stream().map(List::size).toList(),
                "the home is exactly five tiles — a row of three over a row of two");
    }

    @Test
    void categoriesCoverEveryFamilyExactlyOnce() {
        // Tier two: every Family appears in exactly one category, so a family can
        // never be silently unreachable from the home through the category layer,
        // and Category.of(family) — the FamilyMenu back anchor — is total.
        List<Family> flat = Arrays.stream(Category.values())
                .flatMap(category -> category.families().stream()).toList();
        assertEquals(EnumSet.allOf(Family.class),
                EnumSet.copyOf(flat),
                "every family must belong to a category");
        assertEquals(Family.values().length, flat.size(),
                "no family may appear in two categories");
        for (Family family : Family.values()) {
            assertTrue(Category.of(family).families().contains(family),
                    "Category.of must return the category listing " + family);
        }
    }

    @Test
    void onlySystemIsFamilyless() {
        // SYSTEM fronts the compatibility/debug/reload trio instead of families —
        // every other category must actually lead somewhere.
        for (Category category : Category.values()) {
            assertEquals(category == Category.SYSTEM, category.families().isEmpty(),
                    category + " family list shape is wrong");
        }
    }

    @Test
    void everyCategoryCarriesRenderableCopy() {
        for (Category category : Category.values()) {
            assertFalse(category.displayName().isBlank(), category + " has no display name");
            assertFalse(category.blurb().isBlank(), category + " has no blurb");
            assertFalse(category.iconName().isBlank(), category + " has no icon name");
        }
    }

    @Test
    void infrastructureDescriptorsAreNeverSurfaced() {
        // Always-on infrastructure (anticheat coexistence) carries no
        // modules.* toggle and is configured on its own screen — it must never
        // appear as a family toggle.
        Set<Feature> surfaced = DashboardModel.allSurfaced();
        for (Feature feature : Feature.values()) {
            if (feature.infrastructure()) {
                assertFalse(surfaced.contains(feature),
                        feature + " is infrastructure and must not be a family toggle");
            }
        }
    }

    @Test
    void entriesPartitionByFamilyWithoutDuplication() {
        // Every entry lands under its own family, and the per-family lists sum to
        // the surfaced set with no feature counted twice.
        int total = 0;
        for (Family family : DashboardModel.sections()) {
            for (Feature feature : DashboardModel.entries(family)) {
                assertEquals(family, feature.family(), feature + " listed under the wrong section");
                assertFalse(feature.infrastructure(), feature + " is infrastructure yet listed as an entry");
                total++;
            }
        }
        assertEquals(DashboardModel.allSurfaced().size(), total,
                "a feature is surfaced in more than one section");
    }

    @Test
    void everySurfacedFeatureCarriesRenderableCopy() {
        // A surfaced feature the render can actually paint: the toggle reads the
        // descriptor's own display name, blurb, and icon name.
        for (Feature feature : DashboardModel.allSurfaced()) {
            assertFalse(feature.displayName().isBlank(), feature + " has no display name");
            assertFalse(feature.blurb().isBlank(), feature + " has no blurb");
            assertFalse(feature.iconName().isBlank(), feature + " has no icon name");
        }
    }

    @Test
    void everySectionCarriesRenderableCopy() {
        for (Family family : DashboardModel.sections()) {
            assertFalse(family.displayName().isBlank(), family + " has no display name");
            assertFalse(family.blurb().isBlank(), family + " has no blurb");
            assertFalse(family.iconName().isBlank(), family + " has no icon name");
        }
    }

    @Test
    void comboReachHandicapRendersUnderCombo() {
        // The 2.4.4 promotion (field report 2): the reach handicap is a real Feature
        // now, so it appears and toggles in the GUI with ZERO GUI edits — under the
        // COMBO family (Combo Solver), beside Solve Horizontal KB. Before this it was
        // a config sub-record the GUI structurally could not show.
        assertTrue(DashboardModel.entries(Family.COMBO).contains(Feature.COMBO_REACH_HANDICAP),
                "COMBO_REACH_HANDICAP must render as a toggle under the COMBO family");
        assertTrue(DashboardModel.allSurfaced().contains(Feature.COMBO_REACH_HANDICAP),
                "COMBO_REACH_HANDICAP must be surfaced in the dashboard");
    }

    @Test
    void theKnockbackSectionHasEntries() {
        // The knockback screen also carries the profile picker; its toggle list
        // must still be non-empty (the engine + source features live there).
        assertTrue(DashboardModel.entries(Family.KNOCKBACK).size() > 0);
    }
}

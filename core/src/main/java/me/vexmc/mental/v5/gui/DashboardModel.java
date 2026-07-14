package me.vexmc.mental.v5.gui;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.feature.Feature;

/**
 * The dashboard's structure, derived <em>entirely</em> from the descriptor
 * registry — there is no hand-authored, string-keyed parallel catalog (Task 6.0
 * constraint 1). The dashboard's sections are exactly {@link Family#values()};
 * each section's entries are exactly the non-infrastructure {@link Feature}s that
 * declare that family. Display copy (title, blurb, icon) rides on the descriptors
 * themselves — {@link Family} for a section, {@link Feature} for an entry — so a
 * new {@code Feature} (or {@code Family}) constant appears in the GUI with zero
 * edits to any GUI class.
 *
 * <p>{@link DashboardMenu} and {@link FamilyMenu} render off exactly these
 * methods, so {@code DashboardModelTest} — which asserts {@link #allSurfaced()}
 * equals every non-infrastructure descriptor — is a proof about the real render
 * path, not a parallel re-derivation. The always-on infrastructure descriptor
 * (anticheat coexistence) carries no {@code modules.*} toggle and is
 * configured on its own screen, so it is deliberately excluded here.</p>
 */
public final class DashboardModel {

    private DashboardModel() {}

    /** The dashboard's family sections, in {@link Family} declaration order. */
    public static List<Family> sections() {
        return List.of(Family.values());
    }

    /**
     * The home screen's family layout, grouped into rows by concern: the engine
     * (knockback + delivery), the era combat rules, and the cosmetic/loot pair.
     * The home renders one centred row per group. Every {@link Family} appears in
     * exactly one row — {@code DashboardModelTest} pins that, so a new family can
     * never be silently unreachable from the home (the reachability guarantee at
     * the home layer). The FEEDBACK and LOOT tiles open dedicated screens; the
     * rest open the generic {@link FamilyMenu}.
     */
    public static List<List<Family>> homeRows() {
        return List.of(
                List.of(Family.KNOCKBACK, Family.DELIVERY),
                List.of(Family.DAMAGE, Family.CADENCE, Family.SUSTAIN,
                        Family.LOADOUT, Family.COMBO, Family.POTS),
                List.of(Family.FEEDBACK, Family.LOOT));
    }

    /**
     * The togglable features in {@code family} (every non-infrastructure
     * {@link Feature} that declares it), in {@link Feature} declaration order —
     * the entries {@link FamilyMenu} paints as on/off toggles.
     */
    public static List<Feature> entries(Family family) {
        return Arrays.stream(Feature.values())
                .filter(feature -> feature.family() == family && !feature.infrastructure())
                .toList();
    }

    /**
     * Every feature the dashboard surfaces across all of its sections. By
     * construction this is the union of {@link #entries(Family)} over {@link
     * #sections()}; the test pins it to equal every non-infrastructure feature,
     * which is what guarantees a new descriptor is never silently unreachable.
     */
    public static Set<Feature> allSurfaced() {
        return sections().stream()
                .flatMap(family -> entries(family).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

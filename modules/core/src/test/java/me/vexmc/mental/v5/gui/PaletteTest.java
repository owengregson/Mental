package me.vexmc.mental.v5.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import me.vexmc.mental.platform.PaneColor;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.preset.PresetKind;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

/**
 * Pins that every screen resolves a colour identity — a new {@link Family} can
 * never ship colourless (§2.2). The exhaustive {@code switch} in {@link Palette}
 * already makes a missing family a compile error; these tests pin uniqueness and
 * the gallery's kind-follows-family rule.
 */
class PaletteTest {

    @Test
    void everyFamilyHasATheme() {
        for (Family family : Family.values()) {
            Palette.Theme theme = Palette.of(family);
            assertNotNull(theme.pane(), family + " has no pane colour");
            assertNotNull(theme.accent(), family + " has no accent colour");
        }
    }

    @Test
    void familyPanesAreUnique() {
        Set<PaneColor> panes = EnumSet.noneOf(PaneColor.class);
        for (Family family : Family.values()) {
            panes.add(Palette.of(family).pane());
        }
        assertEquals(Family.values().length, panes.size(),
                "every family must own a distinct pane colour");
    }

    @Test
    void familyAccentsAreUnique() {
        Set<NamedTextColor> accents = new HashSet<>();
        for (Family family : Family.values()) {
            accents.add(Palette.of(family).accent());
        }
        assertEquals(Family.values().length, accents.size(),
                "every family must own a distinct accent colour");
    }

    @Test
    void galleryThemesFollowTheirKind() {
        assertEquals(Palette.of(Family.KNOCKBACK), Palette.gallery(PresetKind.KNOCKBACK));
        assertEquals(Palette.of(Family.FEEDBACK), Palette.gallery(PresetKind.EFFECTS));
    }

    @Test
    void everyCategoryHasATheme() {
        for (Category category : Category.values()) {
            Palette.Theme theme = Palette.category(category);
            assertNotNull(theme.pane(), category + " has no pane colour");
            assertNotNull(theme.accent(), category + " has no accent colour");
        }
    }

    @Test
    void familyCategoriesBorrowTheirLeadFamilyIdentity() {
        // The category tile's colour is the colour the admin lands on one click
        // later — the lead (first-listed) family's theme; SYSTEM stays neutral.
        for (Category category : Category.values()) {
            if (category == Category.SYSTEM) {
                assertEquals(Palette.system(), Palette.category(category));
                continue;
            }
            assertEquals(Palette.of(category.families().get(0)), Palette.category(category),
                    category + " must carry its lead family's identity");
        }
    }
}

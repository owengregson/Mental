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
}

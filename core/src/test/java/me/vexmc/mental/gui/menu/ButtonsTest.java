package me.vexmc.mental.gui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ButtonsTest {

    @Test
    void shortTextStaysOnOneLine() {
        assertEquals(List.of("Short blurb"), Buttons.wrap("Short blurb"));
    }

    @Test
    void longTextWrapsAtWordBoundariesAndLosesNothing() {
        String text = "Remove the 1.9 attack cooldown so spam-clicking deals full "
                + "charge damage on every single swing across the version range";
        List<String> lines = Buttons.wrap(text);

        assertTrue(lines.size() > 1, "long text should wrap onto multiple lines");
        for (String line : lines) {
            // Each line stays within the width unless it is a single over-long word.
            assertTrue(line.length() <= 42 || !line.contains(" "),
                    "line over width with a break opportunity: '" + line + "'");
        }
        // Greedy wrap preserves every word in order, joined by single spaces.
        assertEquals(text, String.join(" ", lines));
    }

    @Test
    void aSingleOverlongWordIsNeverSplit() {
        String word = "Supercalifragilisticexpialidocious_pneumonoultramicroscopicsilicovolcanoconiosis";
        assertEquals(List.of(word), Buttons.wrap(word));
    }
}

package me.vexmc.mental.v5.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import me.vexmc.mental.platform.PaneColor;
import org.junit.jupiter.api.Test;

/**
 * Cell-by-cell proof of the computed background frame (§2.3). The accent used
 * throughout is {@link PaneColor#RED}, distinct from the {@code GRAY}/
 * {@code LIGHT_GRAY} fill so an accent cell is unmistakable.
 */
class PanePatternTest {

    private static final PaneColor ACCENT = PaneColor.RED;

    /** The four hand-computed reference grids, keyed by row count. */
    private static final String[] GRID_3 = {
        "AlggggglA",
        "lgggggggl",
        "AlggggglA",
    };
    private static final String[] GRID_4 = {
        "AlggggglA",
        "lgggggggl",
        "lgggggggl",
        "AlggggglA",
    };
    private static final String[] GRID_5 = {
        "AlggggglA",
        "lgggggggl",
        "AgggggggA",
        "lgggggggl",
        "AlggggglA",
    };
    private static final String[] GRID_6 = {
        "AlggggglA",
        "lgggggggl",
        "AgggggggA",
        "AgggggggA",
        "lgggggggl",
        "AlggggglA",
    };

    private static PaneColor decode(char c) {
        return switch (c) {
            case 'A' -> ACCENT;
            case 'l' -> PaneColor.LIGHT_GRAY;
            case 'g' -> PaneColor.GRAY;
            default -> throw new IllegalArgumentException("unknown grid glyph: " + c);
        };
    }

    @Test
    void frameCoversEverySlotForRows3Through6() {
        for (int rows = 3; rows <= 6; rows++) {
            PaneColor[] frame = PanePattern.frame(rows, ACCENT);
            assertEquals(rows * 9, frame.length, "frame must be exactly rows*9 slots");
            for (int slot = 0; slot < frame.length; slot++) {
                assertNotNull(frame[slot], "no slot may be null (rows=" + rows + ", slot=" + slot + ")");
            }
        }
    }

    @Test
    void frameMatchesTheHandComputedGrids() {
        assertGrid(3, GRID_3);
        assertGrid(4, GRID_4);
        assertGrid(5, GRID_5);
        assertGrid(6, GRID_6);
    }

    private static void assertGrid(int rows, String[] grid) {
        PaneColor[] frame = PanePattern.frame(rows, ACCENT);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                PaneColor expected = decode(grid[row].charAt(col));
                assertEquals(expected, frame[row * 9 + col],
                        "rows=" + rows + " row=" + row + " col=" + col);
            }
        }
    }

    @Test
    void cornersAlwaysCarryTheAccent() {
        for (int rows = 3; rows <= 6; rows++) {
            PaneColor[] frame = PanePattern.frame(rows, ACCENT);
            assertEquals(ACCENT, frame[0], "top-left");
            assertEquals(ACCENT, frame[8], "top-right");
            assertEquals(ACCENT, frame[(rows - 1) * 9], "bottom-left");
            assertEquals(ACCENT, frame[(rows - 1) * 9 + 8], "bottom-right");
        }
    }

    @Test
    void sideBarsAppearOnlyOnTallScreens() {
        // Below five rows there is no accent anywhere but the four corners.
        for (int rows = 3; rows <= 4; rows++) {
            PaneColor[] frame = PanePattern.frame(rows, ACCENT);
            for (int slot = 0; slot < frame.length; slot++) {
                boolean corner = slot == 0 || slot == 8
                        || slot == (rows - 1) * 9 || slot == (rows - 1) * 9 + 8;
                if (!corner) {
                    assertNotEquals(ACCENT, frame[slot],
                            "no accent outside corners on a short screen (rows=" + rows + ", slot=" + slot + ")");
                }
            }
        }
    }
}

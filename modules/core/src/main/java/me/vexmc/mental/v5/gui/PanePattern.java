package me.vexmc.mental.v5.gui;

import java.util.Arrays;
import me.vexmc.mental.platform.PaneColor;
import org.jetbrains.annotations.NotNull;

/**
 * The house background pattern — "the Mental frame". Computed, never
 * hand-placed: accent corners, a light-grey fade beside each corner along every
 * edge, an accent mid-bar on the side columns of tall screens, grey everywhere
 * else. Content tiles are drawn OVER the pattern, so the chrome always reads as
 * a deliberate frame and never as noise.
 *
 * <p>Pure slot math with no Bukkit types, so {@code PanePatternTest} pins every
 * cell of the four reference grids without a server.</p>
 */
final class PanePattern {

    private static final int WIDTH = 9;

    private PanePattern() {}

    /**
     * The full {@code rows*9} grid for the standard frame, every cell non-null.
     * Paint rules apply in order (later wins) for {@code rows} in 3..6:
     * fill grey, edge fades beside the corners, side fades, the tall-screen
     * accent mid-bar, then the accent corners.
     */
    static @NotNull PaneColor[] frame(int rows, @NotNull PaneColor accent) {
        PaneColor[] grid = new PaneColor[rows * WIDTH];

        // 1. Every slot grey.
        Arrays.fill(grid, PaneColor.GRAY);

        // 2. Top/bottom edge fades beside the corners.
        grid[idx(0, 1)] = PaneColor.LIGHT_GRAY;
        grid[idx(0, 7)] = PaneColor.LIGHT_GRAY;
        grid[idx(rows - 1, 1)] = PaneColor.LIGHT_GRAY;
        grid[idx(rows - 1, 7)] = PaneColor.LIGHT_GRAY;

        // 3. Side fades below/above the corners.
        grid[idx(1, 0)] = PaneColor.LIGHT_GRAY;
        grid[idx(1, 8)] = PaneColor.LIGHT_GRAY;
        grid[idx(rows - 2, 0)] = PaneColor.LIGHT_GRAY;
        grid[idx(rows - 2, 8)] = PaneColor.LIGHT_GRAY;

        // 4. Side accent bar, only on tall screens: odd heights carry one
        //    mid-row bar; the even six-row screen carries two central rows.
        if (rows >= 5) {
            if (rows % 2 == 1) {
                int mid = (rows - 1) / 2;
                grid[idx(mid, 0)] = accent;
                grid[idx(mid, 8)] = accent;
            }
            if (rows == 6) {
                grid[idx(2, 0)] = accent;
                grid[idx(2, 8)] = accent;
                grid[idx(3, 0)] = accent;
                grid[idx(3, 8)] = accent;
            }
        }

        // 5. Accent corners (they win over the edge/side fades).
        grid[idx(0, 0)] = accent;
        grid[idx(0, 8)] = accent;
        grid[idx(rows - 1, 0)] = accent;
        grid[idx(rows - 1, 8)] = accent;

        return grid;
    }

    private static int idx(int row, int col) {
        return row * WIDTH + col;
    }
}

package me.vexmc.mental.v5.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The clean-room corner/inset geometry, unit-pinned with a stubbed ray sampler:
 * four downward rays inset 0.01 from the 0.6-wide box, the minimum returned, and
 * a 5.0 cap.
 */
class GroundDistanceTest {

    private record Sample(double x, double z) {}

    @Test
    void castsFourCornersInsetPointOhOneFromTheHalfWidth() {
        List<Sample> corners = new ArrayList<>();
        GroundDistance.measure(10.0, -4.0, (x, z) -> {
            corners.add(new Sample(x, z));
            return 1.0;
        });

        assertEquals(4, corners.size(), "exactly four corner rays");
        double inset = GroundDistance.BOX_HALF_WIDTH - GroundDistance.CORNER_INSET; // 0.29
        double eps = 1.0e-9;
        for (Sample corner : corners) {
            assertEquals(0.29, inset, eps);
            assertEquals(inset, Math.abs(corner.x() - 10.0), eps, "x inset from centre");
            assertEquals(inset, Math.abs(corner.z() - (-4.0)), eps, "z inset from centre");
        }
        // All four sign combinations are present.
        assertTrue(corners.contains(new Sample(10.0 - inset, -4.0 - inset)));
        assertTrue(corners.contains(new Sample(10.0 - inset, -4.0 + inset)));
        assertTrue(corners.contains(new Sample(10.0 + inset, -4.0 - inset)));
        assertTrue(corners.contains(new Sample(10.0 + inset, -4.0 + inset)));
    }

    @Test
    void returnsTheMinimumCornerDistance() {
        double[] perCorner = {3.0, 0.75, 2.0, 4.0};
        int[] index = {0};
        double result = GroundDistance.measure(0.0, 0.0, (x, z) -> perCorner[index[0]++]);
        assertEquals(0.75, result, 1.0e-9, "the nearest ground under any corner");
    }

    @Test
    void capsAtTheMaxDistanceWhenNoGroundIsFound() {
        double result = GroundDistance.measure(0.0, 0.0, (x, z) -> 999.0);
        assertEquals(GroundDistance.MAX_DISTANCE, result, 1.0e-9);
    }
}

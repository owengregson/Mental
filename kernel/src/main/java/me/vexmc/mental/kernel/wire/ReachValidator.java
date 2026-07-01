package me.vexmc.mental.kernel.wire;

import java.util.List;

/**
 * Rewound-reach validation, the ClubSpigot-lite shape: pure math over the
 * attacker's eye position and a set of candidate victim positions — the
 * history samples around the instant the attacker actually <em>saw</em>
 * (now − ping − interpolation) plus the live position.
 *
 * <p>Deliberately lenient: the hit passes if <em>any</em> candidate brings
 * the victim's hitbox within {@code maxReach + leniency} of the attacker's
 * eye. This is a sanity gate against blatant reach, not an anticheat —
 * borderline hits always land, and a dedicated anticheat (when present)
 * supersedes it entirely via the gate. Distances are eye-to-box: the
 * closest point of the victim's 0.6 × 1.8 AABB, the same geometry the
 * client targets. Sneaking eye height and hitbox shrinkage are absorbed by
 * the leniency.</p>
 */
public final class ReachValidator {

    /** Standing eye height; sneak deltas are covered by the leniency. */
    public static final double EYE_HEIGHT = 1.62;

    private static final double HALF_WIDTH = 0.3;
    private static final double BOX_HEIGHT = 1.8;

    /** {@code bestDistance} is the closest candidate, for the debug log. */
    public record Verdict(boolean valid, double bestDistance) {}

    private ReachValidator() {}

    public static Verdict validate(
            double eyeX, double eyeY, double eyeZ,
            List<PositionRing.Sample> history,
            double liveX, double liveY, double liveZ,
            double maxReach, double leniency) {

        double best = distanceToBox(eyeX, eyeY, eyeZ, liveX, liveY, liveZ);
        for (PositionRing.Sample sample : history) {
            best = Math.min(best,
                    distanceToBox(eyeX, eyeY, eyeZ, sample.x(), sample.y(), sample.z()));
        }
        return new Verdict(best <= maxReach + leniency, best);
    }

    /** Eye to the closest point of the victim AABB whose feet sit at (x, y, z). */
    static double distanceToBox(double eyeX, double eyeY, double eyeZ, double x, double y, double z) {
        double dx = axisDistance(eyeX, x - HALF_WIDTH, x + HALF_WIDTH);
        double dy = axisDistance(eyeY, y, y + BOX_HEIGHT);
        double dz = axisDistance(eyeZ, z - HALF_WIDTH, z + HALF_WIDTH);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisDistance(double point, double min, double max) {
        if (point < min) {
            return min - point;
        }
        return point > max ? point - max : 0.0;
    }
}

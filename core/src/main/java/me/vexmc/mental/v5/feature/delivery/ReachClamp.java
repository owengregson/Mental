package me.vexmc.mental.v5.feature.delivery;

import java.util.List;
import me.vexmc.mental.kernel.wire.PositionRing;

/**
 * The netty fast path's reach-validation decision, factored out of
 * {@link HitRegistrationUnit#passesReach} so both geometries are pure and
 * unit-pinnable. The reach geometry is core (the combo backstop the servo-lab
 * 243 round exposed needs an eye-to-centre shape the kernel's box-only
 * {@code ReachValidator} does not carry — so the geometry that gates the
 * combo answer lives here, kernel untouched). Two shapes, one seam:
 *
 * <ul>
 *   <li><b>Plain</b> ({@code handicapScale == null}) — eye to the closest point
 *       of the victim's {@code 0.6 × 1.8} AABB, the FULL {@code leniency},
 *       unscaled: byte-for-byte the pre-backstop window. {@link #distanceToBox}
 *       reproduces the kernel {@code ReachValidator}'s math exactly (pinned to
 *       its documented boundary values in {@code ReachClampTest}), so the
 *       non-handicap path is unchanged.</li>
 *   <li><b>Combo reach-handicap backstop</b> ({@code handicapScale != null}) —
 *       when a combo is held against THIS attacker and the handicap lever is
 *       live, accept on EITHER the scaled eye-to-<em>centre</em> threshold
 *       {@code scale·(maxReach + leniency)} OR the honest eye-to-<em>box</em>
 *       floor {@code scale·maxReach}.</li>
 * </ul>
 *
 * <h2>Why the handicap branch scales the leniency AND switches to eye-to-centre</h2>
 *
 * <p>The interaction-audit fix (#6) clamped the window at {@code scale·maxReach +
 * leniency}, eye-to-BOX — at the shipped default ({@code scale 0.8}, {@code
 * maxReach 3.0}, {@code leniency 0.4}) that is {@code 2.8} eye-to-box. The
 * clients the backstop exists for are <em>attribute-blind</em>: their own attack
 * gate is a fixed eye→chest-CENTRE distance {@code ≤ maxReach} (they never read
 * the shortened {@code ENTITY_INTERACTION_RANGE}), which is only {@code ≈ 2.6–2.7}
 * measured eye-to-box at these ranges (the ~0.3–0.4 box/centre gap). So the
 * {@code 2.8} eye-to-box clamp sat ABOVE everything such a client could send —
 * the enforcement leg added zero margin at scale 0.8 (servo-lab 243, S2: the
 * handicap scenarios were statistically identical to the un-handicapped ones).</p>
 *
 * <p>The fix bites by closing both leaks at once:</p>
 * <ol>
 *   <li><b>Scale the leniency</b> → clamp becomes {@code scale·(maxReach +
 *       leniency)} = {@code 0.8·3.4 = 2.72} (was {@code 2.8}).</li>
 *   <li><b>Measure eye-to-centre</b>, the same shape the blind client's gate
 *       uses. Now the {@code 2.72} threshold is a centre distance, so a blind
 *       client's over-reach at eye-to-box {@code 2.7} (eye-to-centre {@code ≈
 *       3.08}) is REJECTED, while its own gate edge (eye-to-centre {@code =
 *       maxReach = 3.0}) is denied too.</li>
 * </ol>
 *
 * <h2>The honest-client floor (2.4.4 — the false-reject the centre shape alone left)</h2>
 *
 * <p>An honest handicapped client's raycast shortens client-side so its picked HIT
 * POINT (a box-<em>surface</em> point) sits at eye-to-box {@code ≤ scale·maxReach}
 * ({@code 2.4} at {@code scale 0.8}). The centre distance is NOT bounded by that: it
 * exceeds the surface distance by up to the box half-diagonal ({@code ≈ 0.995}) on a
 * steep ray (a launched victim answered downward — a near-collinear ray onto the top
 * face, centre {@code ~0.9} below). So an honest steep-angle answer can read eye-to-
 * centre {@code 2.7–3.3} and the centre-only clamp ({@code 2.72}) would false-reject
 * it — the exact swing the handicap is documented to still allow. The fix adds the
 * OR-pass floor: a send within {@code scale·maxReach} eye-to-box always passes,
 * regardless of the centre distance. The floor keeps the blind-client bite intact —
 * at its own gate edge (eye-to-centre {@code = maxReach}) a blind client's eye-to-box
 * is {@code ≈ 2.6–2.7 > 2.4}, so it never reaches the floor and the centre threshold
 * still denies it. At {@code scale 0.6} the centre threshold is {@code 0.6·3.4 = 2.04}
 * and the floor {@code 0.6·3.0 = 1.8} — both well inside the blind client's {@code 3.0}
 * envelope, so it bites hard (S2x's regime).</p>
 */
public final class ReachClamp {

    /** Standing eye height above the feet (the era value the kernel also pins). */
    public static final double EYE_HEIGHT = 1.62;
    /** Victim AABB half-width — the {@code 0.6}-wide hitbox. */
    private static final double HALF_WIDTH = 0.3;
    /** Victim AABB height — feet .. feet + {@code 1.8}. */
    private static final double BOX_HEIGHT = 1.8;
    /** Victim AABB centre height (feet + {@code 0.9}) — the chest point a reach client aims at. */
    public static final double CENTER_HEIGHT = BOX_HEIGHT / 2.0;

    private ReachClamp() {}

    /**
     * Whether the swing passes the reach window: SOME candidate (the live victim
     * position or a rewound history sample) sits within the clamp — the allow-biased
     * closest-candidate rule the ClubSpigot-lite gate keeps.
     *
     * <p>{@code handicapScale == null} is the plain eye-to-box window with the full
     * unscaled leniency, byte-identical to the pre-backstop behaviour. A non-null
     * scale is the combo backstop, which accepts a candidate on EITHER of two bounds:</p>
     * <ol>
     *   <li>the scaled eye-to-<em>centre</em> threshold {@code scale·(maxReach +
     *       leniency)} — the shape the attribute-blind client's own gate uses, so its
     *       over-reach is judged in the same coordinate; OR</li>
     *   <li>the honest eye-to-<em>box</em> floor {@code scale·maxReach} — the hit-point
     *       envelope an honest handicapped client's own shortened raycast can reach. A
     *       send inside it is definitionally an honest handicapped answer, so a
     *       steep-angle ray whose centre distance inflates past bound (1) is never
     *       false-rejected (the 2.4.4 honest-client floor).</li>
     * </ol>
     */
    public static boolean passes(
            double eyeX, double eyeY, double eyeZ,
            List<PositionRing.Sample> history,
            double liveX, double liveY, double liveZ,
            double maxReach, double leniency, Double handicapScale) {
        if (handicapScale == null) {
            // Plain window: full unscaled leniency, eye-to-box — the pre-backstop shape.
            double threshold = maxReach + leniency;
            if (distanceToBox(eyeX, eyeY, eyeZ, liveX, liveY, liveZ) <= threshold) {
                return true;
            }
            for (PositionRing.Sample sample : history) {
                if (distanceToBox(eyeX, eyeY, eyeZ, sample.x(), sample.y(), sample.z()) <= threshold) {
                    return true;
                }
            }
            return false;
        }
        double centreThreshold = handicapScale * (maxReach + leniency); // scale BOTH terms
        double boxFloor = handicapScale * maxReach;                     // the honest self-limit
        if (handicappedCandidatePasses(centreThreshold, boxFloor, eyeX, eyeY, eyeZ, liveX, liveY, liveZ)) {
            return true;
        }
        for (PositionRing.Sample sample : history) {
            if (handicappedCandidatePasses(
                    centreThreshold, boxFloor, eyeX, eyeY, eyeZ, sample.x(), sample.y(), sample.z())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A single candidate passes the handicap backstop on EITHER the scaled centre
     * threshold OR the honest eye-to-box floor (see {@link #passes}). The floor never
     * admits a blind client's over-reach: at its own (unshortened) gate edge the blind
     * client's eye-to-box exceeds {@code scale·maxReach}, so only the centre threshold
     * judges it — while an honest handicapped answer, always within {@code scale·maxReach}
     * eye-to-box by construction, passes the floor regardless of its centre inflation.
     */
    private static boolean handicappedCandidatePasses(
            double centreThreshold, double boxFloor,
            double eyeX, double eyeY, double eyeZ, double x, double y, double z) {
        return distanceToCenter(eyeX, eyeY, eyeZ, x, y, z) <= centreThreshold
                || distanceToBox(eyeX, eyeY, eyeZ, x, y, z) <= boxFloor;
    }

    /**
     * Eye to the closest point of the victim AABB whose feet sit at {@code (x, y, z)}
     * — the plain shape, identical to the kernel {@code ReachValidator.distanceToBox}.
     */
    public static double distanceToBox(
            double eyeX, double eyeY, double eyeZ, double x, double y, double z) {
        double dx = axisDistance(eyeX, x - HALF_WIDTH, x + HALF_WIDTH);
        double dy = axisDistance(eyeY, y, y + BOX_HEIGHT);
        double dz = axisDistance(eyeZ, z - HALF_WIDTH, z + HALF_WIDTH);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Eye to the victim's AABB centre (feet + {@link #CENTER_HEIGHT}) — the handicap
     * shape, the same eye→chest-centre distance an attribute-blind client's own
     * attack gate uses.
     */
    public static double distanceToCenter(
            double eyeX, double eyeY, double eyeZ, double x, double y, double z) {
        double dx = eyeX - x;
        double dy = eyeY - (y + CENTER_HEIGHT);
        double dz = eyeZ - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisDistance(double point, double min, double max) {
        if (point < min) {
            return min - point;
        }
        return point > max ? point - max : 0.0;
    }
}

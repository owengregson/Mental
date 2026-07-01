package me.vexmc.mental.kernel.math;

/**
 * The 1.7/1.8 melee reach and hitbox-margin constants.
 *
 * <p>These are the single source of truth for both levers this module can pull on
 * a modern server:</p>
 *
 * <ul>
 *   <li>the {@code ENTITY_INTERACTION_RANGE} attribute (1.20.5+), whose era base
 *       is {@link #MAX_REACH} blocks; and</li>
 *   <li>the {@code ATTACK_RANGE} item data component (1.21.5+), whose era shape is
 *       {@code (min={@link #MIN_REACH}, max={@link #MAX_REACH},
 *       minCreative={@link #MIN_CREATIVE_REACH}, maxCreative={@link #MAX_CREATIVE_REACH},
 *       hitboxMargin={@link #HITBOX_MARGIN}, mobFactor={@link #MOB_FACTOR})}.</li>
 * </ul>
 *
 * <p>The values mirror OldCombatMechanics' {@code attack-range} defaults (min 0,
 * max 3.0, minCreative 0, maxCreative 4.0, hitboxMargin 0.1, mobFactor 1.0), which
 * in turn restore the classic 3-block survival reach and the ~0.1 hit-detection
 * grow of the era player AABB.</p>
 *
 * <h2>Honest limit (not encoded here, but the reason these are the ceiling)</h2>
 * <p>The CLIENT picks the melee target and sends a fixed entity id; the server
 * resolves it verbatim with no server-side raytrace on the attack path. So these
 * constants tune the reach GATE and the targeting MARGIN only — they cannot
 * reproduce the 1.7 client's wider target-selection geometry. The modern client's
 * melee targeting is already close to 1.7, so the net effect is era-correct reach
 * distance plus a small hitbox margin (1.21.5+).</p>
 */
public final class EraReach {

    /** Era survival melee reach (eye-to-AABB, blocks). */
    public static final double MAX_REACH = 3.0;

    /** Era minimum melee reach (no lower bound on the attack window). */
    public static final double MIN_REACH = 0.0;

    /** Era creative minimum melee reach. */
    public static final double MIN_CREATIVE_REACH = 0.0;

    /** Era creative melee reach (the classic +1-block creative bonus). */
    public static final double MAX_CREATIVE_REACH = 4.0;

    /** Era hit-detection grow margin on the targeted entity AABB (1.21.5+ only). */
    public static final double HITBOX_MARGIN = 0.1;

    /** Era mob-factor (no scaling of the reach against mobs). */
    public static final double MOB_FACTOR = 1.0;

    /**
     * Upper bound for the {@code ENTITY_INTERACTION_RANGE} attribute base — the
     * creative reach, so a creative player is never tightened below their (already
     * era-correct) reach when the band clamps an inflated third-party value.
     */
    public static final double MAX_INTERACTION_RANGE = MAX_CREATIVE_REACH;

    private EraReach() {}

    /**
     * Clamps an interaction-range value into the sane melee band {@code [0,
     * {@link #MAX_INTERACTION_RANGE}]}. Used when the era base is applied to the
     * attribute: a vanilla {@code 3.0} passes straight through, while a third-party
     * inflation is pulled back to the ceiling rather than left wildly long.
     */
    public static double clampInteractionRange(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, MAX_INTERACTION_RANGE);
    }
}

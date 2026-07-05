package me.vexmc.mental.v5.config.settings;

import me.vexmc.mental.kernel.combo.ComboRules;
import me.vexmc.mental.kernel.math.PocketServoConfig;
import me.vexmc.mental.kernel.math.TargetMode;

/**
 * The combo-hold module's tunables (combo-hold §4/§3.2b) — the detector
 * thresholds and the pocket-servo knobs in one immutable record. Split cleanly
 * into the two kernel values it feeds: {@link #rules()} for the {@code
 * ComboTracker} and {@link #servo()} for the {@code PocketServo} solve.
 *
 * <p>Every default is the design's, and the whole record is a byte-identical
 * no-op contract only because the MODULE defaults OFF (strictly server-opt-in,
 * owner decision §6): {@code target 2.75} comes from the reach-triangle (§2, not
 * tuned), and the clamps {@code [0.8, 1.2]} are the honesty boundary — past them
 * the pocket is honestly lost and era physics wins.</p>
 *
 * <p><b>Precision-round knobs (§3.2b).</b> {@code targetMode} is {@code ANCHOR}
 * by default — the exposure-budget dynamic target is computed and pushed to the
 * debug sink per hit but NOT used, so the lab round can calibrate the geometry
 * before flipping it to {@code DYNAMIC}; {@code hitCap} (2.95) is the dynamic
 * target's upper clamp (the practical hittable edge).</p>
 *
 * <p><b>Reach handicap (§1, the deferred sub-feature).</b> An OPT-IN-within-the-
 * opt-in lever that scales DOWN the interaction-range attribute of a victim while
 * their combo is active, so a launched victim's raycast can no longer answer.
 * 1.20.5+ only (the attribute is client-synced there); the servo remains the
 * PRIMARY mechanism — it steers spacing, the handicap only tightens retaliation.
 * {@link ReachHandicap} defaults OFF so the whole record is still a byte-identical
 * no-op contract under the module toggle.</p>
 */
public record ComboSettings(
        int minHits,
        int maxGapTicks,
        int groundedRunTicks,
        double blowoutBlocks,
        double target,
        double gain,
        double minFactor,
        double maxFactor,
        int windowTicks,
        TargetMode targetMode,
        double hitCap,
        ReachHandicap reachHandicap) {

    /**
     * The combo-hold reach-handicap sub-feature (§1). While a combo is active the
     * victim's entity-interaction-range attribute is scaled by {@link #scale()}
     * through an additive modifier (never a base rewrite), applied on combo start
     * and removed on every end reason. {@code enabled} defaults FALSE — opt-in
     * inside the opt-in module — and {@code scale} lives in {@code [0.5, 1.0]}
     * (a handicap only ever shortens reach; {@code 0.8} takes the era 3.0 to 2.4).
     */
    public record ReachHandicap(boolean enabled, double scale) {

        /** OFF, era-3.0-to-2.4 when switched on — the sub-feature is a no-op by default. */
        public static final ReachHandicap DEFAULTS = new ReachHandicap(false, 0.8);
    }

    /** The design defaults (§3.1/§3.2/§3.2b). The MODULE toggle — not this record — is what makes it a no-op. */
    public static final ComboSettings DEFAULTS =
            new ComboSettings(3, 20, 10, 6.0, 2.75, 1.0, 0.8, 1.2, 10,
                    TargetMode.ANCHOR, PocketServoConfig.DEFAULT_HIT_CAP, ReachHandicap.DEFAULTS);

    /** The detector thresholds the {@code ComboTracker} reads. */
    public ComboRules rules() {
        return new ComboRules(minHits, maxGapTicks, groundedRunTicks, blowoutBlocks);
    }

    /** The active servo config the {@code PocketServo} solve reads (never INACTIVE — the caller gates that). */
    public PocketServoConfig servo() {
        return PocketServoConfig.of(target, gain, minFactor, maxFactor, windowTicks, targetMode, hitCap);
    }
}

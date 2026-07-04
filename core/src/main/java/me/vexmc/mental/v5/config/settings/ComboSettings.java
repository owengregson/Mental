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
        double hitCap) {

    /** The design defaults (§3.1/§3.2/§3.2b). The MODULE toggle — not this record — is what makes it a no-op. */
    public static final ComboSettings DEFAULTS =
            new ComboSettings(3, 20, 10, 6.0, 2.75, 1.0, 0.8, 1.2, 10,
                    TargetMode.ANCHOR, PocketServoConfig.DEFAULT_HIT_CAP);

    /** The detector thresholds the {@code ComboTracker} reads. */
    public ComboRules rules() {
        return new ComboRules(minHits, maxGapTicks, groundedRunTicks, blowoutBlocks);
    }

    /** The active servo config the {@code PocketServo} solve reads (never INACTIVE — the caller gates that). */
    public PocketServoConfig servo() {
        return PocketServoConfig.of(target, gain, minFactor, maxFactor, windowTicks, targetMode, hitCap);
    }
}

package me.vexmc.mental.v5.config.settings;

import me.vexmc.mental.kernel.combo.ComboRules;
import me.vexmc.mental.kernel.math.PocketServoConfig;

/**
 * The combo-hold module's tunables (combo-hold §4) — the detector thresholds and
 * the pocket-servo knobs in one immutable record. Split cleanly into the two
 * kernel values it feeds: {@link #rules()} for the {@code ComboTracker} and
 * {@link #servo()} for the {@code PocketServo} solve.
 *
 * <p>Every default is the design's, and the whole record is a byte-identical
 * no-op contract only because the MODULE defaults OFF (strictly server-opt-in,
 * owner decision §6): {@code target 2.75} comes from the reach-triangle (§2, not
 * tuned), and the clamps {@code [0.8, 1.2]} are the honesty boundary — past them
 * the pocket is honestly lost and era physics wins.</p>
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
        int windowTicks) {

    /** The design defaults (§3.1/§3.2). The MODULE toggle — not this record — is what makes it a no-op. */
    public static final ComboSettings DEFAULTS =
            new ComboSettings(3, 20, 10, 6.0, 2.75, 1.0, 0.8, 1.2, 10);

    /** The detector thresholds the {@code ComboTracker} reads. */
    public ComboRules rules() {
        return new ComboRules(minHits, maxGapTicks, groundedRunTicks, blowoutBlocks);
    }

    /** The active servo config the {@code PocketServo} solve reads (never INACTIVE — the caller gates that). */
    public PocketServoConfig servo() {
        return PocketServoConfig.of(target, gain, minFactor, maxFactor, windowTicks);
    }
}

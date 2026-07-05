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
 * owner decision §6): {@code target 2.85} is the data-backed anchor (the lab's
 * 71-combo round — every one of the five longest combos held 2.81–2.86, the
 * [2.8, 2.92) band broke combos 1.3%/hit vs 5.9% at the cap edge, and the old
 * 2.75 was unreachable at signature scale so it was never a regulated
 * equilibrium), and the clamps {@code [0.8, 1.2]} are the honesty boundary —
 * past them the pocket is honestly lost and era physics wins.</p>
 *
 * <p><b>Precision-round knobs (§3.2b).</b> {@code targetMode} is {@code ANCHOR}
 * by default — the exposure-budget dynamic target is computed and pushed to the
 * debug sink per hit but NOT used, so the lab round can calibrate the geometry
 * before flipping it to {@code DYNAMIC}; {@code hitCap} (2.95) is the dynamic
 * target's upper clamp (the practical hittable edge).</p>
 *
 * <p><b>Reach handicap.</b> The reach handicap moved OUT of this record in 2.4.4:
 * it is now its own {@code modules.combo-reach-handicap} feature carrying a flat
 * {@link me.vexmc.mental.v5.config.settings.ReachHandicapSettings} (just the scale;
 * the enable dissolved into the module toggle), so it appears and toggles in the
 * GUI like every other feature. It still rides the combo transitions — it only
 * engages while a combo is held — so it depends on COMBO_HOLD being on.</p>
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

    /** The design defaults (§3.1/§3.2/§3.2b; target 2.85 per the target-v2 retune).
     *  The MODULE toggle — not this record — is what makes it a no-op. */
    public static final ComboSettings DEFAULTS =
            new ComboSettings(3, 20, 10, 6.0, 2.85, 1.0, 0.8, 1.2, 10,
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

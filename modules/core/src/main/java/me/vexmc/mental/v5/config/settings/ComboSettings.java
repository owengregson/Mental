package me.vexmc.mental.v5.config.settings;

import me.vexmc.mental.kernel.combo.ComboRules;
import me.vexmc.mental.kernel.math.PocketServoConfig;
import me.vexmc.mental.kernel.math.TargetMode;

/**
 * The combo-hold module's tunables (combo-hold §4/§3.2b; the 2.4.5 answer-denial
 * redesign) — the detector thresholds and the pocket-servo knobs in one immutable
 * record. Split cleanly into the two kernel values it feeds: {@link #rules()} for
 * the {@code ComboTracker} and {@link #servo()} for the {@code PocketServo} solve.
 *
 * <p>Every default is the design's, and the whole record is a byte-identical no-op
 * contract only because the MODULE defaults OFF (strictly server-opt-in). The
 * detector thresholds ({@code minHits 2} — the SECOND hit fires COMBO START;
 * {@code maxGapTicks}/{@code groundedRunTicks}/{@code blowoutBlocks}) size the
 * chain; the clamps {@code [0.8, 1.2]} are the honesty boundary — past them the
 * pocket is honestly lost and era physics wins. On the min side the loss can be
 * TOTAL: when the solve lands below {@code PocketServo.saturationFloor(minFactor)}
 * (= 2·min − 1) even the clamped shave cannot approach the pocket, and the servo
 * declines to the full era knock instead of shipping a pointless min-factor shave
 * (sprint-fresh hits always saturate there).</p>
 *
 * <p><b>The answer-denial-boundary target (§3.2b).</b> {@code targetMode} is
 * {@link TargetMode#BOUNDARY} by default: the servo lands the victim right at the
 * separation where their reach-back is denied by a hair while the attacker can
 * still reach them. It is computed from the unified reach geometry —
 * {@code victimReach} ({@code R_v}, era 3.0, the victim's answer reach) and
 * {@code attackerReach} ({@code R_a}, era 3.0) — with {@code denyMargin} (the hair
 * past the deny boundary), {@code jitterMargin} (the slack below the attacker's
 * reach), and {@code targetFloor} (the closest the servo will ever pull IN). When
 * the geometry is unmeasurable (no facing), or under {@link TargetMode#STATIC}, the
 * target is {@code staticTarget} (2.85, the lab's held-separation equilibrium). The
 * caller folds the combo reach handicap into {@code R_v} before building the servo
 * config (see {@code KnockbackUnit#effectiveVictimReach}); the record itself always
 * carries the base era reach.</p>
 *
 * <p><b>Reach handicap.</b> The reach handicap is its own {@code
 * modules.combo-reach-handicap} feature (2.4.4) carrying a flat
 * {@link ReachHandicapSettings}. It rides the combo transitions and composes with
 * this servo: shortening the victim's reach drops the deny boundary and opens the
 * keepable pocket. It depends on combo DETECTION — which either keeper (combo-hold
 * or the handicap itself) provides — not on this servo being on.</p>
 */
public record ComboSettings(
        int minHits,
        int maxGapTicks,
        int groundedRunTicks,
        double blowoutBlocks,
        double staticTarget,
        double gain,
        double minFactor,
        double maxFactor,
        int windowTicks,
        TargetMode targetMode,
        double victimReach,
        double attackerReach,
        double denyMargin,
        double jitterMargin,
        double targetFloor) {

    /** The design defaults (§3.1/§3.2/§3.2b). The MODULE toggle — not this record — is what makes it a no-op. */
    public static final ComboSettings DEFAULTS =
            new ComboSettings(2, 20, 10, 6.0,
                    PocketServoConfig.DEFAULT_STATIC_TARGET, 1.0, 0.93, 1.35, 10,
                    TargetMode.BOUNDARY, PocketServoConfig.DEFAULT_REACH, PocketServoConfig.DEFAULT_REACH,
                    PocketServoConfig.DEFAULT_DENY_MARGIN, PocketServoConfig.DEFAULT_JITTER_MARGIN,
                    PocketServoConfig.DEFAULT_TARGET_FLOOR);

    /** The detector thresholds the {@code ComboTracker} reads. */
    public ComboRules rules() {
        return new ComboRules(minHits, maxGapTicks, groundedRunTicks, blowoutBlocks);
    }

    /**
     * The active servo config with the BASE victim reach (never INACTIVE — the
     * caller gates that). Used where no handicap fold applies (tests, re-derivation
     * without a live handicap).
     */
    public PocketServoConfig servo() {
        return servo(victimReach);
    }

    /**
     * The active servo config carrying the given EFFECTIVE victim reach — the caller
     * folds the combo reach handicap into {@code R_v} (era 3.0 → 2.61 at scale 0.87)
     * when that module is live, so the solve sees the reach the victim actually has
     * while their combo is held.
     */
    public PocketServoConfig servo(double victimReachEff) {
        return PocketServoConfig.of(staticTarget, gain, minFactor, maxFactor, windowTicks,
                targetMode, victimReachEff, attackerReach, denyMargin, jitterMargin, targetFloor);
    }
}

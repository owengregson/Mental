package me.vexmc.mental.v5.feature.combo;

import java.util.List;
import java.util.UUID;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.DriftEstimator;
import me.vexmc.mental.kernel.math.PocketServo;
import me.vexmc.mental.kernel.math.PredictorInputs;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.kernel.wire.PositionRing;

/**
 * Assembles the pocket-servo precision predictor inputs (combo-hold §3.2b;
 * precision-derivation §0/§2/§4) from the frozen per-tick state both compute sites
 * share — the victim's and attacker's published {@link PlayerView}s, the
 * {@link PositionRing}, and the {@link LatencyModel}. Reads only immutable
 * published values and ring samples (never a live entity), so it is safe on the
 * attacker's netty thread as well as the victim's region thread; both sites freeze
 * ONE {@link PredictorInputs} from it, so an adopted pre-send and a region
 * recompute see the same truth.
 *
 * <p>Every term degrades independently to its no-op: a missing ring history drops
 * the drift/chase estimate, an unprobed RTT falls back to the view's Bukkit ping,
 * and an ice landing (handled downstream) declines the servo. The measured
 * estimators feed the kernel {@link DriftEstimator} and a simple axis-projected
 * attacker-velocity trend; both are lab-calibration surfaces, so the wiring stays
 * conservative (the estimator's own validity gate and the shrinkage below bound
 * a bad sample).</p>
 */
public final class ComboPredictor {

    /**
     * The drift-estimate shrinkage κ (precision-derivation §2.5) — a held-input
     * flip AT the hit inverts the drift term, so the estimate is trusted at 0.7 to
     * bound that worst case; the per-hit re-solve corrects the residual.
     */
    private static final double DRIFT_SHRINKAGE = 0.7;

    /** How many ring samples the drift estimator reads (≥ 3 deltas for the N=3 mean). */
    private static final int DRIFT_SAMPLES = 5;

    /** How many ring samples the chase trend averages (~3 deltas). */
    private static final int CHASE_SAMPLES = 4;

    private ComboPredictor() {}

    /**
     * Builds the precision inputs for a hit from {@code attackerId} to the victim
     * whose frozen {@code victimView} is given. {@code attackerView} may be null
     * (its RTT then falls back to the model / zero); the positions are the current
     * ring-latest feet positions of both parties (the axis source).
     */
    public static PredictorInputs build(
            UUID attackerId, UUID victimId,
            double attackerX, double attackerZ,
            double victimX, double victimZ,
            PlayerView victimView, PlayerView attackerView,
            PositionRing positions, LatencyModel latency) {

        // The attacker→victim unit axis (pointing away from the attacker).
        double dx = victimX - attackerX;
        double dz = victimZ - attackerZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        double ux = len > 1.0e-9 ? dx / len : 0.0;
        double uz = len > 1.0e-9 ? dz / len : 0.0;

        boolean launchGrounded = victimView.grounded();
        double slip = victimView.slipperiness();
        double launchHeight = launchGrounded ? 0.0 : Math.max(0.0, victimView.kinematics().distanceToGround());

        double driftAxis = DRIFT_SHRINKAGE * estimateDrift(positions, victimId, victimView, ux, uz);
        double chaseAxis = estimateChase(positions, attackerId, ux, uz);

        int attackerRtt = rttMillis(latency, attackerId, attackerView);
        int victimRtt = rttMillis(latency, victimId, victimView);

        double yawVsAxis = yawVsAxisDeg(victimView.yaw(), ux, uz);

        return new PredictorInputs(
                launchGrounded, slip, slip, launchHeight,
                driftAxis, chaseAxis, victimView.moveSpeedAttr(),
                attackerRtt, victimRtt,
                Double.NaN,                 // attacker head-top Y: flat-arena constant (dynamic target is OFF by default)
                victimView.eyeHeight(),
                yawVsAxis,
                Double.NaN,                 // victim yaw slew rate: no per-tick source yet (open issue 4)
                victimView.groundedTicks());
    }

    /* ── the measured estimators ──────────────────────────────────────────── */

    /**
     * The victim's held mid-air input, axis-projected (precision-derivation §2.4):
     * the ring's measured per-tick displacements minus the modeled knock decay
     * (the frozen residual carried backward at air drag), fed to the kernel
     * {@link DriftEstimator}. 0 when the history is too short — the drift term drops.
     */
    private static double estimateDrift(
            PositionRing positions, UUID victimId, PlayerView victimView, double ux, double uz) {
        List<PositionRing.Sample> ring = positions.recent(victimId, DRIFT_SAMPLES);
        int deltas = ring.size() - 1;
        if (deltas < 2) {
            return 0.0;
        }
        double rvx = victimView.motion().vx();
        double rvz = victimView.motion().vz();
        double[] measured = new double[deltas];
        double[] knock = new double[deltas];
        for (int i = 0; i < deltas; i++) {
            PositionRing.Sample a = ring.get(i);
            PositionRing.Sample b = ring.get(i + 1);
            measured[i] = (b.x() - a.x()) * ux + (b.z() - a.z()) * uz;
            // The latest delta (i == deltas-1) is the freshest knock move ≈ the
            // residual; older ticks undecay it backward at air drag (free-flight
            // model — a fresh stamp in the window shows as a gated model break).
            int back = (deltas - 1) - i;
            double factor = Math.pow(1.0 / Decay.AIR_DRAG, back);
            knock[i] = (rvx * factor) * ux + (rvz * factor) * uz;
        }
        return DriftEstimator.estimate(measured, knock);
    }

    /**
     * The attacker's measured closing rate, axis-projected (precision-derivation
     * §1.4/§7 issue 7): the recent ring-velocity trend along the axis toward the
     * victim, positive when closing. {@link Double#NaN} when the history is too
     * short — the solve falls back to the 0.2806×attr model.
     */
    private static double estimateChase(PositionRing positions, UUID attackerId, double ux, double uz) {
        List<PositionRing.Sample> ring = positions.recent(attackerId, CHASE_SAMPLES);
        int deltas = ring.size() - 1;
        if (deltas < 1) {
            return Double.NaN;
        }
        double sx = 0.0;
        double sz = 0.0;
        for (int i = 0; i < deltas; i++) {
            sx += ring.get(i + 1).x() - ring.get(i).x();
            sz += ring.get(i + 1).z() - ring.get(i).z();
        }
        return (sx / deltas) * ux + (sz / deltas) * uz;
    }

    /** The full RTT (ms) from the latency model, or the view's Bukkit ping, or 0 (unavailable → no shift). */
    private static int rttMillis(LatencyModel latency, UUID id, PlayerView view) {
        Double probe = latency.forPlayer(id).pingMillis();
        if (probe != null) {
            return (int) Math.round(probe);
        }
        return view != null ? Math.max(0, view.pingMillis()) : -1;
    }

    /**
     * The absolute angle (deg, [0, 180]) between the victim's facing and the
     * direction to the attacker — the dynamic target's facing input. The axis
     * {@code u} points from the attacker to the victim, so the direction TO the
     * attacker is {@code −u}; a victim facing the attacker reads ~0°.
     */
    private static double yawVsAxisDeg(float victimYaw, double ux, double uz) {
        // Minecraft yaw 0 faces +z, increasing clockwise toward −x: the facing unit
        // vector is (−sin, cos). The direction to the attacker is −u.
        double yawRad = Math.toRadians(victimYaw);
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double dot = fx * (-ux) + fz * (-uz);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    /* ── the debug sink (precision-derivation §7.2) ──────────────────────────── */

    /**
     * The per-hit debug line the lab round parses (precision-derivation §7.2): every
     * predictor input and the resolved solve, space-separated {@code key=value}
     * pairs. The dynamic target rides here (NOT the journal) so the lab can
     * calibrate the exposure-budget geometry before the default flips; the journal
     * carries only the applied σ (comboFactor).
     */
    public static String debugLine(UUID victimId, UUID attackerId, PredictorInputs inputs, PocketServo.Solution s) {
        return "combo-servo"
                + " victim=" + victimId + " attacker=" + attackerId
                + " d0=" + fmt(s.d0()) + " R=" + fmt(s.residualCarry()) + " F=" + fmt(s.freshEra())
                + " V0=" + fmt(s.verticalStamp()) + " launchGrounded=" + s.launchGrounded()
                + " slip=" + fmt(s.launchSlip())
                + " wPrime=" + s.windowTicks() + " tStar=" + s.horizonTicks()
                + " shiftV=" + s.shiftVictimTicks() + " airTime=" + s.airTicks()
                + " aHat=" + fmt(inputs.driftAlongAxis())
                + " chase=" + fmt(s.chaseRate()) + (s.chaseMeasured() ? "(measured)" : "(model)")
                + " targetAnchor=" + fmt(s.anchor()) + " targetDyn=" + fmt(s.dynamicTarget())
                + " targetUsed=" + fmt(s.target())
                + " sigmaStar=" + fmt(s.sigmaStar()) + " sigma=" + fmt(s.sigma())
                + " dNext=" + fmt(s.predictedDNext()) + " declined=" + s.declined();
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}

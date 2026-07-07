package me.vexmc.mental.v5.feature.combo;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.DriftEstimator;
import me.vexmc.mental.kernel.math.PocketServo;
import me.vexmc.mental.kernel.math.PredictorInputs;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.TickStamp;
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
 * recompute see the same truth. It also carries one small per-victim lab cache
 * ({@link #MEMORY}) — the V2 dynamic target's cross-hit smoothing state (target-v2
 * repair #2) — concurrent because both delivery seams commit to it.
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

    /**
     * The shortest inter-hit gap a POST-hit chase window may measure (servo-lab
     * 2.4.5). The era immunity floor keeps landed knocks ≥ ~10 ticks apart
     * (maxHurtResistantTime/2, boundary reads 9), so any shorter "window" is the
     * same hit re-solved at the second delivery seam (a suppressed pre-send
     * followed by the region recompute lands 0–1 ticks apart) — an artifact, never
     * a real window. Half the era floor keeps a wide honesty margin.
     */
    private static final int MIN_WINDOW_GAP_TICKS = 5;

    /**
     * The longest gap a window may measure: 2× the default max-gap (20) that ends
     * every default combo. A live combo past a 40-tick gap exists only under
     * widened test configs, where the "window" no longer measures re-chase but
     * idle wandering — the EMAs keep their prior instead.
     */
    private static final int MAX_WINDOW_GAP_TICKS = 40;

    /**
     * The per-victim pocket-servo memory, carried hit-to-hit within one combo and
     * cleared on every combo END ({@link #forget}). Two tenants: the V2 dynamic
     * target's smoothing state (target-v2 repair #2 — {@code chaseEma}/{@code
     * dynamicTarget}, committed by {@link #remember}, meaningful only under
     * {@code target-mode: dynamic}), and the servo-lab 2.4.5 POST-hit chase window
     * ({@code windowChaseEma} + the previous hit's attacker anchor, committed by
     * {@link #rememberWindow} on EVERY active servo hit — this one is
     * load-bearing: it is the chase the σ* solve prices). Both delivery seams
     * (netty pre-send D1, region compute D2) read and commit it, hence the
     * concurrent map; exactly one seam computes any given hit, so commits are
     * per-hit serialized in practice.
     */
    private static final ConcurrentHashMap<UUID, ServoMemory> MEMORY = new ConcurrentHashMap<>();

    /**
     * One victim's cross-hit servo state. {@code anchorTick} is the tick of the
     * previous servo-solved hit and {@code anchorX/Z} the attacker's feet there —
     * the base of the next POST-hit chase window; {@link TickStamp#NO_TICK}'s
     * value marks "no anchor yet" (a fresh combo).
     */
    private record ServoMemory(
            double chaseEma, double dynamicTarget,
            double windowChaseEma,
            double anchorX, double anchorZ, int anchorTick) {

        static final ServoMemory EMPTY = new ServoMemory(
                Double.NaN, Double.NaN, Double.NaN, 0.0, 0.0, Integer.MIN_VALUE);
    }

    private ComboPredictor() {}

    /**
     * Builds the precision inputs for a hit from {@code attackerId} to the victim
     * whose frozen {@code victimView} is given. {@code attackerView} may be null
     * (its RTT then falls back to the model / zero); the positions are the current
     * ring-latest feet positions of both parties (the axis source); {@code now} is
     * the hit tick — the POST-hit chase window's clock.
     *
     * <p>Pure read: the victim's per-combo servo memory is consumed here (the V2
     * dynamic-target smoothing state, target-v2 repair #2, AND the servo-lab 2.4.5
     * post-hit chase window) but only the seams commit it — {@link #rememberWindow}
     * for the window anchor/EMA on every active servo hit, {@link #remember} for the
     * dynamic-target state — so a re-derivation of the same hit (the ComboSuite's
     * expected-σ build) reads exactly what the production solve read.</p>
     *
     * <p><b>The chase channel (servo-lab 2.4.5).</b> The measured chase fed to the
     * solve is the per-combo EMA of POST-hit windows ({@link
     * PocketServo#windowChaseRate}): the attacker's actual displacement over the
     * just-completed inter-hit gap, axis-projected. The old pre-hit ring-velocity
     * trend read the attacker's at-ring idle/w-tap phase (≈ 0 to negative in every
     * lab cell) while the actual post-hit re-chase measured +1.1…+1.7 blocks per
     * window — under-pricing the chase, driving σ* into the false-low min-clamp
     * mode and shipping every landed hit below era. A fresh combo has no window yet
     * ({@link Double#NaN}) ⇒ the kernel falls back to the §1.4 attribute model.</p>
     */
    public static PredictorInputs build(
            UUID attackerId, UUID victimId,
            double attackerX, double attackerZ,
            double victimX, double victimZ,
            PlayerView victimView, PlayerView attackerView,
            PositionRing positions, LatencyModel latency, TickStamp now) {

        // The attacker→victim unit axis (pointing away from the attacker).
        double dx = victimX - attackerX;
        double dz = victimZ - attackerZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        double ux = len > 1.0e-9 ? dx / len : 0.0;
        double uz = len > 1.0e-9 ? dz / len : 0.0;

        // The launch ground state and the grounded-tick tail (below) are BOTH the
        // combo/precision signal, so both read the session's grounded-tick counter —
        // the one honest source (fed by the packetless physical fallback). Deriving
        // launchGrounded from it (grounded ⟺ the run is ≥ 1 this tick) is byte-
        // identical to the old view.grounded() read for real clients, but stays
        // honest for a packetless victim whose isOnGround() flag lies airborne (the
        // delivery/era baseline view.grounded() keeps that raw flag).
        boolean launchGrounded = victimView.groundedTicks() > 0;
        double slip = victimView.slipperiness();
        double launchHeight = launchGrounded ? 0.0 : Math.max(0.0, victimView.kinematics().distanceToGround());

        double driftAxis = DRIFT_SHRINKAGE * estimateDrift(positions, victimId, victimView, ux, uz);

        // The POST-hit chase window (servo-lab 2.4.5): advance the per-combo EMA
        // with the attacker's measured displacement over the just-completed
        // inter-hit gap. Gaps outside [MIN, MAX] are artifacts (the dual-seam
        // re-solve, or an idle stretch under a widened test config) — the EMA
        // carries its prior unchanged, and a combo with no window yet rides NaN
        // into the kernel's attribute-model fallback.
        ServoMemory memory = MEMORY.getOrDefault(victimId, ServoMemory.EMPTY);
        double chaseAxis = memory.windowChaseEma();
        if (memory.anchorTick() != Integer.MIN_VALUE && now != null && now.known()) {
            long gap = (long) now.value() - memory.anchorTick();
            if (gap >= MIN_WINDOW_GAP_TICKS && gap <= MAX_WINDOW_GAP_TICKS) {
                double measured = PocketServo.windowChaseRate(
                        memory.anchorX(), memory.anchorZ(), attackerX, attackerZ, ux, uz, gap);
                chaseAxis = PocketServo.chaseEma(chaseAxis, measured);
            }
        }

        int attackerRtt = rttMillis(latency, attackerId, attackerView);
        int victimRtt = rttMillis(latency, victimId, victimView);

        double yawVsAxis = yawVsAxisDeg(victimView.yaw(), ux, uz);

        double priorChaseEma = memory.chaseEma();
        double priorDynamicTarget = memory.dynamicTarget();

        return new PredictorInputs(
                launchGrounded, slip, slip, launchHeight,
                driftAxis, chaseAxis, victimView.moveSpeedAttr(),
                attackerRtt, victimRtt,
                Double.NaN,                 // attacker head-top Y: flat-arena constant (dynamic target is OFF by default)
                victimView.eyeHeight(),
                yawVsAxis,
                victimView.yawRateDegPerTick(),   // the measured yaw slew — the V2 turn divisor (target-v2 repair #4)
                victimView.groundedTicks(),
                priorChaseEma, priorDynamicTarget);
    }

    /**
     * Commits the POST-hit chase window state this hit's solve consumed (servo-lab
     * 2.4.5): the advanced window-chase EMA the inputs carried, plus THIS hit's
     * attacker position and tick as the next window's anchor. Called on EVERY
     * active servo hit at whichever delivery seam computed it (a suppressed
     * pre-send followed by the region recompute commits twice; the second sits
     * under {@link #MIN_WINDOW_GAP_TICKS}, so the EMA never double-advances).
     * The V2 dynamic-target tenant is preserved untouched.
     */
    public static void rememberWindow(
            UUID victimId, double attackerX, double attackerZ, TickStamp now, PredictorInputs inputs) {
        if (victimId == null || now == null || !now.known()) {
            return;
        }
        MEMORY.compute(victimId, (id, prior) -> {
            ServoMemory base = prior != null ? prior : ServoMemory.EMPTY;
            return new ServoMemory(
                    base.chaseEma(), base.dynamicTarget(),
                    inputs.chaseAlongAxis(),
                    attackerX, attackerZ, now.value());
        });
    }

    /**
     * Commits the pocket-servo memory this hit produced (target-v2 repair #2): the
     * advanced chase EMA and the emitted dynamic target, so the victim's next hit's
     * V2 target smooths from them. Keyed by victim; a {@link ConcurrentHashMap}
     * because the two delivery seams (netty pre-send D1, region recompute D2) both
     * assemble the solve. A declined solve (NaN chaseEma) leaves the memory alone,
     * so a stray non-combo hit never poisons it. Cleared by {@link #forget} on
     * session teardown. Only meaningful under {@code target-mode: dynamic}; the
     * post-hit chase window tenant is preserved untouched.
     */
    public static void remember(UUID victimId, PocketServo.Solution solution) {
        if (victimId == null || Double.isNaN(solution.chaseEma())) {
            return;
        }
        MEMORY.compute(victimId, (id, prior) -> {
            ServoMemory base = prior != null ? prior : ServoMemory.EMPTY;
            return new ServoMemory(
                    solution.chaseEma(), solution.dynamicTarget(),
                    base.windowChaseEma(),
                    base.anchorX(), base.anchorZ(), base.anchorTick());
        });
    }

    /** Drops the victim's servo memory (session forget hook / combo teardown). */
    public static void forget(UUID victimId) {
        MEMORY.remove(victimId);
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
     * The per-hit debug line the lab round parses (precision-derivation §7.2; the
     * target-v2 sink extension): every predictor input and the resolved solve,
     * space-separated {@code key=value} pairs. The dynamic target rides here (NOT
     * the journal) so the lab can calibrate the exposure-budget geometry before the
     * default flips; the journal carries only the applied σ (comboFactor).
     *
     * <p>The V2 round adds the fields the forensics attribution gaps named
     * (open-gaps 1/2): the victim facing (<b>yawVsAxis</b>) and its measured slew
     * (<b>yawRateHat</b>), the continuous retaliation cost (<b>turn</b>, <b>tAllow
     * = tPing + turn</b>), the smoothed terminal closing (<b>closingEma</b>), and
     * the <b>launchHeight</b> that the reconstruction previously had to assume.</p>
     */
    public static String debugLine(UUID victimId, UUID attackerId, PredictorInputs inputs, PocketServo.Solution s) {
        return "combo-servo"
                + " victim=" + victimId + " attacker=" + attackerId
                + " d0=" + fmt(s.d0()) + " R=" + fmt(s.residualCarry()) + " F=" + fmt(s.freshEra())
                + " V0=" + fmt(s.verticalStamp()) + " launchGrounded=" + s.launchGrounded()
                + " slip=" + fmt(s.launchSlip()) + " launchHeight=" + fmt(inputs.launchHeight())
                + " wPrime=" + s.windowTicks() + " tStar=" + s.horizonTicks()
                + " shiftV=" + s.shiftVictimTicks() + " airTime=" + s.airTicks()
                + " aHat=" + fmt(inputs.driftAlongAxis())
                + " chase=" + fmt(s.chaseRate()) + (s.chaseMeasured() ? "(measured)" : "(model)")
                + " yawVsAxis=" + fmt(inputs.victimYawVsAxisDeg())
                + " yawRateHat=" + fmt(inputs.victimYawRateDegPerTick())
                + " turn=" + fmt(s.turn()) + " tAllow=" + fmt(s.tAllow())
                + " closingEma=" + fmt(s.closingEnd())
                + " targetAnchor=" + fmt(s.anchor()) + " targetDyn=" + fmt(s.dynamicTarget())
                + " targetUsed=" + fmt(s.target())
                + " sigmaStar=" + fmt(s.sigmaStar()) + " sigma=" + fmt(s.sigma())
                + " dNext=" + fmt(s.predictedDNext()) + " declined=" + s.declined();
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}

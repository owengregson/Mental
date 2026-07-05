package me.vexmc.mental.kernel.math;

/**
 * How the pocket servo picks the separation it steers the victim toward
 * (combo-hold §3.2b / precision-derivation §3; the target-v2 round).
 *
 * <ul>
 *   <li>{@link #ANCHOR} — the static config {@code target} (2.85 after the
 *       data-backed retune; see {@code PocketServoConfig}). <b>The shipped
 *       default.</b> The {@link #DYNAMIC} value is still evaluated and pushed to the
 *       debug sink per hit, but the anchor is what the solve uses, so a lab round
 *       can calibrate the exposure-budget geometry before anyone flips it.</li>
 *   <li>{@link #DYNAMIC} — the <b>V2 exposure-budget target</b> ({@link
 *       PocketServo#dynamicTarget}, the forensics/verifier repairs). It picks the
 *       LEAST-aggressive separation {@code T ∈ [anchor, capEff]} whose terminal
 *       exposure integral {@code e(T)} stays within the victim's continuous
 *       retaliation budget {@code tAllow = tPing + turn} — the honest {@code min}
 *       selection (repair #1). An out-drifting victim ({@code closing ≤ 0}) keeps
 *       the anchor (pull-in posture, repair #2); the chase is EMA-smoothed and the
 *       emitted target slew-limited so the noisy estimate no longer coin-flips the
 *       target across the old cliff. Falls back to the anchor whenever a geometry
 *       input is missing. (The v1 single-instant target was journal-only and never
 *       applied — replaced outright.)</li>
 * </ul>
 */
public enum TargetMode {
    ANCHOR,
    DYNAMIC
}

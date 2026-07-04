package me.vexmc.mental.kernel.math;

/**
 * How the pocket servo picks the separation it steers the victim toward
 * (combo-hold §3.2b / precision-derivation §3).
 *
 * <ul>
 *   <li>{@link #ANCHOR} — the static config {@code target} (2.75, the hittability
 *       centre derived from the reach triangle). The safe default: the computed
 *       {@link #DYNAMIC} value is still evaluated and pushed to the debug sink per
 *       hit, but the anchor is what the solve uses, so the lab round can calibrate
 *       the exposure-budget geometry constants (practical reach, interpolation
 *       lag) before the default flips.</li>
 *   <li>{@link #DYNAMIC} — the exposure-budget target (precision-derivation §3.3):
 *       a facing, low-ping victim pushes the target up toward {@code hitCap} to
 *       minimise the terminal exposure the physics floor concedes; a faced-away or
 *       laggy victim relaxes it down toward the anchor, buying rhythm margin. Falls
 *       back to the anchor whenever any geometry input is missing.</li>
 * </ul>
 */
public enum TargetMode {
    ANCHOR,
    DYNAMIC
}

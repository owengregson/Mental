package me.vexmc.mental.kernel.profile;

/**
 * How the modern knockback formula shapes the vertical component of a hit —
 * an additive {@link ModernKnockback} knob, default {@link #VANILLA}.
 *
 * <ul>
 *   <li>{@link #VANILLA} — the Paper 26.1.2 shape Mental has always shipped: a
 *       grounded victim lifts to {@code min(cap, vy·residualVertical + strength)},
 *       an airborne victim's lift is the {@code downward-knockback} toggle
 *       (keep its own vy for the mid-air slam, or lift like a grounded one).</li>
 *   <li>{@link #CT8C_SPLIT} — the Combat Test 8c split (spec §2.5): a grounded
 *       victim gets {@code min(cap, groundedVerticalFactor·strength)} with no
 *       dependence on its own vy, an airborne victim gets {@code min(cap, vyIn +
 *       airborneVerticalFactor·strength)} (an ADD, capped — so a deep fall can
 *       still ship a net-downward knock). The {@code downward-knockback} toggle
 *       is inert under this shape; the split formula owns both ground states.</li>
 * </ul>
 *
 * <p>Additive-only: {@code VANILLA} is the delegating-constructor default, so
 * every existing preset and unit pin constructs under it byte-identically and
 * the pre-shape engine output is bit-for-bit preserved.</p>
 */
public enum VerticalShape {
    VANILLA,
    CT8C_SPLIT
}

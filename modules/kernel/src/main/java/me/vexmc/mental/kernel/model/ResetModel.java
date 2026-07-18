package me.vexmc.mental.kernel.model;

/**
 * The attacker's sprint-reset model at a hit instant (servo dynamic-chase spec,
 * 2026-07-07): how far into a re-acceleration ramp the attacker is, and whether
 * they are sprinting / blocking — the input-derived signal the pocket servo's
 * dynamic chase prices the attacker's close from, instead of a flat rate. Built by
 * {@code ResetModelWire} (D1) from the arrival-order sprint and block packets; a
 * pure immutable value that crosses no plugin API boundary (kernel↔core only).
 *
 * @param phaseTicks ticks since the attacker's last sprint (re-)engage — the ramp
 *                   phase (0 = a fresh w-tap/blockhit, large = a settled sprint).
 * @param sprinting  whether the attacker is currently sprinting (the raw client flag).
 * @param blocking   whether the attacker is holding a sword block — the block-slow
 *                   gate (a legacy-protocol blockhitter crawls; a modern one keeps
 *                   full sprint under Mental).
 * @param known      false until the wire has seen a real reset signal ⇒ the servo
 *                   falls back to the measured-ring / attribute chase.
 */
public record ResetModel(int phaseTicks, boolean sprinting, boolean blocking, boolean known) {

    /** The no-signal value — the servo declines the dynamic chase and uses its fallbacks. */
    public static final ResetModel UNKNOWN = new ResetModel(0, false, false, false);
}

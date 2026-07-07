package me.vexmc.mental.kernel.math;

/**
 * How the pocket servo picks the separation it steers the victim toward
 * (combo-hold §3.2b; the 2.4.5 answer-denial-boundary redesign).
 *
 * <ul>
 *   <li>{@link #BOUNDARY} — the <b>geometric answer-denial target</b> ({@link
 *       PocketServo#boundaryTarget}). <b>The shipped default.</b> It lands the
 *       victim right at the separation where, at the moment they could first swing
 *       back ({@code t* = tPing + turn}), their reach-back is denied by a hair
 *       ({@code sepDeny + denyMargin}) while the attacker can still reach them
 *       ({@code sepReach − jitterMargin}), never pulling in below {@code
 *       targetFloor}. It is computed from ONE unified reach geometry (feet-to-feet
 *       separation, eye→AABB reach) evaluated at the victim's predicted arc height
 *       at {@code t*}, folding in the effective (possibly reach-handicapped) victim
 *       reach. When the geometry is unmeasurable — no facing — the target degrades
 *       to {@link #STATIC}'s fixed separation.</li>
 *   <li>{@link #STATIC} — a fixed config separation ({@code staticTarget}), the
 *       degrade/fallback when the geometry cannot be measured (no facing, no RTT,
 *       an ice landing or a collapsed window — the last two decline the servo
 *       outright). Set this mode to pin the servo to one separation regardless of
 *       facing.</li>
 * </ul>
 *
 * <p>The pre-2.4.5 {@code ANCHOR}/{@code DYNAMIC} modes are gone: {@code ANCHOR}
 * (a fixed anchor separation) became {@link #STATIC}, and the exposure-budget
 * {@code DYNAMIC} target — which minimised toward the LEAST separation still
 * un-answerable, dragging the victim into their own retaliation range — was
 * replaced by the {@link #BOUNDARY} target, which pushes OUT to the denial edge.
 * The config parser migrates the old keys (see {@code SnapshotParser}).</p>
 */
public enum TargetMode {
    BOUNDARY,
    STATIC
}

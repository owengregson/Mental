# Input-driven dynamic chase for the pocket servo (Mental 2.4.5-beta)

**Status:** design approved (owner, 2026-07-07). Implements on top of the
answer-denial-boundary servo rebuild (`2026-07-06-servo-answer-denial-boundary.md`);
integration lands in the same servo files, so this is built *after* that rebuild
merges, not in parallel with it.

## Goal (owner, verbatim intent)

> Accurately understand the sprint-resets occurring leading up to the knock, and
> therefore how the attacker will be moving during the time the victim is in the
> air — so we calculate the knock from the perspective of this **dynamic chase**,
> not a static snapshot of where the attacker is at the instant of the hit.

The pocket servo places the victim so that at the retaliation tick the separation
sits at the answer-denial boundary. That placement subtracts an attacker "chase"
from the current separation (`constant = d0 − chaseTravel + drift + tail`). Today
`chaseTravel` is **retrospective and phase-blind**: the attacker's measured
displacement over the *last* inter-hit gap, assumed to repeat. This spec replaces
the chase source with a **prospective, input-derived model** of how the attacker
will actually move across the victim's airtime.

## Why input, not position-delta inference

A fresh knock only exists because the attacker just reset their sprint (that is
what produces a fresh sprint hit for the servo to shape). Mental already reads the
packets that reveal *how*:

- `SprintWire` already reads sprint START/STOP (entity-action) in arrival order.
- The blockhit work already reads use-item / sword-block right-clicks + release.

Reading these tells us the reset **technique**, its exact **timing**, and the
attacker's **phase** in the reset cycle at hit time — none of which the noisy,
latency-delayed position ring gives cleanly. Each technique has a *known velocity
signature*, and critically the attacker's **effective speed differs by technique**
(a blockhitting attacker moves at sprint+block speed, meaningfully slower than a
pure sprint — assuming pure sprint over-predicts the close and mis-places the
victim). The position-delta model catches a technique change only a full cycle
late; the input model catches it *this* cycle and is robust to mid-combo switches
(w-tap → blockhit stall).

## The reset model (the published value)

A per-attacker immutable value the D1 connection thread maintains and publishes;
the servo reads it (single-writer domains preserved — D1 owns its inbound packets
and publishes, the servo consumes the published snapshot on D2/netty).

```
ResetModel {
  Technique technique;   // W_TAP | S_TAP | BLOCKHIT | SPRINT_TOGGLE | NONE
  int ticksSinceReset;   // phase: how deep into the re-accel we are at hit time
  double effectiveSpeed; // the steady axis speed the attacker ramps toward (b/t)
  boolean confident;     // true when derived from a real input signal (not fallback)
}
```

- `technique` classification (universal tier — whole 1.9.4→26.x range):
  - **BLOCKHIT** — a sprint reset caused by raising the sword block (use-item);
    the attacker holds block, so `effectiveSpeed` is the block-slowed sprint
    speed. This is the case the owner called out; Mental already tracks block
    state, so it is free on every version.
  - **SPRINT_TOGGLE** — an explicit STOP_SPRINTING→START_SPRINTING pair (from the
    entity-action stream) with no block. The classic keyboard sprint reset.
  - **NONE** — sustained sprint, no reset seen in the recent window (steady chase).
- `technique` refinement (modern tier — 1.21.4+ `player_input`, where present):
  - **W_TAP** — forward key released then re-pressed (a movement-key tap that
    dropped sprint), distinguished from a sprint-key toggle.
  - **S_TAP** — a backward key press (sharper reversal signature).
  - Raw directional intent also sharpens the **axis alignment** (below).
- Degradation ladder: modern `player_input` → universal sprint-toggle+block →
  measured-ring chase (today's `windowChaseRate`) → the `0.2806×attr` attribute
  model. `confident=false` below the input tiers routes the servo to the measured
  fallback; era-correct at every rung (platform-probe doctrine).

## The dynamic-chase projection (kernel-pure)

Given the `ResetModel` at hit time and the servo's horizon window `w` (the
gap-aware cadence horizon the servo already computes), project the attacker's
axis-displacement across the victim's airtime:

```
chaseTravel = Σ_{k=1}^{w}  v_axis(ticksSinceReset + k)
v_axis(t)   = alignment · effectiveSpeed · (1 − r^t)      // re-accel ramp toward steady speed
```

- The attacker's speed **ramps** from the post-reset dip back toward
  `effectiveSpeed` over the re-accel (ground acceleration ≈ a few ticks; `r` is the
  per-tick approach factor, calibrated against the movement model — nms-archaeology).
  Starting the ramp at `ticksSinceReset` places us correctly in the cycle: a hit
  landed right after the reset (small `ticksSinceReset`) prices the early, slower
  ticks; a hit landed mid-sprint prices near-steady speed.
- `effectiveSpeed` is **per technique**: sprint for W_TAP/S_TAP/SPRINT_TOGGLE/NONE,
  the block-slowed sprint for BLOCKHIT. Measured from the attacker's move-speed
  attribute × the technique's speed multiplier (block-slow ≈ the era use-item
  movement multiplier — calibrate against NMS, do not guess).
- `alignment` ∈ [0,1] — how directly the attacker's motion is toward the victim on
  the servo axis. Universal tier: derive from the recent axis-projected velocity
  sign/magnitude. Modern tier: sharpen from `player_input` directional keys.
- If the window `w` spans **more than one reset cycle** (slow cadence), the ramp is
  re-seeded at each predicted future reset (a periodic dip). v1 models a single
  ramp and flags multi-cycle windows for a later refinement; the measured cadence
  tells us when that matters.

The servo then solves the knock against this `chaseTravel` exactly as before — the
only change is the *source and shape* of the chase, so the answer-denial-boundary
placement now targets **where the attacker will be across the airtime**, tracking
the dip-and-re-accel of the reset instead of a flat rate.

## Integration & structure

- **Not a separate toggle** (owner choice): this is an accuracy input to the
  horizontal servo (`COMBO_HOLD`), not a combo-keeper in its own right. It only
  runs when `COMBO_HOLD` is enabled and a combo is held; zero-touch otherwise.
- Feeds `ComboPredictor.build`'s `chaseAlongAxis` as the **primary** chase source
  (`confident` models); the measured-ring `windowChaseRate` and the attribute
  model become the fallback rungs. `PocketServo` consumes it unchanged (it already
  takes a scalar `chaseAlongAxis` + the model fallback).
- The `ResetModel` reader lives in the netty realm (D1), alongside `SprintWire`
  and the blockhit listener — reuse those signals; add a `player_input` handler in
  the rim on the modern tier only. Publish the model into the view the servo reads.

## Invariants

- Kernel stays Bukkit-free / pure-JDK: the projection math takes scalars; all
  packet reads happen in the netty/rim seam (core).
- Zero-touch: `COMBO_HOLD` off ⇒ no reader work, no publish, no cost.
- Era-exact: this changes only the servo's internal chase estimate; with the servo
  off (`σ=1`) nothing about the shipped knock changes.
- Single-writer domains: D1 reads its own inbound packets and publishes the model;
  the servo consumes the published snapshot. No cross-thread live reads.
- Degrade individually: a missing input tier drops to the next rung, never breaks
  the solve.

## Test pins (hand-computed, per technique)

Kernel projection pins — for a fixed window `w`, ramp factor `r`, and unit
alignment, hand-compute `chaseTravel` for: NONE (steady sprint, `ticksSinceReset`
large ⇒ ≈ `sprint·w`); SPRINT_TOGGLE fresh (`ticksSinceReset=0` ⇒ the ramped, <
steady sum); BLOCKHIT (same shape at the reduced block speed ⇒ strictly less
close); and a degraded/`confident=false` case falling back to the measured value.
Assert the BLOCKHIT projection is meaningfully below the sprint projection at the
same phase (the owner's core case). Classifier pins: STOP→START ⇒ SPRINT_TOGGLE;
block raise ⇒ BLOCKHIT; (modern) forward tap ⇒ W_TAP, backward press ⇒ S_TAP.

## Sequencing

Builds on the merged answer-denial-boundary servo. Order: (1) that servo lands and
gates green; (2) implement the `ResetModel` + reader (universal tier first, then
the `player_input` enhancement); (3) the kernel projection + pins; (4) swap it into
`ComboPredictor` as the primary chase with the measured/attribute fallbacks; (5)
full gate. The universal tier alone already covers the blockhit case across the
whole version range — that is the priority.

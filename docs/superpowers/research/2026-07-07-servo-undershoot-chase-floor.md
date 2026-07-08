# Pocket-servo undershoot — root cause and the chase-alignment floor (2.4.6)

**Owner report (2026-07-07, playtest):** the combo servo "frequently undershoots
and undercalculates." Raising the σ min-clamp 0.8 → 0.85 → **0.93** monotonically
improved; raising the max-clamp 1.2 → **1.35** also helped. The solver "does a
good job at the upper end (knowing when to apply MORE knockback) but a bad job of
knowing when to do less." Hypothesis: it underestimates approach speed.

## The instrument

A deterministic kernel probe dumped the full `PocketServo.Solution` for a realistic
grounded mid-combo hit (`d0=2.85`, `freshEra=0.40`, `V0=0.40`, target = the
answer-denial boundary **3.150**) under each chase source, over residual 0→0.20:

| chase source | chaseTravel | σ* @res 0 | @0.10 | @0.20 |
| --- | --- | --- | --- | --- |
| **measured** post-hit window (§4d "ground truth", ~1.4b) | 1.385 | 0.857 | **0.607** | **0.357** |
| dynamic ramp (the 2026-07-07 C work) | 3.03–3.35 | 1.69–1.86 | 1.44–1.61 | 1.19–1.36 |
| attribute (full straight-line sprint) | 3.367 | 1.865 | 1.615 | 1.365 |

## Root cause

The owner's hypothesis is correct — the chase is **under-priced**, and it splits
by source:

1. **The measured post-hit window under-reads the chase.** `windowChaseRate`
   averages the attacker's NET axis displacement over the WHOLE inter-hit gap
   (~0.11 b/t, only 41% of sprint), which dilutes the harder post-knock chase
   *burst* the solve actually prices. That drives σ* to **0.36–0.86** — the
   false-low min-clamp mode (the undershoot; "bad at doing less").
2. **The model channels over-price.** The dynamic ramp and the attribute model
   assume alignment = 1.0 (straight-line full sprint), giving σ* 1.4–1.9 — which
   is why the *upper* end feels right and the 1.35 ceiling helps.
3. **Residual carry has enormous leverage** — each 0.10 of residual drops σ* by
   ~0.25 — so as a combo sustains, σ sinks further. The residual is physically
   real (A3 law); it is not the bug, it just means the chase must compensate.

The true effective chase sits **between** the channels (~0.7× sprint ≈ 0.20 b/t),
which maps exactly to the owner's empirical band [0.93, 1.35]. My 2026-07-07
dynamic-chase work made a mis-calibrated MODEL the primary source while the
under-reading measured window stayed primary on sustained hits — the two straddle
the truth and the wide [0.8, 1.2] clamps could not contain either.

## The fix — a chase-alignment floor

`PocketServo.CHASE_ALIGNMENT = 0.70`: the share of full straight-line sprint a real
combo attacker's close actually realizes over the flight window (imperfect axis
alignment + the per-hit w-tap dip — a real reset dips only to ≈0.55× sprint and
recovers in 3–4 ticks, `dynamic-chase-movement-constants §3b`). In
`PocketServo.simulate`, after the chase ladder, the chase is floored:

```
chaseTravel = max(chaseTravel, CHASE_ALIGNMENT · SPRINT_GROUND_SPEED · chaseFactor(attr) · wPrime)
```

An active combo means the attacker IS closing (by definition), so the floor is
the honest minimum; a genuinely harder MEASURED chase still exceeds it and wins.
With the floor the measured channel's σ* rises from 0.36–0.86 to **0.85–1.35**
(centre ≈ 1.1), and the model channels still clamp at the 1.35 ceiling (the
"apply more" case, which errs toward the victim slightly-far = combo held). The
clamp band is recalibrated to the owner's playtested **[0.93, 1.35]**.

Because the floor scales by `chaseFactor(attr)`, a Speed-III attacker's floor
scales up with their sprint too (they close faster). Ice landings still decline
the servo entirely. Verified: the 27 precision pins (four re-derived by hand for
the floored chase — including the renamed `theChaseFloorPreventsTheAirborneFalseLowMode`
which now proves the floor holds σ* interior instead of collapsing to 0.60), a
live 1.21.4 ComboSuite pass in the new band, and the full unit gate.

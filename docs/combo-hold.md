# Combo hold — the pocket servo

**Module:** `combo-hold` (in `config.yml` → `modules`). **Default OFF.** Strictly
server-opt-in — no bundled config pre-enables it. Works under whichever knockback
profile is active.

Combo hold makes *holding* a classic 1.8 sweet-spot combo easier. It is **not**
the "combo" arcade gamemode (no hit-delay changes, no juggling, no shortened
immunity): normal ~10-tick cadence, sweet-spot combos. While a combo is active it
nudges the victim back toward the attacker's **un-retaliatable pocket** so the
chain does not slip out of reach or into a touchdown window — and nothing else.

## Why a pocket exists

Reach is eye → nearest point of the target's hitbox. When a victim is launched
(feet a little above the ground) their 1.8-tall body still spans the attacker's
eye height, so the attacker's shot is the flat base ≈ the reach distance `h`. But
the victim's eye rides 1.62 above their feet, so from up there the nearest point
of the attacker is the *head top* — the victim's required reach is the hypotenuse
`√(h² + Δ²)`, where `Δ` is the victim-eye-to-attacker-head vertical gap. That
margin `≈ Δ²/2h` is geometric, not artificial: at a typical era launch apex
(`Δ ≈ 0.8`, `h = 2.9`) the victim needs ≈ 3.01 blocks to answer — beyond reach —
while the attacker is comfortably inside. Turn ~180° mid-air before your raycast
even faces them and the timing asymmetry stacks on top. When the victim grounds,
`Δ → ~0.2` and reach goes symmetric — combos end on touchdown windows.

So the un-retaliatable band sits around **2.7–2.8 blocks** at era launch heights,
and staying hittable needs `≲ 2.95`. The servo's default `target` of **2.75** is
*derived* from this triangle, not tuned. **The vertical is deliberately never
touched** — the profile's launch height is what buys the `Δ²` margin.

## What it does, precisely

While a combo is active, every **fresh melee horizontal** knock from the attacker
to the victim is scaled by a factor **σ**. σ is the **exact inverse solve of the
era flight equations** — the value that makes the victim land at `target` at the
next swing:

```
dNext = d0 + (residualCarry + σ·freshEra)·dragSum − chase
σ*    = (target − d0 + chase − residualCarry·dragSum) / (freshEra·dragSum)
σ     = clamp(min-factor, max-factor, 1 + gain·(σ* − 1))
```

- `d0` — the current attacker→victim horizontal separation.
- `freshEra` — the fresh knock's horizontal magnitude (base push + sprint/wtap/
  enchant extras); **only this is scaled**.
- `residualCarry` — the victim's own friction-carried motion; **never scaled**
  (the A3 law — a combo hit's residual already carries its previous hits' factors).
- `dragSum` — the era air-drag geometric sum over the flight window, and the
  window truncates at touchdown (computed from the shipped vertical stamp by the
  kernel's own tick simulation).
- `chase` — the sprinting attacker's closing distance (era sprint ground speed
  0.2806 b/t, scaled by the attacker's movement-speed attribute).

Every constant is one of Mental's existing era decay constants — no new physics.

**The clamps are the honesty boundary.** Past `[min-factor, max-factor]` the
pocket is honestly lost and era physics wins — the servo never forces a non-era
knock. It composes cleanly with speed-conformal pace scaling (`fresh = base ×
pace × combo`) and with the static `range-reduction` taper (taper first).

**It never** changes reach, hit-delay, the vertical, or the victim's input; it
does nothing to any pair not in an active combo; and it does **nothing at all**
when the module is off (zero-touch). A false positive mid-scrap is the worst case
and costs only a single clamped knock nudge — graceful degradation by design.

## When a combo is "active"

Detected per victim: a combo with one attacker goes active on the **third** melee
hit (`min-hits`) when each inter-hit gap holds within `max-gap-ticks`, and ends on
any of — the gap expiring; the victim landing a melee hit of their own
(retaliation); a real touchdown (`grounded-run-ticks` consecutive grounded ticks,
so brief ground-skims survive); separation past `blowout-blocks`; or either party
leaving. A hit from a different attacker restarts the chain on them.

## Knobs (`config.yml` → `combo-hold`)

All optional; an absent section uses the defaults shown.

| Key | Default | What |
| --- | --- | --- |
| `min-hits` | `3` | hits from one attacker before the servo engages |
| `max-gap-ticks` | `20` | a longer inter-hit gap ends the chain |
| `grounded-run-ticks` | `10` | consecutive grounded ticks that end the combo |
| `blowout-blocks` | `6.0` | separation past this ends the combo |
| `target` | `2.75` | the separation (blocks) the servo steers toward |
| `gain` | `1.0` | blend toward the full exact solve (1.0 = exact) |
| `min-factor` | `0.8` | lower honesty clamp on the knock multiplier |
| `max-factor` | `1.2` | upper honesty clamp on the knock multiplier |
| `window-ticks` | `10` | the cadence horizon the flight is projected over |

## Integration surface

Two Bukkit events (fired on the victim's owning region thread) let plugins react —
a free scoreboard / stats hook:

- `me.vexmc.mental.api.event.ComboStartEvent` — `getVictim()`, `getAttacker()`,
  `getHits()`.
- `me.vexmc.mental.api.event.ComboEndEvent` — `getVictim()`, `getAttacker()`,
  `getReason()` (`EXPIRED` / `RETALIATION` / `GROUNDED` / `BLOWOUT` / `RETIRED` /
  `DISABLED`).

The servo factor Mental applied to each hit is journaled as `comboFactor` beside
`paceFactor` in the delivery journal, so any non-era stamp is attributable in one
read.

## Roadmap: the precision round

v1 solves exactly over four inputs (`d0`, `residualCarry`, vertical stamp,
attacker speed). A follow-up **precision round** upgrades the predictor — victim
self-drift (measured mid-air steering), axis projection, a facing-driven dynamic
target, ping horizons, and ground-tail drift — behind a single predictor seam, so
the solve and every other seam stay put. Each upgraded input ships only after its
own tick-sim pins and a wire-measured lab round (SimpleBoxer) validate it.

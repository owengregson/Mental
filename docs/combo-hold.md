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
next swing. Everything is projected on the attacker→victim axis:

```
σ*  =  (target − d0 + chase − R·D(w') − ⟨â,u⟩·S2(w') − tail) / (F·D(w'))
σ   =  clamp(min-factor, max-factor, 1 + gain·(σ* − 1))
```

- `d0` — the current attacker→victim horizontal separation.
- `F` (freshEra) — the fresh knock's horizontal (base push + sprint/wtap/enchant
  extras), axis-projected; **only this is scaled**.
- `R` (residualCarry) — the victim's own friction-carried motion, axis-projected
  and signed; **never scaled** (the A3 law — a combo hit's residual already carries
  its previous hits' factors).
- `D(w')` — the drag schedule over the flight window `w'`, with the **launch
  ground-state branch** (one ground-drag decay on a grounded launch, then air drag,
  then the ground tail after touchdown) — a grounded launch travels ~1.9 blocks
  less than the pure-air sum. `w'` is the era cadence shaved by the attacker's
  half-RTT and shifted by the victim's.
- `chase` — the attacker's measured closing (from the position ring), falling back
  to the era sprint model `0.2806 × attr/0.10 × w'`.
- `⟨â,u⟩·S2` — the victim's estimated held mid-air steering (recovered from the
  ring, history-free) accumulated over the window; `tail` their own ground-tail
  walk over any grounded ticks inside it.

Every constant is one of Mental's existing era decay constants — no new physics.
The servo **declines (σ = 1)** on an ice-class landing or a flight too brief to
shape, rather than forcing a non-era knock.

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
| `window-ticks` | `10` | the cadence horizon the flight is projected over (ping-shifted per hit) |
| `target-mode` | `anchor` | `anchor` steers to `target`; `dynamic` uses the facing-/ping-aware exposure-budget target (computed and logged either way — flip only after a lab round) |
| `hit-cap` | `2.95` | the dynamic target's upper clamp (the practical hittable edge); only consulted under `target-mode: dynamic` |

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

## The precision round (§3.2b)

v1 solved over four inputs (`d0`, `residualCarry`, vertical stamp, attacker
speed). The **precision round** (derivation
`docs/superpowers/research/2026-07-04-pocket-servo-precision-derivation.md`)
upgraded the predictor behind that single seam — the `PocketServo.sigma` inversion
never changed, only what feeds it:

- the **launch ground-state drag branch** (mandatory — the pure-air sum overshoots
  a grounded launch by ~1.9 blocks) with signed, axis-projected `R`/`F`;
- **victim self-drift** — the held mid-air steering, recovered from the position
  ring history-free (`DriftEstimator`), κ=0.7-shrunk;
- **ping horizons** — the attacker's half-RTT shaves the judgment horizon, the
  victim's shifts the arc's touchdown;
- the **ground tail** — the victim's own walk over grounded ticks inside the window;
- the **exposure-budget dynamic target** (`target-mode: dynamic`), which relaxes
  between the anchor and `hit-cap` on the victim's facing and ping.

Everything is axis-projected; ice landings decline the servo. `target-mode`
defaults to `anchor`: the dynamic value and the full solve are pushed to the debug
sink per hit (not the journal), so a wire-measured lab round (SimpleBoxer, the
derivation's §7.2 protocol) can calibrate the exposure-budget geometry before the
default flips. The tick-sim grid, drift-estimator recovery, ping-shift, ground-tail
and dynamic-target functions are all kernel-pinned to 1e-9 against an independent
era fold.

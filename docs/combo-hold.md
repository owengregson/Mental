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
and staying hittable needs `≲ 2.95`. The reach triangle *derives* this band; the
shipped default `target` is **2.85**, the lab's data-backed anchor (the target-v2
round). The 71-combo lab analysis found every one of the five longest combos
(120–173 hits) held a separation of **2.81–2.86**, the `[2.8, 2.92)` band broke
combos **1.3% per hit** versus **5.9%** at the cap edge, and the reach-triangle
2.75 was *unreachable* at signature scale (σ\* ≈ 0.76 rode the 0.8 clamp) so it
was never a regulated equilibrium — 2.85 is where the servo actually pins. **The
vertical is deliberately never touched** — the profile's launch height is what
buys the `Δ²` margin.

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

**The servo never** changes reach, hit-delay, the vertical, or the victim's input;
it does nothing to any pair not in an active combo; and it does **nothing at all**
when the module is off (zero-touch). A false positive mid-scrap is the worst case
and costs only a single clamped knock nudge — graceful degradation by design.
(Reach *is* touched by the optional, default-off **reach handicap** sub-feature
below — a separate lever, 1.20.5+ only; the servo itself never touches it.)

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
| `target` | `2.85` | the separation (blocks) the servo steers toward — the lab's data-backed anchor (target-v2) |
| `gain` | `1.0` | blend toward the full exact solve (1.0 = exact) |
| `min-factor` | `0.8` | lower honesty clamp on the knock multiplier |
| `max-factor` | `1.2` | upper honesty clamp on the knock multiplier |
| `window-ticks` | `10` | the cadence horizon the flight is projected over (ping-shifted per hit) |
| `target-mode` | `anchor` | `anchor` steers to `target`; `dynamic` uses the V2 exposure-budget target — the least-aggressive separation whose terminal exposure integral stays within the victim's continuous retaliation budget (`tPing + turn`), an out-drifting victim keeps the anchor, chase EMA-smoothed and target slew-limited (computed and logged either way — flip only after a lab round) |
| `hit-cap` | `2.95` | the dynamic target's upper clamp (the practical hittable edge); only consulted under `target-mode: dynamic` |
| `reach-handicap.enabled` | `false` | the reach handicap sub-feature (see below); off inside the opt-in module |
| `reach-handicap.reach-scale` | `0.8` | the interaction-range multiplier while a combo is held, in `[0.5, 1.0]` (`0.8` → era 3.0 becomes 2.4) |

## The reach handicap (1.20.5+, opt-in within the opt-in)

The servo shapes **spacing** — where the victim lands. The **reach handicap** is a
separate, secondary lever that tightens **retaliation** directly: while a victim is
held in a combo, their `entity-interaction-range` attribute is scaled down (default
`0.8`, so the era 3.0 reach becomes 2.4 blocks) with an **additive** modifier
(`mental:combo-reach`, `MULTIPLY_SCALAR_1`), never a base rewrite — so it composes
with any third-party reach base. It is applied the moment the combo goes active and
removed the moment it ends **by any reason** (`EXPIRED` / `RETALIATION` / `GROUNDED`
/ `BLOWOUT` / `RETIRED` / `DISABLED`), on the victim's owning region thread.

**The servo stays the primary mechanism.** The pocket is a *geometric* property of
the era launch (the `Δ²` reach-triangle margin, above); the servo holds it by
shaping the knock, which is era-faithful and works on every version. The reach
handicap only shortens the answer window on top — a directer but blunter lever.
Leave it off unless you specifically want retaliation tightened during a hold.

**1.20.5+ only.** The interaction-range attribute is client-synced from 1.20.5, so
shortening it makes the client's **own** raycast shorten — no phantom misses where
the client thinks it hit but the server refuses. Below 1.20.5 the attribute does not
exist; the sub-feature is a **documented no-op** and logs one loud line if you
enable it there (the platform-probe doctrine — never a silent degrade).

**ViaVersion caveat.** A legacy client connected through ViaVersion **ignores** the
synced attribute — the server cannot make its raycast shorter. This is a client-side
limit with no server-side fix; the handicap simply has no effect for those players
(the servo, which shapes server-side motion, still holds their pocket).

**Reversibility (leak-safe).** Player attribute modifiers persist to the save file,
so a crash mid-combo could otherwise leave a shortened reach in a profile. The
modifier is therefore swept by fixed identity on player **join**, on module
**enable** (every online player), and on module **disable / reload-off** (restored
inline for every online player) — idempotent, so a leaked modifier is cleared on
sight even if this session never applied it.

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

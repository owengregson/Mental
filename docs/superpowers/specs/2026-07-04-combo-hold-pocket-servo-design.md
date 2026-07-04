# Combo-hold: the pocket servo — design

**Status:** owner-review draft (2026-07-04). Targets **2.5.0**, after the 2.4.1
combat-fix round ships.

**Goal:** a new, default-OFF module that makes *holding* a classic 1.8
sweet-spot combo easier: the victim stays in the attacker's knock pocket —
hittable on rhythm, geometrically unable to answer — for longer. Distinct from
every KB preset: it works under whichever profile is active.

## 1. Research grounding

A two-track research round (workflow wf_59267371-0b0, 2026-07-04) established:

- **What MMC-class servers actually do:** combo *kits* set the victim's
  `maximumNoDamageTicks` from 20 to ~3 at match start (the leaked Minemen
  practice core does exactly this, one line), optionally with a second static
  softer KB constant set. That is the **"Combo" arcade gamemode** — spam hits,
  juggled victims — and the owner explicitly rejected it for Mental: we want
  normal 10-tick cadence, sweet-spot combos. Hit-delay shaping is OUT.
- **No stateful precedent exists.** Across every leaked core, fork source, and
  KB plugin examined, nothing detects an active combo and changes rules
  mid-combo (MMC's core keeps combo counters — for scoreboard stats only).
  The pocket servo is genuinely novel; there is no community-validated feel
  target, so clamps, journaling, and field-testability are load-bearing.
- **Also rejected** (owner): trade dampening (victim's outgoing damage/KB
  scaled), registration grace (wider rewind for the combo holder). **Deferred,
  not rejected:** the reach handicap (victim interaction-range attribute,
  1.20.5+ only; ViaVersion clients ignore it; player attribute modifiers
  persist to NBT so a crash leaks the handicap; the fast path would need a
  companion change). §3 shows round one does not need it.

## 2. Physics: why a pocket exists and why its far edge is safe

A combo drops when the knock ships the victim out of the attacker's reach
envelope (escape) or leaves them close and grounded enough to answer
(retaliation). The **retaliation asymmetry is geometric**, not artificial:

Reach is eye → nearest point of the target's AABB. With the victim launched
(feet ~Δh above ground) their 1.8-tall body still spans the attacker's eye
height, so the attacker's shot is the flat base: ≈ h. The victim's eye rides
1.62 above their feet; from up there the nearest attacker point is the head
top, so their required reach is the hypotenuse √(h² + Δ²), Δ = victim-eye −
attacker-head vertical gap. Margin ≈ Δ²/2h. At a typical era launch apex
(Δ ≈ 0.8) and h = 2.9: the victim needs ≈ 3.01 blocks — beyond reach — while
the attacker is comfortably inside. Turning ~180° mid-air before their raycast
even faces the attacker stacks a timing asymmetry on top. When the victim
grounds, Δ → ~0.2 and reach goes symmetric: combos end on touchdown windows.

**Therefore:** the un-retaliatable band is h ≥ √(reach² − Δ²) ≈ **2.7–2.8**
at era launch heights, and staying hittable needs h ≲ 2.95. The servo's
default target (2.75) is derived, not tuned. Vertical is deliberately NOT
touched: the preset's launch height is what buys the Δ² margin.

## 3. Mechanism

### 3.1 The detector (kernel `ComboTracker`, pure, unit-pinned)

Per victim session (D2, single-writer): combo with attacker A is **active**
when A has landed ≥ `minHits` (default 3) melee hits with inter-hit gaps ≤
`maxGapTicks` (default 20 — sweet-spot cadence is ~10–12), and **ends** on:
gap expiry; the victim landing any melee hit (retaliation); `groundedRunTicks`
consecutive grounded ticks (default 10 — brief ground-skims survive, real
touchdowns end it); separation > `blowoutBlocks` (default 6); either party
retiring. Inputs all exist: the delivery fold gives every shipped hit with
attacker identity (DeskRouter/Deliveries), the ledger's ground events give
grounded runs, applyAttackerObligations gives the victim's own landed hits
(stamped onto their session), the PositionRing gives pair separation.
`ComboState` is a session field published as an additive `PlayerView`
component (old-arity ctor — the moveSpeedAttr precedent), so the netty
pre-send and the region path read one frozen truth. Conservative defaults
matter: a false positive mid-scrap is the worst failure, and it costs only a
±clamp KB nudge (see 3.2) — the design's graceful-degradation property.

### 3.2 The servo (engine `comboFactor`, beside `paceFactor`)

While active, every **fresh melee horizontal** knock from A to V is scaled by
σ — and σ is an **exact inverse solve of the era flight equations** (owner
directive 2026-07-04: the victim must land in a very specific position; a
proportional nudge is not precise enough), then clamped:

```
window' = min(windowTicks, airTime(verticalStampShipped))       [tick-sim]
dragSum = Σ_{k=0}^{window'-1} (era air drag)^k                  [kernel constants]
σ*      = (target − d0 + chase(window') − residualCarry × dragSum)
          / (freshEra × dragSum)
σ       = clamp(minFactor, maxFactor, 1 + gain × (σ* − 1))
```

- The shipped horizontal is `residualCarry + σ × freshEra` (the ledger term is
  never scaled — A3), so separation at next-swing time is
  `d0 + (residualCarry + σ·freshEra)·dragSum − chase`; σ* is the value that
  makes it EQUAL the target. `gain` blends toward the full solve (default 1.0
  = exact); the clamps stay the honesty boundary.
- `airTime` is computed from the SAME vertical stamp being shipped, by the
  kernel's own tick simulation (gravity/drag — the CompensationQuery
  flight-sim precedent): horizontal carry effectively dies at touchdown, so
  travel truncates there. v1 assumes launch from ground level (combo hits
  overwhelmingly connect in touchdown windows — the 1.6.0 boundary-ordering
  work); the simulation pins quantify that approximation's error.
- `chase` = era sprint ground speed (0.2806 b/t, a named pinned constant) ×
  (attacker walk-normalized attr / 0.10) × window' — the 2.4.1 normalization
  work supplies the attribute.
- Every constant is the kernel's own era decay constant — no new
  approximations — and the solve is pinned against a tick-by-tick era
  simulation over a grid of (d0, residualCarry, vertical stamp, attacker
  speed): unclamped cases must land |dNext − target| < 1e-9; clamped cases
  pin the boundary exactly.

### 3.2b The predictor's inputs — the precision mandate

Owner directive (2026-07-04): extreme care goes into WHERE the victim is
placed — everything measurable about them must feed the prediction. The v1
solve (above) consumes (d0, residualCarry, vertical stamp, attacker
normalized speed). The **precision round** — its own derivation + lab
workstream before 2.5.0 ships — upgrades the predictor with, in priority
order:

1. **Victim self-drift.** Mid-air the victim steers at ~0.02 b/t² along their
   input direction (sprint-air slightly more) — up to ~1 block over a 10-tick
   window, the single largest unmodeled term. Source of truth: the MEASURED
   per-tick horizontal velocity from the position ring (captures W/strafe/
   sprint automatically, no input inference needed), blended with the input
   channel where the wire streams it (player_input, 1.21.4+). Projected onto
   the pair axis.
2. **Axis projection.** The whole solve runs along the attacker→victim axis;
   lateral components pass through unscaled (a strafing victim keeps their
   lateral motion — the servo shapes radial spacing only).
3. **Victim facing → a DYNAMIC target.** A victim already facing the attacker
   can answer the instant geometry allows, so the far-edge bound comes from
   the LIVE triangle — h ≥ √(reach² − Δ²) with Δ from the predicted apex
   heights — plus ping slack; a faced-away victim relaxes the target toward
   the hittability center. The static 2.75 remains the config anchor and the
   fallback when inputs are unavailable.
4. **Ping.** The attacker swings on what their CLIENT sees (RTT/2 old), so
   the prediction horizon shifts by the attacker's half-RTT; the victim's
   half-RTT widens the retaliation slack in the dynamic target. Mental's
   per-connection latency model already measures both.
5. **Ground-tail drift.** Grounded ticks inside the window drift at ground
   speed × the victim's OWN normalized attribute (Speed/Slowness on the
   victim matters here, not for the knock itself).
6. Noted, not v1: slipperiness underfoot (ice ground drag, compendium §),
   mid-air launch height (non-ground hits), Jump Boost in the apex.

**Validation contract for every upgraded input:** (i) tick-sim grid pins in
the kernel; (ii) a wire-measured lab round — SimpleBoxer scripted combos
measuring ACTUAL landing positions against predicted, with a per-input error
budget — before the input is trusted in the shipped default.
- Defaults: `target` 2.75 (§2), `gain` 1.0, clamps **[0.8, 1.2]** (owner
  decision — the stronger hold; the wider band trades a little visible KB
  variance for grip on the pocket). Past the clamps the pocket is honestly
  lost and era physics wins.
- Applied at the SAME seam as pace scaling: inside `KnockbackEngine`, on the
  post-taper fresh push and extras, **never the vertical, never the
  friction-carried residual** (the A3 law: the ledger records the scaled
  stamp, so residuals already carry their own hit's factors). One seam covers
  all three delivery paths (netty pre-send adopted verbatim; region compute;
  blocked re-delivery carries the vector).
- Composition is multiplicative and commuting: fresh = (base − rangeTaper) ×
  pace × combo. Each term touches each component once. RangeReduction (the
  static distance cousin) stays independent; enabling both is coherent —
  documented, taper first.
- **Journaled**: `comboFactor` rides the same carrier as 2.4.1's `paceFactor`
  (JournalEntry additive field, old-arity ctor), so any non-era stamp is
  attributable in one journal read.

### 3.3 What the servo never does

No reach changes, no hit-delay changes, no vertical changes, no victim-input
changes, no effect on any pair not in an active combo, nothing at all when the
module is OFF (zero-touch: scope-closed unit; era-exact defaults hold —
`parse(empty)` yields the module absent/OFF).

## 4. Plumbing (from the seams inventory — all precedented)

- New `Family.COMBO` + `Feature.COMBO_HOLD` (default OFF, all four facets +
  `ComboSettings` key — FeatureRegistryTest enforces completeness). GUI needs
  zero edits (DashboardModel derives families/features).
- `ComboSettings` record (DEFAULTS + warn-and-fallback parsing) under
  `modules.combo-hold` + a commented settings block in the bundled config.
  Knobs: `min-hits`, `max-gap-ticks`, `grounded-run-ticks`, `blowout-blocks`,
  `target`, `gain`, `min-factor`, `max-factor`.
- Unit registers in MentalPluginV5; per-player state drops via a forget hook.
- api: `ComboStartEvent`/`ComboEndEvent` (additive; japicmp-gated) — free
  scoreboard/integration surface.
- Kernel additions are additive-only: `ComboTracker`, the engine overload
  growth, `PlayerView`/`JournalEntry` component growth with old-arity ctors.

## 5. Verification

- **Kernel pins** (hand-computed): ComboTracker state machine (start on hit
  3, gap expiry, retaliation end, grounded-run end, blowout end); servo σ at
  pinned separations/residuals incl. both clamps; composition with pace
  (σ×pace on fresh only, A3 relation with the residual).
- **Live suite** (tester): scripted same-attacker hit chains on the victim's
  region thread; assert journal `comboFactor` = 1.0 for hits 1–2, servo-valued
  from hit 3; retaliation/grounding flips it back to 1.0; zero-touch case
  (module off ⇒ every journal comboFactor 1.0 and vectors byte-identical to
  the profile's era stamps); a Folia entry repeat.
- **Known limit** (recorded in the era-accuracy skill during 2.4.1): suite
  fakes attack server-side, so wire-ordered stance nuances ride the existing
  ProfileSuite pins; the combo feel itself is field-judged — SimpleBoxer
  sparring at base speed and Speed III, servo on/off, on the signature preset.

## 6. Resolved decisions (owner, 2026-07-04)

1. **Clamps ship at [0.8, 1.2]** — the stronger hold.
2. **Strictly server-opt-in**: the module defaults OFF everywhere; no bundled
   config pre-enables it.
3. **API events ship from day one**: `ComboStartEvent` / `ComboEndEvent`.

**Status: approved — implementation greenlit (2.5.0 line, based on
release/2.4.1).**

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
at era launch heights, and staying hittable needs h ≲ 2.95. The reach triangle
derives that band; the shipped default target is **2.85** — the target-v2
data-backed retune (the lab's 71-combo round: the five longest combos held
2.81–2.86, the [2.8, 2.92) band broke combos 1.3%/hit vs 5.9% at the cap edge,
and 2.75 was unreachable at signature scale so it never regulated). Vertical is
deliberately NOT touched: the preset's launch height is what buys the Δ² margin.

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

While active, every **fresh melee horizontal** knock from A to V is scaled by the
**exact inverse solve of the era flight equations** — the derivation's final
integrated form (`docs/superpowers/research/2026-07-04-pocket-servo-precision-derivation.md`
§8), all quantities projected on the attacker→victim axis:

```
w'     = c − round(rttA/2 / 50)                    the ping-shifted horizon (c = 10 cadence)
D(w')  = Σ_{k=1}^{w'} Π(k)     Π = the launch-branch drag schedule: one ground-drag
                              decay on a grounded launch tick, then air drag q,
                              then the knock's own ground-drag tail after touchdown
target = anchor (2.85)  |  the V2 exposure-budget dynamic target (§3.2b, behind a knob)
chase  = measured attacker-velocity trend  (fallback 0.2806 × attr/0.10 × w')
tail   = D_ground(G) · dir(â)                       the victim's own ground-tail walk

         target − d0 + chase − R·D(w') − ⟨â,u⟩·S2(w') − tail
σ*  =  ─────────────────────────────────────────────────────────
                           F · D(w')

σ   = clamp(minFactor, maxFactor, 1 + gain × (σ* − 1))
```

- `F` (freshEra) = the σ=1 fresh horizontal (post-taper × pace × air-mult),
  axis-projected; `R` (residualCarry) = the friction-carried residual,
  axis-projected and **signed** (a residual moving V toward A reduces separation —
  the v1 magnitude bug is fixed); **only F is scaled**, never `R` (the A3 law).
- `D(w')` is the drag schedule with the **mandatory launch-ground-state branch**
  (§3.2b): the pure-air sum overshoots a grounded launch by ~1.9 blocks. `⟨â,u⟩`
  is the estimated held mid-air input (§3.2b victim self-drift); `S2` its air
  double-sum; `tail` the victim's own locomotion over the `G` grounded ticks
  inside the window; `chase` the measured attacker closing (attr model fallback).
- The servo **declines (σ = 1)** on an ice-class landing (predicted slip > 0.7)
  or a degenerate flight (air-time < 3, horizon < 1) — never a forced non-era knock.
- Defaults: `target` **2.85** (§2, the target-v2 data-backed retune — the lab's
  held-separation equilibrium; the old reach-triangle 2.75 was unreachable at
  signature scale so it never regulated), `gain` 1.0, clamps **[0.8, 1.2]** (owner
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
  `target`, `gain`, `min-factor`, `max-factor`, `window-ticks` (the cadence
  horizon `c` — load-bearing but absent from the round-one list, derivation open
  issue 1), and the §3.2b precision pair: `target-mode` (`anchor` | `dynamic`,
  default `anchor` — the dynamic value is computed and journaled to the debug sink
  either way, so the lab round can calibrate before flipping it) and `hit-cap`
  (2.95, the dynamic target's upper clamp). The precision predictor (launch-state
  branch, victim self-drift, ping horizons, ground tail) folds into `window-ticks`
  with no further knobs — every input degrades to a no-op when unavailable.
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

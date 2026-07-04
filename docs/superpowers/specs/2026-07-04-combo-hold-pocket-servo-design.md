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

```
σ = clamp(minFactor, maxFactor, 1 + gain × (target − dPredicted) / target)
```

- `dPredicted` = separation at hit + the fresh knock's horizontal travel over
  the cadence window (kernel decay math — the CompensationQuery flight-sim
  precedent) − the attacker's closing distance (walk-normalized move speed ×
  window; sprint assumed — the 2.4.1 normalization work supplies the attr).
- Defaults: `target` 2.75 (§2), `gain` 1.0, clamps **[0.85, 1.15]** — inside
  the band it reads as consistent KB, past it the pocket is honestly lost and
  era physics wins.
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

## 6. Open questions for owner review

1. `target` 2.75 / clamps [0.85, 1.15] as shipping defaults — or wider clamps
   (e.g. [0.8, 1.2]) for a stronger hold at the cost of more visible KB
   variance?
2. Should the signature preset's *bundled config* ship with combo-hold ON
   (module default stays OFF globally), or is the module strictly
   opt-in-by-server for now? (Recommendation: strictly opt-in for 2.5.0;
   revisit after field feel.)
3. Combo events in the api from day one, or hold until a consumer exists?
   (Recommendation: ship them — additive and cheap.)

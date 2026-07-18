# Pocket-servo redesign — the answer-denial reach boundary (Mental 2.4.5-beta)

Status: implementation spec. Branch: `release/2.4.5-beta` (STAY ON IT; no version
bump; the shipped 2.4.5 items — min-hits 2, reach-handicap scale 0.87, blockhit
fresh-sprint — stay intact).

Ground truth this replaces: the exposure-budget dynamic target
(`docs/superpowers/specs/2026-07-04-combo-hold-pocket-servo-design.md` §3.3 and
the target-v2 round). Everything under "The placement physics" there still holds.

---

## 0. Why (the validated diagnosis)

The pocket servo scales the **fresh horizontal melee knock** by a factor `σ` to
place a combo victim at a chosen separation from the attacker at the moment they
could next swing. The current target chooser is **inverted** vs what combos need:

- Its entire operating band is `[target 2.85, hitCap 2.95]` — structurally
  **below 3.0**. It cannot place the victim at or beyond the reach boundary.
- Its philosophy is "pull the victim IN to the least separation still
  un-answerable" (`selectTarget` picks the MINIMUM feasible distance; `capEff`
  caps below 3.0). That drags the victim into their own retaliation range and
  makes combos HARDER.

The correct model: land the victim **right at the answer-denial boundary** — the
separation where, at the tick they could first swing back, their reach-back is
denied by a hair, while the attacker can still reach them. **Push OUT** to that
boundary; pull IN only if the natural knock ejects them past the attacker's own
reach.

---

## 1. Unified reach geometry (ONE basis)

Fix the basis inconsistency in the current code (it mixes `VICTIM_REACH = 2.9`
eye-to-box with `HIT_EDGE = 3.0` flat feet-to-feet). Use ONE consistent model:
**feet-to-feet horizontal separation `sep`, eye → AABB reach.**

Constants (kernel):

| Symbol | Value | Meaning |
| --- | --- | --- |
| `w0` (`HALF_WIDTH`) | `0.3` | player AABB half-width |
| `PLAYER_HEIGHT` | `1.8` | head-top above feet |
| `Ya` (attacker eye) | `1.62` | attacker grounded, eye height |
| `eyeV` (victim eye) | `1.62` (pose-aware) | victim eye above its feet |

Setup: attacker grounded, attacker box vertical span `[0, 1.8]`, attacker eye at
`Ya = 1.62`. Victim feet height above the attacker's ground = `h (>= 0)`, victim
box vertical span `[h, h + 1.8]`, victim eye at `h + eyeV`.

**Reach predicate.** A player with eye-to-box reach `R` hits a target iff

```
sqrt( (sep - w0)^2 + dvert^2 ) <= R
```

where `dvert` = vertical distance from the aimer's EYE to the nearest point of
the target box's vertical span.

**Victim answering the attacker** (attacker box `[0, 1.8]`, victim eye at
`h + eyeV`):

```
dvertV  = max(0, (h + eyeV) - PLAYER_HEIGHT)          // victim eye above attacker head
sepDeny = w0 + sqrt(R_v^2 - dvertV^2)                 // victim cannot answer past this
```

Guard: if `dvertV >= R_v` the victim can NEVER answer — the deny boundary
collapses (treat as `+INF`; the target rule then rides `sepReach - jitter`).

**Attacker hitting the victim** (victim box `[h, h + 1.8]`, attacker eye at `Ya`):

```
dvertA   = max(0, h - Ya, Ya - (h + PLAYER_HEIGHT))   // usually max(0, h - 1.62)
sepReach = w0 + sqrt(R_a^2 - dvertA^2)                // attacker cannot reach past this
```

Guard: if `dvertA >= R_a` the attacker can never reach — decline the servo
(`σ = 1`); there is no keepable pocket to steer into.

`R_v` is the **EFFECTIVE** victim reach (§4 handicap composition); `R_a` is the
attacker reach and is **never** handicapped.

---

## 2. The critical tick `t*` and the target rule

Evaluate the geometry at `h = arcHeight(t*)`, where `t* = tAllow` = the tick the
victim could FIRST retaliate:

```
tAllow = tPing + turn        // both already computed in PocketServo
```

That is exactly "their predicted ability to hit back at the point in time they'd
hit back." Concretely: `tStarTick = round(tAllow)`, and

```
h = arcHeight(verticalStamp, launchHeight, max(0, tStarTick - shiftV))
```

(`shiftV` = the victim half-RTT arc shift, same as the old exposure integral used
— the victim's observed arc lags by their ping).

**The target:**

```
target = min( sepReach(t*) - jitterMargin,
              max( targetFloor, sepDeny(t*) + denyMargin ) )
```

- **Aim at the deny boundary** (`+ denyMargin`, so jitter can't let them answer).
- **Never exceed the attacker's reach** (`- jitterMargin`, so the attacker
  reliably connects).
- **Never pull IN below `targetFloor`** (`max(targetFloor, …)`).
- **Empty / razor pocket** (`sepDeny + denyMargin >= sepReach - jitterMargin`,
  e.g. low elevation with equal reach): the `min` naturally picks
  `sepReach - jitterMargin` — **reachability wins** (keep the victim hittable so
  the combo continues; denial there is the reach-handicap / vertical submodule's
  job, not the servo's).

The A3 law is untouched: `σ` scales the FRESH horizontal only — never the ledger
residual, never the vertical. The affine inversion
`sigmaStar = (target - constant - slope*residual) / (freshEra*slope)` is
identical; only how `target` is chosen changes.

### 2.1 Worked pins (see §7 for the full table)

- `h = 0.42`, `R_v = 3.0`: `dvertV = 0.24` → `sepDeny = 3.2904`; `sepReach =
  3.30`. Razor pocket → `target = sepReach - jitter = 3.15`.
- `h = 0.42`, `R_v = 2.61` (handicap 0.87): `sepDeny = 2.8989`; `sepReach = 3.30`.
  Wide → `target = sepDeny + deny = 2.9189`. **The field-report behavior.**

This is *why* "the reach nerf is the only thing keeping my combo": lowering `R_v`
drops `sepDeny` well below `sepReach` and OPENS a keepable pocket at normal combo
elevations.

---

## 3. Keep vs rip

### KEEP (the placement physics — the boundary target still needs accurate
elevation/horizontal prediction to evaluate the geometry at `h_{t*}` and to invert
`target → σ`)

- The flight fold: drag schedule `Π`, grounded-launch branch, air drag, ground
  tail, victim drift (`S2`), ground-tail locomotion, chase (measured post-hit
  window EMA + attribute fallback), ping horizons (`shiftA` / `shiftV`), the
  gap-aware cadence window, the touchdown-aware launch repricing.
- `arcHeight()`, `airTime()`, `s2()`, `geo()`, `groundLocomotion()`, the affine
  `FlightPrediction` inversion, the `[min, max] = [0.8, 1.2]` clamp, gain.
- `tAllow(...)` and `turn(...)` — they now DEFINE `t*` for the boundary target.
  Keep them.
- The `arcHeight` / `sepDeny` (`hSafe`) building blocks inside the old
  `exposure()` — reuse the geometry, not the integral.
- The A3 law and every degenerate-→-`σ=1` decline path.

### RIP (the inverted target-selection engine)

- `dynamicTarget()`, `selectTarget()`, the `exposure()` **integral min-selection**,
  `capEff()` as the target chooser.
- The exposure-budget `[anchor, capEff]` band and the bisection.
- The static `2.85` anchor as the default target.
- The mixed-basis constants: `VICTIM_REACH = 2.9`, `HIT_EDGE = 3.0`,
  `LANDING_SLACK`. Replace with the unified `R_v` / `R_a` / `w0` geometry and the
  new margins.
- `CHASE_EMA_ALPHA` / `TARGET_SLEW_LIMIT` **only** insofar as they served the
  dynamic-target smoothing. Note: the chase EMA is ALSO the σ* placement chase
  (servo-lab 2.4.5, load-bearing) — keep `chaseEma()` and the window-chase memory
  intact; only the target-slew wrapper and the dynamic-target `priorDynamicTarget`
  tenant go away.

Replace the SELECTION with the direct boundary target from `arcHeight(t*)`.

---

## 4. Handicap composition (the two submodules compose)

`R_v` fed into `sepDeny` is the **effective** victim reach:

```
R_v_eff = handicapEngaged ? R_base * handicapScale : R_base
```

- `R_base` = configured victim reach (default `3.0`).
- `handicapScale` = `ReachHandicapSettings.scale()` (default `0.87`), applied
  exactly while `COMBO_REACH_HANDICAP` is enabled AND supported (1.20.5+) AND a
  combo is held against the victim — i.e. exactly while the attribute modifier is
  live on the victim.
- `R_a` (attacker) is never handicapped.

**Where it is folded:** in `core`, at the servo-config build site
(`KnockbackUnit.comboServoFor` and `HitRegistrationUnit.comboServoFor`). Those
sites already know the victim's frozen view and can read the handicap flag/scale
(HitRegistrationUnit already has `comboReachHandicapScale(...)`; give KnockbackUnit
the sibling read). They compute `R_v_eff` and pass it into
`ComboSettings.servo(rvEff)` → `PocketServoConfig` with `victimReach = R_v_eff`.
The kernel solve stays scalar-only and Bukkit-free.

Rationale for config-carries-`R_v_eff` (vs `R_base + scale`): the kernel never
learns whether the handicap is on, only the number it must solve against — the
same discipline the rest of the precision seam uses (axis-projected scalars only).

---

## 5. TargetMode collapse

Two modes only:

- **`BOUNDARY`** — the new geometric answer-denial target (§2). **THE DEFAULT.**
  The owner wants it applied, not journal-only, so the default flips to `BOUNDARY`.
- **`STATIC`** — a fixed config separation (`staticTarget`), the degrade / fallback
  when the geometry is unmeasurable: no facing (`victimYawVsAxisDeg` NaN), no RTT,
  ice landing, or a collapsed window. Also the honest home for a server that just
  wants a flat anchor.

Old `ANCHOR` / `DYNAMIC` go away. Every degenerate/unmeasurable case still
declines to `σ = 1` exactly as today (ice, `airTime < 3`, `t* < 1`, no fresh
lever, inactive). The `STATIC` fallback is for cases where the flight is solvable
but the *boundary geometry* is not (missing facing/RTT) — there the servo still
steers, just toward the flat `staticTarget` instead of the boundary.

Implementation note: in `simulate(...)`, when `targetMode == BOUNDARY` and the
boundary inputs are present, compute `target = boundaryTarget(...)`; otherwise
`target = staticTarget`. Keep the resolved `target`, `sepDeny`, `sepReach` on the
`Fold` / `Solution` for the debug sink.

---

## 6. The detection / servo split (second deliverable)

Today `COMBO_HOLD` bundles (a) combo DETECTION (SessionService installs the
`ComboTracker` when `comboEnabled`) and (b) the KB servo. `COMBO_REACH_HANDICAP`
rides combo transitions (`ComboEvents`) that only fire when the tracker is
installed — so the handicap CANNOT run with the servo off. Break this:

### 6.1 Detection runs when EITHER keeper is enabled

Reference-count it (best fit for the existing `enableCombo`/`disableCombo`
reconciler pattern):

- Replace `SessionService.comboEnabled` (volatile boolean) with a retain count
  (volatile int, or an `AtomicInteger`). `retainCombo()` increments, `releaseCombo()`
  decrements (floored at 0); `driveCombo` treats detection active iff the count `> 0`.
- **BOTH** keepers hold it: `ComboHoldUnit.assemble` calls `sessions.retainCombo()`
  and returns `sessions::releaseCombo`; `ComboReachHandicapUnit.assemble` does the
  same (in addition to its existing enable/disable sweep task). Detection is on iff
  at least one keeper holds a scope.
- Preserve the DISABLED-end / tracker-teardown behavior when the count drops to 0:
  `driveCombo` still fires `ComboEndReason.DISABLED` and clears the tracker on the
  session's next tick, exactly as the `!comboEnabled` branch does today.

(Alternative accepted by the brief: read the live OR of the two feature flags in
`driveCombo`. The reference count is preferred because it mirrors the scope
lifecycle the reconciler already drives and keeps `driveCombo` a single `count > 0`
read with no snapshot lookups on the hot tick path.)

### 6.2 The SERVO application stays gated on `enabled(COMBO_HOLD)`

- `comboServoFor` (both sites) keeps its `!enabled(COMBO_HOLD) → INACTIVE` gate.
  With only the handicap on, the servo returns INACTIVE (`σ = 1`) but detection +
  transitions still run, so the handicap engages.
- With only the servo on, the handicap self-gates off: `comboReachHandicapScale`
  already returns `null` when `!enabled(COMBO_REACH_HANDICAP)`, and
  `ComboReachHandicap.onComboStart` already no-ops when its module is disabled.

### 6.3 Zero-touch still holds

BOTH keepers off → retain count 0 → no tracker, no per-tick combo work, no
listener beyond the units' own (which are unregistered when their scopes close).
Nothing.

### 6.4 Docs to correct (the coupling is now false)

- `Feature.COMBO_REACH_HANDICAP` blurb: drop "Requires Combo Hold"; say it
  requires **combo detection**, which either keeper provides.
- `SnapshotParser`: the "enabled but combo-hold is off → will never engage"
  dependency warning is now WRONG — the handicap provides its own detection.
  Remove it (or downgrade to an informational note that the handicap will run its
  own detection when combo-hold is off).
- `ComboReachHandicap` / `ComboReachHandicapUnit` javadoc: "depends on COMBO_HOLD"
  → "depends on combo detection (provided by Combo Hold or the handicap itself)".

---

## 7. Config shape and migration

### 7.1 Kernel: `PocketServoConfig`

Immutable, kernel-pure. New shape (records — implementer picks exact field order):

```
PocketServoConfig(
    boolean active,
    TargetMode targetMode,     // BOUNDARY (default) | STATIC
    double staticTarget,       // the STATIC fallback separation (2.85)
    double victimReach,        // R_v_EFFECTIVE — caller folds in the handicap
    double attackerReach,      // R_a (3.0, never handicapped)
    double denyMargin,         // 0.02
    double jitterMargin,       // 0.15
    double targetFloor,        // 2.5
    double gain, double min, double max,   // 1.0, 0.8, 1.2
    int windowTicks)           // 10
```

- `INACTIVE` = `active=false`; the solve returns `σ` exactly `1.0` (byte-identical,
  zero-touch). Retains its role as the module-off / not-this-attacker value.
- The caller passes `victimReach = R_v_eff`. `attackerReach` stays `R_a`.
- Drop `target` (anchor) and `hitCap` (dynamic upper clamp) — both belonged to the
  ripped selection engine.

### 7.2 Core: `ComboSettings`

Replace the static-anchor knobs. New fields:

```
minHits, maxGapTicks, groundedRunTicks, blowoutBlocks,   // detector (unchanged)
targetMode (BOUNDARY),
staticTarget (2.85),
victimReach (3.0),        // R_base
attackerReach (3.0),      // R_a
denyMargin (0.02),
jitterMargin (0.15),
targetFloor (2.5),
gain (1.0), minFactor (0.8), maxFactor (1.2), windowTicks (10)
```

`servo(double rvEff)` builds the active `PocketServoConfig` with
`victimReach = rvEff` (the caller having folded the handicap). A no-arg `servo()`
convenience may pass `victimReach` (unhandicapped `R_base`) for callers/pins that
do not compose the handicap.

`DEFAULTS` keeps the detector defaults and adds the geometric knobs above.
`parse(empty)` stays era-exact-no-op automatically (the MODULE defaults OFF).

### 7.3 Config keys and the loud migration

New YAML under `combo-hold`:

```yaml
combo-hold:
  target-mode: BOUNDARY        # BOUNDARY | STATIC
  static-target: 2.85
  victim-reach: 3.0
  attacker-reach: 3.0
  deny-margin: 0.02
  jitter-margin: 0.15
  target-floor: 2.5
  gain: 1.0
  min-factor: 0.8
  max-factor: 1.2
  window-ticks: 10
  # detector knobs unchanged: min-hits, max-gap-ticks, grounded-run-ticks, blowout-blocks
```

Migration (in `parseCombo`, loud lines via `reader.issues()`):

- Old `target-mode: ANCHOR` → `STATIC`, and carry the old `target` value into
  `static-target` (emit a line naming both keys).
- Old `target-mode: DYNAMIC` → `BOUNDARY` (the new geometric target; emit a line).
- Old `target-mode` absent → `BOUNDARY` (new default).
- Old `target: <v>` (no explicit mode, or ANCHOR) → `static-target: <v>` for the
  STATIC fallback; emit a one-release deprecation line ("`target` renamed to
  `static-target`; the default target is now the geometric BOUNDARY").
- Old `hit-cap: <v>` → deprecated and ignored (the dynamic upper clamp is gone);
  emit a loud line so a tuned value is never silently dropped.
- Validation: `victim-reach`/`attacker-reach` in `[0.5, 6.0]`; `deny-margin`,
  `jitter-margin` in `[0.0, 1.0]`; `target-floor` at least `0.5`; `min > max`
  transposition warn-and-fallback as today. A `TargetMode` parsed from an old
  `ANCHOR`/`DYNAMIC` string maps as above rather than warning-to-default.

`parse(empty)` → module OFF → no-op; the defaults above are the values used when
someone flips it on with no tuning.

---

## 8. Invariants (a reviewer checks each)

- Kernel stays Bukkit-free and pure-JDK (build-asserted). All Bukkit reads
  (handicap enabled?, scale, poses) happen in core; the kernel solve takes scalars.
- A3 law: scale fresh horizontal only; never residual, never vertical.
- Era-exact no-op: `COMBO_HOLD` defaults OFF; `INACTIVE ⇒ σ` exactly `1.0`.
- Zero-touch: both keepers off ⇒ no tracker, no cost.
- Hand-computed unit pins (this repo's convention): the geometry changes MUST
  carry kernel unit tests asserting `sepDeny` / `sepReach` / `boundaryTarget`
  against the §9 table (to 1e-9 with the constants below).
- Every degenerate/unmeasurable case declines to `σ = 1` (ice, no air-time,
  collapsed window, no fresh lever) or degrades to `STATIC` (no facing / no RTT) —
  never crashes, never ships a wild knock.

---

## 9. Hand-computed pin table

Constants: `w0 = 0.3`, `PLAYER_HEIGHT = 1.8`, `Ya = 1.62`, `eyeV = 1.62`,
`denyMargin = 0.02`, `jitterMargin = 0.15`, `targetFloor = 2.5`,
`R_a = 3.0`, `staticTarget = 2.85`. `sepReach - jitterMargin` at flat/low `h`
(`h <= 1.62`, `dvertA = 0`) is `3.30 - 0.15 = 3.15`.

Formulas:
```
dvertV  = max(0, h + 1.62 - 1.8) = max(0, h - 0.18)
sepDeny = 0.3 + sqrt(R_v^2 - dvertV^2)
dvertA  = max(0, h - 1.62)
sepReach= 0.3 + sqrt(R_a^2 - dvertA^2)
target  = min( sepReach - 0.15, max( 2.5, sepDeny + 0.02 ) )
```

| # | scenario | h | R_v | dvertV | sepDeny | dvertA | sepReach | target | why |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | low elev, no handicap | 0.42 | 3.00 | 0.24 | **3.2904** | 0 | **3.3000** | **3.1500** | razor pocket: `deny+0.02 = 3.3104 > reach-jitter 3.15` → `min` picks `reach-jitter`; reachability wins (denial deferred to handicap/vertical). |
| 2 | low elev, handicap 0.87 | 0.42 | 2.61 | 0.24 | **2.8989** | 0 | **3.3000** | **2.9189** | wide pocket: `target = sepDeny + 0.02`, inside `[2.5, 3.15]`; victim held just past the deny boundary, attacker reaches. Field-report behavior. |
| 3 | mid elev, no handicap | 1.238 | 3.00 | 1.058 | **3.1072** | 0 | **3.3000** | **3.1272** | ~1.24 lift drops deny below reach even unhandicapped; `deny+0.02 = 3.1272 <= 3.15` so it stands. |
| 4 | mid elev, handicap 0.87 | 1.238 | 2.61 | 1.058 | **2.6859** | 0 | **3.3000** | **2.7059** | wide pocket; `target = sepDeny + 0.02`. |
| 5 | high launch, handicap 0.87 | 2.00 | 2.61 | 1.82 | **2.1707** | 0.38 | **3.2758** | **2.5000** | `deny+0.02 = 2.1907 < targetFloor 2.5` → `max` clamps up to the floor; never pull IN below floor. `dvertA = 0.38` → `sepReach = 0.3 + sqrt(9 - 0.1444) = 3.2758`. |
| 6 | ice landing (declined) | — | 2.61 | — | **n/a** | — | **n/a** | **2.85** | `landingSlip 0.9 > 0.7` → servo DECLINES, `σ = 1` exactly; geometry not evaluated; BOUNDARY target unused; `staticTarget = 2.85` shown for reference. |

Intermediate arithmetic (verified):

- Pin 1: `sqrt(9 - 0.0576) = sqrt(8.9424) = 2.990385` → `sepDeny = 3.290385`.
- Pin 2: `sqrt(2.61^2 - 0.0576) = sqrt(6.7545) = 2.598942` → `sepDeny = 2.898942`.
- Pin 3: `1.058^2 = 1.119364`; `sqrt(9 - 1.119364) = sqrt(7.880636) = 2.807247`
  → `sepDeny = 3.107247`.
- Pin 4: `sqrt(6.8121 - 1.119364) = sqrt(5.692736) = 2.385946` → `sepDeny = 2.685946`.
- Pin 5: `1.82^2 = 3.3124`; `sqrt(6.8121 - 3.3124) = sqrt(3.4997) = 1.870748` →
  `sepDeny = 2.170748`. `0.38^2 = 0.1444`; `sqrt(9 - 0.1444) = sqrt(8.8556) =
  2.975836` → `sepReach = 3.275836`.

All targets recomputed in `min(sepReach - 0.15, max(2.5, sepDeny + 0.02))` and
cross-checked numerically.

---

## 10. Build / gate

- Iterate: `./gradlew :kernel:test :core:test`.
- Final green: `./gradlew build` (unit + japicmp + kernel-Bukkit-free + downgrade
  gates). Do NOT run `integrationTestMatrix` (owner runs it after review).
- Commit as you go, conventional commits, prose bodies, ending each body with the
  session's `Co-Authored-By` / `Claude-Session` trailers.

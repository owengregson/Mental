# Pocket-servo precision — the full predictor derivation

Status: research deliverable for the 2.5.0 precision round (2026-07-04).
Fulfils the contract of the combo-hold design §2/§3.2/§3.2b
(`docs/superpowers/specs/2026-07-04-combo-hold-pocket-servo-design.md`).

Every constant below is cited to the kernel or the compendium — none is
invented here. Sources, abbreviated throughout:

- **[K-Decay]** `kernel/.../math/Decay.java` — gravity 0.08, vertical drag
  0.98, air drag 0.91, default slipperiness 0.6 (ground drag = slip × 0.91 =
  0.546 on stone), grounded equilibrium −0.0784, terminal −3.92, rest
  threshold 0.005.
- **[K-Motion]** `kernel/.../math/MotionMath.java` — the move-then-decay tick
  order, `ticksToFall` / `distanceTraveled` (the CompensationQuery flight-sim
  precedent), 30-tick prediction cap.
- **[K-Friction]** `kernel/.../math/GroundFriction.java` — slipperiness table
  (default 0.6, ice/packed 0.98, blue ice 0.989, slime 0.8).
- **[K-Engine]** `kernel/.../math/KnockbackEngine.java` — compute order (taper
  → base/friction → extras → air multipliers → clamps), pace-on-fresh-only
  (A3), packet clamp ±3.9.
- **[K-Pace]** `kernel/.../math/PaceScale.java` — walk-normalized attribute,
  WALK_BASELINE 0.10.
- **[K-Reach]** `kernel/.../math/EraReach.java` — era survival reach 3.0.
- **[K-Ledger]** `kernel/.../ledger/MotionLedger.java` — the residual fold the
  published view carries.
- **[C]** `docs/research/2026-06-06-combat-compendium.md` — §2 residual
  physics table, §4 hit-delay (cadence 10), §5b wire tables (1.994 / 4.948
  anchors, ice decomposition), the residual final form.
- **[S-motion]** `.claude/skills/legacy-motion-physics/SKILL.md` — constants
  table, integration order, the pre-move-friction and send-then-restore traps,
  reference flights.
- **[A1/A2]** `docs/superpowers/research/2026-07-04-pace-scaling-assumptions.md`
  — javap-verified: airborne accel is the fixed 0.02f both eras; attribute
  values (0.10 walk / 0.13 sprint / ×1.6 Speed III).
- **[SIG]** `kernel/.../profile/Presets.java#SIGNATURE` — base (0.325, 0.365),
  extra (0.5, 0.0), friction 0.1, air (0.92 h, 0.98 v), vertical cap 0.36,
  pace exponent 0.95.
- **[SPEC]** the combo-hold design; **[PACE-SPEC]** the speed-conformal design.

Notation: `q = 0.91` (air drag), `sq = slip × 0.91` (ground drag; 0.546 on
stone), `g = 0.08`, ticks are server ticks (50 ms),
`geo(n) = Σ_{i=0}^{n−1} q^i = (1 − q^n)/(1 − q)`. "Axis" means the horizontal
attacker→victim unit vector `u` at the hit (pointing away from the attacker);
all separations `d` are measured along it (§2.3 quantifies the lateral
second-order error).

---

## 0. The flight model, validated against the wire before anything is built on it

The client REPLACES its motion with every velocity packet and integrates the
trajectory itself [S-motion send-then-restore; C §5]. Per tick, in order
[K-Motion; S-motion]:

1. input acceleration (held keys — §2);
2. friction factor chosen from the **pre-move** ground state;
3. move by the current motion (collisions land the entity);
4. decay: horizontal ×(q or sq), vertical `(v − 0.08) × 0.98`.

So a knock `(H0, V0)` applied to a **grounded** victim moves the full `H0` on
tick 1 (the decay is post-move), takes ONE ground-drag decay on that launch
tick (pre-move state was grounded — the #1 trap in [S-motion]), and air drag
thereafter:

```
move_1 = H0
move_k = H0 · sq · q^(k−2)          for k ≥ 2 (until touchdown)
```

Applied to an **airborne** victim the launch tick decays at air drag:
`move_k = H0 · q^(k−1)`.

**Validation (the reason to trust everything below).** Folding this model with
the [K-Decay] constants, landing when the vertical fold returns to launch
height, plus the post-touchdown ground tail (§5), reproduces the measured era
settles [C §5b; S-motion reference flights]:

| Case | Model | Measured wire |
| --- | --- | --- |
| plain standing (0.4, 0.3608) | 1.984 (1.788 flight + 0.196 ground tail, rest-trimmed) | **1.994** |
| sprint (0.9, 0.4607) | 4.939 (4.599 flight + 0.340 tail, rest-trimmed) | **4.948** |

Both close to ~0.01 blocks (the [K-Decay] 0.005 rest threshold trims the tail;
wire settles are read at rest). The model is exact; the residual-vs-trajectory
distinction below is the only subtlety.

**Trajectory vs residual — one grounded decay vs two.** The compendium's
residual final form (`0.4 × sq(knock tick) × sq(liftoff tick) × q^k`, ice-pinned
[C §5b]) is the SERVER's between-hits field machine — the thing
[K-Ledger] folds and the thing `residualCarry` is read from. The client
TRAJECTORY (what the position ring measures, what travel is) takes ONE grounded
decay: the tick-1 move already lifts the victim, so tick 2's pre-move state is
airborne. The 1.994/4.948 anchors above pin the one-decay trajectory; the ice
chain pins the two-decay residual. Use each machine for its own quantity; mixing
them is a ~0.4-block class error.

---

## 1. The base solve, restated exactly

### 1.1 σ-linearity — why a closed form exists at all

The servo scales **only the fresh horizontal** [SPEC §3.2]; the vertical stamp
`V0` and therefore the entire vertical arc, the touchdown tick, and the
per-tick drag *schedule* (grounded / airborne / grounded again) are all
**σ-invariant**. Horizontal travel is then linear in the shipped horizontal,
and the shipped horizontal is affine in σ:

```
H(σ) = R + σ · F
```

- `F` (**freshEra**): the fresh horizontal the engine would ship at σ = 1,
  with EVERYTHING else already applied and projected on the axis — post
  range-taper, × pace factor `s` [K-Pace], × air.horizontal when the victim
  captures airborne [K-Engine order: air multipliers scale the whole vector].
  For [SIG]: grounded sprint hit `F = 0.325 + 1×0.5 = 0.825`; airborne
  `F = 0.825 × 0.92 = 0.759`.
- `R` (**residualCarry**): the victim-motion term as the engine ships it —
  `⟨view.motion, u⟩ × friction.x × (air.horizontal if airborne)`. Read from the
  published view ([K-Ledger] fold; its fidelity is already era-pinned), never
  re-modeled here. σ never touches it (the A3 law: the ledger recorded the
  previous hit's own factors [K-Engine]).

### 1.2 The drag-sum

Let `w` be the prediction window in ticks (§1.4/§4 define it). Define the
**drag schedule** `Π(k)` = the product of per-tick horizontal decays applied
before the tick-k move, taken from the σ-invariant vertical simulation's
ground states:

```
Π(1) = 1
Π(k) = Π(k−1) × ( sq_launch  if tick k−1 pre-move state grounded
                  q          if airborne )
```

and the travel factor

```
D(w) = Σ_{k=1}^{w} Π(k).
```

Closed forms for the two clean cases (`sq` from the block under the victim's
feet at launch — [K-Friction], the view's `slipperiness` field):

```
grounded launch:  D_g(w) = 1 + sq · geo(w−1)
airborne launch:  D_a(w) = geo(w)
```

Numbers (stone, `sq = 0.546`): `geo(10) = 6.784265`, `D_g(10) = 4.470559`,
`D_a(10) = 6.784265`.

> **Correction to the spec's shorthand.** [SPEC §3.2] writes `dragSum` as the
> pure air-drag geometric sum while also assuming launch from ground level.
> Those are inconsistent by the launch tick's pre-move friction: at [SIG]
> sprint values the pure-air sum overstates a grounded launch's travel by
> `F × (D_a(10) − D_g(10)) = 0.825 × 2.3137 = 1.909 blocks` — the exact trap
> [S-motion] warns costs "a full block" on a plain 0.4 knock (3.06 vs 2.06).
> The implementable formula MUST branch on the victim's launch ground state
> (the same captured `grounded` flag the engine's air multipliers already
> branch on, so the flag is free and consistent). §0's wire anchors prove the
> grounded branch.

If the window outlives the flight (`w > landTick`, §5), the schedule simply
continues with ground-drag factors after touchdown — `D` stays a single
σ-linear sum; "travel truncates at touchdown" is the v1 approximation of
dropping those tail terms (§5 quantifies it).

### 1.3 The window: `w' = min(t*, shiftV + airTime(V0))`

- **cadence** `c = 10` ticks — the era full-hit cadence
  (`maxHurtResistantTime = 20`, effective cadence 10 [C §4]). Note: `c` is
  load-bearing but absent from [SPEC §4]'s knob list — open issue 1.
- `t*` = the ping-shifted horizon (§4): `c − round(rttA/2 / 50 ms)`.
- `airTime(V0)` — the kernel tick-sim [K-Motion `ticksToFall` precedent]: fold
  `V ← (V − 0.08) × 0.98` from the SHIPPED vertical `V0` (post cap, post
  air-multiplier — for [SIG] a standing hit ships
  `V0 = −0.0784 × 0.1 + 0.365 = 0.35716`), summing displacement, until the
  cumulative displacement ≤ −(launch height above ground). v1 assumes launch
  height 0 (ground level) [SPEC §3.2]; the general form uses
  `kinematics.distanceToGround` exactly as `CompensationQuery` already does.
- `shiftV` = the victim's half-RTT in ticks (§4): the flight starts on the
  victim's client that much after the stamp ships, so in server time the arc
  (and its touchdown) is shifted late by `shiftV`.

Representative airTimes from the fold (ground launch): V0 0.25 → 8 t,
0.30 → 9 t, 0.3283 → 10 t, **0.35716 → 10 t**, 0.40 → 11 t, 0.4607 → 13 t.
The [SIG] standing stamp's 10-tick flight matching the 10-tick cadence is why
the signature pocket exists at all. Degenerate guard: `airTime < 3` or
`w' < 1` ⇒ the servo declines (σ = 1) — no meaningful flight to shape.

### 1.4 The chase term — and where 0.2806 comes from

The attacker's ground locomotion per tick at terminal speed, from the same
integration order (input `a` added pre-move, ground decay `sq` post-move) with
the client's ×0.98 input damping:

```
stored terminal m∞ = sq · 0.98a / (1 − sq)
displacement/tick  = m∞ + 0.98a = 0.98a / (1 − sq)
```

With the sprint attribute `a = 0.13` [A2] and stone `sq = 0.546`:

```
0.98 × 0.13 / 0.454 = 0.280617 b/t   (= 5.612 m/s)
0.98 × 0.10 / 0.454 = 0.215859 b/t   (= 4.317 m/s walk)
```

— exactly the spec's named pinned constant **0.2806** [SPEC §3.2] and exactly
the m/s figures in [S-motion]'s table. (That table's "0.286 b/t" intermediate
is the undamped `a/(1−sq)`; the 0.98 input damping reconciles it with its own
5.6 m/s — lab-confirmable in one straight-line SimpleBoxer run.) Then

```
chase(w') = 0.2806 × (attr_walkNorm_attacker / 0.10) × w'
```

[K-Pace]'s walk-normalized attribute makes the ratio 1.0 at base speed and
1.6 at Speed III (0.449 b/t — [PACE-SPEC §1]'s figure). Refinements (§6 row 7,
all sub-0.35-block): the attacker's own ×0.6 self-slow on each attack tick
[C §1.5] costs ≈ 0.135 blocks of the 10-tick chase (geometric recovery at
`sq`), and line/jump deviations are ±0.3; the measured-velocity estimator of
§2 applied to the attacker recovers both.

### 1.5 The solve

Separation at the judgment moment (drift and tail terms deferred to §2/§5):

```
dNext(σ) = d0 + (R + σF) · D(w') − chase(w')
```

Setting `dNext(σ*) = target`:

```
        target − d0 + chase(w') − R · D(w')
σ*  =  ─────────────────────────────────────
                  F · D(w')

σ   = clamp(minFactor, maxFactor, 1 + gain · (σ* − 1))     [SPEC §3.2]
```

with defaults target 2.75, gain 1.0, clamps [0.8, 1.2] [SPEC §3.2/§6].

### 1.6 Worked examples (signature preset, base speed, zero ping)

**A — grounded touchdown-window hit (the combo norm), unclamped.**
Sprint hit, victim grounded at capture: `F = 0.825`, `R = 0.02` (residual
0.20 on-axis × friction 0.1 [SIG]), `V0 = 0.35716`, airTime 10, `w' = 10`,
`D = D_g(10) = 4.470559`, `chase = 2.806`, `d0 = 2.35`, target 2.75:

```
σ* = (2.75 − 2.35 + 2.806 − 0.02×4.470559) / (0.825×4.470559)
   = (0.40 + 2.806 − 0.089411) / 3.688211
   = 3.116589 / 3.688211 = 0.845014
```

Check by the tick fold: `H = 0.02 + 0.845014×0.825 = 0.717136`;
`dNext = 2.35 + 0.717136×4.470559 − 2.806 = 2.750000000` (1e-9 — the pin-grid
assertion class).

**B — the same hit at d0 = 2.60.** σ* = 0.777231 → clamps to **0.8**;
`dNext(0.8) = 2.834` — the pocket misses by +0.084 and the clamp is the honesty
boundary, exactly the design's graceful degradation.

**C — airborne boundary hit (era ordering reads the pre-landing flight
[C §1.4]).** Victim 0.2 above ground, vy −0.30 at the boundary read:
`F = 0.759`, `V0 = (−0.30×0.1 + 0.365)×0.98 = 0.3283`, airTime from +0.2 = 10,
`D = D_a(10) = 6.784265`, `R = 0.023`, `d0 = 2.5`:
σ* = 0.563 → clamps to 0.8, `dNext(0.8) = 3.97` — a full-sprint airborne hit
overshoots the pocket beyond what the clamp can hold (era physics wins,
combo honestly lost to escape)… **unless the victim is holding W** (§2: the
drift term moves σ* by up to ±0.83). The drift term is not a small correction;
at signature scale it is the difference between pocket-held and pocket-lost.

---

## 2. Victim self-drift

### 2.1 The era airborne steering model

Airborne, the movement-speed attribute is NOT consulted: the horizontal
move-relative factor is the fixed `0.02f` (`jumpMovementFactor` / field `aR`),
javap-verified on both a legacy and a modern jar [A1]. Sprinting sets the same
field 30% higher → **0.026** (the sprint-air variant; [S-motion] table
"0.02 / 0.026 (sprint)"). Conditions:

- straight-line held key: the client's ×0.98 input damping applies →
  effective per-tick Δv = `0.0196` (walk-air) / `0.02548` (sprint-air);
- diagonal (two keys): the input vector normalizes to magnitude 1 → the full
  `0.02` / `0.026`. Hard physics bound: **|per-tick input Δv| ≤ 0.026**.
- sprint-air requires holding forward; a knocked victim holding S steers at
  the 0.02-class rate.

### 2.2 The drift double sum

Input `a` (vector, per-tick) added pre-move, air drag q post-move; drift
velocity starting from 0 (see 2.4 — the stamp wipes it):

```
driftVel(k)   = a · geo(k)                      (k ticks after the stamp)
Drift(w)      = Σ_{k=1}^{w} a · geo(k)  =  a · S2(w)

S2(w) = Σ_{k=1}^{w} geo(k) = [ w − q·geo(w) ] / (1 − q)
```

`S2(10) = (10 − 0.91×6.784265)/0.09 = 42.514650`. Maxima over a 10-tick
window: walk-air straight `0.0196 × 42.5147 = 0.833` blocks; hard bound
`0.026 × 42.5147 = 1.105` blocks — the spec's "up to ~1 block", now exact.
Terminal drift velocity `a/(1−q)`: 0.218–0.289 b/t — comparable to the chase
rate, which is why held input decides pocket outcomes (§1.6 C).

### 2.3 Axis projection

The solve runs on the scalar separation along `u`; only `⟨a, u⟩` enters.
Lateral drift `l` perturbs the separation at second order,
`Δd ≈ l²/(2d) ≤ 1²/(2×3) ≈ 0.17` blocks for a full 1-block lateral strafe —
passed through unscaled by design [SPEC §3.2b.2]; the residual after
projection is ≤ 0.05 for realistic strafes.

### 2.4 The estimator — separating input from knock by exact decay subtraction

Source of truth: the measured per-tick horizontal velocity from the position
ring (one owning-thread sample per tick, 40 deep — `PositionRing`), which is
the CLIENT-integrated truth (held input stacks client-side and never enters
the server fields [C §5]; the ring sees positions, hence everything).

Let `u_t = p_t − p_{t−1}` (measured per-tick displacement vector) and `k_t`
the modeled knock move for that tick — the ledger's own decayed stamp
([K-Ledger] `current()` fold; the model the wire anchors validated in §0).
Define the input-driven component `e_t = u_t − k_t`. Airborne, it obeys
exactly

```
e_t = q · e_{t−1} + a_t
```

(`a_t` = that tick's input Δv, any direction, |a_t| ≤ 0.026). Therefore the
per-tick input is recovered **exactly, history-free**:

```
â_t = e_t − q · e_{t−1}  =  (u_t − q·u_{t−1}) − (k_t − q·k_{t−1})
```

**The estimator:** `â = clamp_|·|≤0.0264 ( mean of the last N valid â_t )`,
`N = 3` — long enough to average packet-cadence jitter, short enough to track
human input flips (2–5-tick scale). Validity gates: discard `â_t` with
`|â_t| > 0.03` (physics bound + slack — signals a model break, e.g. a missed
ground transition), ticks straddling a ledger `record`/`recordLiftoff`/
`recordLanding` event, and ring ticks without a fresh sample (a tick with 0
then 2 move packets aliases as a spike; the |·|>0.03 gate also catches it).
Fallback when < 2 valid samples: `â = 0` (drift term drops out, base solve
stands). Where the wire streams `player_input` (1.21.4+), blend: direction
from the keys, magnitude from the constants (0.02/0.026 by the sprint flag),
`â` as the version-universal fallback — open issue 5.

**The knock wipes the drift velocity, not the input.** The stamp REPLACES the
client's motion [S-motion], so the pre-hit drift *velocity* `e_0` does NOT
persist through the hit — no `e_0·geo(w)` term exists. What persists is the
*input being held* (human key state is sticky on 500 ms horizons), which is
exactly what `â` estimates. Post-hit drift therefore rebuilds from zero:

```
driftAway = ⟨â, u⟩            (positive = fleeing, away from the attacker)
DriftTravel(w') = driftAway · S2(w')
```

### 2.5 Entry into σ*

Drift is σ-independent additive travel:

```
dNext(σ) = d0 + (R + σF)·D(w') + driftAway·S2(w') − chase(w')

        target − d0 + chase(w') − R·D(w') − driftAway·S2(w')
σ*  =  ───────────────────────────────────────────────────────
                          F · D(w')
```

Example A with a fleeing walk-air victim (`driftAway = +0.0196`):
numerator −0.833 → σ* = 0.619 → clamped 0.8. With a W-holding victim
(`driftAway = −0.0196`): numerator +0.833 → σ* = 1.071 (unclamped — the servo
*strengthens* the knock to keep the chaser out). Prediction risk: an input
flip AT the hit inverts the term (worst 2×0.83); mitigations are shrinkage
(`â × κ`, κ ≈ 0.7 recommended v1) and the per-hit re-solve (each hit corrects
the last window's error). Grounded ticks inside the window use the ground
constants instead — §5.

---

## 3. The dynamic target

> **Superseded by target-v2 (2026-07-04).** The single-instant relaxation below
> (§3.3, `relaxed = safeNeed − tAllow·closing`) shipped as journal-only and was
> the mechanism the forensics traced the bimodal `{2.75, 2.95}` saturation to
> (the `closing ≤ 0` early-exit → anchor on 69.6% of hits; the σ=1 terminal-Δ
> degeneracy; a discrete `{0,1,2}` turn ladder with a dead ping term and a
> hard-NaN flick gate). `TargetMode.DYNAMIC` now names the **V2 exposure-budget
> min-selection** (`PocketServo.dynamicTarget`): `min{T ∈ [anchor, capEff] :
> e(T) ≤ tAllow}` over the terminal-window exposure integral `e(T)`, with the
> continuous `tAllow = tPing + turn` (§4 / §3.2 refined), `capEff = 3.0 −
> 0.5·chaseEMA − 0.17` clamped to `[anchor, hitCap]`, `closing ≤ 0 → the anchor`
> (pull-in posture), and a per-combo chase EMA + ≤0.05/hit target slew that kills
> the noise-driven cliff. The geometry (§3.1) and the exposure formulation (§3.2)
> are unchanged and carried through. The anchor default was retuned **2.75 →
> 2.85** (the lab's held-separation equilibrium; 2.75 was unreachable at
> signature scale). The §3.3 pseudocode is kept below as the derivation of record.

### 3.1 Exact geometry

Era constants: reach `r = 3.0` eye→nearest-AABB-point [K-Reach], player
height 1.8, eye 1.62, AABB half-width 0.3 [SPEC §2]. Attacker grounded (eye
at 1.62), victim feet at height `y(t)` from the σ-invariant vertical fold
(§1.3). In horizontal center-to-center separation `h`:

- **Attacker's shot** at the victim: while the victim's body spans the
  attacker's eye plane (`y(t) ≤ 1.62`, true everywhere under era apexes
  ≤ 1.25), the vertical gap is 0 → needs `h − 0.3 ≤ r_a`. The practical
  hittable cap is tighter than the exact 3.3 (client targeting, interpolation,
  the attacker's own view lag): **hitCap ≈ 2.95** [SPEC §2], anchor's basis.
- **Victim's answer** at the attacker: victim eye at `y(t) + 1.62`; the
  nearest attacker point from above is the head top at 1.8, so
  `Δ(t) = max(0, y(t) + 1.62 − 1.8) = max(0, y(t) − 0.18)` and the victim
  needs `sqrt((h − 0.3)² + Δ(t)²) ≤ r_v`. The victim can answer iff

```
h  ≤  h_safe(t) = 0.3 + sqrt(r_v² − Δ(t)²)        (r_v practical ≈ 2.9, v1)
```

  [SPEC §2]'s `√(reach² − Δ²) ≈ 2.7–2.8 at Δ ≈ 0.8` is this bound in the
  eye-to-AABB convention with the practical `r_v = 2.9`; the ±0.3 half-width
  cancels when comparing the two players' requirements, leaving the design's
  `Δ²/2h` margin. The geometry constants (2.9 practical vs 3.0 exact, the
  interpolation lag) are lab-calibration items — open issue 3.

### 3.2 The binding constraint is the window END, not the apex

`h_safe(t)` is minimal at APEX (Δ largest) — but `h(t)` there is maximal (the
knock's travel front-loads: at [SIG] scale the victim is 1.5+ blocks outside
`h_safe` from tick 2 to ~tick 8, §1.6). Near touchdown the two curves cross:
`Δ → 0` restores `h_safe → ~2.9–3.0` while `h(t)` descends toward `target`
(< h_safe(w') necessarily, else the attacker couldn't hit either — "combos
end on touchdown windows" [C §1.4] is this crossing). So the min over the
window of `h(t) − h_safe(t)` sits in the terminal ticks, and the honest
formulation of "geometrically unable to answer" is an **exposure budget**:

```
E(target) = #{ t ≤ w' : h(t) < h_safe(t) }  ≈  (h_safe(w') − target) / closing
closing   = chaseRate − knockRate(w') − driftRate(w')     (evaluate tick-wise
            over the last ~4 sim ticks; knockRate(w') = H·Π(w'), driftRate =
            driftAway·geo(w'))
```

Require `E ≤ t_allow`, the victim's earliest-retaliation cost:

```
t_allow = shiftV (victim RTT/2 in ticks, §4)  +  turnCost(yaw)
```

**Turn cost, bounded honestly:** a mouse flick is one packet — the true lower
bound is ZERO ticks for a flick-capable player. The relaxation therefore never
*trusts* facing; it grants small floors gated on the live yaw trend:
`|yawVsAxis| < 60° → 0`, `< 120° → 1`, else `2` ticks, granted only while the
measured recent yaw rate < 30°/tick (a player already flicking gets 0). Lab
yaw-trace data refines the table — open issue 4.

Mid-window safety (`t < t_x`) is non-binding at signature-scale stamps
(numbers above); the implementable function still evaluates the tickwise check
across the whole sim as a guard for soft-stamp/short-window corners, taking
the max required σ over any binding tick — the solve stays linear in σ per
tick since `h(t; σ)` is affine in σ (§1.1).

### 3.3 The exact function

```
double dynamicTarget(FlightSim sim, int wPrime, double yawVsAxisDeg,
                     double yawRateDegPerTick, int rttVicMs, Settings cfg):
    anchor = cfg.target            // 2.75, the config anchor [SPEC §2]
    if (!sim.valid()) return anchor            // FALLBACK: inputs unavailable
    shiftV  = rttVicMs / 100                   // victim RTT/2 in ticks, floor
    closing = tickwiseClosing(sim, wPrime, last 3 ticks)   // b/t
    if (closing <= 0) return anchor            // victim never re-enters
    dEnd    = max(0, sim.feetY(wPrime − shiftV) − 0.18)    // Δ at the swing
    safeNeed = 0.3 + sqrt(RV² − dEnd²)         // RV = 2.9 (practical, v1)
    tAllow  = shiftV + turnCost(yawVsAxisDeg, yawRateDegPerTick)  // {0,1,2}
    relaxed = safeNeed − tAllow × closing
    return clamp(anchor, cfg.hitCap /*2.95*/, relaxed)
```

Behavior: a facing, low-ping victim pushes `target` up toward `hitCap`
(minimizing terminal exposure to the physics floor
`E = (safeNeed − hitCap)/closing ≈ 1–2` ticks, which no target can remove —
conceded, era-real); a faced-away or laggy victim relaxes `target` down toward
the **anchor 2.75 — the hittability center** [SPEC §3.2b.3], buying rhythm
margin. The static 2.75 is both the floor and the whole answer whenever any
input is missing.

---

## 4. Ping horizons — where each half-RTT enters

RTT source: `LatencyModel` (play-PING / transaction probes, ms + jitter).
`ticks(ms) = round(ms / 2 / 50)` unless stated.

| Term | Enters | Formula | Why |
| --- | --- | --- | --- |
| attacker RTT/2 | the horizon `t*` | `t* = c − round(rttA/2/50)`, `w' = min(t*, shiftV + airTime)` | the attacker swings on client sight; their next click, thrown on rhythm off what they SAW, arrives and is judged against the victim's REWOUND position (the reach validator rewinds by rttA/2) — so the separation that decides hit N+1 is `h` at `c − rttA/2` ticks after hit N |
| victim RTT/2 (flight shift) | the arc alignment | server-time `y(t) = flight y(t − shiftV)`, `shiftV = floor(rttV/2/50)`; touchdown/airTime shift with it | the victim's client applies the stamp rttV/2 late; the ring-measured trajectory (and Δ(t), and the touchdown that truncates D) lives on that shifted clock |
| victim RTT/2 (retaliation) | `t_allow` (§3) | `t_allow += shiftV` | the victim clicks on THEIR client's view: an approaching attacker renders `shiftV × closing` farther than server truth, delaying the earliest effective answer by `shiftV` ticks (equivalently: h_safe shifted by `shiftV·closing` — the spec's "retaliation slack") |
| jitter | error band only | ±1 tick on `t*` | not modeled; `JitterCalculator` guards — treat as the §6 residual |

Rounding: round-to-nearest for `t*` (each ±0.5-tick residual ≈
`|closing| × 0.5 ≈ 0.05–0.1` blocks); **floor** for the slack terms
(`shiftV`) — conservative in the safety direction. Sensitivity: each tick of
horizon error moves `dNext` by `|knockRate(w') + driftRate − chaseRate|` ≈
0.10–0.19 b/t at signature scale, so an unmodeled 150–250 ms attacker costs
0.15–0.5 blocks — the §6 row.

---

## 5. Ground-tail drift

When `w' > landTick` (`G = t* − shiftV − airTime > 0` grounded ticks inside
the window — touchdown-window hits, soft verticals, long horizons):

- **The knock's ground tail** (σ-linear — fold it into `D`, exact): after
  touchdown the schedule continues at `sq` per tick. Dropping it (the v1
  truncation) under-counts by
  `m_land · sq(1 − sq^G)/(1 − sq)` per unit `H`, `m_land = Π(landTick)·q`.
  Stone, airTime 8, G = 2: `0.310 × 0.844 = 0.26 × H ≈ 0.19` blocks at
  H ≈ 0.72. On ICE (`sq = 0.8918` [K-Friction]) the tail barely decays —
  the §0 full tail is `m_land/(1 − sq)`: stone ×2.20, ice ×9.24 — the
  truncation is invalid there (open issue 6; era ice compounding [C §5b]).
- **The victim's own locomotion** (σ-independent additive): grounded steering
  uses the ATTRIBUTE — the victim's OWN effective movement speed (their
  Speed/Slowness matters here and only here [SPEC §3.2b.5]). Per-tick input
  `a_g = 0.98 × attr_victim_live` (sprint modifier included if fleeing-sprint);
  displacement rebuilding from ~0 over G ticks:

```
D_ground(G) = a_g · Σ_{k=1}^{G} (1 − sq^k)/(1 − sq)
```

  G = 2 sprint base: `0.1274 × 2.298 = 0.29` blocks; G = 3: 0.48; ×1.6 at
  Speed III: 0.47–0.77. Direction: reuse `â`'s direction (the held key
  persists through touchdown); magnitude switches from the 0.026-class air
  constant to the 0.127-class ground rate — ground ticks matter ~5× per tick,
  which is why even a 2-tick tail earns its own term.
- A jump inside the tail (jump-reset) restamps vy 0.42 (+boost) [K-Decay,
  C §1.6] and restarts a flight — unpredictable pre-hit (victim's post-knock
  choice); bounded inside this row's worst case, corrected by the next
  per-hit re-solve.

v1 (no ground-tail model) worst-case error: ~0.65 blocks (Speed III victim,
3-tick tail, stone) — see §6.

---

## 6. The error budget table (the lab round's acceptance contract)

"Worst if omitted" at representative combo states: signature preset, sprint
hit, w' = 10, d0 2.3–2.6, H 0.63–0.83. "Modeled residual" = expected remaining
error with the §-cited model in place. Priority order = the table order.

| # | Input | Worst-case landing error if OMITTED | Expected residual when MODELED |
| --- | --- | --- | --- |
| 1 | Launch ground-state drag branch (§1.2 — base-solve correctness) | **1.91** (grounded launch computed with the pure-air sum: `0.825 × (6.7843 − 4.4706)`) | exact (the captured grounded flag selects the branch); boundary-tick mis-capture rare and self-correcting next hit |
| 2 | Victim self-drift, airborne (§2) | **±1.11** hard bound (0.026 × 42.51); ±0.83 typical held-key walk-air | ≤ 0.30 p95 (N=3 estimator + κ=0.7 shrinkage + per-hit re-solve); input-flip-at-hit worst ≈ 0.5 after shrinkage |
| 3 | Attacker ping horizon (§4) | 0.15–0.50 @ 150–250 ms (1–2.5 ticks × 0.10–0.19 b/t) | ≤ 0.10 (jitter ±0.5 tick) |
| 4 | Victim ping flight shift (§4) | 0.15–0.35 @ 150–250 ms (touchdown misalignment: D truncation + Δ(t) read) | ≤ 0.10 |
| 5 | Dynamic target (§3) — not a landing error; an exposure error | static anchor concedes ~1–2 terminal exposure ticks a facing victim can use; over-relaxation risk 0 (anchor floor) | exposure ≤ t_allow by construction; geometry constants (2.9/interp) are the lab-calibrated residual |
| 6 | Ground tail (§5) | 0.30–0.65 (G = 2–3: victim sprint 0.29–0.48 + knock tail ~0.2); 0.77+ w/ Speed III victim | ≤ 0.15 (attr known, `â` direction reused, tail folded into D exactly) |
| 7 | Chase refinement (§1.4) | 0.14 (attacker ×0.6 self-slow recovery) + ±0.30 line/jump deviation | ≤ 0.15 (measured attacker-velocity trend via the §2 estimator) |
| 8 | Launch slipperiness (§1.2) | up to **1.81** on ice (`0.825 × geo(9) × (0.8918 − 0.546)`) | ~0 (the view's slipperiness field feeds `sq` — free); post-touchdown ice tail → open issue 6 |
| 9 | Axis projection (§2.3) | ≤ 0.2 (second order, full 1-block lateral) | ≤ 0.05 |
| 10 | Wire quantization (±3.9 clamp, 1/8000 shorts [K-Engine; S-motion]) | ≤ 1e-3 | — |

Implementation priority follows directly: the launch-state branch is a base
correctness fix (must land with the v1 solve, not the precision round);
then drift, ping horizons + dynamic target, ground tail, chase refinement.
This matches [SPEC §3.2b]'s stated order once row 1 is folded into "the base
solve".

---

## 7. Pin plan

### 7.1 Kernel tick-sim grid (unit pins, hand-checkable)

Reference: an INDEPENDENT per-test era integrator (the EraOracle precedent —
fold §0's model tick-by-tick; do not call the production sum code), asserting
the closed-form solve against the fold.

Core grid (full cross, 600 cases):

| Dimension | Values |
| --- | --- |
| launch state | grounded, airborne |
| V0 (shipped vertical) | 0.25, 0.30, 0.35716, 0.40, 0.4607 |
| d0 | 1.75, 2.00, 2.35, 2.60, 3.00 |
| R (shipped residual, on-axis) | 0, 0.02, 0.10, 0.25 |
| driftAway | −0.026, 0, +0.0196 |

Edge sets (defaults elsewhere): slip {0.6, 0.98} × launch grounded;
chase s_att {1.0, 1.6, 0.7}; rttA/rttV {0, 100, 250} ms (horizon/shift/slack
arithmetic incl. rounding); cadence {10, 12}; window-outlives-flight cases
(V0 0.25, t* 10 → G = 2 ground-tail fold); degenerate guards (airTime < 3,
w' ≤ 0 ⇒ σ = 1).

Assertions:
- unclamped: `|dNext(σ*) − target| < 1e-9` (fold vs closed form — §1.6 A
  demonstrates the class);
- clamped: σ pinned EXACTLY at the boundary; `sign(dNext − target)` as derived
  (§1.6 B/C);
- σ-invariance: the vertical fold and airTime byte-identical for
  σ ∈ {0.8, 1.0, 1.2} (the linearity premise);
- dynamic target: Δ(t)/h_safe tables at the three arcs (V0 0.357 / 0.4607 /
  0.30); exposure counting; turn-cost band edges; NaN/missing-input fallback
  = 2.75 exactly;
- estimator: synthetic ring traces — pure knock (â = 0 to 1e-12), knock +
  held-S (â recovers 0.0196 exactly), flip at tick 5 (N=3 tracks within 2
  ticks), spike tick (gate discards), clamp at 0.0264;
- zero-touch: servo off / gain 0 ⇒ every vector byte-identical to the
  profile's era stamps (the [SPEC §5] suite's kernel half).

### 7.2 SimpleBoxer lab protocol (wire-measured, per-input budget)

Setup: flat stone arena, signature preset, servo ON (gain 1, clamps
[0.8, 1.2]); both boxers scripted — the attacker's stop-distance ring OFF
(a stop ring is a de-facto w-tap machine and would contaminate cadence);
latency grid {5, 75, 150} × {5, 75, 150} ms attacker×victim via the boxer
latency lines.

Scripts: attacker holds W on the pair axis, clicks every 10 ticks when in
range. Victim: (a) idle, (b) hold S, (c) hold W, (d) A/D strafe alternating
5 ticks, (e) S→W flip at +5 t after each hit, (f) jump on landing
(jump-reset probe, ground-tail row).

Logging: per hit, the journal debug line
`{d0, R, F, V0, launchGrounded, slip, w', t*, â, chaseModel, target_dyn, σ*,
σ, predicted dNext}` (rides the comboFactor journal carrier); per tick, both
positions + yaws (server ring dump on combo end + boxer-side client logs).

Measurement: **landing separation = the axis-projected separation at the
rewound judgment tick `t*` of the NEXT swing** (from the ring, matching the
reach validator's rewind) — never at packet-send time. Per-hit error =
measured − predicted; aggregate per scenario × latency cell; unclamped and
clamped hits reported separately (clamped hits validate the boundary sign,
not the magnitude).

Ablations: debug flags disable one term at a time (drift, attacker-ping,
victim-ping, ground tail, dynamic target → anchor); each measured error delta
must land within ±50% of its §6 "omitted" row — that is the per-input
acceptance test.

Acceptance: scenarios a–d unclamped p95 |error| ≤ 0.35, median ≤ 0.15;
scenario e p95 ≤ 0.6 (the flip-risk bound, documented); pocket-hold metric:
mean combo length servo-on ≥ 1.5× servo-off at 150 ms victim ping with no
increase in victim retaliations landed. Plus one calibration run: straight
sprint displacement = 0.2806 ± 0.002 b/t (§1.4's damping note) and one
yaw-rate trace set to refine §3.2's turn-cost table.

---

## 8. The final integrated solve

```
w'        = min( c − round(rttA/2 / 50),  shiftV + airTime(V0, launchHeight) )
D(w')     = Σ_{k=1}^{w'} Π(k)          Π from the σ-invariant flight fold:
                                        launch slip·0.91 while grounded pre-move,
                                        0.91 airborne, slip·0.91 after touchdown
target    = dynamicTarget(...)          §3.3 (fallback: the 2.75 anchor)
chase     = 0.2806 × (attrA_walkNorm / 0.10) × w'      (or the measured trend)
tail      = D_ground(G) · sign(⟨â,u⟩ dir)               §5 (0 when G ≤ 0)

        target − d0 + chase − R·D(w') − ⟨â,u⟩·S2(w') − tail
σ*  =  ──────────────────────────────────────────────────────
                          F · D(w')

σ = clamp(0.8, 1.2, 1 + gain·(σ* − 1))
```

with `F` = post-taper × pace × air-mult fresh horizontal on the axis,
`R` = view residual × friction × air-mult on the axis, `S2(w) =
[w − 0.91·geo(w)]/0.09`, and every constant from [K-Decay]/[K-Friction]/
[A1]/[A2]/[C].

## 9. Open issues

1. **`windowTicks`/cadence source** — load-bearing but missing from
   [SPEC §4]'s knob list. Recommend the constant 10 (the era cadence [C §4])
   for v1; revisit against measured inter-hit gaps from the lab.
2. **Spec §3.2 formula amendment** — `dragSum` must branch on the launch
   ground state (the pure-air sum overshoots a grounded launch by ~1.9 blocks
   at signature values, §1.2), and freshEra/residualCarry must be defined
   post-air-multiplier, axis-projected (§1.1). The spec's own pin grid would
   catch this; amend before implementation.
3. **Victim-answer geometry calibration** — practical reach 2.9 vs exact 3.0,
   client entity-interpolation lag (~2–3 ticks, version-dependent), half-width
   conventions: the dynamic target's `safeNeed` needs the lab's calibration
   run before it ships as a computed default; until then target_dyn is
   journaled-but-anchored (2.75).
4. **Turn cost** — honestly boundable only at 0 for flick-capable players;
   the {0,1,2}-tick floors gated on measured yaw rate are the conservative
   shipped posture; lab yaw traces refine.
5. **`player_input` blend** (1.21.4+) into `â` — direction-from-keys +
   magnitude-from-constants proposed (§2.4); weights unspecified until wired.
6. **Ice / non-default slip post-touchdown tails** — modelable exactly inside
   `D` (σ-linear) but the pocket geometry itself changes (era ice compounding
   [C §5b]); recommend v1 declines (σ = 1) when the landing-segment slip
   > 0.7, revisit with an ice lab round.
7. **Chase model** — attr-model (spec-pinned) vs the measured attacker-velocity
   estimator (§2's machinery, covers self-slow + line deviation): recommend
   measured with the attr model as fallback; also confirm the 0.2806 damping
   note (§1.4) on the wire — [S-motion]'s table carries the undamped 0.286
   intermediate next to the damped 5.6 m/s.
8. **Seam coverage** — σ must ride the same single seam as pace (post-taper
   fresh + extras) so all three delivery paths (pre-send, region, blocked
   re-delivery carrying the vector) ship one truth; verify the blocked-knock
   direct-delivery path (2.4.0) journals the same σ.

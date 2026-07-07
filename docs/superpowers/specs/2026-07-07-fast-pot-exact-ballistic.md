# Fast-pot exact-ballistic predictor (Mental 2.4.5-beta)

**Status:** design approved (owner, 2026-07-07); **speed-band + lead round
(owner, 2026-07-07, as-built below).** Replaces the direction-only `PotsAim` with
a full closed-form ballistic solve so a fast pot bursts *exactly* just in front of
the thrower's predicted feet. The owner decisions that lock the shape:

- **Speed is a bounded BAND, not an exact target.** The magnitude is a free
  variable: the solver spends the *minimum* speed that lands the burst on the
  (led) predicted feet, bounded into **`[min-speed-multiplier, max-speed-multiplier]
  × vanilla` — the owner's `[0.5×, 1.5×]` band by default** (`min` ceiling 1.0,
  `max` floor 1.0, so `min ≤ 1 ≤ max` always). The solver can only tune speed up
  to `1.5×`; for any further trajectory refinement it uses the OTHER variables
  (`vx/vy/vz`/direction are all already free — the closed form fully determines the
  velocity vector per `N`). The band's only real restriction is *which impact-tick
  `N` is reachable*. (Was: a single `speed-multiplier` cap ≈ `[0, 3×]`.)
- **Lead the target.** The burst is aimed at `feet + throwerVel·(N + leadTicks)`
  (`lead-ticks` default ≈ **1.0**, clamp `[0, 5]`), landing it slightly *in front
  of* where the feet will be at impact so a running player moves INTO the cloud.
  `lead = 0` reproduces the un-led feet (byte-identical to the pre-lead intent).
- **Velocity-only — the spawn is never moved.** The potion launches from exactly
  where vanilla spawns it; the solve uses the launch velocity alone. Zero visual
  / anticheat surface. (The owner considered but declined spawn-position freedom.)

## Why the current predictor leaves accuracy on the table

`PotsAim.aim()` fixes the speed to `multiplier × vanilla` and only rotates the
direction toward the predicted feet, over a **continuous `½gT²`** gravity model
with **drag neglected**. Three gaps:

1. The real potion integrates **discretely** — `vy −= 0.05; v ×= 0.99; pos += v`
   each tick — not `½gT²`. Over a multi-tick flight the continuous approximation
   drifts, and drag (×0.99/tick) is dropped entirely.
2. Magnitude is locked, so the burst only points *toward* the feet at whatever
   speed the multiplier dictates; it cannot land *on* them.
3. Predicted feet is a raw `getVelocity()×T` extrapolation with no discrete
   flight-time consistency.

## Physics ground truth (nms-archaeology, cited)

From the 1.21 server decomp (`AbstractThrownPotion`, `ThrowableProjectile`,
`ThrowableItemProjectile`, `Projectile`):

- **Gravity `g = 0.05`** blocks/tick — `AbstractThrownPotion.getDefaultGravity()`
  overrides the `0.03` throwable base (the current `PotsAim.GRAVITY = 0.05` is
  therefore already correct; potions fall faster than eggs/snowballs/pearls).
- **Drag `d = 0.99`** per tick in air (`applyInertia`, `0.8` in water — ignored).
- **Tick order: gravity → drag → move.** `ThrowableProjectile.tick()` calls
  `applyGravity()` (`vy −= g`) then `applyInertia()` (`v ×= d`) then moves by the
  resulting delta (`pos += v`). So per tick `vy ← (vy − g)·d`, `vx,vz ← vx,vz·d`,
  then `pos += v`.
- **Spawn** `(x, eyeY − 0.1, z)` — `ThrowableItemProjectile(type, owner, …)`.
- **Splash range 4.0**, potency `1 − dist/4` (`AbstractThrownPotion.SPLASH_RANGE`,
  `onHitAsPotion`) — every centimetre of miss is lost heal, so landing precision
  is the whole game.
- A projectile does **not** collide with its owner (owner-grace, `checkLeftOwner`
  / `canHitEntity`), so a self-thrown pot bursts on the **ground** below the
  thrower, not on the thrower. "Land on the feet" therefore means *the potion's
  ground-impact x/z equals the predicted-feet x/z* (the feet stand on that ground).

Cross-version: `g` and `d` are potion-universal across 1.9.4→26.x. Only the tick
**order** differs (pre-1.13 moves before applying drag/gravity, a ≈1% range
difference — sub-centimetre over a 1–3-tick flight). v1 implements the modern
order and documents legacy as a bounded approximation; the current code models no
drag at all, so this is strictly far more accurate everywhere.

## The closed-form solve (core-pure, `PotsAim`)

For a fixed integer impact-tick `N`, the discrete recurrence sums in closed form.
With `H(N) = d·(1 − d^N)/(1 − d)` (horizontal range per unit launch velocity) and
`G(N) = (d·g/(1 − d))·(N − H(N))` (total gravity drop), a launch `v0` from spawn
`L` reaches, after `N` ticks:

```
x_N − L.x = v0.x · H(N)
z_N − L.z = v0.z · H(N)
y_N − L.y = v0.y · H(N) − G(N)
```

so, inverting for the launch that lands on target `(xt, yt, zt)` at tick `N`:

```
v0.x = (xt − L.x) / H(N)
v0.z = (zt − L.z) / H(N)
v0.y = (yt − L.y + G(N)) / H(N)
```

With `d = 0.99`: `H(N) = 99·(1 − 0.99^N)`, `G(N) = 4.95·(N − H(N))`. Worked:
`H(1)=0.99, G(1)=0.0495`; `H(2)=1.9701, G(2)=0.148005`; `H(3)=2.94040, G(3)=0.29500`.

**Led predicted feet** at impact tick `N`: `P(N) = feet + throwerVel·(N + lead)`
(all three axes; a grounded thrower has `vel.y ≈ 0`, so `yt = feetY` regardless of
the lead). Constant-velocity extrapolation is the right model here — the thrower is
actively inputting motion over a 1–3-tick flight, so friction-decay would mispredict.

**Why the un-led aim landed "behind" (owner playtest).** The un-led target
`feet + vel·N` places the burst at the tick-`N` feet, but the potion's ground burst
is *not* an instantaneous tick-`N` event: its collision is a swept/AABB test that
fires when the potion first dips into the ground block *during* tick `N`'s move — a
sub-tick moment strictly between `P(N−1)` and `P(N)`, i.e. slightly *behind* the
tick-`N` position along the thrower's motion. Independently, the owner wants the
cloud a touch ahead so a moving player runs INTO it. Both pull the same way, so one
knob covers both: `leadTicks` (≈ 1 tick of thrower motion) absorbs the sub-tick lag
and delivers the forward bias. It is the owner-facing lever; the sub-tick term is a
sub-`leadTicks` fraction folded into it (no separate correction).

**Objective — smallest in-band N on the direct-throw arm.** Walk `N = 1 … NMAX`;
for each, compute `v0(N)` against `P(N)` and its speed. Increasing `N` monotonically
*lowers* the required speed until the geometric minimum, past which the closed form
only yields slow **upward-arc lobs** (the pot thrown *up* to fall back onto the
feet) — never wanted for a self-heal — so the walk **stops at the first rise**.
Return the **first (smallest) N** whose `minSpeed ≤ |v0(N)| ≤ maxSpeed` — the
promptest exact-on-led-feet landing within the band (least prediction drift, most
"fast-pot"). Every in-band N lands *exactly* on the led feet by construction;
smaller N throws harder (up to the ceiling) and lands sooner. `NMAX = 20` (1 s) is a
safe ceiling; realistic N ≤ 6. A grounded target is always below the spawn, so an
in-band N normally exists.

**Fallback (no in-band N on the direct arm** — thrower outrunning the pot at the
ceiling, or a barely-below drop needing less than the floor): take the direct-arm
candidate whose required speed is *closest* to the band and clamp its magnitude to
the near edge — `maxSpeed` if it was too fast (ceiling clamp), `minSpeed` if too
slow (floor clamp) — the closest reachable direct throw toward the led feet. Rare at
the defaults; documented best-effort.

**Degenerate:** `maxSpeed ≤ 0` ⇒ `(0,0,0)` (unchanged).

## Semantics change: a bounded speed band + a forward lead

The redirect magnitude is bounded into `[min, max] × vanillaSpeed`, no longer a
single `≤ cap`. `min-speed-multiplier` (default `0.5`, clamp `[0.05, 1.0]`) is the
floor (a fast pot always has punch); `max-speed-multiplier` (default `1.5`, clamp
`[1.0, 5.0]`) is the ceiling (anticheat + splash-resolution sanity). `lead-ticks`
(default `1.0`, clamp `[0, 5]`) leads the predicted feet forward. The `PotsSuite`
magnitude assertion is `min×vanilla ≤ |v| ≤ max×vanilla`, joined by the off-server
**landing-accuracy** pins (forward-simulate the discrete physics from the redirect
for its `N` ticks and assert the burst lands on the *led* feet).

## API / structure

- `PotsAim.aim(Lx,Ly,Lz, Fx,Fy,Fz, Vx,Vy,Vz, minSpeed, maxSpeed, leadTicks)`
  returns `Aim(x, y, z, ticks)` — the launch velocity (in-band `minSpeed ≤ |v| ≤
  maxSpeed`, else clamped to the near edge) and the chosen impact tick `N` (for
  observability + pins). Kernel-vocabulary plain doubles, core-level
  (combat-adjacent gameplay math, not the frozen delivery core), exhaustively
  unit-pinnable. All three velocity components are solved independently; the spawn
  `L` is a pure input, never moved.
- `FastPotsUnit.redirect(launch, thrower, vanillaSpeed, settings)` unchanged
  signature, returns a `Vector` (drops `ticks`); `minSpeed = vanillaSpeed ×
  minMultiplier`, `maxSpeed = vanillaSpeed × maxMultiplier`, `leadTicks` from
  settings. Still reads `thrower.getVelocity()` (owning-thread, region-safe;
  fresher published view not worth the coupling for a 1–3-tick aim aid).

## Invariants

- **Zero-touch / era-exact:** `FAST_POTS` defaults OFF; nothing changes for a
  disabled feature, and no era combat surface is touched (it only re-aims a
  player's own thrown pot).
- **Core purity:** the solve is plain doubles; all Bukkit reads stay in
  `FastPotsUnit`.
- **No spawn move / all components free:** the potion entity is never teleported —
  zero anticheat/visual surface — and the band restricts only which impact tick is
  reachable, never any velocity component.
- **Well-ordered band:** `min ≤ 1 ≤ max` holds by the individual clamps (floor
  ceilinged at 1.0, ceiling floored at 1.0), so no `min ≤ max` reconciliation is
  needed.

## Test pins (hand-computed)

- **Resting, band `[0.5, 1.6]`, lead 0:** feet directly below the eye ⇒ `N=1`,
  `aim=(0, −1.5863636, 0)`, `ticks=1`, `|v| = 1.5863636 ∈ [0.5, 1.6]` (the band is
  a budget, not the magnitude).
- **Moving thrower (vx=0.2), band `[0.5, 1.5]`, lead 0:** `N=1` needs 1.599 > 1.5
  ⇒ picks `N=2`, `aim=(0.2030353, −0.747167, 0)`, `ticks=2`; the forward-sim lands
  at the un-led `(0.4, 0, 0) = feet + vel·N` (byte-identical to the pre-lead intent).
- **Lead ahead (vx=0.2), band `[0.5, 1.5]`, lead 1.0:** same `ticks=2`,
  `aim=(0.3045531, −0.7471677, 0)`; the forward-sim lands at `0.6 = feet + vel·(N+1)`,
  exactly `vx·lead = 0.2` **in front of** the un-led feet-at-impact `0.4`.
- **Landing accuracy (load-bearing), lead 1.0:** asymmetric geometry/motion;
  forward-simulate `vy −= 0.05; v ×= 0.99; pos += v` for `ticks` steps from the aim
  and assert the final position equals `feet + vel·(ticks + lead)` within `1e-6`.
- **Ceiling clamp:** fast mover (vx=2.5), band `[0.5, 1.0]` ⇒ no in-band N; the
  direct-arm minimum `N=4` clamps down to the ceiling, `|v| == 1.0`.
- **Floor clamp:** resting 1.62-drop, narrow band `[0.46, 0.55]` in the gap between
  `N=2 (0.7472)` and `N=3 (0.4506)` ⇒ closest candidate `N=3` clamps UP to the
  floor, `aim=(0, −0.46, 0)`, `|v| == 0.46`.
- **`maxSpeed ≤ 0` ⇒ zero.**
- **Smallest-N:** a fast mover picks `ticks > 1` and `N−1` would exceed the ceiling.

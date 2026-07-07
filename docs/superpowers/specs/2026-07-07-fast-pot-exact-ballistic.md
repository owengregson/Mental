# Fast-pot exact-ballistic predictor (Mental 2.4.5-beta)

**Status:** design approved (owner, 2026-07-07). Replaces the direction-only
`PotsAim` with a full closed-form ballistic solve so a fast pot bursts *exactly*
on the thrower's predicted feet. Two owner decisions lock the shape:

- **Speed is a CAP/budget, not an exact target.** The magnitude is a free
  variable: the solver spends the *minimum* speed that lands the burst on the
  predicted feet, bounded above by `speed-multiplier × vanilla`. (Was: magnitude
  pinned to `multiplier × vanilla`, direction the only lever.)
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

**Predicted feet** at impact tick `N`: `P(N) = feet + throwerVel·N` (all three
axes; a grounded thrower has `vel.y ≈ 0`, so `yt = feetY`). Constant-velocity
extrapolation is the right model here — the thrower is actively inputting motion
over a 1–3-tick flight, so friction-decay would mispredict.

**Objective — smallest feasible N.** Iterate `N = 1 … NMAX`; for each, compute
`v0(N)` against `P(N)` and its speed. Return the **first (smallest) N** whose
`|v0(N)| ≤ cap` — the promptest exact-on-feet landing within the speed budget
(least prediction drift, most "fast-pot"). Every feasible N lands *exactly* on the
predicted feet by construction; smaller N throws harder (up to the cap) and lands
sooner. `NMAX = 20` (1 s) is a safe ceiling; realistic N ≤ 6. A grounded target is
always below the spawn, so a feasible N always exists (in the limit the pot is
simply dropped).

**Fallback (no feasible N ≤ cap** — thrower outrunning the pot at the cap): take
the minimum-required-speed candidate and scale its velocity to the cap (direction
preserved) — the closest reachable throw toward the predicted lead. Rare at the
default `multiplier 3` (cap ≈ 1.5 b/t vs a 0.28 b/t sprint is comfortably
feasible at N≈2); documented best-effort.

**Degenerate:** `cap ≤ 0` ⇒ `(0,0,0)` (unchanged).

## Semantics change: `speed-multiplier` is now a ceiling

The redirect magnitude is `≤ multiplier × vanillaSpeed`, no longer `==`. The
multiplier still bounds the max speed (anticheat + splash-resolution sanity). The
`PotsSuite` magnitude assertion changes from equality to `≤ cap`, joined by a
**landing-accuracy** assertion: forward-simulate the discrete physics from the
redirect for its `N` ticks and assert the burst lands on the predicted feet.

## API / structure

- `PotsAim.aim(Lx,Ly,Lz, Fx,Fy,Fz, Vx,Vy,Vz, speedCap)` returns
  `Aim(x, y, z, ticks)` — the launch velocity (`|v| ≤ speedCap`) and the chosen
  impact tick `N` (for observability + pins). Kernel-vocabulary plain doubles,
  core-level (combat-adjacent gameplay math, not the frozen delivery core),
  exhaustively unit-pinnable.
- `FastPotsUnit.redirect(launch, thrower, vanillaSpeed, settings)` unchanged
  signature, returns a `Vector` (drops `ticks`); `speedCap = vanillaSpeed ×
  multiplier`. Still reads `thrower.getVelocity()` (owning-thread, region-safe;
  fresher published view not worth the coupling for a 1–3-tick aim aid).

## Invariants

- **Zero-touch / era-exact:** `FAST_POTS` defaults OFF; nothing changes for a
  disabled feature, and no era combat surface is touched (it only re-aims a
  player's own thrown pot).
- **Core purity:** the solve is plain doubles; all Bukkit reads stay in
  `FastPotsUnit`.
- **No spawn move:** the potion entity is never teleported — zero anticheat/visual
  surface.
- **Multiplier ≥ 1 clamp** stays (never slower than vanilla).

## Test pins (hand-computed)

- **Resting, cap generous (1.6):** feet directly below the eye ⇒ `N=1`,
  `aim=(0, −1.5863636, 0)`, `ticks=1`, `|v| = 1.5863636 ≤ 1.6` (cap is a ceiling,
  not the magnitude).
- **Moving thrower (vx=0.2), cap 1.5:** `N=1` needs 1.599 > cap ⇒ picks `N=2`,
  `aim=(0.20304, −0.74716, 0)`, `ticks=2`; the forward-sim lands at `(0.4, 0, 0) =
  P(2)`.
- **Landing accuracy (load-bearing):** for several geometries, forward-simulate
  `vy −= 0.05; v ×= 0.99; pos += v` for `ticks` steps from the aim and assert the
  final position equals `feet + vel·ticks` within `1e-6`.
- **Magnitude ≤ cap** for arbitrary asymmetric geometry/motion.
- **`cap ≤ 0` ⇒ zero.**
- **Smallest-N:** a fast mover picks `ticks > 1` and `N−1` would exceed the cap.

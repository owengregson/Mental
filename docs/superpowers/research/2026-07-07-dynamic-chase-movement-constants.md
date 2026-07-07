# Dynamic-chase movement constants — block-slow, sprint+block, sprint re-accel

Purpose: feed the pocket servo's chase-prediction fallback. The servo predicts
where the ATTACKER will be during the victim's airtime to place a combo knock;
when no measured attacker-velocity trend is available it falls back to a model
of the attacker's horizontal ground speed as they sprint-reset (w-tap /
blockhit) and re-accelerate. This note pins the three constants that model needs,
each read from the actual client/server code (nms-archaeology approach).

Sources this round:
- **Fresh decompile of the 1.8.9 CLIENT jar**
  (`~/Library/Application Support/minecraft/versions/1.8.9/1.8.9.jar`, CFR via
  `legacy-lab/cfr.jar`, output obfuscated). `EntityPlayerSP.onLivingUpdate`
  decompiles to **`bew.java`** (obf). This is the authority for the block-slow
  and the sprint gate — both are **client-side** and are NOT present in the
  server jar (see caveat).
- **Server-jar decompile** `legacy-lab/decomp-1.8.9/pr.java` (EntityLivingBase,
  `moveEntityWithHeading`) — the ground friction/accel/drag integrator. The
  server carries the same shared physics the client runs.
- **Existing kernel constants** `kernel/.../math/Decay.java`,
  `PocketServo.SPRINT_GROUND_SPEED`, `GroundFriction`.
- **SimpleBoxer client-motion-pins** `~/Documents/SimpleBoxer/docs/research/
  2026-06-06-client-motion-pins.md` — the steady-state equilibria table.
- **Compendium** `docs/research/2026-06-06-combat-compendium.md` §2 (residual
  physics). The compendium documents the *knocked-victim residual* decay
  exhaustively but only mentions the *input-side* ground-accel constant in
  passing (§2, "client-side for real players … irrelevant to the residual").
  The block-slow multiplier and the sprint gate are **not** in the compendium;
  both were read fresh from `bew.java` this round.

---

## 1. Block-slow movement multiplier = **0.2** (on movement INPUT)

**Fresh decompile — `bew.java:469-472` (1.8.9 client `EntityPlayerSP.onLivingUpdate`):**

```java
if (this.bS() && !this.au()) {   // isUsingItem() && !isRiding()
    this.b.a *= 0.2f;            // movementInput.moveStrafe  *= 0.2
    this.b.b *= 0.2f;            // movementInput.moveForward *= 0.2
    this.d = 0;                  // sprintToggleTimer = 0
}
```

- Using an item — **sword blocking** is right-click-holding the sword, i.e.
  `isUsingItem()` true — multiplies the **movement input** (both strafe and
  forward axes) by **0.2** before it feeds the movement integrator. It does not
  multiply `motion` directly; it scales the input impulse, so the steady-state
  displacement scales by the same 0.2 (displacement is linear in the accel
  term, §3).
- It also zeroes the double-tap sprint timer.

**Where it holds:** client-side, 1.7.x–1.8.9 (sword blocking exists only through
1.8; 1.9 replaced it with shields). Value 0.2 is stable across that range.

**Caveat (load-bearing for Mental):** this is **client-side** physics. Player
movement is client-authoritative; the server only adopts the resulting position
packets. Proof: the server-jar decompile (`legacy-lab/decomp-1.8.9`, 1089
files incl. netty/commons) contains **no** `EntityPlayerSP`, no sprint-toggle
timer, and no input-slowdown — grepped and confirmed. So Mental cannot *compute*
the block-slow from server code; it can only *observe* it in the attacker's
position ring. On a **modern client (1.9+)** a plugin-simulated 1.8 sword-block
applies **no** movement slowdown at all (the client does not know it is
"using" a sword to block), so a modern blockhitter is NOT slowed unless a plugin
imposes a Slowness effect. See §2.

---

## 2. Sprint + block composition — **block CANCELS sprint** (era); walk×0.2

Answer to "does raising a block cancel sprint, or can you sprint AND block?":
**in vanilla 1.8.9 raising a block cancels sprint.** A block-holder is
**walk+block speed**, not `sprint × block-mult`. The gate, all fresh from
`bew.java`:

```java
float f = 0.8f;                                   // bew.java:466 — sprint fwd threshold
...
if (isUsingItem() && !isRiding()) { moveForward *= 0.2f; ... }   // bew.java:470  → 0.98*0.2 = 0.196
...
// START sprint (bew.java:479 / :486): requires
//   onGround && moveForward >= 0.8 && !isSprinting && food>6 && !isUsingItem && !blindness
// STOP sprint (bew.java:489):
if (isSprinting() && (moveForward < 0.8f || isCollidedHorizontally || foodTooLow))
    setSprinting(false);
```

- The block-slow (line 470) runs **before** the sprint stop-check (line 489) in
  the same tick. Blocked `moveForward = 0.98 × 0.2 = 0.196 < 0.8` → the stop-gate
  fires → `setSprinting(false)` **that tick**. Sprint's ×1.3 speed modifier is
  removed before the tick's movement integrates.
- The START gate (lines 479, 486) additionally requires `!isUsingItem()` **and**
  `moveForward >= 0.8` — both false while blocking — so you cannot re-start
  sprint while the block is held.
- Net: **holding block = walk speed with input ×0.2**, sprint fully suppressed.
  There is no "sprint × 0.2" simultaneous state reachable on a real 1.8.9 client.

**Effective ground speeds (flat stone, blocks/tick — derivation in §3):**

| stance | accel a | move/tick (b/t) | m/s |
| --- | --- | --- | --- |
| sprint (base) | 0.13×0.98 = 0.1274 | **0.28061** | 5.612 |
| walk (base) | 0.10×0.98 = 0.098 | 0.21586 | 4.317 |
| **walk + block** (era, sprint cancelled) | 0.098×0.2 = 0.0196 | **0.04317** | 0.863 |
| *hypothetical* sprint × block-mult | 0.1274×0.2 = 0.02548 | 0.05612 | 1.122 |

The `sprint × block-mult` row (0.0561 b/t) is the value the composition *would*
produce IF sprint survived a raised block — it does **not** in vanilla 1.8.9.

**Reconciling the owner's observed "distinct sprint+block speed":** on Mental's
actual target (modern clients on Paper 1.9.4→26.x) a plugin-driven 1.8 blockhit
applies **no** client slowdown, so the blockhitter closes at essentially **full
sprint (~0.2806 b/t)**, punctuated by the brief per-hit sprint-RESET dip
(blockhit is used as a sprint-reset tool, exactly like a w-tap — the block tap
cancels sprint, the release re-arms the +1 sprint-knockback level, §3). That
full-sprint-with-dips profile is the "distinct" speed the owner sees; the
era-authentic slowed value (0.0432 b/t) only applies to a genuine legacy client
or if the server imposes an equivalent Slowness. **Chase-model implication:**
model a modern blockhitter as a near-full-sprint closer on a reset cadence, not
as a 0.2×-slowed mover.

**Where it holds:** 1.8.x client (and 1.7.x — same gate shape). Server-side the
×1.3 sprint modifier (0.13) is confirmed both eras (client-motion-pins;
`pr.java` move-speed field `bI()`); the START/STOP gate is client-only.

---

## 3. Sprint re-acceleration ramp — approach factor **r = ground drag = 0.546**

The ground movement integrator, **server-jar `pr.java:972-1003`
(`EntityLivingBase.moveEntityWithHeading`)** — the same physics the client runs
for players:

```java
float f4 = 0.91f;
if (onGround) f4 = blockBelow.slipperiness * 0.91f;         // pr.java:974  → 0.6*0.91 = 0.546 (stone)
float accel = 0.16277136f / (f4*f4*f4);                     // pr.java:976  → 0.546³/0.546³ = 1.0 on stone
float speed = onGround ? getAIMoveSpeed()*accel : jumpMovementFactor;  // pr.java:977 (bI() = move-speed attr)
moveFlying(strafe, forward, speed);                          // pr.java:978  add input impulse
...
this.motionY -= 0.08; this.motionY *= 0.98;                 // pr.java:1000-1001 gravity
this.motionX *= f4; this.motionZ *= f4;                     // pr.java:1002-1003 horizontal drag
```

Per-tick horizontal recurrence, letting `f1 = f4 = slip×0.91` (ground drag) and
`a` = the input impulse (`getAIMoveSpeed × accelFactor × moveForward`):

- carried motion:   `D_t = (D_{t-1} + a) · f1`
- **displacement** (distance moved that tick): `s_t = D_{t-1} + a = f1·s_{t-1} + a`

The displacement recurrence is first-order with ratio `f1`, so from a sprint
reset to rest (`s_0 ≈ 0`):

> **s(t) = v_sprint · (1 − f1^t),  with r = f1 = slipperiness × 0.91 = 0.546 (stone).**

That is exactly the requested `speed(t) ≈ sprint·(1 − r^t)` form.

**(a) Steady sprint ground speed:** `v_sprint = a/(1 − f1) = 0.1274/0.454 =`
**0.28061 b/t** (5.612 m/s) — matches `PocketServo.SPRINT_GROUND_SPEED = 0.2806`
and the canon 5.612 m/s. (`a = getAIMoveSpeed_sprint × 1.0 × moveForward = 0.13
× 1.0 × 0.98 = 0.1274`; sprint attr = 0.1 base × 1.3 modifier.)

**(b) Approach factor r and ticks-to-95%:** `r = 0.546` on stone.

| target | condition | ticks |
| --- | --- | --- |
| 90% | 0.546^t ≤ 0.10 | 4 (3.8) |
| **95%** | 0.546^t ≤ 0.05 | **5 (4.95 = 0.25 s)** |
| 99% | 0.546^t ≤ 0.01 | 8 (7.6) |

From a realistic **w-tap / blockhit** reset the forward key drops for only ~1
tick (dip to `0.546 × 0.2806 ≈ 0.153 b/t`), not to full rest, so recovery to
~95% takes **~3–4 ticks** in practice; the `(1 − r^t)` from-rest figure (5 ticks)
is the conservative bound.

**Slipperiness dependence:** `r = slip × 0.91`. Ice/packed-ice `r = 0.98×0.91 =
0.8918` → ~26 ticks to 95% (a much slower ramp) — but the servo already declines
on ice landings (`landingSlip > 0.7`, `PocketServo.ICE_DECLINE_SLIP`), so the
stone `r = 0.546` is the operative value. Slime `r = 0.728`.

**Where it holds:** the integrator constants (0.91 air drag, slip×0.91 ground
drag, 0.16277136 accel numerator = 0.546³, 0.08 gravity, 0.98 damping) are
byte-identical 1.7.10 → 26.x (`pr.java`, `decomp-1.21.11/LivingEntity.java:2981`
uses the modern-form `0.21600002/friction³` with `friction`=raw 0.6 slipperiness,
same factor-1.0-on-stone result). The sprint attr ×1.3 and base 0.1 are stable
across the range.

---

## 4. How Mental replicates this — we're replicating 1.8, not running on it

The constants above are **era-client** physics. Mental runs on modern Paper with
mostly modern clients, and it replicates the 1.8 *combat* model, NOT the 1.8
*movement* model. What actually reaches the servo differs from bare era truth in
three load-bearing ways.

### 4a. Mental does NOT slow a blocking player's movement

`core/.../feature/damage/SwordBlockingUnit.java` (the ported 1.7-style sword
block) is **damage-only**: a blocked melee hit is reduced by the 1.8
`(damage−1)×0.5` (`kernel/.../math/SwordBlockReduction.java`) at
`EventPriority.HIGH`, "while knockback is left FULL — the event is never
cancelled and **velocity is never touched**" (SwordBlockingUnit:49-50). Grep
confirms Mental applies **no** `Slowness` effect and no `setWalkSpeed` for
blocking anywhere. So the §1 block-slow (0.2 input multiplier) is **not
reproduced by the plugin** — Mental imposes zero movement penalty for blocking.

### 4b. Modern clients keep sprint through a block; Mental re-arms only the bonus

Mental's own note (`SwordBlockingUnit:58-65`): *"Starting a block dropped the
attacker's sprint in 1.7/1.8 and the re-engage re-earned the sprint knockback
bonus; modern clients **keep the sprint flag through an item-use block**, so that
STOP/START never crosses the wire."* `resetSprintForBlock` re-arms only the
**sprint-knockback freshness** (the +1 level, gated on the RAW client sprint
flag via `SprintWire`) — it does **not** drop the attacker's speed. Net for a
**modern-client** blockhitter under Mental: sprint flag stays set, no movement
slowdown → they move at **full sprint (~0.2806 b/t)** while blocking. That is the
`sprint × block` simultaneity that §2 proved impossible on a real 1.8 client —
Mental permits it because it reproduces neither the client stop-gate nor the
input slowdown.

### 4c. The effective speed depends on the attacker's CLIENT version

Because §1/§2 are client-side, the in-world speed of a Mental blockhitter splits
by protocol:

| attacker client | block-slow applied? | sprint through block? | effective ground speed |
| --- | --- | --- | --- |
| **modern (1.9+)** | no (can't sword-block; Mental adds none) | yes (flag kept) | **~0.2806 b/t** (full sprint) |
| **legacy 1.8 via ViaVersion** | yes (client runs `bew.java`) | no (client stop-gate cancels) | **~0.0432 b/t** (walk-block) |

The owner's observed "distinct sprint+block speed" is therefore a **legacy-client
artifact** (1.8 clients via Via genuinely crawl while blocking), not a server
behavior — modern-client blockhitters are NOT slower than pure sprint under
Mental. This is the single most important correction to the naive "blockhitter =
slower" assumption in the dynamic-chase spec.

### 4d. Mental's chase is measurement-first — the constants calibrate the fallback

`ComboPredictor.build` (`core/.../feature/combo/ComboPredictor.java:159-168`)
prices the chase from `PocketServo.windowChaseRate` — the attacker's **actual
axis displacement over the just-completed inter-hit gap** (per-combo EMA). This
**measures the real speed regardless of client version**, so Mental does not need
the era block-slow constant to get the right chase for an *ongoing* combo. The
`SPRINT_GROUND_SPEED × chaseFactor(attr)` attribute model
(`PocketServo.chaseFactor`, §1.4 fallback) is used only for a **fresh combo with
no window yet** (`NaN`).

The new **`kernel/.../math/DynamicChase.java`** (added today, spec
`docs/superpowers/specs/2026-07-07-servo-input-driven-dynamic-chase.md`)
implements exactly the `speed(t) = steadySpeed·(1 − r^t)` ramp this note derives,
and its `BLOCKHIT` technique wants `effectiveSpeed` = "block-slowed sprint" with
`r` = the movement approach factor, both flagged "calibrate against NMS, do not
guess." This note is that calibration:

- **`r` (rampFactor)** → **0.546** on stone (= slip×0.91, §3). Version-blind
  1.7→26.x.
- **BLOCKHIT `effectiveSpeed`** → **only** apply the ×0.2 block-slow when the
  attacker is on a **legacy protocol** (ViaVersion-detected 1.8 client), giving
  ~0.0432 b/t (walk-block, since the era client also cancels sprint). For a
  **modern-client** attacker, BLOCKHIT `effectiveSpeed` must stay at **full
  sprint** (~0.2806) — the block-slowed value would under-predict the close and
  mis-place the victim, the exact failure the servo is trying to avoid. When in
  doubt, the measured-ring fallback (4d) self-corrects within one cycle.
- `DynamicChase` is present but **not yet wired** into `ComboPredictor` (grep:
  no callers) — it lands in step (4) of the spec's sequencing.

**Caveat / open question for the implementer:** the dynamic-chase spec's core
assertion ("a blockhitting attacker moves at sprint+block speed, meaningfully
slower than pure sprint") holds for legacy-client attackers only. The
`ResetModel.effectiveSpeed` resolution should read the attacker's protocol
version (or gate the ×0.2 behind a legacy-client probe) rather than assuming
every blockhit is slowed; otherwise modern-client blockhits get systematically
under-chased. The measured-ring rung remains the honest ground truth either way.

---

## Provenance summary (compendium vs fresh decompile)

| Answer | Value | Source |
| --- | --- | --- |
| Block-slow multiplier | **0.2** (on move input) | **Fresh decompile** `bew.java:470-471` (1.8.9 client). Not in compendium. |
| Sprint+block → block cancels sprint; walk-block **0.0432 b/t** | gate at `bew.java:466/479/486/489` | **Fresh decompile**. Not in compendium. |
| Sprint steady speed | **0.2806 b/t** (5.612 m/s) | Kernel `SPRINT_GROUND_SPEED` + client-motion-pins; re-derived from `pr.java`. |
| Re-accel approach factor r | **0.546** (=slip×0.91), 95% in **5 ticks** | **Derived** from `pr.java:974,1002-1003` integrator + `Decay.AIR_DRAG`/`GroundFriction`. Compendium §2 states the constants but not the ramp. |

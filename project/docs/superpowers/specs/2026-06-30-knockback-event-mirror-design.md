# Knockback-event mirror — making Mental authoritative at `EntityKnockbackEvent`

**Date:** 2026-06-30
**Status:** approved, implementing
**Target release:** 2.1.3

## Problem

A long-running "downward knockback on the airborne second combo hit, on Folia"
report drove the entire 2.1.1 + 2.1.2 audit (≈19 fixes). The symptom never
changed across any of them. Root cause, confirmed against `folia-1.21.11.jar`
bytecode and both codebases:

- Mental applies its knockback **only** at `PlayerVelocityEvent` (HIGH) — the
  latest point, just before `ServerEntity.sendChanges` builds the
  `ClientboundSetEntityMotionPacket`. There is no `EntityKnockbackEvent`
  listener in Mental.
- SimpleBoxer (the Folia test harness), **on Folia only**, captures the
  knockback it receives from Paper's `EntityKnockbackEvent` at MONITOR
  (`eventBasedKnockback = autoTicksEntities() && eventAvailable()`), computing
  `victim.getVelocity() + event.getKnockback()`. On classic Paper it instead
  polls `hurtMarked` (the *final* deltaMovement, after Mental's override).
- `EntityKnockbackEvent` fires **inside** `LivingEntity.knockback(...)`, *before*
  `PlayerVelocityEvent`, carrying **vanilla's** delta. For an airborne victim
  vanilla keeps the current vertical (`onGround() ? min(0.4, dm.y/2+strength) :
  dm.y`), so the delta `y` is `0` → SimpleBoxer reconstructs the falling
  velocity → **downward**. Grounded hit 1 gets `+0.4`, so only the airborne 2nd
  hit looks wrong.

So SimpleBoxer-on-Folia measures vanilla's knockback, never Mental's — by
construction. Real clients on Folia already receive Mental's correct value (the
tracker fires `PlayerVelocityEvent` and sends the result). "Nothing changed"
because every fix lived in the `PlayerVelocityEvent`/pending path that this
observer never reads.

## Goal

Make any observer of the standard knockback event — anticheats, SimpleBoxer,
other plugins — see **Mental's** value instead of vanilla's, on the servers
where that event exists (1.20.6+), **without changing the final client
velocity**. This also future-proofs against the whole "observer sees vanilla
KB" class of report (e.g. movement-prediction anticheats on modern servers).

## Non-goals

- No change to the final wire velocity: `PlayerVelocityEvent` stays the single
  authoritative apply. The mirror only corrects the *intermediate, observable*
  delta so it matches what will ship.
- No new config knob. No behavior below 1.20.6.

## Approach (chosen: complement / mirror)

A new listener on `EntityKnockbackEvent` (HIGH, `ignoreCancelled = true`) that
**peeks** the victim's head pending and sets the knockback delta so vanilla's
`setDeltaMovement(current + knockback)` lands exactly on the value
`onPlayerVelocity` will ship. It does **not** consume the pending and does
**not** fire `KnockbackApplyEvent` — those stay at `PlayerVelocityEvent`, the
authoritative apply. HIGH so it writes before MONITOR observers like SimpleBoxer;
`ignoreCancelled` so it yields when a third party already cancelled the knockback.

### Why complement, not "move authority"

The `PlayerVelocityEvent` path is matrix-pinned and carries the pre-send
adoption + duplicate suppressor + API event. Leaving it untouched and only
mirroring keeps the regression surface to "what mid-pass observers see," which
is exactly the bug, and keeps the final velocity provably identical.

## Components

### `Capabilities.knockbackEvent` (common)
`classPresent("io.papermc.paper.event.entity.EntityKnockbackEvent")`. Absent
below 1.20.6 → no listener registered → byte-identical, zero-touch.

### `PendingStore.peekLiveHead(victim, now, expiry)` (core, package-private)
Returns the oldest non-expired pending **without removing anything** (expired
heads are dropped only by `pollLive` at the real apply). Read under the
per-victim `computeIfPresent` lock, consistent with the store's
one-linearization-point rule. Returns `null` when the victim has no live pending.

### `KnockbackPipeline.peekMirror(Player victim)` → `MirrorDecision` (core)
Non-consuming. `MirrorDecision` is a public record `(boolean cancel, @Nullable
Vector target)`; `NONE` = `(false, null)`.
- no live head → `NONE` (leave vanilla untouched: OCM-owned, vanilla
  passthrough, non-Mental hit).
- head `vector == null` → `CANCEL` (the resistance roll will suppress; the
  mirror cancels the event so observers see no knockback, matching the
  cancelled `PlayerVelocityEvent`).
- otherwise → `target` = the value `onPlayerVelocity` ships for that head:
  `preDelivered` when it wins, else `deliveryAdjusted(vector)`.

To guarantee the mirror can never disagree with the eventual apply, the ship
computation is extracted into a shared seam used by **both** `onPlayerVelocity`
and `peekMirror`:
- `static boolean preDeliveredWins(Pending p, Vector apiVelocity)` —
  `p.preDelivered() != null && apiVelocity.equals(p.vector().toBukkit())`
  (pure; this is the exact branch `onPlayerVelocity` already uses).
- `onPlayerVelocity` passes `apply.velocity()` (post-API); `peekMirror` passes
  `head.vector().toBukkit()` (no API event), so for any pre-delivered/pinned
  pending the mirror target is `preDelivered` — the reported SimpleBoxer case,
  and a pure value.
- The `deliveryAdjusted` fallback (only when `preDelivered == null`, e.g.
  projectile/arrow/rod via `submit()`) is the same call `onPlayerVelocity` makes.

Cause-agnostic: it mirrors the FIFO head exactly as `onPlayerVelocity` serves
the FIFO head, so melee/projectile/arrow/rod are all covered with no special
case and the mirror is always consistent with the apply.

### `KnockbackEventMirror` (core, reflective listener)
- Registered in `MentalPlugin.registerModules` only when
  `capabilities().knockbackEvent()`, via `registerEvent(eventClass, …,
  EventPriority.HIGH, executor, plugin, /*ignoreCancelled*/ true)`. Auto-removed
  on plugin disable.
- Per event, guards on `services.config().knockback().enabled()` first
  (zero-touch across runtime reloads). The victim comes from the floor-API
  `EntityEvent.getEntity()` (no reflection); only `setKnockback(Vector)` is a
  cached `MethodHandle`. Cancel via the floor-API `Cancellable`.
- `MirrorDecision d = pipeline.peekMirror(victim)`; `NONE` → return; `CANCEL` →
  `setCancelled(true)`; else `setKnockback(target − victim.getVelocity())`.
- `victim.getVelocity()` is region-safe: the event fires on the victim's owning
  region thread. The whole handler is wrapped best-effort (catch `Throwable`,
  log, leave vanilla's value) — a missed mirror is cosmetic for observers; the
  real client value is unaffected.

## Data flow (melee, the reported case)

1. Fast path registers, pre-sends/pins the vector → `Pending` queued.
2. `victim.damage(amount, attacker)` → `EntityDamageByEntityEvent` → KnockbackModule
   adopts/submits (pending present).
3. `LivingEntity.knockback(...)` → `EntityKnockbackEvent` (HIGH): mirror peeks the
   head pending, sets `knockback = target − current`. SimpleBoxer (MONITOR) then
   reads `current + knockback = target`. Vanilla applies `setDeltaMovement(target)`.
4. `ServerEntity.sendChanges` → `PlayerVelocityEvent` (HIGH): `onPlayerVelocity`
   polls the same head, ships `target`, fires the API event, arms the suppressor.
   Final wire value = `target` — unchanged from today.

## Invariants

- **Era-exact:** final client velocity unchanged; only the intermediate
  observable delta is corrected.
- **Zero-touch:** no listener below 1.20.6; no-op when the knockback module is
  disabled.
- **No drift:** `peekMirror` and `onPlayerVelocity` share `preDeliveredWins` and
  `deliveryAdjusted`, so the mirrored value cannot diverge from what ships.

## Testing

- `PendingStoreTest`: `peekLiveHead` is non-consuming, skips expired heads,
  returns the oldest, `null` when empty/all-expired.
- `KnockbackPipeline` (pure static): `preDeliveredWins` true for a pre-delivered/
  pinned pending whose vector matches, false when `preDelivered == null` or the
  vector differs.
- `KnockbackPipelineExpiryTest` neighbours / a small pin: a `MirrorDecision`
  kind helper resolves `NONE` (no head), `CANCEL` (null-vector head), `target`
  (normal head). Where a live `Player`/services are required (the
  `deliveryAdjusted` fallback and the reflective listener), coverage is the
  build + the 9-server matrix (byte-identical on the three <1.20.6 servers; the
  listener registers and boots clean on the four ≥1.20.6 servers incl. Folia)
  plus the live SimpleBoxer-on-Folia reproduction. The mirror emits a `KNOCKBACK`
  debug line so the live path is observable. This residual is documented (Folia
  has no combat matrix coverage by construction).

## Cross-cutting updates

- `netty-fast-path` skill: the "observers read `EntityKnockbackEvent`, Mental
  writes `PlayerVelocityEvent`" trap + the mirror.
- `docs/research/2026-06-29-folia-knockback-audit.md`: a closing note that the
  symptom was a SimpleBoxer observation artifact and the mirror is the actual fix.
- `build.gradle.kts` → 2.1.3; project memory.

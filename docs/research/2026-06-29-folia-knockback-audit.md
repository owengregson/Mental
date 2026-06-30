# Folia knockback audit — 2026-06-29

A multi-agent adversarial audit of the entire knockback subsystem (delivery,
ledger, pre-send, sprint, scheduling) prompted by a report of **downward / missing
knockback on the second combo hit on Folia**. The audit surfaced 16 panel-verified
findings; this note records what shipped, what was deliberately deferred, and why —
so a later reader does not mistake a documented limitation for an engine bug.

## Root-cause family

Nearly every Folia defect traces to the same fault line: the netty pre-send and the
authoritative damage run on *different* threads with a multi-region-tick gap between
them on Folia (netty → next region tick → entity-tracking phase), where on Paper the
whole chain closes inside one 50 ms tick. Wall-clock windows, single-slot pendings,
and "current tick" reads that are fine on Paper's single clock become wrong under
region threading.

Ground truth established by `javap` on `run/folia-1.21.11/.../folia-1.21.11.jar`
(do not re-derive):

- `CraftEntity.getHandle()` calls `ensureTickThread(...)` → **throws** off the owning
  region thread. Any `getHandle()`-routed accessor throws off-region.
- `CraftEntity.isOnGround()` does **not** route through `getHandle()` — it reads the
  raw `Entity.onGround()` field. **Safe** on the netty thread (benign stale read).
- `Bukkit.getCurrentTick()` → `RegionizedServer.getCurrentTick()` → **throws**
  off-region.

## Shipped fixes (this release)

| # | Fix | Where |
| --- | --- | --- |
| 1 | Melee withdraws its pending on cancel / full-block / OCM-yield (cause-scoped), mirroring projectile | `KnockbackModule`, `KnockbackPipeline.withdraw(victim, Cause)` |
| 4 | Cross-region Folia rod cast degrades to a logged skip instead of an uncaught throw | `FishingKnockbackModule` |
| 5 | Duplicate suppressor cancels only the payload-matching velocity, not any same-entity one | `VelocityDuplicateSuppressor` |
| 6 | `VictimMotion.recordLiftoff/recordLanding` do an atomic read-modify-write (no lost `record()`) | `VictimMotion` |
| 8 | Attack-verdict TTL is Folia-aware (300 ms vs Paper 150 ms) | `SprintTracker` |
| 9 | `clearWireSprint` stamped with the registration nanos; never overwrites a newer wire press | `SprintTracker`, `KnockbackModule` |
| 10 | `reconcileWire` won't readopt the stale-high live flag while a deferred clear is pending | `SprintTracker`, `KnockbackModule` |
| 11 | A rod hit (`victim.damage(rodder)` → ENTITY_ATTACK) no longer re-enters the melee module | `MeleeReentryGuard`, `FishingKnockbackModule`, `KnockbackModule` |
| 12 | `ensureDelivery` (sole path for modern thrown projectiles) is no longer wall-clock gated | `KnockbackPipeline` |
| 13 | A pre-send / stamp throw can no longer drop the already-claimed authoritative hit; the pre-send guard withdraws its pending so the dispatch ships a real, unsuppressed velocity | `HitPacketListener` |
| 14 | `FoliaScheduling.runOn` fires the retired callback when the entity is already retired | `FoliaScheduling` |
| — | Pre-delivered adoption scoped to the registering attacker (`sameAttacker`) so the wider Folia window cannot let a *different* attacker's hit adopt a lingering vector | `KnockbackPipeline`, `KnockbackModule` |

The original report (downward 2nd hit) was the netty→tracker latency expiring the
pre-delivered pending so vanilla's airborne (falling-`y`) velocity shipped; the Folia
window widening (300 ms) plus the hardening above close it. All Paper paths stay
byte-identical (the changes are no-ops unless an exception, a cross-region cast, a
mismatched attacker, or region-tick lag occurs); the full unit suite + the 9-server
integration matrix (7 Paper + 2 OCM) stay green.

## The four deferred items — now SHIPPED (2.1.2)

All four limitations the first round deferred were completed in 2.1.2, each rebuilt
to an adversarial design panel's validated respec (the first cut of three of the four
had a blocker the panel caught: a bare-deque race, a cross-victim ThreadLocal clobber,
and an inverted "degrades safely" claim for the tick surrogate). A fifth issue in the
same root-cause family surfaced during that panel and shipped with them.

1. **Token-keyed pending → per-victim FIFO (`PendingStore`).** The single slot became a
   bounded per-victim FIFO so two overlapping same-attacker hits each pair with their own
   velocity event in arrival order. Every mutation runs inside
   `ConcurrentHashMap.compute`/`computeIfPresent` (the inner `ArrayDeque` is only touched
   under the per-victim bin lock — one linearization point, like the old put/remove); a
   20k-pending concurrency pin proves conservation. Degenerates to the old single-slot
   take at depth ≤ 1 (the universal era path), so the measured wire values are untouched.
   `ensureDelivery(victim, cause)` promotes the newest pending of its cause so a
   projectile/rod knock is never orphaned behind a lingering melee.

2. **`HitApplier.applyPlayer` region-safety + the 5th issue.** `Scheduling.isOwnedBy
   CurrentRegion` (true on Paper, the real check on Folia) gates the authoritative
   attacker reads up front in BOTH `HitApplier.applyPlayer` AND
   `KnockbackModule.onEntityDamageByEntity`. The fifth issue: the EDBEE-handler attacker
   reads run inside the synchronous event dispatch, so Bukkit's per-listener
   `catch(Throwable)` would swallow an off-region throw — leaving vanilla to apply a raw
   knock with a half-mutated sprint/freshness ledger — which a try/catch around the
   applier could never intercept. The up-front gate skips the cross-region hit (not
   within reach anyway) before any attacker read; the applier's backstop catch is scoped
   to `IllegalStateException` so a genuine bug still surfaces on Folia.

3. **Folia boundary exclusion restored (`ServerTickClock`).** A netty-readable tick both
   the packet stamp and the snapshot read share: `Bukkit.getCurrentTick()` on Paper
   (byte-identical), a global-region-task `AtomicInteger` on Folia, **initialised to
   `NO_TICK`** so a never-started/stalled counter degrades to no-exclusion (inclusive)
   rather than — as a stuck shared `0` would — excluding universally and permanently (a
   server-wide too-sinky regression, the inverted claim the panel caught).
   `currentExcludingTick` additionally requires the boundary sample to be recent (4
   ticks), so a stale match cannot false-exclude. The exact-touchdown Folia combo now
   ships the era ~0.25 instead of the grounded ~0.3608.

4. **`AppliedTag` clock-free carrier (`AppliedTagStore`).** The 25 ms TTL is gone: the
   HIGH handler clears the victim's slot on entry and sets it only on apply, and since
   any event reaching MONITOR uncancelled also passed HIGH, MONITOR reads exactly this
   event's tag with no clock — GC-pause-immune. Kept keyed by victim (not a
   `ThreadLocal`, which the panel showed would let a nested different-victim event
   clobber it).

## Deferred — none

There are no remaining deferred items from this audit. The narrow Folia-straddle outcome
that survives (a cross-region melee is dropped rather than applied) is a Folia limitation,
not a Mental bug: a victim cannot be damaged from another region's thread, so dropping the
hit is the only region-safe outcome.

The matrix has **no live Folia combat coverage** (the tester runs only the boot suite
on Folia — gameplay suites drive cross-region state from one context, which Folia
forbids). The Folia-specific fixes rest on the `javap` ground truth, the decompile /
timing analysis, and the unit pins; confirm end-to-end on a live Folia server by
enabling the `KNOCKBACK` debug category and reproducing.

## Coda (2026-06-30): the symptom was a measurement artifact

The "downward 2nd combo hit on Folia" that drove this entire audit was **not a
Mental bug**. It was the SimpleBoxer test harness reading the wrong event.
Confirmed against `folia-1.21.11.jar` bytecode and both codebases:

- Mental applies its knockback only at `PlayerVelocityEvent` (HIGH) — the latest
  point, fired by `ServerEntity.sendChanges` just before the
  `ClientboundSetEntityMotionPacket`. There is no `EntityKnockbackEvent` listener
  in Mental. A real client on Folia therefore receives Mental's era-correct value.
- SimpleBoxer, **on Folia only** (`eventBasedKnockback = autoTicksEntities() &&
  eventAvailable()`), captures Paper's `EntityKnockbackEvent` at MONITOR and
  computes `victim.getVelocity() + getKnockback()`. On classic Paper it polls
  `hurtMarked` — the *final* deltaMovement, after Mental's override — which is why
  it always read correctly there.
- `EntityKnockbackEvent` fires *inside* `LivingEntity.knockback(...)`, **before**
  `PlayerVelocityEvent`, carrying vanilla's delta. Vanilla's airborne vertical is
  `onGround() ? min(0.4, dm.y/2 + strength) : dm.y` — for an airborne victim it
  KEEPS the current (falling) `y`, so the delta `y` is `0` and SimpleBoxer
  reconstructs the falling velocity = downward. Grounded hit 1 gets `+0.4`, so only
  the airborne 2nd hit looked wrong.

So SimpleBoxer-on-Folia could never reflect Mental's knockback, by construction —
it reads an event Mental does not write to. "Nothing changed across the ~19 fixes"
because every fix lived in the `PlayerVelocityEvent`/pending path this observer
never reads.

**The actual fix (2.1.3):** a `KnockbackEventMirror` that, on 1.20.6+, mirrors the
value the velocity event will ship onto the `EntityKnockbackEvent` delta — so any
observer of the standard knockback event (anticheats, SimpleBoxer, other plugins)
sees Mental's value, with the final wire velocity unchanged. Spec:
`docs/superpowers/specs/2026-06-30-knockback-event-mirror-design.md`. The 2.1.1 /
2.1.2 fixes were chasing a ghost but remain valid hardening (region-safety, pending
lifecycle, the boundary exclusion) and are kept.

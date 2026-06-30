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

## Deferred — known narrow limitations (NOT engine bugs)

1. **Token-keyed pending (per-hit identity).** The pending is still a single per-victim
   slot. After the attacker-scoping + withdraw discipline + payload-matched suppressor,
   the residual exposure is: two *overlapping* hits from the **same** attacker on the
   same victim within the pending window, which only happens with a **non-era lowered
   invulnerability** config, or knock-drop under **sub-~6.6 TPS** lag where even the
   300 ms window expires before the entity tracker fires. The complete fix is a small
   bounded per-victim token map consumed by the matching velocity event instead of a
   wall-clock window; deferred as a higher-risk refactor of the era-tuned delivery.

2. **`HitApplier.applyPlayer` reads the live attacker on the victim's region.** For
   melee, Folia keeps the attacker and victim in the same region (they are within a few
   blocks), so the reads are region-correct in practice. A region-boundary straddle or
   an attacker that pearls across a region in the dispatch tick could throw; deferred
   because the co-location guarantee holds for essentially all real melee.

3. **Folia `NO_TICK` disables the within-tick attack-ordering exclusion.** The packet
   feed cannot read a region tick off the netty thread (`getCurrentTick` throws), so it
   stamps `VictimMotion.NO_TICK`; `currentExcludingTick` then never excludes a same-tick
   transition. An exact-touchdown second combo hit on Folia can therefore ship the
   grounded ~0.3608 vertical where the era ships the pre-landing ~0.25 — slightly
   **floatier exact-boundary combos on Folia only**. This is an accepted trade: the
   alternative (reading the region tick off-thread) **threw and froze the whole ground
   ledger**, which was strictly worse. A netty-readable monotonic per-region tick
   surrogate could restore it; deferred as high-risk. **If you see floatier combos on
   Folia, this is the cause — not the engine.**

4. **`AppliedTag` 25 ms wall-clock TTL** between the HIGH and MONITOR velocity handlers
   could lapse across a >25 ms GC pause, missing the combos-off send-then-revert skip
   once. Rare and self-healing; a per-event carrier would remove the clock dependence.
   Deferred as low severity.

The matrix has **no live Folia combat coverage** (the tester runs only the boot suite
on Folia — gameplay suites drive cross-region state from one context, which Folia
forbids). The Folia-specific fixes rest on the `javap` ground truth, the decompile /
timing analysis, and the unit pins; confirm end-to-end on a live Folia server by
enabling the `KNOCKBACK` debug category and reproducing.

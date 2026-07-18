# Mental public API — generation 3: the ratified rulings

**Companion to** [`api-gen3-integration-surface.md`](api-gen3-integration-surface.md) (the
spec). That document surfaced the open questions; this one records how each was
answered and why, for the next maintainer who has to reason about the surface
without re-deriving the decisions. These are the §10.3 "documented rulings" the
policy contract promises, plus the one production finding Task 4 turned up.

Every ruling here is already implemented in `2.8.0-beta` (api artifact
`me.vexmc:mental-api:3.0.0`). Where the code and the ruling diverge, that
divergence is itself the ruling — read the reasoning, do not "fix" it back.

---

## D1 — feed timing: RATIFIED as recommended (confirmed-ship feed)

**Question (spec Decision box D1):** the combo feed ran from the
`PlayerVelocityEvent` **HIGH** handler, so a knock a foreign plugin cancels at
HIGHEST/MONITOR had *already* advanced the chain — the §5 events fired for a
knock the client never received.

**Ruling: move the feed to the confirmed-ship point.** The feed now fires at the
`PlayerVelocityEvent` **MONITOR** confirm (`DeskRouter.onPlayerVelocityConfirm`),
gated on `!event.isCancelled()`. "Shipped knock" in the whole surface therefore
means *confirmed shipped*: a foreign-cancelled velocity advances nothing.

Two precision points that are easy to get wrong:

- **The gate is `!isCancelled()`, NOT the valve's quantization check.** The valve
  arm (the duplicate-ENTITY_VELOCITY consumer) demands the wire encoding still
  quantize to the planned payload (`confirmsArm`); the combo feed does not. A
  foreign *modify* that leaves a different-but-non-zero velocity still ships a
  real knock to the victim, so it must still advance the chain — only a *cancel*
  suppresses the ship. The feed and the arm are two independent identity-checked
  stashes (`pendingFeed` / `pendingArm`) consumed off the same MONITOR dispatch.
- **The feed could not ride `pendingArm`.** That stash is valve-gated and carries
  no attacker/source. A separate `pendingFeed` ThreadLocal is stamped at HIGH on
  every melee ship (attacker id + tick) and drained at MONITOR.

**Deliberately left at decision-time (HIGH):** the desk **journal** and the
**motion ledger**. Those record what Mental *decided* to ship, one seam earlier;
moving them would change the journal's meaning. The valve and the combo feed are
the two *confirmed-ship* artifacts; the journal and ledger are the two
*decision-time* artifacts. That divergence is documented, not a bug.

The `ComboSuite` expectations were re-checked against the MONITOR seam (Task 4).

## D2 — blocked-knock hits: premise REFUTED by source; no second feed

**Question (spec Decision box D2):** a natively blocked melee hit that still ships
an era knock travels `KnockbackUnit.deliverBlockedKnock`, which was believed to
ship directly via BurstSender and *never* reach `ComboTracker.onKnockShipped` —
so an attacker connecting at cadence through a blocking victim would ship knocks
while the chain silently gap-outs.

**Ruling: the premise is false; blocked knocks already qualify.** Reading the
source, `deliverBlockedKnock` performs an authoritative
`victim.setVelocity(era)` (`KnockbackUnit.java:648`). That call fires a *real*
`PlayerVelocityEvent`, which re-enters `DeskRouter` on the HIGH handler and is
resolved through the desk exactly like any other knock — the `ship-formula`
resolve path. With the D1 move, the same HIGH→MONITOR seam that feeds every other
melee ship feeds the blocked re-delivery too. The re-entrancy trace in full:

```
deliverBlockedKnock
  → victim.setVelocity(era)
    → PlayerVelocityEvent
      → DeskRouter.onPlayerVelocity (HIGH): desk.resolve → ship, stash pendingFeed
        → DeskRouter.onPlayerVelocityConfirm (MONITOR): onKnockShipped, fire events
```

Adding a *direct* feed from the blocked path would therefore **double-advance**
the chain. The correct action is to add nothing — the D1 funnel already covers
it. The stale `DeskRouter:105-107` comment that claimed the blocked re-delivery
resolves through the desk was **accurate but non-obvious**; it was expanded to
name the blocked path explicitly (see `DeskRouter.onPlayerVelocity`, the combo
feed comment), not "corrected".

Live coverage: the D2-include half of §11.6 — a natively blocked cadence
sequence forms and sustains a combo — is asserted in `ComboSuite` (Task 4).

## §9 mitigation preview — deferred to 3.1

`MentalCombat.previewFinalDamage(...)` (the §9 mitigation curve as data, which
would let SE retire its hand-tuned `combat.attack-scale` 5.0) is **not shipped in
3.0.0.** `Capability.MITIGATION_PREVIEW` exists and `has(...)` reports `false`
for it; `BootSuite` pins that false so the deferral can never silently flip. The
rest of the surface does not depend on it, so 3.1 can add it additively behind
the same capability with no break.

## `combat()` nullness — keyed on combo DETECTION being live

`Mental.get().combat()` returns null when the `COMBO_QUERY` capability is absent
OR **while no combo-family module is enabled.** "Enabled" is `comboKeepers > 0`
(`SessionService.comboDetectionLive()`) — i.e. **COMBO_HOLD *or*
COMBO_REACH_HANDICAP**, not COMBO_HOLD alone. Both features spin up the per-victim
tracker; keying nullness on COMBO_HOLD only would fire chain events (under
reach-handicap-only) while `combat()` answered null, an incoherent state. The
facade's javadoc says "while no combo-family module is enabled" for exactly this
reason (`MentalPluginV5.combatQuery()` → `comboDetectionLive()`).

A held `MentalCombat` handle degrades safely across a toggle-off or plugin
disable: `MentalCombatService` re-checks `comboDetectionLive()` per call and
answers the NONE/false shapes, never an exception, never stale ACTIVE state.

## Active-chain attacker-switch END — keeps `EXPIRED` (frozen public enum)

When an *active* combo is taken over by a new attacker (or its gap lapses), the
public `ComboEndEvent` reports `Reason.EXPIRED` — the same value every release
before gen-3 reported. The public `ComboEndEvent.Reason` enum is **frozen** by
§5.5; a `SWITCHED` end would be a new public constant and a behavior change for
existing gen-2 consumers.

`SWITCHED` exists only in the *new* developing-chain abort vocabulary
(`ComboChainAbortEvent.Reason` / kernel `ComboAbortReason`): a chain that dies
*before activating* by attacker switch reports `SWITCHED`, and there the
SWITCHED-wins priority is pinned (§5.2 — SWITCHED whenever the terminating hit's
attacker differs, else EXPIRED, so the "new attacker after the gap already
lapsed" hit is stable). The kernel encodes the split directly: `onKnockShipped`
emits `ended(..., EXPIRED, ...)` for an active takeover but
`chainAborted(..., SWITCHED|EXPIRED, ...)` for a developing one.

## Disable-drain ordering — FIXED, with a firing-thread exception

Plugin disable now runs, in order:

1. `reconciler.closeAll()` — every feature closed, the combo keepers released.
2. **`sessions.drainComboTerminals()`** — a new synchronous drain that fires each
   live tracker's balanced `DISABLED` terminal (`ComboEndEvent` /
   `ComboChainAbortEvent`) so no consumer ever sees `register(null)` with an
   unbalanced sequence still open.
3. `Mental.register(null)` + services-manager unregister.
4. `sessions.shutdown()`.

The drain terminals fire on the **disabling thread** (main / the shutdown
thread), NOT the victims' region threads — the **one documented exception** to
the region-thread firing contract in §5. It is safe precisely because it runs
during disable, after every region task is already being torn down; a normal
runtime terminal still fires on the victim's owning region thread. The §11.5
plugin-disable half is enforced by this construction plus review (the tester
cannot disable Mental mid-run without killing the harness); the module-toggle
half is tested live (`ComboSuite`). See `MentalPluginV5.onDisable`.

## Tick reference frame — NAMED per platform, never the server tick

Every tick value in the surface (event `getTick/getStartedTick/getEndedTick/
getGapDeadlineTick`, `ComboView.lastKnockTick/gapDeadlineTick`,
`MentalCombat.currentTick()`) lives in **Mental's own session clock frame**, and
which concrete clock backs it depends on the platform:

- **Modern Paper:** `PaperTickClock`, reading `Bukkit.getCurrentTick()` (netty-safe
  there). On Paper the frame *happens* to equal the server tick, but consumers
  must still treat it as this surface's frame, not sniff `Bukkit.getCurrentTick()`
  themselves.
- **Folia AND the legacy backport tier** (any target lacking a thread-safe
  `getCurrentTick`): `CounterTickClock`, a global-region counter Mental advances
  once per tick and any thread may read. It starts at `NO_TICK` so a stalled
  counter degrades to no-exclusion rather than a false universal match. It is
  **monotonic and shared across all victims**, but **NOT comparable to the server
  tick or any foreign counter.**

Selection lives in one place: `MentalPluginV5` picks `CounterTickClock` when
`capabilities.folia() || !capabilities.currentTick()`, else `PaperTickClock`. The
only sanctioned comparisons are deltas against other values from this surface —
above all `MentalCombat.currentTick()`, which exists so a consumer computes "ticks
remaining until the gap deadline" and schedules in its *own* scheduler by delta,
never by absolute value.

The api↔kernel sentinel translation is pinned too: api `MentalCombat.NO_TICK ==
-1L`, kernel `TickStamp.NO_TICK == Integer.MIN_VALUE`; the core boundary
translates and never leaks the kernel value.

## API artifact versioning — publication-level 3.0.0, project version single-homed

The api surface ships as `me.vexmc:mental-api:3.0.0` while the plugin is
`2.8.0-beta`. The two versions are **independent by design**: the api evolves
additively and slower than the plugin's release cadence. This is achieved with a
new `apiVersion` gradle property read only by the `:api` maven publication
(`api/build.gradle.kts`); the project `version` stays the single source of truth
for everything else (plugin.yml, bStats, the release tag). `apiVersion()` on the
facade now returns `3` (the default in the interface stays `1` — the conservative
graceful-degradation contract for implementations compiled against an earlier
interface). The artifact is published to mavenLocal
(`./gradlew :api:publishToMavenLocal`) and attached to each GitHub release beside
the mega-jar (`release.yml`, renamed to `mental-api-<apiVersion>.jar`).

---

## Outcome-limitation: `KnockbackApplyEvent` vector overrides off the fast path

*(An owner ruling from Task 4's production finding — document, do not fix.)*

**Finding.** `KnockbackApplyEvent` exposes three outcome writers —
`setCancelled(true)` → `YIELDED`, `velocity(v)` → `SHIP` with a mutated vector,
and `suppress()` → `SUPPRESSED` (zero-velocity ship). The `YIELDED` stand-down is
honored on **every** delivery path. The **vector overrides** (`velocity()` and
`suppress()`) are currently applied **only on the pre-sent wire fast path** — the
normal real-player delivery route. On the pinned and region/live fallback
branches the mutated vector is not read at all.

**Where it lives — `DeliveryDesk.resolve` branch anatomy** (`DeliveryDesk.java`,
~:195-254). `DeskRouter` reads the api outcome at HIGH and calls
`desk.resolve(apiX, apiY, apiZ)` with the post-listener vector (or `(0,0,0)` for
SUPPRESSED); a `YIELDED` event never reaches `resolve` — the router `withdraw`s
the decision and returns before it (`DeskRouter.onPlayerVelocity:105-113`), which
is why YIELDED is honored everywhere. Inside `resolve`, the mutated `apiX/Y/Z`
are consulted by **exactly one** branch family:

- **PRE_SENT** (`tx.state() == State.PRE_SENT`, ~:209-224) — the wire fast path.
  It compares `apiX/Y/Z` against the formula; if a third party (or the api
  writer) modified them it ships `new KnockbackVector(apiX, apiY, apiZ)` — the
  api-mutated components. **This is the only branch that honors the override.**
- **PINNED** (~:225-232) — ships `tx.carried()`, the transaction's carried era
  values, ignoring `apiX/Y/Z` entirely.
- **LIVE / region** (`LIVE.contains(tx.state())`, the `ship-formula` path,
  ~:233-238) — ships the desk-computed `formula`, also ignoring `apiX/Y/Z`.

So on a pinned (clientless / pre-delivered) or region-thread delivery, a handler
that reshapes or suppresses the knock is silently overridden with the era/formula
vector; only a `setCancelled(true)` stand-down takes effect there.

**Provenance.** This is **pre-existing behavior of `velocity()`** (the PRE_SENT
comparison predates gen-3) and is **inherited by `suppress()`**, which gen-3
implements as a zero-vector SHIP through the same `resolve` argument channel.
gen-3 did not introduce it; it made it observable by adding the second writer.

**Ruling: document, do not fix.** A correct fix has to teach the PINNED and LIVE
branches to consult the api vector, which means editing the **frozen delivery
core** — the single-writer `DeliveryDesk` the whole tester gate asserts against.
That earns its own validated round rather than riding a docs-and-release task.
**Slated for 3.0.1.** Until then the contract is stated plainly in three places:
the `KnockbackApplyEvent` class javadoc ("Known limitation (3.0.0)"), the
`gradle.properties` 2.8.0-beta changelog paragraph, and here. Integrators that
must *reshape* (not merely cancel) a knock should treat the override as
best-effort off the fast path, or use `setCancelled(true)` for a guaranteed
stand-down on any path.

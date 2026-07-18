# Mental public API — generation 3: the integration surface

**Audience:** the Mental developer implementing this. **Requester:** StarEnchants (SE), the
first consumer. **Goal:** a complete, versioned public surface for combat-state integration
so that no consumer ever again mirrors Mental internals, probes methods reflectively, or
ships assumption constants derived from reading Mental's source.

Everything specified here is implementable with state Mental already tracks — each item
names the internal seam that already carries the data. Nothing asks Mental to take custody
of foreign plugins' behaviour: the division of ownership is **Mental publishes combat
truth; integrators defer/shape their own effects**. (The rejected alternative — Mental
delaying foreign damage events mid-combo — re-mints other plugins' damage outside their
pipelines, triggers their defence/proc walks twice, and silently breaks non-combat plugins'
event assumptions. Do not build that.)

This document was adversarially reviewed against the StrikeSync source before handoff; the
two items where the desired contract and today's code genuinely diverge are surfaced as
explicit **Decision boxes** (D1, D2) rather than silently specified — everything else is
mechanical publishing of existing state. Review adjudication at the end.

---

## 1. Motivation: the hacks this surface deletes

Current SE integration code and the assumption it is forced to make:

| # | SE hack / assumption today | Root cause | Killed by |
|---|---|---|---|
| H1 | `MentalKnockbackBridge` probes `KnockbackApplyEvent` accessors via `Class.forName` + `getMethod`, degrading on `NoSuchMethodException` | no consumable artifact, no capability discovery | §3 (published `mental-api` artifact) + §4 (capabilities) |
| H2 | KNOCKBACK_CONTROL:0 writes a **zero vector** because `cancel()` means "vanilla velocity stands" — intent expressed as a magic value | no explicit outcome API on the apply event | §8 (`suppress()`) |
| H3 | Combo-DoT parking mirrors combo state from Start/End events into an SE-side store guarded by a **600-tick TTL belt** (stale-state insurance against Mental dying without balanced ends) | no authoritative query; events are the only source | §6 (`comboOn` query — the mirror and the TTL are deleted) |
| H4 | SE re-implements a hurt-window boundary as `noDamageTicks <= maximumNoDamageTicks/2` for its Mental-release gating — copied from `KnockbackUnit` internals; Mental itself has THREE divergent half-window reads, so the mirror silently diverges at boundary cells | window judgment is internal and not even singular | §6 (`hurtWindowClear` — ONE pinned expression) |
| H5 | **Combo-formation gap**: parking engages only at `ComboStartEvent` (min-hits), so a DoT tick between qualifying hits 1 and 2 can still abort formation — the developing chain is invisible (`ComboTracker` hits 1..minHits−1 fire nothing) | no developing-chain signal | §5 (`ComboChainEvent` / `ComboChainAbortEvent`) |
| H6 | Combo events carry a **nullable best-effort attacker entity** only; SE branches on null and does guarded cross-region reads for liveness | no always-present attacker identity | §5 (`attackerId()` UUID on every combo event) |
| H7 | SE infers "a combo-advancing hit" from its own damage pipeline (its melee heuristics may not match Mental's qualifying-knock rules) | chain advancement is not published | §5 (`ComboHitEvent`) + §7 (ordering contract) + D2 |
| H8 | `combat.attack-scale` ships a **hand-tuned 5.0** derived from reading Mental's era armor pipeline in source ("~5% of a hit survives a full 1.8 Prot stack") | mitigation curve unqueryable | §9 (mitigation preview — optional, capability-gated) |
| H9 | SE excluded vanilla DoTs (fire/poison) from combo protection based on *reading Mental's source* to learn its era stance | policy undocumented | §10 (documented policy contract) |

---

## 2. Design principles (binding for this surface)

1. **UUID-first identity.** Every event/query carries the party's `UUID` unconditionally;
   a live entity handle is an optional best-effort convenience (`@Nullable`), never the
   identity. (Folia: entity resolution is region-bound; UUIDs are not.)
2. **Publish state, never custody.** Mental never holds, delays, or re-mints foreign
   plugins' events or damage.
3. **Balanced lifecycles.** Every opened sequence terminates in exactly one terminal
   event, including on module toggle, plugin disable, and player retire. Consumers may
   build refcount-free state on this — and with §6 they don't need state at all.
4. **Thread and clock contracts are part of the signature.** Every event documents its
   firing thread; every query documents its calling-thread rule; every tick-valued field
   documents its reference frame (§5.7). On Folia this surface is region-thread-bound per
   victim; on Paper that degenerates to the main thread.
5. **Additive evolution.** Event classes stay `final`; fields are added, never removed or
   re-typed. **Every addition to the `MentalApi` interface MUST be `default`-implemented**
   (the shipped `apiVersion()` javadoc already states why: binary compatibility for
   implementations compiled against an earlier interface). New behaviour arrives behind a
   new `Capability` constant.

---

## 3. Distribution

- The existing `api` module becomes a consumable artifact: `me.vexmc:mental-api`,
  published to the shared local repository (mavenLocal / the composite-build include both
  plugins already use), semantically versioned starting `3.0.0`.
- **Zero dependencies** beyond the Bukkit API and `org.jetbrains.annotations`
  (compile-only). No kernel/core/platform types may leak into signatures — primitives,
  `UUID`, `Optional*`, Bukkit types, and api-module-owned types only.
- Bytecode target: **Java 17 maximum**, no preview features; prefer plain
  classes/interfaces over records so a lower `--release` stays possible if ever needed.
- Consumers compile `compileOnly` against it and guard entry behind one
  `Mental.get() != null` check (the facade already self-registers on enable and nulls on
  disable — that contract is formalized in §4). Reflection dies entirely.

## 4. Discovery & capabilities

Extend the existing `Mental.MentalApi` facade (`api/src/main/java/me/vexmc/mental/api/Mental.java`):

```java
/**
 * The API generation. UNCHANGED default (1) — the conservative default is the
 * graceful-degradation contract; the gen-3 implementation overrides to return 3.
 */
default int apiVersion() { return 1; }

/** Fine-grained feature probe — additive; unknown constants on older impls return false. */
default boolean has(@NotNull Capability capability) { return false; }

enum Capability {
    COMBO_EVENTS,          // ComboStart/ComboEnd (gen 2 behaviour, formalized)
    COMBO_CHAIN_EVENTS,    // ComboChainEvent + ComboChainAbortEvent (§5)
    COMBO_HIT_EVENTS,      // ComboHitEvent (§5)
    COMBO_QUERY,           // combat().comboOn(...) (§6)
    WINDOW_QUERY,          // combat().hurtWindowClear(...) (§6)
    KNOCKBACK_OUTCOMES,    // KnockbackApplyEvent.suppress()/outcome() (§8)
    MITIGATION_PREVIEW     // combat().previewFinalDamage(...) (§9, may ship later)
}

/**
 * The combat-state query service (§6). DEFAULT null — binary-compatible with older
 * implementations (Principle 5). Null when the capability is absent OR the combo-hold
 * module is disabled. Consumers should re-fetch per use rather than caching (see below).
 */
default @Nullable MentalCombat combat() { return null; }
```

Contract: `Mental.register(api)` on enable **before** any gen-3 event can fire;
`Mental.register(null)` on disable **after** the last balanced terminal event has fired.
A `MentalCombat` handle a consumer holds across **either** Mental's plugin disable **or a
runtime combo-hold module toggle-off** degrades identically: every query answers the
NONE/false shapes — never an exception, never stale ACTIVE state. (Recommended consumer
pattern regardless: call `combat()` fresh per decision and null-check; it is one volatile
read.)

## 5. Combo lifecycle events — the complete state machine

Per victim, the published machine is `NONE → DEVELOPING → ACTIVE → NONE`, with
`DEVELOPING → NONE` (abort) and restart edges. All events: fired on the **victim's owning
region thread**, not cancellable, `final`, in `me.vexmc.mental.api.event`.

Internal seams (for the implementer): all transitions below are computed inside
`kernel/combo/ComboTracker` — `onKnockShipped` already distinguishes chain-open
(`attackerId` null→set), pre-activation advance (`hits++` while `!active`), promotion
(`hits >= minHits`), continuation (`hits++` while `active`), and silent developing-chain
death (the `resetChain()` calls where `!active`); `onTick`/`onOwnHitLanded`/`reset` own
the time/retaliation/administrative ends. Today only started/ended surface as
`ComboTransition`s; add `CHAIN_OPENED`/`CHAIN_ADVANCED`/`CHAIN_ABORTED`/`HIT` transition
kinds and let `ComboEvents` fire them exactly as it fires start/end today. The gap
deadline is `lastHitTick + rules.maxGapTicks()` — both already fields.

> **Decision box D1 — feed timing (behavioral, owner ratifies).** Today the combo feed
> (`feedComboOnShip`) runs from the `PlayerVelocityEvent` **HIGH** handler
> (`DeskRouter.java:68→108`), NOT the MONITOR confirm — so a knock that a foreign plugin
> cancels at HIGHEST/MONITOR has *already advanced the chain*, and the desk's own
> dead-arm lesson (the valve arms only at the MONITOR confirm, `DeskRouter.java:121-131`,
> because "a HIGHEST/MONITOR foreign listener may still cancel") applies equally to combo
> feeds. **Recommendation: move the feed to the confirmed-ship point** (ride the existing
> `pendingArm` confirm), so "shipped knock" in this spec means *confirmed shipped* and
> the §5 events never fire for a knock the client never received. This is a behavior
> change only in the presence of foreign velocity-cancelling plugins; existing
> `ComboSuite` expectations must be re-checked. If the owner instead keeps the HIGH feed,
> the api javadoc must say so plainly ("a later-cancelled velocity still advanced the
> chain") — do not leave it undocumented either way.

> **Decision box D2 — blocked-knock hits (behavioral, owner ratifies).** A natively
> blocked melee hit that still ships an era knock travels a different delivery path —
> `KnockbackUnit.deliverBlockedKnock` (`KnockbackUnit.java:264/:355`, shipping directly
> via BurstSender because vanilla skips `markHurt`, so no `PlayerVelocityEvent` fires,
> `KnockbackUnit.java:225`) — and **never reaches `ComboTracker.onKnockShipped`** (whose
> single repo-wide caller is `DeskRouter.java:150`). Consequence today: an attacker
> connecting at cadence through a blocking victim ships knocks but the chain gap-outs —
> arguably a live combo-detection bug, and exactly the hidden qualifying-rule mismatch H7
> exists to kill. **Recommendation: feed `onKnockShipped` from the blocked-delivery path
> too**, so "every qualifying melee knock" below is literally true. If the owner instead
> ratifies the exclusion, §5's promise must be weakened in the api javadoc to "every
> velocity-path melee knock; natively blocked hits do not advance chains" — again,
> documented either way. (The stale `DeskRouter.java:105-107` comment claiming the
> blocked re-delivery resolves through the desk should be corrected with whichever
> ruling.)

### 5.1 `ComboChainEvent` (new — closes H5)

Fired on **every qualifying melee knock that advances a not-yet-active chain** (hits
`1..minHits−1`), i.e. the developing window Start cannot see. ("Qualifying knock" is
D1/D2-resolved: whatever the ruling, the api javadoc states it exactly.)

```java
@NotNull Player getVictim();
@NotNull UUID getAttackerId();          // never null (H6)
@Nullable LivingEntity getAttacker();   // best-effort convenience
int getHits();                          // 1 = chain opened
long getGapDeadlineTick();              // Mental-clock tick (§5.7) by which the next
                                        // qualifying knock must ship or this chain dies
```

`getGapDeadlineTick()` lets a consumer park/defer **exactly** for the developing window
with zero heuristics and self-expire (via §6 `currentTick()`) without needing the abort
event on a quiet server.

### 5.2 `ComboChainAbortEvent` (new)

Fired when a DEVELOPING chain dies **without activating**: gap expiry, attacker switch
(the old developing chain is abandoned), victim retaliation, retire, module disable.

```java
@NotNull Player getVictim();
@NotNull UUID getAttackerId();
int getHits();                          // final developing length
@NotNull Reason getReason();            // EXPIRED | SWITCHED | RETALIATION | RETIRED | DISABLED
```

Reason priority when discriminators coincide (both are computed independently at
`ComboTracker.java:117-118` and today collapse into one): **SWITCHED wins whenever the
terminating hit's attacker differs from the chain's**, else EXPIRED — pinned so consumers
get a stable value for the "new attacker arrives after the old gap already lapsed" hit.

### 5.3 `ComboStartEvent` (existing — additive changes)

Keep shape and semantics. Add: `@NotNull UUID getAttackerId()` (never null),
`long getStartedTick()` (§5.7 frame).

### 5.4 `ComboHitEvent` (new — closes H7)

Fired on every chain-advancing shipped knock **while the combo is ACTIVE** (hit
`minHits+1` onward). The promotion hit itself (hit == minHits) is announced by
`ComboStartEvent` alone — the two never fire for the same knock.

```java
@NotNull Player getVictim();
@NotNull UUID getAttackerId();
int getHits();
long getTick();                         // §5.7 frame
long getGapDeadlineTick();
```

### 5.5 `ComboEndEvent` (existing — additive changes)

Keep shape and the `Reason` enum exactly. Add: `@NotNull UUID getAttackerId()` (never
null), `int getHits()` (final chain length), `long getEndedTick()` (§5.7 frame).

### 5.6 Balance invariants (documented + tested, §11)

- Every `ComboChainEvent(hits == 1)` sequence terminates in **exactly one** of
  `ComboStartEvent` (promotion) or `ComboChainAbortEvent`.
- Every `ComboStartEvent` is followed by **exactly one** `ComboEndEvent` (all reasons,
  including DISABLED on module toggle and plugin disable — the gen-2 guarantee,
  formalized).
- An attacker-switch restart fires the terminal for the old sequence **before** the
  opening event of the new one, in that order, same thread, same tick (the tracker
  already returns END-then-START ordered transition lists).
- No combo state survives a server restart (no persisted sequences to re-balance).

### 5.7 The tick reference frame (applies to EVERY tick-valued field in this surface)

All tick values — event `getTick()`/`getStartedTick()`/`getEndedTick()`/
`getGapDeadlineTick()`, and `ComboView.lastKnockTick()`/`gapDeadlineTick()` — are in
**Mental's session clock frame**: the same monotonic counter the tracker itself is fed
(`TickStamp` / `clock.current()`). Consumers MUST NOT compare them against their own
plugin's counters or against `Bukkit.getCurrentTick()`; the only sanctioned comparisons
are (a) against other tick values from this surface and (b) against
`MentalCombat.currentTick()` (§6), which exists precisely so a consumer can compute "ticks
remaining until deadline" and schedule in its own scheduler by *delta*, never by absolute
value. The frame is monotonic and shared across all victims on a server; on Folia it is
whatever single counter Mental already feeds every session (the api javadoc must name it).

## 6. `MentalCombat` — the authoritative query service (closes H3, H4)

```java
public interface MentalCombat {

    long NO_TICK = -1;

    /**
     * Snapshot of the combo machine for {@code victim}. Never null and never throws —
     * state NONE (attackerId null, ticks NO_TICK) for a victim with no chain, an
     * offline victim, or a UUID Mental has never seen. Callable from ANY thread: the
     * implementation publishes an immutable view at each transition (write on the
     * victim's session thread, read anywhere — a concurrent-map publish, not a live
     * tracker read). An on-thread call (the victim's owning region thread) observes the
     * exact current state; an off-thread call may lag by at most the in-flight
     * transition, and is never torn.
     */
    @NotNull ComboView comboOn(@NotNull UUID victim);

    /**
     * The pinned hurt-window admit test for {@code victim}:
     *     victim.getNoDamageTicks() <= victim.getMaximumNoDamageTicks() / 2
     * (integer division — the vanilla admit gate). PINNED AS THE CONTRACT: this
     * deliberately does NOT track PlayerView.damageImmune()'s "+1 staleness" read
     * (PlayerView.java:194-195) or any other internal variant Mental's fast paths use —
     * Mental's internals have three divergent half-window reads, and the entire point
     * of this method is that integrators get ONE stable, tested expression for "would a
     * fresh hurt ship cleanly rather than be window-swallowed". If Mental ever retunes
     * its own boundary family, this method's expression only changes with a Capability
     * bump. Victim's owning region thread only (it reads the live entity).
     * Consumers must never re-derive this FOR THE MENTAL-INTEGRATION DECISION;
     * a consumer's own Mental-agnostic vanilla-window reads are unaffected.
     */
    boolean hurtWindowClear(@NotNull Player victim);

    /** The current tick in this surface's clock frame (§5.7). Any thread. */
    long currentTick();
}

public interface ComboView {
    @NotNull State state();             // NONE | DEVELOPING | ACTIVE
    @Nullable UUID attackerId();        // null iff NONE
    int hits();
    long lastKnockTick();               // MentalCombat.NO_TICK iff NONE
    long gapDeadlineTick();             // MentalCombat.NO_TICK iff NONE
    enum State { NONE, DEVELOPING, ACTIVE }
}
```

Implementation seam: build a fresh published-view mechanism — on every tracker
transition, the session thread writes an immutable `ComboView` into a concurrent map keyed
by victim UUID (removed on retire). Note: `ComboTracker.snapshot()`/`ComboSnapshot` look
like this but are **dead code outside the kernel test** (the servo gate actually reads
`activeAttacker()` — `CombatSession.java:177`, `SessionService.java:742`); widening or
replacing `ComboSnapshot` breaks nothing in production, but do not go hunting for an
existing view-publish consumer — there is none. The session thread IS the victim's region
thread (D2 ownership doc), so the write side needs no locking.

**Why both events and queries:** queries make consumer-side mirrors (and their staleness
belts) unnecessary for *decisions*; events remain the *prompt triggers* (a consumer
releasing banked work on ComboEnd shouldn't poll). Consumers are expected to use events
to schedule and queries to decide.

## 7. Ordering contract (the subtle one — must be documented verbatim in the api javadoc)

Combo advancement is fed at knock-delivery time on the `PlayerVelocityEvent` path —
today from the desk's **HIGH** handler (`DeskRouter.java:108`), at the MONITOR confirm if
D1's recommendation is taken — and in every case *after* the victim's
`EntityDamageEvent` for that hit has completed its entire handler chain (the velocity
event is a separate, later event; a cancelled damage event ships no knock and feeds
nothing). Therefore:

> **A query made from inside any `EntityDamageEvent` handler observes the pre-hit combo
> state.** The hit being processed has not yet advanced/opened/ended any chain. All §5
> events for that hit fire later in the same tick, on the same thread.

Fold-eligibility statement (load-bearing for consumers joining banked damage into a hit's
damage moment): at fold time, `comboOn(victim)` answering **ACTIVE *or* DEVELOPING with
`attackerId()` equal to this hit's attacker** means "this hit continues (or is about to
promote) that chain" and banked damage belonging to that attacker is fold-eligible into
this hit. The DEVELOPING arm matters precisely on the promotion hit (pre-hit state of hit
`minHits` is DEVELOPING — `active` flips only after the feed, `ComboTracker.java:141-145`);
a consumer folding only on ACTIVE would starve its developing-window bank into the
end-release path and re-create the extra window/knockback it exists to avoid. Only NONE,
or a different attacker, is not fold-eligible.

## 8. Knockback event upgrades (closes H2)

`KnockbackApplyEvent` — additive:

```java
@Nullable UUID getAttackerId();         // the knock's source party, when one exists
@NotNull Source getSource();            // MELEE | ROD | PROJECTILE | OTHER
void suppress();                        // ship ZERO velocity (explicit intent)
@NotNull Outcome getOutcome();          // SHIP (default) | SUPPRESSED | YIELDED
```

Resolution semantics — **one last-writer-wins ordering across ALL THREE writers**
(`velocity(Vector)`, `suppress()`, `setCancelled(boolean)`):

- `velocity(v)` → outcome SHIP with vector v (and **clears a prior cancel** — the writer
  is expressing "ship exactly this").
- `suppress()` → outcome SUPPRESSED (zero velocity ships; clears a prior cancel).
- `setCancelled(true)` → outcome YIELDED — Mental stands down and vanilla's own velocity
  applies (it does NOT mean "no knockback"); `setCancelled(false)` restores SHIP with the
  last written (or desk-computed) vector.
- `getOutcome()` reflects the **current accumulated state at read time**; it is final
  only when read at MONITOR. Document all of this in the event javadoc.

## 9. Mitigation preview (optional — closes H8; may ship after the rest)

```java
/**
 * The final damage Mental's era pipeline would deliver to {@code victim} for a
 * {@code base}-damage hit of {@code cause} with their CURRENT armor/state — the
 * mitigation curve as data. Victim's owning region thread. Pure: no events, no
 * side effects, no cooldown interaction.
 */
double previewFinalDamage(@NotNull Player victim, double base, @NotNull DamageCause cause);
```

Motivation: SE's `combat.attack-scale` (pack ships 5.0) is a hand-derived constant
compensating for Mental's era armor crush; with a preview, SE can calibrate empirically
at runtime (its `/se damagedebug` machinery already measures per-hit folds). Gate behind
`MITIGATION_PREVIEW`; fine to ship in 3.1.

## 10. Documented policy contracts (docs, not code — closes H9)

Add to this docs directory (or the api javadoc package-info):

1. **Era-authenticity stance:** vanilla periodic damage (fire tick, poison, wither,
   drowning…) interacts with combos exactly as 1.8 vanilla — it CAN break them; Mental
   will not defer or suppress it, ever. Custom plugin damage is the plugin's own
   responsibility using §5/§6/§7 (the ownership split in the preamble).
2. **Foreign-window behaviour:** what happens when foreign damage re-arms the victim's
   hurt window mid-combo (the window-swallow + knock-interference failure chain) — so
   integrators understand precisely WHY they defer.
3. **The D1/D2 rulings**, whichever way they land.

## 11. Acceptance criteria (Mental-side tests to ship with this)

1. Kernel tests (`ComboTrackerTest` siblings): the new transition kinds fire at the exact
   state edges of §5 — including the END-then-START restart order, the developing-chain
   abort sites, and the SWITCHED-over-EXPIRED reason priority (§5.2); balance invariants
   of §5.6 hold over randomized hit/tick/retaliation sequences (property-style).
2. Live tester (Mental's own tester module): a staged combo produces
   `Chain(1) → … → Start(minHits) → Hit(minHits+1…) → End` on the victim's thread with
   monotonically consistent hits/ticks and **no Hit fired for the promotion knock**;
   `comboOn` observed from inside a damage-event handler shows the pre-hit state (§7) —
   assert on hit N that the query answers N−1, and assert the promotion hit's pre-hit
   state is DEVELOPING.
3. `hurtWindowClear` equals the §6 pinned expression exactly, parameterized over
   `maximumNoDamageTicks` values including the boundary cell where the "+1 staleness"
   variant diverges (max=20, noDamageTicks=11 → pinned says NOT clear).
4. Tick frame: event ticks, `gapDeadlineTick`, and `currentTick()` are mutually
   consistent (deadline − lastKnock == configured max-gap; an event's tick ≤
   `currentTick()` read in its handler).
5. Disable/toggle: module off and plugin disable both fire the balanced terminals before
   `Mental.register(null)`; a held `MentalCombat` reference answers NONE/false after
   EITHER (§4).
6. D1/D2 (as ruled): if D2-include, a natively-blocked cadence sequence forms and
   sustains a combo; if D1-confirmed, a HIGHEST-cancelled velocity advances nothing.
7. Folia lane: all of the above on a real Folia server (events on the victim's region
   thread — assert `Bukkit.isOwnedByCurrentRegion(victim)` in the test listeners); plus
   an off-thread `comboOn` read (global thread) returning an untorn snapshot.

## 12. Reference consumer: the StarEnchants adoption map (context for design review)

What SE deletes/changes the day gen 3 ships (SE-side follow-up, not this task):

| SE component (shipping now, gen-2) | Under gen 3 |
|---|---|
| `MentalComboBridge` (reflective probe of Start/End) | `compileOnly mental-api`; one `Mental.get()` guard class; typed listeners. Dual-path kept one release for gen-2 servers, then reflection deleted |
| SE-side combo mirror store + 600-tick TTL belt | **deleted** — park/flush decisions call `combat().comboOn(victim)` (§6); events only trigger prompt release |
| Formation gap (continuation-only protection, documented limitation) | **closed** — parking engages on `ComboChainEvent` / `state()==DEVELOPING`, self-expiring by `gapDeadlineTick` vs `currentTick()` deltas (§5.7), released on `ComboChainAbortEvent`; fold-eligibility per §7 includes the DEVELOPING promotion hit |
| `noDamageTicks <= max/2` window mirror in the paced release | `combat().hurtWindowClear(victim)` (same pinned expression — SE's mirror was already the vanilla-gate variant; SE's Mental-agnostic `ReHitGuard` window reads are out of scope and stay) |
| Null-attacker degrade paths on combo events | `getAttackerId()` always present; entity handle used only as an optimization |
| Zero-vector knockback cancel | `suppress()` |
| `combat.attack-scale` hand-tuned 5.0 | unchanged now; optional auto-calibration via §9 later |

SE's park/flush **engine kernel** (the per-(victim,attacker) ledger, the fold join, the
paced release, terminal semantics) is API-agnostic and survives unchanged — the gen-3
migration touches only the state-source layer. This is why SE ships the gen-2 integration
now rather than waiting.

---

## Review adjudication (adversarial source-verified review, 2026-07-17)

Fourteen findings, all ACCEPTED and folded:

- **Feed seam mis-stated (HIGH, not MONITOR)** → §7 corrected; the behavioral choice
  surfaced as Decision box D1 (recommendation: confirmed-ship feed).
- **Blocked-knock delivery bypasses the combo feed** → surfaced as Decision box D2
  (recommendation: feed it — it is arguably a live combo-detection bug).
- **hurtWindowClear boundary ambiguous (three divergent internal reads)** → §6 pins ONE
  expression (`<= max/2`, integer division), explicitly not the "+1 staleness" variant;
  §11.3 tests the exact divergent cell.
- **`combat()` non-default = binary break; `apiVersion()` default bump** → both fixed in
  §4; Principle 5 now mandates default-implemented additions.
- **`snapshot()` is production-dead** → §6 seam note corrected (build a fresh published
  view; nothing consumes ComboSnapshot).
- **Abort Reason on switch+expiry coincidence** → §5.2 pins SWITCHED-wins.
- **Tick frame undefined** → new §5.7 + `MentalCombat.currentTick()`; §11.4 pins it.
- **`comboOn` offline/unknown-UUID thread contract unsatisfiable** → §6 re-specified as
  publish-on-transition, any-thread reads, NONE for unknown/offline, never torn.
- **Outcome/cancel interplay unspecified** → §8 one last-writer-wins ordering across all
  three writers + read-time semantics.
- **§7 fold rule omitted the DEVELOPING promotion hit** → fold-eligibility extended to
  ACTIVE-or-DEVELOPING with matching attacker; §11.2 asserts the promotion pre-hit state.
- **Module-toggle handle lifecycle** → §4 defunct-handle guarantee extended to module
  toggle; re-fetch-per-use recommended.
- **"Never re-derive" over-claim** → §6 scoped to the Mental-integration decision.
- **NO_TICK named but undeclared / inconsistent sentinels** → declared on `MentalCombat`,
  referenced uniformly.

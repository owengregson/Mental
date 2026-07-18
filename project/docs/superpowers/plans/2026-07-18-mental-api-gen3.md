# Mental Public API Generation 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. In this run the orchestrator dispatches one Opus agent per task; **agents do NOT run `git commit` — the orchestrator commits at task boundaries** with the messages given here.

**Goal:** Implement the gen-3 integration surface specified in `docs/api-gen3-integration-surface.md` — combo lifecycle events, the `MentalCombat` query service, capabilities, knockback outcomes, publishing — and ship it as Mental `2.8.0-beta` with api artifact `me.vexmc:mental-api:3.0.0`.

**Architecture:** The kernel `ComboTracker` widens its transition vocabulary to surface every currently-silent developing-chain edge; core maps transitions to new api events and publishes an immutable per-victim `ComboView` into a concurrent map (write on the session thread, read anywhere); the combo feed moves from the velocity HIGH handler to the MONITOR confirmed-ship point (resolving decision boxes D1 **and** D2 with one edit); the facade grows `apiVersion()==3`, `has(Capability)`, and `combat()`.

**Tech Stack:** Java (`--release 17`), Gradle Kotlin DSL, Bukkit/Paper events, JUnit (existing kernel/core test infra), the tester in-server harness.

## Ratified decisions (owner-proxy rulings — the spec's open questions, answered)

These are binding for every task below. The full reasoning ships in `docs/api-gen3-rulings.md` (Task 5).

- **D1 — RATIFIED as recommended, amended:** the combo feed moves to the `PlayerVelocityEvent` MONITOR confirm, gated on `!event.isCancelled()` (NOT the stricter `confirmsArm` quantization — a foreign *modify* still ships a knock). It cannot ride `pendingArm` (valve-gated, lacks attacker/source) — a new `pendingFeed` ThreadLocal is stashed at HIGH on every melee ship. The desk **journal and ledger stay at HIGH** (decision-time) — that divergence is documented, not changed.
- **D2 — premise REFUTED by source; ruling: no second feed.** `KnockbackUnit.deliverBlockedKnock`'s authoritative `victim.setVelocity(era)` (KnockbackUnit.java:648) fires a real `PlayerVelocityEvent` that re-enters `DeskRouter` and already feeds the tracker (`ship-formula` resolve). Blocked knocks therefore already advance chains; a direct feed would double-advance. The D1 move covers the blocked path automatically (same HIGH→MONITOR seam). The DeskRouter:105-107 comment is **accurate but non-obvious** — it gets expanded, not "corrected".
- **§9 mitigation preview: deferred to 3.1.** `Capability.MITIGATION_PREVIEW` ships and reports `false`.
- **`combat()` nullness keys on combo DETECTION being live** (`SessionService.comboKeepers > 0`, i.e. COMBO_HOLD **or** COMBO_REACH_HANDICAP enabled), not on COMBO_HOLD alone — otherwise events fire while `combat()` is null under reach-handicap-only. Javadoc says "while no combo-family module is enabled".
- **Active-chain attacker-switch END keeps `ComboEndEvent.Reason.EXPIRED`** (§5.5 freezes that enum). `SWITCHED` exists only in the new abort vocabulary (developing chains).
- **Versioning:** plugin `2.8.0-beta` (public 2.x line per the owner's standing directive); api artifact `me.vexmc:mental-api:3.0.0` via a *publication-level* version from a new `apiVersion` gradle property — the project version stays single-homed.
- **Enable ordering stays as-is** (`converge` before `register(facade)`) with an explanatory comment: combo events only fire from later ticks/velocity events, never during `onEnable`.
- **Disable ordering is FIXED**: `reconciler.closeAll()` → a new synchronous combo-terminal drain → `Mental.register(null)` → `sessions.shutdown()`. Disable-time terminals fire on the disabling thread — the documented exception to the region-thread contract.
- **YIELDED knocks do not advance chains** (unchanged from today's cancel path); **SUPPRESSED knocks DO** (a zero-velocity melee ship still shipped — unchanged from today's zero-vector behavior).
- **§11.5's plugin-disable half is enforced by construction + review**, not a live test (the tester cannot disable Mental mid-run without killing the harness); the module-toggle half is tested live.

## Global Constraints

- Api module: **zero dependencies** beyond Bukkit API + `org.jetbrains.annotations` (compile-only); bytecode `--release 17`; **no kernel/core/platform types in any api signature** — primitives, `UUID`, `Optional*`, Bukkit types, api-owned types only (this is also the D-8 descriptor rule: no post-Java-8 JDK type in any cross-plugin API descriptor).
- Event classes stay `final`; **nothing is removed or re-typed** — japicmp (`:api:apiCompat`, baseline `api-2.2.2.jar`) must stay green; every `MentalApi` interface addition is `default`-implemented.
- Kernel stays **pure JDK / Bukkit-free** (build-asserted). Kernel emits only immutable values (UUID/int/enum/`TickStamp`); core constructs Bukkit events.
- **Zero-touch**: with both combo features off, no tracker exists, no gen-3 combo event ever fires, `combat()` is null. **No new config knobs** (verified: nothing in the spec needs one).
- Api tick sentinel `MentalCombat.NO_TICK == -1L`; kernel `TickStamp.NO_TICK == Integer.MIN_VALUE`. **Translate at the core boundary, never leak the kernel value** (helper `tickValue(TickStamp)` in Task 3).
- Every event javadoc documents firing thread; every query documents its calling-thread rule; §7's ordering contract text appears verbatim in the api javadoc.
- Comments explain the why; imports never inline-qualified; conventional commits with prose bodies.
- Mutate-then-fire pattern is MANDATORY at every fire site: capture the transition(s) in a local, THEN call `tracker.view()`, THEN `comboEvents.fire(...)`. Java argument evaluation is left-to-right — `fire(p, tracker.view(), tracker.onTick(...))` reads the view BEFORE the mutation and publishes a stale state.

## File Structure

| Task | Creates | Modifies |
|---|---|---|
| 1 kernel | `kernel/.../combo/ComboAbortReason.java`, `kernel/.../combo/ComboViewState.java` | `ComboTransition.java`, `ComboTracker.java`, `ComboTrackerTest.java` |
| 2 api | `api/.../MentalCombat.java`, `api/.../ComboView.java`, `api/.../event/ComboChainEvent.java`, `api/.../event/ComboChainAbortEvent.java`, `api/.../event/ComboHitEvent.java`, `api/.../package-info.java` | `Mental.java`, `event/ComboStartEvent.java`, `event/ComboEndEvent.java`, `event/KnockbackApplyEvent.java`, `api/build.gradle.kts`, `gradle.properties` (apiVersion property only) |
| 3 core | `core/.../v5/feature/combo/ComboViewBook.java`, `core/.../v5/api/MentalCombatService.java`, `core/.../v5/api/WindowJudge.java`, `core/src/test/.../WindowJudgeTest.java`, `core/src/test/.../KnockbackApplyOutcomeTest.java` | `ComboEvents.java`, `DeskRouter.java`, `SessionService.java`, `CombatSession.java` (only if an accessor is missing), `KnockbackUnit.java` (call-site), `MentalFacade.java`, `MentalPluginV5.java` |
| 4 tester | — | `BootSuite.java`, `ComboSuite.java`, `FoliaCombatSmoke.java`, `MentalTesterPlugin.java` (javadoc list only if suites list text changes — no new suite class) |
| 5 docs/release | `docs/api-gen3-rulings.md` | `gradle.properties` (version bump + changelog paragraph), `.github/workflows/release.yml` (attach api jar) |

---

### Task 1: Kernel — surface the silent combo edges

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/combo/ComboAbortReason.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/combo/ComboViewState.java`
- Modify: `kernel/src/main/java/me/vexmc/mental/kernel/combo/ComboTransition.java`
- Modify: `kernel/src/main/java/me/vexmc/mental/kernel/combo/ComboTracker.java`
- Test: `kernel/src/test/java/me/vexmc/mental/kernel/combo/ComboTrackerTest.java`
- Leave alone: `ComboSnapshot.java` + `snapshot()` (production-dead but kernel is additive-only; they stay).

**Interfaces (Produces — Task 3 relies on these exact signatures):**
- `enum ComboAbortReason { EXPIRED, SWITCHED, RETALIATION, RETIRED, DISABLED }`
- `record ComboViewState(UUID attackerId, int hits, boolean active, TickStamp lastKnockTick, TickStamp gapDeadline)` with `NONE` constant, `boolean none()`, `boolean developing()`
- `ComboTransition` kinds `{ NONE, CHAIN_OPENED, CHAIN_ADVANCED, CHAIN_ABORTED, STARTED, HIT, ENDED }`; components `(Kind kind, UUID attacker, int hits, ComboEndReason reason, ComboAbortReason abortReason, TickStamp tick, TickStamp gapDeadline)`
- `ComboTracker`: `List<ComboTransition> onKnockShipped(UUID, TickStamp)` (unchanged signature, richer returns), `ComboTransition onOwnHitLanded(TickStamp)`, `ComboTransition onTick(TickStamp, boolean, double)`, **`ComboTransition reset(ComboEndReason reason, TickStamp tick)`** (tick param added — old 1-arg `reset` REMOVED, kernel-internal so no api break), **`ComboViewState view()`** (new).

- [ ] **Step 1: `ComboAbortReason`** — new file:

```java
package me.vexmc.mental.kernel.combo;

/**
 * Why a DEVELOPING (pre-activation) chain died without ever activating.
 * Distinct from {@link ComboEndReason}: an active combo's end vocabulary is
 * frozen by the public api (gen-3 §5.5), while developing chains can die by
 * attacker switch — a cause an active end never reports. GROUNDED/BLOWOUT are
 * deliberately absent: both tracker guards are active-only, so a developing
 * chain can never reach them.
 */
public enum ComboAbortReason {
    EXPIRED,
    SWITCHED,
    RETALIATION,
    RETIRED,
    DISABLED
}
```

- [ ] **Step 2: `ComboViewState`** — new file:

```java
package me.vexmc.mental.kernel.combo;

import java.util.UUID;
import me.vexmc.mental.kernel.model.TickStamp;

/**
 * The tracker's full published-view value — unlike {@link ComboSnapshot} it
 * surfaces the DEVELOPING attacker and the gap clock, which the gen-3 query
 * surface needs. Read on the session thread right after a mutation and
 * published as an immutable value; never a live tracker reference.
 */
public record ComboViewState(UUID attackerId, int hits, boolean active,
                             TickStamp lastKnockTick, TickStamp gapDeadline) {

    public static final ComboViewState NONE =
            new ComboViewState(null, 0, false, TickStamp.NO_TICK, TickStamp.NO_TICK);

    /** No chain at all (the map-removal shape). */
    public boolean none() {
        return attackerId == null;
    }

    /** A chain below min-hits: attacker set, not yet active. */
    public boolean developing() {
        return attackerId != null && !active;
    }
}
```

- [ ] **Step 3: rewrite `ComboTransition`** (replace the record header, `Kind`, and ALL factories; fix the stale "at most one transition" javadoc — `onKnockShipped` has returned `[END, START]` pairs since the list return landed):

```java
public record ComboTransition(Kind kind, UUID attacker, int hits, ComboEndReason reason,
                              ComboAbortReason abortReason, TickStamp tick, TickStamp gapDeadline) {

    public enum Kind { NONE, CHAIN_OPENED, CHAIN_ADVANCED, CHAIN_ABORTED, STARTED, HIT, ENDED }

    public static final ComboTransition NONE =
            new ComboTransition(Kind.NONE, null, 0, null, null, TickStamp.NO_TICK, TickStamp.NO_TICK);

    public static ComboTransition chainOpened(UUID attacker, TickStamp tick, TickStamp gapDeadline) {
        return new ComboTransition(Kind.CHAIN_OPENED, attacker, 1, null, null, tick, gapDeadline);
    }

    public static ComboTransition chainAdvanced(UUID attacker, int hits, TickStamp tick, TickStamp gapDeadline) {
        return new ComboTransition(Kind.CHAIN_ADVANCED, attacker, hits, null, null, tick, gapDeadline);
    }

    public static ComboTransition chainAborted(UUID attacker, int hits, ComboAbortReason abortReason, TickStamp tick) {
        return new ComboTransition(Kind.CHAIN_ABORTED, attacker, hits, null, abortReason, tick, TickStamp.NO_TICK);
    }

    public static ComboTransition started(UUID attacker, int hits, TickStamp tick, TickStamp gapDeadline) {
        return new ComboTransition(Kind.STARTED, attacker, hits, null, null, tick, gapDeadline);
    }

    public static ComboTransition hit(UUID attacker, int hits, TickStamp tick, TickStamp gapDeadline) {
        return new ComboTransition(Kind.HIT, attacker, hits, null, null, tick, gapDeadline);
    }

    public static ComboTransition ended(UUID attacker, int hits, ComboEndReason reason, TickStamp tick) {
        return new ComboTransition(Kind.ENDED, attacker, hits, reason, null, tick, TickStamp.NO_TICK);
    }

    public boolean started() { return kind == Kind.STARTED; }

    public boolean ended() { return kind == Kind.ENDED; }
}
```

Keep the existing imports (`java.util.UUID`) and add `me.vexmc.mental.kernel.model.TickStamp`. Javadoc for the class: transitions are ordered — a terminal (ENDED/CHAIN_ABORTED) for the old sequence always precedes the opening transition of its successor in a returned list (§5.6).

- [ ] **Step 4: `ComboTracker` edits.** Add a private deadline helper + public `view()`:

```java
    /** The tick by which the next qualifying knock must ship, or NO_TICK with no chain clock. */
    private TickStamp gapDeadline() {
        return lastHitTick.known()
                ? new TickStamp(lastHitTick.value() + rules.maxGapTicks())
                : TickStamp.NO_TICK;
    }

    /** The full immutable view of this tracker — the gen-3 publish value (unlike
     * {@link #snapshot()}, the DEVELOPING attacker and the gap clock are visible). */
    public ComboViewState view() {
        if (attackerId == null) {
            return ComboViewState.NONE;
        }
        return new ComboViewState(attackerId, hits, active, lastHitTick, gapDeadline());
    }
```

Rewrite `onKnockShipped` (lines 110-153) to emit the full vocabulary. SWITCHED-wins is the §5.2 pin; the active-end reason stays EXPIRED because the public `ComboEndEvent.Reason` enum is frozen (§5.5):

```java
    public List<ComboTransition> onKnockShipped(UUID attacker, TickStamp tick) {
        if (attacker == null) {
            return List.of();
        }
        ComboTransition terminal = null;
        if (attackerId != null) {
            boolean expired = gapExceeded(tick);
            boolean switched = !attacker.equals(attackerId);
            if (expired || switched) {
                if (active) {
                    // The public end vocabulary is frozen (gen-3 §5.5): an active
                    // takeover still reports EXPIRED, matching every release before it.
                    terminal = ComboTransition.ended(attackerId, hits, ComboEndReason.EXPIRED, tick);
                } else {
                    // Developing chains DO distinguish the cause — SWITCHED wins
                    // whenever the terminating hit's attacker differs (§5.2 pin),
                    // so the "new attacker after the gap already lapsed" hit is stable.
                    terminal = ComboTransition.chainAborted(attackerId, hits,
                            switched ? ComboAbortReason.SWITCHED : ComboAbortReason.EXPIRED, tick);
                }
                resetChain();
            }
        }
        boolean opened = false;
        if (attackerId == null) {
            attackerId = attacker;
            hits = 0;
            opened = true;
        } else if (lastHitTick.known() && tick.known() && tick.value() > lastHitTick.value()) {
            cadenceEmaTicks = PocketServo.cadenceEma(cadenceEmaTicks, tick.value() - lastHitTick.value());
        }
        hits++;
        lastHitTick = tick;
        groundedRun = 0; // a fresh knock re-launches the victim
        ComboTransition advance;
        if (!active && hits >= rules.minHits()) {
            active = true;
            activeSince = tick;
            advance = ComboTransition.started(attackerId, hits, tick, gapDeadline());
        } else if (!active) {
            advance = opened
                    ? ComboTransition.chainOpened(attackerId, tick, gapDeadline())
                    : ComboTransition.chainAdvanced(attackerId, hits, tick, gapDeadline());
        } else {
            advance = ComboTransition.hit(attackerId, hits, tick, gapDeadline());
        }
        return terminal == null ? List.of(advance) : List.of(terminal, advance);
    }
```

Rewrite `onOwnHitLanded` (the tick param becomes load-bearing):

```java
    public ComboTransition onOwnHitLanded(TickStamp tick) {
        boolean wasActive = active;
        boolean wasDeveloping = attackerId != null && !active;
        UUID endedAttacker = attackerId;
        int endedHits = hits;
        resetChain();
        if (wasActive) {
            return ComboTransition.ended(endedAttacker, endedHits, ComboEndReason.RETALIATION, tick);
        }
        if (wasDeveloping) {
            return ComboTransition.chainAborted(endedAttacker, endedHits, ComboAbortReason.RETALIATION, tick);
        }
        return ComboTransition.NONE;
    }
```

`onTick` — the gap-expiry branch aborts developing chains too; grounded/blowout stay active-only and gain hits+tick:

```java
        if (attackerId != null && gapExceeded(now)) {
            boolean wasActive = active;
            UUID endedAttacker = attackerId;
            int endedHits = hits;
            resetChain();
            return wasActive
                    ? ComboTransition.ended(endedAttacker, endedHits, ComboEndReason.EXPIRED, now)
                    : ComboTransition.chainAborted(endedAttacker, endedHits, ComboAbortReason.EXPIRED, now);
        }
```

and in the grounded/blowout branches replace `ComboTransition.ended(endedAttacker, ComboEndReason.GROUNDED)` with `ComboTransition.ended(endedAttacker, endedHits, ComboEndReason.GROUNDED, now)` (capture `int endedHits = hits;` before `resetChain()`; same for BLOWOUT).

`reset` — signature change + developing abort:

```java
    public ComboTransition reset(ComboEndReason reason, TickStamp tick) {
        boolean wasActive = active;
        boolean wasDeveloping = attackerId != null && !active;
        UUID endedAttacker = attackerId;
        int endedHits = hits;
        resetChain();
        if (wasActive) {
            return ComboTransition.ended(endedAttacker, endedHits, reason, tick);
        }
        if (wasDeveloping) {
            return ComboTransition.chainAborted(endedAttacker, endedHits, abortFor(reason), tick);
        }
        return ComboTransition.NONE;
    }

    private static ComboAbortReason abortFor(ComboEndReason reason) {
        return switch (reason) {
            case RETIRED -> ComboAbortReason.RETIRED;
            case DISABLED -> ComboAbortReason.DISABLED;
            case RETALIATION -> ComboAbortReason.RETALIATION;
            // GROUNDED/BLOWOUT can never reach a developing chain (active-only
            // guards); EXPIRED is the defensive mapping, not a reachable path.
            case EXPIRED, GROUNDED, BLOWOUT -> ComboAbortReason.EXPIRED;
        };
    }
```

- [ ] **Step 5: update + extend `ComboTrackerTest`.** Update every existing test to the new factories/shapes. The behavioral pins that CHANGE (deliberately, per spec): hit 1 now emits `CHAIN_OPENED` (was nothing) — `activatesExactlyOnTheSecondHit`; a developing retaliation now emits `CHAIN_ABORTED(RETALIATION)` (was NONE) — `ownHitOnADevelopingChainResetsSilently` (rename to `ownHitOnADevelopingChainAborts`); active continuation hits now emit `HIT` (was nothing); `reset` takes a tick. The pins that MUST NOT change: gap survives at exactly maxGap and dies at +1; END-then-START order at minHits==1; grounded/blowout thresholds and cadence-EMA math; `snapshot()` behavior. Add these new tests (hand-compute every expectation; `RULES = ComboRules.DEFAULTS` = minHits 2, maxGap 20 unless stated):

```java
    @Test
    void chainOpenedCarriesTheGapDeadline() {
        ComboTracker tracker = new ComboTracker(RULES);
        List<ComboTransition> t = tracker.onKnockShipped(A, t(10));
        assertEquals(1, t.size());
        assertEquals(ComboTransition.Kind.CHAIN_OPENED, t.get(0).kind());
        assertEquals(A, t.get(0).attacker());
        assertEquals(1, t.get(0).hits());
        assertEquals(10, t.get(0).tick().value());
        assertEquals(30, t.get(0).gapDeadline().value()); // 10 + maxGap 20
    }

    @Test
    void chainAdvancesBeforePromotionAtMinHitsThree() {
        ComboTracker tracker = new ComboTracker(new ComboRules(3, 20, 10, 6.0));
        tracker.onKnockShipped(A, t(0));
        List<ComboTransition> t2 = tracker.onKnockShipped(A, t(8));
        assertEquals(ComboTransition.Kind.CHAIN_ADVANCED, t2.get(0).kind());
        assertEquals(2, t2.get(0).hits());
        List<ComboTransition> t3 = tracker.onKnockShipped(A, t(16));
        assertEquals(ComboTransition.Kind.STARTED, t3.get(0).kind()); // promotion is STARTED alone — never HIT, never CHAIN
        assertEquals(3, t3.get(0).hits());
    }

    @Test
    void activeContinuationEmitsHitWithDeadline() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        tracker.onKnockShipped(A, t(10)); // STARTED (minHits 2)
        List<ComboTransition> t3 = tracker.onKnockShipped(A, t(20));
        assertEquals(ComboTransition.Kind.HIT, t3.get(0).kind());
        assertEquals(3, t3.get(0).hits());
        assertEquals(20, t3.get(0).tick().value());
        assertEquals(40, t3.get(0).gapDeadline().value());
    }

    @Test
    void developingSwitchAbortsSwitchedThenOpensTheNewChain() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        List<ComboTransition> t = tracker.onKnockShipped(B, t(5)); // in-window switch
        assertEquals(2, t.size());
        assertEquals(ComboTransition.Kind.CHAIN_ABORTED, t.get(0).kind());
        assertEquals(ComboAbortReason.SWITCHED, t.get(0).abortReason());
        assertEquals(A, t.get(0).attacker());
        assertEquals(1, t.get(0).hits());
        assertEquals(ComboTransition.Kind.CHAIN_OPENED, t.get(1).kind());
        assertEquals(B, t.get(1).attacker());
    }

    @Test
    void switchedWinsOverExpiredWhenBothCoincide() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        List<ComboTransition> t = tracker.onKnockShipped(B, t(50)); // gap lapsed AND attacker differs
        assertEquals(ComboAbortReason.SWITCHED, t.get(0).abortReason()); // §5.2 pin
    }

    @Test
    void developingGapExpiryAbortsExpiredOnTheSweep() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        assertEquals(ComboTransition.NONE, tracker.onTick(t(20), false, Double.NaN)); // at deadline: alive
        ComboTransition abort = tracker.onTick(t(21), false, Double.NaN);
        assertEquals(ComboTransition.Kind.CHAIN_ABORTED, abort.kind());
        assertEquals(ComboAbortReason.EXPIRED, abort.abortReason());
    }

    @Test
    void resetAbortsADevelopingChainWithTheMappedReason() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        ComboTransition abort = tracker.reset(ComboEndReason.DISABLED, t(3));
        assertEquals(ComboTransition.Kind.CHAIN_ABORTED, abort.kind());
        assertEquals(ComboAbortReason.DISABLED, abort.abortReason());
        assertEquals(ComboViewState.NONE, tracker.view());
    }

    @Test
    void endedCarriesFinalHitsAndTick() {
        ComboTracker tracker = new ComboTracker(RULES);
        tracker.onKnockShipped(A, t(0));
        tracker.onKnockShipped(A, t(10));
        tracker.onKnockShipped(A, t(20));
        ComboTransition end = tracker.onOwnHitLanded(t(25));
        assertEquals(ComboTransition.Kind.ENDED, end.kind());
        assertEquals(3, end.hits());
        assertEquals(25, end.tick().value());
        assertEquals(ComboEndReason.RETALIATION, end.reason());
    }

    @Test
    void viewProgressesNoneDevelopingActiveAndBack() {
        ComboTracker tracker = new ComboTracker(RULES);
        assertTrue(tracker.view().none());
        tracker.onKnockShipped(A, t(10));
        ComboViewState developing = tracker.view();
        assertTrue(developing.developing());
        assertEquals(A, developing.attackerId());       // the read ComboSnapshot could never give
        assertEquals(1, developing.hits());
        assertEquals(10, developing.lastKnockTick().value());
        assertEquals(30, developing.gapDeadline().value());
        tracker.onKnockShipped(A, t(18));
        ComboViewState activeView = tracker.view();
        assertTrue(activeView.active());
        assertEquals(2, activeView.hits());
        assertEquals(38, activeView.gapDeadline().value()); // 18 + 20
        tracker.onOwnHitLanded(t(20));
        assertTrue(tracker.view().none());
    }

    @Test
    void balanceInvariantsHoldOverRandomizedSequences() {
        java.util.Random random = new java.util.Random(20260718L);
        for (int run = 0; run < 50; run++) {
            ComboTracker tracker = new ComboTracker(RULES);
            int opened = 0, openTerminals = 0, starts = 0, ends = 0;
            int tick = 0;
            java.util.List<ComboTransition> all = new java.util.ArrayList<>();
            for (int op = 0; op < 200; op++) {
                tick += random.nextInt(30);
                switch (random.nextInt(4)) {
                    case 0, 1 -> all.addAll(tracker.onKnockShipped(random.nextBoolean() ? A : B, t(tick)));
                    case 2 -> all.add(tracker.onTick(t(tick), random.nextBoolean(), Double.NaN));
                    case 3 -> all.add(tracker.onOwnHitLanded(t(tick)));
                }
            }
            all.add(tracker.reset(ComboEndReason.RETIRED, t(tick + 1)));
            for (ComboTransition t : all) {
                switch (t.kind()) {
                    case CHAIN_OPENED -> opened++;
                    case CHAIN_ABORTED -> openTerminals++;
                    case STARTED -> { starts++; openTerminals++; } // promotion terminates the developing sequence
                    case ENDED -> ends++;
                    default -> { }
                }
            }
            // Every developing sequence (except a promotion at minHits==1, impossible
            // here) opens with CHAIN_OPENED and terminates in exactly one of
            // STARTED / CHAIN_ABORTED; every STARTED gets exactly one ENDED.
            assertEquals(opened, openTerminals, "developing sequences unbalanced (run " + run + ")");
            assertEquals(starts, ends, "start/end unbalanced (run " + run + ")");
        }
    }
```

(`t(int)` is the existing tick helper; keep the file's local conventions. Seeded `Random` is fine in kernel tests.)

- [ ] **Step 6: run** `./gradlew :kernel:test` — expected: BUILD SUCCESSFUL, all kernel tests green. NOTE: `:core` will NOT compile yet (it still calls the old factories/`reset(reason)`) — that is expected mid-plan; do not run the full `build` in this task.

- [ ] **Step 7 (orchestrator): commit** — `feat(kernel): surface the developing-chain combo transitions` with a prose body covering: the four silent edges now emit (CHAIN_OPENED/ADVANCED/ABORTED/HIT), SWITCHED-wins pin, tick-stamped ends, `view()` for the gen-3 publish, frozen public end vocabulary.

---

### Task 2: Api module — the generation-3 surface

**Files:**
- Modify: `api/src/main/java/me/vexmc/mental/api/Mental.java`
- Create: `api/src/main/java/me/vexmc/mental/api/MentalCombat.java`
- Create: `api/src/main/java/me/vexmc/mental/api/ComboView.java`
- Create: `api/src/main/java/me/vexmc/mental/api/event/ComboChainEvent.java`, `ComboChainAbortEvent.java`, `ComboHitEvent.java`
- Modify: `api/src/main/java/me/vexmc/mental/api/event/ComboStartEvent.java`, `ComboEndEvent.java`, `KnockbackApplyEvent.java`
- Create: `api/src/main/java/me/vexmc/mental/api/package-info.java`
- Modify: `api/build.gradle.kts` (maven-publish), `gradle.properties` (add `apiVersion=3.0.0` ONLY — do NOT touch `version=`)

**Interfaces (Produces — Tasks 3/4 rely on these exact signatures):**
- `Mental.MentalApi`: `default boolean has(@NotNull Capability capability) { return false; }`, `default @Nullable MentalCombat combat() { return null; }`, nested `enum Capability { COMBO_EVENTS, COMBO_CHAIN_EVENTS, COMBO_HIT_EVENTS, COMBO_QUERY, WINDOW_QUERY, KNOCKBACK_OUTCOMES, MITIGATION_PREVIEW }`
- `MentalCombat`: `long NO_TICK = -1;` `@NotNull ComboView comboOn(@NotNull UUID victim);` `boolean hurtWindowClear(@NotNull Player victim);` `long currentTick();`
- `ComboView`: `@NotNull State state(); @Nullable UUID attackerId(); int hits(); long lastKnockTick(); long gapDeadlineTick(); enum State { NONE, DEVELOPING, ACTIVE }`
- `ComboChainEvent`: ctor `(Player victim, LivingEntity attacker, UUID attackerId, int hits, long gapDeadlineTick)`; getters `getVictim() @NotNull`, `getAttacker() @Nullable`, `getAttackerId() @NotNull`, `getHits()`, `getGapDeadlineTick()`
- `ComboChainAbortEvent`: ctor `(Player victim, UUID attackerId, int hits, Reason reason)`; nested `enum Reason { EXPIRED, SWITCHED, RETALIATION, RETIRED, DISABLED }`; getters `getVictim()`, `getAttackerId()`, `getHits()`, `getReason()`
- `ComboHitEvent`: ctor `(Player victim, UUID attackerId, int hits, long tick, long gapDeadlineTick)`; getters `getVictim()`, `getAttackerId()`, `getHits()`, `getTick()`, `getGapDeadlineTick()`
- `ComboStartEvent`: NEW ctor `(Player victim, LivingEntity attacker, UUID attackerId, int hits, long startedTick)`; old 3-arg ctor KEPT `@Deprecated` (derives `attackerId` from the entity when present); new getters `getAttackerId() @NotNull`, `getStartedTick()`
- `ComboEndEvent`: NEW ctor `(Player victim, LivingEntity attacker, UUID attackerId, Reason reason, int hits, long endedTick)`; old 3-arg ctor KEPT `@Deprecated`; new getters `getAttackerId() @NotNull`, `getHits()`, `getEndedTick()`; `Reason` enum byte-identical
- `KnockbackApplyEvent`: NEW ctor `(Player victim, LivingEntity attacker, Vector velocity, UUID attackerId, Source source)`; old 3-arg ctor KEPT `@Deprecated` (attackerId from entity, source OTHER); `enum Source { MELEE, ROD, PROJECTILE, OTHER }`, `enum Outcome { SHIP, SUPPRESSED, YIELDED }`, `getAttackerId() @Nullable`, `getSource() @NotNull`, `suppress()`, `getOutcome() @NotNull`

- [ ] **Step 1: `Mental.java`.** Update the `apiVersion()` javadoc (generation 3 exists; the default stays 1 — the conservative graceful-degradation contract) and add, inside `interface MentalApi`:

```java
        /**
         * Fine-grained capability probe. Additive evolution contract: new
         * behaviour always arrives behind a new constant, and an
         * implementation compiled against an earlier interface answers
         * {@code false} for constants it has never heard of — so consumers
         * probe, never version-sniff. Capability truth is static per
         * implementation: {@link Capability#COMBO_QUERY} stays {@code true}
         * even while the combo modules are toggled off ({@link #combat()}
         * answering null is the runtime signal).
         */
        default boolean has(@NotNull Capability capability) {
            return false;
        }

        /**
         * The combat-state query service (generation 3). Null when the
         * capability is absent OR while no combo-family module is enabled
         * (combo detection not running). Re-fetch per decision rather than
         * caching — it is one volatile read — though a held handle degrades
         * safely: after a module toggle-off or plugin disable it answers the
         * NONE/false shapes, never an exception, never stale ACTIVE state.
         */
        default @Nullable MentalCombat combat() {
            return null;
        }

        /** Generation-3 capability constants (see {@link #has(Capability)}). */
        enum Capability {
            COMBO_EVENTS,
            COMBO_CHAIN_EVENTS,
            COMBO_HIT_EVENTS,
            COMBO_QUERY,
            WINDOW_QUERY,
            KNOCKBACK_OUTCOMES,
            MITIGATION_PREVIEW
        }
```

Also extend the `register` javadoc: registered on enable before any gen-3 event can fire; nulled on disable after the last balanced terminal event has fired.

- [ ] **Step 2: `MentalCombat.java`** — new file, javadoc lifted from spec §6 (the pinned expression, the publish-on-transition thread contract, the tick frame). The §5.7 frame javadoc must NAME the clock: on Paper the frame is `Bukkit.getCurrentTick()`; on Folia and the legacy tier it is Mental's own global-region counter — monotonic, shared across all victims, and NOT comparable to the server tick or any foreign counter; only deltas against other values from this surface are sanctioned.

```java
package me.vexmc.mental.api;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public interface MentalCombat {

    /** Sentinel for "no tick": no chain clock, or the frame is not running. */
    long NO_TICK = -1;

    /**
     * Snapshot of the combo machine for {@code victim}. Never null and never
     * throws — state NONE (attackerId null, ticks {@link #NO_TICK}) for a
     * victim with no chain, an offline victim, or a UUID Mental has never
     * seen. Callable from ANY thread: the implementation publishes an
     * immutable view at each transition (write on the victim's session
     * thread, read anywhere). An on-thread call (the victim's owning region
     * thread) observes the exact current state; an off-thread call may lag
     * by at most the in-flight transition, and is never torn.
     */
    @NotNull ComboView comboOn(@NotNull UUID victim);

    /**
     * The pinned hurt-window admit test for {@code victim}:
     * {@code victim.getNoDamageTicks() <= victim.getMaximumNoDamageTicks() / 2}
     * (integer division — the vanilla admit gate). PINNED AS THE CONTRACT:
     * this deliberately does NOT track Mental's internal "+1 staleness"
     * variant or any other fast-path read — integrators get ONE stable,
     * tested expression for "would a fresh hurt ship cleanly rather than be
     * window-swallowed". If Mental ever retunes its own boundary family,
     * this expression only changes with a Capability bump. Victim's owning
     * region thread only (it reads the live entity). Never re-derive this
     * for the Mental-integration decision; your own Mental-agnostic
     * vanilla-window reads are unaffected.
     */
    boolean hurtWindowClear(@NotNull Player victim);

    /** The current tick in this surface's clock frame. Any thread. */
    long currentTick();
}
```

- [ ] **Step 3: `ComboView.java`** — new file, exactly the spec §6 shape (interface + nested `State`), javadoc on each accessor (`attackerId()` null iff NONE; both ticks `NO_TICK` iff NONE).

- [ ] **Step 4: the three new events.** Copy the HandlerList/final/non-cancellable pattern from `ComboStartEvent` verbatim (private static final HANDLERS, `getHandlers`, static `getHandlerList`). All three: fired on the victim's owning region thread, not cancellable, `final`. Javadoc must state (verbatim intent from §5.1/§5.2/§5.4):
  - `ComboChainEvent`: fired on every **confirmed-shipped** qualifying melee knock that advances a not-yet-active chain (hits `1..minHits−1`); "qualifying" = a melee knock Mental confirmed shipped on the victim's velocity event — a foreign-cancelled velocity never advances a chain (D1), and natively blocked hits DO qualify (they re-deliver through the same authoritative velocity seam — D2). `getGapDeadlineTick()` javadoc: the Mental-clock tick by which the next qualifying knock must ship or this chain dies; compute "ticks remaining" only as a delta against `MentalCombat#currentTick()`.
  - `ComboChainAbortEvent`: fired when a DEVELOPING chain dies without activating; Reason javadoc pins SWITCHED-wins.
  - `ComboHitEvent`: fired on every chain-advancing confirmed-shipped knock while ACTIVE (hit `minHits+1` onward); the promotion hit is announced by `ComboStartEvent` alone — the two never fire for the same knock.

- [ ] **Step 5: additive upgrades to `ComboStartEvent` / `ComboEndEvent`.** Add fields + new ctor + getters per the Interfaces block. Old ctors delegate to the new ones (`attacker != null ? attacker.getUniqueId() : null`, tick `MentalCombat.NO_TICK`) and are `@Deprecated` with javadoc "constructor kept for binary compatibility; events Mental fires always carry a non-null attacker id". Fix `ComboStartEvent`'s stale "third qualifying melee hit" javadoc → "the configured min-hits qualifying melee knock (default: the second)". Extend both classes' javadoc with the §5.6 balance invariants (every start followed by exactly one end, including DISABLED on module toggle and plugin disable; attacker-switch restart fires the old terminal before the new opening event, same thread, same tick).

- [ ] **Step 6: `KnockbackApplyEvent` outcome machine.** Replace the `boolean cancelled` field with `Outcome outcome = Outcome.SHIP` plus `UUID attackerId` and `Source source` fields. One last-writer-wins ordering across all three writers, documented in the class javadoc verbatim from §8:

```java
    public void velocity(@NotNull Vector velocity) {
        this.velocity = velocity.clone();
        this.outcome = Outcome.SHIP;   // "ship exactly this" — clears a prior cancel/suppress
    }

    /** Ship ZERO velocity — explicit intent, distinct from YIELDED (§8). Clears a prior cancel. */
    public void suppress() {
        this.outcome = Outcome.SUPPRESSED;
    }

    @Override
    public boolean isCancelled() {
        return outcome == Outcome.YIELDED;
    }

    @Override
    public void setCancelled(boolean cancel) {
        // true → YIELDED: Mental stands down and vanilla's own velocity applies
        // (NOT "no knockback"); false → restore SHIP with the last written vector.
        this.outcome = cancel ? Outcome.YIELDED : Outcome.SHIP;
    }

    public @NotNull Outcome getOutcome() {
        return outcome;   // current accumulated state; final only when read at MONITOR
    }

    public @Nullable UUID getAttackerId() {
        return attackerId;
    }

    public @NotNull Source getSource() {
        return source;
    }

    public enum Source { MELEE, ROD, PROJECTILE, OTHER }

    public enum Outcome { SHIP, SUPPRESSED, YIELDED }
```

Javadoc additions: YIELDED knocks do not advance combo chains (Mental did not ship them); SUPPRESSED knocks do (a zero-velocity melee still shipped). No runtime null-checks in the ctor (unit tests construct with nulls).

- [ ] **Step 7: `package-info.java`** — the §10 policy contracts, in prose:
  1. Era-authenticity stance: vanilla periodic damage (fire tick, poison, wither, drowning…) interacts with combos exactly as 1.8 vanilla — it CAN break them; Mental will never defer or suppress it. Custom plugin damage is the plugin's own responsibility using the events + queries + the ordering contract.
  2. Foreign-window behaviour: foreign damage mid-combo re-arms the victim's hurt window; the next era knock is then window-swallowed and the combo's motion chain breaks — that is WHY integrators defer their own effects (Mental publishes combat truth; integrators defer/shape their own effects — the ownership split).
  3. The D1/D2 rulings, as ratified above (confirmed-ship feed; blocked knocks qualify via the authoritative velocity seam).
  4. The §7 ordering contract VERBATIM: combo advancement is fed at the MONITOR confirm of the victim's `PlayerVelocityEvent`, after the victim's `EntityDamageEvent` for that hit has completed its entire handler chain; therefore **a query made from inside any `EntityDamageEvent` handler observes the pre-hit combo state**; all combo events for that hit fire later in the same tick, on the same thread. Include the fold-eligibility statement: at fold time, `comboOn(victim)` answering ACTIVE *or* DEVELOPING with `attackerId()` equal to this hit's attacker means the hit continues (or is about to promote) that chain — the DEVELOPING arm matters precisely on the promotion hit; only NONE, or a different attacker, is not fold-eligible.

- [ ] **Step 8: publishing.** `gradle.properties` — insert directly above the `version=` line:

```properties
# The public api artifact's own semver (me.vexmc:mental-api). Independent of the
# plugin version above by design: the api surface evolves additively and slower.
apiVersion=3.0.0
```

`api/build.gradle.kts` — add at the very top (before the existing header comment content, as the first statement):

```kotlin
plugins {
    `maven-publish`
}
```

and after the dependencies block:

```kotlin
java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mentalApi") {
            from(components["java"])
            groupId = "me.vexmc"
            artifactId = "mental-api"
            version = providers.gradleProperty("apiVersion").get()
            pom {
                name.set("mental-api")
                description.set("Mental's public combat-integration surface (API generation 3)")
            }
        }
    }
}
```

No repository block — `publishToMavenLocal` targets `~/.m2` implicitly; consumers use mavenLocal / the composite build. Do NOT touch the japicmp block or the `version=` line.

- [ ] **Step 9: run** `./gradlew :api:build` — expected: BUILD SUCCESSFUL including `:api:apiCompat` (all changes are additive → japicmp green). Then `./gradlew :api:publishToMavenLocal` and verify `~/.m2/repository/me/vexmc/mental-api/3.0.0/mental-api-3.0.0.jar` exists.

- [ ] **Step 10 (orchestrator): commit** — `feat(api): generation-3 surface — capabilities, combat queries, chain events, knockback outcomes` with a prose body: the H1-H9 hacks each item kills, the frozen-enum/deprecated-ctor binary-compat strategy, the publication-level 3.0.0 versioning.

---

### Task 3: Core — confirmed-ship feed, view publishing, query service, facade v3, disable drain

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/combo/ComboViewBook.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/api/WindowJudge.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/api/MentalCombatService.java`
- Modify: `core/src/main/java/me/vexmc/mental/v5/feature/combo/ComboEvents.java`
- Modify: `core/src/main/java/me/vexmc/mental/v5/delivery/DeskRouter.java`
- Modify: `core/src/main/java/me/vexmc/mental/v5/session/SessionService.java`
- Modify: `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java` (the `onOwnHitLanded` call site, ~:490-495)
- Modify: `core/src/main/java/me/vexmc/mental/v5/api/MentalFacade.java`
- Modify: `core/src/main/java/me/vexmc/mental/v5/MentalPluginV5.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/api/WindowJudgeTest.java`, `core/src/test/java/me/vexmc/mental/v5/api/KnockbackApplyOutcomeTest.java` (match the existing core test package layout — put them wherever the existing core unit tests for `v5` classes live)

**Interfaces:**
- Consumes Task 1: `ComboTransition` kinds/components, `ComboViewState`, `ComboTracker.view()/reset(reason, tick)`; Task 2: all api types.
- Produces: `ComboViewBook.publish(UUID, ComboViewState)` / `.forget(UUID)` / `.viewOf(UUID)`; `MentalCombatService implements MentalCombat`; `SessionService.comboDetectionLive()` and `SessionService.drainComboTerminals()`; `MentalPluginV5.combatQuery()`; `ComboEvents.fire(Player, ComboViewState, ComboTransition)` and `fire(Player, ComboViewState, List<ComboTransition>)`.

- [ ] **Step 1: `WindowJudge`** (pure, so the pinned expression is unit-testable):

```java
package me.vexmc.mental.v5.api;

/**
 * The gen-3 §6 pinned hurt-window admit test. ONE expression, frozen as the
 * public contract — deliberately NOT PlayerView.damageImmune()'s "+1
 * staleness" read or any other internal fast-path variant. Only a Capability
 * bump may change it.
 */
public final class WindowJudge {

    private WindowJudge() {
    }

    public static boolean clear(int noDamageTicks, int maximumNoDamageTicks) {
        return noDamageTicks <= maximumNoDamageTicks / 2;
    }
}
```

- [ ] **Step 2: `WindowJudgeTest`** — pin the cells, especially the divergent one: `(0,20)→true`, `(10,20)→true`, `(11,20)→false` (the cell where the "+1 staleness" variant disagrees — `damageImmune` would NOT be immune at 11), `(12,20)→false`, `(20,20)→false`, `(5,10)→true`, `(6,10)→false`, `(0,0)→true`. Run `./gradlew :core:test --tests '*WindowJudgeTest'` after writing — it should pass immediately (pure function).

- [ ] **Step 3: `KnockbackApplyOutcomeTest`** — the §8 last-writer-wins machine (construct with nulls; the event has no runtime null checks):

```java
    @Test
    void freshEventShips() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0), null,
                KnockbackApplyEvent.Source.MELEE);
        assertEquals(KnockbackApplyEvent.Outcome.SHIP, event.getOutcome());
        assertFalse(event.isCancelled());
    }

    @Test
    void suppressThenVelocityRestoresShip() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0), null,
                KnockbackApplyEvent.Source.MELEE);
        event.suppress();
        assertEquals(KnockbackApplyEvent.Outcome.SUPPRESSED, event.getOutcome());
        event.velocity(new Vector(0, 0, 2));
        assertEquals(KnockbackApplyEvent.Outcome.SHIP, event.getOutcome());
        assertEquals(2.0, event.velocity().getZ());
    }

    @Test
    void cancelYieldsAndVelocityClearsIt() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0), null,
                KnockbackApplyEvent.Source.MELEE);
        event.setCancelled(true);
        assertEquals(KnockbackApplyEvent.Outcome.YIELDED, event.getOutcome());
        assertTrue(event.isCancelled());
        event.velocity(new Vector(3, 0, 0));
        assertEquals(KnockbackApplyEvent.Outcome.SHIP, event.getOutcome());
        assertFalse(event.isCancelled());
    }

    @Test
    void uncancelRestoresShipWithLastWrittenVector() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0), null,
                KnockbackApplyEvent.Source.MELEE);
        event.velocity(new Vector(5, 0, 0));
        event.setCancelled(true);
        event.setCancelled(false);
        assertEquals(KnockbackApplyEvent.Outcome.SHIP, event.getOutcome());
        assertEquals(5.0, event.velocity().getX());
    }

    @Test
    void suppressAfterCancelWinsAndClearsCancel() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0), null,
                KnockbackApplyEvent.Source.MELEE);
        event.setCancelled(true);
        event.suppress();
        assertEquals(KnockbackApplyEvent.Outcome.SUPPRESSED, event.getOutcome());
        assertFalse(event.isCancelled());
    }

    @Test
    void deprecatedConstructorDerivesDefaults() {
        KnockbackApplyEvent event = new KnockbackApplyEvent(null, null, new Vector(1, 0, 0));
        assertNull(event.getAttackerId());
        assertEquals(KnockbackApplyEvent.Source.OTHER, event.getSource());
    }
```

NOTE: constructing a Bukkit `Event` subclass in a plain unit test is fine (no server needed as long as `getHandlers()` isn't called); if the test JVM complains about static Bukkit init, follow the pattern the existing `PacketTapStateTest` uses.

- [ ] **Step 4: `ComboViewBook`** — the §6 publish map:

```java
package me.vexmc.mental.v5.feature.combo;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.api.ComboView;
import me.vexmc.mental.api.MentalCombat;
import me.vexmc.mental.kernel.combo.ComboViewState;
import org.jetbrains.annotations.NotNull;

/**
 * The gen-3 published-view map: one immutable {@link ComboView} per victim,
 * written on the victim's session thread at every transition, readable from
 * any thread (§6). NONE-shaped states are removals — the map only ever holds
 * DEVELOPING/ACTIVE views, so a quit/retire leaves nothing behind.
 */
public final class ComboViewBook {

    private final ConcurrentHashMap<UUID, ComboView> views = new ConcurrentHashMap<>();

    public void publish(@NotNull UUID victim, ComboViewState state) {
        if (state == null || state.none()) {
            views.remove(victim);
            return;
        }
        views.put(victim, PublishedComboView.of(state));
    }

    public void forget(@NotNull UUID victim) {
        views.remove(victim);
    }

    public void clear() {
        views.clear();
    }

    public @NotNull ComboView viewOf(@NotNull UUID victim) {
        ComboView view = views.get(victim);
        return view == null ? PublishedComboView.NONE : view;
    }

    /** The api sentinel translation — kernel NO_TICK (MIN_VALUE) must never leak. */
    static long tickValue(me.vexmc.mental.kernel.model.TickStamp stamp) {
        return stamp != null && stamp.known() ? stamp.value() : MentalCombat.NO_TICK;
    }

    record PublishedComboView(ComboView.State state, UUID attackerId, int hits,
                              long lastKnockTick, long gapDeadlineTick) implements ComboView {

        static final PublishedComboView NONE = new PublishedComboView(
                ComboView.State.NONE, null, 0, MentalCombat.NO_TICK, MentalCombat.NO_TICK);

        static PublishedComboView of(ComboViewState state) {
            return new PublishedComboView(
                    state.active() ? ComboView.State.ACTIVE : ComboView.State.DEVELOPING,
                    state.attackerId(), state.hits(),
                    tickValue(state.lastKnockTick()), tickValue(state.gapDeadline()));
        }
    }
}
```

(Adjust the `tickValue` import style to the file-conventions — imports at top, never inline-qualified; shown inline here only for compactness. `PublishedComboView.NONE` needs package-visible access from `MentalCombatService` — keep both in reach or expose a `ComboViewBook.NONE_VIEW` constant; pick ONE and use it consistently.)

- [ ] **Step 5: `MentalCombatService`**:

```java
package me.vexmc.mental.v5.api;

import java.util.UUID;
import me.vexmc.mental.api.ComboView;
import me.vexmc.mental.api.MentalCombat;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.v5.feature.combo.ComboViewBook;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The gen-3 {@link MentalCombat} implementation. Held handles degrade to the
 * NONE/false shapes the moment combo detection stops (module toggle-off or
 * plugin disable) — never an exception, never stale ACTIVE state (§4).
 */
public final class MentalCombatService implements MentalCombat {

    private final SessionService sessions;
    private final ComboViewBook views;
    private final TickClock clock;

    public MentalCombatService(SessionService sessions, ComboViewBook views, TickClock clock) {
        this.sessions = sessions;
        this.views = views;
        this.clock = clock;
    }

    @Override
    public @NotNull ComboView comboOn(@NotNull UUID victim) {
        if (!sessions.comboDetectionLive()) {
            return ComboViewBook.NONE_VIEW;
        }
        return views.viewOf(victim);
    }

    @Override
    public boolean hurtWindowClear(@NotNull Player victim) {
        if (!sessions.comboDetectionLive()) {
            return false;   // the §4 defunct-handle shape
        }
        return WindowJudge.clear(victim.getNoDamageTicks(), victim.getMaximumNoDamageTicks());
    }

    @Override
    public long currentTick() {
        TickStamp now = clock.current();
        return now.known() ? now.value() : NO_TICK;
    }
}
```

(`TickClock` import path: match wherever `DeskRouter` imports it from.)

- [ ] **Step 6: `ComboEvents` rewrite.** Ctor gains the `ComboViewBook`; `fire` gains the post-mutation view and publishes it BEFORE dispatching events (so an on-thread `comboOn` from inside a handler observes the post-transition state, §6). Keep the reach-handicap + `ComboPredictor.forget` side effects EXACTLY coincident with STARTED/ENDED — aborts/chains/hits must not touch them:

```java
    public void fire(Player victim, ComboViewState view, ComboTransition transition) {
        if (transition == null || transition.kind() == ComboTransition.Kind.NONE) {
            return;
        }
        views.publish(victim.getUniqueId(), view);
        fireOne(victim, transition);
    }

    public void fire(Player victim, ComboViewState view, List<ComboTransition> transitions) {
        if (transitions == null || transitions.isEmpty()) {
            return;
        }
        boolean any = false;
        for (ComboTransition transition : transitions) {
            if (transition != null && transition.kind() != ComboTransition.Kind.NONE) {
                any = true;
            }
        }
        if (!any) {
            return;
        }
        views.publish(victim.getUniqueId(), view);
        for (ComboTransition transition : transitions) {
            fireOne(victim, transition);
        }
    }

    private void fireOne(Player victim, ComboTransition transition) {
        if (transition == null || transition.kind() == ComboTransition.Kind.NONE) {
            return;
        }
        switch (transition.kind()) {
            case STARTED -> {
                reachHandicap.onComboStart(victim);
                Bukkit.getPluginManager().callEvent(new ComboStartEvent(
                        victim, resolve(transition.attacker()), transition.attacker(),
                        transition.hits(), tickValue(transition.tick())));
            }
            case ENDED -> {
                reachHandicap.onComboEnd(victim);
                ComboPredictor.forget(victim.getUniqueId());
                Bukkit.getPluginManager().callEvent(new ComboEndEvent(
                        victim, resolve(transition.attacker()), transition.attacker(),
                        map(transition.reason()), transition.hits(), tickValue(transition.tick())));
            }
            case CHAIN_OPENED, CHAIN_ADVANCED -> Bukkit.getPluginManager().callEvent(new ComboChainEvent(
                    victim, resolve(transition.attacker()), transition.attacker(),
                    transition.hits(), tickValue(transition.gapDeadline())));
            case CHAIN_ABORTED -> Bukkit.getPluginManager().callEvent(new ComboChainAbortEvent(
                    victim, transition.attacker(), transition.hits(), mapAbort(transition.abortReason())));
            case HIT -> Bukkit.getPluginManager().callEvent(new ComboHitEvent(
                    victim, transition.attacker(), transition.hits(),
                    tickValue(transition.tick()), tickValue(transition.gapDeadline())));
            case NONE -> { }
        }
    }

    private static ComboChainAbortEvent.Reason mapAbort(ComboAbortReason reason) {
        return switch (reason) {
            case EXPIRED -> ComboChainAbortEvent.Reason.EXPIRED;
            case SWITCHED -> ComboChainAbortEvent.Reason.SWITCHED;
            case RETALIATION -> ComboChainAbortEvent.Reason.RETALIATION;
            case RETIRED -> ComboChainAbortEvent.Reason.RETIRED;
            case DISABLED -> ComboChainAbortEvent.Reason.DISABLED;
        };
    }
```

`tickValue` delegates to `ComboViewBook.tickValue`. Expose the book: `public ComboViewBook views() { return views; }` (SessionService uses it in `forget`). Keep the existing `resolve` and `map` helpers as-is. The old `fire(Player, ComboTransition)` / `fire(Player, List)` signatures are REPLACED (all call sites updated this task).

- [ ] **Step 7: `DeskRouter` — the D1 move.** Add the pending-feed structure beside `PendingArm` (same ThreadLocal/nested-dispatch rationale — a re-entrant dispatch, e.g. the blocked path's authoritative `setVelocity`, gets its own identity-checked stash):

```java
    /** One velocity dispatch's combo-feed intent, stashed at HIGH and fed at the
     * MONITOR confirm — the D1 ruling: a chain advances only for a knock the
     * client actually received (the dead-arm lesson, applied to the feed). */
    private record PendingFeed(PlayerVelocityEvent event, CombatSession session, Player victim,
                               UUID attackerId, TickStamp tick) {}

    private final ThreadLocal<PendingFeed> pendingFeed = new ThreadLocal<>();
```

In the HIGH handler: construct the api event with the new ctor + consume the outcome machine:

```java
        HitContext context = desk.pendingContext();
        KnockbackApplyEvent api = new KnockbackApplyEvent(
                victim, resolveAttacker(context), Vectors.toBukkit(formula),
                context == null ? null : context.attackerId(), sourceOf(context));
        Bukkit.getPluginManager().callEvent(api);
        if (api.getOutcome() == KnockbackApplyEvent.Outcome.YIELDED) {
            // Yield: vanilla's own velocity stands; withdraw so the desk forgets the
            // decision. A yielded knock never advances a combo chain — Mental did
            // not ship it.
            if (context != null) {
                desk.withdraw(context.id());
            }
            return;
        }
        Vector velocity = api.getOutcome() == KnockbackApplyEvent.Outcome.SUPPRESSED
                ? new Vector(0, 0, 0)
                : api.velocity();
```

`sourceOf` — map the kernel `HitSource` (read the enum's actual constants in `kernel`) with the rule: everything `isMelee(...)` already treats as melee → `MELEE`; the rod/fishing source → `ROD`; projectile sources → `PROJECTILE`; anything else → `OTHER`. Write the switch exhaustively over the real constants, no default-only mapping.

Replace the HIGH-time feed (the old `feedComboOnShip` call at :108) with the stash — and rewrite the :103-107 comment with the ruling:

```java
        if (context != null && directive.ship() != null) {
            Deliveries.recordDelivered(session, context.source(), directive.ship());
            // The combo feed (gen-3 D1): stashed here, fed at the MONITOR confirm so a
            // chain only advances for a knock that survived every listener. This is
            // still the ONE ship seam every melee delivery funnels through — the
            // region path, the pre-sent/pinned adopt, AND the blocked re-delivery
            // (KnockbackUnit.deliverBlockedKnock's authoritative setVelocity fires a
            // real PlayerVelocityEvent that re-enters this handler) — so it needs no
            // per-path duplication. The ledger record and desk journal above remain
            // decision-time (HIGH) by design; the valve and the combo feed are the
            // two confirmed-ship artifacts.
            if (session.comboTracker() != null && isMelee(context.source()) && context.attackerId() != null) {
                pendingFeed.set(new PendingFeed(event, session, victim, context.attackerId(), clock.current()));
            }
        }
```

MONITOR handler — consume the feed FIRST, then the existing arm confirm (both identity-checked, independent):

```java
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerVelocityConfirm(PlayerVelocityEvent event) {
        PendingFeed feed = pendingFeed.get();
        if (feed != null && feed.event() == event) {
            pendingFeed.remove();
            if (!event.isCancelled()) {
                ComboTracker tracker = feed.session().comboTracker();
                if (tracker != null) {
                    List<ComboTransition> transitions = tracker.onKnockShipped(feed.attackerId(), feed.tick());
                    comboEvents.fire(feed.victim(), tracker.view(), transitions);
                }
            }
        }
        PendingArm intent = pendingArm.get();
        // ... existing arm-confirm body unchanged ...
    }
```

Note the feed gate is `!event.isCancelled()` only — NOT `confirmsArm` (a foreign *modify* still ships a knock). Delete the old `feedComboOnShip` method; keep `isMelee`.

- [ ] **Step 8: `SessionService`.** Add:

```java
    /** Whether combo detection is running (either combo-family keeper open) —
     * the gen-3 combat() nullness gate. Any thread (atomic read). */
    public boolean comboDetectionLive() {
        return comboKeepers.get() > 0;
    }
```

Update `driveCombo` (mutate-then-fire; the view after a reset is NONE, which publishes the removal):

```java
        ComboTracker tracker = session.comboTracker();
        if (comboKeepers.get() <= 0) {
            if (tracker != null) {
                ComboTransition terminal = tracker.reset(ComboEndReason.DISABLED, clock.current());
                session.clearComboTracker();
                comboEvents.fire(player, tracker.view(), terminal);
            }
            return;
        }
        ComboRules rules = comboRules();
        if (tracker == null) {
            tracker = session.installComboTracker(rules);
        } else if (!tracker.rules().equals(rules)) {
            ComboTransition retuned = tracker.reset(ComboEndReason.RETIRED, clock.current());
            comboEvents.fire(player, tracker.view(), retuned);
            tracker = session.installComboTracker(rules);
        }
        double separation = separationTo(player.getUniqueId(), tracker.activeAttacker());
        ComboTransition swept = tracker.onTick(clock.current(), combatGrounded, separation);
        comboEvents.fire(player, tracker.view(), swept);
```

Update `onQuit` the same way (`reset(ComboEndReason.RETIRED, clock.current())` into a local, then `fire(player, tracker.view(), terminal)`), and add `comboEvents.views().forget(id)` inside `forget(...)` beside the other map removals (defensive hygiene — the quit-path NONE publish already removed it).

Add the disable-time drain:

```java
    /**
     * Disable-time terminal drain (gen-3 §11.5): fire the balanced
     * DISABLED terminal for every live tracker BEFORE the api facade
     * unregisters. Runs synchronously on the disabling thread — the one
     * documented exception to the region-thread firing contract; per-session
     * isolation so one bad listener cannot starve the rest of the drain.
     */
    public void drainComboTerminals() {
        for (Map.Entry<UUID, CombatSession> entry : sessions.entrySet()) {
            try {
                CombatSession session = entry.getValue();
                ComboTracker tracker = session.comboTracker();
                if (tracker == null) {
                    continue;
                }
                ComboTransition terminal = tracker.reset(ComboEndReason.DISABLED, clock.current());
                session.clearComboTracker();
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    comboEvents.fire(player, tracker.view(), terminal);
                } else {
                    comboEvents.views().forget(entry.getKey());
                }
            } catch (Throwable t) {
                // one session's listener failure must not starve the drain
            }
        }
    }
```

(Adapt the map field name/iteration to the actual `SessionService` internals — the sessions map keyed by UUID exists; read the file. If `Bukkit`/`Map`/`UUID` imports are missing, add them.)

- [ ] **Step 9: `KnockbackUnit`** — update the retaliation call site (~:490-495) to the mutate-then-fire shape:

```java
                ComboTracker tracker = attackerSession.comboTracker();
                if (tracker != null) {
                    ComboTransition retaliated = tracker.onOwnHitLanded(clock.current());
                    comboEvents.fire(attacker, tracker.view(), retaliated);
                }
```

(Match the actual local variable names in the surrounding block — the "victim retaliating" player here is the attacker of the current hit; keep exactly the entity the old code passed to `fire`.)

- [ ] **Step 10: `MentalFacade`**:

```java
    @Override
    public int apiVersion() {
        return 3;
    }

    @Override
    public boolean has(Mental.MentalApi.Capability capability) {
        return switch (capability) {
            case COMBO_EVENTS, COMBO_CHAIN_EVENTS, COMBO_HIT_EVENTS,
                 COMBO_QUERY, WINDOW_QUERY, KNOCKBACK_OUTCOMES -> true;
            case MITIGATION_PREVIEW -> false;   // deferred to 3.1 by ruling
        };
    }

    @Override
    public @Nullable MentalCombat combat() {
        return plugin.combatQuery();
    }
```

- [ ] **Step 11: `MentalPluginV5` wiring.**
  - Construct `ComboViewBook comboViews = new ComboViewBook();` immediately before the `ComboEvents` construction (~:298) and pass it: `new ComboEvents(comboReachHandicap, comboViews)`.
  - After `SessionService` construction (~:304-307): `this.combatService = new MentalCombatService(sessions, comboViews, clock);` (new field).
  - Add: `public @Nullable MentalCombat combatQuery() { return sessions != null && sessions.comboDetectionLive() ? combatService : null; }`
  - At `Mental.register(facade)` (~:376) add the why-comment: registration lands after converge but before any gen-3 event can fire — combo events dispatch only from session ticks and velocity events, none of which run during onEnable.
  - `onDisable` reorder: locate the `"api facade unregister"` isolate (~:437-439) and the `"reconciler.closeAll"` isolate (~:452). MOVE the api-facade-unregister isolate to AFTER `reconciler.closeAll`, and INSERT between them a new isolate `"combo terminal drain"` calling `sessions.drainComboTerminals()` (null-guard `sessions`). Every other isolate keeps its current relative order. Resulting order: …existing early isolates… → `reconciler.closeAll` → `combo terminal drain` → `api facade unregister` (`Mental.register(null)` + ServicesManager unregister) → `sessions.shutdown` → …rest unchanged. Add a comment citing §11.5: terminals fire before `register(null)`.

- [ ] **Step 12: run** `./gradlew build` — expected: BUILD SUCCESSFUL — full unit tests, kernel-Bukkit-free assert, japicmp, all four mega-jar gates. Fix any compile fallout from the Task-1 signature changes (the compiler finds every stale call site — there must be zero remaining references to the old `fire(Player, ...)` shapes, old factories, or 1-arg `reset`).

- [ ] **Step 13 (orchestrator): commit** — `feat(core): wire generation 3 — confirmed-ship combo feed, view publishing, combat queries, facade v3` with a prose body covering: the D1 MONITOR move + why `pendingArm` couldn't carry it, the D2 re-entrancy finding (comment now explains the funnel), the disable-drain ordering fix, `combat()` keyed on detection-live, and the two decision-time artifacts (ledger/journal) left at HIGH by design.

---

### Task 4: Tester — acceptance criteria live

**Files:**
- Modify: `tester/src/main/java/.../suite/BootSuite.java`
- Modify: `tester/src/main/java/.../suite/ComboSuite.java`
- Modify: `tester/src/main/java/.../suite/FoliaCombatSmoke.java`

**Interfaces (Consumes):** everything from Tasks 1-3; the tester already has `compileOnly(project(":api"))` — `MentalCombat`, `ComboView`, `Capability`, and the new events are importable with no build change. Suites use `TestContext` (`expect/awaitTicks/awaitUntil/sync/note/skip`), `Captors`, the `ComboEventCaptor` pattern (register via `Bukkit.getPluginManager().registerEvents(captor, tester)`, tear down with `HandlerList.unregisterAll(captor)` in `finally`), journal reads via `countShips`/`awaitNewShip`, module toggling via the suite's existing overlay/`setUp` mechanism, and `mental.clock().current().value()` as the tick source (never `Bukkit.getCurrentTick()`).

- [ ] **Step 1: `BootSuite`** — update the gen pin and extend the facade case:

```java
        context.expect(api.apiVersion() == 3, "API generation must be 3 (got " + api.apiVersion() + ")");
        context.expect(api.has(Mental.MentalApi.Capability.COMBO_EVENTS), "COMBO_EVENTS capability missing");
        context.expect(api.has(Mental.MentalApi.Capability.COMBO_CHAIN_EVENTS), "COMBO_CHAIN_EVENTS capability missing");
        context.expect(api.has(Mental.MentalApi.Capability.COMBO_HIT_EVENTS), "COMBO_HIT_EVENTS capability missing");
        context.expect(api.has(Mental.MentalApi.Capability.COMBO_QUERY), "COMBO_QUERY capability missing");
        context.expect(api.has(Mental.MentalApi.Capability.WINDOW_QUERY), "WINDOW_QUERY capability missing");
        context.expect(api.has(Mental.MentalApi.Capability.KNOCKBACK_OUTCOMES), "KNOCKBACK_OUTCOMES capability missing");
        context.expect(!api.has(Mental.MentalApi.Capability.MITIGATION_PREVIEW), "MITIGATION_PREVIEW deferred to 3.1 — must be false");
        context.expect(api.combat() == null, "combat() must be null with combo modules at default OFF");
```

- [ ] **Step 2: `ComboSuite`** — extend `ComboEventCaptor` with `CopyOnWriteArrayList`s for `ComboChainEvent`, `ComboChainAbortEvent`, `ComboHitEvent` (same shape as start/end), then add these cases to the suite list (each with `captors.reset()` + fresh players + `finally` teardown per the suite's conventions; every case that needs the module uses the existing `setUp(...)` staging):

  1. **"combo: gen3 lifecycle Chain→Start→Hit→End"** — stage 4 cadence hits then a retaliation. Assert: exactly one `ComboChainEvent` and its `getHits() == 1` with `getAttackerId()` = attacker UUID and `getGapDeadlineTick() != MentalCombat.NO_TICK`; `ComboStartEvent` `getHits() == 2`, `getAttackerId()` present, `getStartedTick() != NO_TICK`; `ComboHitEvent`s for hits 3 and 4 with strictly increasing `getHits()` and each `getGapDeadlineTick() - getTick()` equal to the effective max-gap (read it live: `mental.snapshot().settings(...)` for `Feature.COMBO_HOLD` → `.rules().maxGapTicks()` — the suite's `setUp` widens it, so compute, don't hardcode); **no `ComboHitEvent` with `getHits() == 2`** (the promotion knock is Start alone); finally `ComboEndEvent` with `Reason.RETALIATION`, `getHits() == 4`, `getAttackerId()` present, `getEndedTick() != NO_TICK`.
  2. **"combo: comboOn observes the pre-hit state (§7)"** — register an `EntityDamageByEntityEvent` MONITOR listener that, when the victim matches, records `Mental.get().combat().comboOn(victimId)` (state + hits) into a list. Stage 3 hits. Assert recorded pre-hit states: hit 1 → `NONE`; hit 2 → `DEVELOPING` with hits 1 (the promotion hit's pre-hit state is DEVELOPING — the §7 fold-eligibility arm); hit 3 → `ACTIVE` with hits 2.
  3. **"combo: query surface answers (§6/§11.4)"** — while a combo is active: `combat() != null`; `comboOn(new UUID(0,0)).state() == NONE` with `NO_TICK` ticks; a `ComboHitEvent` captured in a handler has `getTick() <= combat().currentTick()` (read `currentTick()` INSIDE the handler and store both); `comboOn(victim)` from the driver thread (off-thread read) returns a non-torn view: state `ACTIVE`, `attackerId` = attacker, `gapDeadlineTick() - lastKnockTick()` == effective max-gap.
  4. **"combo: hurtWindowClear pins the §6 expression (§11.3)"** — with the module on: `context.sync` set the victim's `setMaximumNoDamageTicks(20)` then for each cell `(ndt, want)` in `{(0,true),(10,true),(11,false),(20,false)}`: `setNoDamageTicks(ndt)` and `context.expect(combat.hurtWindowClear(victim) == want, ...)` (call inside `sync` — region thread). Cell (11,false) is the divergence cell vs the "+1 staleness" internal read.
  5. **"combo: a foreign velocity cancel advances nothing (D1/§11.6)"** — register a `PlayerVelocityEvent` listener at `EventPriority.HIGHEST` that `setCancelled(true)` when `event.getPlayer()` is the victim. Stage ONE hit. Assert (after `awaitTicks(3)`): zero `ComboChainEvent`s captured, `comboOn(victim).state() == NONE`. Unregister the canceller; stage another hit; assert the chain now opens (recovery — one `ComboChainEvent(hits==1)`).
  6. **"combo: suppress ships zero and outcomes read back (§8)"** — register a `KnockbackApplyEvent` listener at NORMAL calling `event.suppress()` for the victim, and a second at MONITOR recording `getOutcome()`, `getAttackerId()`, `getSource()`. Stage one hit; await the new journal ship; assert the shipped vector is `(0,0,0)` (`expectNear` per axis, epsilon 1e-9), recorded outcome == `SUPPRESSED`, attackerId == attacker UUID, source == `MELEE`. Unregister; stage a second hit with a NORMAL listener doing `setCancelled(true)`: assert NO new ship journal entry lands (count unchanged after `awaitTicks(3)`) and the MONITOR capture reads `YIELDED`. Third hit with a listener doing `setCancelled(true)` then `velocity(new Vector(0.1, 0.2, 0.3))`: assert outcome `SHIP` and the journal ships `(0.1, 0.2, 0.3)` (last-writer-wins).
  7. **"combo: developing aborts surface (§5.2)"** — use a setUp WITHOUT the widened gap (the grounded-run case's staging precedent). (a) one hit, wait past `maxGapTicks` → exactly one `ComboChainAbortEvent`, reason `EXPIRED`, `getHits() == 1`; (b) one hit from attacker A, then (in-window) one hit from attacker B → abort with reason `SWITCHED` + `getAttackerId()` == A, followed by a `ComboChainEvent` for B; (c) one hit, then victim retaliates → abort reason `RETALIATION`. (The switch+expiry SWITCHED-wins coincidence is kernel-pinned — the live sweep expires first by construction; note that in a comment.)
  8. **"combo: module toggle-off fires terminals and defuncts handles (§11.5)"** — hold `MentalCombat held = Mental.get().combat()`. Build an active combo. Toggle combo-hold off through the same mechanism `setUp` uses to toggle it on (overlay write + converge — mirror it). `awaitUntil`: a `ComboEndEvent(DISABLED)` is captured; then assert `held.comboOn(victimId).state() == NONE`, `held.hurtWindowClear(victim) == false` (sync), and `Mental.get().combat() == null`. Then toggle back ON, build a 1-hit developing chain, toggle OFF → `awaitUntil` a `ComboChainAbortEvent(DISABLED)` (the terminal the old code never fired).
  9. **"combo: natively blocked cadence forms a combo (D2/§11.6)"** — gate on the BLOCKS_ATTACKS probe; where absent `context.skip("BLOCKS_ATTACKS tier absent — D2 pinned by the desk re-entry funnel")`. Reuse `BlockingSuite`'s staging: EXTRACT `forceNativeBlock`/`startUsingMainHand`/`blocksAttacksPresent` into a small shared helper class in the tester (e.g. `NativeBlockStaging`) and update `BlockingSuite` to call it (do NOT copy-paste a third clone; `countShips`/`awaitNewShip` may stay duplicated — out of scope). Stage: blocker victim with `forceNativeBlock`, then 3 cadence hits from the attacker; assert `comboOn(blocker).state() == ACTIVE` and a `ComboStartEvent` was captured — the blocked delivery path advances chains through the desk funnel.

- [ ] **Step 3: `FoliaCombatSmoke`** — add one case **"folia: combo events on the region thread + off-thread query (§11.7)"**: enable combo-hold the way ComboSuite's setUp does (overlay + converge — port the minimal helper; keep the smoke's same-region `runAt`/`runOn`/`callOnBlocking` idioms). Register a listener whose `ComboChainEvent`/`ComboStartEvent` handlers record `sched.isOwnedByCurrentRegion(victim.player())` into `AtomicBoolean`s. Drive 2 same-region hits via `runOn(attacker)`. Assert: both events captured, both region-ownership flags true; then from the test driver thread (NOT a region thread) read `Mental.get().combat().comboOn(victimId)` and assert an untorn ACTIVE view (state ACTIVE ⇒ attackerId non-null ⇒ hits ≥ 2 ⇒ both ticks != NO_TICK — assert all four together). Toggle the module back off and `awaitUntil` the `ComboEndEvent(DISABLED)` terminal so the smoke leaves zero-touch state behind. Keep every entity interaction on its owning region thread per the smoke's conventions.

- [ ] **Step 4: run the matrix** — `./gradlew build` first, then `scripts/integration-matrix.sh` (all Paper entries, concurrent local gate), then `./gradlew integrationTestFolia`. Read results HONESTLY: every entry's `run/<v>/plugins/MentalTester/test-results.txt` (and `run/folia/<v>/…`) must read `PASS nonce=<this run's nonce>`; check `run/matrix-verdicts.txt`; a `1.9.4` concurrency flake retries once per the documented rule. Expected: all entries PASS.

- [ ] **Step 5 (orchestrator): commit** — `test(tester): gen-3 acceptance — lifecycle, ordering, D1/D2, window pins, outcome machine, Folia lane` with a prose body listing which §11 criterion each new case pins.

---

### Task 5: Docs + release surface

**Files:**
- Create: `docs/api-gen3-rulings.md`
- Modify: `gradle.properties` (version bump + changelog paragraph)
- Modify: `.github/workflows/release.yml` (attach the api jar)

- [ ] **Step 1: `docs/api-gen3-rulings.md`** — the §10.3 record, written for the next maintainer: D1 ratified (confirmed-ship feed; the feed gate is `!cancelled`, not the valve's quantization check; ledger + desk journal deliberately remain decision-time/HIGH — the valve and the combo feed are the confirmed-ship artifacts); D2 premise refuted with the full re-entrancy trace (`deliverBlockedKnock` → `setVelocity(era)` → `PlayerVelocityEvent` → desk HIGH `ship-formula` → MONITOR feed) and the ruling that blocked knocks qualify; §9 deferral to 3.1; `combat()` nullness = detection-live (`comboKeepers > 0`, either combo-family feature); the active-end SWITCHED question (frozen public enum keeps EXPIRED); the disable-drain ordering contract and its firing-thread exception; the tick-frame naming (Paper = `Bukkit.getCurrentTick()` via `PaperTickClock`, Folia/legacy = the global-region `CounterTickClock` — NOT the server tick); api artifact versioning (publication-level `3.0.0`, project version untouched). Link the spec doc.

- [ ] **Step 2: `gradle.properties`** — set `version=2.8.0-beta` and add the changelog paragraph above it following the exact prose style of the 2.7.1-beta entry (lines ~279-295): the gen-3 api surface (events, queries, capabilities, outcomes), the D1/D2 rulings in one sentence each, the disable-ordering fix, mental-api 3.0.0 publishing, apiVersion()==3.

- [ ] **Step 3: `release.yml`** — in the `release` job, after the existing mega-jar locate step: build the api jar is already done by the release build; add a step that copies `api/build/libs/api-*.jar` to `mental-api-$(grep -m1 -E '^apiVersion=' gradle.properties | sed -E 's/^apiVersion=//').jar` (fail loudly if the glob matches zero or >1 file) and add that file to the `gh release create`/upload asset list beside the mega-jar. Read the surrounding steps (~:280-355) and match their style exactly; do not touch the draft-then-publish flow.

- [ ] **Step 4: run** `./gradlew build` once more (the changelog/version change re-stamps plugin.yml; the nonce comment in gradle.properties warns the gates hold script refs — config cache stays off). Expected: BUILD SUCCESSFUL.

- [ ] **Step 5 (orchestrator): commits** — `docs: record the gen-3 rulings and adjudication` (rulings doc + the spec doc `docs/api-gen3-integration-surface.md` if not yet committed), then `chore(release): 2.8.0-beta — api generation 3` (gradle.properties + release.yml).

---

### Verification gate & ship (orchestrator, per matrix-gate skill)

1. `./gradlew build` — unit tests, japicmp, kernel-Bukkit-free, four mega-jar gates.
2. `scripts/integration-matrix.sh` — all Paper entries concurrent; verify per-entry `PASS nonce=<fresh>`.
3. `./gradlew integrationTestFolia` — the Folia lane with the new smoke case.
4. Push `release/2.8.0-beta`, open the PR (merge-commit repo style), wait for `ci-ok`.
5. Merge → `release.yml` on main: full paper matrix + Folia gate → tags `v2.8.0-beta` at the merge commit → GitHub pre-release with `Mental-2.8.0-beta.jar` + `mental-api-3.0.0.jar`.
6. Post-ship: `./gradlew :api:publishToMavenLocal` remains the documented consumer path for SE.

## Self-Review notes (spec coverage)

- §3 distribution → Task 2 step 8 (+ release asset, Task 5). §4 → Task 2 step 1, Task 3 steps 5/10/11. §5.1-5.5 → Tasks 1+2+3. §5.6 → Task 1 tests + Task 4 case 1/8. §5.7 → Task 2 javadoc + Task 4 case 3. §6 → Tasks 1 (view) / 2 (interfaces) / 3 (book+service) / 4 (cases 2-4). §7 → package-info + Task 4 case 2. §8 → Task 2 step 6 + Task 3 steps 3/7 + Task 4 case 6. §9 → deferred (capability false, BootSuite pins it). §10 → package-info + rulings doc. §11.1 → Task 1 tests; §11.2 → Task 4 case 1; §11.3 → WindowJudgeTest + case 4; §11.4 → case 3; §11.5 → case 8 + disable-drain (construction); §11.6 → cases 5/9; §11.7 → FoliaCombatSmoke case.
- Known divergences from spec text, all deliberate and documented: D2 handled via the funnel (no blocked-path feed); DeskRouter:105-107 comment expanded rather than "corrected"; `combat()` nullness on detection-live; plugin-disable half of §11.5 enforced by construction.

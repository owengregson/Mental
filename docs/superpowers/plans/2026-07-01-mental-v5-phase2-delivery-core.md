# Mental v5 Phase 2 — Delivery Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the hit-transaction delivery core — the machinery that replaces
`KnockbackPipeline`/`PendingStore`/`AppliedTagStore`/`VelocityDuplicateSuppressor`/
`MeleeReentryGuard`/`ServerTickClock`/`SprintTracker`/`GroundTransitionWatcher` — as
kernel decision logic plus thin core shells, unit-proven before any live wiring.

**Architecture:** Everything that decides lives in `kernel` (Bukkit-free, tested by
a deterministic interleaving harness); core contributes only Bukkit adapters
(TickClock impls, the valve packet listener, session scheduling shells) that are NOT
yet wired into `MentalPlugin` (Phase 4 does the wiring). Old core stays green and
untouched.

**Tech Stack:** as Phase 1. New code in `kernel/src/{main,test}/java/me/vexmc/mental/kernel/`
and `core/src/{main,test}/java/me/vexmc/mental/v5/` (the `v5` package keeps new core
shells cleanly separated until the Phase 4+6 cutover renames it away).

## Global Constraints

- Kernel stays dependency-free (the Task 1.0 guard enforces it).
- No wall-clock (`System.nanoTime`/`currentTimeMillis`) anywhere in this phase's
  code. Time is `TickStamp` only. (`LatencyModel` from Phase 1 keeps its RTT nanos —
  that measures network time, not correctness windows.)
- No modification to ANY existing file outside `kernel/` and `core/src/*/java/me/vexmc/mental/v5/`,
  except reading. Old tests must stay green: gate is `./gradlew build`.
- Every §4.3/§4.5 mandate semantic named below is pinned by a test in this phase.
- Conventional commits with prose bodies + the `Co-Authored-By: Claude Fable 5
  <noreply@anthropic.com>` trailer, one commit per task.
- Where a task says "port pins from `<TestClass>`", the expected VALUES are sacred;
  the calling shape may adapt to the new API. If a value cannot survive, stop and
  report (do not adjust it).

---

### Task 2.0: Kernel time & identity primitives

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/model/TickStamp.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/model/HitId.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/model/HitSource.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/model/SprintVerdict.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/port/TickClock.java`
- Test: `kernel/src/test/java/me/vexmc/mental/kernel/model/TickStampTest.java`

**Interfaces (produced):**

```java
/** A server tick index that can express "unknown" without a magic int. */
public record TickStamp(int value) {
    public static final TickStamp NO_TICK = new TickStamp(Integer.MIN_VALUE);
    public boolean known() { return value != Integer.MIN_VALUE; }
    /** True when both stamps are known and {@code this} is within {@code ticks} of {@code now} (inclusive). */
    public boolean recentAt(TickStamp now, int ticks) {
        return known() && now.known() && now.value - value >= 0 && now.value - value <= ticks;
    }
}

public record HitId(long value) {}

/** Typed provenance minted at a hit's origin — B6's answer. */
public sealed interface HitSource {
    record Melee() implements HitSource {}
    /** The synthetic victim.damage(rodder) a fishing pull provokes. */
    record RodPull() implements HitSource {}
    record Arrow(int punchLevel) implements HitSource {}
    record Thrown(String projectileType) implements HitSource {}
    record Bobber() implements HitSource {}
    /** A hit Mental did not originate (fast path off, another plugin, environment). */
    record Vanilla(String damageCause) implements HitSource {}
}

/** The attack-time sprint answer, stamped at registration, consumed by the authoritative pass. */
public record SprintVerdict(boolean sprinting, Boolean fresh, TickStamp at) {}

public interface TickClock { TickStamp current(); }
```

- [ ] Write `TickStampTest`: NO_TICK is not `known()`; `recentAt` false when either
  side unknown; true at distance 0 and 4, false at 5 and for future stamps
  (now < this). Run (fails: classes missing) → implement → run PASS → commit
  `feat(kernel): add tick/identity primitives for the delivery core`.

### Task 2.1: MotionLedger — the single-writer residual fold

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/ledger/MotionLedger.java`
- Test: `kernel/src/test/java/me/vexmc/mental/kernel/ledger/MotionLedgerTest.java`

**Interfaces (produced):**

```java
/**
 * One victim's legacy motion fields. Single-writer: only the owning session thread
 * calls any method; there are deliberately NO concurrency primitives here — thread
 * safety is ownership (spec §2), and the interleaving harness proves the protocol.
 */
public final class MotionLedger {
    public MotionLedger(double gravity) {}
    /** The knock record: FINAL delivered value, block-under-feet slip at launch. */
    public void record(double vx, double vy, double vz, boolean grounded, double slip, TickStamp tick) {}
    public void recordLiftoff(double jumpVy, double facingPushX, double facingPushZ, TickStamp tick) {}
    public void recordLanding(TickStamp tick) {}
    /** Attacker-side ×0.6 on bonus-KB hits (mandate §4.3). */
    public void scaleHorizontal(double factor) {}
    /** Advance one tick of legacy decay (called once per session tick). */
    public void tick(TickStamp now) {}
    /** The residual as of the last completed tick — what PlayerView publishes. */
    public Decay.Motion current() { ... }
    public boolean groundedView() { ... }
}
```

**Semantics (each is a test):**
1. Ported pins: mine `core/src/test/java/me/vexmc/mental/module/knockback/VictimMotionTest.java`
   for every ledger-behavior case (record→decay sequences, ice hit-2 = 0.4821,
   grounded-equilibrium reset, the two grounded launch decays, dead-after-60-ticks,
   rest threshold) and port the VALUES onto this API. The `previous`-sample /
   `currentExcludingTick` machinery does NOT port — exclusion is now the publication
   boundary (Task 2.5), so `current()` here is simply "after the last `tick()`".
2. A motion reset (landing) clears horizontal only and seeds vertical to
   `Decay.groundedEquilibrium(gravity)` — never zero (mandate §4.2).
3. Liftoff overwrites vy with the jump stamp and adds the facing push horizontals.
4. `record` replaces all three axes (legacy halve-then-add already happened in the
   engine); `grounded` + `slip` select the decay branch on subsequent `tick()`s
   (launch tick decays at GROUND drag — the #1 trap, keep the pinned behavior).
5. 60 ticks (`DEAD_AFTER_TICKS` — note the old constant is 200 in code but the
   mandate §4.3 says dead after 60: **read the old `VictimMotion` source; whichever
   value its tests pin is the truth to carry** — report which in the commit body).

- [ ] Port/write tests → fail → implement (delegate the decay math to `Decay`) →
  PASS → commit `feat(kernel): single-writer motion ledger over the decay authority`.

### Task 2.2: GroundFsm + SprintWire — the connection-domain observers

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/wire/GroundFsm.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/wire/SprintWire.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/model/LedgerEvent.java`
- Test: `kernel/src/test/java/me/vexmc/mental/kernel/wire/{GroundFsmTest,SprintWireTest}.java`

**Interfaces (produced):**

```java
/** Emitted by the connection thread into the victim's session inbox. */
public sealed interface LedgerEvent {
    record Liftoff(double jumpVy, double pushX, double pushZ, TickStamp tick) implements LedgerEvent {}
    record Landing(TickStamp tick) implements LedgerEvent {}
    record Reset(TickStamp tick) implements LedgerEvent {}   // teleport ack seam
}

/**
 * The client-movement FSM, owned by one connection thread. Pure state machine:
 * inputs are packet-shaped values plus the victim's published view (for jump boost,
 * sprint, yaw); outputs are LedgerEvents.
 */
public final class GroundFsm {
    public GroundFsm(TickClock clock) {}
    /** @return the event to enqueue, or null. */
    public LedgerEvent onMovement(boolean onGround, boolean hasPosition, double y,
                                  ViewSlice view) { ... }
    public LedgerEvent onTeleport() { ... }
    /** The slice of PlayerView the FSM needs (record defined alongside). */
    public record ViewSlice(double jumpImpulse, int jumpBoostAmplifier,
                            boolean sprinting, float yawDegrees, double gravity) {}
}

/** Arrival-order sprint truth for ONE attacker, owned by their connection thread. */
public final class SprintWire {
    public SprintWire(TickClock clock) {}
    public void onSprintStart() {}   // arms freshness, stamps tick
    public void onSprintStop() {}
    /** Mirror of vanilla's in-attack clear, applied when the desk reports an accepted bonus hit. */
    public void onServerClear() {}
    /** Re-seed from the published server flag after >= quietTicks of wire silence. */
    public void reconcile(boolean serverSprinting, TickStamp now, int quietTicks) {}
    /** The registration-time verdict (never null; falls back to the seeded state). */
    public SprintVerdict verdictAt(TickStamp now) { ... }
}
```

**Semantics (each a test; port every portable pin from
`core/src/test/.../GroundTransitionWatcherTest*` and `SprintTrackerTest*` if they
exist — `grep -rl "GroundTransitionWatcher\|SprintTracker" core/src/test` first):**
1. Liftoff = grounded→airborne with rising y ⇒ `Liftoff(0.42 + 0.1×(amp+1 if boost>0
   else 0) + sprint facing push 0.2 along yaw, tick)`. The trig: `pushX = −sin(yaw°
   × π/180) × 0.2`, `pushZ = cos(yaw° × π/180) × 0.2` (verify against the old
   watcher source and pin one non-axis yaw, e.g. 45°).
2. Airborne→grounded ⇒ `Landing`. Movement packets without position change
   (rotation-only) never transition.
3. `onTeleport` ⇒ `Reset` and the FSM forgets its last state (no phantom jump
   across the ack seam).
4. SprintWire: START then `verdictAt` ⇒ sprinting=true, fresh=TRUE; STOP ⇒ false;
   START→ATTACK-same-arrival-order preserved (sub-tick — no tick gating on reads);
   `onServerClear` clears sprinting but a LATER wire START re-arms (stamp
   comparison by arrival sequence, monotonic long, not wall clock).
5. `reconcile(true, now, 3)` adopts server sprint ONLY when the last wire write is
   ≥3 ticks old or absent; a fresh wire STOP is never overwritten by a stale-high
   server flag.

- [ ] Tests → fail → implement → PASS → commit
  `feat(kernel): connection-domain ground and sprint observers`.

### Task 2.3: HitContext + HitTransaction state machine

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/model/HitContext.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/delivery/HitTransaction.java`
- Test: `kernel/src/test/java/me/vexmc/mental/kernel/delivery/HitTransactionTest.java`

**Interfaces (produced):**

```java
/** Compute-once decision inputs (R5). Phase 2 carries the delivery-relevant subset;
 *  Phase 3 extends it with arbiter verdicts. */
public record HitContext(HitId id, HitSource source,
                         java.util.UUID attackerId, java.util.UUID victimId,
                         SprintVerdict sprint, boolean ocmOwnsMeleeKnockback,
                         boolean victimHasWire, Double compensationY,
                         TickStamp registeredAt) {}

public final class HitTransaction {
    public enum State { REGISTERED, PLANNED, PRE_SENT, PINNED,
                        ADOPTED, SUPPRESSED, RETRACTED, DROPPED, ENSURED, RECORDED }
    public HitTransaction(HitContext context) {}          // state REGISTERED
    public HitContext context() { ... }
    public State state() { ... }
    public boolean terminal() { ... }                      // RECORDED, or resolved-without-velocity states after journaling
    public void planned() {}                               // REGISTERED→PLANNED
    public void preSent(KnockbackVector wireVector) {}     // PLANNED→PRE_SENT
    public void pinned(KnockbackVector eraVector) {}       // PLANNED→PINNED
    public KnockbackVector carried() { ... }               // the PRE_SENT/PINNED vector, else null
    public void adopted() {}                               // PRE_SENT|PINNED|REGISTERED|PLANNED→ADOPTED
    public void suppressed(String reason) {}
    public void retracted() {}                             // scheduler retire path
    public void dropped(String reason) {}                  // sweep / no-velocity-event
    public void ensured() {}
    public void recorded() {}                              // any resolved state→RECORDED
}
```

**Semantics (tests):** every legal transition succeeds once; every illegal one
(e.g. `preSent` after `PINNED`, `recorded` from `REGISTERED`, any transition out of
`RECORDED`, double `adopted`) throws `IllegalStateException` naming both states.
`PINNED` can never reach a state that arms a valve (asserted by the desk in 2.4, but
pin here that `carried()` on PINNED is flagged `wireCarried() == false`).

- [ ] Tests → fail → implement → PASS → commit
  `feat(kernel): the hit transaction state machine`.

### Task 2.4: DeliveryDesk decision core + journal + valve payloads

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/delivery/DeliveryDesk.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/delivery/Directive.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/delivery/ValvePayload.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/model/JournalEntry.java`
- Test: `kernel/src/test/java/me/vexmc/mental/kernel/delivery/DeliveryDeskTest.java`

**Interfaces (produced):**

```java
/** Exact wire quantization of a velocity packet (motion × 8000 as shorts, ±3.9 clamp). */
public record ValvePayload(int entityId, short qx, short qy, short qz) {
    public static ValvePayload of(int entityId, KnockbackVector v) {
        return new ValvePayload(entityId, q(v.x()), q(v.y()), q(v.z()));
    }
    private static short q(double axis) {
        double clamped = Math.max(-3.9, Math.min(3.9, axis));
        return (short) (clamped * 8000);
    }
}

public record JournalEntry(HitId id, HitSource source, KnockbackVector shipped,
                           boolean wireCarried, String suppressReason, TickStamp at) {}

/** What the core shell must do after a velocity-event resolution. */
public record Directive(Action action, KnockbackVector ship, ValvePayload arm) {
    public enum Action { SHIP, SHIP_AND_ARM_VALVE, CANCEL_EVENT, PASS_THROUGH }
}

/**
 * One victim's delivery decisions. Single-writer (the victim's session thread) for
 * all methods except submitFromWire, which the registering netty thread calls —
 * the ONLY cross-thread entry, backed by an atomic slot the owner drains.
 */
public final class DeliveryDesk {
    public DeliveryDesk(int victimEntityId, TickClock clock, int journalCapacity) {}
    /** Netty entry: a PRE_SENT/PINNED transaction arriving ahead of its damage task. */
    public void submitFromWire(HitTransaction tx) {}
    /** Owning-thread entry: a feature submits/replaces the vector for a transaction. */
    public void submit(HitTransaction tx, KnockbackVector vector) {}
    /** Withdrawal is by exact id — there is deliberately NO withdraw-all (B4). */
    public void withdraw(HitId id) {}
    /** The damage pass marks which transaction the imminent velocity event resolves. */
    public void awaitVelocityEvent(HitTransaction tx) {}
    /** Non-consuming view for the EntityKnockbackEvent mirror. */
    public KnockbackVector mirrorView() { ... }
    /** The formula vector KnockbackApplyEvent exposes, or null if nothing pending. */
    public KnockbackVector pendingFormula() { ... }
    /** Resolve at PlayerVelocityEvent time with the post-listener api velocity. */
    public Directive resolve(double apiX, double apiY, double apiZ) { ... }
    /** Session-tick sweep: any awaiting transaction from an earlier tick is DROPPED. */
    public void sweep(TickStamp now) {}
    /** Ensure step for no-velocity-event sources: unresolved => Directive to setVelocity. */
    public Directive ensure(HitId id) { ... }
    public java.util.List<JournalEntry> journal() { ... }
}
```

**Resolution algorithm (normative — implement exactly):**
1. `resolve` with no awaited transaction ⇒ `PASS_THROUGH` (foreign velocity; never
   journaled, never ledger-recorded).
2. Awaited tx with a null submitted vector (legacy resistance roll) ⇒
   `CANCEL_EVENT`, tx `suppressed("resistance-roll")`, journal (shipped = null,
   wireCarried = false).
3. Awaited `PRE_SENT` tx where `(apiX,apiY,apiZ)` equals the *formula* vector
   (exact double equality — the API contract: unmodified by listeners) ⇒
   `SHIP_AND_ARM_VALVE` with `ship = carried wire vector`,
   `arm = ValvePayload.of(entityId, carried)`; tx `adopted()` then `recorded()`.
4. Awaited `PRE_SENT` tx where the api velocity differs (a third party modified the
   event) ⇒ `SHIP` the modified value as a correction, NO valve; journal
   `wireCarried=false` with the modified value (the ledger must record what the
   client will actually have — mandate §4.3 "FINAL delivered value").
5. Awaited `PINNED` tx ⇒ `SHIP` (never a valve, by type — B4).
6. Last-submitter-wins: two `submit`s for the same victim in one tick ⇒ the later
   replaces the earlier; the earlier tx is `suppressed("superseded")` and journaled.
7. `withdraw(idA)` never disturbs a concurrent `idB` (B4 pin).
8. `sweep(now)`: an awaiting tx with `registeredAt` tick < now ⇒
   `dropped("no-velocity-event")`, journaled.
9. `submitFromWire` then `sweep` on a later tick without any damage pass ⇒ dropped
   and journaled (an unresolved pre-send can never outlive its hit — B4).
10. Journal ring: capacity N (constructor), oldest evicted; entries immutable.
11. `ensure(id)`: transaction still unresolved ⇒ Directive `SHIP` with the submitted
    vector and tx `ensured()` then `recorded()` (journaled `wireCarried=false`);
    already resolved ⇒ `PASS_THROUGH` (idempotent). This is the causal replacement
    for `ensureDelivery`/`promoteNewestOfCause` — keyed by id, no cause scan.

**Canonical end-to-end pins (the acceptance tests of this phase):** using the real
`KnockbackEngine` + `Presets.LEGACY_17` + `EntityState` fixtures, drive
register→submit→awaitVelocityEvent→resolve and assert the journal ships exactly:
standing `(≈0.4 h, 0.3608 v)`; sprint `(0.9, 0.4607)`; KB II sword `(1.4, 0.4607)`;
sprint+KB II `(1.9, 0.4607)` — the same constants
`KnockbackEngineTest` pins, now proven through the desk path. (Build the
`EntityState` fixtures exactly as `KnockbackEngineTest` does — copy its helpers.)

- [ ] Tests (every numbered semantic + the four canonical pins) → fail → implement
  → PASS → commit `feat(kernel): the delivery desk — single-owner resolution with journal and valve payloads`.

### Task 2.5: PlayerView publication + CompensationQuery

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/model/PlayerView.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/model/KinematicState.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/wire/CompensationQuery.java`
- Test: `kernel/src/test/java/me/vexmc/mental/kernel/wire/CompensationQueryTest.java`,
  `kernel/src/test/java/me/vexmc/mental/kernel/model/PlayerViewTest.java`

**Interfaces (produced):**

```java
/** One player's frozen per-tick snapshot — the ONLY thing netty code may read.
 *  Published by the session at tick start, i.e. state as of the END of the previous
 *  tick: the mandate §4.3 boundary read holds by construction. */
public record PlayerView(java.util.UUID id, int entityId, TickStamp at,
                         Decay.Motion motion, boolean grounded, double slipperiness,
                         double gravity, double jumpImpulse, int jumpBoostAmplifier,
                         boolean sprinting, boolean creative, boolean pvpAllowed,
                         int noDamageTicks, int maxNoDamageTicks,
                         double knockbackResistance, boolean ocmOwnsMeleeKnockback,
                         KnockbackProfile profile, int pingMillis,
                         KinematicState kinematics) {
    /** Mandate §4.3: +1 staleness allowance — boundary hits at max/2 + 1 are admitted. */
    public boolean damageImmune() { return noDamageTicks > maxNoDamageTicks / 2 + 1; }
    /** Stale views must degrade to no-exclusion / fast-path decline (≤4 ticks). */
    public boolean fresh(TickStamp now) { return at.recentAt(now, 4); }
}

public record KinematicState(double y, double distanceToGround, boolean clientOnGround) {}

/** B11's answer: a stateless per-hit vertical query. */
public final class CompensationQuery {
    /** @return the y override for this hit, or null when no correction applies. */
    public static Double verticalFor(PlayerView victim, int rttMillis, double baseVy) { ... }
}
```

**Semantics:**
- `damageImmune` pin: max=20, ndt=11 ⇒ immune false at the +1 boundary
  (`11 > 20/2+1` is false); ndt=12 ⇒ true. (Carries the existing
  `PlayerStateCache.Snapshot.isDamageImmune` behavior — verify against its source
  and port any existing test pin.)
- `CompensationQuery`: victim already-landed prediction ⇒
  `Decay.groundedEquilibrium(gravity)` (never zero); airborne with off-ground sync ⇒
  `MotionMath.simulateVerticalVelocity(...)` over `rtt/50` ticks (continuous signed
  simulation — no apex re-seed); on-ground victim with no prediction ⇒ null. Write
  pins with hand-computed values from the Phase 1 `MotionMath` (its move-then-decay
  order — see the Phase 1 outcomes note in the master plan). Port the double-hit
  guard: no correction when `noDamageTicks > 8`.

- [ ] Tests → fail → implement → PASS → commit
  `feat(kernel): the per-tick player view and stateless compensation query`.

### Task 2.6: The interleaving harness + scripted scenarios

**Files:**
- Create: `kernel/src/test/java/me/vexmc/mental/kernel/harness/SimulatedThreads.java`
- Create: `kernel/src/test/java/me/vexmc/mental/kernel/harness/DeliveryScenariosTest.java`

**Design:** `SimulatedThreads` runs named virtual "threads" (connection-A,
connection-V, session-V, session-A) as plain sequential executors the TEST
interleaves explicitly — determinism comes from the script, not from real threads.
It owns: a settable `TickClock`, per-player inboxes
(`ArrayDeque<LedgerEvent>`), `SprintWire`/`GroundFsm` per connection,
`MotionLedger`/`DeliveryDesk`/view publication per session, and helper steps
`sessionTick(player)` (drain inbox → ledger.tick → publish view → desk.sweep).

**Scenarios (each an @Test, each asserting via the JOURNAL only — B7):**
1. **Boundary combo decline:** grounded victim; hit 1 ships standing 0.3608; victim
   jumps (GroundFsm liftoff w/ jump stamp) mid-flight; hit 2 registered the tick the
   victim touches down but BEFORE session-V's tick — the view still shows flight ⇒
   hit 2's vertical is the declining ~0.25-band value, never a grounded re-stamp
   (compute the exact pin from `Decay` given the jump stamp and tick count — show
   arithmetic in a comment).
2. **Melee superseded by rod, same tick:** melee tx submits; rod tx submits after
   (last wins); resolve ships the rod vector; journal shows melee
   `suppressed("superseded")` + rod shipped. Withdraw-by-id of the rod mid-way
   leaves the melee pending untouched (B4).
3. **Retired mid-flight:** wire-submitted PRE_SENT tx; victim session never runs a
   damage pass; retire path calls `retracted()`; the sweep journals it; no valve
   ever armed.
4. **Stale view declines exclusion:** stop ticking session-V for 5 ticks;
   `view.fresh(now)` false ⇒ the harness's registration step refuses the fast-path
   context (asserts the caller-visible refusal), matching the NO_TICK degradation.
5. **Third-party modification:** resolve with a different api vector ⇒ SHIP without
   valve; journal carries the modified value.
6. **Pinned victim:** PINNED tx resolves SHIP, no valve, journal `wireCarried=false`.

- [ ] Scenarios → fail → implement harness → PASS → commit
  `test(kernel): deterministic interleaving harness for the delivery core`.

### Task 2.7: Core shells (unwired) — TickClock impls, valve listener, session scaffold

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/PaperTickClock.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/CounterTickClock.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/VelocityValve.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/Vectors.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/CombatSession.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/{CounterTickClockTest,VelocityValveTest,VectorsTest}.java`

**Contracts:**
- `PaperTickClock implements TickClock` — wraps an `IntSupplier` (production:
  `Bukkit::getCurrentTick`; tests: a counter). `CounterTickClock` — the Folia shape:
  starts at `TickStamp.NO_TICK`, `advance()` increments (a global-region task will
  drive it in Phase 4); readable from any thread (AtomicInteger inside).
- `VelocityValve` — a PacketEvents-independent core class holding one
  `AtomicReference<ValvePayload>` per victim (`ConcurrentHashMap<UUID, AtomicReference<ValvePayload>>`):
  `arm(UUID, ValvePayload)`, `boolean consume(UUID, int entityId, short qx, short qy, short qz)`
  (exact match ⇒ clear-and-true, else false), `clearStale(UUID)` (session-tick
  sweep). Unit-test arm/consume/mismatch/second-consume/clearStale. The PacketEvents
  listener that feeds `consume` from ENTITY_VELOCITY sends is Phase 4 (it needs the
  rim); keeping the valve PE-free makes it unit-testable now.
- `Vectors.toBukkit(KnockbackVector)` / `Vectors.fromBukkit(Vector)` — the deleted
  Phase 1 seam, with a round-trip test.
- `CombatSession` — the D2 scaffold: owns `MotionLedger`, `DeliveryDesk`, the inbox
  (`ConcurrentLinkedQueue<LedgerEvent>`), the published
  `AtomicReference<PlayerView>`; method `tickStep(PlayerView freshlyBuilt)` = drain
  inbox → ledger.tick → publish → desk.sweep. NO Bukkit scheduling in this phase
  (Phase 4 wires `repeatOn`); constructor takes plain values. Unit-test the drain
  order (events applied before decay, publish after — assert via a scripted
  sequence that reproduces interleaving scenario 1's view timing).

- [ ] Tests → fail → implement → PASS → commit
  `feat(core): unwired v5 shells — tick clocks, velocity valve, session scaffold`.

### Task 2.8: Phase gate (orchestrator)

- [ ] `./gradlew build` green (old modules untouched — verify with
  `git diff --stat <phase-start>..HEAD -- . ':!kernel' ':!core/src/main/java/me/vexmc/mental/v5' ':!core/src/test/java/me/vexmc/mental/v5' ':!docs'`
  showing nothing).
- [ ] Canonical pins reproduce through the desk (Task 2.4 acceptance tests green).
- [ ] Push; record outcomes in this file.

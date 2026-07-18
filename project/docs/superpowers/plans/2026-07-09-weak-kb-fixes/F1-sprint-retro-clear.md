# F1 — sprint retro-clear: arrival-sequence ordering for the post-hit wire clear

## 1. Problem

`SprintWire.onServerClear(TickStamp asOf)` survives a re-engage only when `State.lastWrite` is STRICTLY newer than the hit's verdict stamp (`kernel/src/main/java/me/vexmc/mental/kernel/wire/SprintWire.java:103`). `TickStamp` is one server tick of granularity (`kernel/.../model/TickStamp.java:10`), so a w-tap START_SPRINTING that arrives in the SAME tick as (and after) the ATTACK has `lastWrite == asOf` and is retroactively destroyed by the deferred EDBEE clear 1–2 ticks later (`core/.../feature/knockback/KnockbackUnit.java:266/344 → :433`); the deferred `setSprinting(false)` (`KnockbackUnit.java:442-446`) then consults the poisoned wire and clears the server flag too, so the reconcile (`PacketTap.java:85`) reads Mental's own false on both sides and cannot restore. The next hit ships plain 0.4-style knockback instead of the ~0.9 sprint stamp — the confirmed `same-tick-wtap-start-retro-clear` finding.

## 2. Design decision

Replace tick-granularity ordering with a **per-wire monotonic arrival sequence** carried inside the CAS'd `State`. The wire already observes true arrival order (all its writers funnel through one `AtomicReference` CAS), so a `long seq` bumped by every wire write is exactly the era queue's program order. The verdict peek captures the seq; the clear no-ops iff a write with a larger seq has landed since the peek. This is the minimal, parity-preserving translation of the existing "strictly-newer lastWrite wins" rule from tick granularity to arrival granularity — same guard shape, same no-op semantics, just an ordering currency that cannot collide within a tick.

Rejected alternatives: (a) sub-tick timestamps (`System.nanoTime`) — violates the wire's "no wall clock" contract and is not arrival order under clock skew; (b) tracking only "arming" writes (a `lastArmSeq`) — changes the existing pinned semantics (today ANY strictly-newer write, including a STOP, defeats the clear — pinned by `guardedServerClearNoOpsUnderANewerWireWrite`); the global seq preserves that class exactly. `lastWrite` is NOT removed: it remains the reconcile quiet-window clock (and the clear keeps refreshing it — that refresh is what stops the reconcile re-adopting the not-yet-cleared server flag in the 3-tick window; load-bearing, see §4).

No changes to `PacketTap`, `HitRegistrationUnit`, `HitContext`, or `WtapRegistrationUnit` — the seq rides existing seams.

## 3. Exact changes

### 3.1 `kernel/src/main/java/me/vexmc/mental/kernel/model/SprintVerdict.java`

Add a `long wireSeq` component with a compatible 3-arg constructor (kernel is additive-only; the explicit old-shape constructor keeps japicmp green and every existing 3-arg call site source- and binary-compatible):

```java
public record SprintVerdict(boolean sprinting, Boolean fresh, TickStamp at, long wireSeq) {

    /** Sentinel: the verdict was not peeked from a SprintWire (published-view fallback, non-melee mints). */
    public static final long NO_WIRE_SEQ = -1L;

    /** Compatibility shape for verdicts with no wire provenance — byte-identical to the pre-seq record. */
    public SprintVerdict(boolean sprinting, Boolean fresh, TickStamp at) {
        this(sprinting, fresh, at, NO_WIRE_SEQ);
    }

    /** Whether this verdict was peeked from a live SprintWire (its {@link #wireSeq} orders the post-hit clear). */
    public boolean fromWire() {
        return wireSeq != NO_WIRE_SEQ;
    }
}
```

Update the record javadoc: `wireSeq` is the wire's arrival sequence at peek time — the clear-ordering currency; `NO_WIRE_SEQ` when the verdict came from the published view. `long` is a Java-8 type (D-8 safe); note for the implementer: this is a record component change, so `equals/hashCode/toString` widen — no test or journal serializes `SprintVerdict` (verified: the journal has no sprint field; all constructors in main+test use the 3-arg shape and keep compiling via the compat constructor).

### 3.2 `kernel/src/main/java/me/vexmc/mental/kernel/wire/SprintWire.java`

**State record** gains `long seq` (last component):

```java
private record State(boolean seen, boolean sprinting, boolean armed,
                     boolean clientSprinting, boolean blockReset, TickStamp lastWrite, long seq) {}

private static final State INITIAL =
        new State(false, false, false, false, false, TickStamp.NO_TICK, 0L);
```

**Seq bump rule** (document in the State javadoc): `seq` counts WIRE WRITES in arrival order — client START/STOP, the block-hit re-arm, and a reconcile seed/adopt. `onBlockReleased` and `onServerClear` do NOT bump: a release only drops the sticky `blockReset` (deliberately NOT a sprint write, matching its existing lastWrite exemption — if it bumped, a clear racing a release would no-op and leave the hit's spent freshness armed), and the clear is the consumer of the ordering, never a producer.

Per-method edits (each existing `new State(...)` gains the seq argument):
- `onSprintStart`: `new State(true, true, true, true, s.blockReset(), now, s.seq() + 1)`
- `onSprintStop`: `new State(true, false, s.armed(), false, s.blockReset(), now, s.seq() + 1)`
- `onBlockSprintReset`: `new State(true, true, true, true, true, now, s.seq() + 1)`
- `reconcile` — both writing branches bump (parity with today, where both set `lastWrite=now` and so already defeat a later stamp-guarded clear): seed branch `new State(true, serverSprinting, false, s.clientSprinting(), s.blockReset(), now, s.seq() + 1)`; adopt branch `new State(true, serverSprinting, s.armed(), s.clientSprinting(), s.blockReset(), now, s.seq() + 1)`; hold branch returns `s` unchanged.
- `onBlockReleased`: carry `s.lastWrite(), s.seq()` unchanged.
- deprecated no-arg `onServerClear()`: carry `s.seq()` unchanged (behavioral contract frozen).
- `onServerClear(TickStamp)`: carry `s.seq()` unchanged in the clear branch. KEEP the method (not deprecated): it now serves ONLY verdicts without wire provenance (`wtapConsultWire=false` fallback and non-melee mints) — update its javadoc to say so and to point at the seq form as the authoritative guard for wire-peeked verdicts.

**New method** — the arrival-order clear (the exact CAS loop):

```java
/**
 * Sequence-guarded mirror of vanilla's in-attack sprint clear. {@code asOfSeq}
 * is the wire sequence the hit's verdict peeked ({@link SprintVerdict#wireSeq()});
 * the clear applies only when NO wire write has arrived since that peek —
 * arrival order, not tick granularity, so a w-tap START landing in the SAME
 * tick as (and after) the ATTACK survives the clear that belongs to that
 * ATTACK (the 2.4.x same-tick retro-clear defect; vanilla's synchronous
 * in-attack clear could never eat a later-arriving START). blockReset and the
 * raw clientSprinting flag survive as ever; an applied clear still refreshes
 * {@code lastWrite} so the reconcile's quiet window cannot re-adopt the
 * not-yet-cleared server flag, but it never bumps {@code seq} — the clear is
 * a consumer of the arrival order, never a producer.
 */
public void onServerClear(long asOfSeq) {
    TickStamp now = clock.current();
    state.updateAndGet(s -> {
        if (s.seq() > asOfSeq) {
            return s; // a wire write arrived after the verdict peek — never retro-clear it
        }
        return new State(true, false, false, s.clientSprinting(), s.blockReset(), now, s.seq());
    });
}
```

**`verdictAt`**: stamp the seq — `return new SprintVerdict(sprinting, fresh, now, s.seq());`

**Class javadoc**: amend the ordering paragraph — the newer-wire-write-wins rule is now sequenced by `seq` (arrival order) for wire-peeked verdicts; `lastWrite` remains the reconcile quiet clock. DELETE the "(A same-tick START-after-ATTACK is an accepted residual — physically implausible input.)" sentence from the `onServerClear(TickStamp)` javadoc — that assumption is the refuted premise.

### 3.3 `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java`

Add `import me.vexmc.mental.kernel.wire.SprintWire;` (referenced unqualified in the :400 javadoc already, but not imported — the new local needs it; conventions: imports always).

Change `applyAttackerObligations` to take the whole verdict. Three call sites:
- line 266: `applyAttackerObligations(attacker, sprinting, tx.context().sprint());`
- line 344: same
- line 495 (`deliverBlockedKnock`): `applyAttackerObligations(attacker, sprinting, original.context().sprint());`

Method (signature + the two guarded clears; the combo-retaliation, bonus gate, and ledger ×0.6 blocks are untouched):

```java
private void applyAttackerObligations(LivingEntity attacker, boolean sprinting, SprintVerdict verdict) {
    ...
    if (domains.has(attackerId)) {
        SprintWire wire = domains.domainFor(attackerId).sprint();
        if (verdict.fromWire()) {
            wire.onServerClear(verdict.wireSeq());
        } else {
            // No wire provenance (wtap-registration off / view-fallback verdict):
            // the stamp guard is the best available ordering, byte-identical to 2.4.1.
            wire.onServerClear(verdict.at());
        }
    }
    if (player.isSprinting()) {
        scheduling.runOn(player, () -> {
            // Skip the server-flag clear only for a re-engage NEWER than this hit's
            // verdict peek (arrival order) — vanilla's synchronous clear could never
            // eat it. With no wire provenance, keep the plain live-wire read (2.4.1).
            if (domains.has(attackerId)) {
                SprintVerdict live = domains.domainFor(attackerId).sprint().verdictAt(clock.current());
                if (live.sprinting()
                        && (!verdict.fromWire() || live.wireSeq() > verdict.wireSeq())) {
                    return;
                }
            }
            player.setSprinting(false);
        }, () -> {});
    }
}
```

Update the method javadoc (:390-403): replace the stamp-guard description with the sequence guard; keep the vanilla-obligation framing.

Note on the lambda: `verdict` is an effectively-final immutable record — safe to capture across the `runOn` hop.

### 3.4 No other main-tree changes

`HitRegistrationUnit.sprintVerdict` (:523-535) needs **zero edits**: the wire path (`domain.sprint().verdictAt(clock.current())`) now carries the seq automatically; the fallback path's 3-arg constructor yields `NO_WIRE_SEQ`. Same for `DamageRouter:79`, `FishingKnockbackUnit:188/209`, `ProjectileKnockbackUnit:397` (all 3-arg mints → `NO_WIRE_SEQ` → the stamp-guard leg, byte-identical). `PacketTap`, `WtapRegistrationUnit`, `HitContext`, `ConnectionDomains` untouched.

## 4. Threading analysis

- `seq` lives inside `SprintWire.State`, mutated ONLY inside `updateAndGet` CAS loops. Every retry recomputes `s.seq() + 1` from the freshest state, so bumps are never lost and `seq` is strictly monotonic per wire. Writers: the connection's netty thread (START/STOP/reconcile), the attacker's region thread (`onBlockSprintReset`), the victim's region thread (`onServerClear` — compares, never bumps). This is exactly the sanctioned-writer set the `ConnectionDomains` licensing doc lists; the CAS gives each cross-thread read a coherent atomic view and happens-before on every write. `long` inside a record component read via `state.get()` is tear-free (it is a final field of an immutable object published through an `AtomicReference`).
- `SprintVerdict.wireSeq` is stamped on the netty thread at registration, frozen into the immutable `HitContext`, and read on the victim's region thread (obligations) and the attacker's own thread (the deferred `setSprinting` lambda) — the standard cross-domain immutable-value channel; no new mutable state crosses a domain.
- Reconcile outrace (the design-care item), leg by leg: (a) between an ATTACK and a same-tick later START, any interleaved movement-packet reconcile sees wire `sprinting=true` (pre-clear) == `view.sprinting()` true (the server flag is untouched until the deferred obligation) → no adopt, no bump. (b) The attack-flush STOP and the re-press START both refresh `lastWrite=T`, so no adopt is possible until T+3 — by which time the deferred clear (T+1..T+2) has already been ordered by seq. (c) When the clear APPLIES, it refreshes `lastWrite=now`, so the reconcile cannot re-adopt the still-true server flag before the deferred `setSprinting(false)` lands (preserved from 2.4.1 — this is why the clear keeps writing `lastWrite` but not `seq`). (d) A reconcile adopt that does happen bumps `seq`, so a pathologically late clear no-ops against it — the same outcome the current `lastWrite > asOf` guard produces (reconcile sets `lastWrite=now` today). No leg lets Mental's own cleared flag outrace a same-tick START.
- `seq` is a `long` bumped at most once per inbound sprint packet / reconcile write — overflow is unreachable.

## 5. Era / zero-touch analysis

- **No config change of any kind**: no new knob, no YAML, no `Snapshot` field → `parse(empty) == LEGACY_17` untouched by construction; zero-touch untouched (the wire is always-on infrastructure, and its only behavior change is the clear's ordering guard).
- The behavior change is confined to the window "a wire write arrived after the ATTACK's verdict peek but within the same tick": previously the clear ate it; now it survives. That IS the era contract (compendium §5: the era queue applied STOP/START/ATTACK in arrival order; vanilla's clear ran synchronously inside `attack` and could never eat a later arrival) — this is a divergence-from-era being closed, not a feel knob.
- `wtapConsultWire=false` (WTAP_REGISTRATION disabled): verdicts are view-fallback → `NO_WIRE_SEQ` → the stamp overload + the pre-change skip-guard condition — byte-identical to today.
- Packetless attackers (FakePlayers, SimpleBoxer): no domain → no wire clear, unconditional `setSprinting(false)` — unchanged.
- Non-melee sources and the blocked-redeliver mint carry 3-arg verdicts → `NO_WIRE_SEQ` leg — unchanged.
- One deliberately widened residual, pre-existing in kind: a STOP arriving after the ATTACK (s-tap) now defeats the clear at same-tick range exactly as a T+1 STOP already does today (the strictly-newer-lastWrite guard) — the wire stays `sprinting=false` (STOP's value; no bonus possible since `freshSprint` requires `sprinting`), with `armed` surviving as the "release half never disarms" contract states. Pinned deliberately in test D below.

## 6. Tests

All in `kernel/src/test/java/me/vexmc/mental/kernel/wire/SprintWireTest.java`, using the existing `Clock` harness. Hand-computed seq traces stated per case (INITIAL seq = 0; every START/STOP/blockReset/reconcile-write = +1).

A. `wireSeqStampsIntoTheVerdictAtPeek` — fresh wire: `verdictAt(0)` → `wireSeq == 0`. After `onSprintStart()` (seq 0→1): `verdictAt` → `wireSeq == 1`, `sprinting`, `fresh == TRUE`. Also assert the 3-arg constructor yields `NO_WIRE_SEQ` and `fromWire() == false`.

B. `sameTickReEngageAfterTheAttackSurvivesTheSeqGuardedClear` — **the mandated replay** (ATTACK → STOP → START same tick → next-tick ATTACK):
```java
clock.tick = 4; wire.onSprintStart();               // engagement: seq 1, sprinting+armed
clock.tick = 5;
SprintVerdict first = wire.verdictAt(new TickStamp(5)); // the ATTACK's peek
assertTrue(first.sprinting()); assertEquals(1L, first.wireSeq());
wire.onSprintStop();                                 // the attack-flush STOP: seq 2 (armed survives)
wire.onSprintStart();                                // the same-tick w-tap re-press: seq 3
clock.tick = 7;
wire.onServerClear(first.wireSeq());                 // deferred EDBEE clear: 3 > 1 ⇒ no-op
SprintVerdict second = wire.verdictAt(new TickStamp(7)); // the next-tick ATTACK's peek
assertTrue(second.sprinting());                      // ships the sprint knock (0.9 h, not 0.4)
assertEquals(Boolean.TRUE, second.fresh());
assertEquals(3L, second.wireSeq());
```
Trace: seq 1 (engage) → peek 1 → STOP seq 2 (`sprinting=false, armed=true`) → START seq 3 (`sprinting=true, armed=true`) → clear(1): 3 > 1 → state unchanged → verdict true/TRUE. (The old stamp guard cleared here: lastWrite 5 not > asOf 5.)

C. `seqGuardedClearWithNoLaterWriteClears` — engage at tick 5 (seq 1), peek (`wireSeq 1`), advance clock to 7, `onServerClear(1)`: 1 <= 1 → clears. Assert `sprinting == false`, `fresh == FALSE`, and `clientSprinting()` still true (the raw flag is untouched, as ever).

D. `aStopAfterTheAttackIsNotRetroEatenButStaysNonSprinting` — the s-tap pin: engage (seq 1), peek (1), `onSprintStop()` (seq 2), `onServerClear(1)`: 2 > 1 → no-op. Assert `sprinting == false` (STOP's value — no bonus possible) and `fresh == TRUE` (armed survives the release half; the pre-existing newer-write semantic now at arrival granularity — deliberate).

E. `heldBlockResetSurvivesTheSeqGuardedClear` — `onSprintStart()` (seq 1), `onBlockSprintReset()` (seq 2), peek (`wireSeq 2`), `onServerClear(2)`: 2 <= 2 → clear applies but carries `blockReset`/`clientSprinting`. Verdict: `blockHeldReset = blockReset && clientSprinting = true` → `sprinting == true`, `fresh == TRUE` — the universal blockhit contract holds on the seq overload (the runtime path block-hit combos now take).

F. `reconcileWritesCountForTheSeqGuard` — engage at tick 0 (seq 1), peek (1); `reconcile(false, new TickStamp(10), 3)`: disagree + age 10 ≥ 3 → adopt (`sprinting=false`, seq 2); `onServerClear(1)`: 2 > 1 → no-op; verdict `sprinting == false`. Pins adopt-as-wire-write parity with the current lastWrite guard.

**Existing pins that must NOT change**: all 15 current `SprintWireTest` cases pass unmodified — the stamp-overload tests (`guardedServerClearNoOpsUnderANewerWireWrite`, `guardedServerClearAtOrBeforeTheStampClears`) still pin that overload's frozen contract (it remains live for no-provenance verdicts); the deprecated no-arg pin, the blockhit-contract pins, the reconcile pins, and every 3-arg `SprintVerdict` construction across `SimulatedThreads`, `HitTransactionTest`, `DeliveryDeskTest`, `CombatSessionTest`, `DamageRouterTest` compile and behave identically via the compat constructor. No `KnockbackEngineTest` or era-parity expectation changes (no math touched).

## 7. Verification

- `./gradlew build` — all unit tests green including the six new pins; japicmp green (record gains a component but the explicit 3-arg constructor preserves the old shape; `wireSeq()`/`fromWire()`/`onServerClear(long)`/`NO_WIRE_SEQ` are pure additions); the kernel-Bukkit-free assertion unaffected (no new imports in kernel); the four mega-jar gates unaffected (`long` is a v52-native type; note jvmdg once mis-lowered a record `toString` with a `short` component — `long` is not in that class, and `verifyDowngrade`/`verifyJdk8Api` will catch any surprise).
- `./gradlew integrationTestMatrix` — trust ONLY the fresh-nonce PASS in `test-results.txt`, never the BUILD SUCCESSFUL banner (matrix-gate rule). Existing sprint-family suites (`BlockingSuite` case 6, `KnockbackSuite`, `EraParitySuite`, `FoliaCombatSmoke`) must pass unchanged.
- No new tester suite: FakePlayers cannot emit real START/STOP through the parse rim (live-server-testing: clientless players never traverse the rim), so the exact packet replay is only drivable at the wire seam — which the kernel test B replays against the real `SprintWire` verbatim; the untouched `PacketTap` plumbing adds no new integration surface. End-to-end confirmation is the owner's SimpleBoxer/live w-tap capture (the F9 journal enrichment is the measurement vehicle).
- Commit: `fix(sprint-wire): order the post-hit clear by arrival sequence, not tick stamps` with a prose body citing the finding (a same-tick START-after-ATTACK lost to its own hit's deferred clear; the era queue could never do that) and the guard translation (strictly-newer lastWrite → strictly-newer seq).

## 8. Risks + rollback

- **Widened newer-STOP no-op** (test D): `armed` survives an s-tap's same-tick STOP where it used to clear. Bounded: a bonus requires `sprinting=true`, so the residual alone ships nothing; the phantom-fresh path (a later reconcile-adopt-true riding leftover `armed`) already exists today for a T+1 STOP and is unwidened in kind.
- **Companion two-victim peek race** (extra KB, both hits fresh within the pre-clear window) is real but out of scope and UNCHANGED by this fix — do not attribute residual extra-KB reports to F1.
- **Modern-client no-reengage** (Tier-2 divergence) is untouched: clients that never send STOP/START still go plain after the clear — F1 only stops Mental destroying re-engages that DO arrive.
- Rollback: revert the three `KnockbackUnit` call sites and the method signature to `verdict.at()` + the plain `sprinting()` skip-guard — the kernel additions (`seq`, `wireSeq`, `onServerClear(long)`) are additive and inert when uncalled.

## Files touched

- `kernel/src/main/java/me/vexmc/mental/kernel/model/SprintVerdict.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/wire/SprintWire.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/wire/SprintWireTest.java`
- `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java`

## Cross-lane conflicts (integrator notes)

F2 (presend outcome pin) edits HitRegistrationUnit.plan and likely the HitTransaction state handling that KnockbackUnit's PRE_SENT branch reads — my one-token argument change at KnockbackUnit.java:266 sits inside that branch, so a textual merge conflict is possible there (semantic independence: F1 only changes the obligations argument). F5+F6 (enchant-in-view + registration-yaw stamp) touch HitRegistrationUnit and possibly HitContext — F1 deliberately leaves both untouched, but if F5/F6 add HitContext components the record constructors must be merged carefully with any callers F1's implementer sees. F9 (journal capture enrichment) may print SprintVerdict via toString or add verdict fields to JournalEntry — SprintVerdict.toString now has a fourth component (wireSeq), so F9 should capture wireSeq explicitly rather than relying on the 3-component shape (a useful signal for its per-hit capture anyway). F7 (servo sprint-fresh saturation) reads verdict freshness in PocketServo/ComboSettings — no file overlap, but if it consumes SprintVerdict it should use the compat accessors only. F3, F4, F8: no shared files or regions.
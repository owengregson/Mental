## F4 — desk supersede must carry the owed velocity event to the newest decision (never PASS_THROUGH vanilla)

### 1. Problem

`DeliveryDesk.replacePending` (kernel/src/main/java/me/vexmc/mental/kernel/delivery/DeliveryDesk.java:312-328) resets `awaiting = false` when a newer submission supersedes an armed pending, and `resolve()` (DeliveryDesk.java:172-177) drains the wire FIRST then answers `!awaiting -> PASS_THROUGH`. So when a second submission for the same victim lands between an earlier hit's arm (`desk.awaitVelocityEvent`, KnockbackUnit.java:265/:342) and that hit's end-of-tick `PlayerVelocityEvent` (the tracker fires at most ONE per tick — `hurtMarked` collapses same-tick hits), the owed event resolves against nothing and vanilla's damage-pass velocity stands: base-only 0.4 horizontal with NO sprint/enchant extras (they live in the cancelled `Player.attack`) and kept falling-y when airborne — the confirmed close-range weak-KB leak. A second, adjacent hole in the same seam: `submitFromWire` (DeliveryDesk.java:51-53) is a plain last-write-wins `AtomicReference.set`, and it is called from the REGISTERING attacker's netty thread (HitRegistrationUnit.java:430-432) — two attackers are two DIFFERENT netty threads, so a second wire submit before the owner drains (or even a plain concurrent race) silently discards the first transaction: never journaled, never valve-relevant, vanilla's duplicate ENTITY_VELOCITY ships uncancelled.

### 2. Design decision

**Keep the single pending slot; make `awaiting` mean "a velocity event is owed to this desk" and PRESERVE it across a supersede** — the owed event then always resolves against the NEWEST decision and ships its vector. This is the era-faithful shape: the 1.7/1.8 tracker ships ONE ENTITY_VELOCITY per tick reflecting the LATEST server motion fields (legacy-motion-physics: the tracker wire; era-accuracy: the full-stamp contract), so "latest decision answers the one owed stamp" is exactly the era wire. A two-deep queue is rejected: it would either ship two stamps per event window or resolve the event with the OLDER fields — both anti-era. Concretely the fix is *deleting* the `awaiting = false` reset in `replacePending` (line 327) and re-documenting the flag; `clearDecision()` (resolve/withdraw/sweep) remains the only place the debt is cleared. **Additionally, replace the lossy wire slot with a lock-free CAS-pushed immutable chain** drained in arrival order, so an overtaken wire transaction flows through the one supersede path (journaled `"superseded"`, state SUPPRESSED→RECORDED, never reaches `resolve`, therefore never arms a valve) instead of vanishing. No changes are needed in `HitTransaction.java` (SUPPRESSED already exists and `suppressed("superseded")` is the existing supersede transition), `Directive.java` (no new action), or `DeskRouter.java` (it reads `pendingFormula()/pendingContext()` — which drain — immediately before `resolve`, so context, shipped vector, ledger `recordDelivered`, and the combo feed all coherently follow the newest decision with zero edits).

Known single-slot residual (documented, accepted): an era-silent blocked difference hit that supersedes an armed fresh hit now ships the era-silent hit's recompute on the owed event (row 7 below) where true era would ship the ARMED hit's knock (the era-silent hit wrote no server fields). That recompute is the same-tick era-model value for the same attacker/victim — a close cousin — and strictly dominates the current behavior (vanilla base-0.4 leak). Fixing it exactly would require a second vector slot, i.e. the rejected queue. The window is the narrow boundary-combo + blockhit overlap; the default-config-reachable scenarios (packetless PINNED double-submits at spam CPS; two attackers; lowered feedback-min-interval-ms) all ship the correct newest era vector under this design.

### 3. Exact changes

**File 1: `kernel/src/main/java/me/vexmc/mental/kernel/delivery/DeliveryDesk.java`** (all edits private/internal — zero public API change, japicmp-clean; kernel stays pure JDK).

(a) Wire slot → chain. Replace field (lines 36-37):

```java
/** The netty→owner hand-off slot: a pre-sent/pinned transaction ahead of its damage task. */
private final AtomicReference<HitTransaction> wireSlot = new AtomicReference<>();
```
with:
```java
/**
 * The netty→owner hand-off slot: pre-sent/pinned transactions ahead of their
 * damage tasks, an immutable arrival-ordered chain. Registration runs on the
 * ATTACKER's netty thread, so two attackers push from two different threads,
 * and the owner may not drain between pushes — the old last-write-wins
 * reference silently discarded the earlier transaction (never journaled, its
 * vanilla duplicate never valve-consumed). The CAS push keeps every arrival;
 * the owner drains the whole chain in arrival order so an overtaken
 * transaction is journaled "superseded" through the one supersede path.
 */
private final AtomicReference<Wire> wireSlot = new AtomicReference<>();
```
Add at the bottom of the class (beside the other private helpers):
```java
/** One wire arrival; {@code prior} chains the earlier un-drained arrivals (newest first). */
private record Wire(HitTransaction tx, Wire prior) { }
```
(Object-typed components only — no `short` fields, so the jvmdg record-toString trap cannot apply; we never call its toString.)

(b) `submitFromWire` (lines 50-53) becomes a pure CAS push (the lambda only allocates, safe under `getAndUpdate` retry):
```java
/** Netty entry: a PRE_SENT/PINNED transaction arriving ahead of its damage task. */
public void submitFromWire(HitTransaction tx) {
    wireSlot.getAndUpdate(prior -> new Wire(tx, prior));
}
```

(c) `drainWire()` (lines 305-310) drains the whole chain oldest→newest so the NEWEST ends pending and every displaced one journals:
```java
private void drainWire() {
    Wire newestFirst = wireSlot.getAndSet(null);
    if (newestFirst == null) {
        return;
    }
    // Reverse to arrival order: each older arrival is superseded (journaled) by
    // the next, and the newest becomes the pending decision — the era's
    // latest-fields stamp.
    Wire arrivalOrder = null;
    for (Wire node = newestFirst; node != null; node = node.prior()) {
        arrivalOrder = new Wire(node.tx(), arrivalOrder);
    }
    for (Wire node = arrivalOrder; node != null; node = node.prior()) {
        replacePending(node.tx(), node.tx().carried());
    }
}
```

(d) `replacePending` (lines 312-328): DELETE `awaiting = false;` and replace the trailing comment block (lines 321-327) with:
```java
// `awaiting` is deliberately PRESERVED across the replace: it means "a
// velocity event is owed to this desk" (an armed decision's damage pass
// already marked hurt), not "the current pending was armed". The era wire
// ships ONE tracker stamp per tick reflecting the LATEST server fields, so
// the owed event must resolve against the newest decision. Resetting the
// flag here let a superseded hit's own event fall to PASS_THROUGH and ship
// vanilla's damage-pass velocity — base-only 0.4 with no sprint/enchant
// extras (they live in the cancelled Player.attack), kept falling-y when
// airborne: the close-range weak-knock leak. A supersede while nothing is
// armed (era-silent chains) stays unarmed exactly as before; the debt is
// cleared only by clearDecision (resolve / withdraw / sweep).
```
The `if (pending != null && pending != tx && LIVE.contains(pending.state()))` supersede-journal branch is UNCHANGED (reason stays the plain string `"superseded"` — the enriched `-> <id>` correlation belongs to F9's journal-enrichment lane; do not add it here or you collide).

(e) `awaitVelocityEvent` javadoc (lines 99-108): append one sentence — "The `tx` argument is deliberately unchecked against the pending: the arm declares that a velocity event is OWED to this desk (the caller's damage pass marked hurt); if a newer submission supersedes before the event lands, the debt carries and the newest decision answers it — the era's one-stamp-latest-fields wire." No code change in the method.

(f) Class javadoc (lines 20-23): after "last-submitter-wins per victim per tick", add "— a supersede journals the displaced decision and carries any owed velocity event to the newest".

**File 2: `kernel/src/test/java/me/vexmc/mental/kernel/delivery/DeliveryDeskTest.java`** — see §6.

**No other files change.** Specifically: `HitTransaction.java` untouched (F2 owns it); `Directive.java` untouched; `DeskRouter.java` untouched (F3 owns it); no core/ change; no config change.

### 4. Threading analysis

- `submitFromWire` is the desk's ONLY cross-thread entry and now the only multi-writer one: each registering attacker's netty thread pushes onto the immutable chain via CAS (`getAndUpdate` with a pure allocating lambda). Lost updates — previously possible with plain `set()` under two concurrent attackers even WITHOUT session starvation — become impossible; contention cost is one retry allocating one small node.
- `drainWire`, `replacePending`, `resolve`, `sweep`, `withdraw*`, `ensure`, `awaitVelocityEvent` and the plain fields `pending/pendingVector/vectorSubmitted/awaiting` remain owner-thread-confined (the victim's session/region thread), exactly as today — the fix adds NO new shared mutable state; `awaiting` only loses one write site.
- The journal `ArrayDeque` stays owner-written only; displaced wire transactions are journaled during the owner's drain, never on netty.

### 5. Era / zero-touch analysis

- No new knob, no config read, `parse(empty)==LEGACY_17` untouched. Feature disabled ⇒ nothing ever submits to the desk ⇒ `resolve` still hits row 1 (PASS_THROUGH, unjournaled) ⇒ zero-touch intact.
- Single-hit sequences (submit → arm → resolve, the overwhelmingly common path) are byte-identical: the deleted reset only ever executed when a SECOND submission landed inside the arm→event window. All four canonical end-to-end pins are unaffected.
- The era contract this restores: the legacy tracker broadcast ONE velocity stamp per tick carrying the latest server motion fields. Before: that owed stamp degraded to vanilla's damage-pass motion (non-sprint 0.4 / airborne downward). After: it carries the newest Mental era vector — full stamp, latest fields. The era-silent unarmed contract is preserved unchanged (an unarmed pending with no inherited debt still passes foreign events through and is closed by the sweep).

### 6. Tests (`DeliveryDeskTest.java`)

Constants used: `SPRINT = new KnockbackVector(0.9, 0.4608, 0.0)` (0.4+0.5 sprint level horizontal; vertical −0.0784×0.5+0.4+0.1 = 0.4608) and `STANDING = new KnockbackVector(0.0, 0.3608, 0.4)` (vertical −0.0784×0.5+0.4 = 0.3608). Valve quantization for STANDING at victim entity 42: qx = 0.0×8000 = 0; qy = 0.3608×8000 = 2886.4 → (short)2886; qz = 0.4×8000 = 3200. Reuse the existing `ctx`/`preSent` helpers.

**NEW `supersededArmedDecisionsOwnEventShipsTheNewestVectorNeverVanilla`** (the brief-mandated pin): `A = preSent(1, SPRINT, 0); desk.submit(A, SPRINT); desk.awaitVelocityEvent(A);` then `B = preSent(2, STANDING, 0); desk.submit(B, STANDING);` (supersede mid-window, B's caller has NOT armed yet) then `Directive d = desk.resolve(0.0, 0.3608, 0.4);`. Assert: `d.action() == SHIP_AND_ARM_VALVE`; `assertSame(STANDING, d.ship())` (branch 3 ships `tx.carried()`, the exact object); `d.arm().equals(ValvePayload.of(42, STANDING))` i.e. `(42, (short)0, (short)2886, (short)3200)`; journal size 2 — entry 0: id 1, shipped null, wireCarried false, reason `"superseded"`; entry 1: id 2, shipped STANDING, wireCarried true, reason null; `A.state()==RECORDED`, `B.state()==RECORDED`; exactly one arm (the single directive carries the only non-null `arm`; A never reached resolve).

**NEW `wireArrivalsNeverVanishBetweenDrains`**: `desk.submitFromWire(preSent(1, SPRINT, 0)); desk.submitFromWire(preSent(2, STANDING, 0));` with no owner op between; then `desk.awaitVelocityEvent(txB)` (drains the chain in arrival order: A becomes pending, B supersedes it journaled) and `desk.resolve(0.0, 0.3608, 0.4)`. Assert SHIP_AND_ARM_VALVE with STANDING (the NEWEST — proves arrival-order drain, not stack order); journal = [id 1 `"superseded"`, id 2 shipped wireCarried true]; A RECORDED.

**NEW `pinnedDoubleSubmitShipsTheNewestWithoutAValve`** (the default-config packetless scenario, finding trigger (b)): build A and B via `planned(); pinned(vec)` (A carries SPRINT, B carries STANDING); `submitFromWire(A); submitFromWire(B); desk.awaitVelocityEvent(A);` — the arm argument is deliberately the OLDER tx, pinning that the DEBT (not the tx identity) is what arms; `resolve(0.0, 0.3608, 0.4)` → action SHIP (PINNED never arms a valve, B4), `assertSame(STANDING, d.ship())`, `assertNull(d.arm())`; journal = [A `"superseded"`, B shipped wireCarried false].

**NEW `eraSilentSupersedeOfAnArmedDecisionStillAnswersTheOwedEvent`** (the documented residual): armed `A = preSent(1, SPRINT, 0)` (submit+arm), then an era-silent region recompute `B = new HitTransaction(ctx(2, new HitSource.Melee(), 0))` (state REGISTERED) submitted UNARMED: `desk.submit(B, STANDING)`. `resolve(0.1, 0.2, 0.3)` (arbitrary api — the LIVE-REGISTERED branch ignores it) → action SHIP with STANDING, no valve; journal = [A `"superseded"`, B shipped]. Comment in the test: the owed event is answered by the desk's newest era-model knowledge, never vanilla's leak; the exact-era answer (A's vector) is unreachable in a single slot and this window is the narrow boundary+blockhit overlap.

**REWRITE `awaitingDeliveryForDoesNotInheritASupersededDecisionsArm`** (lines 443-458 — its pinned semantics are the bug) → rename `supersedeCarriesTheOwedEventArmToTheNewestDecision`: armed A (submit+arm), fresh B submit → `assertTrue(desk.awaitingDeliveryFor(new HitId(2)))` (the debt carries to the newest) and `assertTrue(!desk.awaitingDeliveryFor(new HitId(1)))` (the superseded decision is no longer the pending). Second half, on a fresh desk: submit C unarmed, submit D unarmed → `assertTrue(!desk.awaitingDeliveryFor(dId))` — no debt is ever fabricated for an era-silent chain.

**Pins that must NOT change** (re-run green, byte-identical assertions): `resolveWithNoAwaitedTransactionPassesThrough`, `unexpectedVelocityEventAtAnUnarmedPendingPassesThroughAndLeavesItForTheSweep` (the era-silent contract), `lastSubmitterWinsSupersedingTheEarlier` (reason string stays `"superseded"`), both withdraw tests, `withdrawSuperseded*`, `journalDrop*`, all four sweep tests including `sweepNeverTimeDropsAConnectedVictimsMelee` (the 2.4.6 pin), `resolveOnARecordedPendingPassesThroughWithoutThrowing` (F6), `journalRingEvictsTheOldest`, `ensureShips...`, `awaitingDeliveryForDiscriminatesAnArmedDecisionFromAnUnarmedOne`, `mirrorAndPendingFormula...`, `valvePayloadQuantizesAndClamps`, `canonicalKnockbackPinsShipThroughTheDeskPath`.

### Combined resolve() truth table after F2+F3+F4 — THE CONTRACT for the shared worktree lane

Evaluated after `drainWire()` (so "pending" is always the NEWEST decision; every displaced LIVE decision has already journaled `"superseded"` and can never arm a valve). formula = submitted vector (or `carried()` fallback).

| # | pending | awaiting | pending state | api vs formula | Directive | journal | valve arms |
|---|---|---|---|---|---|---|---|
| 1 | null | any | — | — | PASS_THROUGH | none (foreign, unjournaled) | 0 |
| 2 | tx | false | LIVE | — | PASS_THROUGH (no event owed — era-silent decision left for the sweep) | none | 0 |
| 3 | tx | true | LIVE, formula null | — | CANCEL_EVENT | "resistance-roll" | 0 |
| 4 | tx | true | PRE_SENT | equal | SHIP_AND_ARM_VALVE(carried) | shipped, wireCarried=true | exactly 1 |
| 5 | tx | true | PRE_SENT | differs | SHIP(api) | shipped=api, wireCarried=false | 0 |
| 6 | tx | true | PINNED (incl. F2's failed-burst downgrade) | — | SHIP(carried) | shipped, wireCarried=false | 0 (B4) |
| 7 | tx | true | REGISTERED/PLANNED | — | SHIP(formula) | shipped, wireCarried=false | 0 |
| 8 | tx | true | RECORDED/terminal | — | PASS_THROUGH | "late-resolve-recorded" note | 0 |

F4's delta: a supersede between arm and event no longer forces rows 1/2 (the vanilla leak) — `awaiting` survives and row selection uses the newest decision. F2's delta: an unshipped burst can never present at rows 4/5; it arrives at row 6. F3's delta: row 4's single arm is what its valve lifecycle confirms/expires — F4 guarantees at most one SHIP_AND_ARM_VALVE per owed event and zero arms for superseded transactions (they are journaled at drain time, before resolve runs).

### 7. Verification

`./gradlew build` green: all kernel tests including the 5 new/rewritten DeliveryDeskTest cases; japicmp clean (no public signature changed — `submitFromWire(HitTransaction)` identical, everything else private); kernel-Bukkit-free check unaffected; D-9 log scan unaffected (no new logging). Tester: no new suite REQUIRED — the fix is fully kernel-pinnable. Optional follow-up (do not gate on it): a two-attacker-one-victim FakePlayer stage would exercise the PINNED double-submit path (FakePlayers are packetless, exactly finding scenario (b)), but same-tick bunching is timing-fragile per live-server-testing; if added, assert on the JOURNAL (one `superseded` + one shipped per bunched pair, zero unjournaled gaps), not on positions. Run `./gradlew integrationTestMatrix` before release as usual (honor the nonce rule).

Commit: `fix(delivery): carry the owed velocity event across a supersede — never vanilla` with a prose body explaining the one-stamp-latest-fields era contract, the awaiting=false reset leak, the netty wire-slot lost-update, and the single-slot residual.

### 8. Risks + rollback

- `awaitingDeliveryFor` now reads true for a newest pending that inherited a debt before its caller armed it — consumers: `SessionService:402` (the F1 packetless net) which runs on the session task, not mid-EDBEE, so the transient window is invisible; the durable case makes the net ship the newest vector where vanilla previously leaked — intended.
- Era-silent-over-armed residual ships the newest recompute instead of the armed hit's exact vector (documented above; strictly better than the leak; rare window).
- The wire chain is unbounded in principle; in practice bounded by CPS × starvation ticks (a handful of tiny nodes) and fully cleared every drain — no cap needed.
- Rollback: revert the single commit; no config, journal-format, or API change to migrate.

## Files touched

- `kernel/src/main/java/me/vexmc/mental/kernel/delivery/DeliveryDesk.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/delivery/DeliveryDeskTest.java`

## Cross-lane conflicts (integrator notes)

F2 (shares the resolve() truth table and DeliveryDeskTest.java; F2's PINNED downgrade must land at row 6 — no HitTransaction.java edits from F4, so file conflict is test-class-only). F3 (shares the valve-arm semantics — F4 guarantees ≤1 arm per owed event and zero arms for superseded txs; F4 makes NO edits to DeskRouter/DirectiveExecutor/ValveListener, so overlap is contract-level plus possible DeliveryDeskTest edits). F9 (journal capture enrichment touches DeliveryDesk.java's record/appendJournal region — direct file overlap; F4 deliberately keeps the supersede reason the plain string "superseded" and leaves the superseding-id correlation to F9).
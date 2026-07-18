# F2 — Pin the hit when the pre-send burst fails to ship (presend-marked-before-ship-outcome-discarded-valve-eats-only-copy)

## 1. Problem

`HitRegistrationUnit.plan()` commits `tx.preSent(shipped)` at `core/src/main/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationUnit.java:409` BEFORE `senders.ship(...)` at :427, and discards `ship()`'s `Outcome`. `BurstSender.ship()` (`core/src/main/java/me/vexmc/mental/v5/rim/BurstSender.java:63-82`) catches all Throwables and null-user, returning `UNSENDABLE` having written NOTHING — yet the phantom-PRE_SENT tx is still `submitFromWire`'d (:430-431), the authoritative pass adopts it, `DeliveryDesk.resolve` (:186-194) returns `SHIP_AND_ARM_VALVE`, and the `ValveListener` cancels the tracker's authoritative ENTITY_VELOCITY — the ONLY copy that would ever have reached the client. Result: damage + flinch land, zero velocity packets, journaled as a healthy wireCarried=true SHIP. Both triggers (the user-null race between the :324-325 check and the :427 ship, and a mid-ship throw) surface as the same `UNSENDABLE` return, so one fix covers both.

## 2. Design decision

**Move the state commit AFTER the ship and branch on the captured `Outcome`** (rather than adding a `PRE_SENT → PINNED` downgrade transition). Rationale: the state machine's PRE_SENT/PINNED split is deliberately a type-level guarantee ("conflating them is unrepresentable" — netty-fast-path skill); a backward downgrade edge would create an instant where the tx *lies* (PRE_SENT + wireCarried=true with nothing on the wire) and would need `wireCarried` to be mutable-downward. Committing after the ship means the machine never states an untruth at any instant, needs zero new transition edges, and preserves the `submitFromWire` ordering (state commit still strictly precedes the hand-off). The reorder is timing-neutral on the wire — only local bookkeeping moves.

The downgrade is journaled by riding the transaction: `HitTransaction` gains a nullable `deliveryNote` ("wire-failed") set atomically with the pin; `DeliveryDesk.resolve`'s PINNED branch passes it as the journal reason. This avoids any `JournalEntry` shape change (no conflict with F9's enrichment): `suppressReason` is already the free-text note column (existing entries like `"blocked-redeliver -> 7"` use it correlationally), and both tester readers (`BlockingSuite:159`, `FoliaCombatSmoke:131`) only *print* it diagnostically after gating on `shipped != null` — a non-null note on a shipped entry breaks nothing. The netty thread cannot write the journal (single-writer), so the note MUST ride the tx to the owning-thread `record`.

Extract the commit decision as a package-private pure static (`commitPreSendState`), mirroring the existing `admitVelocityPreSend` precedent at :511-521, so the contract is unit-pinnable without PacketEvents scaffolding.

## 3. Exact changes

### 3a. `kernel/src/main/java/me/vexmc/mental/kernel/delivery/HitTransaction.java`

Add (all additive — japicmp-safe; pure JDK):

```java
    /** The journal note for a burst the wire refused (BurstSender UNSENDABLE) — the pin downgrade. */
    public static final String WIRE_FAILED = "wire-failed";

    private String deliveryNote; // field, after suppressReason
```

New method, placed directly after `pinned(...)` (:126-130):

```java
    /**
     * The wire refused this hit's burst (a user-null race or a mid-ship throw —
     * BurstSender returned UNSENDABLE having written nothing): pin the era-moment
     * vector so it ships once via the genuine velocity event and no valve ever
     * arms to eat the victim's only authoritative ENTITY_VELOCITY. The note is
     * journaled by the desk on the pinned ship, so a wire failure is visible in
     * one journal read instead of masquerading as a healthy wire-carried SHIP.
     */
    public void pinnedWireFailed(KnockbackVector eraVector) {
        pinned(eraVector);
        this.deliveryNote = WIRE_FAILED;
    }

    /** The delivery note the desk journals on a pinned ship ({@link #WIRE_FAILED}), or null. */
    public String deliveryNote() {
        return deliveryNote;
    }
```

`pinnedWireFailed` from a non-PLANNED state throws via `pinned`'s existing assertion — no new legality logic. Update the class-doc `<pre>` diagram's PINNED line to: `→ PINNED (value pinned — connectionless victim OR wire-failed burst; ships once via the velocity event, never a valve — B4)`.

### 3b. `core/src/main/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationUnit.java` — `plan()`, lines 404-433

BEFORE (current):

```java
            KnockbackVector shipped = null;
            if (velocityShips) {
                shipped = vector; // TRACKER (full stamp); TRACKER_DECAYED handled by the desk record
                tx.planned();
                if (victimHasWire) {
                    tx.preSent(shipped);
                } else {
                    tx.pinned(shipped);
                }
            }

            float hurtYaw = HurtYaw.hurtYaw(...);   // :415-419 unchanged
            // ...comment :420-424 unchanged...
            boolean shipBurst = victimHasWire && (vector == null || velocityShips);
            if (shipBurst) {
                senders.ship(PacketEvents.getAPI().getPlayerManager().getUser(playerVictim),
                        victimView.entityId(), shipped, hurtYaw, settings.bundleFeedback());
            }
            if (tx.state() == HitTransaction.State.PRE_SENT || tx.state() == HitTransaction.State.PINNED) {
                sessions.sessionFor(victimId).desk().submitFromWire(tx);
            }
            return tx;
```

AFTER:

```java
            KnockbackVector shipped = velocityShips ? vector : null; // TRACKER (full stamp); TRACKER_DECAYED handled by the desk record

            float hurtYaw = HurtYaw.hurtYaw(...);   // :415-419 byte-identical, keep in place
            // ...existing shipBurst comment block unchanged...
            boolean shipBurst = victimHasWire && (vector == null || velocityShips);
            // The burst ships FIRST and the transaction state commits off its
            // Outcome — BurstSender's contract: an UNSENDABLE burst (user-null
            // race, mid-ship throw) must be PINNED, never accounted
            // wire-delivered. Committing PRE_SENT ahead of the ship let a failed
            // burst arm the valve, which then ate the authoritative
            // ENTITY_VELOCITY — the victim's only copy (F2).
            BurstSender.Outcome outcome = null;
            if (shipBurst) {
                outcome = senders.ship(PacketEvents.getAPI().getPlayerManager().getUser(playerVictim),
                        victimView.entityId(), shipped, hurtYaw, settings.bundleFeedback());
            }
            commitPreSendState(tx, shipped, velocityShips, victimHasWire, outcome);
            if (tx.state() == HitTransaction.State.PRE_SENT || tx.state() == HitTransaction.State.PINNED) {
                sessions.sessionFor(victimId).desk().submitFromWire(tx);
            }
            return tx;
```

New package-private static in the "shared helpers" section (beside `admitVelocityPreSend`):

```java
    /**
     * Commits the transaction state for a planned velocity pre-send off the
     * burst's actual ship {@link BurstSender.Outcome}. Only a DELIVERED burst may
     * account wire-carried (PRE_SENT — the valve will consume the tracker
     * duplicate); anything else on a wired victim is the wire-failed pin — the
     * era-moment vector ships once via the genuine velocity event and no valve
     * arms (a phantom PRE_SENT would let the valve eat the victim's only copy).
     * A connectionless victim pins plain (the pre-existing B4 path). A no-velocity
     * plan (hurt-only burst) commits nothing — the hit stays REGISTERED for the
     * authoritative recompute. Pure over the transaction so the contract is
     * unit-pinned at this seam.
     */
    static void commitPreSendState(
            HitTransaction tx, KnockbackVector shipped, boolean velocityShips,
            boolean victimHasWire, BurstSender.Outcome outcome) {
        if (!velocityShips) {
            return;
        }
        tx.planned();
        if (victimHasWire && outcome == BurstSender.Outcome.DELIVERED) {
            tx.preSent(shipped);
        } else if (victimHasWire) {
            tx.pinnedWireFailed(shipped);
        } else {
            tx.pinned(shipped);
        }
    }
```

(`velocityShips && victimHasWire` guarantees `shipBurst` was true, so `outcome` is non-null on that path; the null-outcome defensive branch still lands in `pinnedWireFailed` — never a phantom PRE_SENT.)

### 3c. `kernel/src/main/java/me/vexmc/mental/kernel/delivery/DeliveryDesk.java` — `resolve`, branch 5 (:202-207)

```java
        } else if (tx.state() == State.PINNED) {
            // 5. Pinned: ship normally, never a valve (B4). A wire-failed
            //    downgrade (BurstSender UNSENDABLE) carries its note into the
            //    journal so a refused burst is visible in one read.
            KnockbackVector carried = tx.carried();
            tx.adopted();
            record(tx, carried, false, tx.deliveryNote());
            directive = new Directive(Directive.Action.SHIP, carried, null);
        }
```

Only the `record` reason argument changes (`null` → `tx.deliveryNote()`); an ordinary pin's note is null, so existing journal output is byte-identical.

### 3d. `kernel/src/main/java/me/vexmc/mental/kernel/model/JournalEntry.java` — javadoc only

Extend the `suppressReason` param doc: "…or null. A SHIPPED pinned entry may carry the {@code wire-failed} note — the pre-send burst the wire refused; the knock still shipped once via the velocity event." No shape change.

### 3e. `core/src/main/java/me/vexmc/mental/v5/rim/BurstSender.java` — NO change

The `Outcome` enum, the catch-all, and the javadoc contract (:22-23) already say exactly what the caller now does. Do not touch it.

## 4. Threading analysis

All `HitTransaction` mutations in `plan()` (planned/preSent/pinned/pinnedWireFailed, including the new `deliveryNote` write) happen on the registering netty thread strictly BEFORE `submitFromWire`'s `AtomicReference.set` — the identical publication edge `carried`/`wireCarried` already rely on; the session thread reads them only after `wireSlot.getAndSet` (drainWire), so `deliveryNote` is safely published. The reorder moves the ship a few statements earlier on the same thread — no new crossings, no live-entity reads added. `DeliveryDesk` remains single-writer: the branch-5 change runs on the victim's owning thread inside `resolve`, and the desk stays the sole journal writer (the netty thread never writes a journal entry — that is WHY the note rides the tx). DeliveryDesk/DirectiveExecutor/DeskRouter/ValveListener control flow is untouched: a PINNED resolve already returns `SHIP` with a null arm payload, so no valve arms and the genuine ENTITY_VELOCITY ships (verified against DeskRouter.sinkFor / DirectiveExecutor).

## 5. Era / zero-touch analysis

- Fast-path or pre-send-feedback disabled: `plan()` returns at :303/:334-336 before any of this code — zero-touch holds unchanged.
- Successful ship (the overwhelmingly common case): identical state sequence (`planned → preSent` / `planned → pinned`), identical journal (reason null), identical valve arming — byte-identical to today. The only behavioral delta is on a FAILED ship, which today is a broken state (total silent knock loss).
- The new failure behavior is era-correct: the era-moment vector (computed at registration, in-order) ships exactly once through the genuine `PlayerVelocityEvent`/tracker packet — the same timing vanilla always had (era servers had no pre-send at all). No new config knobs; `parse(empty)==LEGACY_17` untouched; no client-side technique contract involved.

## 6. Tests

Fixture vector everywhere: the existing `VECTOR = (0.9, 0.4608, 0.0)`. Arithmetic (state in test comments): horizontal 0.9 = era base 0.4 + sprint extra 0.5; vertical 0.4608 = grounded-equilibrium vy −0.0784 × vertical friction 0.5 + base 0.4 + bonus 0.1 = −0.0392 + 0.5 = 0.4608.

### `kernel/src/test/java/me/vexmc/mental/kernel/delivery/HitTransactionTest.java` (add)
- `pinnedWireFailedCarriesTheNoteAndIsNeverWireCarried`: fresh → `planned()` → `pinnedWireFailed(VECTOR)`; assert state `PINNED`, `carried()==VECTOR`, `wireCarried()==false`, `deliveryNote()` equals `HitTransaction.WIRE_FAILED` (== `"wire-failed"`).
- `ordinaryPinnedCarriesNoDeliveryNote`: `planned()` → `pinned(VECTOR)`; assert `deliveryNote()==null` (pins the byte-identical default).
- `pinnedWireFailedFromRegisteredIsIllegal`: fresh tx, `assertThrows(IllegalStateException, () -> tx.pinnedWireFailed(VECTOR))`, message contains `"REGISTERED"` and `"PINNED"`.

### `kernel/src/test/java/me/vexmc/mental/kernel/delivery/DeliveryDeskTest.java` (add)
- `wireFailedPinnedResolvesAsShipWithoutValveAndJournalsTheDowngrade`: tx = `ctx(1, Melee, 0)` → `planned()` → `pinnedWireFailed(VECTOR)`; `desk.submitFromWire(tx)` (the real netty entry); `desk.awaitVelocityEvent(tx)`; `resolve(0.9, 0.4608, 0.0)` → assert `Action.SHIP` (NOT `SHIP_AND_ARM_VALVE`), `directive.arm()==null` ("a wire-failed pin never arms a valve — the authoritative ENTITY_VELOCITY is the victim's only copy"), `directive.ship()==VECTOR`; journal size 1 with `shipped()==VECTOR`, `wireCarried()==false`, `suppressReason()=="wire-failed"`; tx state `RECORDED`.
- In the existing `pinnedShipsWithoutAValve` (:144-160) add one assertion: `assertNull(entry.suppressReason(), "an ordinary pin journals no note")`.

### `core/src/test/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationPreSendOutcomeTest.java` (NEW; pattern: HitRegistrationPacingTest — plain JUnit, pure seam)
Context helper: same `HitContext` fixture as `HitTransactionTest.context()` (HitContext/HitId/HitSource/SprintVerdict/TickStamp are kernel types on core's test classpath; referencing `BurstSender.Outcome` alone never initializes BurstSender's PE imports, and PE is on the core test classpath regardless — PacketTapStateTest precedent).
- `deliveredOutcomeCommitsPreSent`: `commitPreSendState(tx, VECTOR, true, true, Outcome.DELIVERED)` → `PRE_SENT`, `wireCarried()==true`, `carried()==VECTOR`, `deliveryNote()==null`.
- `unsendableOutcomeDowngradesToPinnedWireFailedAndNeverArmsAValve` (the brief's mandated case, end-to-end): `commitPreSendState(tx, VECTOR, true, true, Outcome.UNSENDABLE)` → assert `PINNED`, `wireCarried()==false`, `deliveryNote()=="wire-failed"`; then feed it through a real `DeliveryDesk(42, fixedClock, 4)`: `submitFromWire(tx)`, `awaitVelocityEvent(tx)`, `resolve(0.9, 0.4608, 0.0)` → `Action.SHIP`, `arm()==null` (no valve ever armed), `ship()==VECTOR` (the velocity event ships the carried era vector), journal `suppressReason()=="wire-failed"`, `wireCarried()==false`.
- `nullOutcomeOnAWiredVictimIsAlsoWireFailed` (defensive — unreachable in plan but the pure seam must never phantom-PRE_SENT): `commitPreSendState(tx, VECTOR, true, true, null)` → `PINNED`, note `"wire-failed"`.
- `noWireCommitsAPlainPin`: `commitPreSendState(tx, VECTOR, true, false, null)` → `PINNED`, `deliveryNote()==null` (byte-identical to the pre-fix connectionless path).
- `hurtOnlyPlanCommitsNothing`: `commitPreSendState(tx, null, false, true, Outcome.DELIVERED)` → state stays `REGISTERED`.

### Existing pins that must NOT change
`DeliveryDeskTest.preSentUnmodifiedShipsAndArmsTheValve`, the four canonical end-to-end pins (`canonicalKnockbackPinsShipThroughTheDeskPath`), `sweepNeverTimeDropsAConnectedVictimsMelee`, all existing `HitTransactionTest` transitions, `HitRegistrationPacingTest` (admitVelocityPreSend untouched), `FeedbackGateTest`. None of their expected values move.

## 7. Verification

`./gradlew build` — all unit tests green, japicmp green (kernel changes are purely additive: `WIRE_FAILED`, `pinnedWireFailed`, `deliveryNote()`), kernel-Bukkit-free assertion green (new kernel code uses only existing kernel types). No tester-suite addition: the UNSENDABLE-with-live-wire path is un-drivable by FakePlayers (they have no PE user, so `victimHasWire` is false at :324-325 and the plan pins BEFORE any ship attempt — live-server-testing skill), so the pure-seam unit chain above is the honest coverage; existing suites (BlockingSuite, FoliaCombatSmoke, KnockbackSuite) act as the no-regression net since they exercise the DELIVERED and connectionless-pin paths. Run `./gradlew integrationTestMatrix` with the batch as usual (nonce+PASS rule).

Commit: `fix(delivery): pin the hit when the pre-send burst fails to ship` with a prose body explaining the phantom-PRE_SENT → valve-eats-only-copy chain and the honor-the-Outcome contract.

## 8. Risks + rollback

- **Partial-burst duplicate**: `ship()` buffers via `writePacket` then `flushPackets()`; a throw between them can leave a buffered velocity a later flush delivers, so a wire-failed pin could rarely produce TWO velocity packets (late pre-send + authoritative). Benign by explicit design ("duplicates are no-op corrections"); today the same partial case delivers ZERO copies — strictly an improvement.
- **~0-1 tick later knock on failed bursts**: the pin ships at velocity-event time instead of pre-send time — the pre-existing, documented PINNED semantics; only affects hits that today lose the knock entirely.
- **Journal note on a shipped entry**: both tester `suppressReason` readers gate on `shipped != null` first and only print the reason on failure — verified no assertion keys on reason-null for shipped entries.
- **Rollback**: single revert of one commit; no config, no schema, no API removal involved.

## Files touched

- `core/src/main/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationUnit.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/delivery/HitTransaction.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/delivery/DeliveryDesk.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/model/JournalEntry.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/delivery/HitTransactionTest.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/delivery/DeliveryDeskTest.java`
- `core/src/test/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationPreSendOutcomeTest.java`

## Cross-lane conflicts (integrator notes)

F4 (desk supersede passthrough) is the hard one: it edits DeliveryDesk.drainWire/resolve and F2 edits resolve branch 5 (one line: record reason null -> tx.deliveryNote()) — same method, overlapping hunks; merge F2's branch-5 line into whatever resolve shape F4 lands. F5+F6 edit HitRegistrationUnit.plan() (the HitContext construction at :328-330 and attacker capture) while F2 restructures plan() :404-433 — same method, textually adjacent but logically disjoint; rebase order matters. F1 edits HitRegistrationUnit.sprintVerdict + KnockbackUnit — same file as F2's plan() edit, different methods (trivial). F9 (journal enrichment) touches JournalEntry and desk record sites: F2 deliberately avoids any JournalEntry shape change (rides the existing suppressReason column + a javadoc line) so it composes with F9 whether or not F9 adds columns; if F9 adds a dedicated note/capture field, the implementer may route tx.deliveryNote() there instead — coordinate the column name. F3 (valve lifecycle) shares the valve-eats-copy theme but touches VelocityValve/ValveListener/DirectiveExecutor/DeskRouter/SessionService.clearStale — no file overlap with F2's edits; F2 reduces the phantom arms F3's lifecycle work reasons about, so land F2 first if sequencing is free.
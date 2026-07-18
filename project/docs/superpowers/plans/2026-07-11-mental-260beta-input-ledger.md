# 2.6.0-beta InputLedger Round Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the standalone InputLedger (the observable sprint-reset ledger), the three StarEnchants-compat fixes, and the sub-1-heart heal threshold — spec: `docs/superpowers/specs/2026-07-11-input-ledger-design.md`.

**Architecture:** The kernel `SprintWire` machine is subsumed into a new `InputLedger` (same package, `kernel.wire`) that adds a synchronized diagnostic event ring, an `eventSeq`/`seq` partition (the existing `seq` IS the reset-seq — `onKeyIntent` already doesn't bump it), the one-credit block door, the `SERVER_FALL` latch failsafe, and starvation counters. Core wiring: the rim feeds new lanes, an always-on `BlockResetTap` replaces the feature-scoped door, the vanilla-mint consume is skipped, and `JournalCapture` grows `resetseq=`/`trail=`/`note=` tokens. SE-compat and heal changes are independent workstreams.

**Tech Stack:** Pure-JDK kernel (Bukkit-free, build-asserted), PacketEvents rim, JUnit hand-computed pins, legacy-lab `measure.js` wire suite.

## Global Constraints

- Kernel stays Bukkit/PacketEvents-free (build-asserted); `kernel.wire` is internal — japicmp gates only `api/`.
- Zero-touch: disabled features do NOTHING; the always-on block door changes nothing unless a sprint hit already consumed an engagement.
- Era-exact defaults: knockback values byte-identical; `parse(empty) == LEGACY_17` untouched; the one-engagement-one-sprint-knock contract preserved exactly.
- Arrival order is era truth: same-tick ATTACK-before-START ships plain (bytecode-pinned 1.8.9 + 1.21.11) — never reorder.
- Threading: D1 appends events on the netty thread; consume arrives from D2; derived state stays one CAS'd immutable `State`; the ring is a small synchronized deque (uncontended).
- Never create a domain by read: `domains.peek(...)`, not `domainFor(...)`, outside the rim (the 2.4.4 domain-poisoning trap).
- Packet types compared by REFERENCE; listeners fire in every connection state.
- `onKeyIntent`-class observations must never bump `seq` (the 2.5.1 consume-veto trap) — new KEY_INPUT ring events bump `eventSeq` only.
- Conventions per `mental-conventions`: records, why-comments with era provenance, conventional commits with prose bodies, commit per task.

---

## Workstream A — the InputLedger (kernel + core). Sequential; one branch.

### Task A1: kernel `InputEvent` + `InputLedger` skeleton (ring + eventSeq + trail)

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/wire/InputEvent.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/wire/InputLedger.java`
- Test: `kernel/src/test/java/me/vexmc/mental/kernel/wire/InputLedgerRingTest.java`

**Interfaces (produced):**
- `record InputEvent(InputEvent.Kind kind, long eventSeq, TickStamp tick, int bits)` with `enum Kind { SPRINT_START, SPRINT_STOP, KEY_INPUT, RELEASE_USE_ITEM, WINDOW_CLOSE, BLOCK_RESET, SEED, ADOPT_TRUE, ADOPT_FALSE, ADOPT_BLOCKED, SERVER_FALL, CONSUME, ATTACK }`. `bits` carries the raw PLAYER_INPUT byte for KEY_INPUT, else 0.
- `InputLedger(TickClock clock)`; `List<InputEvent> trail()` (synchronized snapshot copy, newest last); `long eventSeq()`; ring capacity 128 (drop oldest).
- Private `void ring(Kind kind, int bits)` — appends with `eventSeq++`, called from every mutator added in later tasks.

Steps: write failing `InputLedgerRingTest` (append via a package-visible test hook or the first real mutators from A2 — if A2 not yet merged, test `ring` through a package-private `recordForTest`), assert capacity eviction, ordering, `eventSeq` monotonicity across kinds; implement; `./gradlew :kernel:test --tests '*InputLedgerRing*'`; commit `feat(kernel): the InputLedger event ring — the diagnostic spine of the wire`.

### Task A2: port the SprintWire machine into InputLedger, byte-identical

**Files:**
- Modify: `kernel/src/main/java/me/vexmc/mental/kernel/wire/InputLedger.java`
- Delete (end of A5, after no references remain): `kernel/src/main/java/me/vexmc/mental/kernel/wire/SprintWire.java`
- Test: re-target `kernel/src/test/java/me/vexmc/mental/kernel/wire/SprintWireTest.java` → `InputLedgerSprintTest.java` (same pins, same expectations); Create: `InputLedgerReplayTest.java` (the S1–S5 diagnosis-harness scenarios as permanent pins: STOP→START re-arms at every interleaving vs the deferred clear; held-W no-gesture stays consumed; movement-packet reconcile with 1-tick-lagged view; PLAYER_INPUT noise never vetoes the consume).

**Semantics (copied EXACTLY from SprintWire — every transition, guard, and javadoc provenance):** the `State` record (`seen, sprinting, armed, clientSprinting, blockCredit*, clearedAt, lastWrite, seq, keyIntent, lastSprintingAt` — `blockCredit` replaces `blockReset` in A3; keep `blockReset` in this task), `onSprintStart`/`onSprintStop` (bump seq, reset clearedAt, ring SPRINT_START/STOP), `onKeyIntent(boolean, int rawBits)` (bumps NOTHING but rings KEY_INPUT with the raw byte — eventSeq only), `onServerClear(long)`/`onServerClear(TickStamp)`/deprecated no-arg (ring CONSUME when applied, NOT when no-opped), `onBlockSprintReset` (ring BLOCK_RESET), `onBlockReleased` (ring RELEASE_USE_ITEM only when it drops a held reset), `reconcile` (ring SEED/ADOPT_TRUE/ADOPT_FALSE on applied writes and ADOPT_BLOCKED on the latch-blocked branch — the blocked branch still returns state unchanged), `verdictAt`, `blockReArmEligible`, `clientSprinting()`, `ERA_BLOCKHIT_RECENCY_TICKS`. `onAttack()` (new): rings ATTACK, mutates nothing.

Steps: copy transitions; run the re-targeted pins RED against the skeleton, implement, GREEN (`./gradlew :kernel:test`); commit `feat(kernel): InputLedger subsumes the SprintWire machine — transitions byte-identical, every write ringed`.

### Task A3: the one-credit block door (owner semantics — supersedes the held chain)

**Files:** Modify `InputLedger.java`; Test: `InputLedgerBlockCreditTest.java`.

Replace sticky `blockReset` with `blockCredit`:
- `onBlockSprintReset()` raises `blockCredit=true` (plus the existing arm/seq/clearedAt effects).
- **An APPLIED `onServerClear(...)` drops `blockCredit`** (the credit is spent by the accepted bonus hit — one hit per reset; era analogue: vanilla consumed inside the successful bonus attack). A no-opped clear (seq/stamp guard) leaves it.
- `onBlockReleased()` drops it (the credit lives only while the block key is down — owner: "WHILE the block key is down for that specific hit").
- `verdictAt`: `blockHeldReset = blockCredit && (clientSprinting || recentlySprintedWithKeyHeld)` — unchanged gate, non-sticky flag.
- Deferred-consume sliver (document in the javadoc, pin it): a second hit registering inside the 1–2-tick window before the first hit's consume lands still reads the credit — the same inherent sliver the engagement seq guard documents. Pin: reset→hit1 verdict fresh→hit2 verdict fresh→consume(hit1 seq) applies→hit3 verdict NOT fresh.

Pins: full cycle (reset→fresh hit→consume→next hit plain→release→re-block→fresh again); release-without-hit drops credit; STOP drops the gate.
Commit: `feat(kernel): the one-credit block door — one sprint hit per block-hit reset`.

### Task A4: SERVER_FALL failsafe + starvation counters

**Files:** Modify `InputLedger.java`; Test: `InputLedgerFailsafeTest.java`.

- `reconcile`: when `s.sprinting()==false && serverSprinting==false` there is no disagreement today; ADD the falling-edge detector one level up: track `lastServerSprinting` (Boolean, in State) updated every reconcile call; when it transitions true→false while `clearedAt.known()`, close the latch (`clearedAt=NO_TICK`), ring SERVER_FALL, do NOT arm/bump seq beyond the standard adopt path. (A client STOP provably reached vanilla; era-sound as a latch closer only.)
- Counters in State: `startsSeen` (int, bumped by onSprintStart), exposed `boolean starved()` = a consume has applied while `startsSeen==0`.
- Pins: latch closed by server falling edge with no wire packets; `starved()` true for seed→hit→consume with zero STARTs, false once any START arrives.

Commit: `feat(kernel): the latch failsafe and the starvation detector`.

### Task A5: rim + Domain wiring (core)

**Files:**
- Modify: `core/src/main/java/me/vexmc/mental/v5/rim/ConnectionDomains.java` (Domain holds `InputLedger`; keep the accessor NAME `sprint()` returning `InputLedger` — call-site churn stays nil)
- Modify: `core/src/main/java/me/vexmc/mental/v5/rim/PacketTap.java`
- Modify: `core/src/main/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationUnit.java` (`sprintVerdict` adds `domain.sprint().onAttack()` beside the peek)
- Modify: `core/src/main/java/me/vexmc/mental/v5/feature/damage/SwordBlockingUnit.java` (its `BlockReleaseListener` moves out — see below; `resetSprintForBlock` stays until A6 rewires it)
- Test: extend `core/src/test/java/.../PacketTapStateTest.java` (state-safety pins unchanged + the new lanes never downcast pre-Play)

PacketTap changes (reference-compares as ever):
- `onPlayerInput`: pass the RAW input byte through — `sprint().onKeyIntent(packet.isSprint(), rawBits)` where rawBits packs the 7 booleans (forward|backward|left|right|jump|shift|sprint as bits 0x01..0x40, matching the wire codec).
- New lane `CLOSE_WINDOW` → `sprint().onWindowClose()` (rings WINDOW_CLOSE, mutates nothing — evidence only; add the trivial method in kernel).
- New lane `RELEASE_USE_ITEM` (PacketType.Play.Client.PLAYER_DIGGING with `DiggingAction.RELEASE_USE_ITEM`) → `sprint().onBlockReleased()` — this REPLACES SwordBlockingUnit's feature-scoped `BlockReleaseListener` (delete it in A6); the kernel method is already a no-op when no credit is held, so always-on observation stays zero-touch.

Steps: wire, extend PacketTapStateTest, `./gradlew :core:test`, commit `feat(rim): the ledger lanes — key bits, window close, always-on block release`.

### Task A6: the always-on `BlockResetTap` + SwordBlockingUnit rewire

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/knockback/BlockResetTap.java` (Bukkit listener, registered ALWAYS-ON in `MentalPluginV5` next to the PacketTap registration at `MentalPluginV5.java:316`)
- Modify: `SwordBlockingUnit.java` — `resetSprintForBlock` and its interact routing DELETE from the unit; the unit CONTRIBUTES its decorated-sword predicate to the tap's item gate on assemble and retracts on close.
- Test: `core/src/test/java/.../BlockResetTapTest.java`

Behavior (moved verbatim from `resetSprintForBlock`, `SwordBlockingUnit.java:368-397`): on the born-cancelled `RIGHT_CLICK_AIR` `PlayerInteractEvent` (`ignoreCancelled=false`, self-filter `useItemInHand() != DENY`) or `PlayerInteractEntityEvent`, when the held item **can block** — shield (all versions), a BLOCKS_ATTACKS-component item (1.21.5+ capability probe), or the SWORD_BLOCKING-contributed predicate (a decorated sword while that feature is on) — and `domains.has(id)` (non-creating) and `blockReArmEligible()`: call `sprint().onBlockSprintReset()`, `domain.resetModel().onBlockRaise()`, and the `setSprinting(true)` re-sync. The item gate is a `volatile Predicate<ItemStack>` slot defaulting to shield/component; SWORD_BLOCKING's assemble ORs in its decorated-sword test, close restores the default.
Zero-touch argument (javadoc): without a prior consumed engagement the re-arm writes are idempotent no-ops on an armed wire; a non-blockable item never fires; packetless players are peeked out.

Commit: `feat(knockback): the block-hit reset door goes always-on — knockback semantics, not a damage rule`.

### Task A7: the vanilla-mint consume skip

**Files:** Modify `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java:485-501`; Test: extend the KnockbackUnit obligation pins.

In `applyAttackerObligations`: keep the `verdict.fromWire()` seq-guarded clear; DELETE the else-branch stamp-guarded `wire.onServerClear(verdict.at())`. A verdict without wire provenance (view fallback, Vanilla mints — `DamageRouter.mintVanilla` at `DamageRouter.java:88-93` stamps `fresh=null`, `NO_WIRE_SEQ`) no longer spends the wire engagement (spec §1.5: no wire verdict, no wire consume; the stamp guard was same-tick-blind and could retro-eat a re-arm). The ×0.6 self-slow and combo bookkeeping are untouched.
Commit: `fix(knockback): non-wire verdicts never spend the wire engagement`.

### Task A8: journal observability

**Files:** Modify `kernel/.../model/JournalEntry.java` (Capture unchanged), `core/.../debug/JournalCapture.java`, `core/.../MentalPluginV5.java` (inject `ConnectionDomains` into JournalCapture); Test: `JournalCaptureTest` exact-line pins.

- `resetseq=`: print `context.sprint() == null || !fromWire() ? "-" : wireSeq`.
- `trail=`: formatted ONLY when the channel is active (inside the existing `debug.log(() -> ...)` supplier — zero-cost-off preserved): read the ATTACKER's ledger via `domains.peek(context.attackerId())`, render the last 6 events as `KIND±Δtick` relative to `entry.at()` (e.g. `trail=STOP-3,START-1,ATTACK+0,CONSUME+1`), `-` when no domain.
- `note=`: `start-trailed` when the trail shows a SPRINT_START with the same tick as, and later eventSeq than, the hit's ATTACK; `starved` when the ledger reports `starved()`; else `-`.
- Extend the line format pin in the existing JournalCapture unit test with a fake domains/ledger.
- Boot report (spec §1.8): one line in the existing boot-report seam naming the active input lanes per tier (`input lanes: entity-action, use-item, window` + `, player-input` on 1.21.2+ — the same version probe the rim uses).

Commit: `feat(journal): resetseq/trail/note — every sprint verdict self-explaining`.

### Task A9: docs

Modify `.claude/skills/netty-fast-path/SKILL.md` (the wire section: reconcile runs on the netty thread per movement packet — fix the owning-thread drift; rewrite "the wire sprint view" around the InputLedger, one-credit block door, SERVER_FALL, trail tokens) and `docs/fast-path.md` if it names SprintWire. Commit `docs: the ledger round — skill drift fixed`.

---

## Workstream B — StarEnchants compat (Mental side). After A merges (same files as A7's neighborhood).

### Task B1: phantom-knock reconcile

**Files:** Modify `KnockbackUnit.java:379-395` (`onMeleeCancelled`), `HitRegistrationUnit.java:878-909` (`damageWithSlot`), plus a small `CorrectiveVelocity` helper in `core/.../feature/delivery/`; Test: unit pins for the pure decision (`foreignWindowReject(...)`) + FeedbackCoherenceSuite cases.

- Pure decision (mirror of `adoptBoundary`, `HitRegistrationUnit.java:683-690`): `static boolean foreignWindowReject(boolean committedKnock, int noDamageTicks, int maxNoDamageTicks, double amount, double lastDamage)` = `committedKnock && noDamageTicks > max/2 + 1 && amount <= lastDamage` — the vanilla-guaranteed-rejection band ABOVE the adoption sliver.
- `damageWithSlot`: before `victim.damage`, when `foreignWindowReject(...)`: `retract(victimUuid, tx)` (`HitRegistrationUnit.java:911-916` exists), ship the corrective velocity, `tx.presend(tx.presend() + "+foreign-window-reject")` (the `boundaryAdopted` composition idiom at :701-703), still call `victim.damage` (vanilla rejects silently; era-correct).
- `onMeleeCancelled` (withdraw already exists): add — when `committedKnock(tx.state())`, ship the corrective velocity and stamp `tx.presend(... + "+cancelled-by-plugin")`.
- `CorrectiveVelocity.ship(Player victim)`: on the victim's region thread (both call sites are), send one ENTITY_VELOCITY carrying `victim.getVelocity()` via the PacketEvents user (`getUser` null ⇒ skip — synthetic victims); the valve only arms at DeskRouter confirm, which never happened for these paths, so the corrective packet passes unconsumed (verify with a valve unit pin).

Commit: `fix(delivery): reconcile the phantom knock — cancelled and foreign-window-rejected committed hits correct the client`.

### Task B2: 0-damage coherence

**Files:** Modify `core/.../feedback/HitFeedbackListener.java` (guards at the `onHit` head — the `finalDamage <= 0.0` return currently precedes `marks.mark(...)`); Test: extend the listener unit test.

Hoist the `marks.mark(...)` arm ABOVE the `finalDamage <= 0.0` return and let the custom hit sound voice for 0-damage connected hits (vanilla-snowball semantics); the low-health layer and indicator path stay behind the damage guard (`DamageIndicatorsListener` untouched — its 0-guard is correct). Pin: 0-damage ENTITY_ATTACK arms the mark and voices; negative/cancelled still skip.
Commit: `fix(feedback): 0-damage connected hits keep the custom voice — the Blacksmith proc incoherence`.

---

## Workstream C — heal threshold (independent, parallel-safe)

### Task C1: `HealFold` MIN_SHIP_HEALTH

**Files:** Modify `core/.../feedback/HealFold.java:41-48`; Tests: rewrite `HealFoldTest.dripAggregatesToTenPerWindow`, add sub-threshold pins; extend `tester/.../suite/FeedbackCoherenceSuite.java` heal cases (sub-heart attributed heal ⇒ NO heal decision; two +1.0 heals in one window ⇒ exactly one decision).

```java
/** No indicator under one heart (2.0 pts, owner-directed): sub-threshold sums are
 *  NOT consumed — they keep accumulating, so trickle regen that crosses a heart
 *  still ships once, summed. Strict <: a full-heart heal shows. */
static final double MIN_SHIP_HEALTH = 2.0;
...
if (sum < MIN_SHIP_HEALTH) {
    return 0.0;
}
```

Commit: `feat(feedback): heal indicators start at one heart — sub-heart trickle accumulates silently`.

---

## Workstream D — StarEnchants (the OTHER repo, parallel-safe)

### Task D1: fold same-hit damage; attribute separate procs

**Repo:** `/Users/owengregson/Documents/StarEnchants` — branch `fix/mental-compat-damage-fold` off its main line (NOT `feat/pets-system`); follow SE conventions (ADR trailer style per recent commits).

- Victim-targeted zero-delay DAMAGE on the current event joins the DamageFold (`addFlatDamage`) instead of the bare `target.damage(amount)` (`se/engine/src/engine/sink/DispatchSinkBase.java:363-376`; SE's own §6.1 principle) — same-hit bonus damage must never arm a second immunity window.
- Genuinely separate procs — `lightningAndDamage`'s `target.damage(n)` (`DispatchSinkBase.java:827-843`), WAIT-delayed DoT ticks (bleed), reflects (`CombatDispatch.java:246-269`) — route through `damage(amount, attacker)` where an attacker entity is in scope so downstream plugins see an attributed event; keep bare damage only where no attacker exists.
- SE test suite green per its own gate; do not touch dodge/immune cancel behavior.

Commit in SE conventions; do NOT release — hand back the branch.

---

## Workstream E — the live wire suite (legacy-lab, parallel-safe)

### Task E1: four scenario additions + the ledger assertion runner

**Files:** Modify `legacy-lab/harness/measure.js` (all scenario-file-local; primitives exist at :220-291):
1. `double-sprint-fastwtap-attackfirst`: clone `double-sprint-fastwtap` (:739-747) with `attacker.attack()` BEFORE `setSprint(true)` in the same flush; run with `CLIENT_SPRINT_DROP=0`.
2. `double-sprint-stap`: `setSprint(false)` + `player_input` backward-bit set, N `sleepTicks`, backward clear + `setSprint(true)`, hit.
3. `bot.releaseUseItem()` (`player_action`/`block_dig` status `release_use_item`) + `blockhit-one-credit`: USE_ITEM hold → attack (expect fresh) → attack (expect plain) → release → re-USE_ITEM → attack (expect fresh).
4. `gui-reset`: `player_input` all-false + `close_window` windowId 0, then re-press pattern, hit.
Assertions ride the JOURNAL capture (grep `hit=` lines for `sprint=`/`fresh=`/`trail=`/`note=` per the recipe: `config.yml` debug.enabled+journal, rcon staging, `TARGET_NAME` exported). On modern, write entity_action by NAME (the 1.21.6 enum compaction).
Expected matrix: wtap-offset ⇒ hit2 `sprint=t fresh=t`; fastwtap START-first ⇒ fresh; ATTACK-first ⇒ `sprint=f note=start-trailed`; no-reset control ⇒ `sprint=t fresh=f` first, then post-consume plain; blockhit cycle per task 3.

Commit: `test(lab): the ledger wire suite — every reset form asserted on the real protocol`.

---

## Verification order

1. `./gradlew build` (kernel/core pins, D-8/D-9 gates, japicmp).
2. Wire suite at 1.21.11 (`node measure.js 1.21.11 25999 <scenario>` per E1, staged current jar in `legacy-lab/srv-mental` — restage BOTH stale plugins there first).
3. `./gradlew integrationTestMatrix` or remote CI (owner-accepted release gate); nonce+PASS honesty rule.
4. Release 2.6.0-beta; SE branch handed to its own gate.

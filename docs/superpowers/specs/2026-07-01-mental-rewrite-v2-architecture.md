# Mental rewrite v2 — architecture decision document

**Status: APPROVED 2026-07-01 — the owner approved Candidate B (Two-Realm Kernel) by
explicit written selection, and chose to KEEP bStats (overriding the §6.1 default;
it becomes a config-gated descriptor). The mandate's hard human checkpoint is
satisfied; spec → plan → build may proceed.**

This document is the first deliverable of the 2026-07-01 rewrite mandate
(`docs/` prompt, commit 2f0fccb). It presents three candidate architectures, evaluates
them against the mandate's rubric (R1–R13), invariants (§4), and bug-classes (B1–B14),
recommends one, re-justifies every reused asset, and resolves the version/branding and
coexistence-stance decisions. Section numbers prefixed "mandate §" refer to the mandate;
bare "§" refers to this document.

---

## 1. Method and baseline

The design below is grounded in a fresh map of the current codebase (bootstrap wiring,
the delivery chain, the module framework, config, the fast path, compensation, OcmGate,
purity audit of the math kernel, scheduling backends, and the Gradle graph) and a
distillation of the prior rewrite's design docs, the Folia audit coda, the OCM ground
truth, and all ten project skills. The load-bearing baseline facts:

- **The correlation between the netty pre-send and the authoritative pass is
  `(victim UUID, attacker UUID, exact formula-vector equality)` plus wall-clock expiry
  (100 ms Paper / 300 ms Folia).** There is no hit identity anywhere in the system.
  Every band-aid in mandate §2.1 exists to compensate for that absence.
- **The math kernel is already clean.** `KnockbackEngine`, `MotionMath`,
  `ReachValidator`, `FeedbackBurst`, `DefenceMath`, `ArmourDurabilityMath`,
  `ToolDurabilityMath`, `RegenMath`, `SwordBlockReduction`, `EraReach`,
  `PotionDurations`, `GoldenAppleEffects`, `RodLaunchMath` have zero Bukkit imports and
  zero state. `VictimMotion`'s pure statics (`decay`, `decayOnce`,
  `groundedEquilibrium`) are extractable. The disease is entirely in the glue.
- **The prior design's correct principles failed where they were style notes, not
  structure.** "Netty reads only frozen snapshots" was right and still shipped the
  Folia downgrade regression, because nothing *prevented* a live read. The lesson the
  new architecture must encode: a rule that matters must be unrepresentable to violate,
  not documented.
- **Per-player combat state currently has up to four writers on three threads**
  (ledger: knockback record, jump bookkeeping, decay task, netty packet feed), and the
  sprint model is five concurrent collections plus a thread-local plus a deferred-clear
  dance. Single-writer ownership is the single highest-leverage structural change.
- **The wire sprint view is naturally connection-thread-local.** Entity-action packets
  and the ATTACK packet for one attacker arrive in order on that attacker's own netty
  thread. The current design shares this state globally (hence the races); it never
  needed to be shared at registration time.
- **Per-tick snapshot publication makes the era boundary read free.** The
  `currentExcludingTick` machinery ("read the residual as of the END of the previous
  tick") exists because the ledger is written mid-tick from netty and read mid-tick
  from netty. If netty only ever reads a snapshot published at the victim's previous
  tick end, the mandate §4.3 exclusion holds *by construction* and the tick-recency
  guard becomes a snapshot-freshness check.
- The support matrix lives in three places and the version→JDK mapping in two that
  disagree on 1.20.0–1.20.4; the version literal itself is single-homed but
  semantically broken against the ancestor StrikeSync v4.x tags.

---

## 2. The five hard problems every candidate must answer

Distilled from R1–R13 and B1–B14, a candidate is defined by its answers to:

1. **Identity** — how a hit is named from packet arrival through pre-send,
   authoritative apply, third-party modification, and ledger record (R3, B3, B4).
2. **Confinement** — how "netty must not touch live state" and "one writer per state
   unit" are *proven*, not audited (R2, R4, B1, B2).
3. **Ownership** — how exactly one place authors velocity, damage, and feedback, and
   how peers (OCM, other plugins) are arbitrated (R1, R9, B6, B14).
4. **Lifecycle** — how features (Bukkit + packet + tasks) enable, disable, and fail as
   transactions, and how they are enumerated for config/GUI/coexistence (R6, R7, R8,
   B8, B9).
5. **Observability** — how "what did we actually ship to the wire" is one seam the
   plugin, the mirror, and the tests all read (R13, B7).

---

## 3. Candidate architectures

### Candidate A — "Region-Actor Sessions" (evolutionary, runtime-enforced)

**Core idea.** All per-player combat state (motion ledger, pending deliveries, sprint
freshness, kinematics) moves into a single `CombatSession` object per player, owned by
that player's region thread. A per-player `repeatOn(player, 1, 1)` task is the only
code that mutates the session; netty threads communicate with it exclusively by
appending immutable messages (movement observation, sprint action, hit intent) to a
per-player MPSC inbox the session drains at tick start. The session publishes one
immutable `PlayerView` snapshot per tick (a single volatile write) that netty code
reads. Hits are identified by a `HitId` (global sequence) minted at packet arrival and
threaded through pre-send, pending, and the authoritative pass.

**Confinement mechanism:** runtime. Session methods assert
`Thread.currentThread() == ownerThread`; netty-visible types are read-only interfaces
over immutable records. Violations are loud fail-fast assertions (never swallowed), and
a CI architecture test greps the packet-layer classes against the netty-safe accessor
allow-list from `netty-fast-path`.

**Ownership:** a `DeliveryDesk` inside the session is the sole `PlayerVelocityEvent`
writer and the sole ledger recorder; features are pure vector computers (as today) but
submit `(HitId, vector)` rather than `(victim, vector)`.

**Lifecycle:** a `Feature` base class acquires every resource (Bukkit listener, packet
listener, task) through a `Scope` that closes them all, individually isolated, on any
exit including a thrown enable.

**Strengths.** Smallest conceptual jump from the current codebase; every reused asset
drops in with minimal adaptation; delivery risk is lowest; the inbox/single-writer
model alone eliminates B2 and most of B1's blast radius.

**Weaknesses.** R2 is satisfied by assertion, not construction — a new contributor can
still *write* a live read in the packet layer and only a test or a Folia runtime
assert catches it. The Bukkit/netty boundary remains a convention inside one Gradle
module. The kernel/orchestration seam (R11) stays a package convention, exactly the
blur that let the current glue metastasize.

**Migration cost:** low. **Residual risk:** the same class of drift that degraded the
2026-06-04 rewrite, slowed but not fenced.

---

### Candidate B — "Two-Realm Kernel" (compile-time partition + hit transactions)

**Core idea.** Express the thread boundary in the *build graph*. A new Gradle module
`kernel` has **no Bukkit dependency at all — not even `compileOnly`**. It contains the
era math (lifted verbatim), the motion/ledger fold logic, all frozen value types
(`FrozenPlayer`, `WorldRules`, `TickStamp`, `KinematicState`), the hit-transaction
model, the wire-side decision logic (hit validation, feedback plan, sprint wire view,
ground-transition FSM, compensation query, reach geometry), and the SPI ports
(`TickClock`, `SignalSink`, `PacketPort`). Code that runs on a netty thread is kernel
code by policy; the only core-side netty code is a thin parse rim (PacketEvents wrapper
→ kernel record, kernel decision → PacketEvents write) that an architecture test pins
to an import allow-list. Live-entity access from the netty realm is a **compile
error** — `org.bukkit` does not resolve there.

The same boundary is the R11 seam: `kernel` *is* the pure era-physics kernel plus the
decision logic, and `core` is the delivery adapter. One Gradle edge does double duty.

**Identity: the `HitTransaction`.** Minted at origin (netty packet arrival, or at the
damage event for vanilla-originated hits, or by a feature for synthetic hits like the
rod pull) with an explicit typed source token (`MELEE`, `ROD_PULL`, `ARROW`,
`THROWN(kind)`, `VANILLA_EVENT`, …) and a monotonically increasing id. It carries the
compute-once `HitContext` (frozen snapshots of both parties, sprint+freshness verdict
stamped from the attacker's connection-local wire view, resolved profile, OCM ownership
verdicts, latency answer, computed vector) — R5's "one decision object" — and a state
machine `REGISTERED → PLANNED → {PRE_SENT | PINNED} → RESOLVED{ADOPTED, SUPPRESSED(reason),
RETRACTED, DROPPED(reason)} → RECORDED`. Every transition happens on the victim's
region thread except the mint and plan; **every transaction must reach a terminal
state through an explicit path** (damage-task completion, retire callback, cancel
handler) — there is no expiry timer anywhere, and withdrawal is keyed by id
(no all-pendings-for-victim operation exists). Session teardown asserts no live
transactions leak.

**Confinement / single-writer:** three single-writer domains, each with exactly one
owning thread and immutable messages between them:

1. **Connection domain** (per player, owned by that player's netty thread): the wire
   sprint view and arrival-order stamps. Entity-action, movement, and ATTACK packets
   for one player arrive in program order on one thread — this state never needed
   sharing. The attack verdict is computed here and stamped *into the transaction*
   (no UUID-keyed side map, no TTL).
2. **Session domain** (per player, owned by the player's region thread): motion
   ledger, pending transactions, kinematics, feedback pacing, ephemeral decorations.
   Fed by the per-player inbox; publishes the per-tick frozen `PlayerView`.
3. **Global domain** (config snapshot, platform profile, tick clock, feature
   reconciler): written on the global region thread, read anywhere by reference.

The victim's ground FSM runs on the victim's connection thread (it owns the packet
sequence and reads the typed `TickClock`) and emits ledger events (jump stamp @tick,
landing @tick) into the victim's inbox; the region-side fold applies them. Netty
pre-send reads only the published `PlayerView` — which, being end-of-previous-tick by
construction, *is* the mandate §4.3 boundary read; the ≤4-tick recency guard becomes
"snapshot older than 4 ticks → no exclusion / decline fast path", preserving the pinned
semantics.

**Ownership:** one `DeliveryDesk` per session. Features never apply; they submit
`(HitTransaction, vector)` and the desk arbitrates last-submitter-wins per victim per
tick (mandate §4.5). The desk is the sole `PlayerVelocityEvent` writer; apply-and-record
is one method: ship the final post-third-party value, record it into the ledger under
the transaction id, arm the wire valve, and expose the same decision object to the
1.20.6+ `EntityKnockbackEvent` mirror — divergence is unrepresentable because there is
only one object. Duplicate suppression stops being a heuristic: for a `PRE_SENT`
transaction whose event velocity was not third-party-modified, the desk arms a
consume-once valve keyed by (connection, entityId, **exact short-quantized wire
payload**) that the synchronously-following packet write consumes — no epsilon, no
deadline; the valve dies with the transaction. Damage has the same shape: one
`DamageShaper` is the sole EDBEE modifier; damage features contribute pure components
composed in fixed legacy order.

**Coexistence:** a total `MechanicToken` enum (every era mechanic Mental can restore,
not 6) and a central `Arbiter`. Rule handlers are registered only via
`scope.rule(token, decider, handler)` — a handler without a token cannot be effectful.
The historical six keep BOUND/CONFIG per-decider resolution frozen into snapshots; the
ported rules are self-owned but peer-detected: the arbiter knows OCM's config key per
token and warns loudly on double-enable, plus the two mandated startup warnings
(`old-player-knockback` in the default modeset, `playerDelay: 18`).

**Lifecycle & taxonomy:** one `FeatureDescriptor` registry (a sealed enumeration —
typed id, family, display metadata, config schema, mechanic tokens, facet coverage
declaration). Families follow the vanilla combat subsystem each feature owns:
`delivery` (fast path, reach filter, w-tap registration), `knockback` (melee, fishing,
rod, projectile, compensation), `damage` (tool damage, crits, armour, durability,
blocking), `cadence` (attack cooldown, sounds, sweep), `sustain` (regen, gapples,
potions, pearls), `loadout` (offhand, crafting, hitbox). Config parsing, GUI catalog,
zero-touch enumeration, and the arbiter all iterate the descriptors: adding a feature
is one descriptor + one class, compiler-checked. Enable/disable is transactional
through the same `Scope` as Candidate A (Bukkit + PacketEvents + tasks, each close
isolated). B5's complete-contract rule is enforced by the descriptor: a mechanic
declares its facets (server rule / client presentation / fast-path damage /
vanilla-path damage) and provides a handler-or-explicit-none per facet; a unit test
enumerates descriptors and fails on silent gaps.

**Config:** the `Snapshot` is a typed heterogeneous map keyed by descriptors
(`snapshot.get(KNOCKBACK)` returns that feature's settings record) — no 26-arg
constructor, no string quadruplication. Operations capture the snapshot once at entry;
hit-relevant settings are frozen into the `HitContext`. Machine writes (GUI toggles) go
to a machine-owned overlay file layered at parse time, so the commented, human-owned
YAML is never re-serialized. Migration keys on an explicit `config-version` chain.

**Compensation:** a stateless per-hit query. The session refreshes the kinematic
snapshot (position, ground distance, ledger vertical) every tick as part of its own
tick step; RTT updates on pong arrival (Play PING/PONG transport behind a `Probe`
port). `Compensation.verticalFor(hitContext)` is called synchronously by whichever
path owns the hit — N combo hits get N answers; no slot, no TTL, no probe-cadence
coupling. `MotionMath`/`GroundProbe` are re-derived clean-room from the compendium's
decompile citations to clear the GPL lineage.

**Observability:** the desk writes every terminal resolution into a bounded per-victim
**delivery journal** (id, source, final shipped vector, wire-vs-event, tick, suppress
reason). The tester asserts against the journal — the exact seam the plugin ships
through — and the journal is what closes B7: a Folia fix is validated against shipped
values, not against whichever Bukkit event a harness happened to read. Because all
mutation is inbox-message-driven, a deterministic unit harness can drive the netty
logic and session drains on test-controlled executors with scripted interleavings —
the deterministic Folia-capable delivery test the gate requires — and the live matrix
gains a first-class Folia entry running a same-region combat suite against the journal.

**Strengths.** B1 is a compile error for the bulk of netty code and an
architecture-test failure for the thin parse rim; B2–B4 dissolve into the
single-writer/transaction model rather than being patched; the R11 seam and the R2
boundary are the same physical edge, so they cannot drift apart; the math kernel and
its pins move into `kernel` byte-identical (they already have no Bukkit imports).

**Weaknesses.** One more Gradle module and a freeze/thaw mapping layer at the boundary
(mitigated: the mapping layer largely exists today as `PlayerStateCache`); the parse
rim remains runtime-audited rather than compile-proven (honest limit: PacketEvents
types must live somewhere Bukkit-adjacent); moderate migration cost — the delivery
core is a genuine rewrite, as mandated.

**Migration cost:** medium. **Residual risk:** boundary friction; mitigated by lifting
the existing snapshot-freeze code as the mapping rim.

---

### Candidate C — "Combat Journal" (event-sourced functional core)

**Core idea.** Every combat input — packet observation, damage judgment, config swap,
tick advance — becomes an immutable `CombatFact` appended to a per-victim log; all
combat state is a pure fold over the log; prediction and truth are two reads of the
same log with the same reducer; the delivery layer is an interpreter that executes the
diff between intended and shipped effects. Identity is log position; replaying a log
in a test reproduces any interleaving exactly.

**Strengths.** The theoretical maximum of R13 (perfect replayable observability) and
R5 (state literally is a fold of immutable inputs); B3/B4 are definitionally
impossible (causal order is the log order).

**Weaknesses — disqualifying in this domain.** (1) A hit spans *two* players' logs
(attacker verdicts, victim motion); cross-log ordering re-introduces exactly the
correlation problem the log was meant to dissolve, now with more machinery. (2) The
hot path allocates facts at packet rate (movement packets ~20/s/player plus combat
bursts) and must re-fold or maintain incremental caches — the caches *are* mutable
state again, now hidden behind functional vocabulary. (3) Bukkit's synchronous,
mutable event bus does not interpret effect diffs; the adapter layer to bridge it
would be a reimplementation of Candidate B's desk with extra indirection. (4) Memory
bounding and Folia region migration of logs are open problems the mandate's timeline
should not fund. The journal *idea* — an append-only record of shipped decisions — is
worth keeping at the observability seam, where it is cheap and bounded.

**Migration cost:** high. **Residual risk:** high, novel, and concentrated exactly on
the hot path.

---

## 4. Rubric evaluation

| Rubric | A — Region-Actor | B — Two-Realm Kernel | C — Combat Journal |
|---|---|---|---|
| R1 single-owner side effects | ✅ desk-per-session | ✅ desk + one decision object shared with mirror/valve | ✅ interpreter, but bridged through an adapter that re-blurs it |
| R2 provable Folia safety | ⚠️ runtime asserts + arch test | ✅ compile-time for the netty realm; arch test for the parse rim only | ⚠️ same as A for the input rim |
| R3 identity over timing | ✅ HitId | ✅ HitTransaction state machine, no expiry anywhere | ✅ log position (but cross-log ordering reopens it) |
| R4 single-writer state | ✅ inbox/session | ✅ three explicit single-writer domains | ⚠️ folds hide incremental caches |
| R5 compute-once | ✅ HitContext | ✅ HitContext on the transaction | ✅ by definition |
| R6 unified lifecycle | ✅ Scope | ✅ Scope | ✅ Scope (orthogonal) |
| R7 coherent taxonomy | ✅ descriptors | ✅ descriptors drive config/GUI/arbiter | ✅ (orthogonal) |
| R8 config scale | ✅ | ✅ typed descriptor-keyed snapshot + overlay | ✅ (orthogonal) |
| R9 fail-closed coexistence | ✅ token arbiter | ✅ token-gated handler registration | ✅ (orthogonal) |
| R10 loud compat | ✅ PlatformProfile | ✅ PlatformProfile + packet-local mutation adapter | ✅ (orthogonal) |
| R11 kernel/orchestration seam | ⚠️ package convention | ✅ the Gradle edge *is* the seam | ✅ pure core, but blurred at the bus adapter |
| R12 stable API | ✅ | ✅ frozen shapes + ServicesManager + apiVersion + binary-compat gate | ✅ |
| R13 delivered-value seam | ✅ journal (borrowed) | ✅ journal native to the desk | ✅✅ full replay (overkill) |
| Migration cost of §6 assets | Low | Medium (kernel lifts verbatim; glue rewritten as mandated) | High |
| Blast radius of a version change | Medium | Low (one profile + one adapter) | Medium |
| Risk of re-accretion over 12 months | **High** — the fences are tests and asserts | **Low** — the fences are the build graph | Medium — novelty invites bespoke glue |

---

## 5. Recommendation

**Candidate B — the Two-Realm Kernel — adopting A's per-player inbox as the
single-writer mechanism (it is B's session domain) and C's journal as the
observability seam (it is B's delivery journal).**

The deciding argument is the mandate's own history. The 2026-06-04 rewrite had the
right principles and still accreted nineteen Folia fixes, six delivery band-aids, and
a split-brain lifecycle — because every principle was enforceable only by review. The
one mechanism in this codebase that has *never* drifted is the Gradle boundary (the
floor-API compile has held for the whole version range). Candidate B moves the two
most-violated rules — thread confinement and the kernel seam — onto that same
never-drifted mechanism. Candidate A is cheaper today and strictly more expensive
every month after; Candidate C spends its novelty budget on the hot path where the
domain (a synchronous mutable event bus, two-party hits) fights it.

### 5.1 Module partition (justified)

| Module | Compiles against | Contents |
|---|---|---|
| `api` | Paper 1.17.1 | Public events (shapes frozen), `Mental` facade, `apiVersion()` |
| `kernel` | **nothing** (pure JDK) | Era math + pins, motion fold, frozen value types, hit transactions, wire decision logic, SPI ports, profile schema/value records |
| `platform` | Paper 1.17.1 | `Scheduling` SPI + TCK, `Capabilities`, `PlatformProfile` manifest types |
| `core` | Paper 1.17.1 | Sessions, desk, features, packet parse rim, config IO, GUI, NMS adapter, OCM arbiter binding |
| `compat-folia` | Paper 1.20.4 | `FoliaScheduling` (name-loaded behind capability) |
| `tester` | floor + kernel | Integration harness; era-parity oracle *derived from the kernel motion authority* |

`compat-brigadier` is dropped (§6.3). `common` dissolves into `kernel` (Bukkit-free
parts) and `platform` (Bukkit-typed SPI). PacketEvents stays shaded+relocated in
`core`; the kernel never sees it.

### 5.2 What each mandated quality rides on

- **R2/B1:** `kernel` cannot name `org.bukkit`; the parse rim is pinned by an
  import-allow-list architecture test; the typed `TickClock` (sentinel `TickStamp`,
  `NO_TICK` degradation, recency-guarded exclusion) is a kernel port implemented in
  `core` (Paper: `Bukkit.getCurrentTick`; Folia: global-region-task counter).
- **R3/B3/B4:** the `HitTransaction` lifecycle; no wall-clock constant exists in the
  delivery path; the wire valve consumes by exact quantized payload within one causal
  window; withdrawal by id only; pinned-vs-pre-delivered is a typed state, so a
  non-PacketEvents victim (`getUser == null`) is structurally `PINNED` and can never
  arm a suppressor. The typed pre-send plan (`FeedbackBurst`, lifted) is computed
  before the pacing gate, which observes only the velocity component.
- **B5:** descriptor facet coverage, test-enumerated.
- **B6:** the source token is minted at origin; the rod feature mints `ROD_PULL`
  before invoking `victim.damage`, and the melee feature dispatches on the active
  transaction's token — the thread-local guard has no reason to exist. The
  "active inbound transaction" slot is a session field (single-writer, asserted
  consumed), not a hidden static.
- **B7/R13:** the delivery journal; the tester and the `EntityKnockbackEvent` mirror
  both read the desk's decision object; deterministic interleaving harness for
  Folia; a live Folia matrix entry with same-region combat coverage.
- **B8/R6:** the `Scope`; enable is transactional; teardown closes each disposable in
  isolation; PacketEvents build-in-onLoad / init-after-registration /
  terminate-after-unregistration stays in the plugin shell.
- **B9/R8:** descriptor-keyed snapshot, captured once per operation; overlay file for
  machine writes; explicit `config-version` chain migrating the current v2 layout.
- **B10/R10:** one boot-built `PlatformProfile` with a required/optional manifest;
  packet-local NMS mutation through one version-gated, boot-probed adapter that fails
  loud (disables the owning feature with a log); the remapper wired in production.
- **B11:** stateless per-hit compensation over the session's per-tick kinematic
  snapshot; PING/PONG probe behind a port; KEEPALIVE dropped.
- **B12:** an `EphemeralDecoration` session service with the canonical exit-trigger
  set and a pre-save reconciliation hook.
- **B13:** era item effects computed purely, applied at the confirmed terminal event.
- **B14/R9:** the total `MechanicToken` arbiter; `scope.rule(token, decider, handler)`
  is the only way to register an effectful rule handler; §4.11's decider table and the
  two startup warnings are arbiter constants.

### 5.3 §4 invariants — how honored

The kernel lifts the engine, motion model, profile schema, presets, and every
hand-computed pin **byte-identical** (mandate §6 list; §7 below). Delivery semantics
(§4.3 ledger rules, §4.5 arbitration/pre-send/pinned-vs-pre-delivered, §4.6 damage
windows) are carried as kernel decision logic under the transferred unit pins plus the
canonical wire-vector, combo-decline, and boundary-contract tests. The client-side
technique contract (§4.4) is untouched by construction (server-authoritative
velocities; the sole reconstruction — block-hit sprint reset gated on the raw client
flag — is a descriptor facet). Zero-touch and `parse(empty) == LEGACY_17` remain
test-enforced properties of the descriptor registry (a disabled descriptor assembles
no scope). §4.11's decider table, BOUND/CONFIG freezing, vanilla-shaped handoff
(sharpness-5 diamond = 14.25), and both startup warnings are arbiter behavior with
dedicated tests. §4.8 preset extraction/sacred-edits/superseded-upgrade semantics are
lifted with their tests.

---

## 6. Cross-cutting decisions (owner-visible)

### 6.1 Version & branding
- **Version: `5.0.0`.** Supersedes StrikeSync 4.0.1 without a numeric downgrade,
  restores natural tag sorting (the `release.yml` anti-v4 hack dies), and marks the
  rewrite epoch. Single source of truth: one `version` property in `gradle.properties`
  flowing to `plugin.yml`, the facade, and the release tag (the existing
  push-bump-to-main auto-release flow is kept).
- **`apiVersion()` = 2**, independent of the display version (API 1 died at the 2.1.0
  break). The facade also registers via `ServicesManager`; event shapes stay frozen; a
  binary-compat check (japicmp against a frozen baseline) joins the gate.
- **bStats: dropped by default** per the mandate's stated default (it was shipped
  against the prior design's own out-of-scope list). If the owner wants the four
  telemetry charts kept, it becomes an explicit, config-gated descriptor — say so at
  approval.
- The repo directory rename (`StrikeSync` → `Mental`) is an owner action noted here.

### 6.2 OCM stance
**Standalone-first, arbiter-total.** Mental is a complete 1.8 combat suite on its own;
the historical six mechanics keep per-decider BOUND/CONFIG yielding (frozen into
snapshots for netty); every other token is Mental-owned with loud double-enable
warnings. The half-frozen `OcmMechanic` enum is replaced by the total token set.

### 6.3 Commands & GUI
GUI-first stands (owner's 2.1.0 decision). The command surface is two literals
(`/mental` opens the menu, `/mental reload` for console) registered **once** through
plugin.yml only — the Brigadier bridge and the CommandTree DSL are retired with the
double-registration bug. `compat-brigadier` is deleted.

### 6.4 Latency probe transport
Play PING/PONG behind the `Probe` port. The KEEPALIVE strategy is deleted, not
gated — its correctness depended on out-cancelling the server's disconnect handler,
and the mandate permits dropping it outright.

### 6.5 GPL re-derivation
`MotionMath`/`GroundProbe` are re-implemented clean-room from the combat compendium's
decompile-cited constants (which are vanilla facts, not KnockbackSync expression),
with provenance comments citing the compendium. Their unit pins (vanilla physics
truths) carry over.

### 6.6 Support matrix
One machine-readable descriptor (`support-matrix.json`: version list, per-version JDK,
platform flags, suites) read by Gradle task registration, both CI workflows, the local
concurrent runner, the release-notes range, and `plugin.yml` — retiring the three
version lists and the two disagreeing JDK mappings. Folia becomes a first-class entry
with live same-region combat coverage asserting against the delivery journal, and the
per-run freshness nonce replaces the mtime ritual.

### 6.7 Docs reconciliation
`docs/knockback-profiles.md` loses the per-player narrative (global model only);
profile docs gain a generation/verification step against the kernel schema so they
cannot drift again; `docs/effective-material-contract.md` is preserved as shipped
(the resolver lifts verbatim).

---

### 6.8 Conventions deliberately kept vs discarded

Per the owner's directive, the current codebase's conventions carried **zero weight**
in this design — everything below was re-decided on merit, and the burden of proof sat
on keeping, not discarding.

**Discarded** (existing convention, rejected): the `CombatModule` base class and
registry; the `MentalServices` god-record and hand-wired bootstrap; the
26-accessor/26-arg config pattern; the `module/{rules,damage,potion,…}` package
taxonomy; the split Bukkit-vs-packet listener lifecycles; the `common` module (its
contents split into `kernel` and `platform` on the thread-boundary axis instead of the
"has Bukkit deps" axis); `compat-brigadier` and the CommandTree DSL; the KEEPALIVE
probe; bStats-by-default; the 2.x version line; per-victim shared concurrent maps as
the default state shape; string module ids.

**Kept, on argued merit — not because they exist today:**
- *Gradle multi-module in Java* — because the build graph is this design's enforcement
  mechanism (§5); the boundary is the feature. Kotlin was considered (sealed
  hierarchies, nicer value types) and rejected: a shaded stdlib inflates the universal
  jar, complicates the 1.20.5 remapper interplay, and buys nothing the kernel's
  records + sealed interfaces don't already give on Java 17 bytecode.
- *External DI frameworks* (Guice/Dagger) considered for the reconciler and rejected:
  reflection-heavy wiring is exactly what Folia's thread guards and the remapper
  punish, and the descriptor registry is ~a hundred lines of plain code.
- *Operator-facing commented YAML* — the Bukkit ecosystem's lingua franca; operators
  diff and share these files. The machine-write overlay (§5.2, §9 item 3) exists
  precisely so this convention stops costing us comment-stripping.
- *`PlayerVelocityEvent` as the sole apply point, floor-API compile, shaded
  PacketEvents, region-scheduler-only entity work* — these are mandate-pinned domain
  truths (§3.3/§4.5), not conventions; no latitude was taken or wanted.
- *GUI-first with a two-literal command surface* — re-affirmed intentionally (mandate
  §9.5): the admin surface is discovery-heavy (toggle 20+ features, browse presets),
  which menus serve better than command grammars; consoles keep `reload`.
- *Immutable records, atomic snapshots, hand-pinned pure math* — kept because they are
  the part of the old codebase that measurably never broke.

## 7. Reuse ledger (mandate §6, re-justified)

**Lift verbatim into `kernel` (math + tests, byte-identical pins):**
`KnockbackEngine`+test, `VictimMotion` pure statics (`decay`, `decayOnce`,
`groundedEquilibrium`)+test, `KnockbackProfile` schema (+`KnockbackDelivery`,
`VerticalMode`, `ResistancePolicy`, `RangeReduction`, `Limits`, `LEGACY_17`,
`sameValues`)+tests+preset value table, `GroundFriction.of` table,
`KnockbackVector`, `EntityState` record (capture factories move to the core rim),
`ReachValidator`, `FeedbackBurst`, `CpsLimiter`, `DamageCalculator`'s era tables
(reflection moves to the platform adapter), `FeedbackSenders.hurtYaw` trig,
`JitterCalculator`, `LatencyTracker` correlation logic, and the OCM-port math
(`DefenceMath`, `ArmourDurabilityMath`, `ToolDurabilityMath`, `RegenMath`,
`SwordBlockReduction`, `EraReach`, `RodLaunchMath`+`legacyPull`, `PotionDurations`,
`GoldenAppleEffects`, `OffhandPolicy`, `withPunch`) with injected randomness. All are
already Bukkit-free or trivially split (purity audit confirmed); each satisfies the
new architecture because the kernel is *defined* as their home.

**Lift with re-derivation:** `MotionMath`/`GroundProbe` (§6.5, GPL).

**Lift as technique into the platform profile:** `Attributes`/`Enchantments`/
`Materials` modern-then-legacy resolve-once probing, wrapped in the required/optional
manifest instead of bare nullables. `ServerEnvironment` parse (scoped to the boot
report). `EffectiveMaterial.resolve` verbatim (thin PDC shell in core).

**Lift interface, add TCK:** `Scheduling`+`TaskHandle` — the retired-callback
thread/timing contract becomes explicit and both backends pass one conformance suite
(the documented Bukkit-vs-Folia divergence is the first TCK case).

**Lift shapes only:** the three public events verbatim; `AsyncHitRegisterEvent` gains
a structural async-safety guard without a signature change. `Buttons.wrap`, `Brand`.
The FakePlayer *knowledge* (three join-protection layouts, constructor fuzzing, voided
pipeline) is extracted into small unit-tested components.

**Do not port (redesigned away):** `KnockbackPipeline`, `PendingStore`,
`AppliedTagStore`, `VelocityDuplicateSuppressor`, `MeleeReentryGuard`,
`ServerTickClock` (concept survives as the typed clock port; the class does not),
`GroundPacketTap`/`GroundTransitionWatcher` (observation survives as the
connection-domain FSM; the racy multi-writer tap does not), `SprintTracker`,
`KnockbackEventMirror` (survives as a desk view), `MentalServices`, `MentalConfig`'s
26-accessor snapshot, the string-keyed `Catalog`, the six bootstrap packet listeners,
the scattered NMS reflection, `OcmMechanic`, the compensation probe-slot,
`CommandTree`+Brigadier bridge.

---

## 8. Phasing (post-approval; each phase TDD, committed as it lands)

1. **Kernel** — port the math + pins verbatim (gate: all transferred tests green,
   `kernel` has no Bukkit on its compile classpath by construction).
2. **Delivery core** — transactions, sessions/inboxes, desk, valve, journal, typed
   clock; deterministic interleaving tests; canonical wire vectors reproduce.
3. **Feature framework** — descriptors, scopes, arbiter, config snapshot + overlay +
   migration.
4. **Features** — delivery family first (fast path, w-tap), then knockback family,
   then the rule families; era-parity + zero-touch suites at each step.
5. **Platform** — profile manifest, NMS adapter, compat-folia, support-matrix
   descriptor, CI (OCM artifact acquired reproducibly).
6. **Surface** — GUI, config docs, API compat gate, version/branding cutover, docs
   reconciliation.

---

## 9. Open items the owner may want to weigh in on (defaults chosen)

1. **bStats** — default: dropped (§6.1). Say "keep bStats" to retain it as a gated
   descriptor.
2. **Version `5.0.0`** — default as specified; an alternative (e.g. `3.0.0` +
   changelog-logic keep) is possible but re-inherits the downgrade problem against
   v4.x upgraders.
3. **Machine-write overlay** (§5.2/B9) — default: overlay file (`overrides.yml`),
   keeping human YAML pristine; the alternative (comment-preserving YAML editor) is
   heavier and riskier on the 1.17 floor.

---

*Prepared 2026-07-01. Approval of exactly one candidate (with any amendments) unlocks
spec → plan → build per mandate §3.1/§9. Silence is not approval; no rewrite code
exists as of this commit.*

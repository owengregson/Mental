# Mental v5 — engineering spec (Two-Realm Kernel)

**Status: ACTIVE.** Implements the owner-approved direction from
`2026-07-01-mental-rewrite-v2-architecture.md` (Candidate B). The rewrite mandate's
§4 invariants and §5 bug-class properties are normative and not restated here; this
spec defines the concrete structure that delivers them. Version target: **5.0.0**,
`apiVersion() = 2`.

---

## 1. Modules

| Module | Gradle deps | Compiles against | Bytecode |
|---|---|---|---|
| `api` | — | Paper 1.17.1 (compileOnly) | 17 |
| `kernel` | — (test: JUnit only) | **pure JDK — no Paper, no PacketEvents, not even compileOnly** | 17 |
| `platform` | `kernel` | Paper 1.17.1 (compileOnly) | 17 |
| `core` | `api`, `kernel`, `platform`; shades PacketEvents (relocated `me.vexmc.mental.lib.packetevents.*`) + bStats (relocated) | Paper 1.17.1 (compileOnly) | 17 |
| `compat-folia` | `platform` (compileOnly) | Paper 1.20.4 (compileOnly) | 17 |
| `tester` | `api`, `kernel`, `platform`, `core` (compileOnly) + reflection-remapper | Paper 1.17.1 | 17 |

`compat-brigadier` and `common` are gone. `compat-folia` classes fold into core's
shadow jar and load only by name behind `Capabilities.folia()` (unchanged technique).
The kernel's Bukkit-absence is the R2/R11 enforcement edge; a build-time check
asserts the kernel compile classpath contains no `org.bukkit` (belt-and-braces on
top of "it isn't declared").

Package roots: `me.vexmc.mental.api`, `me.vexmc.mental.kernel.*`,
`me.vexmc.mental.platform.*`, `me.vexmc.mental.*` (core), tester unchanged.

### Kernel packages

- `kernel.math` — KnockbackEngine, Decay (the `VictimMotion` pure statics),
  MotionMath (clean-room re-derivation), ReachValidator, HurtYaw, DamageTables (era
  weapon/sharpness/crit tables out of `DamageCalculator`), DefenceMath,
  ArmourDurabilityMath, ToolDurabilityMath, RegenMath, SwordBlockReduction, EraReach,
  RodLaunchMath, PotionDurations, GoldenAppleEffects, OffhandPolicy, GroundFriction
  (the `of(material-name)` table keyed by string/enum name, not Bukkit Material).
- `kernel.model` — KnockbackVector, EntityState, FrozenPlayer, WorldRules,
  KinematicState, TickStamp, HitId, HitSource (sealed), SprintVerdict, HitContext,
  HitTransaction, DeliveryDecision, JournalEntry.
- `kernel.wire` — HitIntentCheck (netty-side validation over frozen views),
  FeedbackPlan (today's FeedbackBurst), SprintWire (per-connection FSM), GroundFsm,
  CpsLimiter, LatencyModel (LatencyTracker + JitterCalculator logic),
  CompensationQuery, PositionRing (reach-history geometry).
- `kernel.ledger` — MotionLedger: the single-writer residual fold (plain fields, no
  concurrency primitives — thread-safety is ownership, §3).
- `kernel.profile` — KnockbackProfile schema + nested enums + `LEGACY_17` +
  preset value tables + SupersededPresets fingerprints.
- `kernel.port` — TickClock, Journal (sink), RandomSource, Probe (transport-agnostic
  latency probe contract).

---

## 2. Threading model — three single-writer domains

**D1 Connection domain** (per player, owner = that player's netty read thread).
State: `SprintWire` (arrival-order sprint view + freshness), `GroundFsm` (movement
FSM + jump-stamp emission), CPS window, position-ring feed. Inputs: that player's own
inbound packets, the `TickClock`, and *published* immutable views (D2 output). It
never touches another player's D1 state and never any live entity.

**D2 Session domain** (per player, owner = the player's region thread). One
`CombatSession` object holds: `MotionLedger`, live `HitTransaction`s, kinematics,
feedback pacing, ephemeral decorations, Jump-Boost cache. Mutated only by its owning
thread; fed by a per-player MPSC inbox of immutable `SessionSignal`s (ledger events
from D1's GroundFsm, quit/teleport notices, etc.). A `repeatOn(player, 1, 1)` session
tick: drain inbox → decay ledger → refresh kinematics (owning-thread reads: ground
distance, block-under-feet slip, live sprint flag, hurt window) → **publish** one
immutable `PlayerView` (single `AtomicReference.set`).

**D3 Global domain** (owner = global region thread / main). Config snapshot swap,
feature reconciler, `TickClock` implementation, entityId→UUID index (written at
join/quit), OCM binding, platform profile (immutable after boot).

Cross-domain communication is exclusively **immutable values**: D1→D2 via inbox;
D2→D1/anyone via the published `PlayerView`; D3→all by reference swap.

**Rules the design certifies rather than audits:**
- Netty-realm logic is kernel code operating on kernel types; the only core-side
  netty code is the packet parse rim (§6), pinned by an architecture test
  (import/call allow-list = the `netty-fast-path` safe list).
- `PlayerView` is published at session-tick start, i.e. it is the state **as of the
  end of the previous tick** — the mandate §4.3 boundary read holds by construction.
  A view older than 4 ticks (TickStamp compare) ⇒ exclusion off / fast path declines,
  preserving the pinned `NO_TICK`/recency semantics.
- An **accepted** melee hit certifies attacker and victim share the region thread
  (cross-region hits are dropped-with-log before any attacker read, mandate §4.5);
  only then may the desk touch both sessions (attacker sprint clear, ledger ×0.6).

`TickClock` (kernel port): returns `TickStamp` (value type; `NO_TICK` constant; no
raw int comparisons). Core implements it — Paper: `Bukkit.getCurrentTick()`
(netty-safe on Paper); Folia: a global-region 1-tick task advancing a counter,
initialised to `NO_TICK`.

---

## 3. The hit lifecycle

### 3.1 Identity

`HitId` — monotonically increasing long (global counter). `HitSource` (sealed):
`Melee`, `RodPull` (the synthetic `victim.damage(rodder)`), `Arrow`,
`Thrown(kind)`, `Bobber`, `Vanilla(cause)` (hits Mental didn't originate).

`HitContext` (immutable, compute-once — R5): attacker/victim `FrozenPlayer` views,
`WorldRules`, resolved profile, `SprintVerdict` (stamped from D1 at registration —
sprint AND wire freshness), OCM ownership verdicts for every relevant token,
compensation answer, anticheat policy, feedback-window state, the computed
`KnockbackVector`, damage inputs. Prediction and truth call **one** decision
function over this context; only *when* the packet ships differs.

### 3.2 States

```
REGISTERED → PLANNED → PRE_SENT            (wire carried the burst)
                     → PINNED              (no PacketEvents user: value pinned, ships once via the velocity event, no valve)
          → RESOLVED{ ADOPTED | SUPPRESSED(reason) | RETRACTED | DROPPED(reason) | ENSURED }
          → RECORDED                        (terminal; journal entry written)
```

Transitions are methods on `HitTransaction` that assert the current state. Minting
sites: the packet rim (fast path), a feature (synthetic sources — minted *before*
invoking `victim.damage`), or the DamageShaper at EDBEE entry when no transaction is
active (`Vanilla` source). **Every transaction reaches a terminal state through an
explicit causal path**: the damage task's completion, the scheduler's retired
callback, a cancel handler, the velocity-event resolution, or the next session tick's
sweep (§3.4). There are no expiry timers; withdrawal is by `HitId` only; no
"withdraw all for victim" operation exists (B4).

### 3.3 Fast path (D1 → D2)

On ATTACK packet (attacker's netty thread): CPS gate → resolve victim via frozen
entityId→UUID index + `Bukkit.getPlayer(uuid)` → `HitIntentCheck` over the two
published `PlayerView`s (creative, pvp, immunity with the +1 staleness allowance,
reach ring) → fire `AsyncHitRegisterEvent` → cancel the vanilla packet → build
`HitContext` (sprint verdict read from this thread's own `SprintWire`; compensation
query over the victim's view) → compute the **typed `FeedbackPlan`** (has-velocity? /
has-hurt?) *before* the pacing gate; the pacing gate observes only the velocity
component → ship the burst (velocity-before-hurt, bundles on 1.19.4+, HURT_ANIMATION
/ status-2) → transaction becomes `PRE_SENT` (or `PINNED` when `getUser == null`) →
schedule the authoritative damage on the victim's region thread
(`runOn(victim, …, retired = resolve RETRACTED)`).

Velocity suppressors (hurt still ships; state records the reason): anticheat gate,
OCM owning attacker's melee KB (frozen in the view), pending LEGACY resistance roll,
missing/stale snapshots, feedback window.

### 3.4 Authoritative pass (D2)

The damage task sets the session's `activeInbound` slot to the transaction (plain
field, owning thread; asserted consumed on exit — this is the typed replacement for
`MeleeReentryGuard`, per-victim and universal rather than rod-only), calls
`victim.damage(amount, attacker)`, then clears the slot.

- **DamageShaper** (single EDBEE listener, the only damage mutator): reads
  `activeInbound` — present ⇒ dispatch by its `HitSource` (a `RodPull` is never
  melee); absent ⇒ mint a `Vanilla` transaction with an owning-thread-built context.
  Damage features contribute pure components composed in fixed legacy order; OCM
  handoff is vanilla-shaped (mandate §4.11).
- **Vector submission**: knockback features are pure computers; they submit
  `(transaction, vector)` to the desk. Arbitration is **last-submitter-wins per
  victim per tick** (mandate §4.5): a later submission replaces the pending decision;
  cancellation/withdrawal keys on the exact `HitId`.
- On task completion the transaction must be in a resolved-or-awaiting-velocity
  state; the **next session tick sweeps** any transaction still awaiting a velocity
  event from a previous tick to `DROPPED(no-velocity-event)` — tick-causal, not
  wall-clock.

### 3.5 Velocity event resolution (D2)

The desk is the sole `PlayerVelocityEvent` writer (one global listener routing to the
victim's session; the event fires on the owning thread). If no pending decision:
pass through (foreign velocity). Otherwise, one atomic apply-and-record:

1. Fire `KnockbackApplyEvent` (API contract preserved).
2. `PRE_SENT` + event velocity unmodified by third parties ⇒ ship the pre-delivered
   value and **arm the valve**; modified ⇒ ship the modified value as a correction
   (no valve). `PINNED` ⇒ ship normally (never a valve — B4's conflation is
   unrepresentable in the type). Null-vector (legacy resistance roll) ⇒ cancel event,
   resolve `SUPPRESSED(resistance)`.
3. Record the **final delivered value** into the `MotionLedger` under the combo-era
   rules (melee skipped when `combos:false`; rod/arrow/thrown always) — same
   operation, same thread, same object (B4: no HIGH/MONITOR pair, no side-channel).
4. Write the `JournalEntry`; state → `RECORDED`.
5. Post-hit obligations for accepted sprint-bonus melee: `attacker.setSprinting(false)`
   + attacker ledger `scaleHorizontal(0.6)` (both certified same-thread, §2).

**The valve** replaces `VelocityDuplicateSuppressor`: keyed
`(entityId, shortQuantizedX, shortQuantizedY, shortQuantizedZ)` — the exact wire
encoding (motion×8000), integer equality, no epsilon. Armed by step 2 on the region
thread; the ENTITY_VELOCITY write that follows `sendChanges` synchronously on the
same thread consumes it (consume-once). An unconsumed valve is cleared at the next
session tick (tick-causal), and exact payload+entity keying bounds any residual risk.

**The mirror** (1.20.6+): the `EntityKnockbackEvent` listener asks the desk for the
*same pending decision object* and sets `delta = target − current`; it cannot diverge
because there is no second source of truth. Registered only when the capability
exists; zero-touch when the knockback feature is off.

**Ensure-delivery** (sources with no vanilla velocity event — e.g. rod self-launch):
the submitting feature marks the transaction `ensure`; the desk schedules
`runOn(victim, next tick)`: still unresolved ⇒ `setVelocity` + resolve `ENSURED`.

### 3.6 The delivery journal (R13/B7)

Bounded per-victim ring of terminal `JournalEntry`s: (HitId, source, cause, final
vector, wire-vs-event, suppress reason, TickStamp). Written only by the desk. Read
by: the tester (all combat assertions), the debug sink, and nothing else. This is the
single "what did we actually ship" seam.

---

## 4. Connection domain detail

- **SprintWire** (kernel): consumes START/STOP_SPRINTING + ATTACK in arrival order on
  the connection thread; freshness armed on START arrival; vanilla's in-attack clear
  mirrored beside the desk's post-hit clear (via a published flag the FSM reads);
  after ≥3 ticks of wire silence (TickStamp compare — replaces the 150 ms constant)
  it re-seeds from the published `PlayerView`'s server sprint flag. Bukkit toggle
  events never write it (they don't exist in this domain at all).
- **GroundFsm** (kernel): the movement-packet FSM — liftoff (`onGround→!onGround`,
  dy>0 ⇒ jump stamp `0.42 + 0.1×(JumpBoost+1) + 0.2 sprint push`), landing, teleport
  reset — emitting `LedgerEvent(tick, kind, …)` into the victim's session inbox.
  Jump-Boost amplitude and slipperiness come from the published view (session-cached,
  refreshed per tick). Packetless players (tester fakes) are served by a session-side
  sampler that stands down permanently once packets are seen (unchanged semantics).
- The parse rim guards: reference-compare packet types (never downcast pre-Play
  traffic), `getUser == null` ⇒ pinned path, wrap sends to reconfiguring targets.
  The pre-Play contract stays pinned by unit tests against synthetic events.

## 5. Compensation

Stateless per-hit query (B11): `CompensationQuery.verticalFor(context)` reads the
victim's `KinematicState` from the published view (freshness ≤ 1 tick, refreshed by
the session tick — no probe-cadence coupling, no slot, no TTL; N combo hits get N
answers) and the RTT/jitter estimate from `LatencyModel` (updated on pong arrival).
Trajectory is one continuous signed-velocity simulation (no rise-glued-to-fall
re-seed). Transport: Play PING/PONG behind the `Probe` port; ids exact-match,
consume-once, foreign transactions pass through. KEEPALIVE support is deleted.
`MotionMath`/`GroundProbe` re-derived clean-room from the combat compendium's
decompile citations (provenance comments cite the compendium, not KnockbackSync).

## 6. Packet parse rim (core, netty realm's only Bukkit-adjacent code)

One package `me.vexmc.mental.rim`: PacketEvents listeners that (a) parse wrappers
into kernel records and forward to D1 logic, (b) execute kernel-computed
`FeedbackPlan`s/probe sends, (c) host the valve consume check, (d) the
packet-decoration senders (§9). An architecture test scans this package (and only
core's netty-facing packages) against the explicit allow-list; any other import/call
of live-entity accessors fails the build.

## 7. Feature framework

- **`FeatureDescriptor`** — one sealed registry (enum) in core. Fields: typed id
  (yaml key — single source for parser/GUI/arbiter/`id()`), family (`DELIVERY`,
  `KNOCKBACK`, `DAMAGE`, `CADENCE`, `SUSTAIN`, `LOADOUT`), display name, blurb, icon
  name, default-enabled, `MechanicToken`s, **facet declaration** (server-rule /
  client-presentation / fast-path-damage / vanilla-path-damage — each a
  handler-or-explicit-`NONE`; a unit test enumerates descriptors and fails on silent
  gaps — B5), settings parser + defaults reference.
- **`Scope`** — the only resource acquisition path: `scope.listen(bukkitListener)`,
  `scope.packets(peListener)`, `scope.repeatOn/repeatGlobal/repeatAsync(...)`,
  `scope.rule(token, decider, handler)`, `scope.service(closeable)`. Enable runs
  `assemble(scope, settings)`; a throw closes everything registered so far, each
  close individually isolated (B8). Disable = close scope. No `active` flag; the
  reconciler's map of open scopes is the truth.
- **Reconciler** — converges descriptor set × config snapshot to open scopes;
  per-feature exception isolation; reload = converge. PacketEvents build-in-onLoad /
  init-after-first-converge / terminate-after-final-close ordering lives in the
  plugin shell.
- **Zero-touch**: a disabled descriptor assembles nothing. Always-on infrastructure
  (sessions, parse rim taps) is observation-only by type: the tap interfaces it is
  given expose no mutating operations.

## 8. Coexistence — `MechanicToken` + `Arbiter`

Total token enum (one per era mechanic Mental can restore — the historical six plus
every ported rule). `Arbiter.confirm(token, decider)`:

- Historical six: BOUND (OCM service API, owning thread only, verdicts **frozen into
  the per-tick views** for netty) / CONFIG (conservative global) / ABSENT — per the
  fixed decider table (attacker for melee-KB/tool-damage/crits, rodder for fishing,
  victim for thrown, Mental always for arrows).
- Ported rules: Mental-owned; the arbiter knows OCM's config key per token and warns
  loudly at startup on double-enable (fail-closed against *silent* double-apply).
- The two mandated startup warnings (OCM `old-player-knockback` in the default
  modeset; `playerDelay: 18 ≠ 20`) are arbiter boot checks.
- `scope.rule(...)` is the only effectful-rule registration; a feature without a
  token cannot register one (B14). OCM ownership verdicts consumed at event time come
  from the `HitContext` (compute-once), so a two-path feature cannot forget the gate
  on one path (the CritFallback bug class).

## 9. Platform profile & NMS adapter (R10/B10)

`PlatformProfile` — built once at boot from a manifest of `Required<T>` /
`OptionalSince<T>` resolutions (attributes, enchantments, HURT_ANIMATION/bundles,
knockbackEvent, `BLOCKS_ATTACKS`/`CONSUMABLE`/`max_damage` components,
join-protection layout, projectile-KB-restored-at-1.21.2 flag). A `Required` failure
disables the owning feature with one loud log (engine-critical ⇒ boot fail); an
`OptionalSince` absence returns a typed absence whose fallback is declared at the
manifest entry. All NMS access (sword-block components, tooltip hiding, attack-range)
routes through **one** version-gated adapter, boot-probed, remapper-wired; outbound
packet mutation operates **only on packet-local copies** in the rim and fails loud on
a mapping break. The modern-then-legacy resolve-once probing technique is lifted from
`Attributes`/`Enchantments`/`Materials`.

## 10. Config (R8/B9)

- Files: `config.yml` (features + general), `knockback.yml`,
  `hit-registration.yml`, `latency-compensation.yml`, `profiles/*.yml` (ten presets,
  extraction/sacred-edit/superseded-upgrade semantics lifted), plus
  **`state/overrides.yml`** — machine-owned overlay the GUI writes; human files are
  never re-serialized. Effective value = overlay ?? file ?? default; the GUI shows
  effective values and marks overridden keys.
- `Snapshot`: immutable, descriptor-keyed typed map + engine sections; built whole,
  swapped by one reference; every operation captures once at entry; hit-relevant
  values are frozen into `HitContext`/`PlayerView`.
- `config-version: 3`; explicit chain v1→v2→v3 with backup; warn-and-fallback
  parsing (absent silent, wrong-type one named warning).
- `parse(empty) == LEGACY_17` and per-knob no-op-at-default property tests carry
  over; preset canonical-value pins carry over.

## 11. Public API (R12)

Event shapes verbatim (`KnockbackApplyEvent`, `AsyncHitRegisterEvent` + structural
async guard, `KnockbackProfileChangeEvent`). `Mental` facade: existing methods +
`apiVersion()` (= 2) + registration in `ServicesManager` alongside the static holder.
Deprecation policy documented in the API module; japicmp binary-compat gate against
the frozen baseline.

## 12. Testing

1. **Kernel pins ported first** — byte-identical expectations (engine vectors, decay
   tables, presets, damage tables, burst plans, reach, RTT correlation).
2. **Interleaving harness** (kernel test fixture): D1/D2 as test-controlled
   executors, scripted packet/tick sequences, assertions against the journal —
   the deterministic Folia-capable delivery test (B7). Scripted scenarios include:
   boundary-cadence combos, overlapping melee+rod, retired-mid-flight, stale-view
   fallback, valve consume/clear.
3. **Architecture tests**: kernel classpath has no `org.bukkit`; rim allow-list scan.
4. **Scheduling TCK**: one conformance suite both backends pass (retired-callback
   thread+timing contract made explicit — the documented Bukkit/Folia divergence is
   case #1).
5. **Live matrix**: suites assert via the journal; **Folia is a first-class entry**
   running a same-region combat suite; per-run **nonce** — the harness passes a nonce
   system property, the tester embeds it in `test-results.txt`, the check task
   validates nonce+PASS (staleness structurally impossible).
6. **OCM coexistence CI**: the OCM jar acquired reproducibly (pinned release download
   or submodule build) — covers the six mechanics AND rule-module double-apply
   warnings.
7. **Era-parity oracle** derives from the kernel motion authority (tester depends on
   `kernel` directly — no third re-implementation).

## 13. Build, version, branding

- `version=5.0.0` in `gradle.properties` (single home; root build reads it);
  auto-release flow unchanged (bump-on-main tags `v5.0.0`); the v4-tag changelog
  hack is deleted (5 > 4 sorts naturally).
- `support-matrix.json` at repo root: `[{version, jdk, platform, suites}]` — read by
  Gradle task registration, `integration-matrix.sh` (jq), both workflows, the
  release-notes range, and `plugin.yml` api-version floor. No version/JDK literal
  anywhere else.
- bStats **kept** (owner decision): id 31788, four charts, config-gated
  (`metrics.enabled`, default on), relocated as today.
- plugin.yml: `mental` command + `mental.command.use`/`reload`, registered via
  plugin.yml only (Brigadier gone). `folia-supported: true`,
  `softdepend: [OldCombatMechanics]`.
- Repo directory rename to `Mental` = owner action, out of band.

## 14. Porting map (source → target)

| Current | Target |
|---|---|
| `module/knockback/KnockbackEngine` + test | `kernel.math.KnockbackEngine` (verbatim) |
| `VictimMotion` statics + test | `kernel.math.Decay`; ledger machinery → `kernel.ledger.MotionLedger` (rewritten single-writer) |
| `config/KnockbackProfile` + enums + tests | `kernel.profile.*` (verbatim) |
| `GroundFriction`, `KnockbackVector`, `EntityState` | `kernel.math` / `kernel.model` (capture factories → core) |
| `hitreg/{ReachValidator,FeedbackBurst,CpsLimiter,DamageCalculator-tables,FeedbackSenders.hurtYaw}` + tests | `kernel.math` / `kernel.wire` (reflection parts → §9 adapter) |
| `compensation/{LatencyTracker,JitterCalculator}` + tests | `kernel.wire.LatencyModel` (verbatim logic) |
| `compensation/{MotionMath,GroundProbe}` | clean-room re-derivation (`kernel.math.MotionMath`, core `GroundDistance`) |
| OCM-port math (`DefenceMath` … `OffhandPolicy`, `withPunch`) + tests | `kernel.math.*` (verbatim, injected randomness) |
| `platform/{Attributes,Enchantments}`, `gui/Materials` | §9 manifest entries (technique lifted) |
| `EffectiveMaterial.resolve` + test | kernel-pure resolve; PDC shell in core |
| `common/scheduling/*` | `platform.Scheduling` + TCK |
| `ServerEnvironment` + test | `platform` (boot report only) |
| API events, `Buttons.wrap`, `Brand` | verbatim |
| Pipeline/PendingStore/AppliedTagStore/Suppressor/ReentryGuard/ServerTickClock/Tap/Watcher/SprintTracker/Mirror/MentalServices/MentalConfig/Catalog/OcmMechanic/CommandTree | **not ported** — replaced per §3–§8 |

## 15. Out of scope (unchanged from the mandate)

Trident KB, per-world modesets, 1.16-, Fabric/Velocity, randomized/CPS-scaled
anti-features, the 1.21.2+ projectile-substitution no-op stays a no-op, cross-region
melee stays dropped-with-log, true 1.7 client-side target selection stays documented
as irrecoverable.

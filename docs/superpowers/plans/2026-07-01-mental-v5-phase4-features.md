# Mental v5 Phase 4 — Live Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the v5 machinery live and rebuild every feature family on it,
family-by-family with live matrix gates, ending with the old core deleted and the v5
plugin as the sole entry point.

**Architecture — the cutover strategy (normative):**
- Sub-phases 4A1 → 4A2 → 4B → 4C → 4D → 4E, each one executor run, each ending with
  a fresh `scripts/integration-matrix.sh` PASS on the suites enabled at that point.
- **The tester's committed suite list is the source of truth for coverage.** At the
  4A2 swap it is trimmed to the suites the ported families support; each sub-phase
  adds its suites back; 4E restores the full list. Every commit keeps the tree AND
  the live matrix green.
- **The swap happens at the end of 4A2**: `plugin.yml` `main:` flips to
  `me.vexmc.mental.v5.MentalPluginV5`. Old core classes stay compiled-but-dormant
  (harmless dead code) until 4E deletes them. From 4A2 on, the shipped jar IS v5.
- Phase 4 uses the EXISTING `common/scheduling/Scheduling` (already region-correct,
  already shipped); Phase 5 relocates it into `platform` and adds the TCK. v5 code
  may import `me.vexmc.mental.common.scheduling.*`.
- NMS-heavy features (sword-block components, tooltip hider, attack-range) build
  against a minimal `v5/platform/PlatformProbe` created in 4B/4C/4D; Phase 5
  completes it into the full manifest.

**Tech Stack:** as Phase 3, plus live wiring: PacketEvents (shaded), Bukkit events,
run-paper matrix.

## Global Constraints

- Ported pins sacred; era behavior byte-identical — the live suites are the judge.
- New code under `core/src/{main,test}/java/me/vexmc/mental/v5/` and `kernel/`;
  PLUS these sanctioned existing-file edits, each named in its task: `plugin.yml`
  (main class, 4A2), tester suite files (adaptation + suite list, per sub-phase),
  `core/build.gradle.kts` only if a task names it. 4E's deletion list is explicit.
- Netty-realm rule: rim classes parse wrappers → kernel records → kernel logic; an
  architecture test (4A1) pins the rim package's imports to the allow-list.
- No wall-clock correctness. Conventional commits + trailer, one per task.
- **The matrix-gate honesty rule**: a sub-phase is done only when
  `run/**/plugins/MentalTester/test-results.txt` are FRESH for THIS run and read
  PASS; paste the freshness evidence (mtimes) in the report.

---

## Sub-phase 4A1 — the live spine (bootable, zero features, zero-touch)

### Task 4A1.0: Production Registrar + plugin bootstrap

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/MentalPluginV5.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/BukkitRegistrar.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/feature/BukkitRegistrarTest.java` (unit-testable parts)

**Contracts:**
- `BukkitRegistrar implements Registrar`: `bukkit(listener)` registers via
  `PluginManager.registerEvents`, closes via `HandlerList.unregisterAll(listener)`;
  `packets(peListener)` registers via the PacketEvents API returning the handle,
  closes via `unregisterListener(handle)`; `task(starter)` invokes the starter
  (which wraps `Scheduling.repeat*`), closes by cancelling the `TaskHandle`;
  `rule(token, reg)` consults the live `OcmBinding`-backed arbiter gate before
  every handler run (the registration wraps the handler).
- `MentalPluginV5 extends JavaPlugin`: `onLoad` builds+loads PacketEvents (same
  settings as old). `onEnable` order: ConfigStore → Migrations.run → Overlay →
  SnapshotParser → Capabilities/ServerEnvironment/SchedulingFactory (reuse existing
  classes read-only) → TickClock (Paper: `PaperTickClock(Bukkit::getCurrentTick)`;
  Folia: `CounterTickClock` driven by `repeatGlobal(1,1,…)`) → OcmBinding +
  CoexistWarnings logged → SessionService → rim listeners → Reconciler.register(all
  units) → converge(snapshot) → `PacketEvents.init()` LAST. `onDisable`: reverse;
  reconciler.closeAll, sessions shutdown, PE terminate — each step isolated
  try/catch (B8 teardown isolation). `reloadAll()`: re-read files → overlay →
  parse → swap snapshot → converge; returns issues.
- 4A1 registers ZERO FeatureUnits — the spine must be a no-op server (zero-touch).

### Task 4A1.1: SessionService + view publication

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/session/SessionService.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/session/ViewBuilder.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/session/GroundDistance.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/session/ViewBuilderTest.java`

**Contracts:**
- Join/quit/world-change Bukkit listeners; per-player `CombatSession` (Phase 2
  shell) + `Scheduling.repeatOn(player, 1, 1, tick, retired)`; a frozen
  `ConcurrentHashMap<Integer,UUID>` entityId index maintained at join/quit; a view
  registry `ConcurrentHashMap<UUID, AtomicReference<PlayerView>>` the rim reads.
- Session tick (owning thread): drain inbox → `ledger.tick` → `ViewBuilder.build`
  (owning-thread reads only: ledger, `GroundDistance`, live sprint flag, hurt
  window, KB resistance attr, per-world profile from the snapshot, frozen OCM
  melee verdict via the arbiter, ping, jump-boost amplifier, slipperiness under
  feet via `GroundFriction.of(block.getType().name())`, gravity attr) → publish →
  `desk.sweep` → valve `clearStale`.
- `GroundDistance` — **clean-room** (the old `GroundProbe` is GPL-lineage; do NOT
  open it): four corner rays inset 0.01 from the 0.6-wide bounding box, downward,
  max 5.0, returning the minimum distance-to-block-top; owning-thread only.
  Unit-test the corner/inset geometry with a stubbed ray function.
- `ViewBuilder` unit test: given stubbed inputs, the produced `PlayerView` carries
  them 1:1 and `at` = the clock's stamp (the freshness contract).

### Task 4A1.2: The rim — packet taps, valve listener, probe, feedback sender

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/rim/{ConnectionDomains,PacketTap,ValveListener,ProbeRim,BurstSender}.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/rim/{PacketTapStateTest,RimArchitectureTest}.java`

**Contracts:**
- `ConnectionDomains`: per-player `SprintWire`+`GroundFsm` created at PE user
  connect, keyed by UUID; single-writer = that connection's netty thread.
- `PacketTap` (inbound, MONITOR-equivalent priority): movement packets →
  `GroundFsm.onMovement` → enqueue the returned `LedgerEvent` to the session inbox;
  entity-action START/STOP_SPRINTING → `SprintWire`; **reference-compare packet
  types, never downcast pre-Play traffic** — port the old `ProbeListenerStateTest`
  technique for the pre-Play pin (`PacketTapStateTest`).
- `ValveListener` (outbound, HIGHEST): ENTITY_VELOCITY sends →
  `VelocityValve.consume(victimId, entityId, qx, qy, qz)` → cancel on true.
- `ProbeRim`: Play PING send / PONG receive → `LatencyModel` (exact-match ids,
  foreign transactions pass). KEEPALIVE support: none (deleted by design).
- `BurstSender`: executes a kernel `FeedbackPlan` through the victim's PE `User`
  (bundle delimiters on 1.19.4+, HURT_ANIMATION with yaw / entity-status 2 below,
  velocity-before-hurt, single flush, wrap-and-drop for reconfiguring targets);
  `getUser == null` → returns "unsendable" so callers pin.
- `RimArchitectureTest`: scans `core/src/main/java/me/vexmc/mental/v5/rim/` SOURCE
  files and fails on any occurrence of the forbidden live-entity accessors
  (`getEntityById`, `getGameMode(`, `getNearbyEntities`, `getEntities()`,
  `Bukkit.getCurrentTick`, `.getHandle(`, `getName()` on Player) — the allow-list
  pin from `netty-fast-path`.

### Task 4A1.3: Desk wiring + API events

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/delivery/{DeskRouter,DamageRouter,MirrorListener}.java`
- Test: unit tests for router decision mapping with stubbed events.

**Contracts:**
- `DeskRouter` (Bukkit listener): `PlayerVelocityEvent` HIGH → victim's desk
  `pendingFormula()` → fire `KnockbackApplyEvent` (API shape verbatim) →
  `resolve(api velocity)` → execute the Directive (set velocity / cancel /
  arm valve via `VelocityValve`); MONITOR record half does NOT exist — apply and
  record are one desk call (B4).
- `DamageRouter` (EDBEE, priorities per old KnockbackModule): reads the session's
  `activeInbound` slot; absent ⇒ mint `Vanilla` transaction. 4A1 routes but no
  feature consumes yet.
- `MirrorListener`: capability-gated (`knockbackEvent`), reflective registration as
  the old mirror, but reads `desk.mirrorView()` — one decision object.
- Quit/death/world-change forget hooks.

### Task 4A1.4: 4A1 gate

- `./gradlew build` green; new unit tests green; rim architecture test green.
- Boot MentalPluginV5 on the floor + ceiling servers locally by TEMPORARILY
  pointing a scratch copy of plugin.yml at it (do not commit the swap):
  `Boot` suite passes; with all features off the server is vanilla (`ZeroTouch`
  suite technique). Commit; report raw outputs.

## Sub-phase 4A2 — delivery + knockback families, THE SWAP

### Task 4A2.0: HitRegistrationUnit (the fast path) + AnticheatCompatUnit
Port the old `HitPacketListener` flow onto the rim/kernel seam: CPS gate →
frozen-index victim resolve (`Bukkit.getPlayer(uuid)`) → `HitIntentCheck` over two
published views (immunity +1 allowance, creative, `world.getPVP()`, reach ring) →
`AsyncHitRegisterEvent` (shape verbatim, structural async guard) → cancel packet →
build `HitContext` (SprintWire verdict, `CompensationQuery`, view-frozen profile +
OCM flag) → `FeedbackPlan` computed BEFORE the pacing gate (gate sees only the
velocity component; `auto` = max/2−1 ticks) → `BurstSender` ship / pin →
`desk.submitFromWire` → `Scheduling.runOn(victim, applier, retired=retract)`.
Applier: `isOwnedByCurrentRegion(attacker)` gate (cross-region = logged skip),
activeInbound slot around `victim.damage(amount, attacker)`, vanilla-shaped amount
in 4A2 (legacy damage arrives in 4B). Non-player targets: Paper live path / Folia
vanilla passthrough (port the old behavior exactly). Reach validation (P5)
default-OFF port with the ping-rewound ring, bias-to-allow, anticheat deference.
AnticheatCompatUnit: port detection + policy volatiles feeding the gate.

### Task 4A2.1: Knockback family units
`KnockbackUnit` (EDBEE MONITOR: read-or-mint tx via DamageRouter slot, dispatch on
`HitSource` — a `RodPull` is not melee (B6), engine compute with
`CompensationQuery`, `desk.submit`, `awaitVelocityEvent`; accepted sprint-bonus
obligations: `setSprinting(false)` + attacker-session `ledger.scaleHorizontal(0.6)`
+ `SprintWire.onServerClear` signal; combos flag → desk record policy);
`FishingKnockbackUnit` (mints `RodPull` BEFORE `victim.damage(rodder)`; rod KB from
angler position; arms the 20-tick window at lastDamage=0 semantics);
`RodVelocityUnit` (RodLaunchMath + `desk.ensure`); `ProjectileKnockbackUnit`
(Thrown/Arrow sources, PunchMath, **1.21.2+ substitution no-op preserved**);
`LatencyCompensationUnit` (RTT probe cadence via `repeatAsync` — transport only;
per-hit answers come from `CompensationQuery`); `WtapRegistrationUnit` (enables
`SprintWire` consult in the fast path). All are pure vector computers — the desk
alone applies and records.

> **GATE AMENDMENT (2026-07-01, after the first 4A2 run):** the 7-server concurrent
> `scripts/integration-matrix.sh` FAILS on this host for the OLD plugin (host
> starvation: 4 FAIL / 3 PASS concurrent, PASS in isolation). Local live gates for
> the rest of Phase 4 therefore use the SEQUENTIAL chain
> `./gradlew integrationTestMatrix` (one server at a time); the concurrent script
> remains valid on beefier hosts/CI. Additionally, three small 4E seams are pulled
> forward into 4A2.2 because every live gate needs them: the minimal `mental
> reload` command executor, the v5 `Mental` facade registration (`apiVersion()=2`,
> ServicesManager + static holder), and the global-profile management seam
> (overlay `knockback.profile` write + reloadAll + `KnockbackProfileChangeEvent`,
> API shape verbatim).

### Task 4A2.2: Tester adaptation + THE SWAP + 4A2 gate
Trim the tester suite list to `{Boot, Knockback, Profile, Fishing, Projectile,
EraParity, Reload, ZeroTouch}` (edit `MentalTesterPlugin`'s selection; delist suites
whose families aren't ported; fix any internal-class imports in the retained suites
to v5 equivalents). Flip `plugin.yml` `main:` to `me.vexmc.mental.v5.MentalPluginV5`.
Gate: `./gradlew build` + `scripts/integration-matrix.sh` with FRESH PASS evidence
(mtimes) on all retained suites, all matrix versions. The EraParity oracle must
derive its expectations from the kernel (`Decay`/`KnockbackEngine`) — if the old
suite re-implements motion math, port it onto kernel calls.

## Sub-phase 4B — damage family (contract level; detail at open)
`DamageShaper` composition in fixed legacy order feeding `DamageRouter`; units:
ArmourStrength (EntityDamageEvent-wide, DefenceMath), ArmourDurability, CritFallback
(both paths via the SAME token query on HitContext — the forgotten-gate class dies),
ToolDurability (max_damage component), legacy tool damage on the fast path
(DamageTables + `EffectiveMaterial` PDC shell + charge reset per hit), SwordBlocking
(+ ShieldReduction, `EphemeralDecoration` service with the canonical exit-trigger
set + pre-save reconciliation, `SwordBlockComponents` adapter on `PlatformProbe`
with boot-probe + loud-fail, block-hit sprint reset gated on the RAW client flag),
mid-invuln difference-damage semantics (§4.6: stronger = difference/no KB/no sound;
weaker = nothing; 0-damage accepted hits knock FULL and arm 20 ticks). Suites back:
`Damage`, `Blocking`. Vanilla-shaped OCM handoff pin: sharpness-5 diamond = 14.25.

## Sub-phase 4C — cadence + sustain (contract level)
AttackCooldown as a COMPLETE contract (B5): charge reset on BOTH damage paths +
attr-spoof packet mutation on packet-local copies + tooltip hider via
`PlatformProbe` (loud-fail) + 1.9-sweep re-disable; AttackSounds + Sweep (event +
netty halves INSIDE the unit's scope — no split-brain); GoldenApples (B13: compute
pure, apply at confirmed consume), EnderPearlCooldown, Regen (per-player repeatOn),
PotionDurations/Values (B13 for splash/lingering: terminal-event application).
Suites back: `Consumable`, `CosmeticSmoke`.

## Sub-phase 4D — loadout (contract level)
Crafting, Offhand (OffhandPolicy + ephemeral decoration reuse), Hitbox
(EraReach via attribute where available / NMS adapter where not, documented
client-owned limit). Suites back: `Hitbox`, `Inventory`.

## Sub-phase 4E — deletion + minimal command surface (contract level)
Delete the old core: `MentalPlugin`, `engine/`, `module/`, `config/MentalConfig*`,
`gui/`, `manage/`, old `hitreg`/`compensation`/`knockback`/`ocm` packages, command
tree + Brigadier bridge (and `compat-brigadier` from settings.gradle.kts), pruning
`common/` to what v5 uses (Scheduling, Capabilities, debug — Phase 5 finishes the
split). Keep `api/` intact (shapes frozen). Add the minimal v5 command: plugin.yml
`mental` executor — no args ⇒ placeholder message (GUI lands Phase 6), `reload` ⇒
`reloadAll` with permission `mental.command.reload`. Adapt `CommandSuite`; restore
the FULL suite list; full matrix gate + OCM coexistence run
(`./gradlew integrationTestOcm` if the OCM jar is staged). `Mental.register` API
impl moves to v5 (facade + ServicesManager registration; `apiVersion()` returns 2).

---

## Execution notes
- One executor dispatch per sub-phase; the orchestrator expands 4B–4E task detail
  at each open, carrying forward the previous sub-phase's outcomes.
- Every sub-phase report: per-task commits, test counts, RAW gate outputs
  (build tails, matrix output, test-results.txt freshness evidence), deviations.

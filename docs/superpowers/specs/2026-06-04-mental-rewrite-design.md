# Mental — Ground-Up Rewrite Design

**Date:** 2026-06-04
**Status:** Approved (autonomous execution authorized by owner)
**Supersedes:** the legacy plugin, v4.0.1 (single-version Paper 26.1.2)

## 1. Goals

Rewrite the legacy plugin from scratch as **Mental**: a latency-compensated combat plugin
delivering authentic 1.8 combat feel on modern servers.

| Requirement | Target |
| --- | --- |
| Server range | Paper 1.17.1 → 26.1.2, every major version between |
| Folia | First-class support (`folia-supported: true`, region-safe everywhere) |
| Features | Async hit registration (fast path), 1.8 melee knockback, latency compensation, **1.8 fishing rod knockback + cast velocity**, **1.8 ranged projectile knockback** |
| Commands | Ground-up rewrite, player-friendly client-side UX (Brigadier where available) |
| Debug | Runtime-toggleable verbose logging, per-category |
| Anticheat | Vulcan / GrimAC compatibility by construction + detection-driven policy |
| Tests | Unit suite + integration tests booting real Paper servers per version (OCM-inspired) |
| Quality | Modern Java (17 bytecode floor), meticulous detail, performance-first, self-documenting code |

## 2. Constraints discovered in research

These facts drive the architecture; each was verified against primary sources
(docs.papermc.io, jd.papermc.io, minecraft.wiki, Folia README, OCM + knockback-sync source).

1. **Version timeline:** 1.17 → 1.21.11 is the last of the `1.x` scheme; 2026 switched
   to year-based versions (26.0, 26.1, 26.1.2 = `YY.D.H`). There is no 25.x.
2. **Java floors:** 1.17 → Java 16 (17 universally used), 1.18–1.20.4 → 17,
   1.20.5–1.21.11 → 21, 26.1+ → 25. Therefore the plugin must ship **Java 17 bytecode**
   (the newest floor that loads on every supported server JVM).
3. **`plugin.yml` + `api-version: 1.17`** is valid across the entire range (the value is
   a *minimum*). `paper-plugin.yml` is still experimental → not used.
4. **Folia schedulers** (`GlobalRegionScheduler`, `RegionScheduler`, `EntityScheduler`,
   `AsyncScheduler`) exist in modern paper-api but **cannot be assumed on 1.17–1.19**.
   `BukkitScheduler` is illegal on Folia. → A scheduling abstraction is mandatory.
   Folia detection: `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`.
5. **Brigadier command API** (`io.papermc.paper.command.brigadier`, `LifecycleEvents.COMMANDS`)
   exists since Paper 1.20.6 only. Classic `CommandExecutor`/`TabCompleter` works everywhere.
6. **Binary break:** `org.bukkit.attribute.Attribute` was an enum with `GENERIC_*`
   constants through 1.21.1 and is a registry-backed interface with unprefixed constants
   from 1.21.3. Compile-time references to either form break on the other side.
   → Attribute constants must be resolved reflectively (cached `MethodHandle`s).
7. **`Enchantment.KNOCKBACK`** survives unchanged through 26.1.2. `FishHook` (interface)
   is stable across the range; `EntityType.FISHING_HOOK`/`FISHING_BOBBER` constant naming
   is not → use `instanceof FishHook`, never the enum constant.
8. **Anticheats:** GrimAC and Vulcan run movement-prediction engines. Velocity applied
   server-side (`PlayerVelocityEvent` → `setVelocity`) is *by construction* compatible:
   the server's own model produces the packet, so prediction matches. **Out-of-band
   velocity packets (the legacy pre-send fast path) are the one risky behavior** and
   must be policy-gated when an anticheat is detected. Grim's public API is observational
   (`FlagEvent`, `CompletePredictionEvent`) — no "register custom KB" hook exists; Vulcan
   has no public API. Hurt-animation pre-send is cosmetic and prediction-irrelevant.
9. **Play Ping/Pong packets** were added in **1.17** — exactly our floor — giving a
   dedicated probe channel that cannot interfere with vanilla keepalive kick logic.
10. **PacketEvents 2.12.1** supports 1.8→26.1.2 and Folia; OCM ships it shaded+relocated
    to thousands of servers. → Shade + relocate (no external plugin dependency).
11. **OCM's integration testing** works by booting real Paper servers per version via the
    `run-paper` Gradle plugin with the plugin + a test-runner plugin installed; tests
    execute inside the live server, write a PASS/FAIL result file, and Gradle verifies it.
    Per-version JVM toolchains (17/21/25). This is the model to replicate.
12. **1.8 mechanics reference (from OCM source):**
    - *Melee KB:* friction `v/2`, base `0.4/0.4`, bonus per (sprint + KB level) `0.5/0.1`,
      vertical cap `0.4` applied before bonus, optional KB-resistance honoring.
    - *Rod KB:* victim `v/2` + `0.4` horizontal toward hook-to-victim direction,
      `y + 0.4` capped at `0.4`; tiny damage (`0.0001`) through the normal damage call;
      drag-in cancelled via `PlayerFishEvent(CAUGHT_ENTITY)` + hook removal.
    - *Rod cast:* direction from yaw/pitch × `0.4`, normalized, gaussian jitter ×
      `0.0075`, × `1.5`; modern gravity compensation `-0.01/tick` while airborne
      (gravity changed 0.04 → 0.03 in 1.14; our whole range is post-1.14).
    - *Projectile KB:* 1.9+ stopped applying KB for zero-damage projectiles
      (snowball/egg/ender pearl). Restore by substituting a negligible damage
      (`0.0001`) when `damage == 0`, zeroing the ABSORPTION modifier; vanilla then
      applies its own (correct) projectile knockback.

## 3. Approaches considered

**A. Single module + pervasive reflection (OCM style).** One source tree compiled against
the newest API, every newer-than-floor touchpoint behind hand-rolled reflection.
*Rejected:* stringly-typed reflection everywhere, no compile-time safety for the very
code most likely to break, and our floor (1.17) doesn't need 1.9-era heroics.

**B. Paperweight-userdev + NMS per version.** Full internals access, per-version source sets.
*Rejected:* massive maintenance surface, mapping churn (Spigot→Mojang at 1.20.5),
and nothing we need requires NMS — PacketEvents covers the wire, Bukkit API the rest.

**C. Multi-module Gradle with a thin capability-gated compat layer. (Chosen.)**
Core compiles against **paper-api 1.17.1** → the common path is binary-safe everywhere
by construction. Version-specific code (Folia schedulers, Brigadier) lives in a separate
module compiled against **paper-api 1.20.6**, merged into the shadow jar, and **classloaded
only behind feature detection**. Compile-time safety on both sides of every version line;
reflection confined to one tiny attribute resolver where Bukkit itself broke binary compat.

## 4. Architecture

### 4.1 Modules & packages

```
Mental/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
├── api/        → me.vexmc.mental.api        (public events + facade; paper-api 1.17.1)
├── core/       → me.vexmc.mental            (the plugin; paper-api 1.17.1; shadow assembly)
├── compat/
│   └── modern/ → me.vexmc.mental.compat.modern (paper-api 1.20.6; loaded via Capabilities)
└── tester/     → me.vexmc.mental.tester     (integration-test plugin; never shipped)
```

```
me.vexmc.mental
├── MentalPlugin                  bootstrap, lifecycle, wiring
├── platform/
│   ├── Capabilities              feature detection (FOLIA, BRIGADIER, HURT_ANIMATION, …)
│   ├── ServerEnvironment         parsed version + capability report (logged at boot)
│   ├── scheduling/               Scheduling, TaskHandle, BukkitScheduling
│   └── attribute/Attributes      MethodHandle resolver (ATTACK_DAMAGE ⇄ GENERIC_…)
├── engine/                       CombatModule, ModuleRegistry
├── config/                       MentalConfig + per-module settings records (atomic swap)
├── module/
│   ├── hitreg/                   packet listener, state cache, dispatcher, applier, …
│   ├── knockback/                engine + module (melee)
│   ├── compensation/             latency tracker, probes, motion math, hints
│   ├── fishing/                  FishingKnockbackModule, FishingRodVelocityModule
│   ├── projectile/               ProjectileKnockbackModule
│   └── anticheat/                AnticheatCompatModule, AnticheatPolicy
├── command/                      spec DSL + Bukkit renderer + nodes
├── debug/                        DebugLog, DebugCategory, sinks
└── text/                         Brand, Messages (Adventure)

me.vexmc.mental.compat.modern     (only classloaded when capability present)
├── FoliaScheduling               implements Scheduling via region/entity/async schedulers
└── BrigadierBridge               renders the command spec via LifecycleEvents.COMMANDS
```

### 4.2 Scheduling abstraction (the Folia keystone)

```java
public interface Scheduling {
    void runGlobal(Runnable task);
    void runAt(Location location, Runnable task);
    void runOn(Entity entity, Runnable task, Runnable retired);
    void runAsync(Runnable task);
    TaskHandle repeatGlobal(long initialTicks, long periodTicks, Runnable task);
    TaskHandle repeatOn(Entity entity, long initialTicks, long periodTicks, Runnable task, Runnable retired);
    TaskHandle repeatAsync(Duration initial, Duration period, Runnable task);
}
```

- **BukkitScheduling** (core): `BukkitScheduler` — correct on every non-Folia server 1.17→26.x.
- **FoliaScheduling** (compat-modern): global/region/entity/async schedulers — required on Folia.
- Selection at boot: `Capabilities.FOLIA ? load("…FoliaScheduling") : new BukkitScheduling(plugin)`.
- **No global entity-iterating tick task anywhere** (illegal on Folia). Per-entity periodic
  work (player state snapshots, rod-hook gravity) uses `repeatOn(entity, …)` so each task
  runs on its owner's region thread on Folia and on the main thread on Paper.

### 4.3 Module framework

`CombatModule`: abstract base — `id()`, `displayName()`, `enable()/disable()/reload()`,
listener registration helpers, scoped `DebugLog` handle, enabled-state introspection.
`ModuleRegistry`: ordered lifecycle with per-module exception isolation (one failure never
takes down siblings), status surface consumed by `/mental` dashboard.

Modules: `hit-registration`, `knockback`, `latency-compensation`, `fishing-knockback`,
`rod-velocity`, `projectile-knockback`, `anticheat-compat`.

### 4.4 Combat behavior (ported + new)

**Melee knockback** — the legacy engine with one authenticity fix: the vertical
limit clamps the *base* Y **before** bonus levels are added (vanilla-1.8/OCM ordering,
so sprint hits reach 0.5; the legacy code clamped after, flattening sprint verticals).
Direction-normalized base KB with friction, sprint+enchant bonus, optional
armor-KB-resistance honoring; computed at `EntityDamageByEntityEvent
(MONITOR)`, stashed per-victim, applied at `PlayerVelocityEvent (HIGH)` — i.e. **always
server-authoritative** (anticheat-safe). Additions from OCM: skip when shield BLOCKING
modifier absorbed the hit; pending-KB entries expire after 2 ticks.

**Hit registration** — port of the netty fast path: PacketEvents `INTERACT_ENTITY/ATTACK`
listener → CPS limiter → snapshot validation → cancellable `AsyncHitRegisterEvent` →
cancel vanilla packet → optional pre-send feedback → damage scheduled via
`Scheduling.runOn(victim, …)`. Per-player state snapshots move from a global tick task to
per-player `repeatOn` tasks (Folia-correct). Pre-send **velocity** is gated by
`AnticheatPolicy`; pre-send **hurt animation** uses `HURT_ANIMATION` packets on 1.19.4+
and `ENTITY_STATUS(2)` below (PacketEvents server-version check).

**Latency compensation** — port of the KnockbackSync-derived algorithm unchanged
(ping-offset 25 ms, spike filter, IQR jitter, `MotionMath` gravity simulation, 4-corner
ground probe, Y-override hints consumed by the KB engine). New: configurable
`probe-strategy: PING | KEEPALIVE` — `PING` (default) uses the 1.17+ Play Ping/Pong
channel with a distinctive ID base so it can never collide with vanilla keepalive logic
or anticheat transaction tracking; `KEEPALIVE` preserved as fallback.

**Fishing rod knockback** *(new)* — OCM semantics: `ProjectileHitEvent` where the
projectile `instanceof FishHook` and hit entity is living → ignore self/creative/NPC →
apply tiny configured damage through `damage(amount, rodder)` (normal event chain, so
region plugins keep working) → set 1.8 KB vector (`v/2 ± 0.4`, `y+0.4` cap `0.4`).
`PlayerFishEvent(CAUGHT_ENTITY)` cancels vanilla drag-in (configurable: players/mobs/all/none)
and removes the hook. Honors victim invulnerability window (`noDamageTicks > max/2` skip).

**Fishing rod cast velocity** *(new)* — OCM semantics: on `PlayerFishEvent(FISHING)`,
replace hook launch velocity with the 1.8 formula (yaw/pitch direction × 0.4 → normalize →
gaussian ×0.0075 → ×1.5), then a per-hook `repeatOn(hook, 1 tick)` gravity task applies
`-0.01 vy` while airborne (our entire range has 0.03 gravity), retiring with the hook.

**Projectile knockback** *(new)* — OCM semantics: `EntityDamageByEntityEvent` from
snowball/egg/ender pearl with `damage == 0` → substitute configured `0.0001`, zero the
ABSORPTION modifier when applicable; vanilla's own projectile KB then applies. No custom
velocity math → zero anticheat surface.

### 4.5 Anticheat compatibility

`AnticheatCompatModule` detects known anticheats (`GrimAC`, `Vulcan`, extensible config
list) at enable + on `PluginEnableEvent`, and publishes an `AnticheatPolicy`:

- `mode: auto` *(default)* — anticheat present ⇒ `allowVelocityPreSend() == false`
  (fast path stays; only the out-of-band velocity packet is suppressed — the same hit
  still lands with vanilla-cadence server velocity). Hurt-animation pre-send stays on.
- `mode: force-safe` — always behave as if an anticheat is present.
- `mode: off` — never adjust (operator opted out).

Boot log states exactly what was detected and which behaviors were adjusted. The policy is
re-evaluated live when plugins enable/disable, and surfaced in `/mental` + debug logs.
No flag-cancelling, no exemptions: compatibility is achieved by keeping every
gameplay-affecting velocity server-authoritative, which is what prediction engines verify.

### 4.6 Command system

One declarative tree (`CommandSpec`: literals, typed arguments with suggesters,
permissions, descriptions, executors) rendered by two backends:

- **BukkitRenderer** (core): `plugin.yml` command + `CommandExecutor`/`TabCompleter` — everywhere.
- **BrigadierBridge** (compat-modern, 1.20.6+): native Brigadier nodes via
  `LifecycleEvents.COMMANDS` → client-side argument validation, rich completion.

UX (root `/mental`, alias `/mtl`):

| Command | Behavior |
| --- | --- |
| `/mental` | Interactive dashboard: every module with state, click-to-toggle, hover docs |
| `/mental module <name> <on\|off\|status>` | Manage any module (tab/click completes module ids) |
| `/mental ping [player]` | RTT, jitter, spike state, probe strategy |
| `/mental debug <on\|off>` / `/mental debug category <name> <on\|off>` | Runtime debug control |
| `/mental reload` | Atomic config snapshot swap, timed |
| `/mental version` | Version, platform, capability report |
| `/mental help` | Clickable command list |

All toggles persist to `config.yml`. Permission tree `mental.command.*` mirroring nodes.

### 4.7 Debug subsystem

`DebugLog` with per-category switches (`HITREG, KNOCKBACK, COMPENSATION, FISHING,
PROJECTILE, PACKETS, ANTICHEAT, SCHEDULING, COMMANDS, CONFIG`); supplier-based messages
(zero cost when off — one volatile read); sinks: console (`[Mental/debug:<cat>]`) and
opted-in admins (`/mental debug subscribe`, permission-gated). State persisted in config,
toggleable live.

### 4.8 Configuration

`config.yml` v1 (fresh schema; new data folder `plugins/Mental/` so no migration needed):

```yaml
config-version: 1
modules:
  hit-registration: {enabled, max-cps, fast-path: {enabled, pre-send-feedback, feedback-min-interval-ms, simulate-crits, reset-attack-cooldown}}
  knockback:        {enabled, base{h,v}, extra{h,v}, limits{h,v}, friction{x,y,z}, modifiers{sprint, armor-resistance, shield-blocking-cancels}}
  latency-compensation: {enabled, probe-strategy, ping-offset-ms, spike-threshold-ms, probe-interval-ticks, combat-timeout-ticks, off-ground-sync}
  fishing-knockback: {enabled, damage, cancel-dragging-in, knockback-non-player-entities}
  rod-velocity:      {enabled}
  projectile-knockback: {enabled, damage: {snowball, egg, ender-pearl}}
anticheat: {mode, known: [GrimAC, Vulcan]}
debug: {enabled, categories: {…}}
```

Typed immutable records per section, parsed once, swapped atomically on reload
(`AtomicReference`) — no torn reads mid-hit. Unknown keys warn; invalid values fall back
to defaults with a console warning naming the path.

### 4.9 Public API (`mental-api`)

- `AsyncHitRegisterEvent` — unchanged semantics (async, cancellable, fired pre-vanilla).
- `KnockbackApplyEvent` — sync, mutable vector, fired on the owning thread just before
  velocity application (lets region/minigame plugins adjust or veto).
- `Mental` facade — `moduleEnabled(String)`, `ping(Player)`, version info.

### 4.10 Build & toolchain

- Gradle (Kotlin DSL) + version catalog; wrapper 9.x.
- Java **toolchain 25** for building, `options.release = 17` → Java 17 bytecode everywhere.
- `core` + `api`: compileOnly `io.papermc.paper:paper-api:1.17.1-R0.1-SNAPSHOT`.
- `compat/modern`: compileOnly `paper-api:1.20.6-R0.1-SNAPSHOT`.
- PacketEvents `2.12.1` shaded, relocated to `me.vexmc.mental.lib.packetevents.{api,impl}`.
- Shadow jar `Mental-<version>.jar` = api + core + compat-modern + relocated packetevents.
- `plugin.yml`: `name: Mental`, `main: me.vexmc.mental.MentalPlugin`, `api-version: '1.17'`,
  `folia-supported: true`, commands + permission tree. No external plugin dependencies.

### 4.11 Testing

**Unit (core, JUnit 5):** knockback engine vectors (all branches: friction, caps, sprint,
enchant, resistance, Y-override), `MotionMath` against hand-computed vanilla traces,
`JitterCalculator` IQR filtering, `CpsLimiter` window math, `HitFeedbackGate` atomicity,
config parsing (defaults, invalid values, round-trip) via `MemoryConfiguration`.

**Integration (tester module, OCM-inspired, pure Java):**
- `MentalTesterPlugin` boots inside a real server, runs a registered suite on a dedicated
  driver thread with `sync(Callable)` / `awaitTicks(n)` bridges, writes
  `test-results.txt` (PASS/FAIL) + `test-failures.txt`, then shuts the server down.
- `FakePlayer`: reflection-built `ServerPlayer` (reflection-remapper for 1.20.5+ Mojang
  mappings, no-op below), join/spawn/tick, equipment control — trimmed to 1.17+.
- Suite: plugin+modules enable cleanly on this version; melee hit produces the engine's
  exact vector via `PlayerVelocityEvent`; fishing hook KB + drag-in cancel; projectile
  damage substitution; command tree executes + completes; reload under load; capability
  report matches expectations for the booted version.
- Gradle: per-version `runIntegrationTest_<v>` (run-paper, headless, log capture,
  toolchain-matched JVM) + `checkIntegrationTest_<v>` result verification.
  Default `integrationTest` = floor + latest (1.17.1, 26.1.2);
  `integrationTestMatrix` = 1.17.1, 1.18.2, 1.19.4, 1.20.6, 1.21.4, 1.21.11, 26.1.2.
  JVMs: 17 (≤1.20.4), 21 (1.20.6–1.21.11), 25 (26.x).
- Folia: boot smoke test on latest Folia (module enable + scheduling sanity) where a
  Folia runtime is obtainable; otherwise scheduling correctness is enforced by design
  (no `BukkitScheduler` on the Folia path) and the capability report test.

### 4.12 CI

- `build.yml`: JDK 17/21/25 setup, `gradle build` (compile + unit), integration matrix
  `[1.17.1, 1.20.6, 1.21.11, 26.1.2]` fail-fast, upload `Mental.jar` artifact.
- `release.yml`: tag-triggered, full matrix, GitHub release with jar.

### 4.13 Migration & repo hygiene

- Delete Maven/Eclipse artifacts (`pom.xml`, `.classpath`, `.project`, `.settings/`,
  `.factorypath`, the legacy `.iml`, tracked `target/` outputs) and the old source tree.
- `FAST_PATH.md` → rewritten `docs/fast-path.md` for Mental.
- README rewritten (features, version matrix, install, commands, config, API, anticheat
  notes, acknowledgements preserved: knockback-sync GPL-3.0 algorithm adaptation, SmashHit
  concept, OCM-inspired fishing/projectile mechanics + test harness).
- Version reset to `1.0.0`. License stays MIT.
- GitHub repo rename to `Mental` is an owner action outside the working tree;
  flagged as a manual follow-up.

## 5. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| paper-api 1.17.1 snapshot availability | Verified coordinate format; OCM proves old-artifact resolution works; fallback: 1.18.2 floor for *compilation* only (runtime floor stays 1.17 via api-version + reflection-free common path) |
| Brigadier API drift 1.20.6 → 26.x | Bridge is one small class; integration matrix includes 1.20.6 and 26.1.2 |
| Attribute resolver misses a rename | Resolver tries registry → modern field → `GENERIC_` field, logs the resolution path at boot; unit-tested against both shapes |
| Folia runtime unavailable in CI | Design-level guarantee (no BukkitScheduler on Folia path) + boot smoke when obtainable |
| FakePlayer reflection per version | Scoped to tester (never ships); matrix catches breakage; reflection-remapper handles mapping flips |
| Pre-send velocity flagged by AC | Default `auto` policy disables it when AC detected; documented loudly |

## 6. Out of scope

bStats/metrics, mob-targeted melee KB customization (vanilla handles mobs), sweep/durability
/hunger semantics beyond the current fast-path contract, 1.16-and-below support, Fabric/Velocity
platforms, per-world modesets (config is global; per-module toggles cover the use cases).

# Mental Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the legacy plugin from the ground up as **Mental** — a multi-version (Paper 1.17.1→26.1.2), Folia-supporting, anticheat-compatible 1.8-combat plugin with fishing-rod/projectile knockback, a new command system, debug subsystem, and real-server integration tests.

**Architecture:** Multi-module Gradle build. `:api` (public events/facade) and `:common` (internal SPI: Scheduling, Capabilities, DebugLog, command-spec model) compile against paper-api **1.17.1**; `:core` (the plugin + all modules, shadow assembly) compiles against **1.17.1**; `:compat-modern` (FoliaScheduling, BrigadierBridge) compiles against **1.20.6** and is class-loaded only behind feature detection; `:tester` is a separate integration-test plugin booted alongside Mental inside real Paper servers per version.

**Tech Stack:** Java 17 bytecode (JDK 25 toolchain), Gradle 9.5.1 Kotlin DSL + version catalog + foojay toolchain resolver, PacketEvents 2.12.1 (shaded/relocated), Shadow 9.4.2, run-paper 3.0.2, JUnit 5, reflection-remapper (tester only).

**Reference material (in this repo / on this machine — do not delete until Phase 9):**
- Old sources: the legacy `src/main/java` tree (canonical port source for hitreg/compensation/knockback/config/messages)
- OCM repo: `/Users/owengregson/Documents/BukkitOldCombatMechanics` (build wiring: `build.gradle.kts`; fishing: `ModuleFishingKnockback.java`, `ModuleFishingRodVelocity.java`; projectile: `ModuleProjectileKnockback.java`; tests: `src/integrationTest/kotlin/**`, esp. `FakePlayer.kt`)
- Spec: `docs/superpowers/specs/2026-06-04-mental-rewrite-design.md`

**Execution decision:** Inline execution (executing-plans) — the executor carries full research context; subagent-per-task would discard it.

**Spec amendment (locked here):** Vertical limit clamps the **base** Y *before* the sprint/enchant vertical bonus is added (vanilla-1.8/OCM ordering; sprint hits reach y=0.5). Amend spec §4.4 wording in Task 10.

---

## Phase 0 — Scaffold

### Task 1: Gradle multi-module scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `api/build.gradle.kts`, `common/build.gradle.kts`, `core/build.gradle.kts`, `compat/modern/build.gradle.kts`, `tester/build.gradle.kts`
- Copy from OCM: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` (Gradle 9.5.1)
- Modify: `.gitignore` (add `.gradle/`, `build/`, `run/`, keep `target/` ignored)
- Delete: `pom.xml`, `.classpath`, `.project`, `.factorypath`, `.settings/`, the legacy `.iml`, `.vscode/settings.json` (old src/ stays until Phase 9)

- [ ] **Step 1:** `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Mental"

include(":api", ":common", ":core", ":compat-modern", ":tester")
project(":compat-modern").projectDir = file("compat/modern")
```

- [ ] **Step 2:** `gradle/libs.versions.toml`:

```toml
[versions]
paper-floor = "1.17.1-R0.1-SNAPSHOT"
paper-modern = "1.20.6-R0.1-SNAPSHOT"
packetevents = "2.12.1"
annotations = "26.0.2"
junit = "5.11.4"
reflection-remapper = "0.1.2"
shadow = "9.4.2"
run-paper = "3.0.2"

[libraries]
paper-api-floor = { module = "io.papermc.paper:paper-api", version.ref = "paper-floor" }
paper-api-modern = { module = "io.papermc.paper:paper-api", version.ref = "paper-modern" }
packetevents-spigot = { module = "com.github.retrooper:packetevents-spigot", version.ref = "packetevents" }
jetbrains-annotations = { module = "org.jetbrains:annotations", version.ref = "annotations" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-launcher = { module = "org.junit.platform:junit-platform-launcher" }
reflection-remapper = { module = "xyz.jpenilla:reflection-remapper", version.ref = "reflection-remapper" }

[plugins]
shadow = { id = "com.gradleup.shadow", version.ref = "shadow" }
run-paper = { id = "xyz.jpenilla.run-paper", version.ref = "run-paper" }
```

(Verify `reflection-remapper` version against OCM's build file; bump if theirs is newer.)

- [ ] **Step 3:** Root `build.gradle.kts` — shared config for all subprojects:

```kotlin
plugins {
    `java-library`
}

allprojects {
    group = "me.vexmc"
    version = "1.0.0"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://repo.codemc.io/repository/maven-snapshots/")
    }
}

subprojects {
    apply(plugin = "java-library")
    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
        options.encoding = "UTF-8"
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "skipped", "failed") }
    }
}
```

- [ ] **Step 4:** Module build files. `api/` + `common/`: compileOnly paper-api-floor + annotations. `core/`: shadow plugin, api/common as `api(project(...))`, compat-modern wired into the shadow jar without a compile dependency:

```kotlin
// core/build.gradle.kts
plugins { alias(libs.plugins.shadow) }

dependencies {
    api(project(":api"))
    api(project(":common"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
    implementation(libs.packetevents.spigot)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.paper.api.floor)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveBaseName.set("Mental")
    archiveClassifier.set("")
    from(project(":compat-modern").sourceSets.main.get().output)
    relocate("com.github.retrooper.packetevents", "me.vexmc.mental.lib.packetevents.api")
    relocate("io.github.retrooper.packetevents", "me.vexmc.mental.lib.packetevents.impl")
    minimize { exclude(project(":api")); exclude(project(":common")) }
}

tasks.build { dependsOn(tasks.shadowJar) }
```

```kotlin
// compat/modern/build.gradle.kts
dependencies {
    compileOnly(project(":common"))
    compileOnly(libs.paper.api.modern)
    compileOnly(libs.jetbrains.annotations)
}
```

```kotlin
// tester/build.gradle.kts
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":common"))
    compileOnly(project(":core"))
    compileOnly(libs.paper.api.floor)
    implementation(libs.reflection.remapper)
}
// shadow or plain jar with remapper shaded — add shadow plugin, relocate xyz.jpenilla.reflectionremapper -> me.vexmc.mental.tester.lib
```

NOTE: `from(project(":compat-modern")...output)` requires `evaluationDependsOn(":compat-modern")` or task dependency — wire `tasks.shadowJar { dependsOn(":compat-modern:classes") }`. If shadow `minimize` misbehaves with project outputs, drop `minimize` (correctness > size).

- [ ] **Step 5:** Verify: `./gradlew projects` lists all five modules; `./gradlew help` succeeds.
- [ ] **Step 6:** Commit `build: replace Maven with multi-module Gradle scaffold`.

### Task 2: Repo hygiene + gradle.properties

- [ ] `gradle.properties`: `org.gradle.parallel=true`, `org.gradle.caching=true`, `mentalVersion` not needed (version in root build).
- [ ] `.gitignore`: append `.gradle/`, `build/`, `*/build/`, `run/`, `bin/`.
- [ ] `git rm -r --cached target/` if tracked; delete Eclipse/IDEA artifacts listed in Task 1.
- [ ] Commit `chore: remove legacy build artifacts and IDE metadata`.

---

## Phase 1 — Platform layer (`:common` + `:core`)

### Task 3: Capabilities + ServerEnvironment (common)

**Files:** `common/src/main/java/me/vexmc/mental/common/platform/Capabilities.java`, `ServerEnvironment.java`; test `common/src/test/java/.../ServerEnvironmentTest.java` (+ junit deps in `common/build.gradle.kts`)

- [ ] Capability detection by `Class.forName`, computed once into a record:

```java
public record Capabilities(
        boolean folia, boolean brigadierCommands, boolean hurtAnimationPacket,
        boolean registryAttributes, boolean modernSchedulers) {

    public static Capabilities detect() {
        boolean folia = classPresent("io.papermc.paper.threadedregions.RegionizedServer");
        boolean brigadier = classPresent("io.papermc.paper.command.brigadier.Commands");
        boolean modernSchedulers = folia || classPresent("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
        boolean registryAttributes = !org.bukkit.attribute.Attribute.class.isEnum();
        // hurt animation: protocol 1.19.4+, decided via PacketEvents server version at runtime use-site
        ...
    }
}
```

`ServerEnvironment`: parses `Bukkit.getBukkitVersion()` (`"26.1.2-..."` / `"1.20.6-R0.1-SNAPSHOT"`) into `(major, minor, patch)` with year-scheme awareness (`major >= 26` ⇒ new scheme; ordering: any `26.x` > any `1.x`), exposes `isAtLeast(int, int, int)` and a one-line boot report. Version parsing is a **static pure function** `parse(String)` → unit-testable without Bukkit.

- [ ] Unit tests: `parse("1.17.1-R0.1-SNAPSHOT")` → (1,17,1); `parse("26.1.2")` → (26,1,2); `parse("1.21.11-R0.1-SNAPSHOT")` ordering vs (26,0,0); malformed input falls back to (0,0,0) + flagged.
- [ ] `./gradlew :common:test` green. Commit `feat(platform): capability detection and version-aware server environment`.

### Task 4: Scheduling SPI (common) + BukkitScheduling (core)

**Files:** `common/.../scheduling/Scheduling.java`, `TaskHandle.java`; `core/src/main/java/me/vexmc/mental/platform/BukkitScheduling.java`

- [ ] Interface exactly as spec §4.2 (six methods + `repeatOn`); `TaskHandle` = `void cancel(); boolean cancelled();`.
- [ ] `BukkitScheduling`: `runGlobal/runAt` → `runTask`; `runOn(entity, task, retired)` → `runTask` guarding `entity.isValid() || entity instanceof Player p && p.isOnline()` else `retired.run()`; `runAsync` → `runTaskAsynchronously`; repeats → `runTaskTimer`/`runTaskTimerAsynchronously` wrapped in `TaskHandle` delegating to `BukkitTask.cancel()`.
- [ ] Commit `feat(platform): scheduling abstraction with Bukkit implementation`.

### Task 5: Attribute resolver (core)

**Files:** `core/.../platform/Attributes.java`; test `core/src/test/java/.../AttributesTest.java`

- [ ] Cached static lookups for `ATTACK_DAMAGE`, `KNOCKBACK_RESISTANCE`, `ATTACK_SPEED`. Resolution order: static field `ATTACK_DAMAGE` on `org.bukkit.attribute.Attribute` → static field `GENERIC_ATTACK_DAMAGE` → `Registry`-based lookup via reflection (`Bukkit.getRegistry` may not exist on 1.17 — guard). Use `MethodHandles.lookup().findStaticGetter` with fallbacks; memoize per-attribute; expose `Attributes.attackDamage()` etc. and `double valueOr(LivingEntity, Attribute, double def)` helper.
- [ ] Unit test (runs against 1.17 API on test classpath): resolves `GENERIC_ATTACK_DAMAGE` enum constant; helper returns default when attribute instance is null.
- [ ] Commit `feat(platform): cross-version attribute resolution`.

### Task 6: Debug subsystem (common core + core sinks)

**Files:** `common/.../debug/DebugCategory.java` (enum, spec §4.7 list), `DebugLog.java`; `core/.../debug/ConsoleSink.java`, `PlayerSink.java` (added with messages task)

- [ ] `DebugLog`: global volatile `enabled` + `EnumSet`-backed per-category volatile mask (copy-on-write); `void log(DebugCategory cat, Supplier<String> msg)` — single volatile read fast-exit; sink fan-out only when on. Scoped view `DebugLog.Scoped forCategory(cat)`.
- [ ] Unit test: supplier NOT invoked when disabled (counter), invoked when category on; mask toggling thread-visibility smoke.
- [ ] Commit `feat(debug): per-category zero-cost debug logging core`.

### Task 7: Config system (core)

**Files:** `core/.../config/` → `MentalConfig.java` (atomic holder + load), settings records: `HitRegSettings`, `KnockbackSettings`, `CompensationSettings`, `FishingKnockbackSettings`, `RodVelocitySettings`, `ProjectileKnockbackSettings`, `AnticheatSettings`, `DebugSettings`; resource `core/src/main/resources/config.yml`; tests `core/src/test/java/.../MentalConfigTest.java`

- [ ] `config.yml` exactly per spec §4.8 (defaults: KB values 0.4/0.4/0.5/0.1/0.4/-1/0.5/1.0; compensation 25/20/5/30/true, probe-strategy PING; hitreg 20cps/fast-path on/pre-send on/500ms/crits on/reset-cooldown on; fishing damage 0.0001, cancel-dragging-in players, non-player-kb false; projectile damages 0.0001; anticheat mode auto, known [GrimAC, Vulcan]; debug off, all categories listed false). Inline comments document every key.
- [ ] Records parse from `ConfigurationSection` with clamped/validated values (negative → default + warning collector). `MentalConfig.reload(FileConfiguration)` builds all records then swaps one `AtomicReference<Snapshot>` (Snapshot = record of records — single volatile read gets a consistent view).
- [ ] Tests with `YamlConfiguration.loadFromString`: defaults load; invalid value (`max-cps: -5`) falls back with warning recorded; snapshot atomicity (two loads, reference inequality); probe-strategy parse case-insensitive.
- [ ] Commit `feat(config): typed atomic configuration snapshots`.

### Task 8: Branding, messages, module framework (core) + plugin bootstrap

**Files:** `core/.../text/Brand.java`, `Messages.java`; `core/.../engine/CombatModule.java`, `ModuleRegistry.java`; `core/.../MentalPlugin.java`; resource `core/src/main/resources/plugin.yml`

- [ ] `Brand`: port the legacy palette, prefix `Mental` (gold bold "Men" + yellow bold "tal"? No — single clean prefix: `Mental` gold bold + arrow). Keep helpers `prefix/line/success/failure/info`.
- [ ] `CombatModule`: abstract — `id()`, `displayName()`, `description()`, lifecycle `enable/disable/reload`, `listener` helpers (`registerListener(Listener)` tracked for auto-unregister on disable), scoped debug. `ModuleRegistry`: ordered map, exception-isolated lifecycle (`enableAll/disableAll/reloadAll`), `statuses()` for dashboard.
- [ ] `plugin.yml`:

```yaml
name: Mental
version: '${version}'
main: me.vexmc.mental.MentalPlugin
api-version: '1.17'
folia-supported: true
description: Latency-compensated 1.8 combat - async hitreg, knockback, fishing and projectile mechanics.
authors: [owengregson]
website: https://github.com/owengregson/Mental
commands:
  mental:
    description: Mental root command.
    aliases: [mtl]
permissions:
  mental.*: {default: op, children: {mental.command.use: true, mental.command.module: true, mental.command.reload: true, mental.command.debug: true, mental.command.ping: true}}
  mental.command.use: {default: true}
  mental.command.module: {default: op}
  mental.command.reload: {default: op}
  mental.command.debug: {default: op}
  mental.command.ping: {default: true}
```

- [ ] `MentalPlugin`: `onLoad` → PacketEvents builder (`SpigotPacketEventsBuilder.build(this)`, settings `checkForUpdates(false)`), `PacketEvents.getAPI().load()`. `onEnable` → config load, Capabilities/ServerEnvironment boot report, Scheduling selection (Folia ⇒ reflective `FoliaScheduling`, else `BukkitScheduling`), ModuleRegistry registration (modules added in later tasks), command registration, `PacketEvents.getAPI().init()`. `onDisable` → modules disable, PE terminate. Wrap a `MentalServices` record (plugin, config, scheduling, debug, registry, capabilities).
- [ ] Verify `./gradlew build` + `ls core/build/libs/Mental-1.0.0.jar`; `unzip -l` shows relocated `me/vexmc/mental/lib/packetevents/`.
- [ ] Commit `feat(core): plugin bootstrap, module framework, branding`.

### Task 9: compat-modern — FoliaScheduling + loader

**Files:** `compat/modern/src/main/java/me/vexmc/mental/compat/modern/FoliaScheduling.java`; `core/.../platform/SchedulingFactory.java`

- [ ] `FoliaScheduling implements Scheduling` using `getGlobalRegionScheduler()`, `getRegionScheduler()`, `entity.getScheduler()` (with `retired` runnable), `getAsyncScheduler()`; `TaskHandle` wraps `ScheduledTask::cancel`. Note: Folia tick-delay APIs reject delay 0 — clamp `Math.max(1, ticks)` for delayed/rate tasks; `runGlobal` uses `execute`.
- [ ] `SchedulingFactory.create(Plugin, Capabilities)` — reflective construction when `capabilities.folia()`; on any reflective failure log severe + fall back to BukkitScheduling (non-Folia only; on Folia rethrow — Bukkit scheduler would hard-fail anyway).
- [ ] If paper-api 1.20.6 lacks `EntityScheduler` (compile error) → bump `paper-modern` to `1.21.4-R0.1-SNAPSHOT` in the catalog (Brigadier present there too; loading stays capability-gated).
- [ ] Verify `./gradlew :compat-modern:compileJava :core:shadowJar` and jar contains `compat/modern` classes. Commit `feat(platform): Folia scheduling implementation behind capability gate`.

---

## Phase 2 — Combat modules (ports + engine)

### Task 10: KnockbackEngine (TDD)

**Files:** `core/.../module/knockback/KnockbackEngine.java`, `KnockbackVector.java` (record), `EntityState.java` (record: x,y,z,yaw, vx,vy,vz, sprinting, kbResistance, kbEnchantLevel); test `KnockbackEngineTest.java`. Also amend spec §4.4 (vertical-cap ordering sentence).

- [ ] **Step 1 — failing tests** with hand-computed vectors (defaults: base .4/.4, extra .5/.1, limitV .4, limitH -1, friction .5, sprint 1.0):
  - plain hit: attacker (0,64,0) yaw 0, victim (3,64,4) at rest → `(0.24, 0.4, 0.32)`
  - sprint hit (same geometry, sprinting, no enchant): → `(0.24, 0.5, 0.82)`
  - moving victim (vel 0.2,0.3,-0.4): → `(0.34, 0.4, 0.12)` *(base Y: 0.15+0.4=0.55 → clamped 0.4 pre-bonus)*
  - resistance 0.5 honored: plain-hit xz halved → `(0.12, 0.4, 0.16)`
  - zero-distance: attacker==victim position → finite vector, horizontal magnitude ≤ base (random direction; assert no NaN, |y−0.4|<1e-9)
  - horizontal cap 0.3: plain hit → xz scaled to magnitude 0.3
  - Y-override: vicVy override 0.0 with friction → y = 0.4
- [ ] **Step 2:** run `./gradlew :core:test --tests '*KnockbackEngine*'` → compile failure/red.
- [ ] **Step 3:** implement: port the legacy `KnockbackEngine.computeImpl` formula with the **vertical-cap-before-bonus** ordering (matches OCM lines 152-199); deterministic `RandomGenerator` injectable for the zero-distance branch.
- [ ] **Step 4:** tests green. **Step 5:** Commit `feat(knockback): 1.8 knockback engine with authentic vertical-cap ordering`.

### Task 11: KnockbackModule

**Files:** `core/.../module/knockback/KnockbackModule.java`; api: `api/src/main/java/me/vexmc/mental/api/event/KnockbackApplyEvent.java`

- [ ] Port the legacy module: `EntityDamageByEntityEvent@MONITOR(ignoreCancelled)` compute → pending map; `PlayerVelocityEvent@HIGH` apply; quit cleanup. Additions: (a) pending entries stamped with tick + expire >2 ticks (lazily, on next access + periodic sweep piggybacked on apply); (b) skip when shield absorbed hit: `event.isApplicable(BLOCKING) && event.getDamage(BLOCKING) < 0` semantics — port OCM's check `getDamage(DamageModifier.BLOCKING) <= 0` guard direction exactly from `ModulePlayerKnockback.java:143`; (c) fire mutable `KnockbackApplyEvent(victim, attacker, vector)` just before applying; cancelled ⇒ leave vanilla velocity.
- [ ] Compensation hint hookup left as a `KnockbackHints` interface (UUID → OptionalDouble yOverride), no-op until Task 15 wires the compensation module in.
- [ ] Commit `feat(knockback): melee knockback module with apply event and shield guard`.

### Task 12: Hit registration components (units first where pure)

**Files:** `core/.../module/hitreg/`: `PlayerStateCache.java` (+ per-player `repeatOn` snapshot tasks), `CpsLimiter.java`, `HitFeedbackGate.java`, `DamageCalculator.java`, `AsyncVelocitySender.java`, `HurtFeedbackSender.java`, `HitDispatcher.java`, `HitApplier.java`; tests for CpsLimiter/FeedbackGate/DamageCalculator-sharpness
- api: `api/.../event/AsyncHitRegisterEvent.java` (port, package change only)

- [ ] Port from the legacy plugin with these deltas:
  - `PlayerStateCache`: replace the global every-tick all-players task with per-player `scheduling.repeatOn(player, 1, 1, snapshotTask, retired)` started on join/enable, handle removal on quit (Folia-correct). Snapshot record unchanged (15 fields) + built via `Attributes` resolver.
  - `CpsLimiter`, `HitFeedbackGate`: port verbatim; unit tests lock window semantics (acquire/deny/slide; one pre-send per window).
  - `DamageCalculator`: extract `static double sharpnessBonus(int level)` (`level<=0 ? 0 : 1.0 + 0.5*(level-1)`) — unit-tested; attribute base via `Attributes.attackDamage()`.
  - `HurtFeedbackSender`: HURT_ANIMATION wrapper when `PacketEvents...getVersion().isNewerThanOrEquals(ServerVersion.V_1_19_4)` else `WrapperPlayServerEntityStatus(entityId, (byte) 2)`. Same dual-recipient + yaw math as old `AsyncHurtSender`.
  - `HitDispatcher`: now just delegates to `Scheduling.runOn(victim, applyTask, retiredLog)`.
  - `HitApplier`: port (re-resolve, reach check 49.0, damage via `damageable.damage(amount, attacker)`, optional cooldown reset via `player.resetCooldown()` guarded — exists 1.17? `HumanEntity#resetCooldown` exists since 1.16 ✓).
- [ ] Commit `feat(hitreg): hit pipeline components on the scheduling abstraction`.

### Task 13: HitPacketListener + HitRegistrationModule

**Files:** `core/.../module/hitreg/HitPacketListener.java`, `HitRegistrationModule.java`

- [ ] Port listener: PacketEvents `INTERACT_ENTITY`+`ATTACK` on netty thread → CPS gate → snapshot validation (alive/attackable/PvP/gamemode) → fire `AsyncHitRegisterEvent` → fast-path cancel + pre-send + dispatch. **Delta:** pre-send *velocity* additionally requires `services.anticheatPolicy().allowVelocityPreSend()` (interface stub in core defaulting true until Task 17); pre-send hurt-anim gated only by config. Keep dual invulnerability gates (cached immune check biased to skip + per-victim window gate).
- [ ] Module class: registers/unregisters the PE listener + owns cache lifecycle; reload re-reads settings snapshot.
- [ ] `./gradlew build` green. Commit `feat(hitreg): packet fast path with anticheat-gated pre-send`.

### Task 14: Compensation math (TDD) + trackers

**Files:** `core/.../module/compensation/MotionMath.java`, `JitterCalculator.java`, `LatencyTracker.java`, `CombatTracker.java`, `GroundProbe.java`; tests for MotionMath + JitterCalculator + LatencyTracker

- [ ] Port all five from the legacy plugin verbatim (MotionMath MAX_TICKS=30, gravity loop `(v-g)*0.98` capped 3.92; Jitter 15-sample IQR; LatencyTracker outstanding-32 eviction; CombatTracker timeout map; GroundProbe 4-corner 5.0-block raytrace).
- [ ] Tests: MotionMath trace `v=0.4,g=0.08` → tick velocities `(0.3136, 0.228928, 0.14594944…)`, `ticksToApex(0.4,0.08)==5`; Jitter: constant 50ms×15 → 0.0, one 500ms outlier filtered (jitter < 5); LatencyTracker: probe send/response RTT math + unmatched id returns false + eviction at 33rd outstanding.
- [ ] Commit `feat(compensation): motion simulation, jitter, and latency tracking primitives`.

### Task 15: Probe strategies + LatencyCompensationModule

**Files:** `core/.../module/compensation/ProbeStrategy.java` (enum PING/KEEPALIVE), `PingProbe.java`, `LatencyCompensationModule.java`

- [ ] `PingProbe` (PE listener LOWEST): outbound by strategy — PING: `WrapperPlayServerPing(id)` with id base `0x4D450000` + counter; inbound `WrapperPlayClientPong` match → tracker, cancel. KEEPALIVE: port old behavior (id base distinct from vanilla, cancel matched responses). Strategy chosen from config; both ids namespaced to never collide with vanilla/Grim.
- [ ] Module: port — async repeating probe task via `scheduling.repeatAsync`; damage listener `@HIGHEST` computes hint (port `computeHint` chain: compensatedTicks=ceil(ping*20/1000), ground probe, `noDamageTicks>8` guard, apex+fall prediction, off-ground forward simulation); implements `KnockbackHints` consumed by KnockbackModule (wire in `MentalPlugin`). GroundProbe call happens inside the damage event (owning region thread ✓).
- [ ] Commit `feat(compensation): ping/keepalive probe strategies and Y-override hints`.

### Task 16: Fishing + projectile modules (TDD on the pure math)

**Files:** `core/.../module/fishing/RodKnockbackMath.java` + `FishingKnockbackModule.java`, `RodLaunchMath.java` + `FishingRodVelocityModule.java`; `core/.../module/projectile/ProjectileKnockbackModule.java`; tests `RodKnockbackMathTest`, `RodLaunchMathTest`

- [ ] **Tests first** — `RodKnockbackMath.knockback(Vector current, double vx... hookX-victimX deltas)`: hook (2,64,0), victim (0,64,0) at rest → `(-0.4, 0.4, 0.0)`; moving victim halves carry; y cap at 0.4; zero-distance randomized branch finite. `RodLaunchMath.launch(yaw, pitch, gaussianSource)` with zeroed gaussian: yaw=0,pitch=0 → normalize((0,0,0.4))×1.5 = `(0, 0, 1.5)`; yaw=90 → `(-1.5, 0, ~0)`.
- [ ] Implement math (OCM formulas §2.12 of spec, verbatim constants 0.4/0.4/0.4, gaussian 0.0075, multiplier 1.5).
- [ ] `FishingKnockbackModule`: `ProjectileHitEvent@HIGHEST(ignoreCancelled)` — projectile `instanceof FishHook`, shooter `instanceof Player`, hit `LivingEntity`; skip self/creative/`hasMetadata("NPC")`/non-player when config says so; skip when `victim.getNoDamageTicks() > victim.getMaximumNoDamageTicks()/2.0`; `victim.damage(settings.damage(), rodder)` then (if not cancelled — re-check `victim.isValid() && !victim.isDead()` and damage event not cancelled via damage result) `victim.setVelocity(RodKnockbackMath...)`. Match OCM: apply velocity unconditionally after damage call (damage may be cancelled by region plugins → respect: check `((LivingEntity)victim).getLastDamageCause()` cancelled state — port OCM behavior: they apply regardless; we instead listen: if the damage call resulted in a cancelled event, skip velocity. Implement by registering a MONITOR probe? Simpler: call `damage()`, then check `victim.getNoDamageTicks() > 0` freshness? **Decision:** mirror OCM exactly (apply velocity after `damage()` without re-check) — region plugins that cancel damage still cancel `KnockbackApplyEvent`-style mechanics rarely for rods; keep `cancel-dragging-in` for vanilla pull. Note this in javadoc.* `PlayerFishEvent(CAUGHT_ENTITY)@HIGHEST`: per config cancel + `getHook().remove()` (`PlayerFishEvent#getHook` returns FishHook on 1.17+ directly).
- [ ] `FishingRodVelocityModule`: `PlayerFishEvent(FISHING)@HIGHEST` → set hook velocity from math; start `scheduling.repeatOn(hook, 1, 1, gravityTick, cleanup)` applying `vy -= 0.01` while `!hook.isInWater() && !hook.isOnGround() && hook.isValid()`, cancelling handle when hook invalid (retire hook task on removal).
- [ ] `ProjectileKnockbackModule`: `EntityDamageByEntityEvent@NORMAL(ignoreCancelled)`, damager Snowball/Egg/EnderPearl (instanceof checks), `event.getDamage()==0.0` → `event.setDamage(perTypeDamage)`; zero ABSORPTION modifier when applicable (try/catch UnsupportedOperationException).
- [ ] All tests green; commit `feat(combat): 1.8 fishing rod and ranged projectile knockback`.

### Task 17: Anticheat compat module

**Files:** `core/.../module/anticheat/AnticheatPolicy.java` (interface: `allowVelocityPreSend()`, `detectedAnticheats()`), `AnticheatCompatModule.java`

- [ ] Module scans `PluginManager` for configured names (default GrimAC, Vulcan) at enable + listens `PluginEnableEvent`/`PluginDisableEvent` to recompute a volatile policy snapshot. Mode mapping: `auto` → presence ⇒ deny pre-send velocity; `force-safe` ⇒ always deny; `off` ⇒ always allow. Boot + change logs state the exact adjustment ("GrimAC detected — velocity pre-send disabled; hits remain vanilla-cadence"). Policy registered into `MentalServices`; HitPacketListener already consumes it (Task 13).
- [ ] Commit `feat(anticheat): detection-driven pre-send policy for Grim/Vulcan coexistence`.

---

## Phase 3 — Commands

### Task 18: Command spec DSL (common) + dispatch tests

**Files:** `common/.../command/CommandSpec.java`, `LiteralNode.java`, `ArgumentNode.java`, `CommandContext.java`, `Suggestions.java`; test `CommandSpecTest.java`

- [ ] Immutable tree: builder-style `literal("module").permission("mental.command.module").description(...).then(argument("name", moduleIdSuggester).then(literal("on").executes(ctx -> ...)))`. `CommandContext`: sender, raw args, parsed values, reply helpers. Tree exposes `execute(sender, String[])` and `complete(sender, String[])` — both permission-filtered. Pure logic (Bukkit types limited to `CommandSender`) → unit test dispatch: routing, unknown-subcommand fallback message hook, permission filtering of completions, argument capture.
- [ ] Commit `feat(command): declarative command tree model`.

### Task 19: Mental command tree + Bukkit renderer

**Files:** `core/.../command/MentalCommandTree.java` (builds the spec), `BukkitCommandRenderer.java` (CommandExecutor+TabCompleter bridging), nodes under `core/.../command/node/`: `DashboardNode.java`, `ModuleNode.java`, `PingNode.java`, `DebugNode.java`, `ReloadNode.java`, `VersionNode.java`, `HelpNode.java`; `Messages` additions

- [ ] Tree per spec §4.6. Dashboard (bare `/mental`): header + per-module line `● knockback  Enabled  [toggle] [info]` — Adventure components, `clickEvent(runCommand("/mental module knockback off"))`, hover descriptions; footer quick links (reload/debug/help). Module toggles persist (`config.set("modules.<id>.enabled", v); saveConfig(); reload()`), reusing one `ModuleToggleService`. Ping node ports old stats display + names probe strategy. Debug node: `on|off`, `category <name> on|off`, `subscribe`. Version node prints version + ServerEnvironment + Capabilities + anticheat policy report. Help: clickable list w/ descriptions.
- [ ] Register via `getCommand("mental")` executor/completer in `MentalPlugin`.
- [ ] Commit `feat(command): player-friendly /mental tree with interactive dashboard`.

### Task 20: BrigadierBridge (compat-modern)

**Files:** `compat/modern/.../BrigadierBridge.java`; wiring in `MentalPlugin`

- [ ] Translates `CommandSpec` → Brigadier `LiteralCommandNode<CommandSourceStack>` (literals nested; arguments as greedy/word `StringArgumentType` + `SuggestionProvider` delegating to spec suggesters; `requires` from permissions; executors adapt `CommandSourceStack.getSender()` into `CommandContext`). Registers through `plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, e -> e.registrar().register(node, "Mental root", List.of("mtl")))`.
- [ ] Capability-gated load in plugin: `capabilities.brigadierCommands()` ⇒ reflective bridge construction; else Bukkit renderer (which also remains the executor backing `plugin.yml` on old servers; when Brigadier path active, skip `getCommand` wiring — Brigadier owns it).
- [ ] Verify: full build; jar contains bridge. Commit `feat(command): native Brigadier rendering on 1.20.6+`.

---

## Phase 4 — API facade + boot completeness

### Task 21: Mental API facade + services finalization

**Files:** `api/.../Mental.java` (static accessor + interface `MentalApi`: `boolean moduleEnabled(String)`, `OptionalDouble pingMillis(Player)`, `String version()`), `core/.../MentalApiImpl.java`; register via Bukkit `ServicesManager` + static init/teardown

- [ ] Wire every module into `MentalPlugin.onEnable` in dependency order: anticheat → hitreg → knockback → compensation (hints wiring) → fishing-knockback → rod-velocity → projectile-knockback. Boot report logs version, capabilities, scheduling impl, module states, anticheat policy.
- [ ] `./gradlew build` green; commit `feat(api): public facade and full module wiring`.

---

## Phase 5 — Tests

### Task 22: Unit-suite completeness pass

- [ ] Review: engine (Task 10), motion/jitter/latency (14), cps/gate/sharpness (12), rod math (16), config (7), env parse (3), debug (6), command dispatch (18). Add any missed edge: KB engine NaN guards, config unknown-key warning, `Suggestions` filtering.
- [ ] `./gradlew test` all green. Commit `test: complete unit coverage for pure logic`.

### Task 23: Tester plugin harness

**Files:** `tester/src/main/java/me/vexmc/mental/tester/`: `MentalTesterPlugin.java`, `TestHarness.java`, `TestCase.java` (record: name + ThrowingConsumer<TestContext>), `TestContext.java` (`sync(Callable)`, `awaitTicks(int)`, `expect(boolean, String)`, listener helpers), `TestResultWriter.java`, `FakePlayer.java`; resource `tester/src/main/resources/plugin.yml` (`name: MentalTester`, `depend: [Mental]`, `api-version: '1.17'`, `folia-supported: true`)

- [ ] Harness: `onEnable` schedules start 40 ticks later (server settled), runs suite sequentially on a dedicated driver thread; `sync()` bridges via Mental's `Scheduling.runGlobal` + `CompletableFuture` (30s timeout); `awaitTicks` via repeating counter task; failures collected; writer outputs `plugins/MentalTester/test-results.txt` (`PASS`/`FAIL`) + `test-failures.txt`; then `Bukkit.shutdown()` (via global scheduling).
- [ ] `FakePlayer`: port OCM's `FakePlayer.kt` to Java, trimmed to 1.17+ (no legacy 1.9/1.12 paths): GameProfile → ServerPlayer reflective construction (reflection-remapper `forReobfMappingsInPaperJar()` w/ noop fallback), connection stub, gamemode SURVIVAL, position/rotation, PlayerList add, join event flow, per-tick entity tick task, `attack(Entity)` via `HumanEntity#attack`, equipment helpers, `remove()`. Consult OCM source at port time for the NMS member names per mapping era.
- [ ] Commit `test(integration): in-server test harness with fake players`.

### Task 24: Integration suite

**Files:** `tester/.../suite/BootSuite.java`, `KnockbackSuite.java`, `FishingSuite.java`, `ProjectileSuite.java`, `CommandSuite.java`, `ReloadSuite.java`; registered in `MentalTesterPlugin`

- [ ] BootSuite: Mental enabled; every expected module id present + enabled; capability report consistent (e.g. brigadier ⇔ `isAtLeast(1,20,6)`; folia false on Paper).
- [ ] KnockbackSuite: two FakePlayers 4 blocks apart at rest, victim `maximumNoDamageTicks=0`; MONITOR `PlayerVelocityEvent` captor; attacker.attack(victim); awaitTicks(3); assert captured vector ≈ `KnockbackEngine` expectation (ε 1e-3) for plain + sprinting variants.
- [ ] FishingSuite: spawn FakePlayer victim + zombie variant; spawn `FishHook` via real cast is flaky → directly construct: rodder FakePlayer, `world.spawn(loc, FishHook.class)` setShooter? FishHook spawn support varies; instead simulate: call victim.damage tiny + assert module math via `ProjectileHitEvent` synthesized — **simplest reliable:** spawn hook entity `player.launchProjectile(FishHook.class)` (works 1.17+ for players), teleport hook adjacent to victim, await `ProjectileHitEvent`… physics-flaky. **Decision:** test the user-visible contract instead: rodder launches projectile at point-blank victim (hook spawned moving toward victim 1 block away), awaitTicks(10), assert victim velocity changed + got damaged ~0.0001 (event captor), and CAUGHT_ENTITY drag produces no pull when cancel-dragging-in=players. Tolerate either no-hit (skip w/ note) on pathological versions — fail only on velocity-without-damage inconsistency. Keep exact-vector assertions in unit tests (Task 16).
- [ ] ProjectileSuite: spawn Snowball with shooter FakePlayer aimed at victim 2 blocks away; on `EntityDamageByEntityEvent` MONITOR assert damage ≈ 0.0001; assert victim velocity non-zero after.
- [ ] CommandSuite: `Bukkit.dispatchCommand(console, "mental version")` no-throw; FakePlayer dispatch `mental` + `mental module knockback status`; completions via `getCommand("mental").tabComplete` on old path (guard: only when not Brigadier).
- [ ] ReloadSuite: 20× `mental reload` while a KB hit loop runs; no exceptions; module count stable.
- [ ] Commit `test(integration): version-agnostic behavior suites`.

### Task 25: Gradle integration-test orchestration

**Files:** Modify root `build.gradle.kts` (+ `core/build.gradle.kts` for run-paper plugin application on a dedicated holder or root), `gradle.properties` (`integrationTestVersions=1.17.1,1.18.2,1.19.4,1.20.6,1.21.4,1.21.11,26.1.2`)

- [ ] Model on OCM `build.gradle.kts:325-787` (consult at implementation time): per version V → `runIntegrationTest_V` (`xyz.jpenilla.runpaper.task.RunServer`: `minecraftVersion(V)`, `runDirectory(file("run/$V"))`, `pluginJars(core shadowJar output, tester jar output)`, `systemProperty("com.mojang.eula.agree", true)`, no-GUI args (`nogui`), log redirect to `build/integration-test-logs/$V.log`, `javaLauncher` from toolchain {17 if V<1.20.5; 21 if <26; else 25})` → finalized by `checkIntegrationTest_V` (reads `run/$V/plugins/MentalTester/test-results.txt`, fails unless exactly `PASS`, prints failures file + log tail on fail; deletes stale result before run).
- [ ] Aggregates: `integrationTest` (1.17.1 + 26.1.2), `integrationTestMatrix` (all), sequential dependsOn-ordering for fail-fast.
- [ ] **Run it:** `./gradlew integrationTest` locally — iterate on harness/plugin until PASS on floor + latest. This step is the heavyweight verification; budget for fixes (mapping names in FakePlayer, timing).
- [ ] Then `./gradlew integrationTestMatrix` — fix per-version fallout (expect: 1.18/1.19 fine; 1.20.6 brigadier path; attribute rename at 1.21.4+).
- [ ] Commit `build(test): real-server integration test matrix across 1.17.1-26.1.2`.

---

## Phase 6 — Finalization

### Task 26: CI workflows

**Files:** Rewrite `.github/workflows/build.yml`, `.github/workflows/release.yml`

- [ ] `build.yml`: push/PR → checkout, `actions/setup-java` temurin 25, gradle cache (`gradle/actions/setup-gradle`), `./gradlew build`, then job `integration` matrix `[1.17.1, 1.20.6, 1.21.11, 26.1.2]` fail-fast running `./gradlew "checkIntegrationTest_${{ matrix.version }}"`, artifact upload `core/build/libs/Mental-*.jar`.
- [ ] `release.yml`: tag `v*` → build + full `integrationTestMatrix` + `softprops/action-gh-release` attaching the jar (port structure from existing release.yml).
- [ ] Commit `ci: gradle build and per-version integration matrix`.

### Task 27: Documentation

**Files:** Rewrite `README.md`; create `docs/fast-path.md` (rewrite of FAST_PATH.md for Mental incl. anticheat policy section); delete `FAST_PATH.md`

- [ ] README: name/badges (Java 17+, Paper 1.17.1–26.1.2, Folia), feature list (incl. fishing/projectile/anticheat/debug), version-support matrix table, install (single jar, no deps), commands table (new tree), config reference, API example (both events), build + integration-test instructions, acknowledgements (knockback-sync GPL-3.0 algorithm; SmashHit concept; OCM-derived fishing/projectile mechanics + test approach), repo-rename note.
- [ ] Commit `docs: Mental README and fast-path deep dive`.

### Task 28: Legacy removal + final verification

- [ ] Delete `src/` (old plugin), stray `target/`; confirm no references: grep for the legacy plugin name → no matches remain.
- [ ] Full gate: `./gradlew clean build integrationTest` → green; `unzip -l core/build/libs/Mental-1.0.0.jar` sanity (plugin.yml, relocated lib, compat classes, no kotlin).
- [ ] Commit `chore: remove legacy sources` then `release: Mental 1.0.0`.
- [ ] Report: summarize verification evidence; flag GitHub repo rename as manual follow-up.

---

## Self-review

**Spec coverage:** §4.1 layout → T1; §4.2 scheduling → T4/T9; attribute break → T5; §4.3 framework → T8; §4.4 melee/hitreg/compensation → T10–15; fishing/projectile → T16; §4.5 anticheat → T17 (+13); §4.6 commands → T18–20; §4.7 debug → T6 (+19 node); §4.8 config → T7; §4.9 API → T11/T12/T21; §4.10 build → T1/T9; §4.11 tests → T10/12/14/16/18/22–25; §4.12 CI → T26; §4.13 hygiene/docs → T2/T27/T28. No gaps.

**Known judgment calls recorded:** fishing velocity applied post-damage without cancellation re-check (OCM parity, noted in javadoc); FishingSuite asserts contract not exact vector (physics flakiness — exact vectors unit-tested); compat compile floor may bump 1.20.6→1.21.4 if EntityScheduler missing (loading unaffected).

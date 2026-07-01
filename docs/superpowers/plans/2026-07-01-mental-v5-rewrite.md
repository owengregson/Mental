# Mental v5 Rewrite Implementation Plan (master + Phase 1 detail)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild Mental on the approved Two-Realm Kernel architecture
(`docs/superpowers/specs/2026-07-01-mental-v5-spec.md`) with every §4 mandate
invariant byte-identical and every §5 bug-class structurally impossible.

**Architecture:** A Bukkit-free `kernel` Gradle module holds all era math and
netty-realm decision logic (thread confinement = compile error); per-player
single-writer sessions + hit transactions replace the pipeline band-aids; a
DeliveryDesk with a delivery journal is the sole velocity owner and test seam.

**Tech Stack:** Java 17 bytecode (JDK 25 toolchain), Gradle multi-module, Paper API
1.17.1 floor, shaded relocated PacketEvents 2.12.1, JUnit 5, run-paper matrix.

## Global Constraints

- Kernel module: **no Paper/Bukkit/PacketEvents dependency, not even compileOnly**.
- All subprojects `options.release = 17`; toolchain JDK 25.
- Ported math + tests land **byte-identical in behavior** — pinned expectations may
  not change; only package names and mechanical seams (e.g. `Material` → name
  string) may differ, and each such seam is listed in its task.
- Conventional commits with prose bodies; commit at the end of every task.
- The existing modules must keep building green through Phases 1–3 (the rewrite is
  additive until Phase 4 starts replacing core).
- Work happens on branch `rewrite/v5` (branched from `docs/rewrite-prompt`).
- MotionMath/GroundProbe: **clean-room only** — implement from the constants and
  integration order in `docs/research/2026-06-06-combat-compendium.md`; do not open
  the existing `module/compensation/MotionMath.java` while writing the new one
  (GPL-3.0 lineage). Tests are written fresh against vanilla-physics facts.

## Phase roadmap (each later phase gets its own detailed plan at phase-open)

| Phase | Deliverable | Gate |
|---|---|---|
| 1 (this doc) | `kernel` module with all math + pins ported | `./gradlew build` green incl. `:kernel:test`; kernel classpath free of Bukkit |
| 2 | delivery core: HitTransaction, sessions/inboxes, PlayerView, DeliveryDesk, valve, journal, TickClock; interleaving harness | kernel+core unit suites green; canonical wire vectors reproduce through the desk in the harness |
| 3 | feature framework: descriptors, scopes, reconciler, arbiter, config snapshot+overlay+migration | parse(empty)==LEGACY_17, facet-coverage, zero-touch property tests green |
| 4 | features rebuilt per family (delivery → knockback → damage → cadence → sustain → loadout), old core classes deleted as replaced | era-parity + zero-touch live suites green per family |
| 5 | platform manifest, NMS adapter, Scheduling TCK, compat-folia, support-matrix.json, CI + Folia combat entry + nonce, OCM artifact | full matrix fresh-PASS incl. Folia entry |
| 6 | GUI on descriptors, version 5.0.0 cutover, apiVersion 2 + ServicesManager, japicmp, docs reconciliation | mandate §8 definition of done, all items |

---

# Phase 1 — kernel module: port the math and its pins

Source paths are relative to repo root. Every port task follows the same step
shape: create target file(s) from the named source with the listed mechanical
edits → port the named test(s) → `./gradlew :kernel:test --tests <class>` →
expected PASS with identical pinned values → commit.

### Task 1.0: Branch + kernel module scaffold

**Files:**
- Modify: `settings.gradle.kts` (add `include("kernel")`)
- Create: `kernel/build.gradle.kts`
- Create: `kernel/src/test/java/me/vexmc/mental/kernel/KernelClasspathTest.java`

**Interfaces:**
- Produces: the `:kernel` module every later task targets; package root
  `me.vexmc.mental.kernel`.

- [ ] **Step 1:** `git checkout -b rewrite/v5`
- [ ] **Step 2:** Add `include("kernel")` to `settings.gradle.kts`. Create
  `kernel/build.gradle.kts`:

```kotlin
plugins { `java-library` }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

// The kernel realm may never see Bukkit or PacketEvents — this is the
// architecture's enforcement edge, so fail the build if anything sneaks in.
configurations.all {
    resolutionStrategy.eachDependency {
        require(!requested.group.startsWith("io.papermc")) { "kernel must stay Bukkit-free" }
        require(!requested.group.startsWith("com.github.retrooper")) { "kernel must stay PacketEvents-free" }
    }
}
```

(Root `build.gradle.kts` already applies the JDK-25/release-17 toolchain to all
subprojects — verify `:kernel` inherits it.)
- [ ] **Step 3:** Write the failing classpath test:

```java
package me.vexmc.mental.kernel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The kernel realm must not be able to see Bukkit even at test runtime. */
class KernelClasspathTest {
    @Test
    void bukkitIsNotOnTheKernelClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.bukkit.Bukkit"));
    }
}
```

- [ ] **Step 4:** Run `./gradlew :kernel:test` — expected PASS (module empty but
  test runs; if Bukkit leaks in later, this fails).
- [ ] **Step 5:** Commit `build(kernel): scaffold the Bukkit-free kernel module`.

### Task 1.1: Value types — KnockbackVector, EntityState, GroundFriction

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/model/KnockbackVector.java`
  from `core/src/main/java/me/vexmc/mental/module/knockback/KnockbackVector.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/model/EntityState.java`
  from `core/.../module/knockback/EntityState.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/math/GroundFriction.java`
  from `core/.../module/knockback/GroundFriction.java`
- Test: port the corresponding test classes from `core/src/test/java/...` to
  `kernel/src/test/java/me/vexmc/mental/kernel/{model,math}/`.

**Interfaces:**
- Produces: `KnockbackVector(double x, double y, double z)` (record, minus
  `toBukkit()`); `EntityState` record (all current components, minus the
  `captureVictim`/`capture` static factories, which stay behind for core to
  reimplement in Phase 2); `GroundFriction.of(String materialName)` returning the
  slipperiness double.

**Mechanical seams (the only allowed changes):**
- `KnockbackVector.toBukkit()` deleted (Bukkit `Vector`); core gains a
  `Vectors.toBukkit(KnockbackVector)` helper in Phase 2.
- `EntityState` capture factories deleted; record + docs verbatim.
- `GroundFriction.of(Material)` becomes `of(String)` keyed by `Material.name()`
  strings (`"ICE"`, `"PACKED_ICE"`, `"SLIME_BLOCK"`, `"BLUE_ICE"`, default) —
  table values byte-identical; `under(Player)` stays behind for core.
- Test expectations unchanged; test calls adjusted to the new seams only.

- [ ] Steps: port → run `./gradlew :kernel:test` → PASS → commit
  `feat(kernel): port the knockback value types and friction table`.

### Task 1.2: Decay statics from VictimMotion

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/math/Decay.java` — extract
  ONLY the pure statics `decay`, `decayOnce`, `groundedEquilibrium` and the physics
  constants (`DEFAULT_GRAVITY=0.08`, `VERTICAL_DRAG=0.98`, `AIR_DRAG=0.91`,
  `TERMINAL_VELOCITY=3.92`, `JUMP_IMPULSE=0.42`, `SPRINT_JUMP_PUSH=0.2`,
  `DEFAULT_SLIPPERINESS=0.6`, `REST_THRESHOLD=0.005`, `NO_TICK`) from
  `core/.../module/knockback/VictimMotion.java`.
- Test: port the pure-function subset of `VictimMotionTest` (every case that does
  not touch the ledger `Sample` machinery — e.g. the ice hit-2 = 0.4821 pin) to
  `kernel/src/test/java/me/vexmc/mental/kernel/math/DecayTest.java`.

**Interfaces:**
- Produces: `Decay.decay(...)`, `Decay.decayOnce(...)`,
  `Decay.groundedEquilibrium(double gravity)` with signatures identical to the
  current statics; the constants referenced by Phase 2's `MotionLedger`.

- [ ] Steps: port → run → PASS (pins identical) → commit
  `feat(kernel): extract the pure motion-decay authority from VictimMotion`.

### Task 1.3: Profile schema + engine (the crown jewel)

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/profile/KnockbackProfile.java`
  (+ nested/companion types `KnockbackDelivery`, `VerticalMode`,
  `ResistancePolicy`, `RangeReduction`, `Limits`, `SupersededPresets`) from
  `core/.../config/KnockbackProfile.java` and friends — **schema, defaults,
  `LEGACY_17`, `sameValues`, preset value tables only**; any
  `ConfigurationSection` parsing stays behind (Phase 3 re-attaches it in core).
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/math/KnockbackEngine.java`
  from `core/.../module/knockback/KnockbackEngine.java` — verbatim; only imports
  retarget to `kernel.model`/`kernel.profile`. `ThreadLocalRandom`/`RandomGenerator`
  usage is already injectable — keep it.
- Test: port `KnockbackEngineTest` (every branch pin) and the value-table halves of
  `KnockbackProfilesTest` +
  `MentalConfigTest.bundledPresetsCarryTheirCanonicalValues` (the YAML-parsing
  halves stay for Phase 3).

**Interfaces:**
- Produces: `KnockbackEngine.compute(...)` with its current signature over
  `EntityState`/`KnockbackProfile`; `KnockbackProfile.LEGACY_17`;
  `KnockbackProfile.sameValues(...)`; the canonical preset constants
  (kohi/mmc/lunar/velt/minehq/badlion/signature) as the acceptance spec for
  Phase 3's parser.

- [ ] Steps: port schema → port engine → port tests → run → PASS with identical
  pinned vectors (standing (0.4, 0.3608), sprint (0.9, 0.4607), …) → commit
  `feat(kernel): port the knockback engine and profile schema with all pins`.

### Task 1.4: Hit-registration math cluster

**Files:**
- Create in `kernel/.../wire/`: `ReachValidator.java`, `FeedbackPlan.java` (from
  `FeedbackBurst.java`, renamed; enum + `plan(includeVelocity, bundleWanted,
  bundleCapable)` verbatim), `CpsLimiter.java`.
- Create in `kernel/.../math/`: `HurtYaw.java` (extract the pure `hurtYaw`
  trigonometry from `core/.../module/hitreg/FeedbackSenders.java`),
  `DamageTables.java` (extract from `core/.../module/hitreg/DamageCalculator.java`
  the pure era tables and math: pre-1.9 tool damage by tier, Sharpness 1.25×level,
  crit ×1.5-before-enchant, strength/weakness level math — everything that does not
  read a live entity or reflect).
- Test: port `ReachValidatorTest`, `FeedbackBurstTest` (all 8 boolean combos),
  `CpsLimiterTest`, and the pure-math cases of the DamageCalculator/FeedbackSenders
  tests.

**Interfaces:**
- Produces: `FeedbackPlan.plan(boolean, boolean, boolean) -> List<Step>` (Step enum:
  `BUNDLE_OPEN, VELOCITY, HURT, BUNDLE_CLOSE`); `ReachValidator` static geometry as
  today; `DamageTables.weaponDamage(String effectiveMaterialName)`,
  `DamageTables.sharpnessBonus(int level)`, `DamageTables.critMultiplier()` —
  material parameters as enum-name strings.

- [ ] Steps: port → run → PASS → commit
  `feat(kernel): port the hit-registration math (reach, burst plan, cps, damage tables)`.

### Task 1.5: Latency cluster — port two, re-derive one

**Files:**
- Create: `kernel/.../wire/LatencyModel.java` — merge the pure logic of
  `core/.../module/compensation/LatencyTracker.java` (exact-match consume-once RTT
  correlation, `MAX_OUTSTANDING=32`, spike detection) and
  `JitterCalculator.java` (15-sample IQR-filtered stddev) with their current
  behavior; keep them as two classes if the merge muddies the pins.
- Create: `kernel/.../math/MotionMath.java` — **clean-room** (see Global
  Constraints): `simulateVerticalVelocity`, `distanceTraveled`, `ticksToApex`,
  `ticksToFall` implemented from the compendium's integration order
  (`motY = (motY − 0.08) × 0.98` per tick, terminal 3.92, cap 30 ticks, −1 past
  cap) as one continuous signed-velocity simulation.
- Test: port `LatencyTrackerTest`/`JitterCalculatorTest` verbatim; write
  `MotionMathTest` fresh with hand-computed vanilla pins (e.g. from vy=0.42:
  tick1 → (0.42−0.08)×0.98 = 0.3332; ticksToApex counts until vy ≤ 0; a dropped
  body reaches −3.92 terminal and stays).

**Interfaces:**
- Produces: `LatencyModel` (or `LatencyTracker`+`JitterCalculator`) with current
  method names; `MotionMath` statics named as above (same names as the old class —
  the *names* are API, the *implementation* is fresh).

- [ ] Steps: port/derive → run → PASS → commit
  `feat(kernel): port latency correlation and re-derive MotionMath clean-room`.

### Task 1.6: OCM-port math cluster

**Files:** create in `kernel/.../math/`, each from its current core path with only
package/import edits (all confirmed Bukkit-free by the purity audit):
`DefenceMath`, `ArmourDurabilityMath`, `ToolDurabilityMath`, `RegenMath`,
`SwordBlockReduction`, `EraReach`, `RodLaunchMath`, `PotionDurations`,
`GoldenAppleEffects`, `OffhandPolicy` (seam: `Material` → enum-name `String` set,
values identical), plus `PunchMath.java` extracted from
`ProjectileKnockbackModule.withPunch` (0.6/level along flight + flat 0.1 vertical).
- Test: port every corresponding test; adjust only the Material→String seam.

**Interfaces:**
- Produces: the classes above under `kernel.math`, signatures unchanged except the
  listed seam; `PunchMath.withPunch(KnockbackVector base, double flightX, double
  flightZ, int level)`.

- [ ] Steps: port → run → PASS → commit
  `feat(kernel): port the OCM-era rule math with pins intact`.

### Task 1.7: EffectiveMaterial resolution (kernel-pure)

**Files:**
- Create: `kernel/.../model/EffectiveMaterial.java` from
  `core/.../platform/EffectiveMaterial.java`: `resolve(String markerValue,
  String fallbackTypeName) -> String` — unknown names degrade to the fallback,
  never throw; absent marker (null) → fallback.
- Test: port `EffectiveMaterialTest` cases onto the string seam.

**Interfaces:**
- Produces: `EffectiveMaterial.resolve(String, String)`; the PDC-reading shell is
  rebuilt in core in Phase 4 (loadout family).

- [ ] Steps: port → run → PASS → commit
  `feat(kernel): port the effective-material contract resolution`.

### Task 1.8: Phase gate

- [ ] Run `./gradlew build` — everything green including all pre-existing modules
  (the old core is untouched) and `:kernel:test`.
- [ ] Verify the kernel main-source compile classpath is empty of external deps:
  `./gradlew :kernel:dependencies --configuration compileClasspath` shows no
  entries.
- [ ] Commit any stragglers; push the branch.

---

## Execution notes

- Executed inline (superpowers:executing-plans) with bounded delegation for the
  mechanical port clusters; the phase gate is verified by the orchestrator, never
  claimed from a subagent's report alone (matrix-gate honesty rule).
- Phase 2's detailed plan (`2026-07-XX-mental-v5-phase2-delivery-core.md`) is
  written when Phase 1's gate is green, informed by any seams Phase 1 surfaced.

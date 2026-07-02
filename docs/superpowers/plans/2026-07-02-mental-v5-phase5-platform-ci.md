# Mental v5 Phase 5 — Platform, CI, Folia Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the platform layer (module split, Scheduling TCK, the
required/optional manifest), make the support matrix a single machine-readable
source of truth with a freshness nonce, make OCM staging reproducible in CI, gate
the API binary compatibility, and give Folia a first-class matrix entry with the
best live combat coverage discovery proves feasible.

**Execution grouping:** three executor runs — (A) Tasks 5.0–5.2, (B) Tasks 5.3–5.5,
(C) Tasks 5.6–5.7. Old-code-as-contract references and prior outcomes sections
apply as in Phase 4. Gate discipline unchanged (sequential matrix, fresh evidence,
honesty rule).

## Global Constraints

- Kernel additive-only, no pin changes; suite VALUES sacred; era behavior
  byte-identical.
- Conventional commits + trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Sanctioned surface per task (module moves are explicitly sanctioned where named).
- Every gate: fresh evidence pasted (build tails, test-results contents + mtimes).

---

### Task 5.0: The `platform` module (dissolve `common`)

Create Gradle module `platform` (compileOnly Paper 1.17.1, depends `kernel`).
Move INTO it: `common/scheduling/*` (Scheduling, TaskHandle), `common/platform/*`
(Capabilities, ServerEnvironment), `common/debug/*`; plus core's
`platform/{Attributes,Enchantments,SchedulingFactory,BukkitScheduling,EffectiveMaterial-shell}`.
Package root `me.vexmc.mental.platform.*` (adjust imports across core/tester/
compat-folia). Delete the `common` module (settings.gradle.kts, core deps,
shadowJar wiring). `compat-folia` retargets `compileOnly(project(":platform"))`.
Gate: `./gradlew build` green; jar contents verified (`unzip -l` shows relocated
classes, no `common/`).

### Task 5.1: Scheduling TCK

`platform/src/testFixtures` (or a test-support sourceset) hosting a conformance
suite over the `Scheduling` contract, with the retired-callback thread+timing
contract WRITTEN INTO the interface javadoc first (the two backends historically
diverged: Bukkit runs retired on the main thread next tick; Folia fires it inline
on the caller thread when the entity is already retired — make the CONTRACT
explicit: retired may fire on either thread, MUST fire exactly once, and callers
must not assume thread affinity; that is the testable common denominator).
TCK cases: runOn on valid entity executes on owning thread; retired fires
exactly-once for an invalid entity; repeatOn handle cancel stops execution;
repeatGlobal periodicity; runAsync off-main. Run the TCK against
`BukkitScheduling` with a stubbed BukkitScheduler (unit) — full pass. For
`FoliaScheduling`, the TCK compiles against the interface; live verification is
Task 5.6's Folia matrix entry (state this honestly in the plan-outcomes).

### Task 5.2: The PlatformProfile manifest (R10/B10 completion)

Fold the boot-time resolutions into one `PlatformProfile` built at enable from a
manifest of typed entries: `Required<T>` (failure ⇒ owning feature disabled with
ONE loud log line, engine-critical ⇒ boot fail) and `OptionalSince<T>` (typed
absence + declared fallback at the entry). Entries: attribute handles (attack
speed, gravity, KB resistance, armour, interaction range), enchantment handles,
HURT_ANIMATION/bundle capability, knockbackEvent, BLOCKS_ATTACKS/CONSUMABLE/
max_damage components, join-protection layout marker, projectile-KB-restored
(1.21.2+) flag. The existing `v5/platform/PlatformProbe` adapters
(SwordBlockAdapter, WeaponTooltipAdapter, AttackRangeAdapter) become manifest
consumers; `Attributes`/`Enchantments` name-probing becomes the resolution
technique inside entries (no bare `@Nullable` public surface). One boot report
log line summarizing the profile. Unit tests: a Required failure disables only
its owner; an OptionalSince absence yields the declared fallback; nothing
resolves to a magic literal.

### Task 5.3: `support-matrix.json` + freshness nonce

Repo-root `support-matrix.json`:
`{ "floorApi": "1.17", "entries": [{"version": "...", "jdk": 17|25, "platform": "paper"|"folia", "suites": "full"|"boot"|"combat-smoke", "ci": "pr"|"release"}...] }`.
Consumers (NO version/JDK literal may remain anywhere else): core/build.gradle.kts
task registration (delete `integrationTestVersions` from gradle.properties and the
`requiredJavaVersion` function — read the file), `scripts/integration-matrix.sh`
(jq), `.github/workflows/build.yml` (pr entries) + `release.yml` (all entries),
the release-notes range string, `plugin.yml` api-version (from floorApi).
Freshness nonce: run tasks generate a nonce, pass `-Dmental.tester.nonce`; the
tester writes `PASS nonce=<n>`; check tasks + the script validate nonce+PASS
(stale results structurally impossible). Gate: sequential matrix green through
the NEW plumbing, evidence pasted.

### Task 5.4: Reproducible OCM staging in CI

Pin the OCM artifact (kernitus OldCombatMechanics release URL + sha256) in
`support-matrix.json` (an `ocm` object); CI downloads + verifies + stages to
`run/ocm-jar/` and runs `integrationTestOcm` in the release workflow (and a PR
smoke if cheap). Local: the existing fork-build path stays as an override.
Document in the workflow comments.

### Task 5.5: API binary-compat gate (R12)

Build the baseline: from git tag `v2.2.2`, build `:api` once, commit the jar as
`gradle/api-baseline/api-2.2.2.jar` (with a README noting provenance). Wire
japicmp (or the japicmp-gradle-plugin) comparing `:api` against the baseline:
only ADDITIVE changes allowed (the `apiVersion()` default method must pass —
prove it). Add to `./gradlew build` (or `check`). Gate evidence: the japicmp
report output pasted.

### Task 5.6: Folia — discovery, then the matrix entry

**Discovery first (report findings before building):** boot Folia (latest
supported build) with the v5 jar via a run task; probe (a) v5 boots clean
(CounterTickClock global task, sessions repeatOn, PE lifecycle), (b) whether the
NMS FakePlayer bootstrap works under regionized threading (spawn, join events,
session creation), (c) whether two fakes in ONE region can exchange a
desk-delivered hit with the journal recording it (drive all actions via
`Scheduling.runOn`). **Then implement per findings:**
- Best case: a `FoliaCombatSmoke` suite (same-region pair, journal-asserted
  canonical vector + zero-touch check) as the Folia entry's suite.
- FakePlayer blocked: deliver boot suite + a session/journal self-test (a
  synthetic-inbox smoke that exercises desk delivery without players), document
  the limitation and what a protocol-level client would take.
Folia enters `support-matrix.json` (`platform: "folia"`, suites per findings,
`ci: "release"`). The cross-region melee drop stays intended behavior (assert the
logged-skip if drivable, else document).

### Task 5.7: Phase gate

Full `./gradlew build` + sequential matrix (all entries incl. Folia) + OCM run +
japicmp, all through the new support-matrix plumbing, all fresh (nonce-verified),
evidence pasted. Append "Phase 5 outcomes" to this plan; push.

---

## Phase 5 run A outcomes (2026-07-02)

Tasks 5.0–5.2 landed on `rewrite/v5` (from `ab5c75a`). Three conventional
commits, one per task; both regression gates (module move, manifest rewiring)
verified with FRESH sequential-matrix PASS evidence.

Commits: `fe5ce5b` refactor(platform) — dissolve common (5.0); `4eced36`
test(platform) — Scheduling TCK + retired-callback contract (5.1); `1c66d61`
feat(platform) — PlatformProfile manifest R10/B10 (5.2).

### Task 5.0 — the `platform` module (dissolve `common`)

A pure MOVE (package renames only, byte-identical behaviour). New Gradle module
`:platform` (compileOnly Paper 1.17.1, `api(project(":kernel"))`). Moved in:
`common/scheduling/*` (Scheduling, TaskHandle), `common/platform/*`
(Capabilities, ServerEnvironment) → flat `me.vexmc.mental.platform`;
`common/debug/*` (DebugLog, DebugCategory) → `me.vexmc.mental.platform.debug`;
core's `platform/{Attributes, Enchantments, SchedulingFactory, BukkitScheduling,
EffectiveMaterial}` (+ AttributesTest, EffectiveMaterialTest) — package
unchanged, module changed; SchedulingFactory/BukkitScheduling dropped their
now-same-package `common.*` imports. `:common` deleted from settings, its build
script removed. `core` swapped `api(project(":common"))` → `api(project(
":platform"))` (platform re-exports kernel; both shade into `Mental.jar`);
`tester` and `compat-folia` retargeted their compileOnly project dep;
FoliaScheduling's imports moved to `me.vexmc.mental.platform.*`. 51 files, all
consumer imports rewritten (no `me.vexmc.mental.common.*` reference — import,
FQN, or javadoc — remains). **Deviation from spec §14's porting map:**
EffectiveMaterial was moved WHOLE into `:platform` per Task 5.0's explicit list,
not split into a kernel-pure `resolve` + core PDC shell; that split is a rewrite,
out of scope for a pure move, and is left for a later task.

Gate: `./gradlew build` GREEN. `Mental-2.2.2.jar`: `grep -c
'me/vexmc/mental/common'` = **0**, `grep -c 'me/vexmc/mental/platform'` = **16**
(the relocated classes; the `v5/platform` adapters are a distinct path).
Sequential `integrationTestMatrix` — all seven versions FRESH PASS
(test-results.txt mtimes 02:49:32 → 02:57:59, each `PASS`, zero test-failures
files), `BUILD SUCCESSFUL in 9m 44s`.

### Task 5.1 — Scheduling TCK

The retired-callback thread+timing contract was written into the `Scheduling`
interface javadoc FIRST (the honest common denominator: retired may fire on
either thread, MUST fire exactly once, no thread-affinity assumption — the two
backends' historical divergence stated, then reduced to the testable invariant).
`SchedulingTck` is a backend-agnostic abstract suite published as a **test
fixture** (`:platform` gains `java-test-fixtures`); it is tick-driven (no
wall-clock waits) via abstract driver hooks. Five cases — runOn/valid runs on the
owning thread and never retires; runOn/retired fires retired exactly once and
never re-fires; repeatOn stops after handle cancel; repeatGlobal fires
periodically until cancelled; runAsync runs off the owning thread. Run against
`BukkitScheduling` with a stubbed `BukkitScheduler` (`BukkitSchedulingTckTest`: a
single-threaded "main" executor the tick hook advances, a separate async thread,
and dynamic-proxy fake entities/scheduler) — **tests=5, failures=0**. Full
platform suite green (21 cases across the module).

**Honest limit (as planned):** FoliaScheduling gets compile-level coverage only
this run — it implements the same `Scheduling` interface the TCK targets and
builds clean against `:platform`; its LIVE conformance run is Task 5.6's Folia
matrix entry (another run). The fixture is already published so 5.6 can extend it
without moving the suite.

### Task 5.2 — the PlatformProfile manifest (R10/B10)

`PlatformProfile` is the single boot-time resolution owner, built once at enable
from a manifest of typed `ManifestEntry`s (sealed: `Required<T>` /
`OptionalSince<T>`). It wraps the EXISTING probing techniques — it does not
re-derive them: the Attributes/Enchantments modern-then-legacy name-probes are
the entry resolvers; the three v5 adapters + the max_damage accessors become
manifest consumers (the retired `PlatformProbe` is deleted, its
`effectiveMaxDurability` moved onto the profile). A Required miss disables ONLY
its owning `Feature` (one loud log) or — engine-critical — fails the boot; an
OptionalSince absence yields the declared fallback. The profile is handed to the
`Reconciler` as a platform veto (backward-compatible new constructor) and emits
ONE boot-report line. Unit tests (`PlatformProfileTest`, 4 cases): Required-miss
disables owner only; engine-critical miss throws; OptionalSince absence yields
the fallback; every entry is self-describing (name/since/fallback, no magic
literal) and the report is one line.

**Bare-nullable resolution-site audit — routed through manifest entries** (25
entries; the profile is now their single owner + boot report + disable authority):

| Prior bare-nullable / raw resolution site | Manifest entry |
| --- | --- |
| `Attributes.attackDamage/attackSpeed/knockbackResistance/maxHealth/armor/armorToughness` (6 static `resolve`) | `attribute:*` **Required** (owners HIT_REGISTRATION / ATTACK_COOLDOWN / KNOCKBACK / REGEN / ARMOUR_STRENGTH ×2) |
| `Attributes.gravity`, `Attributes.entityInteractionRange` (2 static `resolve`, absent < 1.20.5) | `attribute:gravity`, `attribute:entity_interaction_range` **OptionalSince 1.20.5** |
| `Enchantments.sharpness/punch/knockback/protection/fireProtection/featherFalling/blastProtection/projectileProtection/unbreaking` (9) | `enchant:*` **Required** (owners HIT_REGISTRATION / PROJECTILE_KNOCKBACK / KNOCKBACK / ARMOUR_STRENGTH ×5 / TOOL_DURABILITY) |
| `MentalPluginV5` `environment.isAtLeast(1,19,4)` modernProtocol | `capability:hurt_animation_bundle` **OptionalSince 1.19.4** (read via `profile.modernHurtProtocol()`) |
| `Capabilities.knockbackEvent` | `capability:knockback_event` **OptionalSince 1.20.6** |
| `PlatformProbe` `@Nullable Method hasMaxDamage/getMaxDamage` | `component:max_damage` **OptionalSince 1.20.5** (+ `effectiveMaxDurability` on the profile) |
| `SwordBlockAdapter` tier probe | `component:sword_block` **OptionalSince 1.21.0** |
| `WeaponTooltipAdapter` path probe (gained `supported()`) | `component:weapon_tooltip` **OptionalSince 1.20.5** |
| `AttackRangeAdapter` support probe | `component:attack_range` **OptionalSince 1.21.5** |
| (newly declared) projectile-KB restored, join-protection layout | `flag:projectile_kb_restored`, `marker:join_protection_layout` **OptionalSince 1.21.2** |

**Scope note (deliberate):** the `Attributes`/`Enchantments` static accessors
are KEPT as the plan-sanctioned resolution TECHNIQUE the entries call; the ~30
hot-path READ sites (DamageShaper, SessionService, EntityStates,
ArmourStrengthUnit's enum-constant static-init, RegenUnit, EraReachAttribute,
AttackChargeReset, the tester suites, …) still call them and are unchanged —
rerouting sacred combat reads (several of them static-init that cannot take an
instance) would risk the era-byte-identical invariant for no behavioural gain.
The manifest OWNS these resolutions (declares each as a typed entry, drives the
boot report + feature-disable); it does not force a read-site rewrite. This is
the "restructure ownership, do not re-derive" reading of the task.

Gate: full `./gradlew build` GREEN (PlatformProfileTest 4/4; ReconcilerTest
still 6/6 on the new veto constructor). Sequential `integrationTestMatrix` — all
seven versions FRESH PASS (test-results.txt mtimes 03:17:33 → 03:25:47, each
`PASS`, zero test-failures files), `BUILD SUCCESSFUL in 9m 31s`. Live boot line:
`platform profile — 16/25 handles resolved; sword-block=NONE
attack-range=attribute-only max-damage=material hurt-protocol=legacy; features
disabled: none` on the 1.17.1 floor; `25/25 … sword-block=BLOCKS_ATTACKS
attack-range=component max-damage=component hurt-protocol=modern; features
disabled: none` on the 26.1.2 ceiling.

### Kernel / suite discipline

Kernel untouched (additive-only honoured — no kernel edits this run). Suite
VALUES sacred: no `test-results` expectation changed; the module move and the
manifest rewiring are both behaviour-preserving, proven by two independent FRESH
matrix passes. No OCM run this cycle (not a 5.0–5.2 gate requirement; the OCM
staging is Task 5.4). Remaining Phase-5 work: 5.3 support-matrix.json + nonce,
5.4 OCM staging, 5.5 japicmp, 5.6 Folia entry (the TCK's live half), 5.7 phase
gate.

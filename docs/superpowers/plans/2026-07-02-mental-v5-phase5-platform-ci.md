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

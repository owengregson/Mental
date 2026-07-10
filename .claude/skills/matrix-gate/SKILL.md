---
name: matrix-gate
description: Use when running, verifying, or debugging Mental's verification gate — which command to run locally vs CI, the matrix shape / per-entry native-era JDK & bytecode tier / suite tiers, how to read results honestly, and the concurrency rules that keep the concurrent Paper servers stall-free.
---

# Running the verification gate

## Commands

```bash
./gradlew build                      # compile + unit tests + japicmp (always first)
scripts/integration-matrix.sh        # LOCAL gate: all paper servers AT ONCE (~2m40s)
scripts/integration-matrix.sh --versions 1.17.1,26.1.2            # targeted
./gradlew integrationTest            # sequential floor+ceiling (paper), CI PR path
./gradlew integrationTestMatrix      # sequential ALL paper + folia entries, one machine
./gradlew integrationTestFolia       # sequential folia entries only (real Folia server, JDK 25)
```

The script exists because Gradle serializes tasks within a project. Every
version, its JDK, and its CI lane come from `support-matrix.json`
(the single source the build, the script, both workflows, and the release notes
read). `integrationTestMatrix` now covers **paper + folia** — the Folia entry
(26.x) is a first-class matrix entry that boots a real Folia server and runs the
boot suite + the same-region combat smoke. On CI, the auto-release workflow
(.github/workflows/release.yml) reads the version from `gradle.properties`, runs
the paper matrix in parallel across runners, and adds the Folia gate as a
**release-only** job (an extra real server is too costly per PR); it
tags/releases only when the version is bumped (no v<version> tag yet) and fully
green — pushing a version bump to main IS the release action. Integration jobs
carry 20-minute timeouts and upload server logs on every outcome: a hung
server's log is the only evidence it leaves. It reuses run-paper's cached
paperclip jars (`~/.gradle/caches/run-task-jars/paper/jars`), one port per server
from 25600; the plugin is injected via run-paper's `-add-plugin=` on modern
builds and by COPYING the jars into `plugins/` on legacy builds (whose paperclip
rejects both `-add-plugin=` and `--nogui` — use the bare `nogui` token
range-wide).

## The matrix shape, per-entry JDK/tier & suite tiers (the 2026-07-03 full range)

- **Gate shape — 16 live server boots.** `integrationTestMatrix` = **16 entries**:
  8 legacy Paper (1.9.4, 1.10.2, 1.11.2, 1.12.2, 1.13.2, **1.14.4**, 1.15.2,
  1.16.5) at `full` + 7 modern Paper (1.17.1 → 26.1.2) at `full` + 1 Folia (26.x)
  at `combat-smoke`. All 16 matrix
  entries fresh-nonce PASS is the zero-regression proof. **1.14.4 is now a
  full-tier entry** — the Multi-Release mega-jar's v52 base loads under its
  Java-13 cap, closing the range's last hole. `serverFlags`/`IgnoreJavaVersion`
  are DEAD everywhere.
- **Per-entry native-era JDK (no flags).** Each legacy Paper build runs on the
  NEWEST Java it boots FLAGLESS — `jdk` per entry in `support-matrix.json`:
  1.9.4–1.12.2 → 21, 1.13.2/1.14.4 → 13, 1.15.2 → 14, 1.16.5 → 16 (modern
  entries 17/25). CI installs ONLY the build JDK (`jq '[.entries[].jdk] | max'`
  = 25) via setup-java and the **Gradle foojay toolchain auto-provisions** every
  server JDK on demand, exactly as locally — Temurin publishes no 13/14/16, so a
  full-set install is impossible. Locally the JDKs resolve through
  `javaToolchains.launcherFor(<major>)` (EXACT major — the script's `java_home_for`
  validates the returned home's major, because `/usr/libexec/java_home -v N` is
  "N-or-newer" and would hand a capped server too-new a JVM).
- **`bytecodeTier` is a live gate FACT (H5/Q1).** Each entry declares the
  Multi-Release tier its JVM×loader reads — v61 (`modern`) when the JVM feature
  version is ≥ 17, v52 (`downgraded`) below — and the tester ASSERTS the actually
  loaded major (`-Dmental.tester.tier=<tier>`, mandatory: the JVM-derived default
  is wrong for plain-loader entries). The boot log's `[Mental] bytecode tier:`
  line matching the declared `bytecodeTier` is REQUIRED gate evidence, quoted per
  entry alongside the nonce. Map: 1.9.4–1.12.2 → 61 (their loaders honor MR on
  Java 21), 1.13.2/1.14.4/1.15.2/1.16.5 → 52 (their JVMs are < 17), modern/Folia
  → 61.
- **`suites` tier** (`full | boot | combat-smoke`) reaches the tester via
  `-Dmental.tester.suites=<tier>`. `boot` = the legacy-backport classload/
  boot-safety suite only; `combat-smoke` = the Folia same-region pair; absent
  property = today's full behaviour (modern entries untouched). All 8 legacy
  versions are at `full` — none stay at `boot`.
- **The floor 1.9.4 AND 1.13.2 are on the PR lane** (`ci: "pr"`): the floor
  surfaces classload regressions per-PR, and 1.13.2 (Java 13, `bytecodeTier`
  52) gives the PR lane its only v52/downgraded-tier coverage — without it the
  base tree would only ever be proven at release. The other legacy entries
  stay `release`. On CI, the PR lane runs only when the change class warrants
  it: build.yml's `context` job diffs the branch against main — docs-only
  changes boot nothing, code changes run the `ci: "pr"` lanes, and
  build-toolchain changes (gradle files, `support-matrix.json`, `scripts/`,
  workflows) run the FULL paper fan-out. Pushes to main never run build.yml
  at all (release.yml owns them); `ci-ok` is the single fan-in status check
  branch protection requires.

## Reading results honestly

- **Never trust "MATRIX PASSED"/"BUILD SUCCESSFUL" alone.** The Gradle gate is
  trustworthy because of the **nonce**: each invocation stamps a fresh UUID into
  every boot (`-Dmental.tester.nonce`), the tester echoes it into
  `run/<v>/plugins/MentalTester/test-results.txt` (and `run/folia/<v>/…`) as
  `PASS nonce=<n>`, and the paired check task accepts ONLY
  that nonce — a leftover result from an earlier boot fails the check
  structurally, so staleness is impossible and mtimes are no longer the thing to
  eyeball. Confirm the file reads PASS with THIS run's nonce; failures detail in
  `test-failures.txt`.
- Live progress: `tail -f run/matrix-live.log` — `[version] RUN/PASS/FAIL`
  per case; per-server stdout in `run/<v>/matrix-run.log`; verdicts in
  `run/matrix-verdicts.txt`.

## Concurrency rules (learned the hard way, macOS)

- `caffeinate -i` per server — an App-Napped background JVM stalls for 30s+
  without EVER logging "Can't keep up" (silent-stall signature).
- Heaviest (newest) servers launch FIRST with a 3s stagger; the fast Java-17
  trio then boots into a calm machine instead of hitting its longest suites
  mid-ignition.
- Small heaps (768M) — the fifteen concurrent paper JVMs must stay far from
  memory pressure; page-fault storms read as tick stalls. (Adding 1.14.4 made
  this the 15th concurrent JVM on one machine — enough extra load to tip the
  oldest, most load-sensitive entry, 1.9.4, into the documented concurrency
  flake; a retry clears it, no assertion weakened. CI never runs 15-on-one.)
- A killed run leaves orphan servers holding `world/session.lock` (and
  ports); the script reaps `run-task-jars/paper/jars` processes at startup —
  do the same manually after killing anything.
- Harness guards are deliberately wide (sync 90s / tick-wait 120s); genuinely
  dead servers are caught by the launcher's 420s hard watchdog.
- A test that's correct sequentially but flaky concurrently is usually
  timing-anchored to wall-clock — fix the test (tick-anchored stamps), not
  the load.

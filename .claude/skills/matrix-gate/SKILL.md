---
name: matrix-gate
description: Use when running, verifying, or debugging Mental's verification gate — which command to run locally vs CI, how to read results honestly, and the concurrency rules that keep nine simultaneous Paper servers stall-free.
---

# Running the verification gate

## Commands

```bash
./gradlew build                      # compile + unit tests + japicmp (always first)
scripts/integration-matrix.sh        # LOCAL gate: all paper servers AT ONCE (~2m40s)
scripts/integration-matrix.sh --versions 1.17.1,26.1.2 --no-ocm   # targeted
./gradlew integrationTest            # sequential floor+ceiling (paper), CI PR path
./gradlew integrationTestMatrix      # sequential ALL paper + folia entries, one machine
./gradlew integrationTestFolia       # sequential folia entries only (real Folia server, JDK 25)
./gradlew integrationTestOcm         # +OCM coexistence; stages the pinned OCM jar (local run/ocm-jar wins)
```

The script exists because Gradle serializes tasks within a project. Every
version, its JDK, its CI lane, and the OCM pin come from `support-matrix.json`
(the single source the build, the script, both workflows, and the release notes
read). `integrationTestMatrix` now covers **paper + folia** — the Folia entry
(26.x) is a first-class matrix entry that boots a real Folia server and runs the
boot suite + the same-region combat smoke. On CI, the auto-release workflow
(.github/workflows/release.yml) reads the version from `gradle.properties`, runs
the paper matrix in parallel across runners, and adds the Folia and OCM gates as
**release-only** jobs (an extra real server each is too costly per PR); it
tags/releases only when the version is bumped (no v<version> tag yet) and fully
green — pushing a version bump to main IS the release action. Integration jobs
carry 20-minute timeouts and upload server logs on every outcome: a hung
server's log is the only evidence it leaves. It reuses run-paper's cached
paperclip jars (`~/.gradle/caches/run-task-jars/paper/jars`) with `-add-plugin=`
injection, one port per server from 25600. The OCM jar is staged reproducibly —
`stageOcmJar` uses a local `run/ocm-jar/OldCombatMechanics.jar` fork build AS-IS
if present, else downloads the release PINNED in `support-matrix.json` (url +
sha256) and verifies the hash.

## Reading results honestly

- **Never trust "MATRIX PASSED"/"BUILD SUCCESSFUL" alone.** The Gradle gate is
  trustworthy because of the **nonce**: each invocation stamps a fresh UUID into
  every boot (`-Dmental.tester.nonce`), the tester echoes it into
  `run/<v>/plugins/MentalTester/test-results.txt` (and `run/ocm/<v>/…`,
  `run/folia/<v>/…`) as `PASS nonce=<n>`, and the paired check task accepts ONLY
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
- Small heaps (768M) — nine JVMs must stay far from memory pressure;
  page-fault storms read as tick stalls.
- A killed run leaves orphan servers holding `world/session.lock` (and
  ports); the script reaps `run-task-jars/paper/jars` processes at startup —
  do the same manually after killing anything.
- Harness guards are deliberately wide (sync 90s / tick-wait 120s); genuinely
  dead servers are caught by the launcher's 420s hard watchdog.
- A test that's correct sequentially but flaky concurrently is usually
  timing-anchored to wall-clock — fix the test (tick-anchored stamps), not
  the load.

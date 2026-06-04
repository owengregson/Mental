---
name: matrix-gate
description: Use when running, verifying, or debugging Mental's verification gate — which command to run locally vs CI, how to read results honestly, and the concurrency rules that keep nine simultaneous Paper servers stall-free.
---

# Running the verification gate

## Commands

```bash
./gradlew build                      # compile + unit tests (always first)
scripts/integration-matrix.sh        # LOCAL gate: all servers AT ONCE (~2m40s)
scripts/integration-matrix.sh --versions 1.17.1,26.1.2 --no-ocm   # targeted
./gradlew integrationTest            # sequential floor+ceiling (CI path)
./gradlew integrationTestMatrix      # sequential all versions, one machine
./gradlew integrationTestOcm         # +OCM coexistence (needs run/ocm-jar/OldCombatMechanics.jar)
```

The script exists because Gradle serializes tasks within a project. On CI,
the auto-release workflow runs the full matrix in parallel across runners
and only tags/releases when version-bumped and fully green. It reuses
run-paper's cached paperclip jars (`~/.gradle/caches/run-task-jars/paper/jars`)
with `-add-plugin=` injection, one port per server from 25600. OCM runs are
included automatically when the OCM jar is staged.

## Reading results honestly

- **Never trust "MATRIX PASSED"/"BUILD SUCCESSFUL" alone.** Verify
  `run/<v>/plugins/MentalTester/test-results.txt` (and `run/ocm/<v>/…`) are
  FRESH (mtimes) and read PASS; failures detail in `test-failures.txt`.
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

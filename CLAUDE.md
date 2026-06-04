# Mental — agent guide

Latency-compensated 1.7.10 combat for Paper 1.17.1 → 26.x (+ Folia).
Multi-module Gradle: `api` / `common` / `core` / `compat-*` / `tester`.

## Use the skills

Project skills live in `.claude/skills/` and carry the hard-won knowledge of
this codebase. **Check them BEFORE working in their areas** — they encode
traps that each cost a debugging round to discover:

| Skill | Use when… |
| --- | --- |
| `mental-conventions` | writing/reviewing ANY code here (structure, threading, style, invariants) |
| `legacy-motion-physics` | reasoning about knockback, velocity, trajectories, movement math |
| `era-accuracy` | a change claims 1.7/1.8 authenticity or affects combat feel |
| `knockback-profiles` | touching the engine, profile schema, presets, or config files |
| `netty-fast-path` | touching the packet layer, pre-send, or reach validation |
| `ocm-coexistence` | anything overlapping OldCombatMechanics (ownership, damage shaping) |
| `paper-cross-version` | code that must behave across the version range |
| `nms-archaeology` | a version behaves unexpectedly — read the server with javap, don't guess |
| `live-server-testing` | writing/debugging integration suites (FakePlayer pitfalls, timing) |
| `matrix-gate` | running or verifying the test gate |

## Non-negotiable invariants

- **Zero-touch**: a disabled module does nothing to the game.
- **Era-exact no-op defaults**: new knobs default to byte-identical legacy
  behavior; `parse(empty) == LEGACY_17`.
- **Never touch the client-side technique contract** (0.6 self-multiplier,
  w-tap, jump-resets) — see `era-accuracy`.
- Mental owns knockback + hit delivery ONLY; combat rules belong to
  OldCombatMechanics.

## Verification gate

```bash
./gradlew build                  # unit tests — always first
scripts/integration-matrix.sh    # local: every server concurrently (~3 min)
```

Never trust the success banner alone — verify
`run/**/plugins/MentalTester/test-results.txt` are fresh and read PASS
(details in `matrix-gate`).

## Conventions in one breath

Conventional commits with prose bodies; immutable records + atomic config
snapshots; pure math classes with hand-computed unit pins; netty threads read
only frozen snapshots; entity work via `Scheduling.runOn` (Folia-correct);
imports never inline-qualified; comments explain the why; commit as you go.

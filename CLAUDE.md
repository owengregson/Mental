# Mental — agent guide

Latency-compensated 1.7.10 combat for Paper 1.9.4 → 26.x (+ Folia).
Multi-module Gradle: `api` / `kernel` / `platform` / `core` / `compat-folia` /
`tester`. `kernel` is **pure JDK** (no Bukkit, no PacketEvents — asserted at
build); `platform` is the Bukkit-facing seam (scheduling, capabilities, NMS
resolvers) over the kernel; `core` is the plugin (shades PacketEvents + bStats,
folds in `compat-folia` by name behind `Capabilities.folia()`); `tester` is the
in-server integration harness. Version lives once in `gradle.properties`.

**Legacy tier (1.9.4–1.16.5) — the full range 1.9.4 → 26.x ships as ONE jar,
NO holes (1.14.4 included), NO server flags.** `core` compiles against the
1.17.1 API floor but runs down to 1.9.4: every sub-floor absence is handled by
boot-time probing at the platform seam (never scattered version conditionals),
so absent APIs resolve to era-correct fallbacks printed in the boot report
(material/enchantment names, the six Phase-5.5 resolvers, PDC), text sinks route
through `TextPort` (relocated Adventure → legacy strings; no `Component` crosses
a Bukkit boundary), and the latency probe rides window-confirmation TRANSACTIONs
below 1.17 where the play PING/PONG channel is absent. The kernel stays
modern-vocabulary and version-blind.

Classloading down to Java 8 is an **artifact-level** fork (no source touched):
`core` and `tester` each ship ONE **Multi-Release mega-jar** — `shadowJar` →
jvmdg `DowngradeJar` (class-v52 base + `multiReleaseOriginal` original v61 under
`META-INF/versions/17`) → `ShadeJar` (distinct per-plugin runtime prefixes,
`me/vexmc/mental/lib/jvmdg` vs `…/tester/lib/jvmdg`) → the canonical
`Mental-<version>.jar`. Four gates run in `check`: `verifyDowngrade`,
`verifyJdk8Api`, `verifyTesterIsolation`, `verifyRelocation`. Every legacy Paper
build runs its **native-era Java** — the newest it boots flagless, per-entry
`jdk` in `support-matrix.json`; the tester asserts the actually-loaded tier per
entry against its `bytecodeTier` (≥Java 17 reads v61, below reads v52). **D-8
rule:** no post-Java-8 JDK type may cross a cross-plugin API descriptor (jvmdg
rewrites Java-9+ types INTO descriptors and prunes its runtime per-jar — the
kernel's Java-8-native `computeBase(…, java.util.Random)` overload is the
precedent). Ground truth: the full-range plan
(`docs/superpowers/plans/2026-07-03-mental-full-range.md`) + the v1_14_R1 shapes
doc (`docs/superpowers/research/2026-07-03-v1_14_R1-shapes.md`).

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

## The delivery core

Three single-writer domains, communicating only by immutable values:

- **D1 connection** (per player, the netty read thread): `SprintWire`
  (arrival-order sprint + freshness), `GroundFsm` (jump stamps), CPS window,
  position ring. Reads only its own inbound packets, the `TickClock`, and
  *published* views — never a live entity, never another player's state.
- **D2 session** (per player, the region thread): one `CombatSession` holds the
  `MotionLedger`, live `HitTransaction`s, and kinematics. A per-player 1-tick
  task drains its inbox, decays the ledger, and **publishes** one immutable
  `PlayerView` (`AtomicReference.set`) — the state as of the END of the previous
  tick, the boundary read the era ordering depends on.
- **D3 global** (global/main): config `Snapshot` swap, the feature reconciler,
  the `TickClock` implementation, the entityId→UUID index, the OCM binding.

The `DeliveryDesk` (kernel) is the sole `PlayerVelocityEvent` writer, the sole
`MotionLedger` writer, and it writes the delivery **journal** — the single
"what did we actually ship" seam the tester asserts against. The quantized
**valve** consumes exactly one duplicate ENTITY_VELOCITY per pre-sent knock. The
netty realm's only Bukkit-adjacent code is the packet parse **rim**
(`me.vexmc.mental.v5.rim`), pinned by an architecture test.

## Non-negotiable invariants

- **Zero-touch**: a disabled feature does NOTHING to the game.
- **Era-exact no-op defaults**: new knobs default to byte-identical legacy
  behavior; `parse(empty) == LEGACY_17`.
- **Kernel is additive-only** and Bukkit-free (both build-time asserted).
- **Never touch the client-side technique contract** (0.6 self-multiplier,
  w-tap, jump-resets) — see `era-accuracy`.
- Mental owns knockback + hit delivery (the always-on `DELIVERY` + `KNOCKBACK`
  `Feature` families), AND can OPTIONALLY own combat rules via the default-OFF
  `DAMAGE` (armour strength/durability, tool durability, critical hits, sword
  blocking), `CADENCE` (cooldown, sweep, sounds), `SUSTAIN` (golden apples,
  ender-pearl cooldown, player regen, potion durations/values), and `LOADOUT`
  (offhand, crafting, era hitbox reach) families — 16 rule `Feature`s ported
  from OldCombatMechanics. All default OFF; zero-touch and era-exact-no-op
  defaults hold. Mental still yields to OCM via the `OcmBinding`/`ArbiterCore`
  (over `MechanicToken`s) for the six mechanics OCM owns when present; the
  ported rule features are OCM-agnostic (enabling the same rule in both
  double-applies — pick one per rule). Ground truth: the v5 spec
  (`docs/superpowers/specs/2026-07-01-mental-v5-spec.md`).

## Verification gate

```bash
./gradlew build                  # unit tests (+ japicmp, + kernel-Bukkit-free) — always first
./gradlew integrationTestMatrix  # sequential: every paper + folia server, one machine
./gradlew integrationTestOcm     # OCM coexistence, floor + ceiling (stages the pinned OCM jar)
```

The matrix runs **sequentially** on one machine (every server binds the same
port); `scripts/integration-matrix.sh` is the concurrent local variant. **The
nonce is the honesty rule**: each Gradle run stamps a fresh UUID into the boot,
the tester echoes it into `test-results.txt`, and the check task accepts ONLY
that nonce — a leftover result from an earlier boot fails the check
structurally, so it can never masquerade as this run's PASS. Never trust the
"BUILD SUCCESSFUL" banner alone; the nonce+PASS check is what makes the gate
trustworthy (details in `matrix-gate`).

## Conventions in one breath

Conventional commits with prose bodies; immutable records + atomic config
(one `Snapshot` swapped by reference; the GUI writes a machine overlay, the
human YAML is never re-serialized); pure kernel math with hand-computed unit
pins; netty threads read only published `PlayerView`s; entity work via
`Scheduling.runOn` / `runGlobal` (Folia-correct); imports never inline-qualified;
comments explain the why; commit as you go.

---
name: mental-conventions
description: Use when writing or reviewing ANY code in the Mental repo — the codebase's structural conventions, threading rules, config patterns, comment/commit style, and the invariants every change must preserve (zero-touch, era-exact defaults, atomic config).
---

# Mental codebase conventions

## Structure

- Multi-module: `api` (public surface), `kernel` (**pure JDK** — no Bukkit, no
  PacketEvents, not even compileOnly; a build-time check asserts the compile
  classpath has no `org.bukkit`), `platform` (the Bukkit-facing seam over the
  kernel: `Scheduling`, `Capabilities`, NMS resolvers, boot report), `core`
  (the plugin — shades PacketEvents + bStats, folds in `compat-folia` by name
  behind `Capabilities.folia()`), `tester` (in-server integration harness).
  `common` and `compat-brigadier` are gone.
- Features are `Feature` descriptors (one sealed enum in core) with
  `Scope`-owned lifecycle: `scope.listen(...)`, `scope.packets(...)`,
  `scope.repeatOn/repeatGlobal(...)`, `scope.rule(token, decider, handler)`,
  `scope.service(closeable)` — enable runs `assemble(scope, settings)`, disable
  closes the scope (each close isolated). No `active` flag; the reconciler's map
  of open scopes is the truth.
- Knockback/damage features are **pure computers**; the `DeliveryDesk` (kernel)
  alone owns applying velocities, writing the `MotionLedger`, and writing the
  delivery **journal**. Features submit `(transaction, vector)` to the desk;
  arbitration is last-submitter-wins per victim per tick.

## Invariants (every change must preserve)

- **Zero-touch**: a disabled feature does NOTHING to the game (ZeroTouchSuite
  asserts this live). Always-on infrastructure (sessions, the parse rim taps)
  is observation-only by TYPE — the interfaces it is handed expose no mutating
  operation.
- **Era-exact no-op defaults**: every new knob defaults to byte-identical
  legacy behavior; parsing an empty section must equal `LEGACY_17` (pinned by
  parse-equality unit tests).
- **Kernel is additive-only** (the frozen delivery core) **and Bukkit-free**
  (both build-time asserted).
- **Atomic config**: one immutable `Snapshot` swapped by reference — no code
  path may read a torn mix mid-hit; hit-relevant values are frozen into the
  `HitContext` / `PlayerView`. Settings are flat immutable records with
  `DEFAULTS` and warn-and-fallback parsing through `ConfigReader`. The GUI
  writes a machine overlay (`state/overrides.yml`); the human YAML is never
  re-serialized (effective = overlay ?? file ?? default).
- **Server-authoritative velocities**: everything gameplay-affecting flows
  through the desk (and real `damage(...)` calls) so movement-prediction
  anticheats verify cleanly.

## Threading (three single-writer domains)

- **D1 connection** (netty read thread): `SprintWire`, `GroundFsm`, CPS,
  position ring — reads ONLY its own inbound packets, the `TickClock`, and
  *published* immutable `PlayerView`s; never a live entity, never `getWorld()`,
  never another player's state.
- **D2 session** (region thread): one `CombatSession` per player owns the
  `MotionLedger`, live `HitTransaction`s, kinematics — mutated only by its
  owning thread, fed a per-player inbox of immutable signals. A 1-tick task
  **publishes** one `PlayerView` (`AtomicReference.set`) = end-of-previous-tick
  state.
- **D3 global**: config swap, reconciler, `TickClock`, entityId→UUID index,
  OCM binding.
- Cross-domain communication is exclusively **immutable values**. All entity
  work goes through `Scheduling.runOn/repeatOn/runGlobal(...)` — region-correct
  on Folia, main-thread on Paper, identical code.
- Predict-then-authorize: the netty registration stamps the `SprintVerdict`
  (sprint AND wire freshness) it used into the `HitContext`; the authoritative
  owning-thread pass CONSUMES that stamp instead of a live re-read (see
  `SprintWire` / `netty-fast-path`).

## Style

- Javadoc/comments explain the WHY and the era/provenance, in prose; no
  restating code. Config YAML is exhaustively commented — it is the docs.
- Imports always; never inline fully-qualified names.
- Records over classes for data; nested records to group knob families;
  value types over raw ints for domain quantities (`TickStamp`, `HitId`).
- Pure math lives in the kernel in its own class (`KnockbackEngine`,
  `FeedbackPlan`, `ReachValidator`, `RodLaunchMath`, `DamageTables`) and gets
  exhaustive unit tests with hand-computed expectations; Bukkit shells stay
  thin. Provenance comments cite the combat compendium, never a fork's source.
- Conventional commits with substantial prose bodies explaining reasoning.
- Commands: op-only defaults, declared in `plugin.yml` and dispatched by
  `MentalCommand` (Brigadier is gone) — bare `/mental` opens the management
  GUI, `/mental reload` is the console fallback.

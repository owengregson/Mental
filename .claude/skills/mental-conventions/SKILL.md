---
name: mental-conventions
description: Use when writing or reviewing ANY code in the Mental repo — the codebase's structural conventions, threading rules, config patterns, comment/commit style, and the invariants every change must preserve (zero-touch, era-exact defaults, atomic config).
---

# Mental codebase conventions

## Structure

- Multi-module: `api` (public surface), `common` (no Bukkit deps), `core`
  (the plugin), `compat-*` (newer-API features behind runtime detection),
  `tester` (in-server integration harness).
- Features are `CombatModule`s with registry-owned lifecycle; listeners
  registered via `listen()` auto-unhook on disable.
- Modules are **pure vector computers**; `KnockbackPipeline` alone owns
  applying velocities and recording the ledger.

## Invariants (every change must preserve)

- **Zero-touch**: a disabled module does NOTHING to the game (ZeroTouchSuite
  asserts this live). Always-on infrastructure may observe, never act.
- **Era-exact no-op defaults**: every new knob defaults to byte-identical
  legacy behavior; parsing an empty section must equal `LEGACY_17` (pinned by
  parse-equality unit tests).
- **Atomic config**: one immutable `Snapshot` swapped by reference — no code
  path may read a torn mix mid-hit. Settings are flat immutable records with
  `DEFAULTS` constants and warn-and-fallback parsing through `ConfigReader`.
- **Server-authoritative velocities**: everything gameplay-affecting flows
  through the pipeline so movement-prediction anticheats verify cleanly.

## Threading

- Netty-thread code (packet listeners) reads ONLY immutable per-tick
  snapshots (`PlayerStateCache`) and concurrent primitives — never live
  entities, never `getWorld()`. Anything needing live state is resolved on
  the owning thread and frozen into the snapshot.
- All entity work goes through `Scheduling.runOn/repeatOn(entity, …)` —
  region-correct on Folia, main-thread on Paper, identical code.
- Predict-then-authorize: speculative reads PEEK shared state; the
  authoritative owning-thread path CONSUMES it (see SprintTracker).

## Style

- Javadoc/comments explain the WHY and the era/provenance, in prose; no
  restating code. Config YAML is exhaustively commented — it is the docs.
- Imports always; never inline fully-qualified names.
- Records over classes for data; nested records to group knob families.
- Pure math lives in its own class (KnockbackEngine, FeedbackBurst,
  ReachValidator, RodLaunchMath) and gets exhaustive unit tests with
  hand-computed expectations; Bukkit shells stay thin.
- Conventional commits with substantial prose bodies explaining reasoning.
- Commands: op-only defaults, permission per subtree, declared once in the
  CommandTree and rendered by whichever backend (classic/Brigadier).

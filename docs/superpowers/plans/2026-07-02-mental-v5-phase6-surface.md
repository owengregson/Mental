# Mental v5 Phase 6 — GUI, Version Cutover, Docs Reconciliation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The descriptor-driven management GUI, the 5.0.0 single-source version
cutover with branding cleanup, and reconciliation of every document (including
CLAUDE.md and the project skills) to the shipped v5 model.

**Execution grouping:** two runs — (6A) Tasks 6.0–6.1, (6B) Tasks 6.2–6.4.

## Global Constraints

- Kernel additive-only; suite VALUES sacred; era behavior untouched (this phase is
  surface + docs).
- The OLD GUI was deleted in 4E but remains the BEHAVIOR contract — read it from
  git history (`git show 7c1a533:core/src/main/java/me/vexmc/mental/gui/<file>`),
  never resurrect it wholesale; also lift `gui/menu/Buttons.wrap` (+ its test) and
  `text/Brand` from history verbatim (they were on the reuse ledger and went down
  with the 4E deletion — restoring them into v5 packages honors the ledger).
- Conventional commits + trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Gates: sequential matrix (paper+folia) + OCM stays green; fresh nonce evidence.

---

### Task 6.0 (6A): The management GUI on descriptors

`core/src/main/java/me/vexmc/mental/v5/gui/`: a Menu/MenuManager/Icon system per
the old GUI's interaction model (read from history), but the CATALOG IS THE
DESCRIPTOR REGISTRY — dashboard sections from `Family`, per-feature entries from
`Feature` metadata (displayName, blurb, iconName, live enabled state from the
reconciler), toggles via `Management.setModuleEnabled` (overlay + reload), the
knockback screen from `Snapshot.profileNames/defaultProfile` via
`Management.setGlobalProfile`, anticheat mode + OCM coordination + debug screens
via their overlay keys. No string-keyed parallel catalog may exist — a new Feature
constant must appear in the GUI with zero GUI edits (write the test: the dashboard
render enumerates every non-infra descriptor). `/mental` (no args, permission
`mental.command.use`) opens the menu — replace the 4A2.2 placeholder. Folia note:
menu writes hop through `Scheduling.runGlobal` (the old ManagementService rule).

### Task 6.1 (6A): Suite restoration + 6A gate

Un-SKIP the CommandSuite GUI-open assertion and any CosmeticSmoke GUI checks
(behavior contract from the old suites in history; VALUES/assertion strength
unchanged or stronger). Gate: full build + sequential `integrationTestMatrix`
(incl. Folia) fresh-PASS; paste evidence. Append "Phase 6 run A outcomes"; push.

### Task 6.2 (6B): Version 5.0.0 + branding cleanup

- `version=5.0.0` moves to `gradle.properties` (single home); root build reads it;
  `release.yml` reads it from there; delete the legacy-v4-tag changelog exclusion
  logic (5 > 4 sorts naturally); verify plugin.yml/facade/bStats all flow from the
  one property.
- Sweep the tree for StrikeSync remnants (`grep -ri strikesync` outside .git and
  docs/research provenance citations — justify anything kept). The repo DIRECTORY
  rename is the owner's action (note it in the outcomes, do not attempt).
- `Mental.version()` returns 5.0.0; `apiVersion()` stays 2; README/plugin.yml
  description brand check.

### Task 6.3 (6B): Docs + skills reconciliation

- `docs/knockback-profiles.md`: global model only (delete the per-player override
  narrative + `/mental kb set <player>`; ten presets; management = GUI + overlay).
- `docs/fast-path.md`, `docs/ocm-coexistence.md`, `docs/legacy-combat.md`: update
  every dead-class reference (pipeline/suppressor/tap names → the v5 seams:
  transactions, desk, journal, valve, arbiter); keep the domain truths verbatim.
- **Docs-cannot-drift check**: a unit test asserting every `KnockbackProfile`
  schema knob name appears in `docs/knockback-profiles.md` (kernel schema is the
  source; the test reads the doc file from the repo root — wire the resource path
  so it runs in CI).
- `CLAUDE.md`: rewrite the architecture description to v5 (modules kernel/platform/
  core/compat-folia/tester; sessions/desk/journal; the invariants stay; the gate
  commands updated — sequential matrix note + nonce).
- Skills (`.claude/skills/`): factual reconciliation pass on `mental-conventions`
  and `netty-fast-path` (class names, structure, lifecycle facts → v5; every
  DOMAIN truth — thread rules, era numbers, PE discipline, boundary contracts —
  preserved verbatim); lighter touch on `knockback-profiles` (global model,
  overlay) and `matrix-gate` (nonce, sequential-local rule, Folia entry, OCM pin).
  Do not rewrite history/trap narratives — append v5 mappings where classes died.

### Task 6.4 (6B): Phase gate

Full `./gradlew clean build` (japicmp incl.) + sequential matrix (paper+folia) +
`integrationTestOcm` — all fresh, nonce-verified, evidence pasted. Append
"Phase 6 outcomes / PHASE 6 COMPLETE"; push.

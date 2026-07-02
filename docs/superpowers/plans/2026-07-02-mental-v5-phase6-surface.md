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

---

## Phase 6 run A outcomes (Tasks 6.0–6.1, 2026-07-02)

**Commits:** `cba3787` feat(gui) — the descriptor-driven management GUI (6.0);
`382a026` test(v5) — the CommandSuite dashboard-open assertion un-SKIPped (6.1).

**6.0 — what shipped.** `me.vexmc.mental.v5.gui`: `Menu`/`MenuManager`/
`MenuContext`/`Icon` reprise the retired GUI's holder-identity interaction model
(single-viewer menus, click routing on `getHolder() instanceof Menu`, drags
cancelled, close-on-disable) on the v5 seams. THE CATALOG IS THE REGISTRY:
`DashboardModel` derives the dashboard's sections from `Family.values()` and each
section's entries from the non-infrastructure `Feature` constants declaring that
family; `Family` gained per-section display metadata (title/icon/blurb) so
sections are self-describing descriptors, and every entry's toggle copy reads the
feature's own `displayName/blurb/iconName`. No string-keyed parallel catalog
exists; `DashboardModelTest.everyNonInfraFeatureIsSurfaced` pins
`allSurfaced() == every non-infra descriptor` against the exact methods
`DashboardMenu`/`FamilyMenu` render from, so a new `Feature` constant appears in
the GUI with zero GUI edits. Screens: dashboard (status plate + one nav tile per
family + compatibility + debug + reload/close), `FamilyMenu` (generic toggles via
`Management.setModuleEnabled`; the KNOCKBACK section additionally carries the
server-wide profile picker from `Snapshot.profileNames()/defaultProfile()` applied
via `Management.setGlobalProfile`), `CompatibilityMenu` (anticheat.mode +
compatibility.old-combat-mechanics via their overlay keys), `DebugMenu`
(debug.enabled + debug.categories.* via their overlay keys). Every write flows
through Management / the machine overlay — the human YAML is never re-serialized;
every mutation hops through `Scheduling.runGlobal` (the old ManagementService
rule), inventory work through `Scheduling.runOn(viewer)`. Icon materials resolve
through the platform layer's new `MenuMaterials` name-probe resolver (the retired
`gui/Materials` moved to `platform`, renamed to disambiguate from
`EffectiveMaterial`): a missing constant degrades to STONE, never throws, no raw
`Material.valueOf` on the render path. Reuse-ledger assets restored verbatim:
`Buttons.wrap` + `ButtonsTest`, `text/Brand` (now `v5/text/Brand`), the
`gui/MaterialsTest` (now `MenuMaterialsTest`). `/mental` (bare, permission
`mental.command.use`) now opens the dashboard — the 4A2.2 placeholder is gone;
the console keeps the reload hint; `MentalPluginV5` registers the `MenuManager`
as always-on infrastructure and shuts it down in the teardown chain. Additive
seams: `Snapshot.profile(String)` (the picker's value preview).

**6.1 — suites.** The CommandSuite note-SKIP is gone: a permitted player's bare
`/mental` must now actually open the management menu, asserted through the same
holder-identity contract the click router uses (`getOpenInventory()
.getTopInventory().getHolder() instanceof v5.gui.Menu`) — the pre-4E GUI-era
assertion restored verbatim onto the v5 type; the console-reload case is
unchanged. CosmeticSmoke carried no GUI assertions in history (verified at
`7c1a533`) — nothing to restore there.

**Gate (fresh, nonce-verified: `./gradlew build` then sequential
`integrationTestMatrix`, launched 05:01:07 PDT 2026-07-02, BUILD SUCCESSFUL in
9m 36s, all 8 entries):**

```
run/1.17.1/…/test-results.txt        PASS nonce=d26c9706-c859-4f7d-a264-2fae12e243b3  (mtime Jul  2 05:02:42 2026)
run/1.18.2/…/test-results.txt        PASS nonce=6c762b20-8ea4-4deb-886e-68a07c019da8  (mtime Jul  2 05:04:00 2026)
run/1.19.4/…/test-results.txt        PASS nonce=086a3465-6116-4c6b-9a0e-7ad58972e98a  (mtime Jul  2 05:05:20 2026)
run/1.20.6/…/test-results.txt        PASS nonce=40dbaac2-20d9-4e0d-8169-06ceb8d65b9d  (mtime Jul  2 05:06:42 2026)
run/1.21.4/…/test-results.txt        PASS nonce=273914a2-340d-4ca7-99ac-23832ab9efd9  (mtime Jul  2 05:08:04 2026)
run/1.21.11/…/test-results.txt       PASS nonce=76690ace-c3a2-447c-8136-128fd9d2a9df  (mtime Jul  2 05:09:27 2026)
run/26.1.2/…/test-results.txt        PASS nonce=c5c2037d-2c69-4b90-a860-553737989bbd  (mtime Jul  2 05:10:49 2026)
run/folia/26.1.2/…/test-results.txt  PASS nonce=b2fe5ef1-b763-42b7-b289-c9462a3d49cb  (mtime Jul  2 05:11:00 2026)
```

The new `command: a permitted player opens the dashboard menu` case ran and
PASSED on the floor (1.17.1, 500 ms) and the ceiling (26.1.2, 505 ms); the Folia
entry ran boot + the Folia combat smoke as designed.

**Deviations:** none of substance. Notes: (1) the retired `gui/Materials` was
restored into `platform` as `MenuMaterials` rather than into `v5/gui`, per the
run brief's "icon materials resolve through the platform layer" — behaviour and
test verbatim, only home/name moved; (2) the retired DashboardMenu's per-viewer
ping tile and DebugMenu's in-chat subscribe tile were not carried over — both
read seams the v5 tree does not yet expose (a public per-player ping read on the
facade; a player-facing DebugLog sink), and the plan scopes the GUI to
descriptors + Management/overlay writes. The debug screen still manages
`debug.enabled` and every channel; a subscribe tile can return when a v5
PlayerDebugSink lands.

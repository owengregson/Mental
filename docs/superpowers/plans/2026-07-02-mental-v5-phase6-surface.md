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

---

## Phase 6 run B outcomes (Tasks 6.2–6.4, 2026-07-02)

**Commits:** `c72abdb` chore(version) — the 5.0.0 single-source cutover +
branding sweep (6.2); `ebfed16` docs(v5) — every doc + skill reconciled to the
shipped v5 delivery core, with the docs-cannot-drift test (6.3); this commit —
the phase gate evidence (6.4).

**6.2 — version + branding.** `version=5.0.0` lives ONCE in `gradle.properties`;
the root build reads it (`providers.gradleProperty("version")`), and it flows
gradle.properties → project.version → core `processResources` → plugin.yml →
`Mental.version()` (`getDescription().getVersion()`) and bStats. The built jar's
plugin.yml reads `version: '5.0.0'` (verified from `Mental-5.0.0.jar`);
`apiVersion()` stays 2; the api-version floor still derives from
support-matrix.json. `release.yml` reads the version from gradle.properties and
its legacy-v4-tag changelog exclusion is DELETED — `previous_tag` reverts to a
plain `git tag --sort=-version:refname` pick because 5 > 4 sorts naturally.
Branding sweep: `grep -ri strikesync` outside .git returns exactly three files —
`docs/superpowers/prompts/2026-06-30-mental-rewrite-prompt.md` (the mandate's
§2.5 problem statement), `docs/superpowers/specs/2026-07-01-mental-rewrite-v2-architecture.md`
(the 5.0.0 decision provenance), and this plan (the sweep instruction itself) —
all legitimate historical citations; zero product/build/CI hits. The repo
DIRECTORY is still named `StrikeSync` — the rename is the owner's out-of-band
action.

**6.3 — docs + skills.** `docs/knockback-profiles.md` is global-model-only (ten
presets; per-world → default resolution; the per-player narrative and the
`/mental kb` command family deleted; management = the GUI Knockback screen +
overlay write-back + `Mental.setKnockbackProfile(String)` +
`KnockbackProfileChangeEvent`). `docs/fast-path.md` / `docs/legacy-combat.md` /
`docs/ocm-coexistence.md` / `docs/effective-material-contract.md`: every
dead-class mention updated to the v5 seams (DamageCalculator/HitApplier →
DamageShaper+DamageTables, the pipeline → the delivery desk + journal,
snapshot → the published PlayerView, SprintTracker wire view → SprintWire,
module names → the v5 units) with every domain truth kept verbatim. The
**docs-cannot-drift test** shipped as `KnockbackDocsTest` (core unit test, runs
in `./gradlew build`): it walks up from the working dir to the repo root, reads
the real `docs/knockback-profiles.md`, asserts every `KnockbackProfile` schema
knob YAML key is documented, and pins the record's component count so a new
knob fails the build until the doc and the key list are updated. `CLAUDE.md`
rewritten to v5 (module list, the three single-writer domains +
desk/journal/valve/rim, kernel additive-only + Bukkit-free invariants, the gate
= build + sequential `integrationTestMatrix` (+Folia) + `integrationTestOcm`
with the NONCE honesty rule replacing the mtime ritual; the skills table
verbatim). Skills: `mental-conventions` and `netty-fast-path` fully reconciled
(v5 structure/threading/lifecycle; every measured value and trap narrative
preserved with "was X" v5 mappings appended — 15 annotations in netty-fast-path
alone; the "no Folia combat coverage" corollary updated to the
one-smoke-wide truth); `knockback-profiles` and `matrix-gate` got the lighter
pass (global model + overlay; nonce, sequential-local rule, the Folia matrix
entry, the reproducible OCM pin); `live-server-testing` gained the Phase 5 run C
Folia FakePlayer findings (region-thread spawn via `runAt`, `teleportAsync`, the
double-remove "Already retired" region crash, cross-region melee un-drivability,
`FoliaCombatSmoke`); `era-accuracy` / `legacy-motion-physics` /
`ocm-coexistence` had their named dead classes (VictimMotion, SprintTracker,
HitPacketListener, GroundPacketTap, OcmGate…) annotated to their v5 successors;
`nms-archaeology` / `paper-cross-version` named no dead classes — untouched.

**6.4 — the phase gate (fresh, nonce-verified).** One invocation:
`./gradlew clean build integrationTestMatrix integrationTestOcm`, launched
05:41:05 PDT 2026-07-02, **BUILD SUCCESSFUL in 10m 12s**. `clean build` ran
every unit test (including `KnockbackDocsTest`) + japicmp
(`api-5.0.0.jar` vs the committed `api-2.2.2` baseline — only the additive
`apiVersion()` delta). All 10 live entries, each check accepting ONLY its own
run's nonce; no `test-failures.txt` anywhere:

```
run/1.17.1/…/test-results.txt        PASS nonce=682bd499-ea7f-430b-8fe5-098957ea642e  (mtime Jul  2 05:42:23 2026)
run/1.18.2/…/test-results.txt        PASS nonce=f74e1b19-fce4-49e4-94d7-4d05dfc91e0a  (mtime Jul  2 05:43:41 2026)
run/1.19.4/…/test-results.txt        PASS nonce=753acfc7-4362-4996-bf56-9e5ea58ce17f  (mtime Jul  2 05:45:00 2026)
run/1.20.6/…/test-results.txt        PASS nonce=7de3966d-bb7d-4eec-b540-21305a3caf23  (mtime Jul  2 05:46:22 2026)
run/1.21.4/…/test-results.txt        PASS nonce=b544a83f-fb32-4bdc-a231-f24809d3844b  (mtime Jul  2 05:47:46 2026)
run/1.21.11/…/test-results.txt       PASS nonce=419dadc9-b5d9-4ac7-88f7-b97eaff32ca5  (mtime Jul  2 05:49:09 2026)
run/26.1.2/…/test-results.txt        PASS nonce=00d0e94e-c143-406f-8cb8-d89447adbd52  (mtime Jul  2 05:50:31 2026)
run/folia/26.1.2/…/test-results.txt  PASS nonce=370985fd-fe8f-46e6-884f-2533bc247195  (mtime Jul  2 05:50:47 2026)
run/ocm/1.17.1/…/test-results.txt    PASS nonce=1e97eab3-8a14-4cb5-94de-056f1a430459  (mtime Jul  2 05:51:00 2026)
run/ocm/26.1.2/…/test-results.txt    PASS nonce=7424ee77-098f-4367-83da-9ec47edcd04d  (mtime Jul  2 05:51:16 2026)
```

The OCM entries ran against the locally staged fork jar (`run/ocm-jar/`, the
developer-override path — hash not enforced by design).

**Deviations:** none of substance. Notes: (1) `KnockbackDocsTest` lives in
core (not kernel) so the drift check can also see the parser-side YAML key
names; the kernel stays JUnit-only-pure. (2) The 6.2 sweep briefly re-introduced
the word "StrikeSync" in a gradle.properties comment and was immediately
reworded — build files stay brand-free; the historical brand lives only in the
three provenance docs.

**PHASE 6 COMPLETE.**

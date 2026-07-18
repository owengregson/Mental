# Customization-suite — implementation task partition

Date: 2026-07-18 · Branch: `redesign/customization-suite` (primary checkout —
verify with `git branch --show-current` before writing anything; ignore
`.claude/worktrees/*` entirely).

Seven tasks, executed **SEQUENTIALLY, in this order**, each by one Opus agent
holding the full task in context. The two source plans are normative:

- **BACKEND** = `docs/superpowers/plans/2026-07-18-unified-preset-manager.md`
- **GUI** = `docs/superpowers/plans/2026-07-18-gui-redesign.md`

Both plans carry red-team resolutions in their final sections (BACKEND §10,
GUI §13) — those rulings are binding; if a plan body seems to conflict with a
resolution, the resolution wins. Read your task's plan sections IN FULL before
writing code, plus `CLAUDE.md` and the `mental-conventions` skill. Skills to
consult per task are listed inline.

**Global MUST-NOTs (every task):**
- NO edits to `kernel/`, `api/`, `Migrations.java`, any bundled resource under
  `core/src/main/resources/` (except none — no task touches resources), the 29
  archived texts under `core/src/test/resources/superseded-bundles/`, or the
  byte-identity normalization in `SupersededPresets`/`SupersededEffectsPresets`.
- NO gameplay-default or parse-default change (`parse(empty)` pins hold).
- NO change to `Management` method signatures, `MentalPluginV5.overlaySet/
  overlayRemove`, or `Overlay` routing for existing keys.
- NO version bump, README, highlights, or tester edits before Task 7.
- Conventional commits with prose bodies; commit after each listed commit
  point; never claim green without running the listed gradle command and
  reading its output (verification-before-completion).

**Shared-file sequential edits (explicitly flagged — the ONLY intentional
overlaps):**
- `core/.../gui/Buttons.java`: Task 5 ADDS the v2 factories beside the old
  ones; Task 6 DELETES the old factories with their last callers.
- No other file appears in more than one task.

---

## Task 1 — The preset backend: `me.vexmc.mental.v5.preset` + unit tests

**Executes:** BACKEND §3 (all of it — reconciled signatures incl. `iconName`
and the final PresetKind copy), §6.1, §6.2. Steps 1–2 of BACKEND §9.
**Prerequisites:** none (first task).
**Skills:** `mental-conventions`.

**Files CREATE (exactly these, nothing else):**
- `core/src/main/java/me/vexmc/mental/v5/preset/package-info.java`
- `core/src/main/java/me/vexmc/mental/v5/preset/PresetKind.java`
- `core/src/main/java/me/vexmc/mental/v5/preset/PresetInfo.java`
- `core/src/main/java/me/vexmc/mental/v5/preset/PresetCatalog.java`
- `core/src/test/java/me/vexmc/mental/v5/preset/PresetKindTest.java`
- `core/src/test/java/me/vexmc/mental/v5/preset/PresetCatalogTest.java`

**Gate (must pass before each commit):**
`./gradlew :core:test --tests "me.vexmc.mental.v5.preset.*"` then
`./gradlew build`.

**Commits:**
1. `feat(preset): unified preset catalog — one read surface for both preset kinds`
2. `test(preset): catalog resolution, preview pins, and kind metadata`

**MUST NOT:** touch any existing file (this task is purely additive); touch
`ConfigStore`/`SupersededEffectsPresets` (Task 2); re-word any preview string
(they are copied character-for-character from `ProfileMenu.java:130-153` /
`EffectsPresetMenu.java:107-129` — BACKEND §3.4 lists them verbatim); compute
test expectations by running the code under test (hand-computed pins only).

---

## Task 2 — Config hygiene: one upgrade driver + the effects hash archive

**Executes:** BACKEND §4 (driver collapse), §5 (archive extraction, the
`archivedHashes` seam, `SupersededEffectsBundleHashTest`). Steps 3–4 of
BACKEND §9.
**Prerequisites:** Task 1 committed (not a compile dependency, but the branch
must be green).
**Skills:** `mental-conventions`, `knockback-profiles` (superseded-bundle
contract background).

**Files MODIFY:**
- `core/src/main/java/me/vexmc/mental/v5/config/ConfigStore.java` — delete the
  two private upgrade methods, add `upgradeIfSupersededBundle` per §4
  VERBATIM (the log-string identity proof there is load-bearing), rewrite the
  two call sites, add the `BiPredicate` import, KEEP `ensureDeliverySection`
  on the line before the profiles-loop upgrade call with its ordering comment.
- `core/src/main/java/me/vexmc/mental/v5/config/SupersededEffectsPresets.java`
  — add ONLY the package-private `archivedHashes(String)` seam (§5.3).

**Files CREATE:**
- `core/src/test/resources/superseded-effects-bundles/signature@2.5.3.yml`
- `core/src/test/resources/superseded-effects-bundles/signature@2.5.5.yml`
- `core/src/test/resources/superseded-effects-bundles/signature@2.6.2.yml`
- `core/src/test/resources/superseded-effects-bundles/signature@2.7.0.yml`
  (ALL four via the exact `git show` commands in §5.1 — never an editor; then
  run the §5.1 hash loop and compare against the table; on ANY mismatch STOP
  and escalate. Red-team pre-verified all four reproduce, so a mismatch means
  your extraction is wrong, not the constants.)
- `core/src/test/java/me/vexmc/mental/v5/config/SupersededEffectsBundleHashTest.java`
  (§5.2 — all five methods, exact names).

**Gate:** `./gradlew :core:test --tests "*ConfigStoreTest" --tests
"*SupersededBundleHashTest" --tests "*SupersededEffectsBundleHashTest"
--tests "*MigrationsTest"` then `./gradlew build`.

**Commits:**
1. `refactor(config): one superseded-bundle upgrade driver for both preset libraries`
2. `test(config): archive the four shipped signature.yml revisions behind the effects upgrade hashes`

**MUST NOT:** change any hash VALUE, map key, or the normalization; change any
emitted log string by even one byte (the §4 proof enumerates all five shapes);
touch the extraction/discovery loops (`ensureDefaultFiles` order,
`awaitingEffectsMigration` guard, flat-file preference, dup-stem logging,
signature JAR fallback — all pinned by `ConfigStoreTest`); touch the preset
package from Task 1.

---

## Task 3 — Platform pane seam: `PaneColor` + `MenuMaterials.pane` + aliases

**Executes:** GUI §2.1 (all of it). Step 1 of GUI §12.
**Prerequisites:** none beyond a green branch.
**Skills:** `mental-conventions`, `paper-cross-version`.

**Files CREATE:**
- `platform/src/main/java/me/vexmc/mental/platform/PaneColor.java`
- `platform/src/test/java/me/vexmc/mental/platform/PaneColorTest.java`

**Files MODIFY:**
- `platform/src/main/java/me/vexmc/mental/platform/MenuMaterials.java` — add
  `pane(PaneColor)` exactly per §2.1 (modern name first; data-value ctor ONLY
  on the legacy branch; `@SuppressWarnings("deprecation")`), and the TEN
  `LEGACY_ALIAS` additions (six R7 fixes + four era-degrades — exact pairs in
  the §2.1 table; map grows 12 → 22 entries).
- `platform/src/test/java/me/vexmc/mental/platform/MenuMaterialsTest.java` —
  extend the alias test with the ten exact pairs (GUI §9).

**Gate:** `./gradlew :platform:test` then `./gradlew build`.

**Commit:** `feat(platform): pane colour seam + legacy icon aliases`

**MUST NOT:** remove or change any existing alias entry; pass a data value on
the modern path (the ctor is `ItemStack(Material)` there — red-team
javap-verified `ItemStack(Material,int,short)` + `getDurability()` exist on
the 1.17.1 floor for the legacy branch and BootSuite's later pane check);
touch core or tester.

---

## Task 4 — Design-system core: PanePattern, Chrome, Layout, Palette, Menu wiring

**Executes:** GUI §2.2 (Palette), §2.3 (PanePattern + Chrome + Menu changes),
§2.5 (Layout), §2.6 (title language — as shared knowledge; screens apply it
later). Step 2 of GUI §12. Unit tests from GUI §9: `PanePatternTest`,
`LayoutTest`, `PaletteTest`.
**Prerequisites:** Task 1 (Palette.gallery takes `PresetKind`), Task 3
(`PaneColor`, `MenuMaterials.pane`).
**Skills:** `mental-conventions`.

**Files CREATE:**
- `core/src/main/java/me/vexmc/mental/v5/gui/Palette.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/PanePattern.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/Chrome.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/Layout.java`
- `core/src/test/java/me/vexmc/mental/v5/gui/PanePatternTest.java`
- `core/src/test/java/me/vexmc/mental/v5/gui/LayoutTest.java`
- `core/src/test/java/me/vexmc/mental/v5/gui/PaletteTest.java`

**Files MODIFY:**
- `core/src/main/java/me/vexmc/mental/v5/gui/Menu.java` — add `paintChrome`;
  re-route `fillEmpty` through `Chrome.pane(PaneColor.GRAY)`; re-express
  `placeCentered` over `Layout.centeredRow` (behaviour byte-identical — the
  old math IS the new math, pinned by `LayoutTest`). Everything else in Menu
  (open/refresh/apply/navigate/promptOverlay/handleClick/selfTestInventory/
  holder identity) UNTOUCHED.

**Gate:** `./gradlew :core:test` (every existing GUI test still green — the
old screens do not call `paintChrome` and `fillEmpty` stays visually
equivalent) then `./gradlew build`.

**Commit:** `feat(gui): design system — palette, chrome, pane pattern, layout`

**MUST NOT:** touch `Buttons`, any screen class, `DashboardModel`,
`MeleeFormula`, `MenuManager`, `MenuContext`, `ChatPrompt`; write the
Family-typed `Palette.gallery` contingency (deleted by red-team — the preset
package is already landed); let any Adventure type into a tester-visible
signature.

---

## Task 5 — Buttons v2 (additive) + override seam + SettingsCatalog/SettingsMenu

**Executes:** GUI §2.4 (Buttons v2 — ADDITIVE per the red-team strategy in
§12 step 3: new factories beside the old; old ones die in Task 6), §2.7
(Overlay.has + overlayHas), §4 (SettingsCatalog — PUBLIC class, all fourteen
pages knob-for-knob incl. the §4.3 write-type + CYCLE-reader rules), §6.3
(SettingsMenu), §7.4 (the one additive SnapshotParser change). Steps 3–4 of
GUI §12. Tests: `SettingsCatalogTest`, additive `ButtonsTest` methods,
additive `EffectsPresetParserTest` methods.
**Prerequisites:** Tasks 1–4.
**Skills:** `mental-conventions`; `knockback-profiles` if anything seems to
touch profile parsing (it must not).

**Files CREATE:**
- `core/src/main/java/me/vexmc/mental/v5/gui/SettingsCatalog.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/SettingsMenu.java`
- `core/src/test/java/me/vexmc/mental/v5/gui/SettingsCatalogTest.java`

**Files MODIFY:**
- `core/src/main/java/me/vexmc/mental/v5/gui/Buttons.java` — ADD kv/round/
  nav(accent)/moduleCard/toggle(v2)/stepper(v2)/cycle(v2)/editText(v2)/
  numberPrompt/info/pointer/back(String) + the accent-taking `title` overload;
  `wrap` untouched; OLD factories KEPT for now (flagged shared edit with
  Task 6).
- `core/src/main/java/me/vexmc/mental/v5/config/Overlay.java` — add `has`.
- `core/src/main/java/me/vexmc/mental/v5/MentalPluginV5.java` — add
  `overlayHas` beside `overlaySet`/`overlayRemove` (both untouched).
- `core/src/main/java/me/vexmc/mental/v5/config/SnapshotParser.java` —
  `applyIndicatorOverrides` gains `lifetime-ticks` (intClamped
  MIN_LIFETIME..MAX_LIFETIME), `crit-threshold-percent` (numberClamped
  0..100), `roll-hold-ticks` (intClamped MIN_ROLL_HOLD..MAX_ROLL_HOLD) —
  inside the existing `reader.section() == null → preset` guard, so
  `parse(empty)` is untouched.
- `core/src/test/java/me/vexmc/mental/v5/gui/ButtonsTest.java` — additive
  methods only (`backNamesItsDestination`, `kvJoinsMutedLabelAndAccentValue`,
  `roundKeepsThreeDecimals`).
- `core/src/test/java/me/vexmc/mental/v5/config/EffectsPresetParserTest.java`
  — the two additive methods of GUI §9; existing pins untouched.

**Gate:** `./gradlew :core:test` then `./gradlew build`.

**Commit:** `feat(gui): buttons v2, override seam, and descriptor-driven settings screens`

**MUST NOT:** delete or re-sign any OLD Buttons factory (Task 6's job — the
old screens still call them); alter `Buttons.wrap` or any existing
`ButtonsTest`/`EffectsPresetParserTest` assertion; write any overlay key not
in the §4.1 tables; use a key spelling not verified there (they are copied
from the bundled YAML — do not "correct" them); make `SettingsMenu` reachable
from old screens (it becomes reachable in Task 6); touch `Snapshot`,
`Overlay.route`, or any parser besides the one `applyIndicatorOverrides`
extension.

---

## Task 6 — Screen rewrite + unified preset gallery (deletes the old world)

**Executes:** GUI §6.1 (DashboardMenu), §6.2 (FamilyMenu), §6.4
(PresetGalleryMenu), §6.5 (CompatibilityMenu), §6.6 (DebugMenu), the DELETE
list of §11, and the old-Buttons-factory deletion (shared-edit completion).
Steps 5–6 of GUI §12.
**Prerequisites:** Tasks 1–5 (gallery consumes `PresetCatalog`; screens
consume Buttons v2 + SettingsCatalog + chrome).
**Skills:** `mental-conventions`; `live-server-testing` NOT needed (tester is
Task 7).

**Files CREATE:**
- `core/src/main/java/me/vexmc/mental/v5/gui/PresetGalleryMenu.java`

**Files MODIFY:**
- `core/src/main/java/me/vexmc/mental/v5/gui/DashboardMenu.java` — §6.1
  exactly: 54 slots kept, status plate + Effects line, family rows off
  UNCHANGED `DashboardModel.homeRows()`, `homeDestination` switch DELETED,
  `selfTestIcons()` still `[statusPlate, reloadButton, closeButton]`.
- `core/src/main/java/me/vexmc/mental/v5/gui/FamilyMenu.java` — §6.2 total
  rewrite inside the same public class name/ctor; `spreadColumns` and the
  dead 6-row FEEDBACK branch deleted; public `selfTestIcons()` added.
- `core/src/main/java/me/vexmc/mental/v5/gui/CompatibilityMenu.java` — §6.5
  three radio tiles; public `selfTestIcons()`.
- `core/src/main/java/me/vexmc/mental/v5/gui/DebugMenu.java` — §6.6; the
  hand-synced `CATEGORY_SLOTS` array deleted (Layout.contentRow, capacity-14
  code comment); subscribe tile becomes a *local* region-thread flip
  (PlayerDebugSink is a concurrent set — red-team verified); public
  `selfTestIcons()`.
- `core/src/main/java/me/vexmc/mental/v5/gui/Buttons.java` — DELETE the old
  `toggle/nav/stepper/cycle/editText/back()` factories now that their last
  callers are gone (completes the Task 5 shared edit; `wrap`, `title` stay).

**Files DELETE (all eight):**
- `core/src/main/java/me/vexmc/mental/v5/gui/KnockbackFormulaMenu.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/ProfileMenu.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/EffectsPresetMenu.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/EffectsMenu.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/HitEffectsMenu.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/DeathEffectsMenu.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/DamageIndicatorsMenu.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/LootProtectionMenu.java`

**Gate:** `./gradlew :core:compileJava` then `./gradlew :core:test`
(`DashboardModelTest` untouched and green) then `./gradlew build`.
NOTE: the tester will NOT compile against this state (BootSuite still imports
deleted classes) — that is expected and resolved by Task 7; `./gradlew build`
covers core+platform+kernel unit gates. If the root `build` task forces
`:tester:compileJava`, run `./gradlew :core:build :platform:build` instead and
say so in the commit body.

**Commit:** `feat(gui): family/dashboard/system screens and the unified preset gallery on the new chrome`

**MUST NOT:** touch `DashboardModel` or `DashboardModelTest`; change
`MenuManager`, `MenuContext`, `ChatPrompt`, `MeleeFormula`, `Menu` (beyond
what Task 4 did); rename any surviving public class; alter any Management
call semantics; keep ANY per-preset icon map in GUI code (the catalog owns
them); leave any reference to a deleted class anywhere in core.

---

## Task 7 — Tester lockstep + docs + release (the closing task)

**Executes:** GUI §8 (BootSuite rewrite incl. the §7.2 pane-regression
check), BACKEND §6.3 (ProfileSuite PresetCatalog lockstep — ruled IN), GUI
§10 (README, release-highlights, gradle.properties, knockback-profiles
prose), GUI §12 steps 7–9, BACKEND §9 step 6.
**Prerequisites:** Tasks 1–6 all committed.
**Skills:** `matrix-gate` (READ BEFORE RUNNING ANYTHING), `live-server-testing`.

**Files MODIFY:**
- `tester/src/main/java/me/vexmc/mental/tester/suite/BootSuite.java` — the GUI
  render case per §8: drop KnockbackFormulaMenu/ProfileMenu/EffectsPresetMenu
  imports; add FamilyMenu/SettingsMenu/PresetGalleryMenu/CompatibilityMenu/
  DebugMenu/SettingsCatalog/PresetKind/PaneColor/MenuMaterials; keep the
  Dashboard 54-slot + holder-identity + non-AIR assertions VERBATIM; iterate
  every Family and every `SettingsCatalog.configuredFeatures()`; gallery
  renders per MeleeFormula tab (keeps that compile pin) + EFFECTS; the
  PaneColor loop (STONE = fail; legacy asserts `getDurability() ==
  legacyData()`; modern asserts type name == modernName and durability 0).
- `tester/src/main/java/me/vexmc/mental/tester/suite/ProfileSuite.java` —
  extend `runGlobalApiScenario` with the four BACKEND §6.3 asserts.
- `README.md` — GUI §10.3 verbatim (Management body, Configuration
  `effects.yml` row incl. the signature-default fix, Recommended-presets
  pointer, Debug flow line).
- `.github/release-highlights.md` — first line `<!-- v2.9.0-beta -->` + the
  §10.2 bullets.
- `gradle.properties` — `version=2.9.0-beta` + the §10.1 prose paragraph.
- `docs/knockback-profiles.md` — Runtime-control prose only (§10.5; the 27
  KNOB_KEYS strings must all remain present — KnockbackDocsTest).

**MUST NOT touch:** `plugin.yml` (verified no changes needed — GUI §10.4);
`CommandSuite` (unchanged contract); any other suite; any core/platform
source (if a tester assert fails because core is wrong, STOP and report — do
not patch core here without flagging it loudly in the commit body).

**Gate (in order, all mandatory):**
1. `./gradlew build` — unit gates + japicmp (api untouched) +
   kernel-Bukkit-free + the four mega-jar gates + KnockbackDocsTest.
2. `./gradlew integrationTestMatrix` — every Paper entry 1.9.4 → 26.x plus
   Folia, sequentially. HONESTY RULE: trust ONLY the fresh-nonce
   `test-results.txt` PASS per entry (see `matrix-gate`), never the BUILD
   SUCCESSFUL banner; zero D-9 log-scan hits. The 1.9.4/1.12.2 entries prove
   the pane data-values + ten aliases; the 26.x entry proves the modern pane
   path never passes data.

**Commits:**
1. `test(tester): headless render lockstep for the redesigned suite`
2. `docs(release): 2.9.0-beta — customization suite`

---

## Coverage ledger (every file, exactly one owning task)

| File | Task |
| --- | --- |
| `core/.../v5/preset/{package-info,PresetKind,PresetInfo,PresetCatalog}.java` + 2 tests | 1 |
| `core/.../v5/config/ConfigStore.java`, `SupersededEffectsPresets.java`, `SupersededEffectsBundleHashTest.java`, 4 archive resources | 2 |
| `platform/.../PaneColor.java`, `MenuMaterials.java`, `PaneColorTest.java`, `MenuMaterialsTest.java` | 3 |
| `core/.../v5/gui/{Palette,PanePattern,Chrome,Layout}.java` + 3 tests, `Menu.java` | 4 |
| `core/.../v5/gui/{SettingsCatalog,SettingsMenu}.java` + `SettingsCatalogTest`, `Buttons.java` (ADD half), `ButtonsTest.java`, `Overlay.java`, `MentalPluginV5.java`, `SnapshotParser.java`, `EffectsPresetParserTest.java` | 5 |
| `core/.../v5/gui/PresetGalleryMenu.java`, `{Dashboard,Family,Compatibility,Debug}Menu.java`, `Buttons.java` (DELETE half), 8 deleted screens | 6 |
| `tester/.../BootSuite.java`, `tester/.../ProfileSuite.java`, `README.md`, `.github/release-highlights.md`, `gradle.properties`, `docs/knockback-profiles.md` | 7 |

Files explicitly untouched by every task (spot-check before merging): all of
`kernel/`, all of `api/`, `Snapshot.java`, `SnapshotParser.java` beyond the
one §7.4 method, `ProfileParser.java`, `EffectsPresetParser.java`,
`Migrations.java`, `Management.java`, `MenuManager.java`, `MenuContext.java`,
`ChatPrompt.java`, `MeleeFormula.java`, `DashboardModel.java`, `Icon.java`,
`Brand.java`, `TextPort.java`, `plugin.yml`, every bundled YAML resource,
`DashboardModelTest.java`, and every existing test assertion (additive
methods only where a task says so).

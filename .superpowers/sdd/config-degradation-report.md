# 2.5.2 config degradation — design note + report

Branch: `worktree-agent-a8d571516da97604a` (cut from `release/2.5.2`, fast-forwarded to its tip `c4cc4cc` which carries the FEEDBACK family this round splits out).

## Design note

### What moves where

config.yml today carries, besides the module switches: nine per-module settings
sections. All nine move out; the file degrades to module switches + genuinely
cross-cutting policy.

| Section | New home | Grouping rationale |
| --- | --- | --- |
| `hit-feedback` | `effects/hit-feedback.yml` | Owner directive: one file per Combat Effects module, profiles-style. |
| `damage-indicators` | `effects/damage-indicators.yml` | Owner directive. |
| `death-effects` | `effects/death-effects.yml` | Owner directive. |
| `combo-hold` | `combo.yml` | The COMBO family pair — the servo and the handicap compose (the handicap opens the servo's keepable pocket), and their knobs cross-reference each other constantly; one per-family file. |
| `combo-reach-handicap` | `combo.yml` | Same file as its sibling. |
| `pot-fill` | `pots.yml` | The POTS family pair — two splash-potion utilities, introduced together, toggled independently. |
| `fast-pots` | `pots.yml` | Same file. |
| `disable-offhand` | `loadout.yml` | The LOADOUT family's two settings-bearing rules (old-hitboxes, the third LOADOUT rule, has no knobs). |
| `disable-crafting` | `loadout.yml` | Same file — the comment even explains they complement each other (the shield can only enter the off-hand via the crafting output). |

### What stays in config.yml, and why

- `config-version` — stamps the whole tree, read by the migration chain.
- `modules:` — the established pattern: settings files never carry the enable
  switch; the GUI owns toggles via the overlay (`modules.*` keys).
- `anticheat` — cross-cutting posture: it gates pre-send velocity AND reach
  validation across families; not a per-module setting.
- `metrics` — plugin-wide bStats opt-out.
- `debug` — plugin-wide logging policy.

### Why family files, not per-module files (outside effects/)

The directive names per-module files only for the effects family. combo/pots/
loadout are two-section families whose sections are coupled in prose and in
mechanism; per-module files there would push the tree toward the 30-file
explosion the directive warns against. Final set: 7 top-level files
(config / knockback / hit-registration / latency-compensation / combo / pots /
loadout) + `profiles/` + `effects/` (3 files) — each file's name is a Family
or an established concern.

### Mechanism: the in-parser fallback (the 2.4.4 reach-handicap precedent), not a config-version migration

Two repo precedents exist for a moved key:

1. **v1→v2 migration** (`Migrations.migrateV1toV2`): backup + rewrite config.yml
   from the bundled template via `YamlConfiguration.saveToString`. That rewrite
   drops the owner's own comments/formatting in the rewritten file — acceptable
   once for the v1 big-bang split, but this round's move is shape-preserving
   (the sections keep their exact keys), and re-serializing the human YAML is
   exactly what the config model promises never to do casually.
2. **In-parser legacy-location fallback** (the 2.4.4
   `combo-hold.reach-handicap` → `combo-reach-handicap` promotion, and the
   delivery-block patching's "surgical, never destructive" spirit): honour the
   old location verbatim, one loud issue line per parse naming both locations.

This round matches (2). Concretely:

- **Per-section resolution** in `SnapshotParser`: the new file's section wins
  when present; else config.yml's old-location section is honoured verbatim
  with ONE loud issue line per parse ("moved to X — honoured for now; move
  it"); else defaults. If BOTH are present, the new file wins and one loud line
  says the config.yml section is IGNORED — never a silent drop (mandate B10).
- **Guarded extraction** in `ConfigStore.ensureDefaultFiles`: each new file
  extracts when missing UNLESS config.yml still carries one of its sections —
  so a pristine bundle can never silently shadow a tuned old-location section.
  Suppression itself is silent; the parser's line is THE loud line (once per
  parse, at boot and on every /mental reload). Deleting the section from
  config.yml lets the bundled file extract on the next boot.
- **No config-version bump**: a v3 tree parses identically both ways; the
  version chain is reserved for structural rewrites, which this deliberately
  is not. The bundled config.yml keeps stamping v2 → migrations stamp v3,
  unchanged.
- **Overlay routing** (`Overlay.route`) learns the moved first-segment
  prefixes so `effective = overlay ?? file ?? default` keeps holding if a
  future GUI screen writes settings overrides (today it writes only
  `modules.*` and `knockback.profile`, which stay main-routed).
- The 2.4.3-era nested `combo-hold.reach-handicap.*` fallback now reads the
  RESOLVED combo-hold section (new file or old location), so a 2.4.3 config
  upgrading straight to 2.5.2 still carries its tuned nested scale.

### Invariant audit

- `parse(empty) == DEFAULTS` — unchanged (empty roots resolve every section to
  absent → defaults; pinned by the existing SnapshotTest and new pins).
- No default value changes; the moved YAML bodies are byte-carried (comments
  improved, values identical).
- Overlay precedence unchanged; human YAML never re-serialized (no migration
  rewrite at all).
- One immutable Snapshot; settings records and parse semantics untouched —
  only the SOURCE routing changed.
- Kernel and tester untouched.

## What was done

### Resources (core/src/main/resources/)

- **New bundled files**: `combo.yml`, `pots.yml`, `loadout.yml`,
  `effects/hit-feedback.yml`, `effects/damage-indicators.yml`,
  `effects/death-effects.yml`. Every section's YAML body is carried
  value-identical from config.yml; the comments were carried and improved
  (each file's header states where its toggle lives, the
  extract-once/edits-sacred/delete-regenerates contract, and the effects files
  document the full preset semantics — vanilla/signature/custom, the
  lists-consulted-only-under-custom rule, per-field references, fallback
  behavior on older servers). combo/pots/loadout ship their sections as
  commented templates (as config.yml did — the defaults ARE the shown values);
  the effects files ship live sections at the defaults (as config.yml did).
- **config.yml** degraded to: header (updated file map naming every neighbour
  file), `config-version`, `modules:` (comments updated to point at the new
  files), `anticheat`, `metrics`, `debug`. All module toggles stay here.

### Config layer (core/src/main/java/me/vexmc/mental/v5/config/)

- `ConfigStore`: new file-name constants + `SPLIT_FILE_SECTIONS` (file → the
  sections that moved into it — the one map both back-compat halves read).
  `Sources` grows six typed roots (+ a `Sources.of(...)` test factory that
  fills them empty). `ensureDefaultFiles` extracts each split file when
  missing UNLESS config.yml still carries one of its sections (silent
  suppression; the parser's line is the loud notice). `loadSources` loads the
  six new roots.
- `SnapshotParser`: `parse(ConfigStore.Sources)` replaces the five-arg entry.
  A `movedSection(...)` resolver runs once per moved section per parse:
  split-file section wins; old-location honoured verbatim + one loud line;
  both-present → split file wins + one loud "ignored" line. The 2.4.3 nested
  `combo-hold.reach-handicap` fallback reads the RESOLVED combo-hold reader,
  so a straight 2.4.3 → 2.5.2 upgrade stacks both migrations (pinned).
  Parsing semantics of every settings record are untouched.
- `Overlay.apply(Sources)`; `route` learns the moved first-segment prefixes so
  overlay keys on moved sections land on the root the parser reads (the
  tester's ComboSuite already writes `combo-reach-handicap.reach-scale` and
  `combo-hold.max-gap-ticks` through the real overlay path — verified those
  keys now route to the combo root).
- `MentalPluginV5.parseSnapshot` shrinks to `overlay.apply(sources)` +
  `SnapshotParser.parse(sources)`.
- Kernel untouched. Tester untouched (verified: no tester code assumes the
  old config.yml layout; ComboSuite uses overlay keys only).

### Tests

Updated call sites (ProfileParserTest, OverlayTest, MigrationsTest,
ReconcilerTest, JournalCaptureTest, CritArmourCompositionTest) and rerouted
SnapshotTest's moved-section bodies to their new roots. New pins:

- `bundledSplitFilesParseCleanAndKeepEveryEffectiveDefault` — the six real
  bundled files parse with zero issues and every effective default intact
  (fresh-install no-op; record-equality for the commented templates,
  effective-view equality for the live effects templates whose editable
  custom lists are non-default by design — exactly as the old config.yml was).
- `eachBundledSplitFileParsesToTheSameSettingsFromTheOldConfigYmlLocation` —
  requirement (a): the same bundled bytes load as either root and parse every
  moved feature to the SAME settings; the old location earns exactly one
  moved line per live section.
- `aTunedOldLocationSectionIsHonouredWithOneLoudLine` — requirement (c): a
  tuned config.yml hit-feedback section applies verbatim + one line naming
  the section, both files, and the way out.
- `aTunedOldLocationComboSectionKeepsItsNestedLegacyMigrationToo` — the
  2.4.3 stack: old-location combo-hold + nested reach-handicap → both loud
  lines, tuned gain and scale both carried.
- `whenBothLocationsCarryASectionTheSplitFileWinsLoudly` — the shadow case.
- ConfigStoreTest: `splitFileOwnerEditsSurviveAndADeletedSplitFileRegenerates`
  (requirement (b)), `splitFileExtractionIsSuppressedWhileConfigYmlCarriesTheOldSection`
  (suppression + release when the old section is deleted), and the
  extraction sweep now covers all ten files.
- OverlayTest: `overlayKeysOnMovedSectionsRouteToTheSplitRoots`.

### Docs

README config-file table (+combo/pots/loadout/effects rows), docs/combo-hold.md
(knob location + the 2.5.2 note), the knockback-profiles skill's config-model
paragraph, and the config.yml header file map.

## Test evidence

- `./gradlew :core:test` — **273 tests, 0 failures, 0 errors** (SnapshotTest
  30, ConfigStoreTest 17, OverlayTest 4, MigrationsTest 5, all green).
- `./gradlew build` — **BUILD SUCCESSFUL**: full unit suites, japicmp,
  kernel-Bukkit-free gate, and all four mega-jar gates (`verifyDowngrade`,
  `verifyJdk8Api`, `verifyTesterIsolation`, `verifyRelocation`); the jdk8-gate
  scans of Mental-2.5.2.jar and MentalTester-2.5.2.jar both OK.

## Concerns

1. **Worktree base**: the worktree branch was cut at `6b394bb` (pre-FEEDBACK);
   I fast-forwarded it onto `release/2.5.2`'s tip `c4cc4cc` (a clean ff — the
   branch point was an ancestor) before working, since the effects modules this
   round splits out live there. The controller's merge sees release/2.5.2's
   history plus my two commits.
2. **Upgraded 2.5.2-dev installs** (any server that booted a pre-split 2.5.2
   build) carry the three effects sections live in config.yml, so they will see
   three "moved to effects/…" lines per boot until the sections are deleted.
   That is the designed contract (loud, once per reload, honoured verbatim) —
   same as the 2.4.4 reach-handicap notice. No released version (≤2.5.1)
   carries any moved section, so real-world installs upgrade silently.
3. **The overlay-materialises-the-split-section edge**: if a future GUI screen
   writes a settings override for a moved section while an install still keeps
   the old-location section, the override makes the split root win and the
   shadowed config.yml section is named loudly (the "ignored" line). Loud, not
   silent — and today's GUI writes only `modules.*` + `knockback.profile`, so
   the edge is theoretical.
4. **Intentional non-move**: `anticheat`, `metrics`, `debug` stay in config.yml
   as genuinely cross-cutting policy; `old-*`/`disable-attack-sounds`/
   `disable-sword-sweep`/`sword-blocking`/`old-hitboxes` etc. have no settings
   sections at all (toggle-only), so nothing of theirs exists to move.
5. docs/combo-hold.md line ~130 still says the handicap "depends on combo-hold"
   — stale since the 2.4.5 standalone split, pre-existing and unrelated to this
   round; left alone to keep the diff config-layer-focused.

## Branch + commits

- Branch: `worktree-agent-a8d571516da97604a` (contains release/2.5.2 tip `c4cc4cc`)
- `5fcd355` — docs: design note for the 2.5.2 config degradation round
- `0e58038` — feat(config): degrade config.yml to switches + policy — the 2.5.2 per-concern split
- Final commit hash: see the last line of `git log` on the branch (the report
  copy commit follows this file's update).

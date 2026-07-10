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

(implementation summary, test evidence, concerns — filled in below after implementation)

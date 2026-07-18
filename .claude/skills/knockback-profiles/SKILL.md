---
name: knockback-profiles
description: Use when changing the knockback engine, profile schema, presets, or config files — the profile system's resolution model, engine order of operations, preset provenance, and the checklist for adding a knob without breaking era exactness.
---

# The knockback profile system

## Model

- A profile = one complete feel (`KnockbackProfile` record), one file under
  `profiles/<name>.yml`. Ten shipped presets: `legacy-1.7` (default),
  `legacy-1.8`, `kohi`, `minehq`, `badlion`, `velt`, `mmc`, `lunar`,
  `signature`, `custom`. `signature` is Mental's OWN tuning (not a ported
  fork config) — velt's wipe shape tuned to hold the combo reach pocket:
  `air.horizontal 0.92` + `air.vertical 0.98` trim the airborne follow-ups
  (the `air` multipliers apply only to airborne victims, so they retune combo
  hits 2+ without touching the grounded opener), and `base.vertical 0.365`
  keeps a touch more lift on descending hits (the 0.36 cap still bites on the
  opener). Its 2.2.0 revision (`air (0.92, 1.0)`, `base.vertical 0.36`) is in
  `SupersededPresets`, so an unedited 2.2.0 file upgrades to the 2.2.1 tune.
  Presets are
  extracted only when missing — owner edits are
  sacred, deleting regenerates pristine — with ONE exception: a file still
  matching a superseded bundled revision verbatim (`SupersededPresets`,
  byte-identity after newline normalization — since 2.4.9) is upgraded in
  place when research corrects a preset. Any edit at all — a value, a
  comment, whitespace — freezes the file forever.
- Knockback is **GLOBAL** (since 2.1.0): resolution is by the VICTIM's
  per-world map (knockback.yml) → server default. There is NO per-player
  override — the override map, `/mental kb set <player>`, and the per-player
  API/`PlayerKnockbackProfileChangeEvent` were removed when management moved
  into the in-game GUI. The default is set through the menu's Knockback screen
  (`Management.setGlobalProfile`), which writes the machine **overlay**
  (`state/overrides.yml`) and reloads — the human `knockback.yml` is never
  re-serialized; `KnockbackProfileChangeEvent` (no player) fires on a global
  change, and the public `Mental.setKnockbackProfile(String)` shares that same
  write-back path. The netty pre-send reads the profile FROZEN into the victim's
  per-tick `PlayerView`, so prediction and truth share one profile.
- Config is split by concern: config.yml (module switches + cross-cutting
  policy ONLY since 2.5.2), knockback.yml (selection + rod/fishing/projectile),
  hit-registration.yml, latency-compensation.yml, combo.yml (servo + reach
  handicap), pots.yml, loadout.yml, effects/ (one file per Combat Effects
  module), profiles/. The 2.5.2 splits ride the extracted-only-when-missing
  contract with a guard: a split file never extracts while config.yml still
  carries its old-location section (the parser honours the old location with
  one loud line per reload — SPLIT_FILE_SECTIONS in ConfigStore drives both
  halves). v1 single-file configs migrate automatically (backup
  `config-v1-backup.yml`; tuned knockback becomes profiles/custom.yml,
  selected).

## Engine order of operations (KnockbackEngine)

1. LEGACY resistance roll (all-or-nothing, may cancel)
2. Direction from source POSITION (attacker/angler/shooter — never the
   projectile); range-reduction taper shaves the horizontal push
   (melee-only, 3D distance)
3. Base: `motion×friction − dir×push`; vertical ADD (`vy×f + base`) or SET
   (assigned); horizontal cap rescales; vertical cap clamps BASE only
4. Extras along attacker yaw (sprint levels × extra-or-wtap + enchant levels
   × extra; the vertical bonus is ONE flat term — vanilla never scales it
   per level; wtap freshness comes from the SprintWire: toggle arms,
   authoritative hit consumes, pre-send peeks)
5. Air multipliers (airborne victims) → add offsets (sign-matched,
   axis-ratio) → vertical-min floor → SCALING resistance (horizontal) →
   ±3.9 packet clamp

## Adding a knob — the checklist

1. Default must be an era-exact no-op; `parse(empty) == LEGACY_17` stays true.
2. Knob goes in `KnockbackProfile.parse` + ALL preset yml files + custom.yml
   with full comments + project/docs/knockback-profiles.md.
3. Unit-pin the math in KnockbackEngineTest (hand-computed expectations) AND
   the no-op-at-default property.
4. Preset era pins live in the kernel `PresetsTest` + core `ProfileParserTest`
   (each bundled file parses to its `Presets` constant) — a regenerated preset
   can never drift; the docs-cannot-drift `KnockbackDocsTest` pins every schema
   knob into project/docs/knockback-profiles.md.
5. Provenance: every fork preset is ported from ARCHIVED server configs
   (two independent archives, byte-identical where they overlap; kohi also
   confirmed ×3 + matches the archive). Lineage (verified round 2):
   kohi/minehq 1.7 base SOURCE-verified (prplz/kohi-april-2016 v1_7_R4;
   HCTeams v1_7_R3) and badlion ran a 1.7 back-end → combos true + tracker;
   mmc = 1.8.8 ClubSpigot → combos false + immediate; lunar base UNKNOWN
   (gatekept LunarSpigot) → flat as the era norm; velt = friction-wipe
   shape (combos moot at 0.1 survival). CAVEAT all 1.7 calls: the
   SportBukkit revert was backported into late-era 1.7.10 forks (Kaijo
   carries it) — combos:true ships the attested prime feel, not their
   code. mmc's dev123 (2017) attribution rests on the archivists ("dev123"
   is also a MineHQ dev-host convention). Fork presets ship
   armor-resistance: none (era pools couldn't trigger the roll; legacy
   randomizes zero-knocks on modern gear). Cite in the preset header.
   Research base: project/docs/research/2026-06-04-improved-knockback.md +
   project/docs/research/2026-06-12-archived-server-values.md (the porting and
   lineage decisions, the measured before/after).

## The delivery knobs (1.4.0; semantics corrected 1.5.0)

`delivery.melee` / `delivery.projectile`: `tracker` and `immediate` BOTH
ship the full stamp — vanilla 1.7.10's tracker wire was join-order bimodal
(era-wire-measurements addendum 2) and the dominant mode shipped undecayed,
identical to 1.8.9's in-attack send; the names document provenance, not a
behavioral difference. `tracker-decayed` is the opt-in later-joiner wire:
one victim-physics-tick decay, friction from the victim's ground state AT
THE HIT (captured at submit; the velocity event fires after fake players
have already physics-ticked airborne). Never ship the decayed wire by
default — that artifact survived a release as "era truth" and read as
broken knockback (~1-block standing flights). LEGACY_17 (and parse-empty)
= tracker/tracker; 1.8-lineage presets (legacy-1.8, mmc, lunar) =
immediate melee; projectile = tracker EVERYWHERE (rod/projectile knocks
rode the tracker on both eras — the old mmc immediate/immediate was wrong
and is superseded). ConfigStore patches the missing block into pre-1.4.0
bundled preset files (never custom.yml). With pre-send on, the netty path
applies the profile's delivery and ships; the authoritative pass ADOPTS
that vector and the duplicate outbound packet is suppressed — one wire
stamp per knock, like the era.

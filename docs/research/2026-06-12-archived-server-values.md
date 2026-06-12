# The archived server values — preset corrections, verified

**Date:** 2026-06-12.
**Question:** can Mental's non-vanilla presets actually recreate the
knockback of the best HCF/practice servers? The owner's report: "huge
problems — lack of vertical KB, inability to combo, problems with
sprint-trading."
**Verdict:** the engine was never the problem (its order of operations
matches the canonical fork patch exactly); the *values* were. The mmc and
lunar presets were built from community remakes/recreations because no
primary source was known at the time (2026-06-04 round). Primary sources
exist: two independent GitHub archives carry the real servers' configs,
cross-confirmed byte-identical where they overlap. Every preset is now
ported from archived values, and three new presets (minehq, badlion, velt)
join from the same archives.

## The sources

- **A:** `github.com/threeamnewacc/Server-Archive` — a sprawling archive of
  era server files. Knockback files: `Badlion.net/Knockback/{PotPvP,RodPvP}
  .txt`, `Lunar.gg/Knockback (S5)/Knockback.txt`, `Minemen.club/Knockback
  (2023)/{KitPvP (2023).txt, NOTE.txt, dev123.minemen.club (2017).txt}`,
  `Zonix.us/Knockback/Knockback.txt`, `Zonix.us/Server files/NA-Practice/
  edater-knockback.yml`.
- **B:** `github.com/sprytex/Knockback-Values` — 13 value files
  (Java-assignment snippets despite the `.json` extension, matching
  Spigot-fork patch fields). README: "Yes, these are the real values, not
  some remake."

Every file was fetched raw (raw.githubusercontent.com), twice through
independent fetchers, byte-compared. Where the archives overlap — dev123,
Lunar S5, Zonix 2018, both Badlion ladders — they agree **byte-identically**
(including whitespace quirks). That proves a common lineage, not
authenticity; but the sets also reproduce the era's documented feel
complaints with uncanny precision (see lunar below), the kohi2016 file
matches the values already confirmed across three independent sources in
the 2026-06-04 round, and no contradicting primary source is known.
Confidence: high for kohi (quadruple-sourced), solid for the rest
(dual-archive), with the caveat recorded here.

## The values (friction is the published DIVISOR; Mental ports 1/d)

| Server | friction | h | v | vLimit | extraH | extraV |
| --- | --- | --- | --- | --- | --- | --- |
| Vanilla 1.8 | 2.0 | 0.4 | 0.4 | 0.4 | 0.5 | 0.1 |
| Kohi 2016 | 2.0 | 0.35 | 0.35 | 0.4 | 0.425 | 0.085 |
| Kohi 2015 | 1.9 | 0.36 | 0.36 | 0.36 | 0.45 | 0.09 |
| MineHQ | 2.0 | 0.36 | 0.36 | 0.4 | 0.45 | 0.09 |
| MMC dev123 (2017) | 1.8 | 0.32 | 0.32 | 0.4 | 0.5 | 0.1 |
| Badlion NoDebuff | 2.0 | 0.34 | 0.34 | 0.4 | 0.48 | 0.085 |
| Badlion BuildUHC | 1.9 | 0.5 | 0.34 | 0.34 | 0.6 | 0.125 |
| VeltPvP | 10.0 | 0.325 | 0.36 | 0.36 | 0.5 | 0.0 |
| Ikari | 3.5 | 0.325 | 0.36 | 0.36 | 0.5 | 0.0 |
| Kihar | 10.0 | 0.477 | 0.36 | 0.4 | 0.36 | 0.0 |
| Lunar S5 | 1.46 h / 1.31 v | 0.54 | 0.44 | 0.361735 | 0.38 | 0.0 |
| Zonix 2018 | 1.64 h / 1.406 v | 0.36 | 0.344 | (none) | 0.5 | 0.1 |

Minemen 2023 (`KitPvP (2023).txt`) uses a different knob family entirely:
`Horizontal Multiplier 0.9055 · Vertical Multiplier 0.8835 · Range Factor
0.0 · Max Range Reduction 0.4 · Start Range 3.0 · Idle Reduction 0.6`, with
NOTE.txt: "Since 2019, Minemen's knockback values never changed. but the
knockback patches did." The 0.9055 corroborates the ClubSpigot remake's
constant — the remake was reconstructing THIS model. Its semantics (what
exactly the multipliers scale; what "Idle Reduction" gates) are not public,
so Mental ships the confirmed 2017 six-knob set rather than a guessed
translation of the 2023 model. Revisit only with primary semantics.

## What was wrong, preset by preset (measured before the fix)

Live wire vectors measured on the lab server (Paper 1.21.11 + Mental 1.7.0,
real protocol-client attacker through the netty fast path, 2026-06-12;
matches the engine simulation to 4 decimals):

| Preset (old) | standing wire (h, v) | sprint settle | defect |
| --- | --- | --- | --- |
| legacy-1.8 (control) | 0.400, 0.3608 | era-exact | — |
| kohi | 0.350, 0.3108 | 4.01 blocks | values right; `combos: false` denied the 1.7.10 ledger on the server the combo memory is FROM |
| mmc (remake) | 0.385, **0.2564** | 4.41 blocks | assigned vertical below the era standing baseline (0.3608): the "no vertical KB" report. Label-swap guess, taper invented by the remake |
| lunar (recreation) | 0.460, 0.3012 | **2.86 blocks** | sprint extra 0.138 → trades 42% short of era distance: the "sprint-trading problems" report. Zero sprint vertical compounds it |

The corrected lunar (S5 archive) ships 0.92 h sprint (settle ≈ 4.6); the
corrected mmc ships the vanilla ADD shape (0.2764 standing vertical,
0.82 h sprint). Note the S5 set *faithfully* keeps Lunar's weak sprint
differential (extraH 0.38 < base 0.54) — era players' "you can hold W and
trade" complaints about Lunar are in the archived numbers, and the preset
header says so instead of silently "fixing" history.

## Era-model calls (combos / delivery), per preset

The six-knob values say nothing about delivery or melee-residual handling —
those are fork-lineage calls:

- **kohi, minehq — 1.7.10 lineage → `combos: true`, tracker wire.** Kohi and
  MineHQ are THE 1.7.10 HCF servers; the repo's own era research
  (era-accuracy: "the community's 'comboable 1.7' memory: Kohi/MCSG forks")
  and the 1.7.10 CraftBukkit lineage (no melee motion revert → residual
  compounding) both point the same way. Confidence: high for the era model,
  by-construction rather than by-measurement for these specific servers.
- **badlion, mmc, lunar — 1.8 practice lineage → `combos: false`,
  immediate melee.** 1.8.8-fork practice networks; attack() sends then
  restores, melee never feeds itself.
- **velt — `combos: false`, documented as near-moot:** at 0.1 survival a
  ledger residual contributes < 0.03 blocks; the friction wipe IS the
  anti-combo-stacking design.
- **projectile delivery — tracker everywhere.** Rod/projectile knocks rode
  the tracker on BOTH eras (2026-06-05 wire round); the old mmc preset's
  `projectile: immediate` contradicted the repo's own measurement and is
  corrected.
- **armor-resistance — `none` on every fork preset.** The fork code carried
  1.8's probabilistic whole-knock cancel, but no item on any era pool could
  trigger it; on modern pools `legacy` produces randomized zero-knocks the
  source servers never exhibited (and disables the victim's pre-send
  entirely). `none` preserves the on-server feel. The legacy-1.8 preset
  keeps `legacy` — it documents the era CODE, not a fork's feel.

## The upgrade path

Presets are extracted-when-missing and owner edits are sacred — which means
a correction would never reach existing installs. `ConfigStore` now
recognizes a file that still parses to a superseded bundled revision
verbatim (`SupersededPresets`: the 1.3.0–1.7.0 kohi/mmc/lunar) and replaces
it with the corrected preset, logging loudly. Any value difference = owner
edit = untouched forever. Comment-only edits are the one casualty, accepted
and documented.

## What this round did NOT change

- The engine. Its order (friction → base → cap base-only → uncapped yaw
  extras) was re-confirmed against the canonical fork patch shape
  (SportPaper 0188, PandaSpigot 0014, MWHunter/KohiKB corroborating the
  cap-before-extra placement). No new knobs: every archived set is
  expressible in the existing vocabulary.
- The legacy presets and `custom`. Era-exact defaults stay byte-identical;
  `parse(empty) == LEGACY_17` untouched.
- Hit-delay. Fork-era *cadence* comboability (hit-delay 0–5) remains OCM's
  knob (`attack-frequency`), per the standing non-goal. A preset cannot
  make a server comboable alone if OCM's playerDelay fights it — the
  diagnostic order from era-trade-feel still applies: OCM modeset,
  playerDelay, then the engine.

## Loose ends, recorded honestly

- The lineage calls for velt (and the delivery choice for lunar) rest on
  era context, not direct evidence about those servers' forks; at velt's
  friction the call is imperceptible, at lunar's it only moves boundary-hit
  timing (tracker ≡ immediate at the wire since 1.5.0).
- Kihar, Zonix, edater, Badlion BuildUHC are archived but not shipped as
  presets — values live in this doc and the preset headers (copy a file to
  run them). Zonix has NO vertical limit in the archive — port as `-1`.
- The Minemen 2023 multiplier model awaits primary semantics.
- The two archives' common lineage means "byte-identical" is corroboration,
  not independent proof. Anyone with a genuine era fork config that
  contradicts these values should reopen this file.

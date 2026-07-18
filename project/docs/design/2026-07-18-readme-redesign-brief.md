# Mental README redesign — brief for Claude Design

**Audience of this brief:** Claude Design, generating graphics and assembling the new README.
**Audience of the deliverable:** server owners and players deciding whether to download the plugin. Developers get a compact section at the bottom; everyone else comes first.
**Model:** the owner's StarEnchants repo (https://github.com/owengregson/StarEnchants) — its landing-page structure, committed-SVG visual system, and voice. This brief adapts that system to Mental (one deliberate difference: Mental has no external docs site, so the README keeps the user documentation in-page rather than linking out).

The complete README copy is in §4 — **use it verbatim** (typo fixes fine; factual changes are NOT, see §2). Your work is: create the asset set (§3), assemble §4's copy with the assets wired in, and open a PR replacing `README.md` and adding `/assets`. Everything else user-facing (release bodies, release template, repo metadata, plugin.yml) is handled separately — do not touch those files.

---

## 1. The visual system (from the StarEnchants model)

- **README = landing page.** Hero card → download buttons → tagline → chip row → feature cards, all centered HTML. Real Markdown (tables, code fences, text headings) appears only from "Getting started" down.
- **All styling is committed SVGs under `/assets`** — GitHub strips CSS, so color/type live inside the SVGs. **Zero shields.io badges anywhere** — the current README's badge row dies; compat/version facts move into the chip row and the Compatibility section.
- **Section "headings" are SVG images** (`height="54"`) centered in `<p align="center">`, not `#` text. Text headings (`###`) are allowed only inside the developer section.
- **Theme-aware hero** via `<picture><source media="(prefers-color-scheme: dark)">`.
- **Feature cards** are a 2-column `<table>` with `<td width="50%" valign="top">` cells: icon (`width="40"`) + `<b>bold noun-phrase title</b>` + one packed sentence.
- **`<br>` spacers** between major blocks for vertical rhythm; one divider SVG before the footer.

## 2. Non-negotiable facts (accuracy guardrails)

Every claim in the copy below is source-verified. Do not add capabilities, numbers, or version claims beyond these; if a graphic needs a number, take it from here.

- Range: **Paper 1.9.4 → 26.x, one jar, no holes (1.14.4 included), no flags**. 15 Paper versions + Folia in the release test matrix (16 live server boots). Folia supported, release-gated on real Folia 26.x.
- **Java 8 or newer** — whatever the server itself runs (Multi-Release jar; modern JVMs read the Java-17 tree, older JVMs the Java-8 tree).
- **Zero dependencies** (PlaceholderAPI is optional/softdepend).
- **13 knockback presets**: `profiles/legacy/` — signature, lunar, badlion, velt, kohi, minehq, mmc, legacy-1.7, legacy-1.8, custom; `profiles/modern/` — modern-vanilla, modern-uplift, modern-combo. (The old README says 10 flat — that is stale.)
- **10 feature families**; 8 engine features default ON; **~24 optional modules all default OFF** (the era ruleset + combo solver + pots + combat effects + loot protection).
- Command: **`/mental`** (alias `/mtl`), op by default; `/mental reload` from console. Knockback profile is **global** (server-wide), not per-player.
- Developer modules (the old README's table is WRONG): `api / kernel / platform / core / compat-folia / tester`. Public artifact: **`me.vexmc:mental-api` 3.0.0** (API generation 3), attached to every release.
- License MIT. Metrics via bStats (toggleable in `config.yml`).
- **No screenshots exist and none may be fabricated.** All art is vector/typographic SVG. If GUI screenshots are wanted later, the owner captures them from a real server (mark as a follow-up, not part of this pass).

## 3. Asset manifest (all new — the repo has zero images today)

Brand direction: Mental is precision-combat nostalgia — "2013 PvP, engineered." Palette suggestion: near-black slate ground with an **ember/crimson accent** (combat heat) and off-white type; light-theme variants invert to paper-white ground. Brand glyph: a **hit-marker/crosshair spark** (⌖ / ✛ family — four ticks around a center, like a registered hit). Use the glyph as chip bullet, divider motif, and sign-off mark — Mental's equivalent of StarEnchants' ✦. You have aesthetic latitude on letterforms, texture, and exact hues within this direction; keep it sharp and engineered, not cartoon.

| Asset | Path | Size | Content |
|---|---|---|---|
| Hero (dark) | `assets/hero-dark.svg` | `width="860"`, ~860×240 | Wordmark **MENTAL** + the hit-marker glyph + micro-tagline "classic combat, engineered" (or the §4 tagline shortened) |
| Hero (light) | `assets/hero.svg` | same | Light-theme variant |
| Download button | `assets/buttons/download.svg` | `height="46"` | "Download latest" — links `/releases/latest` |
| Releases button | `assets/buttons/releases.svg` | `height="46"` | "All releases" — links `/releases` |
| Section headers ×9 | `assets/headers/{features, getting-started, presets, ingame, configuration, ruleset, faq, compatibility, developers}.svg` | `height="54"` | Header text per §4 section titles, consistent type treatment |
| Card icons ×6 | `assets/icons/{knockback, hitreg, latency, jar, ruleset, gui}.svg` | `width="40"` | Simple line-weight glyphs: KB arc, hit-marker, ping wave, single jar, toggle rows, menu grid |
| Divider | `assets/divider.svg` | `height="22"` | The hit-marker glyph as a horizontal separator |
| Social preview | `assets/social-preview.png` | 1280×640 | Hero composition for the GitHub social card (the owner uploads it in repo settings — note this in the PR description) |

## 4. The complete README copy

Notes for assembly: `<!-- ASSET: ... -->` marks where each image goes with its link target. Copy between the markers is final. The chip row uses `<code>` pills with the brand glyph.

```markdown
<!-- ASSET: hero via <picture>, dark assets/hero-dark.svg / light assets/hero.svg, width 860, centered -->

<!-- ASSET: buttons/download.svg → /releases/latest ; buttons/releases.svg → /releases ; centered row -->

<p align="center">
  <b>Classic 1.7/1.8 combat for modern Paper &amp; Folia.</b><br>
  The hits, knockback, and combos of golden-era PvP — latency-compensated, anticheat-friendly,
  and tuned from the era's real game code, not folklore.
</p>

<p align="center">
  <code>⌖ 13 knockback presets</code>
  <code>⌖ Paper 1.9.4 → 26.x</code>
  <code>⌖ Folia</code>
  <code>⌖ Java 8+</code>
  <code>⌖ 24 optional era modules</code>
  <code>⌖ zero dependencies</code>
</p>

<!-- ASSET: headers/features.svg -->

<table>
<tr>
<td width="50%" valign="top">
<!-- ASSET: icons/knockback.svg -->
<b>Knockback that feels right</b><br>
The real 1.7/1.8 knockback model — sprint resets, w-taps, jump-resets, combos — with
<b>13 presets</b> spanning the classic servers (<code>signature</code>, <code>lunar</code>,
<code>badlion</code>, <code>kohi</code>, <code>minehq</code>…) and three modern-formula feels.
Pick one, or tune your own in YAML.
</td>
<td width="50%" valign="top">
<!-- ASSET: icons/hitreg.svg -->
<b>Hits that register</b><br>
Attacks are read at the packet layer, in arrival order — the way era servers processed them.
Fast swings, boundary hits, and w-tap timing land the way your players expect instead of
dissolving into server-tick luck.
</td>
</tr>
<tr>
<td width="50%" valign="top">
<!-- ASSET: icons/latency.svg -->
<b>Fair at any ping</b><br>
Latency compensation keeps knockback and combos consistent whether a player is at 5&nbsp;ms or
150&nbsp;ms — and everything ships server-authoritative, so movement-prediction anticheats
verify Mental's combat cleanly.
</td>
<td width="50%" valign="top">
<!-- ASSET: icons/jar.svg -->
<b>One jar, every server</b><br>
A single download runs on <b>every</b> Paper build from 1.9.4 to 26.x — 1.14.4 included — plus
Folia, on whatever Java your server already uses (<b>8 and up</b>). No flags, no per-version
builds. Every release passes a <b>16-server live test matrix</b> before it ships.
</td>
</tr>
<tr>
<td width="50%" valign="top">
<!-- ASSET: icons/ruleset.svg -->
<b>The full 1.8 ruleset — optional</b><br>
Around <b>24 switchable modules</b>: old armour strength, no attack cooldown, 1.8 crits and
sword blocking, golden apples, potions and regen, era hitboxes, instant pots, loot protection,
and more. <i>Every one is OFF by default</i> — Mental changes nothing you didn't ask for.
</td>
<td width="50%" valign="top">
<!-- ASSET: icons/gui.svg -->
<b>Run it from in-game</b><br>
<code>/mental</code> opens a full management menu — flip modules, switch knockback presets,
and tune combat effects live, no restart. Every config file is still plain, documented YAML
if you'd rather edit by hand.
</td>
</tr>
</table>

<!-- ASSET: headers/getting-started.svg -->

1. **Download** `Mental-<version>.jar` from [the latest release](../../releases/latest).
2. **Drop it** into your server's `plugins/` folder.
3. **Restart.** That's it — the defaults are the classic combat, no setup required.

Configuration is generated under `plugins/Mental/` on first boot, and every option is
documented inside its own file. Upgrading from an old single-file config? It migrates
automatically — your tuned values become `profiles/custom.yml` (and stay selected), and the
original is kept as `config-v1-backup.yml`.

> **No dependencies.** Mental needs nothing else installed. (PlaceholderAPI is supported but
> optional.)

<!-- ASSET: headers/presets.svg -->

The knockback profile is one server-wide setting — pick it in-game or in `knockback.yml`.

| Preset | The feel |
|---|---|
| `signature` | Mental's own tuning — the recommended default for competitive 1.8-style PvP. |
| `lunar` | The archived Lunar Network values — a lighter, floatier trade. |
| `badlion` | The classic Badlion feel — firm horizontal, honest verticals. |
| `velt` | A modern practice-server tuning — snappy and consistent. |

Nine more ship alongside: `kohi`, `minehq`, `mmc`, `legacy-1.7`, `legacy-1.8`, `custom`
(yours to edit) in `profiles/legacy/`, and `modern-vanilla`, `modern-uplift`, `modern-combo`
in `profiles/modern/` for servers on the modern knockback formula. The full guide to what
each knob does lives in [docs/knockback-profiles.md](docs/knockback-profiles.md).

<!-- ASSET: headers/ingame.svg -->

`/mental` (or `/mtl`) opens the management menu — operators only, by default:

- **Dashboard** — every feature family at a glance, toggle anything live.
- **Knockback** — switch the server's preset, inspect the active values.
- **Combat Effects** — hit sounds, particles, damage indicators, death effects, with presets.
- **No restarts** — changes apply immediately; `/mental reload` re-reads the files from console.

Your hand-edited YAML is never rewritten: in-game changes are stored as a separate overlay,
so comments and formatting in the files you maintain stay exactly as you left them.

<!-- ASSET: headers/configuration.svg -->

Everything lives under `plugins/Mental/`, split by topic — each file documents every key it holds:

| File | What it controls |
|---|---|
| `config.yml` | Module toggles, metrics, debug. |
| `knockback.yml` | The selected knockback profile. |
| `profiles/` | The preset library (`legacy/` and `modern/`) — add your own here. |
| `combat.yml` | Hit registration, reach, latency compensation. |
| `combo.yml` | The combo solver family. |
| `rules.yml` | The optional 1.8 ruleset modules. |
| `effects.yml` + `effects/presets/` | Combat effects and their presets. |
| `pots.yml` | Splash-potion utilities. |
| `loot.yml` | Loot protection. |

<!-- ASSET: headers/ruleset.svg -->

Mental always owns hit delivery and knockback. Everything below is **opt-in** — all OFF by
default, each a one-line toggle, grouped by family:

| Family | What turning it on does |
|---|---|
| **Damage** | 1.8 armour strength &amp; durability, old critical hits, old tool durability, sword blocking. |
| **Combat Cadence** | Removes the 1.9 attack cooldown, sweep attacks, and swing sounds. |
| **Sustain** | 1.8 golden apples, potion values &amp; durations, old regen, no ender-pearl cooldown. |
| **Loadout** | Off-hand and crafting restrictions, era hitboxes and reach. |
| **Combo Solver** | Holds the sweet-spot combo distance on the fresh knock; optional reach handicap. |
| **Potions** | `/potfill` and steep-throw instant pots. |
| **Combat Effects** | Hit sounds and particles, pop-off damage indicators, death effects. |
| **Loot Protection** | A slain player's drops reserved for their killer, gold-glowing until they expire. |

> **Running another combat-rules plugin?** Enable each rule in exactly one plugin — the same
> rule enabled twice applies twice.

<!-- ASSET: headers/faq.svg -->

**Does it work with anticheats?**
Yes — combat is server-authoritative, so movement-prediction anticheats verify it cleanly.
When one is detected, Mental automatically stands down its packet-level fast path and stays
correct.

**Do my players need a mod or a specific client?**
No. Everything is server-side; vanilla clients, Lunar, Badlion, and 1.7-animation mods all
just work.

**Can I keep modern 1.9+ combat and only fix knockback?**
Yes — that's the default install. The 1.8 ruleset is entirely opt-in; out of the box Mental
changes knockback, hit registration, and latency fairness only.

**Does it replace OldCombatMechanics?**
It can. The optional ruleset covers the OCM rule set — pick one plugin per rule and Mental
handles the rest natively, including the parts OCM can't touch (delivery and knockback).

**Folia?**
Supported — every release is gated on a real Folia server before it ships.

**Something feels off — how do I debug?**
`/mental debug subscribe` streams what Mental sees (hits, knocks, sprint reads) to your chat
in real time.

<!-- ASSET: headers/compatibility.svg -->

| | |
|---|---|
| **Server** | Paper 1.9.4 → 26.x (every version, 1.14.4 included) · Folia |
| **Java** | 8 or newer — whatever your server already runs, no flags |
| **Dependencies** | None (PlaceholderAPI optional) |
| **Tested** | 15 Paper versions + Folia boot live in the release gate, every release |

One jar covers the whole range: modern JVMs load Mental's Java-17 code, older JVMs load a
byte-equivalent Java-8 build packed in the same file. On 1.9.4–1.10.2 a handful of
trajectory self-tests are skipped (those era servers don't simulate offline players'
motion) — gameplay is unaffected.

<!-- ASSET: headers/developers.svg -->

Modules: `api` (public surface) · `kernel` (pure-JDK combat model) · `platform` (Bukkit seam)
· `core` (the plugin) · `compat-folia` · `tester` (live-server harness).

**Public API** — `me.vexmc:mental-api` (generation 3): combo lifecycle events, an
authoritative combat-state query service, capability discovery, and knockback outcome
control. The jar ships with every release; the contract lives in
[docs/api-gen3-integration-surface.md](docs/api-gen3-integration-surface.md) with rulings in
[docs/api-gen3-rulings.md](docs/api-gen3-rulings.md).

```bash
./gradlew build                  # unit tests + compatibility gates
./gradlew integrationTestMatrix  # every supported server, live
```

Deep dives: [fast path](docs/fast-path.md) · [knockback profiles](docs/knockback-profiles.md)
· [combo hold](docs/combo-hold.md) · [legacy tier](docs/legacy-combat.md) · the combat
research ledger in [docs/research/](docs/research/).

Licensed [MIT](LICENSE) · third-party notices [here](THIRD-PARTY-NOTICES.md) · anonymous
usage metrics via bStats (opt out in `config.yml`).

<!-- ASSET: divider.svg, centered -->

<p align="center"><sub><b>MENTAL</b> · classic combat, engineered ⌖</sub></p>
```

## 5. Voice rules (hold these while adjusting anything)

1. Benefit first, mechanism second — "hits that register", then how.
2. Numbers over adjectives: 13 presets, 24 modules, 16 servers, Java 8+.
3. One idea per sentence; cards are one packed sentence each.
4. No hype words, no exclamation marks; confidence comes from specifics.
5. The glyph (⌖) is the only recurring ornament; no other emoji anywhere.
6. Player/owner language above the fold ("your players", "your server"); developer language only in the developer section.
7. Honesty beats polish: keep the trajectory-self-test caveat and the double-apply warning — rigor on display is the brand.

## 6. Execution checklist for Claude Design

1. Create every asset in §3 under `/assets` (SVGs hand-authored, no external fonts — outline/convert text to paths so rendering is identical everywhere).
2. Assemble `README.md` from §4, wiring the assets (hero `<picture>` dark/light, buttons linked, headers centered, icons in cards).
3. Verify every relative link resolves in the repo (`docs/*.md`, `LICENSE`, `THIRD-PARTY-NOTICES.md`, `../../releases/...`).
4. Preview in both GitHub themes (the hero must read in light AND dark).
5. PR titled `docs(readme): client-focused landing page redesign` — README + assets only; note in the description that `assets/social-preview.png` needs a manual upload in repo settings.

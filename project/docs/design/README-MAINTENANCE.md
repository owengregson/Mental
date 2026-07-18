# README maintenance guide

How to change `README.md` without breaking the system. Read `docs/design/BRAND-GUIDE.md` first.

## Structure (in order)
1. Hero (`<picture>` dark/light) + download/releases buttons
2. Positioning paragraph ("Classic 1.7.10 combat…")
3. Live player chart (`<picture>` → `project/assets/stats/players{,-dark}.svg`, linked to bStats)
4. FEATURES — 2×3 card table
5. GETTING STARTED — 3 steps + config/migration paragraph + no-dependencies blockquote
6. MODERN OR LEGACY — the two knockback calculations
7. KNOCKBACK PRESETS — 4-row preset table + the rest of the library
8. FASTER THAN VANILLA (`<a id="faster-than-vanilla">`) — bulleted; linked from the hitreg card ("How? ⌖")
9. IN-GAME MANAGEMENT · CONFIGURATION · THE 1.8 RULESET · FAQ · COMPATIBILITY · FOR DEVELOPERS
10. Divider + footer (`MENTAL by @owengregson`)

Section headings are SVG images (`height="54"`, centered `<p align="center">`), never `#` text —
except inside FOR DEVELOPERS where `###` is allowed. `<br>` between major blocks.

## Voice rules (hold these on every edit)
1. Benefit first, mechanism second. 2. Player/owner language above the fold; developer language
only in the developer section. 3. One idea per sentence; feature cards are 1–2 short sentences.
4. No hype words, no exclamation marks, no emoji; ⌖ is the only ornament. 5. **No hard-pinned
counts** (preset counts, module counts, matrix sizes) — they rot; say "presets for both", "a
library of switchable modules", "every supported server". Version FACTS are fine (Paper 1.9.4 →
26.x, Java 8+). 6. Honesty beats polish — keep caveats.

## Verified facts (do not contradict; do not invent beyond)
Paper 1.9.4 → 26.x one jar no flags · Folia supported, release-gated · Java 8+ (Multi-Release jar)
· zero dependencies (PlaceholderAPI optional) · knockback profile is server-wide · `/mental`
(alias `/mtl`, op by default), `/mental reload` from console · legacy presets in `profiles/legacy/`
(signature, lunar, badlion, velt, kohi, minehq, mmc, legacy-1.7, legacy-1.8, custom), modern in
`profiles/modern/` (modern-vanilla, modern-uplift, modern-combo) · era modules all default OFF ·
modules: api/kernel/platform/core/compat-folia/tester · public API `me.vexmc:mental-api` (gen 3),
jar attached to every release · MIT · bStats metrics, opt-out in `config.yml` · fast-path claims
per `project/docs/fast-path.md` (netty-thread read, sub-tick, pre-send, 1.19.4+ bundle, 10–50 ms vanilla
dead time, TPS-independent registration). **No fabricated screenshots — all art is vector.**

## Common edits
- **Add a section**: `node project/scripts/generate-brand-assets.mjs header <slug> "TITLE"`, then insert
  `<p align="center"><img src="project/assets/headers/<slug>.svg" height="54" alt="Title"></p>` + content
  + `<br>`. Same pattern for release headers at `height="42"`.
- **Feature card**: `<img icons/x.svg width="40"><br><b>Bold noun-phrase title</b><br>` + 1–2
  sentences. New icon → author per BRAND-GUIDE icon rules.
- **Rename a section**: regenerate its header SVG (same slug), update `alt`.
- **Wordmark/hero/banner/buttons/divider change**: edit `project/scripts/generate-brand-assets.mjs`
  (tokens or geometry), run `all`, re-rasterize the social PNG, and remind the owner to re-upload
  the social preview in repo settings.
- **Links**: relative to repo root; before committing, verify each target exists
  (`docs/*.md`, `LICENSE`, `THIRD-PARTY-NOTICES.md`, `../../releases{,/latest}`, `#faster-than-vanilla`).

## Release notes
Release notes are generated automatically by `.github/workflows/release.yml`, which reads
`.github/RELEASE-TEMPLATE.md` (the styling: banner, ⌖ badges, section header, footer) and
fills three per-release tokens — `{{VERSION}}`, `{{HIGHLIGHTS}}` (from
`.github/release-highlights.md`, whose first line must be `<!-- vX.Y.Z -->` to match the
tag), and `{{CHANGELOG}}` (every commit since the last release).
- **Per-release copy** → edit `.github/release-highlights.md` on the release branch.
- **Format / styling** → edit `.github/RELEASE-TEMPLATE.md`; keep the three tokens and the
  absolute raw.githubusercontent image URLs (release pages don't resolve relative paths).

## Player chart
`project/scripts/render-bstats-chart.mjs` renders `project/assets/stats/players{,-dark}.svg` (players only, last
14 days). `.github/workflows/bstats-chart.yml` re-renders them every 6 hours and force-pushes the
result as a single orphan commit to the `bstats-charts` data branch; the README embeds the charts
from that branch by absolute raw.githubusercontent URL (main is branch-protected, so the refresh
never lands on main — and release changelogs stay free of chart noise). The copies committed on
main are a static snapshot; the data branch is the live source. To restyle the chart, edit the
script's theme objects/renderer — keep it consistent with BRAND-GUIDE (panel, ruler ticks, accent
line) — then run the workflow once (workflow_dispatch) to publish.

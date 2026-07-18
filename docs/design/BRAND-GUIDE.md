# Mental brand guide (README + release assets)

Everything user-facing in this repo is drawn from one visual system. This file is the contract;
`scripts/generate-brand-assets.mjs` is its executable form. **Never hand-tweak a generated SVG —
change the generator (or use its CLI) and regenerate**, so the system stays deterministic.

## Direction
"2013 PvP, engineered." Sharp, technical, flat — no gradients on brand surfaces (charts may fade),
no emoji, no shields.io badges. The hit-marker glyph (four diagonal ticks + center diamond) is the
only recurring ornament.

## Palette
| Token | Dark surfaces | Light surfaces | Notes |
|---|---|---|---|
| accent | `#FF4655` | `#FF4655` | The Mental red. Same on both themes. |
| bg | `#0C0E12` | `#F8F6F2` | Panel fill |
| border | `#2A2F38` | `#DFDAD1` | Panel stroke, 2px |
| ink | `#EDEAE4` | `#1C1E23` | Wordmark fill |
| muted | `#8A919C` | `#6E7580` | Taglines, chart text |
| rule | `#333944` | `#D8D3C9` | Ruler ticks, hairlines |
| static gray | `#828994` | `#828994` | For single-file assets shown on BOTH themes (headers, icons, divider, buttons) |

Theme-aware assets (hero, release banner, stats charts) ship dark+light files swapped with
`<picture><source media="(prefers-color-scheme: dark)">`. Everything else uses accent + static gray only.

## Type
All text in committed SVGs is **drawn as paths** — no font files, no `<text>` (exception: the
machine-regenerated stats charts use `<text>` with a generic monospace stack).
- **Wordmark** (`VG` in the generator): filled Valorant-style letterforms. M is traced from the
  owner's reference (steep slant, w=109, stroke 19.3). Spacing: every glyph is bbox-exact and the
  gap between adjacent bounding edges is a constant `0.16 × cap-height`; A and L carry a fixed
  −10u optical shift (compensates the T's open right side). Don't re-space by eye.
- **Stroke font** (`F` in the generator): single-stroke engineered glyphs, stroke 15u (19u on
  buttons), square caps. Headers: cap 20, tracking 6.2. Release headers: cap 16/5. Buttons:
  cap 13.5/4. Taglines: cap 13/6.

## Surfaces
- Panels: cut corners (opposite TL/BR, 12–16px), 2px border, ruler ticks at 1/12ths top+bottom.
- Hero 860×240, release banner 860×120, buttons h46 (BR corner cut 9), section headers h54,
  release headers h42, divider 260×22, card icons 40×40, social card 1280×640 PNG.
- Icons: hand-authored 40×40, stroke 2.2–2.6, square caps; accent for the subject, static gray
  for ground/secondary strokes. To add one, copy an existing icon file and edit — keep it to two
  colors and one idea.

## Regeneration
```bash
node scripts/generate-brand-assets.mjs all                          # fixed surfaces
node scripts/generate-brand-assets.mjs header faq "FAQ"             # new/changed section header
node scripts/generate-brand-assets.mjs release-header fixes "FIXES"
node scripts/generate-brand-assets.mjs button download "DOWNLOAD LATEST" filled
```
`all` writes `assets/social-preview.src.svg`; rasterize it to `assets/social-preview.png`
(1280×640), e.g. `npx @resvg/resvg-js-cli assets/social-preview.src.svg -o assets/social-preview.png`.
The stats charts are owned by `scripts/render-bstats-chart.mjs` (see workflow) — not this generator.

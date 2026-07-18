<!--
  Mental release-notes template — read and auto-filled by
  .github/workflows/release.yml on every release. The workflow substitutes three
  per-release tokens and ships everything else below VERBATIM:
    {{VERSION}}     the release tag (e.g. v2.9.0-beta)
    {{HIGHLIGHTS}}  the player-facing bullets from .github/release-highlights.md
    {{CHANGELOG}}   every commit since the previous release
  Edit the fixed styling below freely; just keep the three tokens and the absolute
  raw image URLs (relative paths do not resolve on the release page). Voice:
  benefit first, one idea per bullet, no hype words, ⌖ is the only ornament.
-->

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/owengregson/Mental/main/project/assets/release/banner-dark.svg">
    <img src="https://raw.githubusercontent.com/owengregson/Mental/main/project/assets/release/banner.svg" width="860" alt="Mental {{VERSION}}">
  </picture>
</p>

<p align="center">
  <code>⌖ Paper 1.9.4 → 26.x</code>
  <code>⌖ Folia</code>
  <code>⌖ Java 8+</code>
  <code>⌖ zero dependencies</code>
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/owengregson/Mental/main/project/assets/release/headers/changes.svg" height="42" alt="Changes">
</p>

{{HIGHLIGHTS}}

<details>
<summary><b>Full changelog</b> — click to expand</summary>
<br>

{{CHANGELOG}}

</details>

<p align="center">
  <img src="https://raw.githubusercontent.com/owengregson/Mental/main/project/assets/divider.svg" height="22" alt="">
</p>

<p align="center"><sub><b>MENTAL</b> by <a href="https://github.com/owengregson">@owengregson</a></sub></p>

---
name: paper-cross-version
description: Use when writing or changing code that must run across Mental's whole Paper range (1.17.1 → 26.x) — API selection, version-gated features, reflection, mappings, Java toolchains, or anything that behaves differently per version.
---

# Cross-version Paper development (1.17.1 → 26.x)

## The compilation model

- `core` compiles against the **floor API** (`paper-api-floor` = 1.17.1) so the
  common path is binary-safe everywhere. Anything needing a newer API lives in
  a `compat-*` module loaded behind runtime feature detection (`Capabilities`),
  e.g. `compat-folia`, `compat-brigadier`.
- Class-file targets: `options.release = 17`. Servers ≤ 1.20.4 run Java 17;
  1.20.5+ requires 21 (we run 25). The integration build provisions per-version
  toolchains automatically.
- `api-version: '1.17'` in plugin.yml; one universal jar serves the whole range.

## Known binary breaks in the range (absorbed by boot-time resolvers)

- **1.21.3**: `org.bukkit.attribute.Attribute` changed from enum with
  `GENERIC_*` constants to a registry-backed interface with unprefixed
  constants — a break in BOTH directions. `platform/Attributes` resolves
  constants by name once at class load (modern spelling first, then legacy).
  Same pattern in `platform/Enchantments` for the 1.20.5 enchantment renames.
- **1.20.5**: mappings flip — runtimes are **Mojang-mapped from 1.20.5**,
  spigot-mapped before. Reflection must route names through
  `reflection-remapper` (identity on modern, reobf data from the Paper jar
  below). Parse the reobf mappings ONCE per JVM (expensive).
- **1.21.2**: join protection moved (see the three-layout table in
  `live-server-testing`). Treat any "field stopped existing" symptom as a
  candidate mechanic relocation — verify with `nms-archaeology`, never guess.
- **1.21.2**: vanilla projectile knockback against players restored (the
  projectile module's damage-substitution path becomes a no-op there).
- **1.19.4**: `HURT_ANIMATION` + `DAMAGE_EVENT` packets replace entity-status
  2; the Bundle delimiter packet exists from here. Gate packet choices on
  PacketEvents' server version (`ServerVersion.isNewerThanOrEquals`).

## Rules of thumb

- Feature-detect at runtime; never parse version strings for behavior when a
  capability probe works.
- New version-dependent behavior gets decided ONCE at module enable, not per
  packet/event.
- Reflection helpers must try the Mojang name through the remapper FIRST, then
  the raw name as fallback, and degrade gracefully (best-effort) when a field
  genuinely doesn't exist on a version.
- When adding a matrix version: update `integrationTestVersions` in
  gradle.properties, check the Java requirement boundary in
  core/build.gradle.kts, and expect the gradle task to download/cache the
  paperclip jar that `scripts/integration-matrix.sh` then reuses.

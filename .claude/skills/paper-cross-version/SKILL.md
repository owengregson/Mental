---
name: paper-cross-version
description: Use when writing or changing code that must run across Mental's whole Paper range (runtime 1.9.4 → 26.x, 1.17.1 API compile floor) — API selection, version-gated features, reflection, mappings, Java toolchains, the legacy backport tier, or anything that behaves differently per version.
---

# Cross-version Paper development (runtime 1.9.4 → 26.x; 1.17.1 API compile floor)

## The compilation model

- `core` compiles against the **floor API** (`paper-api-floor` = 1.17.1) so the
  common path is binary-safe everywhere. Anything needing a newer API lives in
  a `compat-*` module loaded behind runtime feature detection (`Capabilities`),
  e.g. `compat-folia`, `compat-brigadier`.
- Class-file targets: `options.release = 17`. Servers ≤ 1.20.4 run Java 17;
  1.20.5+ requires 21 (we run 25). The integration build provisions per-version
  toolchains automatically.
- `api-version: '1.17'` in plugin.yml; one universal jar serves the whole range.
- **Runtime floor is Paper 1.9.4** (legacy backport, below the 1.17.1 compile
  floor). Those servers must run on Java 17+ too — Mental ships Java-17
  classfiles. See the legacy tier below.

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
- When adding a matrix version: add the entry to `support-matrix.json` (the
  single source — version, jdk, platform, suites, ci, optional serverFlags);
  the build task, both workflows, the local script, and the release-notes range
  all derive from it. Expect the gradle task to download/cache the paperclip jar
  that `scripts/integration-matrix.sh` then reuses.

## The legacy backport tier (1.9.4–1.16.5) — runtime floor below the compile floor

`core` compiles against the 1.17.1 API but RUNS down to Paper 1.9.4 (Java 17+
required on the server; **1.14.4 is impossible** — its terminal build hard-caps at
Java 13, no bypass, so a Java-17 plugin can never load there). Because core
compiles against 1.17.1, everything COMPILES and any sub-floor absence surfaces at
RUNTIME as `NoSuchMethodError` / `NoSuchFieldError` / `NoClassDefFoundError` —
legacy support is entirely a runtime-resolution problem, decided ONCE at boot.

- **Presence-probing idiom, never version parsing.** Every legacy path is selected
  by probing for the class/method/field itself (MethodHandles lookup,
  `Class.forName`, enum-constant lookup) at boot and caching the resolved handle —
  the same "try modern, fall back, degrade loud" pattern as `Attributes` /
  `Enchantments`. A probe that misses prints ONE loud boot line (no silent
  degradation — mandate B10) and installs the era-intent fallback. The manifest
  boot report is the ground truth of what resolved present/absent per version.
- **`LegacyMaterialNames` is the SINGLE translation seam.** The kernel's material
  vocabulary stays modern and version-blind;
  `platform/LegacyMaterialNames.modernize` is the ONLY place pre-flattening names
  are mapped (`WOOD_*→WOODEN_*`, `GOLD_*→GOLDEN_*`, `*_SPADE→*_SHOVEL`), guarded by
  one boot flattening check (identity on 1.13+). Never scatter name maps — route
  through this seam.
- **The six Phase-5.5 resolvers** (`platform/`): `Absorptions`, `PotionEffects`,
  `CritPosture`, `Cooldowns`, `HandStates`, `Pings` — each is the modern accessor
  VERBATIM on 1.17+ (unit-pinned byte-identical) with an era-intent fallback below,
  the selection printed in the boot report. They exist precisely because core
  compiles against the 1.17.1 floor: a modern accessor on a crit / absorption /
  cooldown / hand-state / ping path that clientless suites never traverse
  `NoSuchMethodError`s at runtime on legacy unless resolved.
- **Versioned-NMS resolution below 1.17.** Pre-1.17 runtimes are spigot-mapped and
  use versioned packages (`net.minecraft.server.<rev>`, `<rev>` =
  `v1_9_R2` … `v1_16_R3`) — `ReflectionRemapper.noop()`, the spigot names ARE the
  runtime names (no reobf parse). The legacy tooltip-strip and the tester
  FakePlayer branch resolve per-revision NMS; shapes are javap-pinned in
  `docs/superpowers/research/2026-07-02-legacy-fakeplayer-nms-shapes.md`.
- **javap-only floor claims (nms-archaeology).** NEVER claim a method/field exists
  on a version from memory — read the actual server jar with javap against the
  cached `run/legacy-probe/<v>/cache/patched_<v>.jar`. The backport corrected three
  prose guesses this way: tooltip path B floors at 1.16.5 (not 1.13.2); `FishHook`
  exists on all 7; `ProjectileHitEvent#getHitEntity` is absent on 1.9.4 only.

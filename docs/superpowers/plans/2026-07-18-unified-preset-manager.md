# Unified Preset Manager — backend unification plan

Date: 2026-07-18 · Branch: `redesign/customization-suite` · Scope: BACKEND (the
`me.vexmc.mental.v5.preset` package, the ConfigStore duplication collapse, the
effects superseded-hash guard). The companion GUI plan owns every screen, the
`MenuMaterials` pane seam + LEGACY_ALIAS fixes (R7), and the release chores
(R9: version bump to 2.9.0-beta, release-highlights marker, README refresh).
This plan creates **no screens** — the "slot grid" requirement of the
specificity bar is N/A here by scope; the GUI plan carries it.

Every claim below was verified against the live sources on 2026-07-18 (file
paths and line numbers cited are from this checkout). All paths are absolute
or repo-relative from `/Users/owengregson/Documents/StrikeSync`.

---

## 0. DO-NOT-TOUCH (echoed from the orchestrator rulings — build-enforced)

The implementation agent must not modify ANY of the following. If a step below
seems to require it, STOP — the plan is wrong, escalate.

1. **Bundled preset FILE CONTENTS and their parse targets**: every file under
   `core/src/main/resources/profiles/legacy/`, `profiles/modern/`,
   `effects/presets/`, and `effects.yml` / `knockback.yml`. Pinned by
   `ProfileParserTest.everyBundledResourceParsesToItsPhase1PresetConstant`,
   `EffectsPresetParserTest.signatureFileParsesToTheExpectedRecords` /
   `customFileParsesValueIdenticalToSignature`, and the kernel `PresetsTest`.
2. **The 29 knockback archived revision texts** under
   `core/src/test/resources/superseded-bundles/` and their hashes in the kernel
   `SupersededPresets.BUNDLE_SHA256_BY_PRESET`.
3. **The byte-identity normalization** (`replace("\r\n","\n").replace('\r','\n')`
   → SHA-256 lowercase hex) in BOTH `SupersededPresets` (kernel, frozen) and
   `SupersededEffectsPresets` (core). Any change silently un-recognizes every
   historical revision.
4. **`parse(empty) == LEGACY_17`** (ProfileParser) and **`parse(empty) ==
   DEFAULTS`** (EffectsPresetParser / every settings record). Zero gameplay
   default and zero parse default changes (R8).
5. **The `api` module** — zero changes (japicmp additive-only; we need none).
6. **`Management` method signatures** used by the tester and facade: `reload()`,
   `setGlobalProfile(String)`, `setEffectsPreset(String)`,
   `setModuleEnabled(Feature,boolean)`, `setOverlay(String,Object)`,
   `clearOverlay(String)` — and their true/false/no-op semantics.
7. **`MentalPluginV5.overlaySet` / `overlayRemove`**, the `Overlay` routing
   switch for existing keys, the selection overlay keys `knockback.profile`
   and `effects.preset`, and the on-disk layout (R1: profiles stay in
   `profiles/legacy|modern/`, effects presets in `effects/presets/`).
8. **The extraction-only-when-missing contract** and **migrations 1..5** exactly
   as they exist (`Migrations.java` — zero edits).
9. **The kernel module** — zero edits anywhere under `kernel/` (R2).
10. **`Snapshot`, `SnapshotParser`, `ProfileParser`, `EffectsPresetParser`,
    `Overlay`, `Migrations`** — this plan rules ZERO edits to all six (see §2).
    Their tests (`SnapshotTest`, `OverlayTest`, `MigrationsTest`,
    `ProfileParserTest`, `EffectsPresetParserTest`) must pass unmodified.

---

## 1. Files to CREATE / MODIFY / DELETE (complete list)

**CREATE (main):**
- `core/src/main/java/me/vexmc/mental/v5/preset/package-info.java`
- `core/src/main/java/me/vexmc/mental/v5/preset/PresetKind.java`
- `core/src/main/java/me/vexmc/mental/v5/preset/PresetInfo.java`
- `core/src/main/java/me/vexmc/mental/v5/preset/PresetCatalog.java`

**CREATE (test):**
- `core/src/test/java/me/vexmc/mental/v5/preset/PresetKindTest.java`
- `core/src/test/java/me/vexmc/mental/v5/preset/PresetCatalogTest.java`
- `core/src/test/java/me/vexmc/mental/v5/config/SupersededEffectsBundleHashTest.java`
- `core/src/test/resources/superseded-effects-bundles/signature@2.5.3.yml`
- `core/src/test/resources/superseded-effects-bundles/signature@2.5.5.yml`
- `core/src/test/resources/superseded-effects-bundles/signature@2.6.2.yml`
- `core/src/test/resources/superseded-effects-bundles/signature@2.7.0.yml`

**MODIFY:**
- `core/src/main/java/me/vexmc/mental/v5/config/ConfigStore.java` — collapse the
  two superseded-upgrade drivers into one shared private driver (§4). No public
  surface change; `ConfigStoreTest` passes unmodified.
- `core/src/main/java/me/vexmc/mental/v5/config/SupersededEffectsPresets.java` —
  add ONE package-private test seam (`archivedHashes`), §5.3. No hash value, no
  normalization, no public-API change.
- `tester/src/main/java/me/vexmc/mental/tester/suite/ProfileSuite.java` —
  lockstep case pinning `PresetCatalog.apply` through the live management seam
  (§6.3). *(RED-TEAM RULING: IN — executed as part of the final
  tester+docs+release task in the implementation partition, alongside the GUI
  plan's BootSuite rewrite, so the tester compiles exactly once per round.)*

**DELETE:** none.

**Explicitly ZERO-EDIT (load-bearing ruling, see §2):** `Snapshot.java`,
`SnapshotParser.java`, `ProfileParser.java`, `EffectsPresetParser.java`,
`EffectsPreset.java`, `Overlay.java`, `Migrations.java`, everything in
`kernel/`, everything in `api/`, every bundled resource.

---

## 2. Design rulings (what unifies where, and why)

**R-A. The unification seam is the READ/APPLY surface, not the parse or storage
surface.** The two systems' parsers (`ProfileParser`, `EffectsPresetParser`)
are frozen parse targets (DO-NOT-TOUCH #1/#4/#10); their outputs
(`KnockbackProfile`, `EffectsPreset`) are typed differently by necessity (one
is a kernel record, one composes three Bukkit-adjacent settings records).
What IS duplicated and unifiable without observable change:
- the GUI's read logic (two hand-rolled pickers with parallel
  names/selected/preview/apply code) → **`PresetCatalog`** (new package);
- the superseded-bundle upgrade driver (two ~30-line byte-identical methods in
  `ConfigStore`) → **one shared private driver** (§4).

**R-B. `Snapshot` keeps its existing accessors and gains nothing.** The catalog
reads through the public accessors that already exist (`profileNames()`,
`defaultProfile()`, `profile(name)`, `hasProfile(name)`,
`effectsPresetNames()`, `selectedEffectsPreset()`, `effectsPreset(name)`,
`hasEffectsPreset(name)` — `Snapshot.java:75-117`). A unified internal
`Map<PresetKind, …>` inside Snapshot was examined and rejected: the typed reads
(`profileFor` → `KnockbackProfile`, `effectsPreset` → `EffectsPreset`) need the
typed maps regardless, so an internal restructure deletes no code, adds a
generic indirection, and risks `SnapshotTest` churn for zero payoff. The
"unified structure" is the catalog's kind switch — one file, fully unit-tested.

**R-C. `SnapshotParser` wiring stays as-is.** The two `parseSection` calls
(`SnapshotParser.java:93-95` and `:126-127`) are two lines each against two
frozen parsers with different result shapes (`Profiles` carries per-world,
`Library` does not). A generic wrapper would rename, not remove, code.

**R-D. The storage-side duplication collapses INSIDE `ConfigStore`, not in the
new package.** Rationale (flag for red-team — the orchestrator sketched
collaborators in `me.vexmc.mental.v5.preset`): `ConfigStore` owns the injected
`Function<String,InputStream> resources` / `Consumer<String> log` seams and the
private IO helpers (`extractIfMissing`, `readResource`, `loadYaml`, `mkdirs`,
`ConfigStore.java:505-559`) that BOTH preset and non-preset files use. Moving
the preset extraction/discovery to another package would either duplicate those
helpers or force cross-package plumbing of the seams — more surface, same
behavior. The genuinely shared logic (the upgrade driver) unifies in place; the
extraction loops and discovery loops stay verbatim because their differences
ARE contracts, each pinned by `ConfigStoreTest`:
- profiles: flat-file preference (`:226-229`), formula-folder derivation
  (`bundledFolder`, `:130-133`), `ensureDeliverySection` BEFORE the upgrade
  check (`:231-232` — archived hashes are the patched text), recursive
  discovery with shallowest-first sort + dup-stem log (`:405-421`);
- effects: the `awaitingEffectsMigration` guard (`:251-263` — migration owns
  `effects.yml`/`custom.yml` creation), custom exempt from upgrade (`:266`),
  flat discovery + signature JAR-resource torn-install fallback (`:472-480`).

**R-E. Dependency direction is one-way: `preset` → `config` (+ kernel).**
`PresetCatalog`/`PresetKind` read `Snapshot`, `ConfigStore`'s public constants,
`EffectsPreset`, and kernel `KnockbackProfile`. Nothing in
`me.vexmc.mental.v5.config` references the preset package. No cycle, no
classload-order trap.

**R-F. Threading:** `PresetCatalog` reads are pure functions over an immutable
`Snapshot` — callable from any thread (the GUI renders on the viewer's region
thread via `Scheduling.runOn`). `PresetCatalog.apply` delegates to `Management`
and therefore inherits its requirement: main thread / Folia global region
(`Scheduling.runGlobal`) — carry this into the javadoc verbatim.

---

## 3. The new package `me.vexmc.mental.v5.preset`

### 3.1 `package-info.java`

Package javadoc (prose, why-focused — carry into code):

> The unified preset manager's read surface. Mental has exactly two preset
> systems — knockback profiles (`profiles/legacy|modern/*.yml`, selected by
> `knockback.profile`) and Combat Effects presets (`effects/presets/*.yml`,
> selected by `effects.preset`) — deliberately built as mirrors but wired
> independently at every layer. This package is the ONE place the two mirrors
> meet: {@link PresetKind} names the two systems, {@link PresetCatalog} serves
> both through a single typed surface, and {@link PresetInfo} carries
> everything the GUI needs so no menu ever touches a `KnockbackProfile` or
> `EffectsPreset` directly. Selection writes still flow through the existing
> `Management` seam — this package adds NO new write path.

### 3.2 `PresetKind.java`

```java
package me.vexmc.mental.v5.preset;

import java.util.List;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.config.ConfigStore;
import me.vexmc.mental.v5.config.EffectsPreset;
import org.jetbrains.annotations.NotNull;

public enum PresetKind {
    KNOCKBACK("Knockback Presets", "BOOKSHELF",
            "Every knockback feel — the era's archived servers, Mental's own,"
            + " and yours — previewed and applied live."),
    EFFECTS("Effects Presets", "JUKEBOX",
            "Whole combat-effects tunes — hits, indicators and deaths"
            + " swapped as one.");
```

*(RED-TEAM RECONCILED: display metadata is now the GUI plan's final copy —
`displayName` is the gallery title suffix (`Mental · Knockback Presets`) and
the gallery header-card name; `iconName` is the gallery hero/header icon
(`BOOKSHELF` resolves verbatim on 1.9.4 → 26.x, GUI plan §7.1); `blurb` is the
FamilyMenu hero-tile copy, wrapped at render time by `Buttons.wrap`. One
source for all three — the GUI renders these fields, never its own literals.)*

Constructor stores three plain strings (`displayName`, `iconName`, `blurb`) —
no cross-class references in the enum-constant arguments, so class
initialization cannot deadlock or order-trap.

Accessors (all `public`, all `@NotNull`):

| Method | Returns | Implementation note |
|---|---|---|
| `String displayName()` | stored field | the gallery title suffix + header-card name (reconciled with the GUI plan — final copy above) |
| `String iconName()` | stored field | resolves through `MenuMaterials.of` on the render path; both names exist verbatim on 1.9.4 → 26.x (no alias needed) |
| `String blurb()` | stored field | the FamilyMenu hero-tile copy (GUI plan §6.2), wrapped by `Buttons.wrap` at render |
| `String overlayKey()` | `switch (this) { case KNOCKBACK -> "knockback.profile"; case EFFECTS -> "effects.preset"; }` | the FROZEN selection keys (DO-NOT-TOUCH #7); a `PresetKindTest` pin guards the literals |
| `String defaultName()` | `switch: KNOCKBACK -> KnockbackProfile.LEGACY_17.name(); EFFECTS -> EffectsPreset.DEFAULT_NAME` | never a duplicated literal — reads the existing single sources of truth ("legacy-1.7" / "signature") |
| `List<String> bundledNames()` | `switch: KNOCKBACK -> ConfigStore.BUNDLED_PROFILES; EFFECTS -> ConfigStore.BUNDLED_EFFECTS_PRESETS` | delegation, not a copy — `ConfigStoreTest` keeps pinning the lists in one place |

WHY comment to carry on `overlayKey()`: "These two literals are the frozen
selection keys the Overlay routes and Management writes
(`Management.setGlobalProfile` / `setEffectsPreset`); the catalog never writes
them itself — apply() delegates so there is exactly one write path per kind."

Class javadoc intent: "The two preset systems by name — the enum the unified
GUI iterates. Carries only presentation metadata and the frozen wiring
constants; all resolution logic lives in {@link PresetCatalog}."

### 3.3 `PresetInfo.java`

```java
package me.vexmc.mental.v5.preset;

import java.util.List;

/**
 * Everything the GUI needs to render one preset tile, for either kind — the
 * typed preview {@code ProfileMenu}/{@code EffectsPresetMenu} used to
 * hand-roll. Serving this from the catalog (instead of the raw
 * {@code KnockbackProfile}/{@code EffectsPreset}) is what lets one picker
 * screen render both kinds without a type switch, and keeps the preview
 * vocabulary in exactly one place.
 */
public record PresetInfo(
        PresetKind kind,
        String name,
        String displayName,
        String description,
        String iconName,
        boolean loaded,
        boolean bundled,
        boolean active,
        boolean modernFormula,
        List<PreviewLine> preview) {

    /** One read-only "label: value" preview row (the picker tile's lore line). */
    public record PreviewLine(String label, String value) {}

    public PresetInfo {
        preview = List.copyOf(preview);
    }
}
```

Field semantics (exact):
- `iconName` — the preset's themed icon material name, from the per-kind
  name→icon maps in §3.4 (they move OUT of the deleted menus INTO the
  catalog); `"PAPER"` for any name not in the map, including every degraded
  info. *(RED-TEAM RECONCILED: the GUI plan's gallery renders
  `info.iconName()` directly — the maps must not stay in GUI code.)*
- `loaded` — the snapshot resolved this name to a parsed preset. `false` never
  happens for names returned by `PresetCatalog.names(...)` in practice, but
  `info(...)` on an arbitrary name degrades instead of returning null: then
  `displayName == name`, `description == ""`, `iconName == "PAPER"`,
  `preview` empty, `modernFormula == false`.
- `bundled` — membership in `kind.bundledNames()` (a user-dropped file is
  selectable but not bundled; the GUI may badge it differently).
- `active` — `name.equals(PresetCatalog.selected(kind, snapshot))`.
- `modernFormula` — KNOCKBACK only: `profile.modern().enabled()` (the
  `MeleeFormula` grouping read). Always `false` for EFFECTS and for unloaded
  names. The GUI plan's formula filter becomes
  `info.modernFormula() == (formula == MeleeFormula.MODERN)`.

### 3.4 `PresetCatalog.java`

Non-instantiable static utility (private constructor) — the codebase's
`ProfileParser`/`SnapshotParser` shape. All state arrives as parameters
(`Snapshot` for reads, `Management` for apply), so there is nothing to hold.

Class javadoc intent: "The ONE read surface the GUI uses for BOTH preset kinds
(spec: the unified preset manager). Reads are pure functions over the immutable
{@link Snapshot} — safe from any thread; {@link #apply} delegates to the
existing {@link Management} write path and inherits its main-thread /
global-region requirement. No new write path exists here."

Signatures + exact behavior:

```java
public static @NotNull List<String> names(@NotNull PresetKind kind, @NotNull Snapshot snapshot)
```
KNOCKBACK → `new ArrayList<>(snapshot.profileNames())`; EFFECTS →
`new ArrayList<>(snapshot.effectsPresetNames())`; then `names.sort(null)`,
return unmodifiable (`List.copyOf`). WHY comment: "Snapshot copies its maps
with Map.copyOf, which drops iteration order — both legacy pickers sorted at
render time; the catalog sorts once so every consumer agrees."

```java
public static @NotNull String selected(@NotNull PresetKind kind, @NotNull Snapshot snapshot)
```
KNOCKBACK → `snapshot.defaultProfile()`; EFFECTS →
`snapshot.selectedEffectsPreset()`.

```java
public static boolean isBundled(@NotNull PresetKind kind, @NotNull String name)
```
`kind.bundledNames().contains(name)` — names are the lowercased stems; no
extra normalization (the snapshot already lowercases stems at parse time,
`ProfileParser.java:181`, and effects stems are file stems verbatim).

```java
public static @NotNull PresetInfo info(@NotNull PresetKind kind, @NotNull String name, @NotNull Snapshot snapshot)
```
KNOCKBACK: `KnockbackProfile profile = snapshot.profile(name)`; if null →
degraded info (§3.3); else build with `displayName = profile.displayName()`,
`description = profile.description()`, `iconName = iconFor(kind, name)`,
`modernFormula = profile.modern().enabled()`, `preview =
profile.modern().enabled() ? modernPreview(profile) : legacyPreview(profile)`.
EFFECTS: `EffectsPreset preset = snapshot.effectsPreset(name)`; if null →
degraded; else `displayName = preset.displayName()`, `description =
preset.description()`, `iconName = iconFor(kind, name)`, `modernFormula =
false`, `preview = effectsPreview(preset)`.

The per-preset icon vocabulary (moved verbatim from `ProfileMenu.PROFILE_ICONS`
(13 entries, `ProfileMenu.java:35-48`) and `EffectsPresetMenu.PRESET_ICONS`
(2 entries) — both maps verified against source 2026-07-18):

```java
private static final Map<String, String> KNOCKBACK_ICONS = Map.ofEntries(
        Map.entry("legacy-1.7", "STONE_SWORD"), Map.entry("legacy-1.8", "IRON_SWORD"),
        Map.entry("kohi", "DIAMOND_SWORD"), Map.entry("minehq", "GOLDEN_SWORD"),
        Map.entry("badlion", "IRON_AXE"), Map.entry("velt", "DIAMOND_AXE"),
        Map.entry("mmc", "BOW"), Map.entry("lunar", "ENDER_EYE"),
        Map.entry("signature", "NETHER_STAR"),
        Map.entry("modern-vanilla", "NETHERITE_SWORD"),
        Map.entry("modern-uplift", "NETHERITE_AXE"),
        Map.entry("modern-combo", "TRIDENT"), Map.entry("custom", "WRITABLE_BOOK"));

private static final Map<String, String> EFFECTS_ICONS = Map.of(
        "signature", "NETHER_STAR", "custom", "WRITABLE_BOOK");

/** The themed icon for a preset name — "PAPER" for anything unmapped. */
private static String iconFor(PresetKind kind, String name) {
    Map<String, String> icons = kind == PresetKind.KNOCKBACK ? KNOCKBACK_ICONS : EFFECTS_ICONS;
    return icons.getOrDefault(name, "PAPER");
}
```

```java
public static @NotNull List<PresetInfo> infos(@NotNull PresetKind kind, @NotNull Snapshot snapshot)
```
`names(kind, snapshot)` mapped through `info(...)` in order — the picker's
one-call render read.

```java
public static boolean apply(@NotNull PresetKind kind, @NotNull String name, @NotNull Management management)
```
`switch (kind) { case KNOCKBACK -> management.setGlobalProfile(name);
case EFFECTS -> management.setEffectsPreset(name); }` — nothing else. The
true / false / no-op contract, the overlay write, the reload, and the
`KnockbackProfileChangeEvent` (KNOCKBACK only, once per actual transition) are
all Management's, untouched. WHY comment: "Pure delegation — Management is the
single write seam the tester (ProfileSuite/FeedbackSuite) and the public API
both pin; the catalog must never grow a second path to the overlay."

Private preview builders — **the strings are copied character-for-character
from the current menus** (`ProfileMenu.java:130-153`,
`EffectsPresetMenu.java:107-129`), including the `·` and `–` glyphs; the GUI
plan then deletes its hand-rolled copies and renders `PreviewLine`s:

```java
private static List<PresetInfo.PreviewLine> legacyPreview(KnockbackProfile profile)
```
Lines, in order (label → value):
1. `"Base h/v"` → `round(base.horizontal()) + " / " + round(base.vertical())`
2. `"Vertical"` → `verticalMode().name().toLowerCase(Locale.ROOT)`
3. `"Delivery"` → `meleeDelivery().name().toLowerCase(Locale.ROOT).replace('_','-')`
4. `"Combos"` → `combos() ? "yes" : "no"`
5. `"Sprint x"` → `round(sprintFactor())`
6. `"Resistance"` → `resistance().name().toLowerCase(Locale.ROOT)`
7. only when `paceScaling().active()`: `"Pace scale"` → `"attacker x" +
   round(pace.min()) + "–" + round(pace.max()) + " ^" + round(pace.exponent())`

```java
private static List<PresetInfo.PreviewLine> modernPreview(KnockbackProfile profile)
```
1. `"Base"` → `round(modern.baseStrength())`
2. `"Sprint +"` → `round(modern.sprintBonus())`
3. `"Enchant +"` → `round(modern.enchantBonus())`
4. `"Downward"` → `modern.downwardKnockback() ? "yes (mid-air slam)" : "no (uplift)"`
5. `"Combos"` → `combos() ? "yes" : "no"`
6. `"Delivery"` → `meleeDelivery().name().toLowerCase(Locale.ROOT).replace('_','-')`

```java
private static List<PresetInfo.PreviewLine> effectsPreview(EffectsPreset preset)
```
1. `"Hit sounds"` → `hit.vanillaTune() ? "vanilla hurt (era jitter)" : hit.sounds().size() + " layered"`
2. `"Hit particles"` → `hit.particles().isEmpty() ? "none" : String.valueOf(hit.particles().size())`
3. `"Low-HP layer"` → `hit.lowHealthSounds().isEmpty() ? "none" : "below " + round(hit.lowHealthThresholdPercent()) + "% of max health"`
4. `"Indicator"` → `preset.damageIndicators().text()`
5. `"Death"` → when `!death.lightning() && death.sounds().isEmpty() &&
   death.particles().isEmpty() && death.fireworkColors().isEmpty()`:
   `"nothing (vanilla)"`; else `(death.lightning() ? "lightning" : "no bolt")
   + " · " + death.sounds().size() + " sounds" +
   (death.fireworkColors().isEmpty() ? "" : " · " +
   death.fireworkColors().size() + "-color blast")`

```java
private static String round(double value) { return String.valueOf(Math.round(value * 1000.0) / 1000.0); }
```
(moved verbatim from the menus — both had identical private copies).

Imports: `java.util.ArrayList`, `java.util.List`, `java.util.Locale`,
`java.util.Map`,
`me.vexmc.mental.kernel.profile.KnockbackProfile`,
`me.vexmc.mental.v5.config.EffectsPreset`,
`me.vexmc.mental.v5.config.Snapshot`,
`me.vexmc.mental.v5.config.settings.DeathEffectsSettings`,
`me.vexmc.mental.v5.config.settings.HitFeedbackSettings`,
`me.vexmc.mental.v5.manage.Management`, `org.jetbrains.annotations.NotNull`.
(Never inline-qualify — codebase rule.)

---

## 4. ConfigStore: one superseded-bundle upgrade driver

`upgradeSupersededPreset` (`ConfigStore.java:322-350`) and
`upgradeSupersededEffectsPreset` (`:279-307`) are byte-for-byte the same
algorithm — read file, predicate on archived bytes, overwrite from the jar's
current bundle, log — differing only in the predicate, the resource path, and
the log label. Collapse them:

**Delete both methods. Add one:**

```java
/**
 * Replaces a preset file whose RAW BYTES still match a superseded shipped
 * revision — the owner never touched it, so only research corrections separate
 * it from the current bundle. Matching on bytes (not parsed values, as before
 * 2.4.9) is what makes owner edits sacred and parser drift irrelevant. One
 * driver serves both preset kinds: the archive predicate is the only thing
 * that differs (kernel SupersededPresets for knockback, core
 * SupersededEffectsPresets for effects). For knockback this runs AFTER
 * {@link #ensureDeliverySection}, so a pre-1.4.0 file has already had its
 * {@code delivery} block inserted and the archived hashes for those forms are
 * the patched text (see SupersededPresets).
 */
private void upgradeIfSupersededBundle(
        String label, Path file, String resource,
        BiPredicate<String, String> archive, String preset) {
    if (!Files.isRegularFile(file)) {
        return;
    }
    String onDisk;
    try {
        onDisk = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException failure) {
        log.accept("Could not read " + label + ": " + failure);
        return;
    }
    if (!archive.test(preset, onDisk)) {
        return;
    }
    String current = readResource(resource);
    if (current == null) {
        log.accept("Bundled resource " + resource + " is missing from the jar");
        return;
    }
    try {
        Files.writeString(file, current, StandardCharsets.UTF_8);
        log.accept(label + " is a superseded bundled revision,"
                + " byte-identical and unedited — upgraded to the corrected bundle"
                + " (delete the file to regenerate anytime)");
    } catch (IOException failure) {
        log.accept("Could not upgrade " + label + ": " + failure);
    }
}
```

**Call sites (replace the two existing calls):**
- Profiles loop (`ensureDefaultFiles`, currently `:232`):
  ```java
  upgradeIfSupersededBundle(
          PROFILES_DIR + "/" + preset + ".yml", file,
          PROFILES_DIR + "/" + bundledFolder(preset) + "/" + preset + ".yml",
          SupersededPresets::isSupersededBundleText, preset);
  ```
- Effects loop (`ensureEffectsPresetLibrary`, currently `:267`):
  ```java
  upgradeIfSupersededBundle(
          EFFECTS_PRESETS_DIR + "/" + preset + ".yml", file,
          EFFECTS_PRESETS_DIR + "/" + preset + ".yml",
          SupersededEffectsPresets::isSupersededBundleText, preset);
  ```
- Add `import java.util.function.BiPredicate;`.

**Log-line identity proof (verify during review — every emitted string must be
byte-identical to today's):**
- read failure, knockback: old `"Could not read profiles/" + preset + ".yml: "`
  == new `"Could not read " + label + ": "` with `label = "profiles/<p>.yml"`. ✔
- read failure, effects: old `"Could not read " + EFFECTS_PRESETS_DIR + "/" +
  preset + ".yml: "` == new with `label = "effects/presets/<p>.yml"`. ✔
- upgraded line, knockback: old starts `"profiles/" + preset + ".yml is a
  superseded…"` == new `label + " is a superseded…"`. ✔ (Note: the label is the
  FLAT spelling `profiles/<p>.yml` even for a foldered file — today's exact
  behavior, preserved on purpose.)
- upgraded line, effects: old used `resource` (== label here). ✔
- missing-resource + write-failure lines: identical by the same substitution. ✔

**Kept verbatim, with the ordering comment retained:** the profiles loop still
calls `ensureDeliverySection(preset, file)` on the line BEFORE the upgrade call
(patch-then-hash — DO-NOT-TOUCH #2/#3 depend on it), and the effects loop still
skips custom entirely. `ensureDeliverySection`, `loadSources`,
`loadEffectsPresets`, and every public constant/method are untouched.

---

## 5. Closing the verified gap: the effects superseded-hash guard

### 5.1 Provenance (verified 2026-07-18 — all four constants are CORRECT;
independently re-verified by the red-team on 2026-07-18: all five hashes below
— the four archived plus the current `b543…` — reproduced exactly from
`git show` at the named tags, and `v2.6.1-beta` re-confirmed byte-identical to
`v2.5.5-beta`)

The four hashes in `SupersededEffectsPresets.ARCHIVED_HASHES`
(`SupersededEffectsPresets.java:44-55`) were recomputed today from git history
of `core/src/main/resources/effects/presets/signature.yml` and every one
reproduces exactly. **No constant correction is needed.** The lineage, the
shipped tags, and the recomputed SHA-256 (newline-normalized, UTF-8, lowercase
hex):

| Archive resource | Lineage (per class javadoc) | Extract from tag | Verified SHA-256 |
|---|---|---|---|
| `signature@2.5.3.yml` | 2.5.3/2.5.4 — pre percent/heal-text rewrite (introduced by commit `27d91ad`; no v2.5.3 tag exists — the line shipped as `v2.5.4-beta`, byte-identical) | `v2.5.4-beta` | `6781856643e401f3e1ff9f7901a8138f998367ccb369641d11399df6889f3af1` |
| `signature@2.5.5.yml` | 2.5.5 → 2.6.1 — pre window/HP-units rewrite (verified byte-identical at `v2.5.5-beta` and `v2.6.1-beta`) | `v2.5.5-beta` | `40a57e598f9d0d397d6314e76ba351bd9ff163ae90cf23b30c7e9ebaf1cdd7ad` |
| `signature@2.6.2.yml` | 2.6.2 — pre kill-title | `v2.6.2` | `f0cf052e062349b8dc8f03cade450480cde0f9ae065c106a7082218df5a05858` |
| `signature@2.7.0.yml` | 2.7.0 — pre crit-threshold-percent | `v2.7.0-beta` | `808396ddabc6d91047ceda223cbe04c8a9a31a092fa63442f3327957cce973db` |

The CURRENT bundle (`v2.7.1-beta` → head) hashes
`b54323229c3a1d80b00555170e4737172939458ee40af41a582f0c639a527efa` — collides
with none of the four (no self-upgrade loop).

**Extraction commands (run each exactly; `git show` emits the blob verbatim —
no editor, no reformat, no trailing-newline additions):**

```bash
cd /Users/owengregson/Documents/StrikeSync
mkdir -p core/src/test/resources/superseded-effects-bundles
git show v2.5.4-beta:core/src/main/resources/effects/presets/signature.yml \
  > 'core/src/test/resources/superseded-effects-bundles/signature@2.5.3.yml'
git show v2.5.5-beta:core/src/main/resources/effects/presets/signature.yml \
  > 'core/src/test/resources/superseded-effects-bundles/signature@2.5.5.yml'
git show v2.6.2:core/src/main/resources/effects/presets/signature.yml \
  > 'core/src/test/resources/superseded-effects-bundles/signature@2.6.2.yml'
git show v2.7.0-beta:core/src/main/resources/effects/presets/signature.yml \
  > 'core/src/test/resources/superseded-effects-bundles/signature@2.7.0.yml'
# Honesty check — every line must print the table's hash:
for f in core/src/test/resources/superseded-effects-bundles/*.yml; do
  perl -pe 's/\r\n/\n/g; s/\r/\n/g' "$f" | shasum -a 256 | sed "s|-|$f|"
done
```

**Contingency (already ruled out, but for completeness):** had a hash NOT
reproduced from history, the plan's instruction would have been to recompute
from the historical file at its tag and CORRECT the constant with a loud plan
note + prose commit body explaining the discrepancy. Not needed — all four
reproduce.

### 5.2 `SupersededEffectsBundleHashTest.java`

Package `me.vexmc.mental.v5.config` (beside its subject, mirroring
`SupersededBundleHashTest`). Class javadoc: "Pins the byte-identity archive
behind the pristine Combat Effects preset upgrade — the effects twin of
{@code SupersededBundleHashTest}. The four historical signature.yml revisions
are stored verbatim under {@code superseded-effects-bundles/} (extracted from
the shipped tags); the hashes in {@code SupersededEffectsPresets} were
hand-computed when each round shipped and were UNGUARDED until this test:
recognition, no-collision, custom-exemption, and set-completeness are asserted
so a regenerated bundle — or a mistyped hash — can never drift silently."

Test methods (exact names + assertions):

1. `everyArchivedRevisionIsRecognisedInLfAndCrlf()` — for each of the four
   resources (a `LinkedHashMap<String,String>` resource→preset, all mapping to
   `"signature"`): read from the test classpath
   (`superseded-effects-bundles/<name>`), assert
   `SupersededEffectsPresets.isSupersededBundleText("signature", text)` is
   true, then assert the `text.replace("\n","\r\n")` CRLF variant is also true
   (the normalization pin).
2. `noArchivedRevisionCollidesWithACurrentBundle()` — for each current bundled
   effects preset (`"signature"`, `"custom"` — iterate
   `ConfigStore.BUNDLED_EFFECTS_PRESETS`): read
   `effects/presets/<preset>.yml` from the classpath (core main resources are
   on the test classpath — the same mechanism `SupersededBundleHashTest.read`
   uses for `profiles/…`), assert `isSupersededBundleText(preset, current)` is
   false. WHY comment: "if any archived hash equalled a current bundle, that
   bundle would upgrade to itself forever."
3. `customIsExemptWhateverItsBytes()` — for each of the four ARCHIVED texts,
   assert `isSupersededBundleText("custom", text)` is false (the archive is
   keyed only by signature — `custom` is the owner's preset by definition,
   even if its bytes matched an old signature).
4. `garbageUnknownAndRetiredPresetsAreRejected()` — assert false for:
   `("signature", "display-name: X\n")`, `("signature", null)`,
   `("vanilla", <the signature@2.5.3 text>)` (vanilla left the bundle in
   2.5.5 — its retirement is the 4→5 migration's job, never the upgrade
   driver's), and `("nope", "anything\n")`.
5. `everyArchivedHashHasItsTextInTheArchive()` — recompute the SHA-256 of each
   archived resource with a test-local copy of the normalization + digest
   (`replace("\r\n","\n").replace('\r','\n')`, SHA-256, lowercase hex), collect
   into a `Set<String>`, and assert it **equals**
   `SupersededEffectsPresets.archivedHashes("signature")` (the §5.3 seam).
   This is STRONGER than the knockback twin: a hash added to the class without
   an archived text fails here, not just a text without a hash. Also assert
   `archivedHashes("custom")` and `archivedHashes("vanilla")` are empty.

Helper: private static `read(String classpath)` — copy the exact shape of
`SupersededBundleHashTest.read` (`SupersededBundleHashTest.java:72-80`):
classloader stream, `assertNotNull` with the "resource missing" message,
UTF-8, `UncheckedIOException` wrap.

### 5.3 `SupersededEffectsPresets.java` — the one-method test seam

Add below `isSupersededBundleText` (no other change to the file):

```java
/**
 * The archived hash set for {@code preset} (empty when none) — package-private
 * test seam for the completeness guard: the hash-archive test asserts these
 * EXACTLY equal the recomputed hashes of the texts under
 * {@code superseded-effects-bundles/}, so a constant can never exist without
 * its verbatim historical text (nor the reverse).
 */
static Set<String> archivedHashes(String preset) {
    return ARCHIVED_HASHES.getOrDefault(preset, Set.of());
}
```

The hash VALUES, the map keys, the normalization, and `isSupersededBundleText`
are untouched (DO-NOT-TOUCH #3).

---

## 6. Unit tests for the new package (complete enumeration)

### 6.1 `PresetKindTest.java` (`core/src/test/java/me/vexmc/mental/v5/preset/`)

1. `overlayKeysAreTheFrozenSelectionKeys()` — asserts
   `PresetKind.KNOCKBACK.overlayKey().equals("knockback.profile")` and
   `PresetKind.EFFECTS.overlayKey().equals("effects.preset")` as string
   literals (the routing/Management contract pin — if someone "refactors" the
   key, this fails before the overlay silently mis-routes).
2. `defaultNamesAreTheShippedDefaults()` —
   `KNOCKBACK.defaultName().equals(KnockbackProfile.LEGACY_17.name())` AND
   `.equals("legacy-1.7")`; `EFFECTS.defaultName().equals("signature")`.
3. `bundledNamesMirrorTheConfigStoreBundleLists()` —
   `KNOCKBACK.bundledNames() == ConfigStore.BUNDLED_PROFILES` (same instance or
   equals) and `EFFECTS.bundledNames() == ConfigStore.BUNDLED_EFFECTS_PRESETS`.
4. `displayMetadataIsRenderable()` — for every kind: `displayName()`,
   `iconName()`, `blurb()` all non-blank (the `DashboardModelTest`
   renderable-copy idiom).

### 6.2 `PresetCatalogTest.java`

Snapshot construction helper (the test's spine — `Snapshot.Builder` is
package-private to `config`, so build through the public parser exactly as
production does):

```java
private static Snapshot snapshot() {
    Map<String, Configuration> profiles = Map.of(
            "signature", resource("profiles/legacy/signature.yml"),
            "modern-vanilla", resource("profiles/modern/modern-vanilla.yml"));
    Map<String, Configuration> effectsPresets = Map.of(
            "signature", resource("effects/presets/signature.yml"),
            "custom", resource("effects/presets/custom.yml"),
            "bare", new YamlConfiguration());
    ConfigStore.Sources sources = new ConfigStore.Sources(
            new MemoryConfiguration(), new MemoryConfiguration(),
            new MemoryConfiguration(), new MemoryConfiguration(),
            new MemoryConfiguration(), new MemoryConfiguration(),
            new MemoryConfiguration(), new MemoryConfiguration(),
            new MemoryConfiguration(), effectsPresets, profiles);
    return SnapshotParser.parse(sources).snapshot();
}
```
`resource(...)` loads a bundled YAML off the test classpath into a
`YamlConfiguration` (mirror `ProfileParserTest`'s helper). The resulting
snapshot: profiles = {legacy-1.7 (force-inserted by the parser), signature,
modern-vanilla}, defaultProfile = legacy-1.7; effects = {bare, custom,
signature}, selected = signature. `"bare"` parses to every DEFAULTS record —
the stable, kernel/DEFAULTS-pinned preview fixture (deliberately NOT the
signature tune, so this test never duplicates the `EffectsPresetParserTest`
drift pins).

Methods:

1. `namesAreTheSnapshotNamesSortedPerKind()` — `names(KNOCKBACK, s)` equals
   `List.of("legacy-1.7", "modern-vanilla", "signature")`; `names(EFFECTS, s)`
   equals `List.of("bare", "custom", "signature")` (parity with the Snapshot
   accessors + the sort pin — Map.copyOf drops order, the catalog restores it).
2. `selectedDelegatesToTheSnapshotSelectionPerKind()` —
   `selected(KNOCKBACK, s).equals(s.defaultProfile())` and equals
   `"legacy-1.7"`; `selected(EFFECTS, s).equals(s.selectedEffectsPreset())`
   and equals `"signature"`.
3. `bundledNamesComeFromTheConfigStoreLists()` — every entry of
   `ConfigStore.BUNDLED_PROFILES` is `isBundled(KNOCKBACK, …)` true; every
   entry of `BUNDLED_EFFECTS_PRESETS` is `isBundled(EFFECTS, …)` true;
   `isBundled(KNOCKBACK, "nope")` false; cross-kind:
   `isBundled(EFFECTS, "kohi")` false and `isBundled(KNOCKBACK, "bare")` false.
4. `aLegacyProfileInfoCarriesTheLegacyPreviewLines()` — `info(KNOCKBACK,
   "legacy-1.7", s)`: `loaded` true, `bundled` true, `active` true,
   `modernFormula` false, `iconName.equals("STONE_SWORD")`, `displayName`
   equals `s.profile("legacy-1.7").displayName()`, and `preview` equals
   EXACTLY (in order, hand-computed from `KnockbackProfile.LEGACY_17`):
   `("Base h/v", "0.4 / 0.4")`, `("Vertical", "add")`,
   `("Delivery", "tracker")`, `("Combos", "yes")`, `("Sprint x", "1.0")`,
   `("Resistance", "none")` — and NO `"Pace scale"` line.
5. `theSignatureProfileInfoCarriesThePaceLine()` — `info(KNOCKBACK,
   "signature", s).preview()` contains exactly one line with label
   `"Pace scale"` and value `"attacker x0.5–2.0 ^0.95"` (the only pace opt-in;
   values pinned by the kernel `PresetsTest`).
6. `aModernProfileInfoCarriesTheModernPreviewLines()` — `info(KNOCKBACK,
   "modern-vanilla", s)`: `modernFormula` true,
   `iconName.equals("NETHERITE_SWORD")`, and `preview` equals EXACTLY:
   `("Base", "0.4")`, `("Sprint +", "0.5")`, `("Enchant +", "0.5")`,
   `("Downward", "yes (mid-air slam)")`, `("Combos", "no")`,
   `("Delivery", "immediate")`.
7. `anEffectsPresetInfoCarriesTheTuneLines()` — `info(EFFECTS, "bare", s)`
   (the DEFAULTS-valued preset): `iconName.equals("PAPER")` (unmapped name),
   plus `info(EFFECTS, "signature", s).iconName().equals("NETHER_STAR")`;
   `preview` of "bare" equals EXACTLY:
   `("Hit sounds", "vanilla hurt (era jitter)")`, `("Hit particles", "none")`,
   `("Low-HP layer", "none")`, `("Indicator", "&f-{HEALTH} &c❤&r")`
   (== `DamageIndicatorsSettings.DEFAULTS.text()` — assert against the
   constant, not a re-typed literal), `("Death", "nothing (vanilla)")`.
8. `anUnloadedNameDegradesToANameOnlyInfo()` — `info(KNOCKBACK, "ghost", s)`:
   `loaded` false, `displayName.equals("ghost")`, `description.isEmpty()`,
   `iconName.equals("PAPER")`, `preview.isEmpty()`, `active` false, `bundled`
   false, `modernFormula` false. Same shape asserted for
   `info(EFFECTS, "ghost", s)`.
9. `activeFlagsExactlyTheSelectedPreset()` — in `infos(kind, s)` for both
   kinds: exactly one entry has `active()` true and its `name()` equals
   `selected(kind, s)`.
10. `infosCoverEveryNameInOrder()` — for both kinds:
    `infos(kind, s).stream().map(PresetInfo::name).toList()` equals
    `names(kind, s)`.
11. `previewListsAreImmutable()` — `assertThrows(
    UnsupportedOperationException.class, () -> info(...).preview().add(...))`
    (the record's `List.copyOf` pin — GUI code can never mutate a shared info).

**`apply()` has NO unit test — deliberate.** `Management` is final and
plugin-bound (`Management(MentalPluginV5)`); the repo mocks nothing. The method
is a two-arm switch of pure delegation whose semantics
(true / false-on-unknown / no-op-on-active, event once per transition) are
already live-pinned by `ProfileSuite.runGlobalApiScenario` through the very
seam it calls. Compile-time exhaustiveness (`switch` over the enum with no
default) guards the routing. §6.3 adds the live lockstep (ruled in).

### 6.3 Tester lockstep (RED-TEAM RULING: IN — ships with the final
tester+docs+release task; R4 permits)

In `tester/src/main/java/me/vexmc/mental/tester/suite/ProfileSuite.java`,
extend `runGlobalApiScenario` (currently lines 350-386) with four asserts run
through the SAME global-tick context, importing
`me.vexmc.mental.v5.preset.PresetCatalog` and
`me.vexmc.mental.v5.preset.PresetKind`:
- `PresetCatalog.apply(PresetKind.KNOCKBACK, "minemen-exact", mental.management())`
  returns false (unknown rejected — identical to the existing
  `setGlobalProfile` expectation);
- `PresetCatalog.apply(PresetKind.KNOCKBACK, <the currently active profile>,
  mental.management())` returns true (no-op success);
- `PresetCatalog.selected(PresetKind.KNOCKBACK, mental.snapshot())` equals
  `mental.snapshot().defaultProfile()`;
- `PresetCatalog.apply(PresetKind.EFFECTS, "signature", mental.management())`
  returns true (signature is the default selection — no-op success).
This pins catalog-through-management live on every full Paper entry without a
new suite. If the red-team drops it, the GUI plan's menus (which call
`apply`) plus `ProfileSuite`'s existing pins still cover the seam.

---

## 7. Behavior-parity checklist (old dual system → after this plan)

Every observable behavior, where it lives now, and where it lives after. "SAME
CODE" means the lines are untouched.

| # | Behavior | Today | After |
|---|---|---|---|
| P1 | `parse(empty) == LEGACY_17` | `ProfileParser.parse` per-key fallbacks | SAME CODE (zero edits) |
| P2 | `parse(empty) == DEFAULTS` (effects) | `EffectsPresetParser` section parsers | SAME CODE |
| P3 | Unknown `knockback.profile` → one warn + legacy-1.7 | `ProfileParser.parseSection:194-199` | SAME CODE |
| P4 | Unknown `effects.preset` → one loud line + signature stands in; retired `vanilla` name rides the same path | `EffectsPresetParser.parseSection:67-71` | SAME CODE |
| P5 | `legacy-1.7` always resolvable (putIfAbsent) | `ProfileParser.parseSection:190` | SAME CODE |
| P6 | Signature torn-install fallback (JAR resource, loud line; other stems directory-driven) | `ConfigStore.loadEffectsPresets:472-480` | SAME CODE |
| P7 | Last-ditch in-code `EffectsPreset.FALLBACK` | `Library.effective():41-43` | SAME CODE |
| P8 | Dup-stem profile → second file logged & ignored; shallowest-first, flat outranks foldered | `ConfigStore.loadSources:405-421` | SAME CODE |
| P9 | Effects migration guard: config-version < 4 + legacy state suppresses `effects.yml`/`custom.yml` extraction | `ensureEffectsPresetLibrary:251-263` | SAME CODE |
| P10 | Extraction order: main files → splits (guarded) → effects library → profiles loop; per-profile flat-file preference, `ensureDeliverySection` BEFORE upgrade | `ensureDefaultFiles:190-234` | SAME CODE except the upgrade call is the shared driver at the same position; ordering comment retained |
| P11 | Pristine-upgrade log lines (all five message shapes, both kinds) | two private drivers | ONE driver, byte-identical strings (§4 proof) |
| P12 | `custom` never upgraded (effects); custom delivery-patch exempt (knockback) | `:260-267` / `:365` | SAME CODE |
| P13 | Selection write-back semantics (false / true-no-op / write+reload; KB event once per transition) | `Management:49-84` | SAME CODE; `PresetCatalog.apply` delegates |
| P14 | Overlay routing of `knockback.profile` / `effects.preset` (+ per-field `effects.*`) | `Overlay.route:52-79` | SAME CODE |
| P15 | Snapshot read surface (all 8 profile/effects accessors + `profileFor` LEGACY_17 hard fallback) | `Snapshot.java:68-117` | SAME CODE; catalog reads through it |
| P16 | GUI preview vocabulary (the exact kv lines) | hand-rolled twice in `ProfileMenu`/`EffectsPresetMenu` | `PresetCatalog` preview builders, strings copied verbatim, unit-pinned (§6.2 #4-7); menus migrate in the GUI plan |
| P17 | Reload pipeline (ensureDefaultFiles → new Overlay from disk → parse → converge), migrations boot-only, extract-migrate-extract dance | `MentalPluginV5:232-253,512-519` | SAME CODE |
| P18 | Retired-section loudness (`reportRetiredEffectsSections`, overlay scrubs) | `SnapshotParser:272-282`, `MentalPluginV5:546-556` | SAME CODE |
| P19 | Effects hash upgrade chain recognizes the 4 shipped revisions | unguarded hand-computed constants | SAME constants, now guarded by §5's archive + 5 tests |

---

## 8. Caller audit (every caller of touched surfaces; verified by grep 2026-07-18)

**Surfaces touched by this plan:** ConfigStore's two private upgrade methods
(callers: only `ensureDefaultFiles` / `ensureEffectsPresetLibrary`, both
internal — updated in §4) and `SupersededEffectsPresets` (caller: only
`ConfigStore.upgradeSupersededEffectsPreset` → becomes the shared driver's
predicate reference). **No public method changes anywhere**, therefore every
caller below requires ZERO changes from this plan:

- `Management.java:51,54,75,78` — `hasProfile`/`defaultProfile`/
  `hasEffectsPreset`/`selectedEffectsPreset`: unchanged.
- `MentalFacade.java:44,49,59` — `profileFor`/`defaultProfile`/`profileNames`
  (japicmp surface behind it): unchanged.
- Feature units — `KnockbackUnit:719`, `FishingKnockbackUnit:217`,
  `ProjectileKnockbackUnit:298,356`, `SessionService:717`,
  `HitRegistrationUnit:351-353`, `Deliveries:36`: all `profileFor`/view
  reads, unchanged.
- GUI — `ProfileMenu:79-96,166-168`, `EffectsPresetMenu:65-80,141-143`,
  `KnockbackFormulaMenu:60-79`, `DashboardMenu:134`: unchanged by THIS plan;
  the GUI plan migrates them to `PresetCatalog.infos/selected/apply` and
  deletes the duplicated preview/round helpers (P16).
- Tester — `ProfileSuite:374,877`, `KnockbackSuite:587`, `FishingSuite:89`,
  `FoliaCombatSmoke:129`, `ProjectileSuite:65`, `ComboSuite:186,287,487`
  (snapshot reads); `FeedbackSuite:692-697`, `FeedbackCoherenceSuite:988-992`
  (`overlaySet("effects.preset", …)` + reload — the raw overlay path, still
  valid): all unchanged. Optional §6.3 addition is additive.
- Tests — `ConfigStoreTest`, `SnapshotTest`, `OverlayTest`, `MigrationsTest`,
  `ProfileParserTest`, `EffectsPresetParserTest`, `SupersededBundleHashTest`,
  kernel `PresetsTest`/`SupersededPresetsTest`: all pass unmodified (nothing
  they pin moves).

---

## 9. Ordered implementation steps (each with its acceptance check)

Work on `redesign/customization-suite`. Conventional commits with prose bodies;
commit after each step.

**Step 1 — the preset package.** Create `package-info.java`, `PresetKind.java`,
`PresetInfo.java`, `PresetCatalog.java` exactly per §3 (signatures, javadoc
intents, WHY comments, verbatim preview strings).
*Acceptance:* `./gradlew :core:compileJava` succeeds.
*Commit:* `feat(preset): unified preset catalog — one read surface for both preset kinds`.

**Step 2 — unit tests for the package.** Create `PresetKindTest`,
`PresetCatalogTest` per §6.1/§6.2 (every method name and assertion as listed;
hand-computed expectations, never recomputed through the code under test).
*Acceptance:* `./gradlew :core:test --tests "me.vexmc.mental.v5.preset.*"`
passes; then `./gradlew :core:test` passes whole (no collateral damage).
*Commit:* `test(preset): catalog resolution, preview pins, and kind metadata`.

**Step 3 — ConfigStore driver collapse.** Apply §4: delete the two methods, add
`upgradeIfSupersededBundle`, rewrite the two call sites, add the
`BiPredicate` import, keep the ordering comment on the profiles loop.
*Acceptance:* `./gradlew :core:test --tests "*ConfigStoreTest"
--tests "*SupersededBundleHashTest" --tests "*MigrationsTest"` passes, then
`./gradlew build` (unit tests + japicmp + kernel-Bukkit-free) passes.
*Commit:* `refactor(config): one superseded-bundle upgrade driver for both preset libraries`.

**Step 4 — the effects hash archive + guard.** Run the §5.1 extraction commands
EXACTLY; verify the four printed hashes against the §5.1 table (if any
mismatches, STOP and escalate — do not "fix" by editing the archive). Add the
`archivedHashes` seam (§5.3). Create `SupersededEffectsBundleHashTest` (§5.2).
*Acceptance:* the for-loop hash check prints the four table hashes;
`./gradlew :core:test --tests "*SupersededEffectsBundleHashTest"` passes;
`./gradlew build` passes.
*Commit:* `test(config): archive the four shipped signature.yml revisions behind the effects upgrade hashes`.

**Step 5 — tester lockstep (RULED IN, but DEFERRED to the implementation
partition's final task).** Apply §6.3 together with the GUI plan's BootSuite
rewrite — the tester is edited exactly once per round, in the last task.
*Acceptance:* `./gradlew :tester:compileJava` succeeds (the live assertion runs
with the matrix).
*Commit:* folded into the final task's
`test(tester): headless render lockstep for the redesigned suite`.

**Step 6 — gate.** `./gradlew build` first, always. The full
`./gradlew integrationTestMatrix` runs once this plan and the GUI plan are
merged on the shared branch (the GUI plan changes tester compile pins; running
the matrix between the two plans would burn a machine-day on an intermediate
state). Honesty rule: trust only the fresh-nonce `test-results.txt` PASS, never
the BUILD SUCCESSFUL banner (see `matrix-gate`).

---

## 10. Red-team resolutions (all former open questions RULED — design is final)

1. **Collaborator location (R-D): CONFIRMED as designed.** The shared upgrade
   driver stays inside `ConfigStore` — the IO seams (`resources`/`log`
   injection, `extractIfMissing`, `readResource`) and `ConfigStoreTest`'s pins
   live there; a `PresetLibraries` extraction would duplicate helpers for zero
   behavioral gain. The R-D deviation from the orchestrator sketch is accepted.
2. **Static `PresetCatalog`: CONFIRMED.** The GUI plan declared itself
   agnostic; static wins (matches `ProfileParser`/`SnapshotParser` idiom, and
   keeps the BootSuite `MenuContext` ctor compile-pin untouched). The GUI
   plan's §5 has been rewritten to these exact signatures.
3. **`apply()` unit-test gap: CONFIRMED deliberate; §6.3 tester lockstep is
   IN**, executed in the implementation partition's final task (tester edited
   once per round).
4. **Preview copy ownership: CONFIRMED.** `PresetCatalog` + `PresetCatalogTest`
   are the single place; the GUI plan renders `PreviewLine`s verbatim and its
   §5 now says so. `PresetInfo` gained exactly ONE field beyond the draft —
   `iconName` (§3.3/§3.4) — because the GUI's gallery needs the per-preset
   themed icons and the maps must not survive in GUI code. `modernFormula`
   (boolean) is confirmed sufficient for the formula tabs: the gallery filters
   on `info.modernFormula() == (tab == MeleeFormula.MODERN)`; `MeleeFormula`
   itself stays a GUI-package type (no preset→gui dependency).
5. **`PresetKind` copy: RECONCILED** — §3.2 now carries the GUI plan's final
   copy (displayName "Knockback Presets"/"Effects Presets", icons
   BOOKSHELF/JUKEBOX, blurbs = the hero-tile lines). The backend test still
   pins only non-blankness, so future re-wording stays a one-file GUI+catalog
   concern.
6. **R7/R9 assignment: CONFIRMED** — the GUI plan owns the `MenuMaterials`
   pane seam + LEGACY_ALIAS fixes (its §2.1) and the release chores (its §10).
   Verified present there; nothing falls between the plans.

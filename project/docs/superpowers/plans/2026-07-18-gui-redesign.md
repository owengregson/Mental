# Mental GUI Redesign — the Customization Suite surface (2026-07-18)

Design plan for the fully redesigned management GUI: one design system, one
family-screen metaphor, a unified preset gallery over `PresetCatalog`, and
descriptor-driven settings screens for every overlay-safe knob. Executed on
branch `redesign/customization-suite`. Companion plan: the preset-backend plan
(owns `me.vexmc.mental.v5.preset`); this plan CONSUMES that seam (§5).

Version target: **2.9.0-beta**.

---

## 0. DO-NOT-TOUCH (echoed from the orchestrator rulings — build-enforced)

- **On-disk layout is FROZEN** (R1): `profiles/legacy|modern/`, `effects/presets/`,
  selection overlay keys `knockback.profile` and `effects.preset`. No new
  config-version migration step. No disk reshuffle.
- **Kernel module: UNTOUCHED** (R2).
- Bundled preset FILE CONTENTS and their parse targets: `ProfileParserTest`,
  `EffectsPresetParserTest`, `PresetsTest` pins — existing assertions are never
  edited (new test METHODS may be added, §9).
- The 29 superseded-bundle archive texts + hashes (`SupersededBundleHashTest`);
  the byte-identity normalization.
- `parse(empty) == LEGACY_17` and `parse(empty) == DEFAULTS` for every settings
  record. This plan's one parser change (§7.4) is additive and preserves both.
- The `api` module (japicmp additive-only). **This plan makes ZERO api changes.**
- `Management` method signatures used by the tester: `reload()`,
  `setGlobalProfile(String)`, `setEffectsPreset(String)`,
  `setModuleEnabled(Feature, boolean)`, `setOverlay(String, Object)`,
  `clearOverlay(String)` — all kept verbatim; the GUI only CALLS them.
- `MentalPluginV5.overlaySet` / `overlayRemove`; `Overlay` routing behavior for
  existing keys (every new key in this plan routes through EXISTING
  first-segment cases — verified per key in §4.3).
- The extraction-only-when-missing contract; migrations 1..5 as they exist.
- Tester CONTRACTS (may recompile against new class names, contracts survive,
  R4): headless render self-test (`selfTestInventory()` / `selfTestIcons()`
  pure-Bukkit signatures) on EVERY version incl. 1.9.4; holder identity
  (`getHolder() instanceof Menu` and `== the menu`); non-AIR icons; `/mental`
  opens the dashboard for a permitted player; ProfileSuite semantics
  (`setGlobalProfile` true/false/no-op, bundled names selectable,
  `KnockbackProfileChangeEvent` once per transition).
- Names kept per R4: `Menu`, `MenuContext`, `ChatPrompt`, `DashboardMenu`,
  `MenuManager`, `MeleeFormula`, `DashboardModel`.
- Zero-touch and era-exact no-op defaults (R8): NO gameplay default changes, NO
  parse default changes.
- Threading model unchanged (R10): inventory work via `Scheduling.runOn`,
  config writes via `Scheduling.runGlobal`. No new external dependencies.

---

## 1. Executive intent

Today's GUI is 12 bespoke screens with two layout systems, five duplicated
`kv()` helpers, an icon vocabulary that reuses LEVER/CLOCK across unrelated
concepts, a preset picker split across three navigation tiers (one of which
writes nothing), and a settings surface that exposes roughly a tenth of the
overlay-safe knobs. The redesign replaces the twelve bespoke screens with
**six screen classes** — three of them fully data-driven — under one design
system:

| Class | Role |
| --- | --- |
| `DashboardMenu` | home — status plate + family tiles off `DashboardModel` (kept, restyled) |
| `FamilyMenu` | ONE metaphor for all ten families: module cards (left-click toggles, right-click configures) |
| `SettingsMenu` | ONE generic knob screen rendering a `SettingsCatalog` page (14 pages) |
| `PresetGalleryMenu` | ONE gallery for BOTH `PresetKind`s via `PresetCatalog`; legacy/modern as filter tabs |
| `CompatibilityMenu` | anticheat posture as three radio tiles (kept, redesigned) |
| `DebugMenu` | channels + chat stream (kept, restyled, hand-synced slot array removed) |

Deleted outright: `KnockbackFormulaMenu`, `ProfileMenu`, `EffectsPresetMenu`,
`EffectsMenu`, `HitEffectsMenu`, `DeathEffectsMenu`, `DamageIndicatorsMenu`,
`LootProtectionMenu` (8 classes → absorbed by the generic four).

---

## 2. The design system

### 2.1 `PaneColor` + the pane seam (platform) — R7

**CREATE `/Users/owengregson/Documents/StrikeSync/platform/src/main/java/me/vexmc/mental/platform/PaneColor.java`**

```java
package me.vexmc.mental.platform;

/**
 * The sixteen stained-glass-pane colours as a version-blind vocabulary. Modern
 * servers (1.13+) carry one Material per colour; pre-flattening servers carry a
 * single STAINED_GLASS_PANE whose colour is the item's data value. Each
 * constant owns both spellings so the resolver (MenuMaterials.pane) can pick
 * the era-correct construction — the same one-seam philosophy as
 * LegacyMaterialNames, applied to menu chrome.
 */
public enum PaneColor {
    WHITE("WHITE_STAINED_GLASS_PANE", (short) 0),
    ORANGE("ORANGE_STAINED_GLASS_PANE", (short) 1),
    MAGENTA("MAGENTA_STAINED_GLASS_PANE", (short) 2),
    LIGHT_BLUE("LIGHT_BLUE_STAINED_GLASS_PANE", (short) 3),
    YELLOW("YELLOW_STAINED_GLASS_PANE", (short) 4),
    LIME("LIME_STAINED_GLASS_PANE", (short) 5),
    PINK("PINK_STAINED_GLASS_PANE", (short) 6),
    GRAY("GRAY_STAINED_GLASS_PANE", (short) 7),
    LIGHT_GRAY("LIGHT_GRAY_STAINED_GLASS_PANE", (short) 8),
    CYAN("CYAN_STAINED_GLASS_PANE", (short) 9),
    PURPLE("PURPLE_STAINED_GLASS_PANE", (short) 10),
    BLUE("BLUE_STAINED_GLASS_PANE", (short) 11),
    BROWN("BROWN_STAINED_GLASS_PANE", (short) 12),
    GREEN("GREEN_STAINED_GLASS_PANE", (short) 13),
    RED("RED_STAINED_GLASS_PANE", (short) 14),
    BLACK("BLACK_STAINED_GLASS_PANE", (short) 15);

    private final String modernName;
    private final short legacyData;

    PaneColor(String modernName, short legacyData) { ... }

    /** The post-flattening per-colour material name (1.13+). */
    public String modernName();
    /** The pre-1.13 STAINED_GLASS_PANE data value for this colour. */
    public short legacyData();
}
```

**MODIFY `/Users/owengregson/Documents/StrikeSync/platform/src/main/java/me/vexmc/mental/platform/MenuMaterials.java`** — add:

```java
/**
 * A stained-glass background pane of the given colour, era-correct: the
 * per-colour material on 1.13+, or STAINED_GLASS_PANE with the colour's data
 * value below (the deprecated data ctor is the ONLY way to colour a pane
 * there; it is verified present on the 1.17.1 compile floor and the legacy
 * branch can never execute on a modern server because the per-colour name
 * resolves first — data is never passed on the modern path). Returns a fresh
 * stack each call so callers may attach meta without aliasing.
 */
@SuppressWarnings("deprecation")
public static @NotNull ItemStack pane(@NotNull PaneColor color) {
    Material modern = Material.getMaterial(color.modernName());
    if (modern != null) {
        return new ItemStack(modern);            // modern path: no data value, ever
    }
    Material legacy = Material.getMaterial("STAINED_GLASS_PANE");
    if (legacy != null) {
        return new ItemStack(legacy, 1, color.legacyData());  // pre-1.13 colour-by-data
    }
    return new ItemStack(FALLBACK);              // unreachable in the range; boot test screams
}
```

(`org.bukkit.inventory.ItemStack` joins the import block. javap-verified on the
floor jar: `public org.bukkit.inventory.ItemStack(org.bukkit.Material, int, short)`
exists on paper-api 1.17.1.)

**MODIFY `LEGACY_ALIAS` in the same file** — the six mandated fixes plus four
era-degrade aliases for icons the GUI keeps requesting (the table's own javadoc
invariant — "enumerated from exactly the names the GUI requests" — is restored
by this change):

| Modern name (add) | Legacy spelling | Why |
| --- | --- | --- |
| `COMPARATOR` | `REDSTONE_COMPARATOR` | DELIVERY family icon |
| `CRAFTING_TABLE` | `WORKBENCH` | Disable Crafting icon |
| `ENCHANTED_GOLDEN_APPLE` | `GOLDEN_APPLE` | Old Golden Apples icon (plain apple below 1.13 — data-1 glint accepted loss) |
| `FIREWORK_ROCKET` | `FIREWORK` | Death Effects icon |
| `GLISTERING_MELON_SLICE` | `SPECKLED_MELON` | heal-text knob icon |
| `PLAYER_HEAD` | `SKULL_ITEM` | pickup-rule info icon |
| `NETHERITE_SWORD` | `DIAMOND_SWORD` | CADENCE family + Attack Cooldown + Modern tab (era-degrade, not STONE) |
| `NETHERITE_AXE` | `DIAMOND_AXE` | modern-uplift preset icon |
| `TRIDENT` | `GOLD_SWORD` | modern-combo preset icon (shares the legacy glyph with `GOLDEN_SWORD`/minehq below 1.13 — accepted: the tile name disambiguates) |
| `TARGET` | `SLIME_BALL` | Old Hitboxes icon |

`Map.ofEntries` grows from 12 to 22 entries.

### 2.2 `Palette` — the per-family colour identity (core)

**CREATE `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/Palette.java`**

```java
package me.vexmc.mental.v5.gui;

/**
 * The colour identity of every screen: each Family owns a signature pane
 * colour (its chrome accent) and a NamedTextColor accent used in its titles,
 * header cards, and value text. One place, exhaustively mapped — PaletteTest
 * pins that every Family resolves, so a new family can never ship colourless.
 */
final class Palette {

    record Theme(PaneColor pane, NamedTextColor accent) {}

    /** The family's signature colours. */
    static @NotNull Theme of(@NotNull Family family);
    /** The home screen's brand theme (gold). */
    static @NotNull Theme home();
    /** Compatibility + Debug — the neutral system theme. */
    static @NotNull Theme system();
    /** Gallery chrome follows its kind: KNOCKBACK reds, EFFECTS purples. */
    static @NotNull Theme gallery(@NotNull PresetKind kind);
}
```

The mapping (exhaustive `switch`, no default so a new Family is a compile
error):

| Family | PaneColor | NamedTextColor accent | Rationale |
| --- | --- | --- | --- |
| DELIVERY | LIGHT_BLUE | AQUA | the wire — cold, fast |
| KNOCKBACK | RED | RED | the punch |
| DAMAGE | ORANGE | DARK_RED | grit and armour |
| CADENCE | YELLOW | YELLOW | rhythm |
| SUSTAIN | LIME | GREEN | regen, apples |
| LOADOUT | CYAN | DARK_AQUA | inventory discipline |
| COMBO | BLUE | BLUE | the solver |
| POTS | MAGENTA | LIGHT_PURPLE | splash glass |
| FEEDBACK | PURPLE | DARK_PURPLE | the jukebox |
| LOOT | BROWN | GOLD | the killer's gold glow |
| — home | ORANGE | GOLD | the brand |
| — system | LIGHT_GRAY | WHITE | neutral operators' tools |

All ten pane colours are unique; all ten accents are unique.
`Palette.gallery(KNOCKBACK)` returns `of(Family.KNOCKBACK)`;
`Palette.gallery(EFFECTS)` returns `of(Family.FEEDBACK)`.

### 2.3 `PanePattern` + `Chrome` — the background art

**CREATE `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/PanePattern.java`**
— pure slot math, no Bukkit types, fully unit-tested:

```java
/**
 * The house background pattern — "the Mental frame". Computed, never
 * hand-placed: accent corners, a light-grey fade beside each corner along
 * every edge, an accent mid-bar on the side columns of tall screens, grey
 * everywhere else. Content tiles are drawn OVER the pattern, so the chrome
 * always reads as a deliberate frame and never as noise.
 */
final class PanePattern {

    /** The full rows*9 grid for the standard frame. Every cell non-null. */
    static @NotNull PaneColor[] frame(int rows, @NotNull PaneColor accent);
}
```

`frame` paint rules, in order (later wins), for `rows` in 3..6:

1. Every slot → `GRAY`.
2. Top/bottom edge fades: `(row 0, cols 1 and 7)` and `(row rows-1, cols 1 and 7)` → `LIGHT_GRAY`.
3. Side fades: `(row 1, cols 0 and 8)` and `(row rows-2, cols 0 and 8)` → `LIGHT_GRAY`.
4. Side accent bar, only when `rows >= 5`: odd `rows` → row `(rows-1)/2`, cols 0 and 8 → accent; `rows == 6` → rows 2 AND 3, cols 0 and 8 → accent.
5. Corners: `(0,0) (0,8) (rows-1,0) (rows-1,8)` → accent.

Reference grids (`A` = accent, `l` = LIGHT_GRAY, `g` = GRAY):

```
rows=3          rows=4          rows=5          rows=6
A l g g g g g l A   A l g g g g g l A   A l g g g g g l A   A l g g g g g l A
l g g g g g g g l   l g g g g g g g l   l g g g g g g g l   l g g g g g g g l
A l g g g g g l A   l g g g g g g g l   A g g g g g g g A   A g g g g g g g A
                    A l g g g g g l A   l g g g g g g g l   A g g g g g g g A
                                        A l g g g g g l A   l g g g g g g g l
                                                            A l g g g g g l A
```

**CREATE `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/Chrome.java`**
— the Bukkit-facing half:

```java
/**
 * Builds the decorative pane stacks the pattern paints with. Panes carry an
 * empty display name (no tooltip flicker) through the TextPort String sink,
 * exactly like the old filler; the stacks are cached per colour and never
 * mutated after build (Bukkit copies on setItem).
 */
final class Chrome {

    /** A finished, empty-named background pane of the given colour. */
    static @NotNull ItemStack pane(@NotNull PaneColor color);
}
```

Implementation note: `EnumMap<PaneColor, ItemStack>` cache;
`MenuMaterials.pane(color)` → `getItemMeta()` → `TextPort.displayName(meta,
Component.empty())` → `setItemMeta` → cache.

**MODIFY `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/Menu.java`**:

- Add `protected final void paintChrome(@NotNull PaneColor accent)` — iterates
  `PanePattern.frame(rows(), accent)` and `set(slot, Chrome.pane(color))` for
  every slot. Every screen calls it FIRST in `draw()`; content `set(...)`
  calls then overwrite their slots.
- `fillEmpty()` becomes a pure safety net: filler = `Chrome.pane(PaneColor.GRAY)`
  (visually identical to today's filler). It still runs after `draw()`.
- `placeCentered(int rowBase, List<Tile> tiles)` re-expressed as a thin
  delegate to `Layout.centeredRow` (§2.5) — behaviour byte-identical.
- Everything else (`open`, `refresh`, `apply`, `navigate`, `promptOverlay`,
  `handleClick`, `selfTestInventory`, holder identity) UNCHANGED.

### 2.4 `Buttons` v2 — the one click grammar

**MODIFY `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/Buttons.java`**.
`wrap(String)` is kept byte-identical (ButtonsTest pin). The old
`toggle/nav/stepper/cycle/editText/back()` factories are REPLACED by the
vocabulary below (all callers are rewritten in this plan; no other callers
exist — verified in the framework map). New/changed package-private statics:

```java
/** A muted-label / accent-value lore line — THE shared kv, killing 5 copies. */
static @NotNull Component kv(@NotNull String label, @NotNull String value, @NotNull TextColor accent);

/** 3-decimal rounding for previews — THE shared round, killing 2 copies. */
static @NotNull String round(double value);

/** A navigation tile: wrapped grey blurb + "▸ Click to open". */
static @NotNull ItemStack nav(@NotNull String materialName, @NotNull String title,
        @NotNull TextColor accent, @NotNull String blurb);

/**
 * A module card — the family screens' one tile per feature. Left-click
 * toggles; when settingsHint is non-null a second action line advertises the
 * right-click (e.g. "▸ Right-click to configure"). Glows when enabled.
 */
static @NotNull ItemStack moduleCard(@NotNull String materialName, @NotNull String title,
        @NotNull TextColor accent, boolean enabled, @NotNull String blurb,
        @Nullable String settingsHint);

/** A boolean knob tile. overridden renders the ⚑ line + Q-reset hint. */
static @NotNull ItemStack toggle(@NotNull String materialName, @NotNull String title,
        @NotNull TextColor accent, boolean enabled, @NotNull String blurb, boolean overridden);

/**
 * A numeric stepper tile. Grammar: "▸ Left +<step> · Right −<step>" and
 * "▸ Shift for ×10". value already carries its unit suffix.
 */
static @NotNull ItemStack stepper(@NotNull String materialName, @NotNull String title,
        @NotNull TextColor accent, @NotNull String value, @NotNull String blurb,
        @NotNull String step, boolean overridden);

/**
 * A cycle tile rendering EVERY option as a radio line (● selected / ○ other)
 * so the whole option space is visible before clicking. Left cycles forward,
 * right cycles back.
 */
static @NotNull ItemStack cycle(@NotNull String materialName, @NotNull String title,
        @NotNull TextColor accent, @NotNull List<String> options, @NotNull String selected,
        @NotNull String blurb, boolean overridden);

/** A chat-edited text knob (raw & codes shown, "(empty)" when blank). */
static @NotNull ItemStack editText(@NotNull String materialName, @NotNull String title,
        @NotNull TextColor accent, @NotNull String value, @NotNull String blurb, boolean overridden);

/** A chat-entered NUMBER knob ("▸ Click to type a value in chat"). */
static @NotNull ItemStack numberPrompt(@NotNull String materialName, @NotNull String title,
        @NotNull TextColor accent, @NotNull String value, @NotNull String blurb, boolean overridden);

/**
 * The first-class read-only tile: "ℹ " prefix, NON-bold muted name, muted
 * body, no glow, no action line — visually unmistakable as not-clickable.
 */
static @NotNull ItemStack info(@NotNull String materialName, @NotNull String title,
        @NotNull List<String> body);

/**
 * The list-knob pointer tile: an info look plus one exact editing direction —
 * "✎ Edit in <file> → <section>" in SECONDARY — naming file and section.
 */
static @NotNull ItemStack pointer(@NotNull String materialName, @NotNull String title,
        @NotNull String blurb, @NotNull String file, @NotNull String section);

/** Back names its actual destination: "Back" / lore "Return to <destination>." */
static @NotNull ItemStack back(@NotNull String destination);

/** A bold-titled icon starter (unchanged signatures, kept). */
static @NotNull Icon title(@NotNull String materialName, @NotNull String title);
static @NotNull Icon title(@NotNull String materialName, @NotNull String title, @NotNull TextColor color);
```

Exact rendered lore, template by template (every literal is normative):

- **moduleCard**: name = title, bold, `Brand.TEXT` (WHITE) when disabled /
  `accent` when enabled. Lore: wrapped blurb in `Brand.MUTED`; blank;
  `● ENABLED` bold `Brand.SUCCESS` or `○ DISABLED` bold `Brand.FAILURE`;
  `▸ Click to enable` / `▸ Click to disable` in `Brand.SECONDARY`; when
  `settingsHint != null`, the hint verbatim in `Brand.SECONDARY`.
  `.glow(enabled)`.
- **toggle**: name = title bold `Brand.TEXT`. Lore: wrapped blurb MUTED; blank;
  `● ON` bold SUCCESS / `○ OFF` bold FAILURE; `▸ Click to turn on|off`
  SECONDARY; override block (below). `.glow(enabled)`.
- **stepper**: name = title bold TEXT. Lore: wrapped blurb MUTED; blank;
  `kv("Value", value, accent)`; `▸ Left +<step> · Right −<step>` SECONDARY;
  `▸ Shift for ×10` SECONDARY; override block.
- **cycle**: name = title bold TEXT. Lore: wrapped blurb MUTED; blank; one line
  per option — selected: `● <option>` bold SUCCESS; others: `○ <option>`
  MUTED; blank; `▸ Click to cycle · Right-click back` SECONDARY; override
  block.
- **editText**: name = title bold TEXT. Lore: wrapped blurb MUTED; blank;
  value wrapped in `accent` (raw `&` codes shown) or `(empty)` MUTED;
  `▸ Click to edit in chat` SECONDARY; override block.
- **numberPrompt**: as editText but value line = `kv("Value", value, accent)`
  and action line `▸ Click to type a value in chat` SECONDARY; override block.
- **override block** (rendered only when `overridden`):
  `⚑ Overridden in-GUI` in `NamedTextColor.DARK_GRAY`, then
  `▸ Press Q to reset to the file value` in `NamedTextColor.DARK_GRAY`.
- **info**: name = `ℹ ` + title, NOT bold, `Brand.MUTED`. Lore: each body line
  MUTED (caller pre-wraps via `Buttons.wrap`). No blank-glow-action anywhere.
- **pointer**: name = `ℹ ` + title, NOT bold, MUTED. Lore: wrapped blurb MUTED;
  blank; `✎ Edit in <file> → <section>` in `Brand.SECONDARY`.
- **back**: ARROW, name `Back` bold `Brand.SECONDARY`, lore
  `Return to <destination>.` MUTED. Destinations used: `the Dashboard`,
  `Hit Delivery`, `Knockback`, `Damage`, `Combat Cadence`, `Sustain`,
  `Loadout`, `Combo Solver`, `Potions`, `Combat Effects`, `Loot Protection`
  (i.e. the family displayName, or "the Dashboard").

**The one click grammar** (implemented in the screens, advertised by the lore):

| Gesture | Meaning |
| --- | --- |
| Left-click | primary: toggle / apply / open / +step / cycle forward / start chat edit |
| Right-click | −step (steppers) · cycle backward (cycles) · open settings (module cards) |
| Shift + Left/Right | ×10 step (steppers only) |
| Q (drop key, `ClickType.DROP` or `CONTROL_DROP`) | reset this knob to its file/preset value (`Management.clearOverlay`) — surfaces the previously GUI-dead reset capability |

### 2.5 `Layout` — the one placement vocabulary

**CREATE `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/Layout.java`**
— pure math, replaces `placeCentered` internals AND `FamilyMenu.spreadColumns`:

```java
final class Layout {

    /** Contiguous centred slots across the full 9-wide row (the home rows). */
    static int[] centeredRow(int rowBase, int count);

    /**
     * Content slots WITHIN the chrome frame (cols 1..7): gapped every-other
     * column while 2*count-1 <= 7 (count <= 4), contiguous centred for
     * count 5..7; count > 7 is a caller bug (throws IllegalArgumentException —
     * pages must paginate or group instead).
     */
    static int[] contentRow(int rowBase, int count);

    /** The gallery grid: rows 1-3, cols 1..7 → {10..16, 19..25, 28..34}. */
    static int[] galleryGrid();

    /** Pure pagination window over a list. page is clamped into range. */
    record Page(int pageCount, int page, int fromIndex, int toIndex,
                boolean hasPrev, boolean hasNext) {}
    static @NotNull Page page(int itemCount, int pageSize, int requestedPage);
}
```

Exact math: `centeredRow` = old `placeCentered` (`start = rowBase +
max(0,(9-count)/2)`, overflow dropped at col 8). `contentRow`: gapped → first
`= rowBase + 1 + (7 - (2*count - 1)) / 2`, stride 2; contiguous → first
`= rowBase + 1 + (7 - count) / 2`, stride 1. `page`: `pageCount =
max(1, ceil(itemCount/pageSize))`, `page = clamp(requestedPage, 0,
pageCount-1)`, `fromIndex = page*pageSize`, `toIndex = min(itemCount,
fromIndex+pageSize)` (exclusive), `hasPrev = page > 0`, `hasNext =
page < pageCount-1`.

Derived placements used throughout §6 (memorize once):

| count | contentRow cols | slots at rowBase 9 | at 18 | at 27 |
| --- | --- | --- | --- | --- |
| 1 | 4 | 13 | 22 | 31 |
| 2 | 3,5 | 12,14 | 21,23 | 30,32 |
| 3 | 2,4,6 | 11,13,15 | 20,22,24 | 29,31,33 |
| 4 | 1,3,5,7 | 10,12,14,16 | 19,21,23,25 | 28,30,32,34 |
| 5 | 2..6 | 11..15 | 20..24 | 29..33 |
| 7 | 1..7 | 10..16 | 19..25 | 28..34 |

### 2.6 Title language

Every screen title is built the same way (serialized to `§` strings by
TextPort as today):

```
Component.text("Mental", Brand.PRIMARY, TextDecoration.BOLD)
    .append(Component.text(" · ", Brand.MUTED))     // omitted on home
    .append(Component.text(sectionName, theme.accent()))
```

- Home: `Mental` (bold gold, no suffix — the brand mark).
- Family screens: `Mental · <Family.displayName()>`.
- Settings screens: `Mental · <Feature.displayName()>`.
- Galleries: `Mental · Knockback Presets` / `Mental · Effects Presets`.
- `Mental · Compatibility`, `Mental · Debug`.

### 2.7 The override flag (additive read seam)

- **MODIFY `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/config/Overlay.java`**:
  add `public boolean has(@NotNull String key) { return overrides.containsKey(key); }`
  (javadoc: the GUI's "is this knob overridden?" probe; read-only, additive).
- **MODIFY `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/MentalPluginV5.java`**:
  add `public boolean overlayHas(@NotNull String key) { return overlay.has(key); }`
  next to `overlaySet`/`overlayRemove` (which are untouched).

This fulfils the v5 spec's "the GUI shows effective values and marks
overridden keys" promise and powers the ⚑/Q-reset affordance.

---

## 3. Information architecture — screen catalog + navigation graph

### 3.1 The catalog

| # | Screen (class, args) | Rows | Theme |
| --- | --- | --- | --- |
| 1 | `DashboardMenu(ctx)` | 6 (54, tester-pinned) | home (ORANGE/GOLD) |
| 2 | `FamilyMenu(ctx, family)` ×10 | 4 for KNOCKBACK & FEEDBACK, else 3 | `Palette.of(family)` |
| 3 | `SettingsMenu(ctx, feature)` ×14 | 2 + group count (3–5) | `Palette.of(feature.family())` |
| 4 | `PresetGalleryMenu(ctx, kind)` ×2 | 6 (54, tester-pinned) | `Palette.gallery(kind)` |
| 5 | `CompatibilityMenu(ctx)` | 3 | system |
| 6 | `DebugMenu(ctx)` | 5 | system |

### 3.2 The navigation graph (every edge, every Back)

```
/mental (player, mental.command.use)
  └─ DashboardMenu ──────────────────────────────── Close → closeInventory
       ├─ FamilyMenu(DELIVERY)      ─ Back → Dashboard
       │    ├─ SettingsMenu(HIT_REGISTRATION)   ─ Back → FamilyMenu(DELIVERY)
       │    └─ (WTAP card: toggle only)
       ├─ FamilyMenu(KNOCKBACK)     ─ Back → Dashboard
       │    ├─ PresetGalleryMenu(KNOCKBACK) [hero + KNOCKBACK card right-click]
       │    │                                    ─ Back → FamilyMenu(KNOCKBACK)
       │    ├─ SettingsMenu(LATENCY_COMPENSATION) ─ Back → FamilyMenu(KNOCKBACK)
       │    ├─ SettingsMenu(FISHING_KNOCKBACK)    ─ Back → FamilyMenu(KNOCKBACK)
       │    └─ SettingsMenu(PROJECTILE_KNOCKBACK) ─ Back → FamilyMenu(KNOCKBACK)
       ├─ FamilyMenu(DAMAGE)        ─ Back → Dashboard      (5 toggle-only cards)
       ├─ FamilyMenu(CADENCE)       ─ Back → Dashboard      (3 toggle-only cards)
       ├─ FamilyMenu(SUSTAIN)       ─ Back → Dashboard      (5 toggle-only cards)
       ├─ FamilyMenu(LOADOUT)       ─ Back → Dashboard
       │    ├─ SettingsMenu(CRAFTING)             ─ Back → FamilyMenu(LOADOUT)
       │    ├─ SettingsMenu(OFFHAND)              ─ Back → FamilyMenu(LOADOUT)
       │    └─ (HITBOX card: toggle only)
       ├─ FamilyMenu(COMBO)         ─ Back → Dashboard
       │    ├─ SettingsMenu(COMBO_HOLD)           ─ Back → FamilyMenu(COMBO)
       │    └─ SettingsMenu(COMBO_REACH_HANDICAP) ─ Back → FamilyMenu(COMBO)
       ├─ FamilyMenu(POTS)          ─ Back → Dashboard
       │    ├─ SettingsMenu(POT_FILL)             ─ Back → FamilyMenu(POTS)
       │    └─ SettingsMenu(FAST_POTS)            ─ Back → FamilyMenu(POTS)
       ├─ FamilyMenu(FEEDBACK)      ─ Back → Dashboard
       │    ├─ PresetGalleryMenu(EFFECTS) [hero]  ─ Back → FamilyMenu(FEEDBACK)
       │    ├─ SettingsMenu(HIT_FEEDBACK)         ─ Back → FamilyMenu(FEEDBACK)
       │    ├─ SettingsMenu(DAMAGE_INDICATORS)    ─ Back → FamilyMenu(FEEDBACK)
       │    └─ SettingsMenu(DEATH_EFFECTS)        ─ Back → FamilyMenu(FEEDBACK)
       ├─ FamilyMenu(LOOT)          ─ Back → Dashboard
       │    └─ SettingsMenu(DROP_PROTECTION)      ─ Back → FamilyMenu(LOOT)
       ├─ CompatibilityMenu         ─ Back → Dashboard
       └─ DebugMenu                 ─ Back → Dashboard
ChatPrompt edits (TEXT/NUMBER knobs) reopen the SettingsMenu they came from,
on both the value and the cancel path (existing promptOverlay contract).
```

Every screen is reachable from home; every Back returns exactly to its opener
(the graph has no cross-links, so hardcoded Backs are correct without a back
stack). The FamilyMenu-vs-EffectsMenu split is resolved: FEEDBACK is an
ordinary `FamilyMenu` whose three cards right-click into their settings pages.
The formula tier that wrote nothing (`KnockbackFormulaMenu`) is gone —
legacy/modern is a gallery TAB.

---

## 4. `SettingsCatalog` — the descriptor-driven knob registry

**CREATE `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/SettingsCatalog.java`**

```java
package me.vexmc.mental.v5.gui;

/**
 * The knob registry: which of a feature's settings are edited in-GUI, with
 * what widget, bounds, and copy. The same philosophy as DashboardModel — one
 * enumerable catalog the render path reads directly, pinned by
 * SettingsCatalogTest — applied to the settings layer. List-valued knobs are
 * deliberately POINTER tiles (the GUI never edits YAML lists; the overlay is
 * scalar-only by design), and knobs whose parse contract is drop-to-fallback
 * carry GUI clamps INSIDE the legal range so a GUI write can never produce a
 * reload warning.
 */
public final class SettingsCatalog {
    // RED-TEAM FIX: the class and configuredFeatures() MUST be public — the
    // tester (a separate plugin) imports SettingsCatalog and iterates
    // configuredFeatures() in BootSuite (§8 item 3). Knob/Page/pageFor stay
    // package-private (only SettingsMenu and same-package tests read them).

    enum Kind { TOGGLE, STEP_INT, STEP_DOUBLE, CYCLE, TEXT, NUMBER, POINTER, INFO }

    /**
     * One knob descriptor. reader returns the CURRENT effective value from the
     * snapshot (Boolean for TOGGLE, Number for STEP_*/NUMBER, String for
     * CYCLE/TEXT); null for POINTER/INFO. options carries the exact strings a
     * CYCLE writes (case matters — mirrored from the bundled YAML). file and
     * section direct POINTER tiles.
     */
    record Knob(Kind kind, String key, String label, String materialName, String blurb,
                double min, double max, double step, String unit,
                List<String> options, Function<Snapshot, Object> reader,
                String file, String section) {
        static Knob toggle(String key, String label, String material, String blurb,
                           Function<Snapshot, Object> reader);
        static Knob stepInt(String key, String label, String material, String blurb,
                            int min, int max, int step, String unit,
                            Function<Snapshot, Object> reader);
        static Knob stepDouble(String key, String label, String material, String blurb,
                               double min, double max, double step, String unit,
                               Function<Snapshot, Object> reader);
        static Knob cycle(String key, String label, String material, String blurb,
                          List<String> options, Function<Snapshot, Object> reader);
        static Knob text(String key, String label, String material, String blurb,
                         Function<Snapshot, Object> reader);
        static Knob number(String key, String label, String material, String blurb,
                           double min, double max, String unit,
                           Function<Snapshot, Object> reader);
        static Knob pointer(String label, String material, String blurb,
                            String file, String section);
        static Knob info(String label, String material, String blurb);
    }

    /** One screen: the owning feature and its knob rows (subsections). */
    record Page(Feature feature, List<List<Knob>> groups) {}

    /** The page for a feature, or empty when it is toggle-only. */
    static @NotNull Optional<Page> pageFor(@NotNull Feature feature);

    /** Every feature that owns a page — the tester's iteration seam (PUBLIC). */
    public static @NotNull List<Feature> configuredFeatures();
}
```

A private helper reads typed settings:
`private static <S> S settings(Snapshot s, Feature f, Class<S> type)` — casts
`s.settings(f.settingsKey())` (the identity-keyed map demands the descriptor's
own `SettingsKey` instance; never construct a fresh key).

### 4.1 The fourteen pages, knob by knob

Key spellings below are copied from the bundled YAML (verified against
`core/src/main/resources/*.yml` and `effects/presets/signature.yml`); GUI
clamps are chosen INSIDE each parse contract. `unit` renders after the value
(`"20 CPS"`, `"3.0 blocks"`). All blurbs are the exact shipped copy.

**HIT_REGISTRATION** — 3 groups → 5 rows. Accent AQUA.

| Group | Kind | key | label | icon | bounds/step/unit | blurb (exact) |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | STEP_INT | `hit-registration.max-cps` | Max CPS | `REPEATER` | 5..40, step 1, `CPS` | Attack packets per second before the pipeline starts ignoring the excess. |
| 1 | TOGGLE | `hit-registration.fast-path.enabled` | Fast Path | `FEATHER` | — | Register hits on the netty thread — zero server-tick latency on feedback. |
| 1 | TOGGLE | `hit-registration.fast-path.pre-send-feedback` | Pre-Send Feedback | `SUGAR` | — | Ship the victim's hurt feedback before the tick even begins. |
| 1 | TOGGLE | `hit-registration.fast-path.bundle-feedback` | Bundle Feedback | `STRING` | — | On 1.19.4+, land velocity and hurt animation in the same client frame. |
| 2 | TOGGLE | `hit-registration.fast-path.simulate-crits` | Simulate Crits | `NETHER_STAR` | — | Apply the era critical-hit rule on fast-path hits. |
| 2 | TOGGLE | `hit-registration.fast-path.legacy-tool-damage` | Legacy Tool Damage | `IRON_PICKAXE` | — | Use the 1.8 tool damage table on fast-path hits. |
| 2 | INFO | — | Feedback Interval | `CLOCK` | — | The feedback-min-interval-ms knob is 'auto' — derived live from the victim's hurt window. Tune it in hit-registration.yml. |
| 3 | TOGGLE | `hit-registration.reach-validation.enabled` | Reach Validation | `IRON_BARS` | — | Mental's own ping-rewound reach check — for servers running no anticheat. |
| 3 | STEP_DOUBLE | `hit-registration.reach-validation.max-reach` | Max Reach | `TARGET` | 2.5..6.0, step 0.1, `blocks` | The farthest a rewound attack may reach before it is discarded. |
| 3 | STEP_DOUBLE | `hit-registration.reach-validation.leniency` | Leniency | `SLIME_BALL` | 0.0..2.0, step 0.05, `blocks` | Forgiveness added on top of max reach for hitbox edges and jitter. |
| 3 | STEP_INT | `hit-registration.reach-validation.interpolation-offset-ms` | Interpolation Offset | `CLOCK` | 0..500, step 10, `ms` | How far behind real time the victim's rewound position is sampled. |
| 3 | STEP_INT | `hit-registration.reach-validation.rewind-cap-ms` | Rewind Cap | `ANVIL` | 0..1000, step 50, `ms` | The most latency the rewind will honour — pings past this stop gaining reach. |

**LATENCY_COMPENSATION** — 1 group of 5 → 3 rows. Accent RED.

| Kind | key | label | icon | bounds/step/unit | blurb |
| --- | --- | --- | --- | --- | --- |
| CYCLE | `latency-compensation.probe-strategy` | Probe Strategy | `COMPASS` | options `PING`, `TRANSACTION`, `KEEPALIVE` | How Mental measures each player's live ping mid-fight. Below 1.17 PING rides transactions automatically. |
| STEP_INT | `latency-compensation.spike-threshold-ms` | Spike Threshold | `REDSTONE` | 5..200, step 5, `ms` | A ping jump larger than this is a spike and is filtered, not believed. |
| STEP_INT | `latency-compensation.probe-interval-ticks` | Probe Interval | `CLOCK` | 1..100, step 1, `ticks` | How often the latency probe fires while a player is in combat. |
| STEP_INT | `latency-compensation.combat-timeout-ticks` | Combat Timeout | `ANVIL` | 5..1200, step 5, `ticks` | How long after the last hit a player still counts as in combat. |
| TOGGLE | `latency-compensation.off-ground-sync` | Off-Ground Sync | `FEATHER` | — | Correct vertical knockback against the victim's true airborne state. |

**FISHING_KNOCKBACK** — 1 group of 3 → 3 rows. Accent RED.

| Kind | key | label | icon | bounds/step/unit | blurb |
| --- | --- | --- | --- | --- | --- |
| NUMBER | `fishing-knockback.damage` | Hook Damage | `FISHING_ROD` | 0.0..20.0, `HP` | The damage a landed hook deals. Near-zero keeps 1.7 rods: a real hit that knocks but barely hurts. |
| CYCLE | `fishing-knockback.reel-in` | Reel-In Policy | `TRIPWIRE_HOOK` | options `legacy`, `cancel` | What happens on reel-in while hooked to a player — the era yank, or a clean cancel. |
| TOGGLE | `fishing-knockback.knockback-non-player-entities` | Knock Mobs | `EGG` | — | Let hooks shove mobs too, not just players. |

**PROJECTILE_KNOCKBACK** — 1 group of 4 → 3 rows. Accent RED.

| Kind | key | label | icon | bounds/unit | blurb |
| --- | --- | --- | --- | --- | --- |
| TOGGLE | `projectile-knockback.arrows` | Arrow Knockback | `BOW` | — | 1.7 arrow shove — away from the shooter, not the impact. |
| NUMBER | `projectile-knockback.damage.snowball` | Snowball Damage | `SNOWBALL` | 0.0..20.0, `HP` | Snowball hit damage. Near-zero keeps the classic no-hurt trade. |
| NUMBER | `projectile-knockback.damage.egg` | Egg Damage | `EGG` | 0.0..20.0, `HP` | Egg hit damage. Near-zero keeps the classic no-hurt trade. |
| NUMBER | `projectile-knockback.damage.ender-pearl` | Pearl Damage | `ENDER_PEARL` | 0.0..20.0, `HP` | Ender-pearl impact damage dealt to the player it strikes. |

**COMBO_HOLD** — 3 groups → 5 rows. Accent BLUE.

| Group | Kind | key | label | icon | bounds/step/unit | blurb |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | STEP_INT | `combo-hold.min-hits` | Min Hits | `IRON_SWORD` | 1..10, step 1, `hits` | Hits from one attacker before the servo engages — two confirms intent. |
| 1 | STEP_INT | `combo-hold.max-gap-ticks` | Max Gap | `CLOCK` | 1..100, step 1, `ticks` | An inter-hit gap longer than this ends the chain. Era cadence is 10–12. |
| 1 | STEP_INT | `combo-hold.grounded-run-ticks` | Grounded Run | `SLIME_BALL` | 1..100, step 1, `ticks` | Consecutive grounded ticks that end the combo — skims survive, touchdowns don't. |
| 1 | STEP_DOUBLE | `combo-hold.blowout-blocks` | Blowout Distance | `TARGET` | 1.0..20.0, step 0.5, `blocks` | Separation past this ends the combo — the pocket is gone. |
| 2 | STEP_DOUBLE | `combo-hold.gain` | Servo Gain | `REDSTONE` | 0.0..1.0, step 0.05, `×` | Blend toward the exact solve — 1.0 is exact, lower softens the touch. |
| 2 | STEP_DOUBLE | `combo-hold.min-factor` | Min Factor | `HOPPER` | 0.5..1.0, step 0.01, `×` | The honesty floor on the knock multiplier — below it, era physics wins. |
| 2 | STEP_DOUBLE | `combo-hold.max-factor` | Max Factor | `PISTON` | 1.0..2.0, step 0.01, `×` | The honesty ceiling on the knock multiplier — past it, era physics wins. |
| 2 | STEP_INT | `combo-hold.window-ticks` | Solve Window | `REPEATER` | 1..40, step 1, `ticks` | The flight window the inverse solve lands the victim inside. |
| 2 | CYCLE | `combo-hold.target-mode` | Target Mode | `COMPASS` | options `boundary`, `static` | boundary lands them where their answer is denied by a hair; static holds a fixed range. |
| 3 | STEP_DOUBLE | `combo-hold.target` | Static Target | `ITEM_FRAME` | 2.0..3.5, step 0.05, `blocks` | The held separation when target mode is static (the lab's 2.85 equilibrium). |
| 3 | POINTER | — | Reach Geometry | `BOOK` | file `combo.yml`, section `combo-hold` | victim-reach, attacker-reach, deny-margin, jitter-margin and target-floor are precision geometry — tuned in the file. |

**COMBO_REACH_HANDICAP** — 1 group of 2 → 3 rows. Accent BLUE.

| Kind | key | label | icon | bounds/step/unit | blurb |
| --- | --- | --- | --- | --- | --- |
| STEP_DOUBLE | `combo-reach-handicap.reach-scale` | Reach Scale | `REPEATER` | 0.5..1.0, step 0.01, `×` | The multiplier on a juggled victim's reach — 0.87 takes the era 3.0 to 2.61. |
| INFO | — | Version Note | `IRON_BARS` | — | 1.20.5+ only: the interaction-range attribute is client-synced from there. Below, this module is a documented no-op. |

**POT_FILL** — 1 group of 2 → 3 rows. Accent LIGHT_PURPLE.

| Kind | key | label | icon | bounds/unit | blurb |
| --- | --- | --- | --- | --- | --- |
| TEXT | `pot-fill.permission` | Permission Node | `NAME_TAG` | — | The node a player must hold to run /potfill. Blank is refused — the default stands. |
| NUMBER | `pot-fill.cost-per-potion` | Cost per Potion | `GOLD_INGOT` | 0.0..10000.0, `$` | The Vault charge per filled potion. Zero is free and needs no economy plugin. |

**FAST_POTS** — 1 group of 4 → 3 rows. Accent LIGHT_PURPLE.

| Kind | key | label | icon | bounds/step/unit | blurb |
| --- | --- | --- | --- | --- | --- |
| STEP_DOUBLE | `fast-pots.angle-degrees` | Trigger Angle | `ARROW` | 0.0..90.0, step 5, `°` | How steeply below the horizon a throw must aim before the redirect kicks in. |
| STEP_DOUBLE | `fast-pots.min-speed-multiplier` | Speed Floor | `HOPPER` | 0.05..1.0, step 0.05, `×` | The redirected launch never ships slower than this share of vanilla speed. |
| STEP_DOUBLE | `fast-pots.max-speed-multiplier` | Speed Ceiling | `PISTON` | 1.0..5.0, step 0.1, `×` | The redirected launch never ships faster than this multiple of vanilla speed. |
| STEP_DOUBLE | `fast-pots.lead-ticks` | Forward Lead | `FEATHER` | 0.0..5.0, step 0.5, `ticks` | Aim the burst slightly ahead of your feet so you run INTO the cloud. |

**OFFHAND** (Disable Off-Hand) — 1 group of 3 → 3 rows. Accent DARK_AQUA.

| Kind | key | label | icon | blurb |
| --- | --- | --- | --- | --- |
| TOGGLE | `disable-offhand.whitelist` | Whitelist Mode | `SHIELD` | ON: only the listed items are allowed off-hand. OFF: the listed items are blocked. |
| TEXT | `disable-offhand.denied-message` | Denied Message | `PAPER` | The line shown when an off-hand action is denied. Empty suppresses it. |
| POINTER (file `loadout.yml`, section `disable-offhand.items`) | — | Item List | `CHEST` | The whitelist/blocklist itself is a material list. |

**CRAFTING** (Disable Crafting) — 1 group of 1 → 3 rows. Accent DARK_AQUA.

| Kind | label | icon | file → section | blurb |
| --- | --- | --- | --- | --- |
| POINTER | Blocked Items | `CRAFTING_TABLE` | `loadout.yml` → `disable-crafting.items` | The materials that become uncraftable — SHIELD by default, the one 1.9 item an era server blocks. |

**HIT_FEEDBACK** (Hit Effects) — 1 group of 2 → 3 rows. Accent DARK_PURPLE.

| Kind | key | label | icon | bounds/step/unit | blurb |
| --- | --- | --- | --- | --- | --- |
| STEP_DOUBLE | `effects.hit.low-health-threshold-percent` | Low-Health Threshold | `REDSTONE` | 0..100, step 1, `%` | Below this share of max health, the extra low-health sound layer fires. |
| POINTER | — | Sounds & Particles | `JUKEBOX` | file `effects/presets/<selected>.yml`, section `hit-feedback` | The layered hit chord, particles and low-health chirp live in the preset tune. |

(The POINTER's `<selected>` is substituted live from
`snapshot().selectedEffectsPreset()` — the pointer names the actual file.)

**DAMAGE_INDICATORS** — 2 groups → 4 rows. Accent DARK_PURPLE.

| Group | Kind | key | label | icon | bounds/step/unit | blurb |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | TEXT | `effects.indicators.text` | Damage Text | `PAPER` | — | The pop-off template. {HEALTH} is the damage dealt; & colour codes render. |
| 1 | TEXT | `effects.indicators.crit-text` | Crit Text | `BLAZE_POWDER` | — | The critical variant — fires on an era crit or a heavy hit. |
| 1 | TEXT | `effects.indicators.heal-text` | Heal Text | `GLISTERING_MELON_SLICE` | — | Shown to the last attacker when the target heals. Empty disables heal indicators. |
| 2 | STEP_INT | `effects.indicators.lifetime-ticks` | Lifetime | `CLOCK` | 1..200, step 5, `ticks` | How long a marker may live before it despawns mid-air. |
| 2 | STEP_DOUBLE | `effects.indicators.crit-threshold-percent` | Crit Threshold | `REDSTONE` | 0..100, step 1, `%` | Damage at or above this share of max health also counts as a crit. |
| 2 | STEP_INT | `effects.indicators.roll-hold-ticks` | Roll Hold | `HOPPER` | 0..10, step 1, `ticks` | Hold a fresh hit's marker so mid-window upgrades fold into one number. |
| 2 | POINTER | — | Ballistics | `ARMOR_STAND` | file `effects/presets/<selected>.yml`, section `damage-indicators` | The arc itself — ring, launch, gravity, drag — is a tuned art. Shape it in the preset. |

(The three group-2 scalars require the parser-seam extension in §7.4.)

**DEATH_EFFECTS** — 2 groups → 4 rows. Accent DARK_PURPLE.

| Group | Kind | key | label | icon | bounds/step/unit | blurb |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | TEXT | `effects.death.kill-title` | Kill Title | `NAME_TAG` | — | Flashed to the killer. {NAME}, {KILLER} and {PROTECT_SECONDS} substitute; & codes render. |
| 1 | TEXT | `effects.death.kill-subtitle` | Kill Subtitle | `PAPER` | — | The smaller line under the kill title. Empty with an empty title sends nothing. |
| 1 | STEP_INT | `effects.death.title-fade-in` | Fade In | `GLOWSTONE_DUST` | 0..200, step 5, `ticks` | Client ticks the title takes to appear. |
| 1 | STEP_INT | `effects.death.title-stay` | Stay | `CLOCK` | 0..400, step 5, `ticks` | Client ticks the title holds on screen. |
| 1 | STEP_INT | `effects.death.title-fade-out` | Fade Out | `REDSTONE` | 0..200, step 5, `ticks` | Client ticks the title takes to leave. |
| 2 | TOGGLE | `effects.death.lightning` | Cosmetic Lightning | `BLAZE_POWDER` | — | A packet-only bolt at the death spot — all flash, no fire, no damage. |
| 2 | POINTER | — | Sounds & Firework | `FIREWORK_ROCKET` | file `effects/presets/<selected>.yml`, section `death-effects` | The death chord, particles and the coloured blast live in the preset tune. |

**DROP_PROTECTION** — 1 group of 3 → 3 rows. Accent GOLD.

| Kind | key | label | icon | bounds/step/unit | blurb |
| --- | --- | --- | --- | --- | --- |
| STEP_INT | `drop-protection.seconds` | Protection Window | `CLOCK` | 1..3600, step 5, `s` | How long a slain player's drops stay locked to the killer. |
| CYCLE | `drop-protection.glow-color` | Glow Colour | `GOLD_INGOT` | options `GOLD`, `YELLOW` | The outline colour the killer alone sees on the protected drops. |
| INFO | — | Pickup Rule | `PLAYER_HEAD` | — | Only the killer may pick the drops up while the window runs; afterwards they are free-for-all. |

### 4.2 Toggle-only features (no page, by design)

WTAP_REGISTRATION, KNOCKBACK (its "settings" IS the gallery — the card's
right-click opens it), ROD_VELOCITY, ARMOUR_STRENGTH, ARMOUR_DURABILITY,
CRIT_FALLBACK, TOOL_DURABILITY, SWORD_BLOCKING, ATTACK_COOLDOWN,
ATTACK_SOUNDS, SWEEP, GOLDEN_APPLES, ENDER_PEARL_COOLDOWN, REGEN,
POTION_DURATIONS, POTION_VALUES, HITBOX. Their settings class is `NoSettings`
(or, for KNOCKBACK, the profile system). `SettingsCatalogTest` pins the
partition (§9).

### 4.3 Overlay-routing audit of every written key

Every key's FIRST segment already has an `Overlay.route` case — NO Overlay
changes: `hit-registration`, `latency-compensation`, `fishing-knockback`,
`projectile-knockback`, `combo-hold`, `combo-reach-handicap`, `pot-fill`,
`fast-pots`, `disable-offhand`, `disable-crafting`, `drop-protection`,
`effects`, `anticheat` (→ main), `debug` (→ main), `modules` (→ main).

Write-type rules (normative — a wrong type warns on reload):
- STEP_INT writes `Integer`; STEP_DOUBLE/NUMBER write `Double` (rounded to 4
  decimals: `Math.round(v * 10000.0) / 10000.0` — kills float drift in the
  overlay file).
- TOGGLE writes `Boolean`.
- CYCLE writes the exact option String from `options` (case as listed —
  mirrored from the bundled YAML: `PING`/`TRANSACTION`/`KEEPALIVE` upper,
  `legacy`/`cancel` lower, `boundary`/`static` lower, `GOLD`/`YELLOW` upper;
  Compatibility keeps its existing lower-dash `auto`/`force-safe`/`off`).
  RED-TEAM VERIFIED: `ConfigReader.oneOf` (`ConfigReader.java:157-177`)
  normalizes `trim().toUpperCase(ROOT).replace('-','_')` before
  `Enum.valueOf`, and `target-mode` has its own lowercasing migration parser
  (`SnapshotParser.parseTargetMode`) — every listed case parses cleanly.
- **CYCLE reader rule (normative):** the knob's `reader` must return the
  CURRENT value spelled EXACTLY as one of `options` (the selected-radio match
  is `String.equals`). Concretely: `probe-strategy` reader returns
  `settings.probeStrategy().name()` (upper), `glow-color` returns
  `glowColor().name()` (upper), while `reel-in` returns
  `reelIn().name().toLowerCase(Locale.ROOT)` and `target-mode` returns
  `targetMode().name().toLowerCase(Locale.ROOT)` (lower, matching their
  bundled-YAML case).
- TEXT writes the raw chat String (existing `promptOverlay` path).
- NUMBER parses the chat line with `Double.parseDouble` BEFORE writing; a
  non-numeric or out-of-bounds line writes NOTHING, sends
  `Brand.failure("That's not a number between <min> and <max> — nothing changed.")`
  via `TextPort.send`, and reopens the screen.

---

## 5. The `PresetCatalog` seam (consumed; backend plan owns it — RECONCILED)

*(RED-TEAM: this section is now the FINAL contract, reconciled verbatim with
the backend plan's §3, which is normative for implementation details. The
former wishlist's `selectionKey()` is `overlayKey()`; the catalog is STATIC —
no `MenuContext` change, so the BootSuite `MenuContext` ctor compile-pin is
untouched; `formula` is the boolean `modernFormula` — `MeleeFormula` stays a
GUI-package type and the tab filter derives it.)*

```java
package me.vexmc.mental.v5.preset;

public enum PresetKind {
    KNOCKBACK,   // displayName "Knockback Presets", iconName "BOOKSHELF", overlayKey "knockback.profile"
    EFFECTS;     // displayName "Effects Presets",  iconName "JUKEBOX",   overlayKey "effects.preset"
    public String displayName();     // gallery title suffix + header-card name
    public String iconName();        // gallery hero/header icon (legacy-safe)
    public String blurb();           // the FamilyMenu hero-tile copy (§6.2)
    public String overlayKey();      // the FROZEN selection key
    public String defaultName();     // "legacy-1.7" / "signature" (delegated reads)
    public List<String> bundledNames(); // ConfigStore.BUNDLED_PROFILES / _EFFECTS_PRESETS
}

public final class PresetCatalog {   // non-instantiable static utility
    /** Sorted stems of every loaded preset of the kind. */
    public static List<String> names(PresetKind kind, Snapshot snapshot);
    /** The active selection (defaultProfile / selectedEffectsPreset). */
    public static String selected(PresetKind kind, Snapshot snapshot);
    /** Bundled vs user-dropped (drives the gallery's provenance badge). */
    public static boolean isBundled(PresetKind kind, String name);
    /** Display info + preview lines; never null (degrades for unknown names). */
    public static PresetInfo info(PresetKind kind, String name, Snapshot snapshot);
    /** names(...) mapped through info(...) — the gallery's one render read. */
    public static List<PresetInfo> infos(PresetKind kind, Snapshot snapshot);
    /** Applies via Management.setGlobalProfile / setEffectsPreset ONLY. */
    public static boolean apply(PresetKind kind, String name, Management management);
}

// Top-level record in the same package (NOT nested in the catalog):
public record PresetInfo(PresetKind kind, String name, String displayName,
                         String description, String iconName, boolean loaded,
                         boolean bundled, boolean active, boolean modernFormula,
                         List<PreviewLine> preview) {
    public record PreviewLine(String label, String value) {}
}
```

Catalog content guarantees (all owned + unit-pinned by the backend plan):

- `iconName` carries the per-preset themed icons (the 13-entry knockback map
  and 2-entry effects map move OUT of the deleted menus INTO the catalog;
  unknown names fall back to `PAPER`). The gallery renders `info.iconName()`
  and keeps NO icon map of its own.
- `modernFormula` is `profile.modern().enabled()` for KNOCKBACK, always
  `false` for EFFECTS — the gallery's tab filter is
  `info.modernFormula() == (tab == MeleeFormula.MODERN)`.
- `active`/`bundled` flags replace the gallery's own selected/bundled lookups
  (`isBundled` remains available for ad-hoc checks).
- `preview` reproduces today's read-only previews verbatim (strings copied
  character-for-character in the backend plan §3.4, unit-pinned there):
  - legacy profile: `Base h/v`, `Vertical`, `Delivery`, `Combos`, `Sprint x`,
    `Resistance`, and `Pace scale` when active;
  - modern profile: `Base`, `Sprint +`, `Enchant +`, `Downward`, `Combos`,
    `Delivery`;
  - effects preset: `Hit sounds`, `Hit particles`, `Low-HP layer`,
    `Indicator`, `Death`.

---

## 6. Per-screen specifications

Legend for grids: letters are content slots (keyed below each grid); `A`/`l`/`g`
are accent/light-grey/grey chrome panes per §2.3. All click behaviors run
through `apply(viewer, …)` (global-thread mutation + repaint) unless marked
*nav* (region-thread `navigate`) or *local* (no config write).

### 6.1 `DashboardMenu` — MODIFY

Title `Mental` (bold GOLD). Rows **6** (54 — tester-pinned). Theme home
(ORANGE panes, GOLD accent). Renders off `DashboardModel.homeRows()`
(UNCHANGED — DashboardModelTest untouched).

```
slot   0  1  2  3  4  5  6  7  8
row0   A  l  g  g  S  g  g  l  A
row1   l  g  g  K  D  g  g  g  l
row2   A  d  c  u  o  m  p  g  A
row3   A  g  g  E  T  g  g  g  A
row4   l  g  g  C  g  B  g  g  l
row5   A  l  g  R  g  X  g  l  A
```

| Slot | Material | Name (exact, colour) | Lore | Click |
| --- | --- | --- | --- | --- |
| 4 `S` | `NETHER_STAR` | `Mental` bold GOLD | `Latency-compensated 1.7.10 combat` MUTED; blank; kv (GOLD accent): `Version:`, `Server:`, `Scheduling:`, `Knockback: <defaultProfile>`, `Effects: <selectedEffectsPreset>`, `Modules: <n> / <total> active`, `Anticheat: <mode lowercase>` | none |
| 12 `K` | KNOCKBACK family tile | `Knockback` bold WHITE | family blurb wrapped MUTED; blank; `▸ Click to open` SECONDARY | *nav* → `FamilyMenu(KNOCKBACK)` |
| 13 `D` | DELIVERY tile | `Hit Delivery` bold WHITE | same pattern | *nav* → `FamilyMenu(DELIVERY)` |
| 19–24 `d c u o m p` | DAMAGE, CADENCE, SUSTAIN, LOADOUT, COMBO, POTS tiles | `<Family.displayName()>` bold WHITE | family blurb; `▸ Click to open` | *nav* → `FamilyMenu(family)` |
| 30 `E` | FEEDBACK tile | `Combat Effects` bold WHITE | blurb; `▸ Click to open` | *nav* → `FamilyMenu(FEEDBACK)` |
| 31 `T` | LOOT tile | `Loot Protection` bold WHITE | blurb; `▸ Click to open` | *nav* → `FamilyMenu(LOOT)` |
| 39 `C` | `COMPASS` | `Compatibility` bold WHITE | `Anticheat posture — how Mental yields to a prediction anticheat.` wrapped MUTED; `▸ Click to open` | *nav* → `CompatibilityMenu` |
| 41 `B` | `REPEATER` | `Debug` bold WHITE | `Verbose logging channels, streamed to console or your own chat.` wrapped MUTED; `▸ Click to open` | *nav* → `DebugMenu` |
| 48 `R` | `LIME_DYE` | `Reload configuration` bold SUCCESS | `Re-read every file and converge modules.` MUTED; `Applied atomically — no hit reads a half-config.` MUTED; blank; `▸ Click to reload` SECONDARY | `apply(() -> management().reload())` |
| 50 `X` | `BARRIER` | `Close` bold FAILURE | `Close this menu.` MUTED | *local* `viewer.closeInventory()` |

Family tiles are built with `Buttons.nav(family.iconName(),
family.displayName(), Palette.of(family).accent(), family.blurb())` — the
family's accent colours the tile name on hover states via the kv value colour;
name itself stays bold WHITE for a calm home (accents live on the family
screens). All family tiles route to `FamilyMenu(family)` — the
`homeDestination` switch is DELETED (no special cases left).
`selfTestIcons()` keeps returning `[statusPlate, reloadButton, closeButton]`.

### 6.2 `FamilyMenu(family)` — MODIFY (total rewrite inside the same class name)

Constructor `FamilyMenu(MenuContext ctx, Family family)` (kept). Title
`Mental · <family.displayName()>`. Rows: `4` for KNOCKBACK and FEEDBACK
(hero row), else `3`. Theme `Palette.of(family)`.

Structure (3-row form; hero form adds row2 and moves Back to 31):

```
3-row:                              4-row (KNOCKBACK / FEEDBACK):
A  l  g  g  F  g  g  l  A          A  l  g  g  F  g  g  l  A
l  [ feature cards 1..7 ]  l       l  [ feature cards 1..7 ]  l
A  l  g  g  ←  g  g  l  A          l  g  g  g  G  g  g  g  l
                                   A  l  g  g  ←  g  g  l  A
```
*(cols 0/8 of the card row are the §2.3 side fades — chrome, never content;
cards live in cols 1..7 via `Layout.contentRow`.)*

| Slot | Content | Behavior |
| --- | --- | --- |
| 4 `F` | family header card: `Buttons.title(family.iconName(), family.displayName(), accent)`; lore: wrapped family blurb MUTED; blank; `kv("Active", "<n> / <total> modules", accent)` | none |
| row1 | one `Buttons.moduleCard` per `DashboardModel.entries(family)` at `Layout.contentRow(9, count)` — name `feature.displayName()`, blurb `feature.blurb()`, enabled `ctx.plugin().featureActive(feature)`; `settingsHint` = `"▸ Right-click to configure"` when `SettingsCatalog.pageFor(feature)` is present, `"▸ Right-click for the preset gallery"` for KNOCKBACK, null otherwise | right-click (when a settings/gallery destination exists) → *nav* to `SettingsMenu(ctx, feature)` or (KNOCKBACK feature) `PresetGalleryMenu(ctx, PresetKind.KNOCKBACK)`; every other click → `apply(() -> management().setModuleEnabled(feature, !enabled))` |
| 22 `G` (hero, KNOCKBACK) | `PresetKind.KNOCKBACK.iconName()` (`BOOKSHELF`) — `Knockback Preset Gallery` bold RED accent; lore: `Buttons.wrap(PresetKind.KNOCKBACK.blurb())` MUTED (the blurb IS "Every knockback feel — the era's archived servers, Mental's own, and yours — previewed and applied live." — single source, backend plan §3.2); blank; `kv("Active", <selected displayName>, accent)`; `▸ Click to browse` SECONDARY | *nav* → `PresetGalleryMenu(ctx, KNOCKBACK)` |
| 22 `G` (hero, FEEDBACK) | `PresetKind.EFFECTS.iconName()` (`JUKEBOX`) — `Effects Preset Gallery` bold DARK_PURPLE; lore: `Buttons.wrap(PresetKind.EFFECTS.blurb())` MUTED (the blurb IS "Whole combat-effects tunes — hits, indicators and deaths swapped as one."); blank; `kv("Active", <selected displayName>, accent)`; `▸ Click to browse` SECONDARY | *nav* → `PresetGalleryMenu(ctx, EFFECTS)` |
| 22 (3-row) / 31 (4-row) `←` | `Buttons.back("the Dashboard")` | *nav* → `DashboardMenu` |

Per-family card slots (from §2.5's table): DELIVERY 12,14 · KNOCKBACK 11–15 ·
DAMAGE 11–15 · CADENCE 11,13,15 · SUSTAIN 11–15 · LOADOUT 11,13,15 · COMBO
12,14 · POTS 12,14 · FEEDBACK 11,13,15 · LOOT 13.
`spreadColumns` and the dead 6-row FEEDBACK branch are DELETED.
`selfTestIcons()` (new): header card, every module card (rendered with live
`featureActive`), hero when present, back.

### 6.3 `SettingsMenu(ctx, feature)` — CREATE

**CREATE `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/SettingsMenu.java`**

```java
/**
 * The one settings screen: renders a SettingsCatalog page for its feature —
 * master module card in the header, one tile per knob laid out by group row,
 * every write a single overlay key through Management, every tile carrying
 * the override flag and the Q-reset affordance. Adding a knob to the catalog
 * is the ONLY step to surface it here.
 */
public final class SettingsMenu extends Menu {
    public SettingsMenu(@NotNull MenuContext ctx, @NotNull Feature feature);
    // throws IllegalArgumentException when SettingsCatalog.pageFor(feature) is empty
    public @NotNull List<ItemStack> selfTestIcons();
}
```

Rows = `2 + page.groups().size()`. Title `Mental · <feature.displayName()>`.
Theme `Palette.of(feature.family())`.

Fixed slots:
- **4** — master card: `Buttons.moduleCard(feature.iconName(),
  feature.displayName(), accent, featureActive(feature), feature.blurb(),
  null)`; click toggles the module (`setModuleEnabled`).
- **group row i** (1-based) — knob tiles at `Layout.contentRow(9*i, group.size())`.
- **(rows-1)*9 + 4** — `Buttons.back(feature.family().displayName())` → *nav*
  `FamilyMenu(ctx, feature.family())`.

Knob click dispatch (one handler, normative):

```
DROP or CONTROL_DROP  → if overlayHas(key): apply(() -> management().clearOverlay(key))
TOGGLE                → apply(() -> management().setOverlay(key, !current))
STEP_*                → delta = step * (shift ? 10 : 1) * (right ? -1 : 1)
                        next  = clamp(current + delta, min, max)
                        apply(() -> management().setOverlay(key, typed(next)))
CYCLE                 → next/prev option by click side; apply(setOverlay(key, option))
TEXT                  → promptOverlay(viewer, label, key)                  [Menu, unchanged]
NUMBER                → promptNumber(viewer, knob)                          [private; §4.3 rules]
POINTER / INFO        → no handler
```

`selfTestIcons()`: master card + every knob tile (read from the live
snapshot) + back. Pure-Bukkit signature.

Example grid — DEATH_EFFECTS (4 rows, PURPLE panes, DARK_PURPLE accent);
every other page derives mechanically from §4.1 + §2.5:

```
A  l  g  g  M  g  g  l  A     M  = master card (Death Effects)
l  g  t  s  i  y  o  g  l     11 kill-title, 12 kill-subtitle, 13 fade-in, 14 stay, 15 fade-out
l  g  g  L  g  P  g  g  l     21 lightning toggle, 23 sounds&firework pointer
A  l  g  g  ←  g  g  l  A     31 back → Combat Effects
```
*(RED-TEAM grid fix: `Layout.contentRow(9, 5)` places five knobs at 11..15 —
slot 10 is chrome.)*

(Knob slots per page, from §2.5: 5-row pages place group rows at bases 9, 18,
27 with Back at 40; 4-row pages at 9, 18 with Back at 31; 3-row pages at 9
with Back at 22.)

### 6.4 `PresetGalleryMenu(ctx, kind)` — CREATE

**CREATE `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/PresetGalleryMenu.java`**

```java
/**
 * The unified preset gallery: one screen for both PresetKinds, read entirely
 * through PresetCatalog and written entirely through its apply (which
 * delegates to Management.setGlobalProfile / setEffectsPreset — the tester's
 * seam). For KNOCKBACK the legacy/modern formula split is a TAB, not a
 * navigation tier: the tab filters the same gallery in place and writes
 * nothing. 21 tiles per page with pure-math pagination for the day a server
 * drops in a folder of custom presets.
 */
public final class PresetGalleryMenu extends Menu {
    public PresetGalleryMenu(@NotNull MenuContext ctx, @NotNull PresetKind kind);
    public @NotNull List<ItemStack> selfTestIcons();
    /** Tab-forced render for the boot self-test (KNOCKBACK only; EFFECTS ignores it). */
    public @NotNull List<ItemStack> selfTestIcons(@NotNull MeleeFormula tab);
}
```

State: `private MeleeFormula tab` (initialised to
`MeleeFormula.of(snapshot().profile(snapshot().defaultProfile()))` so the
gallery opens showing the ACTIVE formula; always `LEGACY` for EFFECTS but
unused there), `private int page = 0`. Rows **6** (54 — tester-pinned). Title
`Mental · Knockback Presets` / `Mental · Effects Presets`. Theme
`Palette.gallery(kind)`.

```
slot   0  1  2  3  4  5  6  7  8
row0   A  l  L  g  H  g  M  l  A     L/M tabs only for KNOCKBACK (else chrome)
row1   l  p  p  p  p  p  p  p  l
row2   A  p  p  p  p  p  p  p  A
row3   A  p  p  p  p  p  p  p  A
row4   l  g  g  g  g  g  g  g  l
row5   A  l  <  g  ←  g  >  l  A     47 prev · 49 back · 51 next
```
*(RED-TEAM grid fix: cols 1/7 of row0 and cols 0/8 of row1 are the §2.3
fades (`l`), not plain grey — the pattern is computed, the sketch merely
mirrors it.)*

| Slot | Content | Behavior |
| --- | --- | --- |
| 4 `H` | header card: kind icon (`BOOKSHELF`/`JUKEBOX`), name `<kind.displayName()>` bold accent; lore: `kv("Active", <selected displayName>, accent)`; when `pageCount > 1`: `kv("Page", "<page+1> / <pageCount>", accent)`; blank; KNOCKBACK: `Pick the era formula above, then a feel below.` MUTED / EFFECTS: `One tune per tile — sounds, indicators and deaths.` MUTED | none |
| 2 `L` (KNOCKBACK only) | `STONE_SWORD` — `Legacy formula` bold (accent when `tab==LEGACY`, WHITE otherwise); lore: `MeleeFormula.LEGACY.blurb()` wrapped MUTED; blank; `kv("Presets", <count>, accent)`; `● SHOWING` bold SUCCESS when active else `▸ Click to view` SECONDARY; `.glow(tab==LEGACY)` | *local*: `tab = LEGACY; page = 0; refresh(viewer)` |
| 6 `M` (KNOCKBACK only) | `NETHERITE_SWORD` — `Modern formula` bold; same pattern for `tab==MODERN` | *local*: `tab = MODERN; page = 0; refresh(viewer)` |
| 10–16, 19–25, 28–34 `p` | preset tiles for the current page of the filtered list (KNOCKBACK: `info.modernFormula() == (tab == MeleeFormula.MODERN)`; EFFECTS: all), in `Layout.galleryGrid()` order | click → `apply(() -> PresetCatalog.apply(kind, name, management()))` |
| 47 `<` | shown only when `hasPrev`: `ARROW` — `Previous page` bold SECONDARY, lore `Page <page> of <pageCount>.` MUTED | *local*: `page--; refresh(viewer)` |
| 49 `←` | `Buttons.back("Knockback")` / `Buttons.back("Combat Effects")` | *nav* → `FamilyMenu(KNOCKBACK)` / `FamilyMenu(FEEDBACK)` |
| 51 `>` | shown only when `hasNext`: `ARROW` — `Next page` bold SECONDARY | *local*: `page++; refresh(viewer)` |

Preset tile (built from `PresetCatalog.info(...)`):
- material `info.iconName()`;
- name `info.displayName()` — bold, SUCCESS when `info.active()` else WHITE;
- lore: `(<name>)` MUTED; provenance badge — `Bundled preset` DARK_GRAY or
  `Your preset` DARK_GRAY (from `info.bundled()`); blank; wrapped
  `info.description()` MUTED when non-empty; blank; every
  `PreviewLine` as `kv(label, value, accent)`; blank;
  `● ACTIVE — server-wide` bold SUCCESS when `info.active()` else
  `▸ Click to apply server-wide` SECONDARY;
- `.glow(info.active())`.

### 6.5 `CompatibilityMenu` — MODIFY

Title `Mental · Compatibility`. Rows **3**. Theme system (LIGHT_GRAY panes,
WHITE accent). The lore-radio anti-pattern is replaced by three real radio
tiles.

```
A  l  g  g  H  g  g  l  A
l  g  a  g  f  g  o  g  l     11 auto · 13 force-safe · 15 off
A  l  g  g  ←  g  g  l  A     22 back
```

| Slot | Material | Name | Lore | Click |
| --- | --- | --- | --- | --- |
| 4 `H` | `IRON_BARS` | `Anticheat Coexistence` bold WHITE | wrapped MUTED: `Mental's pre-send fast path predicts what an anticheat may dislike. Pick how hard Mental yields when one is present.`; blank; `kv("Mode", <current lowercase>, WHITE)` | none |
| 11 `a` | `ENDER_EYE` | `auto` bold (SUCCESS+glow when selected, WHITE else) | wrapped MUTED: `Detect GrimAC and Vulcan and hold pre-send back automatically — the shipped default.`; blank; `● SELECTED` bold SUCCESS / `▸ Click to select` SECONDARY | `apply(setOverlay("anticheat.mode", "auto"))` |
| 13 `f` | `IRON_BARS` | `force-safe` bold | wrapped MUTED: `Always hold pre-send back, anticheat or not — the most conservative posture.`; state line | `apply(setOverlay("anticheat.mode", "force-safe"))` |
| 15 `o` | `BARRIER` | `off` bold | wrapped MUTED: `Full fast path, no accommodation. For servers that trust their anticheat pairing.`; state line | `apply(setOverlay("anticheat.mode", "off"))` |
| 22 `←` | `Buttons.back("the Dashboard")` | | | *nav* → `DashboardMenu` |

New: `public List<ItemStack> selfTestIcons()` — header + three radios + back.

### 6.6 `DebugMenu` — MODIFY

Title `Mental · Debug`. Rows **5**. Theme system. The hand-synced
`CATEGORY_SLOTS` array is deleted; channels flow through `Layout.contentRow`
(first 7 on row 1, remainder on row 2 — capacity 14 with a code comment
naming the limit; `DebugCategory` has 11).

```
A  l  g  g  M  g  g  l  A     4  master toggle
l  c  c  c  c  c  c  c  l     10..16 channels 1–7
A  c  g  c  g  c  g  c  A     19,21,23,25 channels 8–11
l  g  g  g  S  g  g  g  l     31 stream-to-chat
A  l  g  g  ←  g  g  l  A     40 back
```
*(RED-TEAM grid fix: `Layout.contentRow(18, 4)` gaps the four remainder
channels at 19,21,23,25 — cols 1,3,5,7.)*

| Slot | Content | Click |
| --- | --- | --- |
| 4 | `Buttons.toggle("REDSTONE_TORCH", "Debug logging", WHITE, masterOn, "Master switch for verbose logging. Enable it, then pick channels below.", overlayHas("debug.enabled"))` | `apply(setOverlay("debug.enabled", !masterOn))`; Q resets |
| 10–16, 19,21,23,25 | per `DebugCategory.values()`: `Buttons.toggle(ICONS.getOrDefault(cat,"PAPER"), "Channel: " + cat.key(), WHITE, on, "Verbose " + cat.key() + " logging.", overlayHas("debug.categories."+cat.key()))` — ICONS map kept verbatim | `apply(setOverlay("debug.categories."+key, !on))`; Q resets |
| 31 | `Buttons.toggle("NAME_TAG", "Stream to my chat", WHITE, subscribed, "Send the active debug channels to your own chat. Per-session — cleared when you quit.", false)` | *local*: `ctx.plugin().playerDebugSink().toggle(viewer); refresh(viewer)` — the pointless global-thread hop is removed (in-memory flip, no config write) |
| 40 | `Buttons.back("the Dashboard")` | *nav* → `DashboardMenu` |

New: `public List<ItemStack> selfTestIcons()` — master + all 11 channel tiles
+ the subscribe tile rendered with `subscribed=false` (no viewer in the
self-test) + back.

---

## 7. Cross-version verification

### 7.1 Material audit — every name the redesigned GUI requests

Resolved directly on 1.9.4 (no alias needed): `ARROW`, `ANVIL`, `ARMOR_STAND`,
`BARRIER`, `BLAZE_POWDER`, `BOOK`, `BOOKSHELF`, `BOW`, `CHEST`, `COMPASS`,
`DIAMOND_AXE`, `DIAMOND_CHESTPLATE`, `DIAMOND_SWORD`, `EGG`, `ENDER_PEARL`,
`FEATHER`, `FISHING_ROD`, `GLOWSTONE_DUST`, `GOLD_INGOT`, `GOLDEN_APPLE`,
`HOPPER`, `IRON_AXE`, `IRON_CHESTPLATE`, `IRON_PICKAXE`, `IRON_SWORD`,
`ITEM_FRAME`, `JUKEBOX`, `NAME_TAG`, `NETHER_STAR`, `NOTE_BLOCK`, `PAPER`,
`POTION`, `REDSTONE`, `SHIELD` (1.9+), `SLIME_BALL`, `SPLASH_POTION` (1.9+),
`STONE_SWORD`, `STRING`, `SUGAR`, `TRIPWIRE_HOOK`.

Resolved via LEGACY_ALIAS (12 existing + 10 added in §2.1): `CLOCK`,
`COMPARATOR`, `CRAFTING_TABLE`, `ENCHANTED_GOLDEN_APPLE`, `ENDER_EYE`,
`FIREWORK_ROCKET`, `GLISTERING_MELON_SLICE`, `GOLDEN_SWORD`,
`GRAY_STAINED_GLASS_PANE`, `IRON_BARS`, `LIME_DYE`, `NETHERITE_AXE`,
`NETHERITE_SWORD`, `OAK_SIGN`, `PISTON`, `PLAYER_HEAD`, `REDSTONE_TORCH`,
`REPEATER`, `SNOWBALL`, `TARGET`, `TRIDENT`, `WRITABLE_BOOK`.

Panes: 16 modern `*_STAINED_GLASS_PANE` on 1.13+; `STAINED_GLASS_PANE` + data
0–15 on 1.9.4–1.12.2 (stained panes exist from 1.7 — whole runtime range
covered). Accepted degrades: none remaining — every requested name now
renders a real glyph on every supported version.

### 7.2 What the boot self-test must newly assert (so a legacy pane
regression can never ship silently)

BootSuite (§8) adds a pane check on EVERY version including 1.9.4: for each
`PaneColor` constant, `MenuMaterials.pane(color)`:
1. is non-null and its type is not `AIR` and **not `STONE`** (STONE = the
   resolver fell through — the regression signature);
2. when `Material.getMaterial(color.modernName()) != null` (modern server):
   the stack's type name equals `color.modernName()` and its durability is 0;
3. otherwise (pre-flattening server): the type name equals
   `"STAINED_GLASS_PANE"` and `stack.getDurability() == color.legacyData()`.

### 7.3 Threading / TextPort discipline (unchanged, restated for implementers)

All titles/names/lore continue through `Icon` → `TextPort` →
`setDisplayName(String)` / `setLore(List<String>)`; `createInventory` only via
`TextPort`; no `net.kyori` type in any tester-visible signature; chrome panes
get their empty name through the same sink. Tab/page clicks mutate menu-local
fields on the viewer's region thread and `refresh` (no global hop); all
config writes stay in `apply` → `runGlobal`.

### 7.4 The one parser change (additive, zero-touch-safe)

**MODIFY `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/config/SnapshotParser.java`**
— `applyIndicatorOverrides` additionally layers three scalars (same
warn-and-clamp vocabulary as its siblings):

```java
reader.intClamped("lifetime-ticks", preset.lifetimeTicks(),
        DamageIndicatorsSettings.MIN_LIFETIME, DamageIndicatorsSettings.MAX_LIFETIME),
...
reader.numberClamped("crit-threshold-percent", preset.critThresholdPercent(), 0.0, 100.0),
reader.intClamped("roll-hold-ticks", preset.rollHoldTicks(),
        DamageIndicatorsSettings.MIN_ROLL_HOLD, DamageIndicatorsSettings.MAX_ROLL_HOLD),
```

The override section is only consulted when it EXISTS (`reader.section() ==
null` → preset verbatim), so `parse(empty)` and every existing pin are
untouched; the keys mirror the preset file's spellings exactly
(`lifetime-ticks`, `crit-threshold-percent`, `roll-hold-ticks`).

---

## 8. Tester changes (lockstep, R4)

**MODIFY `/Users/owengregson/Documents/StrikeSync/tester/src/main/java/me/vexmc/mental/tester/suite/BootSuite.java`**
— the case `boot: dashboard GUI renders headless (Adventure + String sinks
load)` becomes `boot: every management screen renders headless (Adventure +
String sinks load)`:

- Imports: DROP `KnockbackFormulaMenu`, `ProfileMenu`, `EffectsPresetMenu`;
  ADD `FamilyMenu`, `SettingsMenu`, `PresetGalleryMenu`, `CompatibilityMenu`,
  `DebugMenu`, `SettingsCatalog`, `me.vexmc.mental.v5.preset.PresetKind`,
  `me.vexmc.mental.platform.PaneColor`, `me.vexmc.mental.platform.MenuMaterials`.
  KEEP `Menu`, `MenuContext`, `ChatPrompt`, `DashboardMenu`, `MeleeFormula`,
  `me.vexmc.mental.v5.feature.Family`, `Feature`.
- Assertions (all on the global tick, one MenuContext as today):
  1. Dashboard: `selfTestIcons()` non-empty, non-null, non-AIR;
     `selfTestInventory().getSize() == 54`; `getHolder() == dashboard` (KEPT verbatim).
  2. For each `Family.values()`: `new FamilyMenu(menuContext, family)` —
     icons render; `selfTestInventory().getHolder() == menu`;
     `getSize() % 9 == 0 && getSize() >= 27`.
  3. For each `SettingsCatalog.configuredFeatures()`:
     `new SettingsMenu(menuContext, feature)` — icons render; holder identity.
  4. `new PresetGalleryMenu(menuContext, PresetKind.KNOCKBACK)`: for each
     `MeleeFormula.values()` → `selfTestIcons(which)` render non-AIR (keeps
     the MeleeFormula compile pin alive); `selfTestInventory().getSize() == 54`;
     holder identity.
  5. `new PresetGalleryMenu(menuContext, PresetKind.EFFECTS)`: icons render;
     size 54; holder identity.
  6. `new CompatibilityMenu(menuContext)` and `new DebugMenu(menuContext)`:
     icons render; holder identity.
  7. The §7.2 pane-regression loop over `PaneColor.values()`.

**`CommandSuite`** — UNCHANGED (bare `/mental` still opens `DashboardMenu`,
holder `instanceof Menu`).
**`ProfileSuite` / `FeedbackSuite` / every management-seam suite** — UNCHANGED
(`setGlobalProfile` semantics, bundled names, the change event, the
`effects.preset` overlay path are all untouched by this plan).

---

## 9. Unit tests

New classes (all under
`/Users/owengregson/Documents/StrikeSync/core/src/test/java/me/vexmc/mental/v5/gui/`
unless noted):

- **`PanePatternTest`** — `frameCoversEverySlotForRows3Through6` (length
  rows*9, no null); `frameMatchesTheHandComputedGrids` (the four §2.3 grids
  asserted cell-by-cell); `cornersAlwaysCarryTheAccent`;
  `sideBarsAppearOnlyOnTallScreens` (rows 3–4 have no accent outside
  corners).
- **`LayoutTest`** — `centeredRowMatchesTheRetiredPlaceCenteredMath` (values
  for counts 1..9 at base 9); `contentRowGapsUpToFourThenPacks` (§2.5 table
  verbatim); `contentRowRejectsMoreThanSeven` (throws);
  `galleryGridIsTheThreeSevenWideRows`; `paginationWindowsClampAndCount`
  (itemCount 0, 1, 21, 22, 43 × pageSize 21 — pageCount/from/to/hasPrev/
  hasNext hand-computed).
- **`PaletteTest`** — `everyFamilyHasATheme` (no throw, non-null both
  members); `familyPanesAreUnique`; `familyAccentsAreUnique`;
  `galleryThemesFollowTheirKind`.
- **`SettingsCatalogTest`** — `everyConfiguredFeatureIsNonInfrastructure`;
  `everyPageBelongsToItsFeature`; `everyKnobCarriesRenderableCopy` (label,
  materialName, blurb non-blank); `everyGroupFitsAContentRow` (size ≤ 7,
  1 ≤ groups ≤ 3); `everyWritableKnobKeyRoutesToAKnownRoot` (first segment ∈
  the §4.3 set); `stepperBoundsAreOrderedAndDefaultsSitInside` (min < max;
  the DEFAULTS-snapshot value of every STEP_*/NUMBER knob is within
  [min,max]); `readersResolveAgainstTheDefaultsSnapshot` (every non-POINTER/
  INFO reader returns the kind's expected type, no throw);
  `cycleReadersReturnAListedOption` (every CYCLE knob's reader value on the
  DEFAULTS snapshot is contained in its `options` — pins the §4.3 reader
  rule); `toggleOnlyFeaturesHaveNoPage` (the §4.2 list, exhaustive).
- **`ButtonsTest`** (MODIFY — existing wrap pins untouched) — add
  `backNamesItsDestination` (`Buttons.back("Knockback")` lore reads
  `Return to Knockback.`); `kvJoinsMutedLabelAndAccentValue`;
  `roundKeepsThreeDecimals` (`round(0.12345) == "0.123"`,
  `round(2.0) == "2.0"`).
- **platform `PaneColorTest`** (CREATE at
  `/Users/owengregson/Documents/StrikeSync/platform/src/test/java/me/vexmc/mental/platform/PaneColorTest.java`)
  — `sixteenColoursWithUniqueDataValuesZeroToFifteen`;
  `modernNamesResolveOnTheCompileFloorEnum` (`Material.getMaterial(
  color.modernName()) != null` for all 16 — the 1.17.1 test classpath IS a
  modern enum); `paneBuildsTheModernMaterialHere` (`MenuMaterials.pane(color)
  .getType().name().equals(color.modernName())` and durability 0 — the legacy
  branch is exercised live by BootSuite on real pre-1.13 servers).
- **platform `MenuMaterialsTest`** (MODIFY) — extend
  `renamedIconNamesAliasToTheirPreFlatteningConstant` with the ten §2.1
  additions (exact pairs).
- **`EffectsPresetParserTest`** (MODIFY — additive methods ONLY, existing
  pins untouched) — `indicatorScalarOverridesLayerOverThePreset`
  (`effects.indicators.lifetime-ticks: 60`, `crit-threshold-percent: 40`,
  `roll-hold-ticks: 0` override signature's values field-by-field, other
  fields preset-verbatim); `indicatorScalarOverridesClampLoudly`
  (`lifetime-ticks: 9999` → clamped to 200 with one warn).
- **`DashboardModelTest`** — UNTOUCHED (homeRows, sections, allSurfaced all
  unchanged).

Deleted with their screens: none (no unit test targets the deleted menu
classes).

---

## 10. Docs + release (R9)

### 10.1 `gradle.properties`

`version=2.8.1` → `version=2.9.0-beta`, with the file's customary prose
paragraph above it: *2.9.0-beta is the customization-suite round: the
management GUI redesigned end-to-end — per-family colour identity on woven
glass chrome, one family-screen metaphor, a unified preset gallery for
knockback and effects, and in-GUI settings screens for every overlay-safe
knob (with Q-to-reset). Legacy servers get true coloured panes and ten
repaired icon aliases.*

### 10.2 `.github/release-highlights.md`

First line → `<!-- v2.9.0-beta -->`. Bullets (replace the template's):

```markdown
- The /mental menu is redesigned end-to-end: every family gets its own colour
  identity on woven glass chrome, and every screen speaks one click grammar —
  left-click toggles, right-click configures, Q resets a knob to your file.
- One preset gallery now holds both knockback profiles AND combat-effects
  tunes — legacy/modern is a tab, every tile previews its exact values, and
  one click applies server-wide.
- Nearly every scalar knob is now editable in-game: hit registration, latency
  compensation, the combo solver, fast pots, rods and projectiles, loadout
  rules, indicators, death effects and drop protection — applied atomically,
  no restart.
- On 1.9.4–1.12.2 the menus finally render in full colour: stained-pane
  backgrounds and ten previously-grey icons now resolve to their real
  pre-flattening glyphs.
```

### 10.3 `README.md`

**Replace the `## Management` section body** (between the heading and
`## Configuration`) with:

```markdown
Run **`/mental`** (or `/mtl`) to open the redesigned management suite — it
works on every supported version (full colour down to 1.9.4) and on Folia.
Changes apply atomically the instant you click: no restart, safe mid-combat.

Every screen shares one visual language — each feature family has its own
colour identity on patterned glass chrome — and one click grammar:
**left-click** toggles or applies, **right-click** opens a module's settings,
steppers ride left/right (shift for ×10), and **Q** resets any in-GUI edit
back to your file's value. Read-only tiles are visibly distinct and point at
the exact file and section to edit for the few list-valued options the GUI
deliberately leaves in YAML.

- **The dashboard** — a live status plate (version, platform, scheduling,
  active knockback profile, active effects preset, modules, anticheat
  posture) and one tile per family: Hit Delivery, Knockback, Damage, Combat
  Cadence, Sustain, Loadout, Combo Solver, Potions, Combat Effects, and Loot
  Protection.
- **The preset galleries** — one gallery for knockback feels (legacy/modern
  as a tab, every tile previews its exact engine values) and one for combat
  -effects tunes, both applied server-wide in a click. The active preset
  glows.
- **Settings screens** — every overlay-safe knob is editable in-game, from
  reach validation and latency probes to the combo servo, fast pots, damage
  indicators and drop protection. Edits land in Mental's machine overlay;
  your hand-written YAML is never rewritten.
- **Compatibility** and **Debug** — the anticheat posture as three radio
  tiles, and eleven verbose logging channels you can stream to your own chat.

Prefer YAML? Editing the files by hand still works — the one surviving
command is **`/mental reload`** (the console can't open a menu), mirrored by
the dashboard's reload button.
```

(Keep the existing `> **Knockback is global.**` blockquote after it,
unchanged.)

**Fix the `## Configuration` table row** for `effects.yml`:

```markdown
| `effects.yml` | Which Combat Effects preset applies — `signature` (default), `custom`, or any file you drop into `effects/presets/`. |
```

**Fix the presets pointer** under `## Recommended presets` ("Pick one
in-game…" line):

```markdown
Pick one in-game under **`/mental` → Knockback → Preset Gallery**, or set `knockback.profile` in `knockback.yml`. The full list (`legacy-1.7`, `legacy-1.8`, `kohi`, `minehq`, `badlion`, `velt`, `mmc`, `lunar`, `signature`, `custom`) with provenance is in the [profile guide](docs/knockback-profiles.md).
```

**Fix the Debug flow line** (README line ~167): `/mental → Debug`, "Stream to
my chat" (the tile's new name).

### 10.4 `plugin.yml`

No changes required: the command description, permission comments and PAPI
note reference no renamed screen (the Debug tile and reload button both
survive). Explicitly verified — do not touch the file.

### 10.5 `docs/knockback-profiles.md`

Only the "Runtime control" prose (NOT test-pinned): update the navigation
wording from `/mental → Knockback → formula chooser → preset list` to
`/mental → Knockback → Preset Gallery (legacy/modern tabs)`. No knob keys
change → `KnockbackDocsTest` untouched.

---

## 11. File manifest

CREATE:
- `/Users/owengregson/Documents/StrikeSync/platform/src/main/java/me/vexmc/mental/platform/PaneColor.java`
- `/Users/owengregson/Documents/StrikeSync/platform/src/test/java/me/vexmc/mental/platform/PaneColorTest.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/Palette.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/PanePattern.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/Chrome.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/Layout.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/SettingsCatalog.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/SettingsMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/PresetGalleryMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/test/java/me/vexmc/mental/v5/gui/PanePatternTest.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/test/java/me/vexmc/mental/v5/gui/LayoutTest.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/test/java/me/vexmc/mental/v5/gui/PaletteTest.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/test/java/me/vexmc/mental/v5/gui/SettingsCatalogTest.java`

MODIFY:
- `/Users/owengregson/Documents/StrikeSync/platform/src/main/java/me/vexmc/mental/platform/MenuMaterials.java`
- `/Users/owengregson/Documents/StrikeSync/platform/src/test/java/me/vexmc/mental/platform/MenuMaterialsTest.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/Menu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/Buttons.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/DashboardMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/FamilyMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/CompatibilityMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/DebugMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/config/Overlay.java` (add `has`)
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/config/SnapshotParser.java` (§7.4)
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/MentalPluginV5.java` (add `overlayHas`)
- `/Users/owengregson/Documents/StrikeSync/core/src/test/java/me/vexmc/mental/v5/gui/ButtonsTest.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/test/java/me/vexmc/mental/v5/config/EffectsPresetParserTest.java` (additive methods only)
- `/Users/owengregson/Documents/StrikeSync/tester/src/main/java/me/vexmc/mental/tester/suite/BootSuite.java`
- `/Users/owengregson/Documents/StrikeSync/README.md`
- `/Users/owengregson/Documents/StrikeSync/.github/release-highlights.md`
- `/Users/owengregson/Documents/StrikeSync/gradle.properties`
- `/Users/owengregson/Documents/StrikeSync/docs/knockback-profiles.md` (prose only)

DELETE:
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/KnockbackFormulaMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/ProfileMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/EffectsPresetMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/EffectsMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/HitEffectsMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/DeathEffectsMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/DamageIndicatorsMenu.java`
- `/Users/owengregson/Documents/StrikeSync/core/src/main/java/me/vexmc/mental/v5/gui/LootProtectionMenu.java`

KEPT UNCHANGED (load-bearing): `MeleeFormula.java`, `DashboardModel.java`,
`MenuManager.java`, `MenuContext.java`, `ChatPrompt.java`, `Icon.java`,
`Brand.java`, `TextPort.java`, `Management.java`, `DashboardModelTest.java`.

---

## 12. Ordered implementation steps

Each step must leave `./gradlew build` green (except step 10's tester step,
gated by the matrix).

1. **Platform seam.** Create `PaneColor`; add `MenuMaterials.pane` and the ten
   LEGACY_ALIAS entries; create `PaneColorTest`, extend `MenuMaterialsTest`.
   *Accept:* `./gradlew :platform:test`.
2. **Design-system core.** Create `PanePattern`, `Chrome`, `Layout`,
   `Palette`; wire `Menu.paintChrome` + `fillEmpty`-via-Chrome +
   `placeCentered`→`Layout.centeredRow`. Create `PanePatternTest`,
   `LayoutTest`, `PaletteTest`. *(RED-TEAM: the implementation partition runs
   the preset-backend task FIRST, so `Palette.gallery(PresetKind)` is written
   directly against the landed package — the temporary Family-typed overload
   contingency is DELETED.)* *Accept:* `./gradlew :core:test` (all existing
   GUI tests still green — nothing else changed behavior).
3. **Buttons v2 + override seam — ADDITIVE.** *(RED-TEAM strategy fix so every
   step leaves the build green: the new §2.4 factories are all
   overload-distinct from the old ones — `toggle` gains accent/overridden
   args, `back(String)` coexists with `back()`, etc. — so ADD the new
   vocabulary beside the old factories; the old factories and their last
   callers are deleted together in step 5.)* Add `Overlay.has` +
   `MentalPluginV5.overlayHas`; update `ButtonsTest` additively (wrap pins
   untouched). *Accept:* `./gradlew :core:test`.
4. **SettingsCatalog + SettingsMenu.** Create both classes with all fourteen
   pages of §4.1 exactly (keys, bounds, copy); create `SettingsCatalogTest`;
   apply the §7.4 `SnapshotParser` extension + the two additive
   `EffectsPresetParserTest` methods. *Accept:* `./gradlew :core:test`.
5. **Screen rewrite.** Rewrite `DashboardMenu`, `FamilyMenu`,
   `CompatibilityMenu`, `DebugMenu` to §6; delete the eight retired screens
   AND the now-orphaned old `Buttons` factories (step 3 kept them alive for
   exactly this window). *Accept:* `./gradlew :core:compileJava` then
   `:core:test` (`DashboardModelTest` untouched and green).
6. **Gallery.** Create `PresetGalleryMenu` against `PresetCatalog` (the
   backend plan's §3 — already landed, reconciled signatures in §5; the
   wishlist contingency is retired). *Accept:* `./gradlew :core:test`.
7. **Tester lockstep.** Rewrite the BootSuite GUI case per §8 (incl. the pane
   check). *Accept:* `./gradlew :tester:compileJava` and `./gradlew build`
   (unit gate + japicmp — api untouched — + kernel-Bukkit-free + the four
   mega-jar gates).
8. **Docs + release.** §10: README, release-highlights, gradle.properties,
   knockback-profiles prose. *Accept:* `./gradlew build` (KnockbackDocsTest
   still green), manual read-through of README rendering.
9. **Full gate.** `./gradlew integrationTestMatrix` — every Paper entry
   1.9.4 → 26.1.2 plus Folia; verify the fresh nonce + PASS per entry and
   ZERO D-9 log-scan hits. The 1.9.4/1.12.2 entries are the pane + alias
   proof; the 26.1.2 entry proves the modern pane path never passes data.
10. **Commits.** Conventional commits with prose bodies, one concern each:
    `feat(platform): pane colour seam + legacy icon aliases`,
    `feat(gui): design system (palette, chrome, layout, buttons v2)`,
    `feat(gui): descriptor-driven settings screens`,
    `feat(gui): unified preset gallery`, `feat(gui): dashboard/family/system
    screens on the new chrome`, `test(tester): headless render lockstep`,
    `docs(release): 2.9.0-beta`.

---

## 13. Red-team resolutions (all former open questions RULED — design is final)

1. **PresetCatalog reconciliation: DONE.** §5 is rewritten to the final
   contract (backend plan §3 normative): STATIC catalog — no `MenuContext`
   record change, the BootSuite `MenuContext(mental, Management, ChatPrompt)`
   ctor compile-pin stays byte-identical; `PresetInfo` carries `iconName`
   (the 13+2 icon maps move into the catalog) and boolean `modernFormula`
   (not `MeleeFormula` — no preset→gui dependency); the tab filter is
   `info.modernFormula() == (tab == MeleeFormula.MODERN)`; preview lines are
   catalog-owned and unit-pinned in the backend plan.
2. **`Palette`/`PresetKind` ordering: RESOLVED by task order.** The
   implementation partition runs the preset-backend task before the
   design-system task, so `Palette.gallery(PresetKind)` compiles directly;
   the temporary Family-typed overload is deleted from step 2.
3. **CYCLE case-sensitivity: VERIFIED SAFE.** `ConfigReader.oneOf`
   (`ConfigReader.java:157-177`) uppercases and maps `-`→`_` before
   `Enum.valueOf` — case-insensitive and dash-tolerant for every listed
   option; `target-mode` additionally has its own lowercasing migration
   parser. The §4.3 reader rule (readers return the option exactly as listed)
   keeps the radio-match consistent; `SettingsCatalogTest` pins it.
4. **`max-cps` parse floor: VERIFIED.** `parseHitReg` reads
   `intAtLeast("max-cps", d.maxCps(), 0)` (`SnapshotParser.java:292`) — the
   legal floor is 0, so the GUI's 5..40 clamp can never write a
   warn-triggering value. Likewise re-verified for every §4.1 knob: all GUI
   clamps sit inside their parse contracts, and the four fast-pots clamps
   EQUAL the parser's `numberClamped` ranges (`FastPotsSettings`
   MIN/MAX constants: 0–90, 0.05–1.0, 1.0–5.0, 0–5.0).
5. **Stepper double round-trip: CONFIRMED.** SnakeYAML serializes a boxed
   `Double` as a plain YAML float and `ConfigReader.number` reads any
   `Number`; the 4-decimal rounding rule (§4.3) stands to keep the overlay
   file tidy. The existing `effects.hit.low-health-threshold-percent` stepper
   already writes doubles through this exact path in production.
6. **Hero tile + card right-click both opening the gallery: KEPT.** The hero
   is the discoverable path, the card right-click is grammar-consistency
   (every card with a destination right-clicks into it) — removing either
   would break one of the two rules. Not redundant; no veto.
7. **DebugCategory capacity 14: KEPT, no pagination.** 11 channels today; the
   two content rows hold 14; the mandated code comment names the ceiling so
   the 15th channel is a conscious layout decision, not a silent truncation.
   Pagination for a diagnostics screen is over-engineering — confirmed.

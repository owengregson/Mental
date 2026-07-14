# Mental 2.7.0-beta — kill-title, drop-protection, GUI redo

**Date:** 2026-07-14
**Branch:** `release/2.7.0-beta` off `main`
**Round:** one release, three independently-implementable parts, one matrix gate at the end.

## Motivation

Three owner requests, bundled into one round:

1. A new **death effect**: show a configurable **title + subtitle to the killer**
   on an enemy-player kill, supporting `&` color codes and PlaceholderAPI
   placeholders. The `signature` preset ships specific text.
2. A new **opt-in feature, drop-protection**: a slain player's drops are
   pickup-locked to the killer for a configurable number of seconds, and glow
   gold **only for the killer**. Default OFF, like the other rule features.
3. **GUI reorganization** of the management menus (home screen + the
   death/hit/indicator effects screens), which are currently cramped and
   offer no in-game value editing.

## Invariants this round must preserve

- **Zero-touch**: both new features do nothing to the game when disabled
  (drop-protection default OFF; kill-title empty-string default = no-op even
  when Death Effects is on).
- **Era-exact no-op defaults**: `DeathEffectsSettings.DEFAULTS` with an empty
  `KillTitle` parses byte-identically to today; `parse(empty)` unchanged.
- **Kernel stays Bukkit-free and additive-only** — all new code is in `core`
  (feature units, GUI, config) and `platform` (only if a new capability probe
  is needed). No kernel change is anticipated.
- **Never re-serialize human YAML**: in-GUI value edits write the machine
  overlay (`state/overrides.yml`) only; preset YAML files stay sacred.
- **Neither new feature claims 1.7/1.8 authenticity.** They are explicitly
  opt-in extras that never touch the knockback/hit/technique contract, so the
  `era-accuracy` anti-feature gate is satisfied by construction (default-OFF +
  zero-touch).

---

## Part A — Kill-title death effect

A new *effect within* the existing `death-effects` module (FEEDBACK family),
**not** a new module. Gated by the existing Death Effects toggle; empty text is
a true no-op folded into the module's `hasEffects` predicate.

### A.1 Schema

Add a nested record to `config/settings/DeathEffectsSettings.java`:

```java
public record KillTitle(String title, String subtitle,
                        int fadeIn, int stay, int fadeOut) {
    public static final KillTitle NONE = new KillTitle("", "", 10, 40, 10);
    public boolean present() { return !title.isEmpty() || !subtitle.isEmpty(); }
}
```

`DeathEffectsSettings` gains a `KillTitle killTitle` field; `DEFAULTS` uses
`KillTitle.NONE` (empty text, vanilla 10/40/10 tick timings) ⇒ era-exact no-op.

### A.2 Trigger and recipient

In `feature/feedback/DeathEffectsListener.onDeath` (already resolves
`Player killer = victim.getKiller()` at MONITOR):

- Fire the title only when `killer != null && !killer.equals(victim)` (an
  enemy-player kill) **and** `settings.killTitle().present()`.
- Send the title packets **only to the killer's `User`** — this is a distinct
  audience from the existing 48-block death-effect radius. Wrap in
  `catch (Throwable)` like the existing emit path.
- Journal the decision to `FeedbackTrace` so the tester can assert it.

Fold `killTitle.present()` into the assemble-time `hasEffects` gate so a
title-only `death-effects` section still enables the module but an all-empty
section stays zero-touch.

### A.3 Placeholders

Applied to the **raw `&`-string, before deserialization** (the `IndicatorText`
pattern — codes ride through untouched):

1. Our own tokens: `{NAME}` = victim display name, `{VICTIM}` = alias of
   `{NAME}`, `{KILLER}` = killer display name, `{PROTECT_SECONDS}` = the
   configured drop-protection seconds (empty string when drop-protection is
   disabled — the two features stay decoupled; the admin aligns the literal
   text or uses the token).
2. If PlaceholderAPI is installed: `PlaceholderAPI.setPlaceholders(killer, raw)`
   via a **reflective bridge** behind a capability probe (see A.5). No compile
   dependency; zero-touch when PAPI absent.
3. `LegacyComponentSerializer.legacyAmpersand().deserialize(rendered)` →
   `Component`.

### A.4 Wire (version-gated once at assemble)

`net.kyori` is relocated across the whole shaded jar, so the `Component` produced
above passes straight into the relocated PacketEvents title wrappers.

- **1.17+**: `WrapperPlayServerSetTitleTimes(fadeIn, stay, fadeOut)`,
  `WrapperPlayServerSetTitleText(component)`,
  `WrapperPlayServerSetTitleSubtitle(component)`.
- **< 1.17**: `WrapperPlayServerTitle` with `TitleAction.SET_TIMES_AND_DISPLAY`,
  `TitleAction.SET_TITLE`, `TitleAction.SET_SUBTITLE`.

Resolve the gate once in `DeathEffectsUnit.assemble` (mirroring the existing
1.13/1.19 gates) and bake the chosen send strategy into the listener. Bukkit
`sendTitle` is not usable — it does not exist at the 1.9.4 runtime floor.

### A.5 PlaceholderAPI bridge

- `plugin.yml`: add `softdepend: [PlaceholderAPI]` (the file currently has no
  depend/softdepend).
- A small `text/Placeholders.java` seam: probe
  `Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null` once (cache
  the result), and if present resolve
  `me.clip.placeholderapi.PlaceholderAPI#setPlaceholders(Player, String)` via
  reflection. When absent, return the raw string unchanged. PAPI operates on
  plain strings *before* deserialization, so relocation does not apply.

### A.6 Preset values

`resources/effects/presets/signature.yml` `death-effects:` gains:

```yaml
kill-title:
  title: "&c&lKILLED:&r &f{NAME}&r"
  subtitle: "&c➥&r &7This player's drops are protected for &r&f&n15s&r&7!"
  fade-in: 5
  stay: 40
  fade-out: 10
```

`custom.yml` (a copy of signature, owner-editable) gets the same block. Bump
`SupersededEffectsPresets.ARCHIVED_HASHES` with the current (pre-change) file
hash so unedited installs auto-upgrade.

### A.7 Tests

- `EffectsPresetParserTest.SIGNATURE_DEATH` — extend the expected record with
  the new `KillTitle`.
- `DeathEffectsPacketsTest` — add a pinned title-packet shape (package-private
  static builder, per the existing convention).
- `MigrationsTest` — verify the signature constants still flow through import.
- New unit test for the placeholder render (token substitution + no-PAPI
  passthrough) with hand-computed expectations.

---

## Part B — Drop-protection (new opt-in, default-OFF)

### B.1 Descriptor and settings

- New `Family.LOOT("Loot Protection", "<icon>", ...)` in `feature/Family.java`
  — auto-surfaces in the GUI.
- New `Feature.DROP_PROTECTION("drop-protection", Family.LOOT, "Drop
  Protection", "<blurb>", "<icon>", false, facets, new SettingsKey<>(
  "drop-protection", DropProtectionSettings.class))`. Facets: `serverRule`
  handled, the other three `none(...)`.
- `config/settings/DropProtectionSettings(int seconds, GlowColor glowColor)`
  with `DEFAULTS` (e.g. `seconds=15`, `glowColor=GOLD`). `GlowColor` is a small
  enum `{ GOLD, YELLOW }` (the only two team colors near the requested tint).

### B.2 Capture

`feature/loot/DropProtectionUnit` (`FeatureUnit` + Bukkit `Listener`), settings
read live via `Supplier<Snapshot>` ⇒ `rebuildOnSettingsChange()` stays `false`.

On `PlayerDeathEvent` (region thread) when enabled and `victim.getKiller()` is a
player:

- If `event.getKeepInventory()` or `event.getDrops().isEmpty()` → nothing to do.
- Otherwise **clear `event.getDrops()`** and re-drop each stack via
  `world.dropItemNaturally(deathLoc, stack)`, capturing each returned `Item`
  entity. Deterministic entity handles — matching-by-stack after the fact is
  fragile.
- Record `{ entityId, entityUuid → killerId, expiryTick }` in a concurrent map
  owned by the unit's scope.

Zero-touch: drops are only intercepted when the feature is enabled **and** a
player killer exists. Non-player/environmental deaths and the disabled state are
byte-identical to vanilla.

### B.3 Pickup gate (killer-only)

Cancel the pickup when the item is protected and the picker is not the killer —
including the original victim (owner's decision: killer-only). Cross-version
split, each in its own listener class so the missing type is never referenced on
the wrong server:

- **1.12+**: `EntityPickupItemEvent` (guard `entity instanceof Player`).
- **< 1.12**: `PlayerPickupItemEvent`.

Register the correct one behind `environment.isAtLeast(1, 12, 0)` (the
`EnderPearlCooldownUnit` gate-and-skip idiom, two branches).

### B.4 Per-player gold glow (killer-only)

For each protected `Item` entity, send **only to the killer's `User`**:

- Glowing entity-metadata: base flags byte (index 0) with bit `0x40` set. (Item
  entities carry no other base flags, so a bare `0x40` is safe.) Glowing exists
  since 1.9 — covers the whole runtime range.
- A GOLD team-color packet (`WrapperPlayServerTeams`, create/add the item's UUID
  string as an entry with `NamedTextColor.GOLD`) so the outline is gold.

Everyone else never receives these packets, so they see a plain, un-glowing drop
they cannot pick up. **Constraint:** the glow outline color is a client-side
*named* team color (16 options) — arbitrary RGB "between &e and &6" is not
possible; GOLD is the closest single choice, and the GUI exposes a GOLD/YELLOW
cycle. Cleanup (metadata `0x00` + team-remove, to the killer) fires on expiry /
pickup / despawn.

### B.5 Expiry

A single `Scheduling.repeatGlobal` sweep over the protected map: at each tick
past `expiryTick`, unprotect the entry and de-glow (packet + map work only, no
live-entity access ⇒ Folia-safe). The sweep task and the maps are registered via
`scope.task(() -> ...; return this::teardown)` so disable/reload-off tears
everything down as a unit.

### B.6 Config and tests

- `resources/config.yml`: `drop-protection: false` under `modules:` with a
  commented block.
- New split file `resources/drop-protection.yml` (`seconds`, `glow-color`),
  wired through `ConfigStore` (`DROP_PROTECTION_FILE`, `extractIfMissing`,
  `Sources.dropProtection()`, `loadSources`) and read by a new
  `SnapshotParser.parseDropProtection` (`case DROP_PROTECTION`).
- `FeatureRegistryTest.OPERATOR_CONTRACT_KEYS += "drop-protection"`.
- New unit test for parse defaults + expiry-map math; tester coverage for the
  killer-only pickup gate and the per-player glow packet audience.

---

## Part C — GUI reorganization + in-GUI editing

### C.1 Home reorg (category layer)

Collapse the flat 9-family sprawl into **category** tiles. A `Category` grouping
(lightweight enum or a table in `DashboardModel`) sits above `Family`:

- **Knockback & Delivery** → KNOCKBACK + DELIVERY families (+ the existing
  formula/profile pickers).
- **Combat Rules** → DAMAGE, CADENCE, SUSTAIN, LOADOUT, COMBO, POTS.
- **Combat Effects** → the three effects screens + the preset picker.
- **Loot Protection** → the drop-protection screen.

Plus the status plate and the system row (Compatibility · Debug · Reload ·
Close). Every feature stays reachable; `DashboardModel.allSurfaced()` and
`DashboardModelTest` are updated so the reachability guarantee still holds
through the category layer.

### C.2 Dedicated effects screens

Replace the crammed single FEEDBACK toggle-row with **Hit Effects**, **Death
Effects**, and **Damage Indicators** screens, each showing its module toggle +
editable value rows + a live preview. The Effects Preset picker
(`EffectsPresetMenu`) stays, reached from the Combat Effects hub. Resolve the
naming friction (Feedback / Combat Effects / Hit Effects) toward one consistent
vocabulary in the display strings.

### C.3 In-GUI editing framework

- `gui/ChatPrompt.java`: click an "edit" row → close the menu → register a
  one-shot `AsyncPlayerChatEvent` capture for that player → validate → write the
  overlay → reopen the menu. Thread-hop: global for the config write, region for
  reopen. `AsyncPlayerChatEvent` spans the whole runtime range. A typed
  chat-cancel + timeout guards against a dangling capture.
- Number **steppers** (+/- buttons) and boolean/enum **cycles** (shift-click
  reverses) reuse the existing `Buttons`/`Icon` vocabulary.

**Editable set (bounded on purpose):**

| Screen | Editable in-GUI |
| --- | --- |
| Death Effects | title, subtitle, fade-in/stay/fade-out, lightning, primary death sound |
| Damage Indicators | text, critText, healText, key toggles/numbers |
| Hit Effects | primary sound, low-HP threshold |
| Loot Protection | seconds, glow color |
| (shared) | firework-color hex list editor (small add/remove/clear) |

**Multi-sound / multi-particle lists stay YAML-only** this round (read-only
preview + a note in the screen) to keep the framework shippable.

### C.4 Persistence (invariant-safe)

In-GUI value edits write flat overlay keys to `state/overrides.yml`:
`effects.<module>.<field>` (module ∈ hit/death/indicators) and
`drop-protection.<field>`. The parser layers **overlay-over-preset per field**
right after `effects.deathEffects()` / `hitFeedback()` / `damageIndicators()` in
`SnapshotParser` — each field reads `overlay ?? presetValue`. This keeps the
"never re-serialize human YAML" invariant intact (the overlay is the machine
surface; the preset YAML is untouched).

New typed setters on `manage/Management.java` for each editable field (e.g.
`setDeathKillTitle(String)` → `overlaySet("effects.death.kill-title", v)` →
`reloadAll()`). The two screens that currently bypass `Management`
(`CompatibilityMenu`, `DebugMenu` poke `plugin.overlaySet` directly) are moved
onto typed `Management` methods for consistency.

### C.5 Layout cleanup

A shared "place N centered tiles on row R" helper (in `Menu` or a small
`Layout` util) retires the per-screen ad-hoc centring math and the stale
"eight families / 5 over 3" comment (there are nine).

---

## Cross-cutting

- `plugin.yml`: `softdepend: [PlaceholderAPI]`.
- `gradle.properties`: bump `version=2.7.0-beta`.
- **Gate:** `./gradlew build` (unit + japicmp + kernel-Bukkit-free) then
  `./gradlew integrationTestMatrix`. New unit pins for the title render/packets,
  drop-protection parse + expiry, and the overlay-layering. Tester coverage:
  title send to killer only, killer-only pickup gate, per-player glow audience.
- **Sequencing:** Part A and Part B are independent and can land first (each
  fully testable). Part C depends on both (it surfaces their new settings) and
  lands last. The overlay-layering in C.4 is the one shared architectural change
  the two features' GUIs both consume.

## Open constraints / non-goals

- Glow outline color is limited to 16 named team colors (GOLD chosen). Not a
  bug — a client limitation.
- Multi-sound / multi-particle list editing stays YAML-only this round.
- No back-stack navigation refactor — screens keep hardcoded Back targets
  (rewired for the new tree), consistent with the current model.

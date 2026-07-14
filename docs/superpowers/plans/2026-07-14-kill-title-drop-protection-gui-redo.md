# Kill-title / Drop-protection / GUI-redo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline, batched by part). Steps use `- [ ]` tracking. Full detail lives in the sibling spec `docs/superpowers/specs/2026-07-14-kill-title-drop-protection-gui-redo-design.md`.

**Goal:** Ship Mental 2.7.0-beta: a killer-only kill-title death effect, a default-OFF drop-protection feature with a killer-only gold glow, and a reorganized management GUI with in-GUI value editing.

**Architecture:** Part A folds a `KillTitle` record into the existing `death-effects` module (FEEDBACK) and sends version-gated PacketEvents title wrappers to the killer. Part B adds a new `Feature.DROP_PROTECTION` in a new `Family.LOOT`, capturing drops via clear-and-redrop, gating pickup to the killer, and glowing items per-connection. Part C reorganizes the dashboard into categories, splits the effects screens, and adds a chat-prompt + stepper editing framework persisting to the machine overlay (`state/overrides.yml`), with the parser layering overlay-over-preset per field.

**Tech Stack:** Java 17 API floor (runtime 1.9.4→26.x), shaded+relocated PacketEvents 2.12.1 + Adventure, Gradle, JUnit unit pins + the in-server tester matrix.

## Global Constraints

- **Zero-touch when disabled** — drop-protection default OFF; empty kill-title = no-op even with Death Effects on. (ZeroTouchSuite asserts live.)
- **Era-exact no-op defaults** — `DeathEffectsSettings.DEFAULTS` + `KillTitle.NONE` parse byte-identically to today; `parse(empty)` unchanged.
- **Kernel stays Bukkit-free & additive-only.** All work in `core`/`platform`.
- **Never re-serialize human YAML** — in-GUI edits write `state/overrides.yml` only; preset files stay sacred.
- **Imports always; no inline-qualified names.** Records over classes. Comments explain the *why*/provenance.
- **All entity work via `Scheduling.runOn/repeatGlobal`** (Folia-correct). Netty/packet sends reference only captured immutable data.
- **Cross-version:** gate absent APIs at assemble (probe + register-or-skip); never scatter version conditionals on the hot path. Icons by string name via `MenuMaterials.of`.
- **Gate:** `./gradlew build` then `./gradlew integrationTestMatrix`; the nonce+PASS is the honesty rule.
- **Conventional commits with prose bodies; commit as you go.** Version `2.7.0-beta` in `gradle.properties`.

---

## PART A — Kill-title death effect

### Task A1: `KillTitle` schema + parser

**Files:**
- Modify: `core/.../config/settings/DeathEffectsSettings.java` (add nested `KillTitle` record + field + DEFAULTS)
- Modify: `core/.../config/EffectsPresetParser.java:164-171` (`parseDeathEffects` — parse `kill-title` sub-block)
- Test: `core/src/test/.../config/EffectsPresetParserTest.java` (extend `SIGNATURE_DEATH`), and a new parse-defaults/no-op unit assertion

**Interfaces produced:**
- `DeathEffectsSettings.KillTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut)` with `KillTitle.NONE = new KillTitle("", "", 10, 40, 10)` and `boolean present()`.
- `DeathEffectsSettings(boolean lightning, List<SoundSpec> sounds, List<ParticleSpec> particles, List<Integer> fireworkColors, KillTitle killTitle)`; `DEFAULTS` uses `KillTitle.NONE`.

- [ ] Write failing `EffectsPresetParserTest` expecting a signature `KillTitle` with the exact strings.
- [ ] Add the `KillTitle` record + field to `DeathEffectsSettings`; `DEFAULTS` gets `KillTitle.NONE`.
- [ ] Extend `parseDeathEffects`: `ConfigReader t = reader.sub("kill-title");` then `new KillTitle(t.text("title", d.killTitle().title()), t.text("subtitle", d.killTitle().subtitle()), t.intClamped("fade-in", d.killTitle().fadeIn(), 0, 200), t.intClamped("stay", d.killTitle().stay(), 0, 400), t.intClamped("fade-out", d.killTitle().fadeOut(), 0, 200))`.
- [ ] Run `./gradlew :core:test --tests '*EffectsPresetParserTest*'` → PASS.
- [ ] Commit.

### Task A2: PlaceholderAPI bridge (`text/Placeholders`)

**Files:**
- Create: `core/.../text/Placeholders.java`
- Modify: `core/src/main/resources/plugin.yml` (add `softdepend: [PlaceholderAPI]`)
- Test: `core/src/test/.../text/PlaceholdersTest.java`

**Interfaces produced:**
- `Placeholders.apply(Player viewer, String raw)` → substitutes our tokens (`{NAME}`,`{VICTIM}`,`{KILLER}`,`{PROTECT_SECONDS}` are applied by the *caller* before this) then, if PAPI present, `PlaceholderAPI.setPlaceholders(viewer, raw)` reflectively; returns raw unchanged when PAPI absent.
- Static `boolean available()` (cached probe of `Bukkit.getPluginManager().getPlugin("PlaceholderAPI")`).

- [ ] Write failing `PlaceholdersTest`: with no PAPI, `apply` returns input unchanged (reflection path skipped).
- [ ] Implement the reflective bridge (cache the `Method` handle; swallow reflection failure to raw passthrough with one warn).
- [ ] `plugin.yml` softdepend line.
- [ ] Run the test → PASS. Commit.

### Task A3: Title send in the listener + version gate

**Files:**
- Modify: `core/.../feature/feedback/DeathEffectsUnit.java:57-77` (resolve `boolean splitTitlePackets = serverVersion.isNewerThanOrEquals(ServerVersion.V_1_17)`; pass settings + gate to listener)
- Modify: `core/.../feature/feedback/DeathEffectsListener.java` (fields for `KillTitle` + `splitTitlePackets`; fold `killTitle.present()` into `hasEffects`; new `sendKillTitle(Player killer, Player victim)` branch in `onDeath`; token render → `Placeholders.apply` → `legacyAmpersand().deserialize`; version-gated wrappers)
- Test: `core/src/test/.../feature/feedback/DeathEffectsPacketsTest.java` (pin a title-packet static builder for both eras) + a `KillTitleRenderTest` (token substitution, hand-computed)

**Interfaces consumed:** `KillTitle`, `Placeholders.apply`.
**Interfaces produced:** package-private static `titleTextPacket(...)`, `titleSubtitlePacket(...)`, `titleTimesPacket(...)` (or the legacy combined builder) for the packet pin.

- [ ] Write failing render test: `{NAME}`→victim, `{KILLER}`→killer, `{PROTECT_SECONDS}`→configured value/blank; codes preserved.
- [ ] Implement `sendKillTitle`: guard `killer != null && !killer.equals(victim) && killTitle.present()`; resolve killer `User`; write times+text+subtitle (1.17+) or `WrapperPlayServerTitle` + `TitleAction` (<1.17); `flushPackets`; wrap catch-Throwable; journal via `FeedbackTrace`.
- [ ] Fold `killTitle.present()` into `hasEffects` at construction.
- [ ] Add the packet-shape pins to `DeathEffectsPacketsTest`.
- [ ] `./gradlew :core:test --tests '*DeathEffects*' --tests '*KillTitle*'` → PASS. Commit.

### Task A4: Signature/custom preset values + superseded hash

**Files:**
- Modify: `core/src/main/resources/effects/presets/signature.yml` (`death-effects.kill-title` block with the exact strings + comments)
- Modify: `core/src/main/resources/effects/presets/custom.yml` (same block)
- Modify: `core/.../config/SupersededEffectsPresets.java` (append pre-change signature hash)
- Test: `EffectsPresetParserTest` already pins the parse; add/confirm `SupersededEffectsPresets` self-consistency test passes.

- [ ] Capture the current `signature.yml` SHA-256 (pre-edit) and append to `ARCHIVED_HASHES`.
- [ ] Add the `kill-title:` block to both preset files (exact strings from the spec §A.6).
- [ ] `./gradlew :core:test` (parser + superseded pins) → PASS. Commit.

---

## PART B — Drop-protection (default-OFF)

### Task B1: `Family.LOOT` + `Feature.DROP_PROTECTION` + `DropProtectionSettings`

**Files:**
- Modify: `core/.../feature/Family.java` (add `LOOT("Loot Protection", "CHEST", "...")`)
- Modify: `core/.../feature/Feature.java` (add `DROP_PROTECTION` constant + import)
- Create: `core/.../config/settings/DropProtectionSettings.java`
- Test: `core/src/test/.../feature/FeatureRegistryTest.java` (add `"drop-protection"` to `OPERATOR_CONTRACT_KEYS`)

**Interfaces produced:**
- `DropProtectionSettings(int seconds, GlowColor glowColor)` with nested `enum GlowColor { GOLD, YELLOW }`; `DEFAULTS = new DropProtectionSettings(15, GlowColor.GOLD)`.
- `Feature.DROP_PROTECTION` — yamlKey `"drop-protection"`, `Family.LOOT`, default OFF, facets `serverRule` handled + 3× `none(...)`, `SettingsKey<>("drop-protection", DropProtectionSettings.class)`.

- [ ] Add `"drop-protection"` to `FeatureRegistryTest.OPERATOR_CONTRACT_KEYS` (test fails: no such constant).
- [ ] Add `Family.LOOT`, the `DropProtectionSettings` record, and the `Feature` constant.
- [ ] `./gradlew :core:test --tests '*FeatureRegistryTest*'` → PASS. Commit.

### Task B2: Config file + parser wiring

**Files:**
- Create: `core/src/main/resources/drop-protection.yml` (commented `seconds`, `glow-color`)
- Modify: `core/src/main/resources/config.yml` (`modules.drop-protection: false` + comment)
- Modify: `core/.../config/ConfigStore.java` (`DROP_PROTECTION_FILE`, `extractIfMissing`, `Sources.dropProtection()`, `loadSources`)
- Modify: `core/.../config/SnapshotParser.java` (`case DROP_PROTECTION -> parseDropProtection(...)`, new method)
- Test: `core/src/test/.../config/SnapshotParserTest.java` (drop-protection parse defaults + clamps)

- [ ] Write failing parse test (defaults seconds=15, glow GOLD; out-of-range clamps; enum warn-and-fallback).
- [ ] Wire the file through `ConfigStore` + add `parseDropProtection` (`seconds` via `intAtLeast`/`intClamped`, `glow-color` via `oneOf`).
- [ ] Run the test → PASS. Commit.

### Task B3: Capture + killer-only pickup gate + expiry

**Files:**
- Create: `core/.../feature/loot/DropProtectionUnit.java` (FeatureUnit + death-capture listener + `scope.task` sweep + glow registry)
- Create: `core/.../feature/loot/DropProtectionState.java` (the `entityId/uuid → killerId, expiryTick, glowActive` map; pure, unit-testable)
- Create: `core/.../feature/loot/ModernPickupListener.java` (`EntityPickupItemEvent`, 1.12+)
- Create: `core/.../feature/loot/LegacyPickupListener.java` (`PlayerPickupItemEvent`, <1.12)
- Modify: `core/.../MentalPluginV5.java` (register `DropProtectionUnit` in `registerUnits()`)
- Test: `core/src/test/.../feature/loot/DropProtectionStateTest.java` (expiry math, killer-only predicate)

**Interfaces consumed:** `Scope.listen/task`, `Scheduling.repeatGlobal`, `ServerEnvironment.isAtLeast`, `Supplier<Snapshot>`.
**Interfaces produced:** `DropProtectionState.protect(entityId, uuid, killerId, expiryTick)`, `.mayPickup(entityId, playerId)`, `.expire(nowTick) → List<Expired>`, `.forget(entityId)`.

- [ ] Write failing `DropProtectionStateTest`: killer may pick up, others (incl. victim) may not; `expire` returns entries past their tick and forgets them.
- [ ] Implement `DropProtectionState` (concurrent maps; pure tick math). Test → PASS. Commit.
- [ ] Implement `DropProtectionUnit`: on `PlayerDeathEvent` (enabled + player killer + drops present + not keepInventory) clear `event.getDrops()`, re-drop via `world.dropItemNaturally`, `state.protect(...)`, glow to killer. `scope.task` starts a `repeatGlobal` sweep that expires + de-glows; returns a teardown that cancels + clears + de-glows all. Register the version-correct pickup listener behind `environment.isAtLeast(1,12,0)`.
- [ ] Register the unit in `MentalPluginV5.registerUnits()`.
- [ ] `./gradlew :core:build` → PASS. Commit.

### Task B4: Per-player gold glow (`GlowPackets`)

**Files:**
- Create: `platform` or `core/.../feature/loot/GlowPackets.java` (send glowing metadata `0x40` + GOLD/YELLOW team to one `User`; and the clear packets)
- Test: `core/src/test/.../feature/loot/GlowPacketsTest.java` (metadata flag byte = 0x40; team color mapping)

**Interfaces produced:** `GlowPackets.glow(User user, int entityId, String entityUuid, GlowColor color)`, `GlowPackets.clear(User user, int entityId, String entityUuid)`.

- [ ] Write failing test pinning the base-flags EntityData byte (0x40) and team NamedTextColor for GOLD/YELLOW.
- [ ] Implement with `WrapperPlayServerEntityMetadata` + `WrapperPlayServerTeams` (create/add + remove). Wrap catch-Throwable per send.
- [ ] Wire `GlowPackets` into `DropProtectionUnit` (glow on protect to killer only; clear on expire/pickup).
- [ ] Run the test → PASS. Commit.

---

## PART C — GUI reorg + in-GUI editing

### Task C1: Overlay-layering for effect values (persistence spine)

**Files:**
- Modify: `core/.../config/SnapshotParser.java:89-114,164-191` (after resolving the preset settings, layer overlay `effects.<module>.<field>` overrides per field)
- Modify: `core/.../manage/Management.java` (typed setters: `setDeathKillTitle/Subtitle/…`, `setDropProtectionSeconds/GlowColor`, and `setAnticheatMode`/`setDebug*` to absorb the current bypass pokes)
- Test: `core/src/test/.../config/EffectsOverlayLayeringTest.java` (overlay field wins over preset; absent → preset; parse-empty unchanged)

**Interfaces produced:** `Management.setDeathKillTitle(String)` etc. (each → `plugin.overlaySet("effects.death.kill-title", v)` → `reloadAll()`); layering helper reading `overlay ?? presetValue` per field.

- [ ] Write failing layering test: preset title X + overlay title Y ⇒ effective Y; no overlay ⇒ X.
- [ ] Implement per-field overlay layering for death (title/subtitle/fades/lightning/first sound), indicators (text/critText/healText), hit (first sound, low-hp %), and drop-protection (seconds/glow).
- [ ] Add typed `Management` setters. Run tests → PASS. Commit.

### Task C2: Editing framework — `ChatPrompt`, steppers, cycles, layout helper

**Files:**
- Create: `core/.../gui/ChatPrompt.java` (one-shot `AsyncPlayerChatEvent` capture: close → capture → validate → global write → region reopen; timeout guard)
- Modify: `core/.../gui/MenuManager.java` (register/track active prompts; route the captured chat)
- Modify: `core/.../gui/Buttons.java` (add `stepper(...)`, `cycle(...)`, `editRow(...)`)
- Modify: `core/.../gui/Menu.java` (add `placeCentered(int row, List<...>)` shared layout helper)
- Test: `core/src/test/.../gui/ChatPromptTest.java` (capture lifecycle, cancel/timeout) where headless-testable; otherwise tester coverage

- [ ] Implement `ChatPrompt` + `MenuManager` wiring; `Buttons` steppers/cycles; `Menu.placeCentered`.
- [ ] `./gradlew :core:build` → PASS. Commit.

### Task C3: Dashboard category reorg + dedicated effects/loot screens

**Files:**
- Modify: `core/.../gui/DashboardModel.java` (category grouping over `Family`; keep `allSurfaced()` guarantee)
- Modify: `core/.../gui/DashboardMenu.java` (category tiles + system row; retire stale "8 families" comment; use `placeCentered`)
- Create: `core/.../gui/CategoryMenu.java` (a hub listing a category's families/screens)
- Create: `core/.../gui/HitEffectsMenu.java`, `DeathEffectsMenu.java`, `DamageIndicatorsMenu.java` (toggle + editable rows + preview; wired to `Management` setters + `ChatPrompt`/steppers)
- Create: `core/.../gui/LootProtectionMenu.java` (toggle + seconds stepper + glow cycle)
- Create: `core/.../gui/FireworkColorsMenu.java` (small hex list add/remove/clear)
- Modify: `core/.../gui/FamilyMenu.java` (drop the crammed FEEDBACK special-case; FEEDBACK routes to the Combat Effects hub)
- Modify: `core/.../gui/CompatibilityMenu.java`, `DebugMenu.java` (use typed `Management` setters from C1)
- Test: `core/src/test/.../gui/DashboardModelTest.java` (reachability through the category layer)

- [ ] Update `DashboardModelTest` for the category layer (fails).
- [ ] Implement the category model + `CategoryMenu` + the four dedicated screens + firework-colors editor; rewire Back targets; move Compatibility/Debug onto `Management`.
- [ ] `./gradlew :core:build` → PASS (incl. `DashboardModelTest`, `selfTestIcons` boot self-tests). Commit.

---

## Cross-cutting close-out

### Task X1: Version bump + docs + full gate
- [ ] `gradle.properties`: `version=2.7.0-beta`.
- [ ] Update `docs/` effects/knockback/feature docs if a docs-drift test (`KnockbackDocsTest`-style) covers the new keys; add drop-protection + kill-title docs.
- [ ] `./gradlew build` → BUILD SUCCESSFUL + all unit pins green.
- [ ] `./gradlew integrationTestMatrix` → nonce+PASS across every Paper + Folia entry. Confirm honestly (not the banner).
- [ ] Final commit; offer PR to `main`.

### Tester coverage (folded into X1's matrix)
- Title send reaches the killer only (not the victim/audience) — extend a FeedbackSuite-style check.
- Killer-only pickup gate: killer picks up, a third party is cancelled.
- Per-player glow: the glow metadata packet is addressed to the killer only.

---

## Self-review notes
- **Spec coverage:** A (A1–A4) covers §A; B (B1–B4) covers §B incl. cross-version pickup + per-player glow; C (C1–C3) covers §C incl. overlay persistence, editing framework, reorg. X1 covers cross-cutting (softdepend already in A2, version bump, gate).
- **Sequencing:** A and B independent; C depends on A+B settings and the C1 overlay spine.
- **Type consistency:** `KillTitle`, `DropProtectionSettings`/`GlowColor`, `DropProtectionState`, `GlowPackets`, `Placeholders.apply`, `Management` setters, `Menu.placeCentered` are named identically across tasks.

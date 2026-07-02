# Mental legacy backport — Paper 1.9.4 → 1.16.5 Implementation Plan

> **For agentic workers:** each phase below is dispatched as a self-contained
> brief to an executor agent. Phases are strictly sequential; every phase ends
> with a live-server gate whose evidence is the nonce rule (`PASS nonce=<uuid>`
> cross-checked between the Gradle log and `test-results.txt`). The
> orchestrator (Fable) judges gates and reviews the critical seams; executors
> (Opus) implement. Read `.claude/skills/mental-conventions`,
> `paper-cross-version`, `nms-archaeology`, `live-server-testing`, and
> `matrix-gate` before touching their areas.

**Goal:** extend Mental's supported range from Paper 1.17.1→26.x down to the
terminal patches of 1.9–1.16 (1.9.4, 1.10.2, 1.11.2, 1.12.2, 1.13.2, 1.14.4,
1.15.2, 1.16.5), with the same era-exactness bar and gate honesty the modern
range has, releasing as **2.3.1-beta**.

**Architecture:** no new modules, no redesign. Version variance enters ONLY
through the existing seams: the `PlatformProfile` manifest (probe-or-degrade),
`platform/` resolvers (Attributes/Enchantments/materials), the packet rim
(probe transport), and the tester. The kernel is untouched (its string
vocabulary stays modern; legacy names are normalized ONCE at the platform
seam). Every legacy path is selected by boot-time probing, never by scattered
version conditionals.

**Non-negotiable invariants (all phases):**
- **Zero regression on 1.17+**: the existing 10-entry gate (7 Paper full + Folia
  combat smoke + 2 OCM) must stay green after every phase. Legacy code paths
  must be unreachable on modern servers.
- **Kernel additive-only and version-blind.** No legacy names enter kernel.
- **No silent degradation** (mandate B10): every legacy absence is either a
  loud manifest disable or a documented `OptionalSince`-style fallback.
- **Gate honesty**: a version is "supported" only when a live nonce-verified
  suite passed on it. Until Phase 5 promotes a version to `full`, it is
  supported at `boot` tier only and the docs must say so.
- Java 17+ runtime is a hard install requirement on legacy servers (Mental
  ships Java-17 classfiles). Documented, loud in the boot log if parseable.

**Branch:** `backport/legacy-matrix` (from main @ ca3e934). Conventional
commits with prose bodies, commit per coherent step, executors end commits
with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## Phase 0 — Empirical viability (DONE 2026-07-02)

`docs/superpowers/research/2026-07-02-legacy-boot-viability.md` (commit
3df9ac7) records the verdict: **7 of 8 targets boot on Java 17** (Temurin
17.0.19). 1.9.4/1.10.2/1.11.2/1.12.2 boot with NO flags; 1.13.2/1.15.2/1.16.5
need exactly `-DPaper.IgnoreJavaVersion=true`. Paperclip patching succeeded
on Java 17 for all 8.

**1.14.4 is DROPPED from scope.** Its terminal build (245) hard-caps at
Java 13 in the server's own guard with no `IgnoreJavaVersion` property in
the jar (grep-proven), and the `-Djava.class.version=57.0` override was also
tried and rejected (guard still reports 61.0). Since Mental ships Java-17
classfiles, no 1.14.4 server on a standard build can load it regardless of
what we do — claiming support would be a lie. Documented as a known hole in
the support range.

**The viable set V = {1.9.4, 1.10.2, 1.11.2, 1.12.2, 1.13.2, 1.15.2, 1.16.5}**
(NMS revisions v1_9_R2, v1_10_R1, v1_11_R1, v1_12_R1, v1_13_R2, v1_15_R1,
v1_16_R3). Downloaded jars + patched caches reusable under
`run/legacy-probe/<v>/`. Note: api.papermc.io v2 is SUNSET; builds resolve
via fill.papermc.io v3 — if run-paper 3.0.2 cannot download legacy versions,
point the RunServer task at the cached jars.

## The classload-hazard register (from the 2026-07-02 code inventory)

These fail at CLASS LOAD on legacy, not at probe time. Phase 1 exists to
clear this register; nothing else lands first.

| # | Site | Hazard | Floor |
|---|------|--------|-------|
| H1 | `gui/Icon.java:55,88`, `gui/Menu.java:56,147`, `feature/loadout/OffhandUnit.java:265` | Paper-native Adventure sinks: `ItemMeta#displayName(Component)`, `#lore(List<Component>)`, `Bukkit.createInventory(holder,int,Component)`, `Player#sendMessage(Component)` — methods absent below Paper 1.16.5; `net.kyori` classes absent too | 1.16.5 |
| H2 | `platform/EffectiveMaterial.java:27` | `NamespacedKey.fromString` in a **static initializer** — every damage hit routes through `DamageShaper.java:149` → class-init failure poisons all damage composition | 1.16 |
| H3 | `EffectiveMaterial.java:36-45`, `EphemeralDecoration.java:401-413`, `ProjectileKnockbackUnit.java:163,342` | PersistentDataContainer API | 1.14 |
| H4 | `feature/sustain/GoldenApplesUnit.java:95-114` | `Material.ENCHANTED_GOLDEN_APPLE` enum constant reference → `NoSuchFieldError` pre-flattening | 1.13 |
| H5 | `session/SessionService.java:300` | `Player#getPing()` (Bukkit 1.17) | 1.17 |
| H6 | rim probe (`ProbeRim.java`, `LatencyCompensationUnit.java:115`) | `PacketType.Play.Server.PING`/`Client.PONG` have no wire mapping below 1.17 — PacketEvents will reject/misroute the send | 1.17 |

Quiet-correctness register (no crash, wrong behavior — Phase 3):

| # | Site | Issue |
|---|------|-------|
| Q1 | `kernel DamageTables.weaponDamage(String)` fed by `DamageShaper.java:149` | pre-flattening names (`WOOD_SWORD`, `GOLD_SWORD`, `*_SPADE`) return null → era weapon damage silently off |
| Q2 | `Enchantments.java:76-89` | needs pre-1.13 constant names (already has the two-name probe pattern; verify coverage on 1.9–1.12) |
| Q3 | `GoldenApplesUnit` | pre-1.13 enchanted gapple is `GOLDEN_APPLE` + data 1; keyed `ShapedRecipe` needs 1.12+ (1.9–1.11: legacy ctor) |
| Q4 | `gui` `MenuMaterials` tables | icon names must resolve pre-flattening (fallback STONE exists but menus degrade to stone soup) |
| Q5 | `ServerEnvironment.parse` | verify legacy `Bukkit.getBukkitVersion()` strings parse (e.g. `1.9.4-R0.1-SNAPSHOT`) |

---

## Phase 1 — Substrate: matrix entries + clean boot (clears H1–H5)

**Files:**
- Modify: `support-matrix.json` — add viable legacy entries `{version, jdk,
  platform:"paper", suites:"boot", ci:"release", serverFlags:[...]}` (new
  optional `serverFlags` array, flags from Phase 0); set `"floorApi": "1.13"`.
  Entries stay version-sorted (legacy first → floor derivations and the
  release-notes range update automatically).
- Modify: `core/build.gradle.kts` — `registerIntegrationServer` gains
  `serverFlags: List<String>` (append to the existing `jvmArgs(...)` at
  :170-171) and passes `-Dmental.tester.suites=<entry.suites>` to the server
  JVM; read `suites` off the JSON into `SupportEntry` (it is currently
  parsed but unused).
- Modify: `tester/.../MentalTesterPlugin.java:46-100` — honor
  `mental.tester.suites`: `boot` → BootSuite only; `combat-smoke` and full
  behave as today (Folia/OCM runtime detection unchanged; absent property =
  today's behavior, so modern entries are untouched).
- Create: `core/.../v5/text/TextPort.java` — the single text sink seam:
  `static String legacy(Component c)` via
  `LegacyComponentSerializer.legacySection()`, plus
  `title(Menu)`, `displayName(ItemMeta, Component)`, `lore(ItemMeta,
  List<Component>)`, `send(CommandSender, Component)` — ALL sinks call
  String-API overloads (`setDisplayName`, `setLore`,
  `createInventory(holder,int,String)`, `sendMessage(String)`), which exist
  1.9→26.x. GUI code keeps building Components.
- Modify: `gui/Icon.java`, `gui/Menu.java`, `feature/loadout/OffhandUnit.java`
  — route the five H1 sinks through `TextPort`.
- Modify: `core/build.gradle.kts` + `gradle/libs.versions.toml` — shade
  `adventure-api` + `adventure-text-serializer-legacy` relocated to
  `me.vexmc.mental.lib.adventure` (so `net.kyori` exists pre-1.16.5; on
  modern servers the relocated copy is inert — it never touches Paper's
  native Adventure because no Component crosses a Paper API boundary
  anymore). All `net.kyori` imports in core/platform then resolve to the
  relocated copy via shadow relocation.
- Modify: `platform/EffectiveMaterial.java` — H2/H3: build the key lazily via
  `new NamespacedKey("combat","effective_material")` inside a try/catch
  holder; PDC read behind a boot-probed capability (absent → `resolve`
  returns `item.getType()`); document contract floor 1.14 in
  `docs/compat` note.
- Modify: `EphemeralDecoration`, `ProjectileKnockbackUnit` — H3: PDC
  behind the same platform capability probe; pre-1.14 fallbacks: ephemeral
  shield marker → in-memory per-player set (same lifecycle, no item NBT);
  arrow punch level → in-memory arrowEntityId→level map with entity-death
  eviction (both single-writer on the region thread; no new threading).
- Modify: `GoldenApplesUnit` — H4: replace the enum constant with a
  `MenuMaterials.of("ENCHANTED_GOLDEN_APPLE")`-style name lookup (null-safe
  pre-1.13; the data-value path is Phase 3, this phase only clears the
  classload break).
- Modify: `SessionService.java:300` — H5: new `platform/Pings.java`
  resolver: `Player#getPing()` → `player.spigot().getPing()` →
  NMS `ping` field, first that resolves at boot (MethodHandles, cached).
- Modify: `core/src/main/resources/plugin.yml` — nothing (api-version flows
  from `floorApi`); verify `folia-supported` is ignored on legacy (it is).
- Verify: `ServerEnvironment.parse` against legacy version strings (Q5) —
  unit test with the 8 legacy `Bukkit.getBukkitVersion()` shapes.

**Gate 1:** `./gradlew build` (unit: TextPort round-trip pins, EffectiveMaterial
fallback pins, ServerEnvironment legacy-parse pins) → sequential
`integrationTestMatrix` — now including the legacy entries at boot tier —
ALL entries fresh-nonce PASS, including the pre-existing 10. BootSuite on
legacy asserts: plugin enabled, manifest boot report printed, zero throwables
in log, GUI opens for a real inventory holder (createInventory String path),
`/mental` command responds.

## Phase 2 — Legacy latency probe (clears H6)

**Files:**
- Modify: `core/.../v5/config/ProbeStrategy.java` — add `TRANSACTION`;
  `resolveEffective` becomes version-aware: below 1.17 PING resolves to
  TRANSACTION (loud info line), at/above 1.17 TRANSACTION resolves to PING
  (loud warn). KEEPALIVE still retires loudly to the effective default.
- Create: `core/.../v5/rim/TransactionProbeRim.java` — receive side:
  `WrapperPlayClientWindowConfirmation` (`PacketType.Play.Client.WINDOW_CONFIRMATION`,
  ≤1.16.5 in PacketEvents 2.12.1), match `windowId==0 && accepted` with our
  action-number namespace, map action short → the model's opaque probe id,
  `event.setCancelled(true)` on match (mirror of `ProbeRim.java:47-49`).
- Modify: `LatencyCompensationUnit.java:112-115` — send side behind a tiny
  `ProbeTransport` switch: PING path unchanged; TRANSACTION path sends
  `WrapperPlayServerWindowConfirmation(windowId=0, action=<next short in our
  namespace>, accepted=false)`. Action-number namespace: negative shorts
  descending from -24575 (vanilla uses its own counter per window; keep ours
  disjoint from small positives; collision risk documented and bounded by
  exact-match + 32-outstanding eviction in `LatencyModel`).
- Kernel: **no change** (`LatencyModel` is transport-agnostic, opaque long
  ids — `onProbeSent`/`onResponse` unchanged).
- Registration: `MentalPluginV5` rim registration selects the transport by
  `ServerEnvironment` at boot; exactly one probe rim is registered.

**Gate 2:** unit pins for the id namespace mapping; then live: legacy floor
(lowest viable) + 1.16.5 BootSuite extended with a probe assertion (tester
queries the latency debug seam after joining a fake... note: fake players are
clientless — the probe assertion instead verifies the rim registered the
TRANSACTION listener and a probe send does not throw; wire-level RTT proof
comes with SimpleBoxer/manual later, documented) + modern regression
(1.17.1 + 26.1.2 with PING asserting unchanged behavior) + full matrix.

## Phase 3 — Flattening correctness (clears Q1–Q4)

**Files:**
- Create: `platform/LegacyMaterialNames.java` — `static String modernize(String)`:
  identity on modern servers (guarded by one boot-time flattening check,
  `ServerEnvironment` < 1.13); pre-flattening table for the combat vocabulary
  ONLY: `WOOD_*→WOODEN_*`, `GOLD_*→GOLDEN_*` (tools + armour),
  `*_SPADE→*_SHOVEL`. Hand-pinned unit tests both directions.
- Modify: `feature/damage/DamageShaper.java:149-150` and every
  `EffectiveMaterial.of(x).name()` feed — wrap with
  `LegacyMaterialNames.modernize(...)`. Kernel untouched.
- Modify: `platform/Enchantments.java` — extend the two-name probes to
  legacy-only names where they differ on 1.9–1.12 (verify each against the
  1.12.2 Bukkit jar; expected: current legacy fallbacks already cover most).
- Modify: `GoldenApplesUnit` — pre-1.13 enchanted-gapple detection by
  `GOLDEN_APPLE` + durability 1 (boot-gated branch); recipe: keyed
  `ShapedRecipe` on 1.12+, legacy ctor reflectively on 1.9–1.11; result
  stack `GOLDEN_APPLE:1` pre-1.13.
- Modify: `platform/MenuMaterials.java` — legacy alias table for the GUI
  icon names actually used (enumerate from `gui/` + `DashboardModel`), so
  menus render properly pre-1.13.
- Sweep: re-run the modern-constant audit (`Material.<CONST>` refs from the
  inventory §5) against the 1.12.2 enum; any other pre-1.13-missing constant
  gets the MenuMaterials treatment. (Known-safe: SHIELD, ELYTRA, ENDER_PEARL,
  GOLDEN_APPLE, POTION, GOLD_BLOCK, APPLE, AIR, STONE all exist pre-1.13.)

**Gate 3:** unit pins (LegacyMaterialNames, gapple detection); live: boot-tier
matrix green on all legacy + a new `LegacyRulesSmoke` addition to BootSuite
under `suites=boot` on pre-1.13 entries only: enable DAMAGE family, assert
`DamageShaper` composes era damage for a `WOOD_SWORD`-named stack (direct
unit-style assertion through the Management seam — no fake combat needed
yet); full modern matrix regression.

## Phase 4 — NMS adapters × legacy revisions

Terminal-patch NMS revisions: `v1_9_R2, v1_10_R1, v1_11_R1, v1_12_R1,
v1_13_R2, v1_14_R1, v1_15_R1, v1_16_R3` (restricted to the viable set V).

**Scope by adapter (from the inventory):**
- `SwordBlockAdapter` — Tier system already degrades to NONE below 1.21.
  On legacy the era mechanic is REAL sword blocking below 1.9... which does
  not exist on 1.9+ servers either; the current shield-based
  `EphemeralDecoration` path (SHIELD exists 1.9+) is the mechanism and is
  version-safe. **Expected outcome: no per-revision NMS work; verify the
  ephemeral-shield flow on 1.9.4 and 1.12.2 live.**
- `WeaponTooltipAdapter` — path B (`Material.getItemAttributes` +
  `ItemMeta.setAttributeModifiers`) floors at 1.13.2. Pre-1.13.2: strip the
  attack-speed line via per-revision NMS NBT (`AttributeModifiers` tag on
  the item copy). One new probed path C, ~4 revisions.
- `AttackRangeAdapter` — stays 1.21.5+; below, the feature already falls
  back. **No legacy work.**
- `feature/loadout/HitboxUnit` — inventory gap: enumerate its NMS surface
  first (it predates this plan's inventory); extend per-revision as needed
  for era hitbox reach. Use javap against the Phase-0 downloaded server jars
  (`run/legacy-probe/<v>/`) — never guess (nms-archaeology).
- `tester FakePlayer` — moved to Phase 5.

**Gate 4:** manifest boot report on each legacy version shows the expected
resolution set (BootSuite gains a manifest-assertion: expected
present/absent per version band, driven by a small expectations table in the
tester); tooltip strip verified by item inspection on 1.12.2; full matrix
regression.

## Phase 5 — FakePlayer + era suites on legacy

- `tester/.../fake/FakePlayer.java` — legacy branch: resolve
  `net.minecraft.server.<rev>.*` / `org.bukkit.craftbukkit.<rev>.*`
  (versioned packages; `ReflectionRemapper.noop()` — spigot names ARE the
  runtime names pre-1.17). Per-revision: `EntityPlayer` ctor,
  `PlayerInteractManager`, `NetworkManager(EnumProtocolDirection)`,
  `PlayerConnection`, `PlayerList.a(NetworkManager, EntityPlayer)` (or
  direct-registration fallback — the 1.17-floor fallback at
  `FakePlayer.java:536-578` generalizes), motion fields `motX/motY/motZ`
  for `setMotion`, `attack` via Bukkit `HumanEntity#attack` where present
  (1.15.2+) else NMS `EntityHuman.attack(Entity)`, `doTick`/`playerTick`
  names per revision. javap against the Phase-0 jars.
- Promote entries `boot` → `full` in `support-matrix.json` ONE VERSION AT A
  TIME, newest first (1.16.5 → … → 1.9.4): each promotion = run that
  version's full era suite live, fix what surfaces, only then promote the
  next. Expect server-behavior interference discoveries here (this is where
  the real per-version bugs live; treat each with the era-accuracy bar:
  decompile-cite, don't guess).
- Suites whose mechanics don't exist on a version band get explicit skips
  with reasons in the suite (visible in the tester log), not silent passes.

**Gate 5:** the FULL sequential matrix — all modern + all promoted legacy
entries — fresh-nonce PASS in one run. This is the campaign's core evidence.

## Phase 6 — CI, docs, review, zero-regression proof

- `.github/workflows/build.yml`/`release.yml`: confirm matrix fan-out picks
  up legacy entries (they read support-matrix.json); decide pr-lane: add the
  new floor (lowest viable legacy) to `ci: "pr"` so classload regressions
  surface per-PR; keep the rest release-only. JDK union derivation already
  automatic.
- Release-notes support range (`release.yml` reads `entries[0] → entries[-1]`)
  now reports the legacy floor — verify string.
- Docs: README support matrix + Java-17-runtime requirement for legacy;
  CLAUDE.md range line; `paper-cross-version` + `live-server-testing` skills
  gain the legacy sections (versioned-package reflection, serverFlags,
  suites tiers); compat note for the PDC/effective-material floor (1.14) and
  anything else OptionalSince'd.
- japicmp: expected clean (no `api/` change anywhere in this plan). Verify.
- **Sparing Fable review** (orchestrator, not an agent): TextPort seam,
  EffectiveMaterial/PDC degradation, TransactionProbe id namespace,
  LegacyMaterialNames table, FakePlayer legacy reflection, the
  suites-tier plumbing, and a B-register pass: no new wall-clock windows, no
  cross-thread reads, no silent caps introduced by any phase.
- Final full gate: everything, fresh nonces.

## Release — 2.3.1-beta

`gradle.properties` → `2.3.1-beta`; PR `backport/legacy-matrix` → main with
the support-range change front and center; merge on green; auto-release
publishes the pre-release (ancestry baseline v2.3.0-beta, prerelease flag
already handled by the releaser). Verify tag/assets/notes as for 2.3.0-beta.

---

## Execution protocol

- One Opus executor per phase (fresh context), briefed with: this plan's
  phase section verbatim, the relevant inventory excerpts, the invariants
  block, and the gate definition. Executors run gates themselves and return
  RAW evidence (nonce lines + failure output); the orchestrator judges.
- Executors commit as they go on `backport/legacy-matrix`; no pushes until
  the phase gate is green (then push).
- Any discovery that contradicts this plan: stop, report, orchestrator
  amends the plan doc (append a dated amendment), then continue.
- The matrix is SEQUENTIAL on this machine (one port). Never run two gates
  concurrently. A full matrix run will grow toward ~20 minutes as entries
  are promoted; budget accordingly.

## Decision log

- 2026-07-02: Terminal patches only (8 revisions, not 12) — halves NMS
  surface; non-terminal patches of a major are not supported.
- 2026-07-02: Text strategy = Components internally, legacy-string sinks
  everywhere via TextPort + relocated adventure-api. One code path for all
  versions; zero Paper-native Adventure calls remain in core.
- 2026-07-02: Kernel vocabulary stays modern; `LegacyMaterialNames.modernize`
  at the platform seam is the only translation point.
- 2026-07-02: Pre-1.14 PDC fallbacks are in-memory per-session state (region
  thread single-writer), not item NBT via NMS — smaller surface, same
  lifecycle guarantees, loses only cross-restart persistence of ephemeral
  markers (which are ephemeral by definition).
- 2026-07-02: Legacy latency probe = window-confirmation transactions with a
  disjoint action-number namespace; KEEPALIVE stays retired.

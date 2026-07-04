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
- Class-file **compile** target: `options.release = 17`. The SHIPPED jar is then
  a Multi-Release mega-jar — downgraded to class v52 (Java 8) as the base tree,
  the original v61 kept under `META-INF/versions/17` — so it classloads on any
  JVM from Java 8 up. Each server runs its **native-era Java** (per-entry `jdk`
  in `support-matrix.json`); the integration build foojay-provisions every
  toolchain automatically.
- `api-version: '1.17'` in plugin.yml; one universal jar serves the whole range.
- **Runtime floor is Paper 1.9.4** (legacy backport, below the 1.17.1 compile
  floor). The full range 1.9.4 → 26.x ships as that ONE mega-jar with no server
  flags and no holes (**1.14.4 included**). See the legacy tier below.

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
  single source — version, jdk (its native-era Java, the newest it boots
  flagless), platform, suites, ci, bytecodeTier); the build task, both
  workflows, the local script, and the release-notes range all derive from it.
  `serverFlags` is DEAD — no legacy entry needs `IgnoreJavaVersion` anymore.
  Expect the gradle task to download/cache the paperclip jar that
  `scripts/integration-matrix.sh` then reuses.

## The legacy backport tier (1.9.4–1.16.5) — runtime floor below the compile floor

`core` compiles against the 1.17.1 API but RUNS down to Paper 1.9.4. Because core
compiles against 1.17.1, everything COMPILES and any sub-floor absence surfaces at
RUNTIME as `NoSuchMethodError` / `NoSuchFieldError` / `NoClassDefFoundError` —
legacy support is entirely a runtime-resolution problem, decided ONCE at boot.
Classloading itself is solved by the **Multi-Release mega-jar** (see the next
section): its v52 base tree loads on any JVM from Java 8 up, so each legacy
server runs its native-era Java and **1.14.4 is a full-tier entry, not a hole**
— its old impossibility was purely that the Java-17 classfiles could not load
under its Java-13 cap; the v52 base fixes exactly that.

- **Presence-probing idiom, never version parsing.** Every legacy path is selected
  by probing for the class/method/field itself (MethodHandles lookup,
  `Class.forName`, enum-constant lookup) at boot and caching the resolved handle —
  the same "try modern, fall back, degrade loud" pattern as `Attributes` /
  `Enchantments`. A probe that misses prints ONE loud boot line (no silent
  degradation — mandate B10) and installs the era-intent fallback. The manifest
  boot report is the ground truth of what resolved present/absent per version.
- **The listener-descriptor hazard (2.4.1 GAP 1).** No sub-floor Bukkit type may
  appear in ANY method/field descriptor of a class handed to `registerEvents`.
  Bukkit's `createRegisteredListeners` reflects over EVERY declared method of the
  listener class; resolving a descriptor whose parameter/return type is absent on
  this server throws `NoClassDefFoundError`, which Bukkit swallows into a single
  SEVERE line ("has failed to register events for class …") and registers ZERO
  handlers — every handler in the class dies while the plugin keeps running and
  the rest of the feature looks alive. The Bukkit-listener analog of the D-8
  descriptor rule. The story: `GoldenApplesUnit`'s private `nappleKey()` returned
  `NamespacedKey` (lands 1.12), so `onConsume` never registered on 1.9.4–1.11.2
  while the recipe half (a `scope.task`, no reflection) kept working. Remedy:
  hoist every sub-floor-typed symbol into a small NON-Listener helper
  (`NappleKeyed`) instantiated only behind the platform resolver (`Recipes`);
  merely LOADING the helper — which descriptor resolution does on every version —
  links nothing sub-floor, because constant-pool entries resolve lazily. Body-only
  references (guarded calls, caught `X.class` literals) are safe; descriptors are
  not.
- **The companion trap: the per-event bus swallow (2.4.1 GAP 2).** A handler that
  throws is logged per event ("Could not pass event … to Mental") and the server
  keeps going. A direct getstatic of a sub-floor enum constant
  (`ENTITY_SWEEP_ATTACK`, lands 1.11) is a STICKY `NoSuchFieldError`: the failed
  constant-pool entry rethrows on EVERY subsequent execution — here, on every
  `EntityDamageEvent` of any cause. Resolve enum constants ONCE at boot via
  `Enum.valueOf` in try/catch (platform `SweepCauses`, never a getstatic), and
  where absent do not register the listener at all — the skip is decided once at
  assemble with a printed degrade line (`SweepUnit`/`AttackCooldownUnit`). Since
  2.4.1 the gate SCANS the captured console log for both signatures plus any
  mental-framed linkage error (D-9, `checkIntegrationTest` +
  `scripts/integration-matrix.sh`): a PASS with either is structurally a FAIL, so
  neither trap can ride a green matrix again.
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

## The Multi-Release mega-jar (classloading from Java 8 up)

The shipped `Mental-<version>.jar` (and the tester jar) is a **Multi-Release
mega-jar** produced at the ARTIFACT level — NO source file changes for the
downgrade (kernel stays pure-JDK/additive). Pipeline (`core/build.gradle.kts`,
via the `xyz.wagyourtail.jvmdowngrader` 1.3.6 plugin): `shadowJar` → jvmdg
`DowngradeJar` (class-v52 base + `multiReleaseOriginal` keeps the original v61
per-class under `META-INF/versions/17`) → jvmdg `ShadeJar` (relocates jvmdg's
own runtime under a **distinct per-plugin prefix** — `me/vexmc/mental/lib/jvmdg`
for core, `…/tester/lib/jvmdg` for the tester) → the canonical
`Mental-<version>.jar` (the ONLY `Mental-*.jar` in `core/build/libs/`;
intermediates live under `build/jvmdg-stage/`). A JVM reads v61 only when its own
feature version is ≥ 17; below that it reads the v52 base. Each entry declares
its `bytecodeTier` and the tester asserts the ACTUALLY-loaded major — which tree
loaded is a live FACT, never assumed.

Four gates run in `check` (every `./gradlew build`), each guarding a jvmdg
hazard class:

- **`verifyJdk8Api`** — jvmdg silently PASSES THROUGH un-shimmable JDK-9+
  `java.*` APIs it has no stub for; they downgrade green and `NoSuchMethodError`
  on a real Java 8 JVM (the fast-path blind spot — paths clientless suites never
  drive). This ASM member-level scan validates the v52 base tree against a real
  JDK-8 `rt.jar`; the allowlist starts EMPTY. It caught the jvmdg 1.3.6
  record-`toString` bug (a synthesized `StringBuilder.append(short)` where Java 8
  has only `append(int)` — the `ValvePayload` record, fixed with an explicit
  int-widening `toString`).
- **`verifyDowngrade`** — base tree ≤ v52; `versions/17` first-party classes
  exactly v61; `Multi-Release: true`; the sentinel forked; greps the shaded tree
  for reflective record introspection (`isRecord`/`RecordComponent`), since
  downgraded records are NOT reflective records.
- **`verifyRelocation`** — zero un-relocated `net/kyori` OR `xyz/wagyourtail`
  outside their relocated prefixes, in BOTH trees.
- **`verifyTesterIsolation`** (D-8) — the tester jar's constant pool holds ZERO
  references to core's `me/vexmc/mental/lib/jvmdg` prefix.

**D-8 — cross-plugin stub isolation.** jvmdg prunes its shaded runtime to each
jar's referenced arities AND rewrites Java-9+ types INSIDE method descriptors —
so two downgraded plugins must NEVER share a stub-typed API descriptor or a
same-FQN pruned runtime (both reproduced live: a `J_U_List.of(7-arg)`
`NoSuchMethodError` via Bukkit's shared class cache, and a `RandomGenerator`-stub
`NoClassDefFoundError` baked into the kernel's `computeBase` descriptor as called
by the tester). Fix = distinct shade prefixes (above) PLUS a Java-8-native
additive overload `computeBase(…, java.util.Random)` in the kernel so no stub
type crosses a plugin boundary. **Rule: no post-Java-8 JDK type in any
cross-plugin API descriptor.** Full campaign:
`docs/superpowers/plans/2026-07-03-mental-full-range.md`.

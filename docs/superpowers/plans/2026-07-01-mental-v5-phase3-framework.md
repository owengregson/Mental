# Mental v5 Phase 3 — Feature Framework, Config, Arbiter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The enumerable feature framework (descriptors + transactional scopes +
reconciler), the descriptor-keyed config snapshot with the machine-write overlay and
version-chain migration, and the total mechanic-token coexistence arbiter — all
unit-proven, still unwired into the live plugin (Phase 4 wires).

**Architecture:** `MechanicToken` and the arbiter's decision core are kernel (pure,
era-domain vocabulary; `HitContext` gains the verdict set); everything Bukkit-shaped
(descriptors, scopes, YAML parsing, overlay, reflective OCM binding) is core under
`me.vexmc.mental.v5.*`. The OLD config surface is the knob contract: every key,
default, and warn-and-fallback behavior of the current parsers carries over
knob-for-knob, proven by porting the existing `MentalConfigTest`/`KnockbackProfilesTest`
pins.

**Tech Stack:** as Phase 2. YAML parsing uses `org.bukkit.configuration.file.YamlConfiguration`
(usable in plain unit tests — the old config tests already do this, mirror their
technique).

## Global Constraints

- Kernel stays dependency-free. No wall-clock in correctness paths.
- New code ONLY under `kernel/` and `core/src/{main,test}/java/me/vexmc/mental/v5/`
  plus `core/src/test/resources/v5/` for YAML fixtures. Never modify other existing
  files. Gate: `./gradlew build` green (old suites untouched).
- Ported pins sacred (stop-and-report, never adjust). The module-id STRINGS in
  config.yml must remain exactly the current ones (no operator churn) — copy them
  from `MentalConfig.reload`'s `modules.flag(...)` calls.
- Conventional commits with prose bodies + trailer
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`, one per task.

---

### Task 3.0: Kernel — MechanicToken + ArbiterCore + startup-warning logic

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/coexist/MechanicToken.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/coexist/Decider.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/coexist/ArbiterCore.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/coexist/CoexistWarnings.java`
- Test: `kernel/src/test/java/me/vexmc/mental/kernel/coexist/{ArbiterCoreTest,CoexistWarningsTest}.java`

**Interfaces (produced):**

```java
/** Which party's modeset decides ownership (mandate §4.11 — fixed table). */
public enum Decider { ATTACKER, RODDER, VICTIM, ALWAYS_MENTAL }

/**
 * The TOTAL set of era mechanics Mental can restore. ocmKey is OCM's config module
 * name for the same mechanic (null when OCM has no equivalent); the historical six
 * are the only BOUND/CONFIG-arbitrated ones — the rest are Mental-owned,
 * peer-detected (double-enable warns, never yields).
 */
public enum MechanicToken {
    MELEE_KNOCKBACK("old-player-knockback", Decider.ATTACKER, true),
    FISHING_KNOCKBACK("old-fishing-knockback", Decider.RODDER, true),
    FISHING_ROD_VELOCITY("fishing-rod-velocity", Decider.RODDER, true),
    PROJECTILE_KNOCKBACK("projectile-knockback", Decider.VICTIM, true),
    TOOL_DAMAGE("old-tool-damage", Decider.ATTACKER, true),
    CRITICAL_HITS("old-critical-hits", Decider.ATTACKER, true),
    ARROW_KNOCKBACK(null, Decider.ALWAYS_MENTAL, false),
    ATTACK_COOLDOWN("disable-attack-cooldown", Decider.ATTACKER, false),
    ATTACK_SOUNDS("old-attack-sounds", Decider.ATTACKER, false),
    SWEEP("disable-sweep", Decider.ATTACKER, false),
    CRAFTING("disable-crafting", Decider.ATTACKER, false),
    OFFHAND("disable-offhand", Decider.ATTACKER, false),
    GOLDEN_APPLES("old-golden-apples", Decider.VICTIM, false),
    ENDER_PEARL_COOLDOWN("disable-enderpearl-cooldown", Decider.ATTACKER, false),
    REGEN("old-player-regen", Decider.VICTIM, false),
    ARMOUR_STRENGTH("old-armour-strength", Decider.VICTIM, false),
    ARMOUR_DURABILITY("old-armour-durability", Decider.VICTIM, false),
    POTION_DURATIONS("old-potion-effects", Decider.VICTIM, false),
    POTION_VALUES("old-potion-effects", Decider.VICTIM, false),
    TOOL_DURABILITY("old-tool-durability", Decider.ATTACKER, false),
    SWORD_BLOCKING("sword-blocking", Decider.VICTIM, false),
    HITBOX("old-hitboxes", Decider.ATTACKER, false);
    // fields: String ocmKey (nullable), Decider decider, boolean arbitrated
}
```

**IMPORTANT — verify the ocmKey strings:** before finalizing the enum, check the six
arbitrated names against `core/src/main/java/me/vexmc/mental/module/ocm/OcmMechanic.java`
(they must match exactly) and the non-arbitrated names against OCM's module keys as
referenced in `docs/superpowers/plans/2026-06-14-ocm-ground-truth.md` — where the doc
names a different OCM module key than my guess above (e.g. sounds/sweep/cooldown
keys), the DOC wins; where OCM has no equivalent module, use null. List every
correction in the commit body.

```java
/** Pure ownership resolution. BOUND per-player answers come in as a resolver fn. */
public final class ArbiterCore {
    public enum Mode { ABSENT, BOUND, CONFIG }
    public interface BoundResolver { boolean ocmEnabledFor(java.util.UUID decider, String ocmKey); }
    public ArbiterCore(Mode mode, java.util.Set<MechanicToken> knownToOcm,
                       java.util.Set<MechanicToken> staticVerdicts, BoundResolver resolver) {}
    /** True when MENTAL owns the mechanic for this decider (null decider => static). */
    public boolean mentalOwns(MechanicToken token, java.util.UUID decider) { ... }
}

/** Pure startup-warning derivation (mandate §4.11). */
public final class CoexistWarnings {
    public record OcmFacts(boolean present, boolean oldPlayerKnockbackInDefaultModeset,
                           Integer playerDelay, java.util.Set<String> enabledModuleKeys) {}
    /** @return human-readable warnings: the two feel-burying defaults + one line per
     *  double-enabled token (mentalEnabled contains it AND facts.enabledModuleKeys
     *  contains its ocmKey). */
    public static java.util.List<String> derive(OcmFacts facts,
                                                java.util.Set<MechanicToken> mentalEnabled) { ... }
}
```

**Semantics (tests):** port every behavioral pin from the old `OcmGate` tests (find
them: `grep -rl OcmGate core/src/test`) onto ArbiterCore — ABSENT ⇒ Mental owns all;
BOUND consults the resolver only for `knownToOcm` arbitrated tokens; CONFIG uses
static verdicts (conservative: verdict present ⇒ OCM owns); non-arbitrated tokens
are ALWAYS Mental-owned regardless of mode (they only warn). CoexistWarnings: the
playerDelay warning fires on any value ≠ 20 (including the default 18), the modeset
warning on the flag, one double-enable line per overlapping token, empty list when
OCM absent.

- [ ] Tests → fail → implement → PASS → commit
  `feat(kernel): total mechanic-token arbiter core and coexistence warnings`.

### Task 3.1: Core — Scope + registrations (transactional lifecycle)

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/Scope.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/Registrar.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/feature/ScopeTest.java`

**Interfaces (produced):**

```java
/** The seam Scope registers through — production impl wires Bukkit/PE/Scheduling in
 *  Phase 4; tests stub it. Every method returns an AutoCloseable that undoes it. */
public interface Registrar {
    AutoCloseable bukkit(Object listener);
    AutoCloseable packets(Object peListener);
    AutoCloseable task(java.util.function.Supplier<AutoCloseable> starter);
    /** Rule registration is TOKEN-GATED at the framework level (B14): the returned
     *  gate is consulted before every handler run. */
    AutoCloseable rule(MechanicToken token, Runnable handlerRegistration);
}

/** All resources a feature acquires; closed as a unit on any exit (B8/R6). */
public final class Scope implements AutoCloseable {
    public Scope(Registrar registrar) {}
    public void listen(Object bukkitListener) {}
    public void packets(Object peListener) {}
    public void task(java.util.function.Supplier<AutoCloseable> starter) {}
    public void rule(MechanicToken token, Runnable handlerRegistration) {}
    /** Close every registration in reverse order; a throw in one close never skips
     *  the rest (suppressed exceptions collected, one summary thrown at the end). */
    @Override public void close() {}
}
```

**Semantics (tests, all with a recording stub Registrar):**
1. close() closes in reverse registration order.
2. A throwing close is isolated: remaining closes still run; suppressed collected.
3. Partial-enable: registrations 1,2 succeed, 3 throws during acquisition ⇒ caller
   (the reconciler, 3.6) closes the scope: 1,2 closed, nothing leaks, the throw
   propagates.
4. Double-close is a no-op.
5. `rule` without a token is unrepresentable (compile-time: there is no overload).

- [ ] Tests → fail → implement → PASS → commit
  `feat(core): the transactional feature scope`.

### Task 3.2: Core — FeatureDescriptor registry

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/Feature.java` (the enum)
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/Family.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/Facets.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/SettingsKey.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/feature/FeatureRegistryTest.java`

**Interfaces (produced):**

```java
public enum Family { DELIVERY, KNOCKBACK, DAMAGE, CADENCE, SUSTAIN, LOADOUT }

/** Per-facet declaration: HANDLED in Phase 4, or NONE with a why. (B5) */
public record Facets(Facet serverRule, Facet clientPresentation,
                     Facet fastPathDamage, Facet vanillaPathDamage) {
    public sealed interface Facet {
        record Handled() implements Facet {}
        record None(String why) implements Facet {}
    }
}

/** Typed settings identity — one per feature; the snapshot map key. */
public final class SettingsKey<S> { /* name + Class<S>; identity-based */ }
```

`Feature` — one constant per feature, fields:
`String yamlKey` (EXACTLY the current `modules.*` strings — copy from
`MentalConfig.reload`), `Family family`, `String displayName`, `String blurb`,
`String iconName` (copy display metadata from `gui/menu/Catalog.java`),
`boolean defaultEnabled` (engine features true, rule features false — copy the
current defaults), `Set<MechanicToken> tokens`, `Facets facets`,
`SettingsKey<?> settingsKey`. The 23 constants and their families:

- DELIVERY: HIT_REGISTRATION, WTAP_REGISTRATION, ANTICHEAT_COMPAT, OCM_COMPAT
- KNOCKBACK: KNOCKBACK, LATENCY_COMPENSATION, FISHING_KNOCKBACK, ROD_VELOCITY,
  PROJECTILE_KNOCKBACK
- DAMAGE: ARMOUR_STRENGTH, ARMOUR_DURABILITY, CRIT_FALLBACK, TOOL_DURABILITY,
  SWORD_BLOCKING
- CADENCE: ATTACK_COOLDOWN, ATTACK_SOUNDS, SWEEP
- SUSTAIN: GOLDEN_APPLES, ENDER_PEARL_COOLDOWN, REGEN, POTION_DURATIONS,
  POTION_VALUES
- LOADOUT: CRAFTING, OFFHAND, HITBOX

(That is 24 — ANTICHEAT_COMPAT and OCM_COMPAT are always-on infra descriptors with
`defaultEnabled=true` and empty facets; their yamlKeys don't exist in `modules.*` —
give them `yamlKey=null` and exclude them from the parser loop.)

**Semantics (tests):**
1. yamlKeys unique and non-null for all non-infra features; assert the exact string
   set equals the `modules.flag` set mined from the old `MentalConfig` source (write
   the expected set literally in the test — it is the operator contract).
2. Every feature that declares a token whose `ocmKey != null` has facets fully
   declared (no accidental default).
3. Every mechanic-altering feature (non-infra) declares all four facets explicitly
   — enumeration test, fails on a new constant with missing declarations.
4. SettingsKey identity: two features never share a key.

- [ ] Tests → fail → implement → PASS → commit
  `feat(core): the enumerable feature descriptor registry`.

### Task 3.3: Core — section parsers + the typed Snapshot

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/config/ConfigReader.java` (port the
  old `core/.../config/ConfigReader.java` warn-and-fallback reader verbatim —
  package change only)
- Create: `core/src/main/java/me/vexmc/mental/v5/config/Snapshot.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/config/SnapshotParser.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/config/ProfileParser.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/config/{SnapshotTest,ProfileParserTest}.java`

**Interfaces (produced):**

```java
/** Immutable; built whole, swapped by reference. NO positional mega-constructor —
 *  built via a builder the parser owns; reads are typed-key lookups. */
public final class Snapshot {
    public boolean enabled(Feature f) { ... }              // modules.* toggle
    public <S> S settings(SettingsKey<S> key) { ... }      // never null: defaults guaranteed
    public KnockbackProfile profileFor(String worldName) { ... } // per-world -> default (global model)
    public AnticheatSettings anticheat() { ... }
    public DebugSettings debug() { ... }
    public OcmCoordination ocmCoordination() { ... }
}

public final class SnapshotParser {
    /** Parses main+knockback+hitreg+compensation YAML (post-overlay), returning the
     *  snapshot plus warn-and-fallback issues. parse(empty) == full defaults. */
    public record Result(Snapshot snapshot, java.util.List<String> issues) {}
    public static Result parse(org.bukkit.configuration.Configuration main,
                               org.bukkit.configuration.Configuration knockback,
                               org.bukkit.configuration.Configuration hitReg,
                               org.bukkit.configuration.Configuration compensation,
                               java.util.Map<String, org.bukkit.configuration.Configuration> profiles) { ... }
}
```

**Settings records:** port each old settings record's FIELDS and DEFAULTS
knob-for-knob (source: the old `config/*Settings` records referenced by
`MentalConfig`) into per-feature records under `v5/config/settings/`, registered via
each Feature's `SettingsKey`. Do not redesign knob names or defaults — the YAML
surface is frozen.

**ProfileParser:** re-attach the old `KnockbackProfile.parse(ConfigReader)` logic to
the kernel schema (the parsing half left behind in Phase 1). Port the WHOLE of the
old parse-behavior tests: `parse(empty) == KnockbackProfile.LEGACY_17` (the
era-exact no-op pin), warn-and-fallback per knob, and the bundled-preset value pins
— parse each preset YAML from `core/src/main/resources/profiles/` (read the REAL
resource files) and assert equality against the Phase 1 `Presets` kernel constants.
Also port the old `KnockbackProfilesTest` selection cases (per-world map → server
default; unknown profile falls back with one warning) onto `Snapshot.profileFor`.

**Semantics (tests):**
1. `SnapshotParser.parse(empty, empty, empty, empty, {})` — every feature at its
   default enablement; every settings record equals its DEFAULTS; zero issues for
   absent sections; `profileFor(anything) == LEGACY_17`.
2. Wrong-typed knob ⇒ exactly one named issue + fallback (port the old
   MentalConfigTest warn-behavior pins).
3. Snapshot immutability: no setter surface; parser returns a fresh instance.

- [ ] Tests → fail → implement → PASS → commit
  `feat(core): typed descriptor-keyed snapshot with the frozen knob surface`.

### Task 3.4: Core — ConfigStore: files, preset extraction, the overlay

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/config/ConfigStore.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/config/Overlay.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/config/{ConfigStoreTest,OverlayTest}.java`
  (JUnit `@TempDir` fixtures)

**Contracts:**
- `ConfigStore(Path dataDir)`: `ensureDefaultFiles()` extracts bundled
  config.yml/knockback.yml/hit-registration.yml/latency-compensation.yml and
  `profiles/*.yml` ONLY when missing (owner edits sacred). Port the
  `SupersededPresets` upgrade-in-place semantics: a preset file value-equal to a
  superseded bundled revision is replaced by the current bundle; any other
  difference freezes the file (port the old tests' pins — find them via
  `grep -rl SupersededPresets core/src/test`).
- `Overlay(Path stateFile)`: flat `String key -> Object` machine-owned YAML at
  `state/overrides.yml`. `apply(Configuration main, Configuration knockback, ...)`
  sets each override onto the matching in-memory Configuration BEFORE
  SnapshotParser.parse (the human files on disk are never rewritten);
  `set(key, value)`/`remove(key)` persist the overlay file only. Round-trip test:
  set `modules.knockback=false` + `knockback.profile=kohi`, apply over parsed real
  bundled YAML, assert the snapshot reflects both AND the main YAML files on disk
  are byte-identical to their bundled originals.

- [ ] Tests → fail → implement → PASS → commit
  `feat(core): config store with sacred human files and a machine overlay`.

### Task 3.5: Core — migration chain v1→v2→v3

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/config/Migrations.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/config/MigrationsTest.java`
  (fixtures under `core/src/test/resources/v5/migration/`)

**Contracts:** explicit chain keyed on `config-version` in config.yml (absent = 1
when the file matches the v1 single-file shape, else current). Steps:
`1→2` — replicate the OLD `ConfigStore.migrateLegacyLayout` behavior (read its
source; backup + split + tuned-knockback → `profiles/custom.yml` + select it);
`2→3` — create empty `state/overrides.yml`, stamp `config-version: 3`; no key
renames. Each step: backup first (`config-backup-v<N>/`), idempotent (running on an
already-migrated tree is a no-op), and `config-version` is now READ (fixing the
mandate §2.7 "written but never read"). Fixtures: a v1 single-file tree, a v2 tree
(copy the current bundled layout), a v3 tree.

- [ ] Tests (per step: shape before/after, backup exists, idempotence) → fail →
  implement → PASS → commit `feat(core): explicit config-version migration chain`.

### Task 3.6: Core — Reconciler + zero-touch + arbiter binding

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/Reconciler.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/FeatureUnit.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/coexist/OcmBinding.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/feature/ReconcilerTest.java`,
  `core/src/test/java/me/vexmc/mental/v5/coexist/OcmBindingTest.java`

**Interfaces (produced):**

```java
/** What Phase 4 features implement. assemble() acquires everything via the scope. */
public interface FeatureUnit {
    Feature descriptor();
    void assemble(Scope scope, Snapshot snapshot) throws Exception;
}

public final class Reconciler {
    public Reconciler(Registrar registrar, java.util.function.Consumer<String> log) {}
    public void register(FeatureUnit unit) {}   // duplicate descriptor throws
    /** Converge open scopes to (snapshot.enabled && unit registered): enable the
     *  missing, close the surplus, per-unit exception isolation; a throwing
     *  assemble closes its partial scope and logs — the unit stays OFF. */
    public void converge(Snapshot snapshot) {}
    public void closeAll() {}                    // reverse order, isolation per unit
    public boolean active(Feature f) { ... }     // truth = open scope, no boolean flag
}
```

`OcmBinding` — ports the old `OcmGate` state machine (`bind`/`configOnly`/`clear`
around a `MethodHandle` to OCM's `isModuleEnabledForPlayer`, plus the
`OcmConfigScan` static-verdict parse — read both old sources) but RESOLVES through
the kernel `ArbiterCore` (it constructs/replaces an ArbiterCore on each state
change). Unit-test with a stub MethodHandle (`MethodHandles.lookup()` on a local
test class) — port the old OcmGate test pins.

**Semantics (tests):**
1. Zero-touch property: a disabled feature's unit NEVER sees `assemble` (stub
   registrar records zero registrations for it) — enumerated over ALL descriptors.
2. converge twice = idempotent; disable-by-reload closes the scope (stub asserts
   every AutoCloseable closed exactly once).
3. Partial-enable isolation: unit A throws in assemble; units B, C still converge;
   A's partial registrations are closed (B8 pin).
4. closeAll with a throwing unit still closes the rest (teardown isolation).
5. OcmBinding: ABSENT/BOUND/CONFIG transitions mirror the old OcmGate pins;
   `CoexistWarnings.derive` wired with real `OcmFacts` from the config scan.

- [ ] Tests → fail → implement → PASS → commit
  `feat(core): the feature reconciler with zero-touch by construction`.

### Task 3.7: Phase gate (executor runs, orchestrator judges)

- [ ] `./gradlew build` — paste last 5 lines + exit code.
- [ ] `git diff --stat <phase3-start>..HEAD -- . ':!kernel' ':!core/src/main/java/me/vexmc/mental/v5' ':!core/src/test/java/me/vexmc/mental/v5' ':!core/src/test/resources/v5' ':!docs'` — paste output (expected empty).
- [ ] `./gradlew :kernel:test :core:test --rerun` fresh — paste tails + the
  `<testsuite>` header lines for ProfileParserTest, ReconcilerTest, ArbiterCoreTest.
- [ ] Paste `git log --oneline` for the phase; flip this file's checkboxes; append a
  "Phase 3 outcomes" section (facts later phases must carry, incl. any ocmKey
  corrections from Task 3.0); commit; push; paste push output.

# Combat Test 8c Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import the code-verified Combat Test 8c mechanic set as a `ct8c`
knockback profile + thirteen default-OFF rule features + a rules-bundle preset
mechanism shipping `ct8c`/`signature`/`vanilla`, per
`docs/superpowers/specs/2026-07-18-combat-test-8c-design.md` (THE SPEC — every
implementer reads it first; §2 is the normative numbers source).

**Architecture:** Additive kernel math + profile knob; feature scaffolding
locked in one task so five implementation clusters run conflict-free in
parallel worktrees; bundles as overlay-batch macros; tester suites last.

**Tech Stack:** Existing Mental stack only (no new dependencies).

## Global Constraints (from the spec + project skills)

- Read BEFORE coding: THE SPEC, `.claude/skills/mental-conventions`, plus the
  per-task skills listed in each task.
- Zero-touch; era-exact no-op defaults; `parse(empty)` equals full defaults;
  kernel stays pure-JDK additive; imports never inline-qualified; comments
  explain why/provenance (cite "CT8c decompile, spec §2.x"); conventional
  commits with prose bodies; commit after each green test cycle.
- Sub-floor listener-descriptor rule (paper-cross-version skill): no
  sub-1.17.1 Bukkit type in any registered Listener's method descriptors;
  enum constants resolved once at boot via `Enum.valueOf` in try/catch.
- Version behavior decided ONCE at assemble via presence probes; every degrade
  prints ONE loud boot line. Never `setNoDamageTicks(positive)` directly (the
  1.16.5–1.20.6 total-invuln trap) — use the platform `SpawnInvulnerability`
  seam.
- All new rule features default OFF (`defaultEnabled=false`).
- Numbers come from spec §2 verbatim. Do not re-derive from the wiki.
- Verification per task: the module test command given in the task. The full
  gate (build + matrix) runs once at the end (Task J).

## File ownership map (conflict control)

Shared files are touched ONLY by their owning task: `Feature.java`,
`SnapshotParser.java`, `config/settings/*`, `config.yml`, unit-file skeletons,
`MentalPluginV5.registerUnits` → **Task B**. `KnockbackProfile.java`,
`ModernKnockback.java`, `KnockbackEngine.java`, `Presets.java`,
`ProfileParser`, `profiles/modern/ct8c.yml`, `ConfigStore.BUNDLED_PROFILES`,
`docs/knockback-profiles.md` → **Task A**. `ConfigStore` bundle additions,
`Management.java`, GUI, api event → **Task H**. Tester → **Task I**. Tasks C–G
edit ONLY the unit/helper/test files listed as theirs.

---

### Task A: Kernel math + ct8c knockback profile

**Skills:** `knockback-profiles`, `legacy-motion-physics`, `era-accuracy`.

**Files:**
- Modify: `kernel/.../profile/ModernKnockback.java`, `KnockbackProfile.java`
  (parse), `Presets.java`, `kernel/.../engine/KnockbackEngine.java` (modern
  branch only)
- Create: `kernel/.../math/Ct8cTables.java`, `Ct8cChargeMath.java`,
  `Ct8cShieldMath.java`, `Ct8cRegenMath.java`, `Ct8cPotionMath.java` + kernel
  tests for each
- Modify: `core/.../config/ConfigStore.java` (add `"ct8c"` to
  `BUNDLED_PROFILES`, modern folder), core `ProfileParserTest` (ct8c pin),
  kernel `PresetsTest`, `KnockbackEngineTest`, `docs/knockback-profiles.md`
  (KnockbackDocsTest pins every schema knob — document the new ones)
- Create: `core/src/main/resources/profiles/modern/ct8c.yml`

**Interfaces (Produces):**

```java
public enum VerticalShape { VANILLA, CT8C_SPLIT }
// ModernKnockback grows three components, delegating ctor defaults
// (VerticalShape.VANILLA, 0.75, 0.5) — inert under VANILLA:
public record ModernKnockback(boolean enabled, double baseStrength,
    double sprintBonus, double enchantBonus, double residualHorizontal,
    double residualVertical, double verticalCap, boolean downwardKnockback,
    VerticalShape verticalShape, double groundedVerticalFactor,
    double airborneVerticalFactor) { /* existing OFF + old ctor delegate */ }

public final class Ct8cTables {
  public enum WeaponClass { FIST, SWORD, AXE, PICKAXE, SHOVEL, HOE, TRIDENT }
  public enum Tier { NONE, WOOD, STONE, IRON, GOLD, DIAMOND, NETHERITE }
  public static double attackSpeed(WeaponClass w, Tier t);   // attr value, e.g. SWORD→4.5
  public static int attackDelayTicks(double attackSpeed);    // (int)(20/clamp(s-1.5,0.1,1024)+0.5)
  public static double reach(WeaponClass w);                 // 2.5 / 3.0 / 3.5
  public static double damage(WeaponClass w, Tier t);        // spec §2.2 table (FIST,NONE)=2
}
public final class Ct8cChargeMath {
  public static double scale(double ticksSinceReset, double fullDelayTicks); // clamp(2*t/full,0,2)
  public static boolean attackAllowed(double scale, boolean missRecovery, double ticksSinceReset); // scale>=1 || (missRecovery && ticks>4)
  public static boolean chargedBonus(double scale, boolean crouching);       // scale>1.95 && !crouching
  public static boolean sweepAllowed(double scale);                          // scale>1.95
}
public final class Ct8cShieldMath {
  public static final double ARC_DOT_LIMIT = -0.8726646304130554; // -50° rad → ≈148° cone
  public static boolean withinArc(double dotViewToAttackerTimesPi);          // < ARC_DOT_LIMIT blocked
  public static double blockedPortion(double damage);                        // min(5.0, damage)
  public static int axeDisableTicks(int cleavingLevel);                      // 32 + 10*level
}
public final class Ct8cRegenMath {
  public static final int REGEN_INTERVAL_TICKS = 40, STARVE_INTERVAL_TICKS = 40;
  public static boolean canRegen(int foodLevel);                             // > 6
  public static boolean canSprint(int foodLevel);                            // > 6
}
public final class Ct8cPotionMath {
  public static int instantHealth(int amplifier);       // 6 << amp
  public static int instantDamage(int amplifier);       // 6 << amp
  public static double strengthMultiplier(int amplifier);   // +0.2*(amp+1)
  public static double weaknessMultiplier(int amplifier);   // -0.2*(amp+1)
  public static double tippedArrowScale();              // 0.125
}
Presets.CT8C  // name "ct8c", modern enabled, verticalShape CT8C_SPLIT,
              // groundedVerticalFactor 0.75, airborneVerticalFactor 0.5,
              // verticalCap 0.4, residuals 0.5/0.5, delivery immediate/tracker,
              // resistance NONE, combos false, shieldBlockingCancels false
```

Parse keys under `formula.modern`: `vertical-shape` (`vanilla`|`ct8c-split`),
`vertical-grounded-factor`, `vertical-airborne-factor` — omitted keys =
delegating-ctor defaults, so every existing preset file parses byte-identical
(assert: parse-empty property + all existing preset pins untouched).

Engine (modern branch, CT8C_SPLIT only): after resistance scaling with
post-resistance scalar `strength`:
`vyOut = grounded ? min(cap, groundedFactor*strength) : min(cap, vyIn + airborneFactor*strength)`.

**Hand-computed engine pins (write these tests first):**
- grounded, strength 0.4 → vy 0.30; grounded, strength 0.9 → vy 0.40 (capped)
- airborne vyIn −0.20, strength 0.4 → vy 0.00; airborne vyIn 0.30, strength
  0.4 → vy 0.40 (capped); airborne vyIn −1.00, strength 0.4 → vy −0.80
- VANILLA shape: bit-identical to pre-change vectors (regression pins)
- Tables: attackDelayTicks(4.5)=7, (3.5)=10, (4.0)=8, (5.0)=6;
  damage(SWORD,NETHERITE)=7, damage(HOE,DIAMOND)=3, damage(FIST,NONE)=2;
  scale: full charge at half of `fullDelay`… scale(7,14)=1.0, scale(14,14)=2.0,
  scale(13.65,14)=1.95

- [ ] Failing kernel tests for tables/charge/shield/regen/potion math (vectors above) → red
- [ ] Implement math classes → green; commit `feat(kernel): CT8c pure math tables`
- [ ] Failing tests: ModernKnockback shape knob + engine pins + no-op-at-default property → red
- [ ] Implement record extension + engine branch + parse → green; commit
- [ ] Presets.CT8C + ct8c.yml (exhaustive comments citing spec §2.5, decompile provenance header like other modern presets) + PresetsTest/ProfileParserTest pins + BUNDLED_PROFILES + docs/knockback-profiles.md knob docs → `./gradlew :kernel:test :core:test` green; commit
- [ ] Run `./gradlew build -x integrationTest` green (KnockbackDocsTest included); commit any doc fixes

### Task B: Feature scaffolding (the shared-surface lock)

**Skills:** `mental-conventions` only. This task makes waves C–G conflict-free:
it owns EVERY shared core file and creates compiling zero-touch SKELETON units.

**Files:**
- Modify: `core/.../feature/Feature.java` (13 constants),
  `core/.../config/SnapshotParser.java`, `core/.../MentalPluginV5.java`
  (registerUnits lines), `core/src/main/resources/config.yml` (13 module
  toggles + `weapon-attack-speeds`/`charged-attacks` settings sections, fully
  commented "Default OFF (era-exact no-op when disabled)")
- Create: `core/.../config/settings/WeaponSpeedSettings.java`,
  `ChargedAttackSettings.java`; skeleton units (assemble = no-op) in their
  final homes: `feature/cadence/WeaponSpeedUnit.java`, `ChargedAttackUnit.java`,
  `Ct8cSweepUnit.java`; `feature/damage/Ct8cDamageUnit.java`,
  `Ct8cCritsUnit.java`, `Ct8cIframesUnit.java`, `Ct8cShieldUnit.java`,
  `CleavingUnit.java`; `feature/sustain/Ct8cRegenUnit.java`,
  `Ct8cConsumablesUnit.java`, `Ct8cPotionsUnit.java`;
  `feature/loadout/Ct8cReachUnit.java`; `feature/knockback/Ct8cProjectilesUnit.java`
- Test: extend the Feature registry test + `SnapshotParser` parse tests

**Interfaces (Produces — exact, C–G compile against these):**

```java
// Feature constants (yamlKey, Family, SettingsKey):
WEAPON_ATTACK_SPEEDS("weapon-attack-speeds", CADENCE, WeaponSpeedSettings)
CHARGED_ATTACKS("charged-attacks", CADENCE, ChargedAttackSettings)
CT8C_DAMAGE("ct8c-damage", DAMAGE, NoSettings)
CT8C_CRITS("ct8c-crits", DAMAGE, NoSettings)
CT8C_SWEEP("ct8c-sweep", CADENCE, NoSettings)
CT8C_IFRAMES("ct8c-iframes", DAMAGE, NoSettings)
CT8C_SHIELDS("ct8c-shields", DAMAGE, NoSettings)
CT8C_REGEN("ct8c-regen", SUSTAIN, NoSettings)
CT8C_CONSUMABLES("ct8c-consumables", SUSTAIN, NoSettings)
CT8C_POTIONS("ct8c-potions", SUSTAIN, NoSettings)
CT8C_REACH("ct8c-reach", LOADOUT, NoSettings)
CT8C_PROJECTILES("ct8c-projectiles", KNOCKBACK, NoSettings)
CLEAVING("cleaving", DAMAGE, NoSettings)

public record WeaponSpeedSettings(double fist, double sword, double axe,
    double pickaxe, double shovel, double trident, HoeSpeeds hoe) {
  public record HoeSpeeds(double wood, double stone, double iron, double gold,
      double diamond, double netherite) {}
  // DEFAULTS = the spec §2.2 att/s table: 2.5,3.0,2.0,2.5,2.0,2.0,
  //            hoe(2.0,2.5,3.0,3.5,3.5,3.5)
}
public record ChargedAttackSettings(boolean requireFullCharge,
    int missRecoveryTicks, double chargedThreshold, double chargedReachBonus,
    boolean denyBonusWhileCrouching) {
  // DEFAULTS = (true, 4, 1.95, 1.0, true)
}
```

Config sections: `weapon-attack-speeds.attacks-per-second.{fist,sword,axe,
pickaxe,shovel,trident,hoe.{wood…netherite}}`;
`charged-attacks.{require-full-charge,miss-recovery-ticks,charged-threshold,
charged-reach-bonus,deny-bonus-while-crouching}`.

- [ ] Failing registry/parse tests (constants exist, defaults OFF, parse(empty)==DEFAULTS) → red
- [ ] Constants + records + parse cases + skeleton units + registerUnits + config.yml comments → `./gradlew :core:test` green
- [ ] `./gradlew build -x integrationTest` green (ZeroTouch-compatible skeletons); commit `feat(core): CT8c feature scaffolding`

### Task C: Combat-core cluster (charged-attacks, weapon-attack-speeds, ct8c-reach, ct8c-sweep)

**Skills:** `netty-fast-path`, `paper-cross-version`, `nms-archaeology` (if a
version misbehaves). **Owns:** the four skeleton unit bodies + new helpers in
their packages + their core tests. Nothing else.

Contracts: `WeaponSpeedUnit` recomputes the player ATTACK_SPEED attribute from
held item on join/respawn/world-change/held-slot/item-change (HitboxUnit
reconcile pattern; `platform/Attributes.attackSpeed()`, captured-base restore
on disable/quit — AttackChargeReset is the template; attribute value =
`attacksPerSecond + 1.5`). Mutually-hostile with ATTACK_COOLDOWN's 1024 spoof:
at assemble, if ATTACK_COOLDOWN enabled, print one warn line (bundles
arbitrate; both enabled = cooldown spoof wins, this unit no-ops — decided once
at assemble/rebuildOnSettingsChange).
`ChargedAttackUnit` owns a D2 per-player charge ledger (reset stamps from
delivered attacks + air swings via the parse rim's existing animation/attack
taps; fed by inbox signals, published in `PlayerView`-adjacent state — netty
reads published only): denies damage below `Ct8cChargeMath.attackAllowed`
(cancel at the same seam the existing rules use), feeds
`chargedBonus` reach extension to reach validation and exposes
`double currentScale(UUID)` via a published view (name it `ChargeView`; its
only consumer is this cluster's own `Ct8cSweepUnit`).
`Ct8cSweepUnit`: sweep requires Sweeping-Edge ratio > 0 AND
`Ct8cChargeMath.sweepAllowed(scale)` when CHARGED_ATTACKS is enabled (else
enchant-gate only, decided at assemble), ratios 0.25/0.333/0.375
(`0.5 − 0.5/(level+1)`), not-crit/not-sprint-knock conditions, and axe anvil
eligibility (Sweeping Edge + Fire Aspect/Looting/Knockback onto axes) via
`PrepareAnvilEvent` — SweepUnit/SweepDamageListener are the structural
templates. `Ct8cReachUnit`: 1.20.5+ interaction-range attribute + the
`AttackRangeAdapter` component path (values spec §2.2); below floor keep ≤3.0
enforcement via `hit-registration` reach validation knobs frozen per view, one
loud degrade line for 3.5 weapons; 0.9 hitbox inflation + through-non-solid
acceptance implemented INSIDE Mental's rewound reach validation
(`ReachValidator` consumers), never by mutating entities (zero-touch).

- [ ] Per-unit failing core tests (charge ledger math vs Ct8cChargeMath, attribute recompute mapping, reach table selection incl. degrade decision) → red → implement → green → commit each (`feat(cadence): …`, `feat(loadout): …`)
- [ ] `./gradlew :core:test` green; conventional commits with provenance bodies

### Task D: Damage cluster (ct8c-damage, ct8c-crits, ct8c-iframes, cleaving)

**Skills:** `paper-cross-version` (registry injection + the invuln trap),
`nms-archaeology` (pin Cleaving's exact registry floor on live jars).
**Owns:** the four skeleton bodies + `platform/.../CleavingRegistrar.java` +
tests.

Contracts: `Ct8cDamageUnit` composes via the `DamageShaper` seam (new
`ct8cToolBase(weapon)` alongside `eraToolBase`, numbers from
`Ct8cTables.damage`; fist 2 baseline). `Ct8cCritsUnit`: CT8c crit policy
(sprint allowed, enchant folded before ×1.5 — mirror `CritFallbackUnit`
structure, `DamageShaper.composeLegacy` gains a ct8c ordering variant, own the
edit since DamageShaper is this cluster's file). `Ct8cIframesUnit`:
`min(Ct8cTables.attackDelayTicks(attackerSpeed), 10)` melee /
0 projectiles, applied ONLY through the platform `SpawnInvulnerability`-safe
seam. `CleavingRegistrar` (platform): boot probe → modern Paper registry
injection (unfreeze→register→refreeze contained here; floor decided by javap
against live jars, expected ~1.21.3), returns
`Optional<CleavingHandle>` with `int levelOf(ItemStack)`; absent = one loud
degrade line, `CleavingUnit` assembles to published-handle consumers only
(damage +`1+level` through Ct8cDamageUnit's composition; disable-scaling
consumed by Task E via `CleavingHandle`). This cluster also owns the
Impaling scope change (spec §2.9: bonus applies to ALL mobs in water or
rain — an environment predicate in the damage composition, decided per hit
from the victim's wet state).

**Interfaces (Produces):** `CleavingHandle { int levelOf(ItemStack stack); }`
reachable via the platform profile; Task E consumes
`Ct8cShieldMath.axeDisableTicks(handle.levelOf(weapon))`.

- [ ] TDD per unit (table composition pins incl. netherite sword 7 / diamond hoe 3 / fist 2; crit ordering hand-computed: sharp V iron sword crit = (2+1+2+3)*1.5 …use spec §2.2+§2.9 exact values; iframe mapping 4.5→7, 3.5→10 capped) → commits
- [ ] `./gradlew :core:test :platform:test` green

### Task E: Shields (ct8c-shields)

**Skills:** `paper-cross-version`. **Owns:** `Ct8cShieldUnit` body + helpers +
tests.

Contract (spec §2.6, all through Bukkit damage events + Cooldowns API,
zero-touch): 148° arc override (cancel vanilla block outside CT8c arc /
apply block inside it via `Ct8cShieldMath`), 5-damage cap with passthrough
(recompute final damage), instant blocking + crouch-to-shield damage math
(`onGround && sneaking` + offhand shield ⇒ treat as blocking; never animate),
0.5 knockback resistance while blocking (feed the desk's context — coordinate
with the profile's shieldBlockingCancels=false), axe hit always disables
`Ct8cShieldMath.axeDisableTicks(cleavingLevel)` via the Cooldowns platform
resolver, axe attack durability 1 (cancel the second point). Banner shields:
intentionally NOT implemented (spec §5).

- [ ] TDD (arc dot vectors: head-on dot·π≈−π→blocked; 90° → not; cap split 7dmg→5 blocked/2 through; disable ticks 32/42/52/62) → implement → `./gradlew :core:test` green → commits

### Task F: Sustain cluster (ct8c-regen, ct8c-consumables, ct8c-potions)

**Skills:** `paper-cross-version` (components floor, potion event surfaces).
**Owns:** the three skeleton bodies + tests.

Contracts: `Ct8cRegenUnit` mirrors `RegenUnit` structure with
`Ct8cRegenMath` (40t/+1HP/>6 food/50% drain via seeded-testable hook;
starvation cadence left vanilla-owned unless probe shows divergence — decide
at assemble, document); eat-interrupt: player/mob damage cancels the active
consume (probe the item-use state seam; degrade loud if absent).
`Ct8cConsumablesUnit`: 1.20.5+ components for 20-tick drinks / potion
stack 16 / snowball stack 64 (AttackRangeAdapter-style adapter,
`Ct8cConsumableAdapter` in `v5/platform/`), loud degrade below.
`Ct8cPotionsUnit`: intercept Instant Health/Damage application to CT8c values
(6·2^amp — EntityRegainHealth/damage substitution pattern from
`PotionValuesUnit`), Strength/Weakness ±20%/level as attack-damage composition
inputs (coordinate: the multiplier applies in `DamageShaper` composition —
consume `Ct8cPotionMath`, own no DamageShaper edits; pass amplifiers through
the existing strength/weakness amp parameters), tipped-arrow ×1/8.

- [ ] TDD per unit (regen cadence with fake clock, potion values 6/12, ±20% multipliers) → implement → `./gradlew :core:test` green → commits

### Task G: Projectiles (ct8c-projectiles)

**Skills:** `netty-fast-path` (the projectile knockback module precedent),
`paper-cross-version` (1.21.2 vanilla projectile-KB restoration note).
**Owns:** `Ct8cProjectilesUnit` body + tests.

Contract: snowball/egg vs players — 0 damage, full 0.4 knock through the
DeliveryDesk (extend the existing PROJECTILE_KNOCKBACK submit path, do not
duplicate it; this unit only supplies CT8c policy), 4-tick throw gate via the
Cooldowns resolver; bow fatigue: track draw start per player (D2), >3s ⇒
strip arrow critical flag + apply 0.25-equivalent spread on launch; momentum:
zero the vertical inherited component, keep aim-direction share (launch-event
velocity rewrite); Loyalty void return (spec §2.9 — recover void-lost
tridents to the owner, probe the modern rule first: vanilla gained this
later, the unit must no-op where vanilla already does it). i-frames stay
Task D's.

- [ ] TDD (fatigue window math, momentum projection, KB submit policy) → implement → `./gradlew :core:test` green → commits

### Task H: Rules bundles + GUI + api event

**Skills:** `mental-conventions` (atomic config/overlay doctrine). **Owns:**
`core/.../config/RulesBundle.java`, `RulesBundleParser.java`,
`SupersededBundles.java`, ConfigStore bundle wiring
(`BUNDLES_DIR="bundles"`, `BUNDLED_BUNDLES=List.of("ct8c","signature",
"vanilla")`, extract-when-missing + superseded upgrade),
`manage/Management.applyBundle(String)`, `gui/CombatPresetsMenu.java` +
dashboard entry, `api` `RulesBundleAppliedEvent` (additive; publication
3.0.0→3.1.0 in the api versioning seam), resources `bundles/{ct8c,signature,
vanilla}.yml`, tests.

```java
public record RulesBundle(String name, String displayName, String description,
    Optional<String> knockbackProfile, Optional<String> effectsPreset,
    Map<String, Boolean> modules, Map<String, String> settings) {}
// Management.applyBundle: validate every module key against Feature yamlKeys
// and profile/preset names against the Snapshot; ONE batch overlay write
// (modules.<key>, knockback.profile, effects.preset, settings.*) + ONE
// reloadAll(); fire RulesBundleAppliedEvent(bundleName); reject unknown keys
// with a listed error, applying nothing (atomicity).
```

Bundle YAML schema (`display-name`, `description`, `knockback-profile`,
`effects-preset`, `modules:` map, `settings:` map) — files exhaustively
commented; contents per spec §3.3 (ct8c: 13 new ON + every classic toggle OFF
+ FEEDBACK OFF + profile ct8c; signature: classics/FEEDBACK/LOOT/COMBO/POTS ON
+ ct8c set OFF + profile signature + effects signature; vanilla: every
yamlKey'd module OFF + profile modern-vanilla).

- [ ] TDD: parser (parse(empty) fails validation loudly — a bundle without modules is invalid, this mechanism has no LEGACY default), applyBundle atomic-reject, extraction-when-missing, superseded-hash upgrade, japicmp green for the api addition → implement → `./gradlew :core:test :api:test build -x integrationTest` green → commits

### Task I: Tester suites

**Skills:** `live-server-testing`, `matrix-gate`. **Owns:**
`tester/.../suite/CombatTest8cSuite.java`, `RulesBundleSuite.java`,
`MentalTesterPlugin` registration lines, `BootSuite.manifestPresentSince()`
entries for the new probes (cleaving registrar, consumable adapter, reach
attribute already covered), harness-guard widths if suite count grows past
the wedge-detector thresholds.

CombatTest8cSuite (enable-assert-teardown per feature, `setFeature` pattern):
charge gate (sub-100% denied, miss-recovery lane allowed after 4t), damage
table via staged weapons (attribute staging trap: fake weapon damage needs
equipment tick), shield arc/cap/crouch/axe-disable, i-frame scaling per
weapon, regen cadence via awaitTicks, snowball KB + no-i-frame double-hit,
reach acceptance (modern lane) + degrade (legacy lane). RulesBundleSuite:
apply each bundle → converged feature set equals spec §3.3 exactly; vanilla
bundle → ZeroTouch-grade transparency; signature round-trip restores.

- [ ] Write suites (red against skeleton-era jar is fine locally) → `./gradlew build` green → single-version smoke (`integrationTest` current default) green → commit

### Task J: Docs + full gate + PR (orchestrator-owned)

- Create `docs/combat-test-8c.md`: verified mechanic list, gap list (spec §5),
  banner-shield intentional omission, bundle usage.
- `./gradlew build` → foreground matrix in 3–5-version batches
  (`matrix-gate` skill; nonce honesty; `--rerun-tasks` on repeats; never
  background the matrix), fix-forward.
- PR to `main` with presets, features, gaps, provenance summary.

## Wave / merge protocol (orchestrator)

- Wave 1: A ∥ B (both directly in `feat/combat-test-8c` — disjoint files).
- Wave 2: C ∥ D ∥ E ∥ F ∥ G, each agent `isolation: worktree` cut from the
  post-wave-1 HEAD; each commits on its worktree branch and reports the
  branch name; orchestrator merges all five into `feat/combat-test-8c`
  (file-ownership makes conflicts structural errors — investigate, don't
  auto-resolve), then `./gradlew build -x integrationTest` before Wave 3.
- Wave 3: H (alone, shared files). Wave 4: I, then J.
- Every agent: Opus, reads THE SPEC + this plan + its listed skills, TDD,
  conventional commits with prose bodies, never touches files outside its
  ownership list, reports deviations honestly instead of improvising across
  a boundary.

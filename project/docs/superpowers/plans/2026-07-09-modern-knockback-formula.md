# Melee Knockback Formula: Legacy | Modern — design (2026-07-09)

Owner request: operators choose between the **Legacy** (1.7/1.8) melee formula and a
**Modern** formula ported byte-exact from the Paper 26.1.2 server jar, each formula
carrying its own preset family with per-preset customization; the modern formula gains
a **downward-knockback** on/off toggle; the GUI gets an intermediate "Melee Knockback
Formula" menu between the Knockback screen and the preset list; snowball/rod/projectile
knockback stay entirely separate (legacy positional `computeBase`, unchanged).

Base commit: `49b4937` on `release/2.4.8-beta`. Branch: `feat/modern-knockback-formula`
(worktree — another agent owns the release branch). PR back into `release/2.4.8-beta`.

---

## 1. The verified 26.1.2 formula (two independent bytecode extractions, agreed)

Source: `run/26.1.2/versions/26.1.2/paper-26.1.2.jar`, Mojang-mapped, read with
`javap -c -p`. Full disassembly notes in the session scratchpad (`arch-*.md`,
`LivingEntity.txt`, `Player.txt`). Every constant below was read from the constant
pool, not assumed. Cross-validation: the closed-form results reproduce the live-measured
modern wire (Paper 1.21.11 bare): standing (0.4, 0.3608), sprint (0.7, 0.4).

A melee hit produces **two sequential applications** of one core function, both landing
before a single motion packet ships:

1. **Base knock** — inside `LivingEntity.hurtServer`, every registering non-NO_KNOCKBACK
   hit: `knockback(0.4, dx, dz)` with `dx = attacker.x − victim.x`, `dz = attacker.z −
   victim.z` (source POSITION; `abs > 200` → random scatter).
2. **Extra knock** — `Player.attack` → `causeExtraKnockback(target, bonus, preHitDelta)`
   where `bonus = getKnockback(target, source) + (sprinting && attackStrengthScale > 0.9
   ? 0.5f : 0.0f)` and `getKnockback = (ATTACK_KNOCKBACK attr [players: 0.0] + Knockback
   enchant levels folded by EnchantmentHelper.modifyKnockback) / 2.0f` → **0.5/level**.
   Skipped entirely when `bonus ≤ 0`. Direction = attacker FACING: the core is called
   with `(sin(yaw·π/180), −cos(yaw·π/180))`.

**The core — `LivingEntity.knockback(strength, x, z, …)`:**

```
strength *= 1.0 − getAttributeValue(KNOCKBACK_RESISTANCE)      // fractional scaling, both axes
old = getDeltaMovement()
while (x*x + z*z < 1.0E-5): x,z = (rand−rand)*0.01 each        // degenerate-direction jitter
imp = Vec3(x, 0, z).normalize() * strength
new = ( old.x/2.0 − imp.x,
        onGround() ? min(0.4, old.y/2.0 + strength) : old.y,   // ← the "downward knockback"
        old.z/2.0 − imp.z )
setDeltaMovement(new)                                           // via Paper's EntityKnockbackEvent delta
```

- **Vertical:** grounded → `min(0.4, old.y/2 + strength)`; airborne → `old.y`
  **unchanged** (a falling victim keeps its negative vy and gets zero lift — the modern
  "slammed sideways/down mid-air" feel; this is the behavior the toggle controls).
- Attack cooldown does **not** scale knock magnitude — it only gates the sprint +0.5
  through the `> 0.9` charge check.
- Attacker side, when `bonus > 0`: `setDeltaMovement(delta.multiply(0.6, 1.0, 0.6))` +
  `setSprinting(false)` — identical shape to the legacy era's ×0.6 + sprint clear.
- Wire: player victims get ONE `ClientboundSetEntityMotionPacket` carrying the combined
  base+extra result, then the server **restores** its motion copy to the pre-attack
  capture — modern is send-then-restore, i.e. Mental's `immediate` delivery + `combos:
  false` semantics.
- 26.1.2 vs classic 1.21: refactor only (`causeExtraKnockback` extraction; the /2 moved
  inside `getKnockback` with the sprint bonus re-expressed as +0.5). Constants unchanged.
  The new `stabAttack` (mace/spear) path is NOT on the left-click melee path — out of scope.

**Deliberate porting decisions** (document in docs + preset comments):
- `ATTACK_KNOCKBACK` attribute is not consulted (players default 0.0; the `sprint-bonus`
  / `enchant-bonus` knobs are the tuning surface).
- No `attackStrengthScale > 0.9` gate — Mental registers the sprint bonus off the
  SprintWire verdict, exactly like the legacy path (Mental's product is cooldown-free
  combat; the CADENCE family owns cooldown).
- Mental substitutes the MotionLedger residual for `old = getDeltaMovement()` — the same
  substitution the legacy formula already makes, and the correct analog of the era
  server's input-free physics fields.

## 2. Kernel changes (additive-only, Bukkit-free, D-8-safe)

### 2a. `ModernKnockback` nested record — the 20th `KnockbackProfile` component

New file `kernel/.../profile/ModernKnockback.java` (or nested in the profile record —
match the codebase's taste; `PaceScaling` is a top-level file, follow that):

```java
public record ModernKnockback(
        boolean enabled,            // the formula switch: false = legacy formula
        double baseStrength,        // 0.4  — the hurt-path base knock
        double sprintBonus,         // 0.5  — flat sprint addition to the extra knock
        double enchantBonus,        // 0.5  — per Knockback enchant level
        double residualHorizontal,  // 0.5  — surviving fraction of vx/vz per application (vanilla /2)
        double residualVertical,    // 0.5  — surviving fraction of vy inside the grounded vertical term
        double verticalCap,         // 0.4  — the min(cap, …) ceiling; <= 0 disables
        boolean downwardKnockback   // true — vanilla: airborne victims keep vy unchanged (zero lift);
                                    //        false: airborne victims get the grounded vertical formula
) {
    public static final ModernKnockback OFF =
            new ModernKnockback(false, 0.4, 0.5, 0.5, 0.5, 0.5, 0.4, true);
}
```

OFF carries the vanilla numbers with `enabled=false` so parse-of-an-absent-block is
value-stable and `sameValues` comparisons stay meaningful.

### 2b. `KnockbackProfile` growth — the exact paceScaling precedent

- New 20-arg canonical constructor ending `…, PaceScaling paceScaling, ModernKnockback
  modern`. Existing 18-arg and 19-arg convenience constructors delegate with
  `ModernKnockback.OFF`. **`LEGACY_17` and all existing `Presets` literals compile
  unchanged** (they use the convenience arities). Update `sameValues(...)` to include
  `modern` — load-bearing for the pristine-upgrade check and the v1-migration "tuned"
  test (kernel map, config map §5a).
- Javadoc on the new convenience delegation mirroring the existing additive-growth note.

### 2c. Engine — branch inside `computePaced`, zero call-site change

At the top of the full `computePaced(...)` overload (KnockbackEngine.java:182), after the
LEGACY-roll early-out position, branch:

```java
if (profile.modern().enabled()) {
    return new Paced(modernCompute(attacker, victim, profile, victimYOverride, random), 1.0, 1.0);
}
```

`modernCompute` (private static, same class or a package-private `ModernKnockbackMath`
helper — keep pure, hand it only EntityStates + profile + override + RandomGenerator):

```
r    = clamp01(victim.knockbackResistance())
vx,vz = victim.vx(), victim.vz()
vy   = victimYOverride != null ? victimYOverride : victim.vy()   // ADD-mode comp hint, same as legacy
g    = victim.grounded()
m    = profile.modern()

// stage 1 — base knock, direction from attacker POSITION (like legacy base)
dx = victim.x() − attacker.x(); dz = victim.z() − attacker.z()   // push AWAY from attacker
while (dx*dx + dz*dz < 1.0E-5): dx,dz = (random.nextDouble()−random.nextDouble())*0.01 each
(nx, nz) = normalize(dx, dz)
s1 = m.baseStrength() * (1 − r)
hx = vx * m.residualHorizontal() + s1*nx        // sign: + here because (dx,dz) points victim-ward;
hz = vz * m.residualHorizontal() + s1*nz        //       match vanilla's −imp with source-minus-victim.
                                                //       IMPLEMENTER: mirror the legacy base() sign
                                                //       convention exactly (KnockbackEngine.base :474-501
                                                //       computes dir = source − victim and SUBTRACTS);
                                                //       pin with a directional unit test.
y1 = (g || !m.downwardKnockback())
        ? cappedVertical(vy * m.residualVertical() + s1, m.verticalCap())
        : vy
// cappedVertical(v, cap) = cap > 0 ? min(cap, v) : v

// stage 2 — extra knock, direction from attacker YAW, only if bonus > 0
bonus = (attacker.sprinting() ? m.sprintBonus() : 0)
      + attacker.knockbackEnchantLevel() * m.enchantBonus()
if (bonus > 0):
    s2 = bonus * (1 − r)
    hx = hx * m.residualHorizontal() − sin(yaw°) * s2
    hz = hz * m.residualHorizontal() + cos(yaw°) * s2
    y1 = (g || !m.downwardKnockback())
            ? cappedVertical(y1 * m.residualVertical() + s2, m.verticalCap())
            : y1

// shared post-pipeline (parity with legacy finish(), minus the legacy-only pieces)
if (!g): hx *= profile.air().horizontal(); hz *= profile.air().horizontal(); y1 *= profile.air().vertical()
applyAdd(profile.add(), …)                       // reuse the existing helper
if (y1 < profile.limits().verticalMin()): y1 = verticalMin
clamp ±3.9 all axes
```

**Legacy knobs deliberately inert in modern mode** (docuument every one):
`base`, `verticalMode`, `extra`, `wtapExtra`/`freshSprint`, `friction`,
`limits.vertical`, `limits.horizontal`, `rangeReduction`, `sprintFactor`,
`resistance` policy (modern always applies the vanilla fractional (1−r) internally —
both the LEGACY roll and the SCALING horizontal multiply are skipped), `paceScaling`,
and the pocket servo + `PredictorInputs` (bypassed; `Paced` factors 1.0/1.0).
**Knobs that still apply:** `air`, `add`, `limits.verticalMin`, `combos`,
`meleeDelivery`/`projectileDelivery`, `shieldBlockingCancels`, the ±3.9 clamp.
`computeBase` (rod/snowball/pearl/arrow/fishing) is **untouched** — those paths remain
pure legacy regardless of the selected profile's formula, per the owner's "separate"
requirement. `explainServo`/servo debug should not run for modern hits (guard at the
call sites if trivial, else leave — debug-only).

No new public engine overload is required (the branch lives inside the existing
entries), so no D-8 `java.util.Random` twin is needed. If `ModernKnockbackMath` is a new
public class, keep its surface free of Java-9+ types in descriptors.

### 2d. Kernel unit pins (hand-computed; KnockbackEngineTest or a new ModernKnockbackEngineTest)

Standing victim residual = (0, −0.0784, 0), grounded, r=0, attacker due south of victim
(pure −x or −z alignment for clean numbers), vanilla knobs:
- plain: h = 0.4, y = min(0.4, −0.0392 + 0.4) = **0.3608**
- sprint: h = 0.4·0.5 + 0.5 = **0.7**, y = min(0.4, 0.3608·0.5 + 0.5) = **0.4**
- sprint + KB II: bonus 1.5 → h = 0.2 + 1.5 = 1.7, y = 0.4
- resistance 0.5, plain: h = 0.2, y = min(0.4, −0.0392 + 0.2) = 0.1608
- airborne vy = −0.5, downward true: y = −0.5 exactly (pass-through, no lift), h = vx·0.5 − 0.4
- airborne vy = −0.5, downward false: y = min(0.4, −0.25 + 0.4) = 0.15
- verticalCap 0 (disabled): grounded plain y = vy·0.5 + 0.4 uncapped
- air multipliers apply post-formula in modern mode (one pin)
- verticalMin floors the final modern vertical (one pin)
- compensationY override replaces the INPUT vy only (one pin)
- degenerate direction: attacker atop victim → jitter loop terminates, |h| == s1 (one pin)
- **the no-op property: `modern == OFF` ⇒ computePaced output bit-identical to a
  profile without the component** (construct via 19-arg vs 20-arg ctor, same inputs,
  assertEquals on vectors) — the era-exact-no-op proof.
- direction sign pin: attacker at (0,0), victim at (2,0) → knock points +x.

Also `PresetsTest`: pin the three modern preset constants' fields (incl. modern block
values, combos, deliveries, verticalMin), extend the verticalMin pin list, and confirm
the non-signature `paceScaling == OFF` loop covers them; `SupersededPresetsTest`
constructions compile unchanged (they use old arities).

## 3. Parse + config (core)

### 3a. ProfileParser
- Read `formula:` via `oneOf("formula", legacy…, "legacy", "modern")`-style helper →
  maps to `modern.enabled`. Fallback = LEGACY_17's (legacy). Accept only those two
  values, warn-and-fallback otherwise (ConfigReader conventions).
- Read the `modern:` block (sub-reader): `base-strength`, `sprint-bonus`,
  `enchant-bonus`, `residual.horizontal`, `residual.vertical`, `vertical-cap`,
  `downward-knockback` — each falling back to the `ModernKnockback.OFF` field values.
  `parse(empty) == LEGACY_17` must keep holding (ProfileParserTest.emptyBlockParsesToLegacy17).
- No new Overlay.route case needed (no new top-level section; selection stays
  `knockback.profile`).

### 3b. Bundled modern presets — three files + constants

Only the modern presets and custom.yml carry the new keys (speed-scaling precedent:
signature.yml + custom.yml only). Nine legacy preset files stay byte-identical.

| preset | intent | deltas from vanilla numbers |
| --- | --- | --- |
| `modern-vanilla` | the byte-exact 26.1.2 port | none; `downward-knockback: true`, `combos: false`, delivery `immediate`/`tracker`, `limits.vertical-min: -3.9` (**required** or downward can never ship), resistance `none`, everything else no-op |
| `modern-uplift` | modern feel without the mid-air slam | `downward-knockback: false`, `limits.vertical-min: 0.0` |
| `modern-combo` | modern math, 1.7-style residual compounding | `downward-knockback: false`, `combos: true`, `limits.vertical-min: 0.0` |

Each file: full header comment (provenance: "ported from the decompiled Paper 26.1.2
server jar; constants read from bytecode; grounded results match the live-measured
modern wire — standing (0.4, 0.3608), sprint (0.7, 0.4)"; these are Mental's OWN
example tunings for uplift/combo — say so, no fake fork provenance), `display-name`,
`description`, `formula: modern`, the `modern:` block fully commented, and the shared
knobs that still apply (`air`, `add`, `limits.vertical-min`, `delivery`, `modifiers`).
Values must parse byte-identically to their `Presets` constants.

Touch points (config map §5b): `Presets` constants + `Presets.ALL`;
`ConfigStore.BUNDLED_PROFILES`; `SupersededBundleHashTest.CURRENT_PRESETS`;
docs preset table. New presets need NO superseded archives.
`ensureDeliverySection` (ConfigStore:146-171): confirm new presets are unaffected
(they ship `delivery:` from birth) — exclude if the insertion heuristic would touch them.

### 3c. custom.yml
Append the commented `formula:` + `modern:` schema block (legacy defaults, like the
speed-scaling block at :129-150). custom.yml bytes change ⇒ archive the CURRENT text:
copy to `core/src/test/resources/superseded-bundles/custom-2.4.8.yml`, add the
`SupersededBundleHashTest.ARCHIVE` entry, add the SHA-256 to
`SupersededPresets.BUNDLE_SHA256_BY_PRESET` (kernel; follow the CUSTOM_2_4_7 precedent
including a revision literal if the existing pattern pairs them), and keep
`noArchivedRevisionCollidesWithACurrentBundle` green.

### 3d. knockback.yml
Extend the preset-list comment with the three modern presets and a one-line
"formula" explainer. (Not hash-pinned; existing installs keep their copy.)

## 4. GUI (core, me/vexmc/mental/v5/gui)

Follow the GUI map §4 mechanics but bucket by the REAL formula field
(`profile.modern().enabled()`), NOT meleeDelivery:

- New `MeleeFormula.java` display enum (GUI package, copy Family.java's shape):
  `LEGACY("Legacy (1.7 / 1.8)", …)`, `MODERN("Modern (26.1.2)", …)` with
  `boolean matches(KnockbackProfile p)`.
- New `KnockbackFormulaMenu` (CompatibilityMenu as template): header slot 4; two nav
  tiles (one per formula — the bucket containing the ACTIVE profile glows / shows
  "● ACTIVE"); click → `navigate(viewer, new ProfileMenu(ctx, formula))`; Back →
  `new FamilyMenu(ctx, Family.KNOCKBACK)` (NOT the dashboard — no back stack, parents
  are hardcoded).
- New `ProfileMenu(ctx, MeleeFormula)`: lift PROFILE_ICONS/PROFILE_SLOTS/drawProfiles/
  profileTile/kv/round out of FamilyMenu verbatim; filter names by
  `formula.matches(snapshot().profile(name))`; click handler unchanged
  (`apply(viewer, () -> ctx.management().setGlobalProfile(name))`); rows()=6
  (PROFILE_SLOTS reach 34 — set() silently drops out-of-range slots); Back →
  `new KnockbackFormulaMenu(ctx)`. For modern profiles the kv preview shows the modern
  knobs (base-strength / sprint-bonus / enchant-bonus / downward / combos / delivery)
  instead of the legacy kv lines.
- Edit `FamilyMenu` KNOCKBACK branch: replace `drawProfiles(viewer)` with one nav tile
  ("Melee Knockback Formula — choose the era formula, then a preset") →
  `new KnockbackFormulaMenu(ctx)`; keep the feature toggles + Back at their slots
  (DashboardModelTest.theKnockbackSectionHasEntries needs the toggle screen alive).
  Do NOT model the chooser as a `Family`.
- PROFILE_ICONS grows past 10 pairs → **switch `Map.of` to `Map.ofEntries`** (the
  DebugMenu:28 ceiling trap); keep the getOrDefault("PAPER") fallback. Pick era-safe
  material names via the existing MenuMaterials fallback path.
- Add `selfTestIcons()` to both new menus (mirror DashboardMenu:128) and extend the
  BootSuite headless-render test to construct them — catches relocated-Adventure/
  TextPort classload breaks on ≤1.16.5.

## 5. Docs + doc pins

- `docs/knockback-profiles.md`: add the new keys **verbatim** to the knob-vocabulary
  YAML block (`formula`, `modern`, `base-strength`, `sprint-bonus`, `enchant-bonus`,
  `residual`, `vertical-cap`, `downward-knockback` — mirror KNOB_KEYS granularity);
  new `### The modern formula (26.1.2)` sub-section using the pace-scaling section as
  the template: the exact math, the two-stage composition, the downward toggle, the
  inert-legacy-knob list, which presets opt in, the porting decisions from §1; three
  preset-table rows; a resolution note that formula is a per-profile property (worlds
  can mix formulas via per-world).
- `KnockbackDocsTest`: `EXPECTED_COMPONENTS` 19 → 20; extend `KNOB_KEYS`.

## 6. Integration suites (tester)

- `ProfileSuite` (the extension point; setGlobalProfile + PROPAGATE_TICKS pattern,
  engine==applied via the journal/velocity captors):
  1. `modern-vanilla` plain standing hit — engine==applied AND absolute ≈ (0.4, 0.3608).
  2. `modern-vanilla` sprint hit — absolute ≈ (0.7, 0.4).
  3. `modern-uplift` grounded parity + (if stageable) one airborne hit asserting the
     no-slam vertical. NOTE the memory trap: combos=false presets cannot be
     melee-leak-staged on clientless fakes — prefer grounded scenarios and the
     engine==applied pattern; do not force an airborne stage for modern-vanilla.
- Do NOT add modern presets to KnockbackSuite's hardcoded airborne-floor list
  (combos=false / floor semantics differ).
- BootSuite: the new menus' selfTest (see §4).

## 7. Invariants checklist (verify before PR)

- [ ] `parse(empty) == LEGACY_17` still green; default selected profile still legacy-1.7.
- [ ] Zero-touch: everything rides the existing KNOCKBACK feature; no new listeners.
- [ ] Kernel additive-only + Bukkit-free (build asserts); no existing public signature
      changed; japicmp (:api) untouched.
- [ ] modern==OFF profiles compute bit-identically to before (unit-pinned property).
- [ ] Rod/snowball/pearl/arrow/fishing paths byte-identical (computeBase untouched);
      one regression pin if cheap.
- [ ] Netty pre-send and authoritative pass agree (branch reads the frozen
      `PlayerView.profile()` at both existing call sites; adopt-not-recompute and the
      valve's exact-encoding consume are untouched; ±3.9 clamp inside the engine).
- [ ] Nine legacy preset YMLs byte-identical; custom.yml archived; hash guards green.
- [ ] Client technique contract untouched (0.6 self-multiplier, w-tap, jump-resets are
      unaffected; modern sprint bonus keys off the same SprintWire verdict).
- [ ] Conventional commits with prose bodies; commit as you go (kernel → config/presets
      → GUI → docs → tester; keep KnockbackDocsTest green within its commit).

## 8. What NOT to do

- No global `melee-formula:` config key — the formula is a per-profile property;
  selection stays `knockback.profile` (back-compatible, per-world can mix formulas).
- No changes to DeliveryDesk, valve, BurstSender, HitRegistrationUnit plumbing,
  EntityState, PlayerView, HitContext — the design needs none.
- No pace-scaling/servo coupling into the modern branch (explicitly bypassed v1).
- No fake provenance on modern-uplift/modern-combo (they are Mental's own tunings).
- Don't touch `SupersededPresets` revisions of the nine legacy presets.

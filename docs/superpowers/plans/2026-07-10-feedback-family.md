# FEEDBACK Family (hit-feedback + damage-indicators) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Mental 2.5.2's two opt-in cosmetic modules — `hit-feedback`
(replace the vanilla melee hurt sound with N configurable sounds + N particle
bursts, vanilla/signature presets) and `damage-indicators` (attacker-client-only
packet armor stand popping off the victim's chest under kernel ballistics).

**Architecture:** A new `Family.FEEDBACK` with two default-OFF `Feature`s.
Both hook `EntityDamageByEntityEvent` at MONITOR (victim's region thread, the
once-per-landed-hit seam). All FX ship as PacketEvents packets (never Bukkit
`Sound`/`Particle` — those enums drift). Pure placement/ballistics math lives
in the kernel. A bounded `FeedbackTrace` decision ring makes clientless suites
able to assert decisions the wire never shows.

**Tech Stack:** Java 17 source (multi-release mega-jar handles the rest),
PacketEvents 2.13.0 (shaded, source imports `com.github.retrooper.packetevents.*`),
relocated Adventure, JUnit (kernel + core test dirs), the tester harness.

**Read first:** `docs/superpowers/specs/2026-07-10-hit-feedback-and-damage-indicators-design.md`
(the spec — behavior contracts, presets, rejected alternatives), and
CLAUDE.md + `.claude/skills/mental-conventions/SKILL.md`.

## Global Constraints

- Both features **default OFF**; `parse(empty) == DEFAULTS` for both settings records; zero-touch (a disabled feature does NOTHING).
- Kernel stays **pure JDK** (no Bukkit, no PacketEvents — build-asserted).
- No getstatic of any version-absent constant anywhere a listener can link it (D-9 gate scans server logs for swallowed linkage errors). All drift-prone names resolve through a probe/band table.
- No sub-floor Bukkit type in any method/field descriptor of a class handed to `registerEvents` (the listener-descriptor hazard).
- Netty-thread discipline: packet listeners read only wrappers + published state, never live entities. EDBEE handlers run on the victim's region thread — block scans and player iteration are legal there.
- Imports always; comments explain why; conventional commits with prose bodies; commit per task.
- Version bands are decided against the **client's** version (`user.getClientVersion()`) for metadata indexes, and PE `ServerVersion`/platform `ServerEnvironment` for server-side gates.
- After EVERY task: `./gradlew :kernel:test :core:test -q` (or the module touched) must pass before commit.

---

### Task 1: Kernel FX math — `IndicatorPlacement` + `IndicatorBallistics`

**Files:**
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/fx/IndicatorPlacement.java`
- Create: `kernel/src/main/java/me/vexmc/mental/kernel/fx/IndicatorBallistics.java`
- Test: `kernel/src/test/java/me/vexmc/mental/kernel/fx/IndicatorPlacementTest.java`
- Test: `kernel/src/test/java/me/vexmc/mental/kernel/fx/IndicatorBallisticsTest.java`

**Interfaces:**
- Produces: `IndicatorPlacement.place(double victimX, double victimY, double victimZ, double attackerX, double attackerZ, double ringRadius, double chestOffset, double heightJitter, java.util.Random random)` → `IndicatorPlacement.Spawn(double x, double y, double z, double bearing)`.
- Produces: `IndicatorBallistics.Params(double launchVertical, double launchOutward, double gravity, double drag)`, `IndicatorBallistics.launch(Spawn spawn, Params params)` → `State(double x, double y, double z, double vx, double vy, double vz)`, `IndicatorBallistics.step(State s, Params params)` → next `State`, and `IndicatorBallistics.landed(State s, double groundY)` → boolean.
- Consumed by Task 7's driver.

- [ ] **Step 1: Write the failing tests**

`IndicatorPlacementTest` (hand-derived expectations; seeded Random makes it
deterministic — compute the expected values by running the SAME Random calls
in the test):

```java
package me.vexmc.mental.kernel.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Pins the front-half ring placement: bearing within ±90° of the
 * victim→attacker azimuth, radius exact, height jitter within its band.
 */
class IndicatorPlacementTest {

    @Test
    void spawnSitsOnTheRingAtTheConfiguredRadius() {
        Random random = new Random(42);
        IndicatorPlacement.Spawn spawn = IndicatorPlacement.place(
                0.0, 64.0, 0.0,   // victim feet
                3.0, 0.0,          // attacker due +x
                0.6, 1.2, 0.3, random);
        double dx = spawn.x();
        double dz = spawn.z();
        assertEquals(0.6, Math.hypot(dx, dz), 1e-9, "radius");
    }

    @Test
    void bearingStaysInTheFrontHalfTowardTheAttacker() {
        // Attacker due +x from the victim: azimuth 0. Front half = (-90°, +90°),
        // i.e. the spawn's x offset is strictly positive across many draws.
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            IndicatorPlacement.Spawn spawn = IndicatorPlacement.place(
                    0.0, 64.0, 0.0, 5.0, 0.0, 0.6, 1.2, 0.3, random);
            assertTrue(spawn.x() > 0.0, "front half draw " + i + " x=" + spawn.x());
        }
    }

    @Test
    void heightIsChestPlusBoundedJitter() {
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            IndicatorPlacement.Spawn spawn = IndicatorPlacement.place(
                    0.0, 64.0, 0.0, 5.0, 0.0, 0.6, 1.2, 0.3, random);
            assertTrue(spawn.y() >= 64.0 + 1.2 - 0.3 - 1e-9, "low bound " + spawn.y());
            assertTrue(spawn.y() <= 64.0 + 1.2 + 0.3 + 1e-9, "high bound " + spawn.y());
        }
    }

    @Test
    void degenerateZeroDistanceAttackerStillPlaces() {
        // Attacker exactly on the victim column: azimuth is undefined; the
        // implementation must substitute bearing 0 rather than NaN.
        Random random = new Random(1);
        IndicatorPlacement.Spawn spawn = IndicatorPlacement.place(
                0.0, 64.0, 0.0, 0.0, 0.0, 0.6, 1.2, 0.3, random);
        assertEquals(0.6, Math.hypot(spawn.x(), spawn.z()), 1e-9);
        assertTrue(Double.isFinite(spawn.bearing()));
    }
}
```

`IndicatorBallisticsTest` (hand-computed table for the default constants —
compute these by hand and write them as literals; the values below show the
recurrence, recompute exactly when writing the test):

```java
package me.vexmc.mental.kernel.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the pop-off integration: position += velocity, THEN
 * vy' = (vy − gravity) × drag and horizontal ×= drag — hand-computed for the
 * shipped defaults (launch 0.25 up / 0.06 outward, gravity 0.05, drag 0.98).
 */
class IndicatorBallisticsTest {

    private static final IndicatorBallistics.Params DEFAULTS =
            new IndicatorBallistics.Params(0.25, 0.06, 0.05, 0.98);

    @Test
    void firstTicksFollowTheHandComputedTable() {
        IndicatorPlacement.Spawn spawn = new IndicatorPlacement.Spawn(0.6, 65.2, 0.0, 0.0);
        IndicatorBallistics.State s = IndicatorBallistics.launch(spawn, DEFAULTS);
        assertEquals(0.25, s.vy(), 1e-12);
        assertEquals(0.06, s.vx(), 1e-12, "outward along bearing 0 = +x");
        assertEquals(0.0, s.vz(), 1e-12);

        // Tick 1: y = 65.2 + 0.25 = 65.45; vy = (0.25 − 0.05) × 0.98 = 0.196
        s = IndicatorBallistics.step(s, DEFAULTS);
        assertEquals(65.45, s.y(), 1e-12);
        assertEquals(0.196, s.vy(), 1e-12);
        assertEquals(0.6 + 0.06, s.x(), 1e-12);
        assertEquals(0.06 * 0.98, s.vx(), 1e-12);

        // Tick 2: y = 65.45 + 0.196 = 65.646; vy = (0.196 − 0.05) × 0.98 = 0.14308
        s = IndicatorBallistics.step(s, DEFAULTS);
        assertEquals(65.646, s.y(), 1e-12);
        assertEquals(0.14308, s.vy(), 1e-12);
    }

    @Test
    void apexArrivesWithinSixTicksThenFalls() {
        IndicatorBallistics.State s = IndicatorBallistics.launch(
                new IndicatorPlacement.Spawn(0, 65.2, 0, 0), DEFAULTS);
        double lastY = s.y();
        int apexTick = -1;
        for (int t = 1; t <= 40; t++) {
            s = IndicatorBallistics.step(s, DEFAULTS);
            if (s.y() < lastY) { apexTick = t; break; }
            lastY = s.y();
        }
        assertTrue(apexTick > 2 && apexTick <= 7, "apex tick " + apexTick);
    }

    @Test
    void landedFiresWhenAtOrBelowGroundPlusEpsilon() {
        assertTrue(IndicatorBallistics.landed(
                new IndicatorBallistics.State(0, 64.04, 0, 0, -0.3, 0), 64.0));
        assertFalse(IndicatorBallistics.landed(
                new IndicatorBallistics.State(0, 64.2, 0, 0, -0.3, 0), 64.0));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :kernel:test --tests 'me.vexmc.mental.kernel.fx.*' -q`
Expected: compilation FAILS (classes don't exist).

- [ ] **Step 3: Implement**

`IndicatorPlacement.java`:

```java
package me.vexmc.mental.kernel.fx;

import java.util.Random;

/**
 * Places a damage indicator on the front half of a ring around the victim —
 * the half facing the attacker, so the popping text lands in the attacker's
 * view. Pure math: the caller supplies positions and randomness; nothing here
 * knows about entities or packets (kernel Bukkit-free invariant).
 *
 * <p>Bearing = the victim→attacker azimuth ± up to 90°; a zero horizontal
 * distance (attacker exactly on the victim column) substitutes azimuth 0 so
 * the placement never goes NaN. Height = feet + chestOffset ± heightJitter,
 * uniform.</p>
 */
public final class IndicatorPlacement {

    /** Where the indicator text spawns, and the ring bearing it popped along. */
    public record Spawn(double x, double y, double z, double bearing) {}

    private IndicatorPlacement() {}

    public static Spawn place(
            double victimX, double victimY, double victimZ,
            double attackerX, double attackerZ,
            double ringRadius, double chestOffset, double heightJitter,
            Random random) {
        double dx = attackerX - victimX;
        double dz = attackerZ - victimZ;
        double azimuth = (dx == 0.0 && dz == 0.0) ? 0.0 : Math.atan2(dz, dx);
        double bearing = azimuth + (random.nextDouble() - 0.5) * Math.PI;
        double y = victimY + chestOffset + (random.nextDouble() * 2.0 - 1.0) * heightJitter;
        return new Spawn(
                victimX + ringRadius * Math.cos(bearing),
                y,
                victimZ + ringRadius * Math.sin(bearing),
                bearing);
    }
}
```

`IndicatorBallistics.java`:

```java
package me.vexmc.mental.kernel.fx;

/**
 * The indicator's pop-off flight: an item-drop-like arc — up for a few ticks,
 * then a dragged gravity fall — integrated as position += velocity, THEN
 * vy' = (vy − gravity) × drag with horizontal velocity × drag. The driver owns
 * ground truth: {@code groundY} is frozen once at spawn (the only place block
 * reads are region-legal), so the per-tick step performs zero world reads.
 */
public final class IndicatorBallistics {

    /** How far above the ground plane the text may hover before counting as landed. */
    public static final double LANDING_EPSILON = 0.05;

    public record Params(double launchVertical, double launchOutward, double gravity, double drag) {}

    public record State(double x, double y, double z, double vx, double vy, double vz) {}

    private IndicatorBallistics() {}

    /** The launch state: outward along the ring bearing, upward at launchVertical. */
    public static State launch(IndicatorPlacement.Spawn spawn, Params params) {
        return new State(
                spawn.x(), spawn.y(), spawn.z(),
                Math.cos(spawn.bearing()) * params.launchOutward(),
                params.launchVertical(),
                Math.sin(spawn.bearing()) * params.launchOutward());
    }

    public static State step(State s, Params params) {
        double x = s.x() + s.vx();
        double y = s.y() + s.vy();
        double z = s.z() + s.vz();
        return new State(
                x, y, z,
                s.vx() * params.drag(),
                (s.vy() - params.gravity()) * params.drag(),
                s.vz() * params.drag());
    }

    public static boolean landed(State s, double groundY) {
        return s.y() <= groundY + LANDING_EPSILON;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :kernel:test --tests 'me.vexmc.mental.kernel.fx.*' -q`
Expected: PASS. Also run the full `./gradlew :kernel:test -q` (the kernel
classpath gate asserts Bukkit-free — new package must stay pure JDK).

- [ ] **Step 5: Commit**

```bash
git add kernel/src/main/java/me/vexmc/mental/kernel/fx kernel/src/test/java/me/vexmc/mental/kernel/fx
git commit -m "feat(kernel): indicator placement + ballistics math

Pure front-half-ring placement and the item-pop integration the damage
indicators drive their packet armor stands with. groundY is frozen by the
caller at spawn so the per-tick step performs zero world reads."
```

---

### Task 2: `Family.FEEDBACK` + the two `Feature` constants + registry pins

**Files:**
- Modify: `core/src/main/java/me/vexmc/mental/v5/feature/Family.java` (add constant after `POTS`)
- Modify: `core/src/main/java/me/vexmc/mental/v5/feature/Feature.java` (two constants; put a `/* ------- FEEDBACK ------- */` block after the POTS block; add imports for the two new settings classes)
- Create: `core/src/main/java/me/vexmc/mental/v5/config/settings/HitFeedbackSettings.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/config/settings/DamageIndicatorsSettings.java`
- Modify: `core/src/test/java/me/vexmc/mental/v5/feature/FeatureRegistryTest.java` (add both yaml keys to `OPERATOR_CONTRACT_KEYS`)

**Interfaces:**
- Produces: `Feature.HIT_FEEDBACK` (yamlKey `"hit-feedback"`), `Feature.DAMAGE_INDICATORS` (yamlKey `"damage-indicators"`); `HitFeedbackSettings` and `DamageIndicatorsSettings` records with `DEFAULTS` (exact shapes below — later tasks depend on every component name).

- [ ] **Step 1: Write the settings records** (they are data, no test-first value; the parse pins in Task 3 are their tests)

`HitFeedbackSettings.java`:

```java
package me.vexmc.mental.v5.config.settings;

import java.util.List;

/**
 * The {@code hit-feedback} module's tunables: which sounds replace the vanilla
 * melee hurt-sound broadcast (each with its own volume/pitch), and which
 * particle bursts pop at the victim's mid-chest. The {@code preset} selects an
 * in-code set (VANILLA — audibly vanilla, the parse default; SIGNATURE —
 * Mental's own layered tune) or CUSTOM, which reads the {@code sounds:} /
 * {@code particles:} lists from the section. Presets are code constants, not
 * files — the knockback profile machinery is deliberately not reused
 * (spec: rejected alternatives).
 */
public record HitFeedbackSettings(
        Preset preset,
        List<SoundSpec> customSounds,
        List<ParticleSpec> customParticles) {

    public enum Preset { VANILLA, SIGNATURE, CUSTOM }

    /** One replacement sound: a resource-location name plus its volume/pitch. */
    public record SoundSpec(String sound, float volume, float pitch) {}

    /**
     * One particle burst. {@code particle} is a PE particle-type name;
     * {@code block} is the block-state name for block-crack particles (empty
     * otherwise). {@code SPREAD} scatters count particles with per-axis Gaussian
     * σ; {@code EMANATE} bursts them outward from the point at {@code speed}.
     */
    public record ParticleSpec(
            String particle, String block, int countMin, int countMax,
            Mode mode, float speed, double spreadX, double spreadY, double spreadZ) {}

    public enum Mode { EMANATE, SPREAD }

    public static final float MIN_VOLUME = 0.0f;
    public static final float MAX_VOLUME = 4.0f;
    public static final float MIN_PITCH = 0.5f;
    public static final float MAX_PITCH = 2.0f;
    public static final int MAX_COUNT = 64;

    /** The signature preset's sound layers (spec: the owner's 2.5.2 ask). */
    public static final List<SoundSpec> SIGNATURE_SOUNDS = List.of(
            new SoundSpec("block.lodestone.break", 1.0f, 1.0f),
            new SoundSpec("entity.generic.hurt", 0.85f, 0.75f),
            new SoundSpec("entity.breeze.deflect", 0.75f, 1.15f));

    /** The signature preset's particles: redstone-block break burst, 6–8, emanating. */
    public static final List<ParticleSpec> SIGNATURE_PARTICLES = List.of(
            new ParticleSpec("block", "redstone_block", 6, 8, Mode.EMANATE, 0.15f, 0, 0, 0));

    /** The vanilla preset: the era hurt sound, era pitch jitter applied at emit time. */
    public static final List<SoundSpec> VANILLA_SOUNDS =
            List.of(new SoundSpec("entity.player.hurt", 1.0f, 1.0f));

    public static final HitFeedbackSettings DEFAULTS =
            new HitFeedbackSettings(Preset.VANILLA, List.of(), List.of());

    /** The effective sound list for the selected preset. */
    public List<SoundSpec> sounds() {
        return switch (preset) {
            case VANILLA -> VANILLA_SOUNDS;
            case SIGNATURE -> SIGNATURE_SOUNDS;
            case CUSTOM -> customSounds;
        };
    }

    /** The effective particle list for the selected preset. */
    public List<ParticleSpec> particles() {
        return switch (preset) {
            case VANILLA -> List.of();
            case SIGNATURE -> SIGNATURE_PARTICLES;
            case CUSTOM -> customParticles;
        };
    }
}
```

`DamageIndicatorsSettings.java`:

```java
package me.vexmc.mental.v5.config.settings;

/**
 * The {@code damage-indicators} module's tunables. The indicator is an
 * attacker-client-only packet armor stand that pops off the victim's chest on
 * a front-half ring and falls under {@code IndicatorBallistics}; it despawns
 * the instant it reaches the spawn-time ground plane or at
 * {@code lifetimeTicks}. Text templates carry the {@code {HEALTH}} placeholder
 * (final damage in HEARTS, one decimal, trailing .0 stripped); the crit
 * variant fires on an era-crit posture OR damage at/above
 * {@code critThresholdHearts} (HEARTS — 2 damage = 1 heart).
 */
public record DamageIndicatorsSettings(
        int lifetimeTicks,
        double ringRadius,
        double heightJitter,
        double launchVertical,
        double launchOutward,
        double gravity,
        double drag,
        String text,
        String critText,
        double critThresholdHearts) {

    public static final int MIN_LIFETIME = 1;
    public static final int MAX_LIFETIME = 200;
    public static final double MAX_RADIUS = 4.0;
    public static final double MAX_JITTER = 2.0;
    public static final double MAX_LAUNCH = 2.0;
    public static final double MAX_GRAVITY = 0.5;
    public static final double MIN_DRAG = 0.5;
    public static final double MAX_DRAG = 1.0;

    public static final DamageIndicatorsSettings DEFAULTS = new DamageIndicatorsSettings(
            40, 0.6, 0.3, 0.25, 0.06, 0.05, 0.98,
            "&f-{HEALTH} &c❤&r",
            "&c&l** -{HEALTH} ❤ **",
            5.0);
}
```

- [ ] **Step 2: Add `Family.FEEDBACK`** — in `Family.java`, after the `POTS` constant (replace POTS's trailing `;` with `,`):

```java
    FEEDBACK("Hit Feedback", "NOTE_BLOCK",
            "Custom hit sounds and particles, and pop-off damage indicators.");
```

- [ ] **Step 3: Add the two `Feature` constants** — in `Feature.java`, after the POTS block (match the surrounding style exactly; add the two settings imports beside the existing `config.settings` imports):

```java
    /* ------------------------------- FEEDBACK ------------------------------- */

    HIT_FEEDBACK("hit-feedback", Family.FEEDBACK, "Hit Sounds & Particles",
            "Replace the vanilla hit sound with your own layered sounds and particles.",
            "NOTE_BLOCK", false,
            new Facets(
                    Facets.none("cosmetic only, no gameplay state"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("hit-feedback", HitFeedbackSettings.class)),

    DAMAGE_INDICATORS("damage-indicators", Family.FEEDBACK, "Damage Indicators",
            "Pop a damage number off the victim on the attacker's screen.",
            "ARMOR_STAND", false,
            new Facets(
                    Facets.none("cosmetic only, no gameplay state"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("damage-indicators", DamageIndicatorsSettings.class)),
```

- [ ] **Step 4: Add the yaml keys to `FeatureRegistryTest.OPERATOR_CONTRACT_KEYS`** (the set at `FeatureRegistryTest.java:25-52`): add `"hit-feedback",` and `"damage-indicators",` in the set literal, with a one-line comment `// FEEDBACK (2.5.2)`.

- [ ] **Step 5: Run**

Run: `./gradlew :core:test --tests '*FeatureRegistryTest' --tests '*DashboardModelTest' -q`
Expected: PASS (DashboardModelTest auto-covers the new family; the registry
test proves defaults OFF + facets complete + unique settings keys).
NOTE: `:core:compileJava` will fail until `SnapshotParser` has cases for the
new records ONLY IF the switch is exhaustive-by-enum — it is not (it has
`default -> NoSettings.DEFAULTS`), but `Snapshot.settings()` would then return
`NoSettings` for a `HitFeedbackSettings` key and `SnapshotTest` (Task 3) fixes
that. If `SnapshotTest` fails at THIS point on a type assertion, proceed to
Task 3 before committing — otherwise commit now.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/me/vexmc/mental/v5/feature core/src/main/java/me/vexmc/mental/v5/config/settings core/src/test/java/me/vexmc/mental/v5/feature/FeatureRegistryTest.java
git commit -m "feat: the FEEDBACK family — hit-feedback + damage-indicators descriptors

Two default-OFF cosmetic features (clientPresentation handled, every other
facet none) under a ninth dashboard family. Settings records carry the preset
model (in-code vanilla/signature constants, custom reads the lists) and the
indicator's full knob set with the spec defaults."
```

---

### Task 3: Config parse — `SnapshotParser` cases, `config.yml` sections, parse pins

**Files:**
- Modify: `core/src/main/java/me/vexmc/mental/v5/config/SnapshotParser.java` (two switch cases at `settingsFor` ~:121 before `default`, two parser methods, imports)
- Modify: `core/src/main/resources/config.yml` (two `modules.*` keys in the modules block + two top-level sections, exhaustively commented — config YAML is the docs)
- Modify: `core/src/test/java/me/vexmc/mental/v5/config/SnapshotTest.java` (DEFAULTS assertions in `emptySourcesYieldEveryDefaultWithoutIssues`, plus round-trip + clamp tests)

**Interfaces:**
- Consumes: the two records from Task 2 (exact component names).
- Produces: `parseHitFeedback(ConfigReader)` and `parseDamageIndicators(ConfigReader)` — private to the parser.

- [ ] **Step 1: Write the failing tests** — add to `SnapshotTest`:

In `emptySourcesYieldEveryDefaultWithoutIssues`, beside the existing record
assertions, add:

```java
        assertEquals(HitFeedbackSettings.DEFAULTS,
                snapshot.settings(Feature.HIT_FEEDBACK.settingsKey()));
        assertEquals(DamageIndicatorsSettings.DEFAULTS,
                snapshot.settings(Feature.DAMAGE_INDICATORS.settingsKey()));
```

(Cast/generic handling: match how the existing assertions in that test read
settings — copy the local idiom.) New round-trip tests in the same class,
following `potsSettingsReadFromTheConfig`'s YAML-string style:

```java
    @Test
    void hitFeedbackCustomListsReadFromTheConfig() throws Exception {
        Snapshot snapshot = parseMain("""
                hit-feedback:
                  preset: custom
                  sounds:
                    - sound: entity.player.hurt
                      volume: 0.9
                      pitch: 1.2
                    - sound: block.anvil.land
                      volume: 0.5
                      pitch: 0.6
                  particles:
                    - particle: crit
                      count-min: 3
                      count-max: 5
                      mode: spread
                      spread: {x: 0.2, y: 0.3, z: 0.2}
                """);
        HitFeedbackSettings s = settings(snapshot, Feature.HIT_FEEDBACK);
        assertEquals(HitFeedbackSettings.Preset.CUSTOM, s.preset());
        assertEquals(2, s.sounds().size());
        assertEquals("entity.player.hurt", s.sounds().get(0).sound());
        assertEquals(0.9f, s.sounds().get(0).volume(), 1e-6);
        assertEquals(1.2f, s.sounds().get(0).pitch(), 1e-6);
        assertEquals(1, s.particles().size());
        assertEquals(HitFeedbackSettings.Mode.SPREAD, s.particles().get(0).mode());
        assertEquals(0.3, s.particles().get(0).spreadY(), 1e-9);
    }

    @Test
    void hitFeedbackPresetsResolveTheirInCodeLists() throws Exception {
        Snapshot snapshot = parseMain("""
                hit-feedback:
                  preset: signature
                """);
        HitFeedbackSettings s = settings(snapshot, Feature.HIT_FEEDBACK);
        assertEquals(HitFeedbackSettings.SIGNATURE_SOUNDS, s.sounds());
        assertEquals(HitFeedbackSettings.SIGNATURE_PARTICLES, s.particles());
    }

    @Test
    void hitFeedbackKnobsAreParseClampedToTheirBounds() throws Exception {
        Snapshot snapshot = parseMain("""
                hit-feedback:
                  preset: custom
                  sounds:
                    - sound: entity.player.hurt
                      volume: 99
                      pitch: 0.01
                """);
        HitFeedbackSettings s = settings(snapshot, Feature.HIT_FEEDBACK);
        assertEquals(HitFeedbackSettings.MAX_VOLUME, s.sounds().get(0).volume(), 1e-6);
        assertEquals(HitFeedbackSettings.MIN_PITCH, s.sounds().get(0).pitch(), 1e-6);
    }

    @Test
    void damageIndicatorKnobsReadAndClamp() throws Exception {
        Snapshot snapshot = parseMain("""
                damage-indicators:
                  lifetime-ticks: 500
                  ring-radius: 0.8
                  text: "&e{HEALTH}"
                  crit-threshold-hearts: 3.5
                """);
        DamageIndicatorsSettings s = settings(snapshot, Feature.DAMAGE_INDICATORS);
        assertEquals(DamageIndicatorsSettings.MAX_LIFETIME, s.lifetimeTicks());
        assertEquals(0.8, s.ringRadius(), 1e-9);
        assertEquals("&e{HEALTH}", s.text());
        assertEquals(3.5, s.critThresholdHearts(), 1e-9);
        assertEquals(DamageIndicatorsSettings.DEFAULTS.critText(), s.critText());
    }
```

(`parseMain`/`settings` — reuse the test class's existing helpers; if the
helper is named differently, adapt to the local names — read the file first.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:test --tests '*SnapshotTest' -q`
Expected: FAIL (new records fall to `NoSettings.DEFAULTS` / new tests fail).

- [ ] **Step 3: Implement the parsers** — in `SnapshotParser.java`, add the two
cases before `default`:

```java
            case HIT_FEEDBACK -> parseHitFeedback(reader(main, "hit-feedback", "config.yml", issues));
            case DAMAGE_INDICATORS ->
                    parseDamageIndicators(reader(main, "damage-indicators", "config.yml", issues));
```

and the parsers (beside the other per-record parsers). The list-of-maps shape
is genuinely new — each entry re-wraps into a per-entry `ConfigReader` so the
warn-and-fallback contract covers list fields too. Use
`reader.section().getMapList(key)` guarded for a null section:

```java
    private static HitFeedbackSettings parseHitFeedback(ConfigReader reader) {
        HitFeedbackSettings d = HitFeedbackSettings.DEFAULTS;
        HitFeedbackSettings.Preset preset =
                reader.oneOf("preset", d.preset(), HitFeedbackSettings.Preset.class);
        return new HitFeedbackSettings(
                preset,
                parseSounds(reader, d.customSounds()),
                parseParticles(reader, d.customParticles()));
    }
```

`parseSounds`/`parseParticles`: iterate `getMapList("sounds")` /
`getMapList("particles")`; for each `Map<?,?>` build a
`MemoryConfiguration`-backed section (`new MemoryConfiguration()` +
`createSection("e", map)`) and wrap it in
`new ConfigReader(section, prefix + ".sounds[" + i + "]", issues)` so every
field read is the standard `text`/`numberClamped`/`intAtLeast`/`oneOf` call:
sound name via `text("sound", "")` — a blank name warns and the entry is
skipped; volume `numberClamped("volume", 1.0, MIN_VOLUME, MAX_VOLUME)` cast to
float; pitch likewise with the pitch bounds. Particles: `text("particle", "")`
(blank → skip), `text("block", "")`, `intAtLeast("count-min", 1, 0)`,
`intAtLeast("count-max", countMin, countMin)` (also cap both at `MAX_COUNT`
via `Math.min`), `oneOf("mode", Mode.EMANATE, Mode.class)`,
`numberClamped("speed", 0.15, 0.0, 2.0)` as float, and the spread via
`sub("spread")` → `numberClamped("x"/"y"/"z", 0.2/0.3/0.2, 0.0, 4.0)`.
Return immutable `List.copyOf(...)`.

```java
    private static DamageIndicatorsSettings parseDamageIndicators(ConfigReader reader) {
        DamageIndicatorsSettings d = DamageIndicatorsSettings.DEFAULTS;
        return new DamageIndicatorsSettings(
                (int) Math.min(DamageIndicatorsSettings.MAX_LIFETIME,
                        reader.intAtLeast("lifetime-ticks", d.lifetimeTicks(),
                                DamageIndicatorsSettings.MIN_LIFETIME)),
                reader.numberClamped("ring-radius", d.ringRadius(), 0.0,
                        DamageIndicatorsSettings.MAX_RADIUS),
                reader.numberClamped("height-jitter", d.heightJitter(), 0.0,
                        DamageIndicatorsSettings.MAX_JITTER),
                reader.numberClamped("launch-vertical", d.launchVertical(), 0.0,
                        DamageIndicatorsSettings.MAX_LAUNCH),
                reader.numberClamped("launch-outward", d.launchOutward(), 0.0,
                        DamageIndicatorsSettings.MAX_LAUNCH),
                reader.numberClamped("gravity", d.gravity(), 0.0,
                        DamageIndicatorsSettings.MAX_GRAVITY),
                reader.numberClamped("drag", d.drag(),
                        DamageIndicatorsSettings.MIN_DRAG, DamageIndicatorsSettings.MAX_DRAG),
                reader.text("text", d.text()),
                reader.text("crit-text", d.critText()),
                reader.numberClamped("crit-threshold-hearts", d.critThresholdHearts(), 0.0, 100.0));
    }
```

NOTE `lifetime-ticks: 500` must CLAMP to 200 (the test asserts MAX_LIFETIME);
`intAtLeast` doesn't clamp high — the `Math.min` wrapper does. If the local
idiom prefers a dedicated helper, add `intClamped` to `ConfigReader` following
`numberClamped` verbatim (with its own small test in the reader's existing
test class if one exists).

- [ ] **Step 4: Add the `config.yml` sections** — in the `modules:` block, matching the surrounding comment style:

```yaml
  # FEEDBACK — cosmetic hit presentation. Both default OFF (zero-touch).
  # hit-feedback replaces the vanilla melee hurt-sound broadcast with the
  # configured sounds and pops particles at the victim's chest;
  # damage-indicators shows the attacker a pop-off damage number.
  hit-feedback: false
  damage-indicators: false
```

and the two top-level sections (put them after the `fast-pots:` section;
comment exhaustively — config YAML is the docs; include: preset semantics,
the two-audience caveat — the victim keeps its own client-derived vanilla
hurt sound, custom sound names are resource locations with era fallbacks for
the signature layers below their add-version, HEARTS unit for the threshold,
and every ballistic knob's meaning):

```yaml
# Hit Sounds & Particles (modules.hit-feedback)
# preset: vanilla   — audibly vanilla: the era hurt sound at the era pitch.
# preset: signature — Mental's layered tune (lodestone break + generic hurt +
#                     breeze deflect, redstone-block burst particles). Sounds
#                     missing on old servers resolve to era fallbacks, printed
#                     at boot.
# preset: custom    — reads the sounds:/particles: lists below.
# Scope matches vanilla exactly: every nearby player EXCEPT the victim (the
# victim's own client derives its hurt sound from the damage event and keeps
# doing so — server-unreachable without breaking the victim's flinch).
hit-feedback:
  preset: vanilla
  sounds:
    - sound: entity.player.hurt   # resource location (or Bukkit-style name)
      volume: 1.0                 # [0..4]
      pitch: 1.0                  # [0.5..2]
  particles:
    - particle: block             # PE particle name; 'block' takes a block state
      block: redstone_block
      count-min: 6
      count-max: 8
      mode: emanate               # emanate (burst outward) | spread (gaussian)
      speed: 0.15                 # emanate outward speed
      spread: {x: 0.2, y: 0.3, z: 0.2}

# Damage Indicators (modules.damage-indicators) — attacker-client-only packet
# armor stands; {HEALTH} = final damage in HEARTS (2 damage = 1 heart), crit
# text fires on an era-crit posture OR damage >= crit-threshold-hearts.
damage-indicators:
  lifetime-ticks: 40
  ring-radius: 0.6
  height-jitter: 0.3
  launch-vertical: 0.25
  launch-outward: 0.06
  gravity: 0.05
  drag: 0.98
  text: "&f-{HEALTH} &c❤&r"
  crit-text: "&c&l** -{HEALTH} ❤ **"
  crit-threshold-hearts: 5.0
```

- [ ] **Step 5: Run**

Run: `./gradlew :core:test --tests '*SnapshotTest' --tests '*ConfigStoreTest' -q`
Expected: PASS (ConfigStoreTest proves the bundled config still parses clean).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/me/vexmc/mental/v5/config core/src/main/resources/config.yml core/src/test/java/me/vexmc/mental/v5/config/SnapshotTest.java
git commit -m "feat(config): parse the FEEDBACK sections

The sounds/particles lists are the codebase's first list-of-records parse
shape: each map entry re-wraps into a per-entry ConfigReader so the
warn-and-fallback contract covers list fields; blank names skip the entry
loudly. parse(empty) == DEFAULTS pinned for both records."
```

---

### Task 4: `FeedbackTrace` — the decision seam suites read

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/FeedbackTrace.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/feature/feedback/FeedbackTraceTest.java`

**Interfaces:**
- Produces: `FeedbackTrace` with `record Entry(String module, java.util.UUID attacker, java.util.UUID victim, String decision, String detail)`, methods `void record(Entry entry)`, `List<Entry> entries()` (snapshot copy), `void clear()`, capacity-bounded (128). Thread-safe (synchronized on an ArrayDeque — written from region threads).
- Consumed by Tasks 5, 7 (writers) and Task 9 (tester reads via `MentalPluginV5`).

- [ ] **Step 1: Failing test**

```java
package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeedbackTraceTest {

    @Test
    void recordsInOrderAndEvictsPastCapacity() {
        FeedbackTrace trace = new FeedbackTrace();
        UUID a = UUID.randomUUID();
        UUID v = UUID.randomUUID();
        for (int i = 0; i < 130; i++) {
            trace.record(new FeedbackTrace.Entry("hit-feedback", a, v, "SOUNDS", "n=" + i));
        }
        assertEquals(128, trace.entries().size());
        assertEquals("n=2", trace.entries().get(0).detail());
        assertEquals("n=129", trace.entries().get(127).detail());
        trace.clear();
        assertEquals(0, trace.entries().size());
    }
}
```

- [ ] **Step 2: Run** — `./gradlew :core:test --tests '*FeedbackTraceTest' -q` → compile FAIL.

- [ ] **Step 3: Implement**

```java
package me.vexmc.mental.v5.feature.feedback;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The FEEDBACK family's decision ring — the journal-pattern seam that makes
 * the modules matrix-testable at all: fake players carry no client and no
 * PacketEvents user, so nothing cosmetic is observable on the wire; suites
 * assert the DECISION (sounds resolved, suppression armed, indicator variant/
 * spawn/sendability) recorded here at the moment it is made, before any send.
 * Bounded and synchronized (writers are region threads); zero writes while
 * both modules are disabled — zero-touch holds.
 */
public final class FeedbackTrace {

    private static final int CAPACITY = 128;

    /** One decision. {@code decision} is an open string namespace (journal pattern). */
    public record Entry(String module, UUID attacker, UUID victim, String decision, String detail) {}

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public synchronized void record(Entry entry) {
        if (entries.size() == CAPACITY) {
            entries.removeFirst();
        }
        entries.addLast(entry);
    }

    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public synchronized void clear() {
        entries.clear();
    }
}
```

- [ ] **Step 4: Run** → PASS. **Step 5: Commit** (`feat: FeedbackTrace decision ring` with a body noting the clientless-testability rationale).

---

### Task 5: `hit-feedback` runtime — marks, suppressor, emitter, unit

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/HurtSoundMarks.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/HurtSoundSuppressor.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/FeedbackSoundTable.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/HitFeedbackListener.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/HitFeedbackUnit.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/feature/feedback/HurtSoundMarksTest.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/feature/feedback/FeedbackSoundTableTest.java`

**Interfaces:**
- Consumes: `TickClock` (kernel port — inject the plugin's instance; see how `MentalPluginV5` hands it to other units), `HitFeedbackSettings` (Task 2), `FeedbackTrace` (Task 4), `Scheduling` not needed here.
- Produces: `HurtSoundMarks` — `void mark(int victimEntityId, double x, double y, double z, TickStamp now)`, `boolean consume(int entityId, TickStamp now)` (entity-id form), `boolean consumeNear(double x, double y, double z, TickStamp now)` (positional form, 1.5-block radius); marks expire after 2 ticks; bounded (64). `FeedbackSoundTable.resolve(String name, ServerEnvironment env)` → `String` (the era-correct name for this server, or the input unchanged, or `""` when unresolvable pre-1.19.3).
- The unit wires: EDBEE listener (`scope.listen`) + suppressor (`scope.packets`).

- [ ] **Step 1: Failing tests.** `HurtSoundMarksTest`: mark → consume true once
then false; unexpired vs expired (use `TickStamp` the way kernel tests
construct it — read `kernel/.../TickStamp` first; if construction is awkward
in core tests, make `HurtSoundMarks` take a `long tick` primitive instead and
have the caller pass `TickStamp.value()` — prefer the primitive to keep the
class trivially testable); positional consume matches within 1.5 blocks,
misses at 3; capacity eviction at 64. `FeedbackSoundTableTest`: on a modern
env `block.lodestone.break` resolves to itself; on a 1.12-era env → its
fallback `block.stone.break`; `entity.breeze.deflect` below 1.21 →
`item.shield.block`; unknown custom name below 1.19.3 → `""`; unknown at/above
1.19.3 → itself (inline-by-name works there); Bukkit-style
`BLOCK_LODESTONE_BREAK` normalizes to `block.lodestone.break`.
(For `ServerEnvironment` in unit tests: check how existing platform tests
fabricate versions — if it can't be fabricated, give `FeedbackSoundTable` a
`(int major, int minor)`-style constructor-injected version tuple and derive
it from `ServerEnvironment` at assemble time; test the tuple form.)

- [ ] **Step 2: Run** → compile FAIL.

- [ ] **Step 3: Implement.**

`HurtSoundMarks` — plain synchronized bounded `ArrayDeque<Mark>` of
`record Mark(int entityId, double x, double y, double z, long tick)`;
`consume` removes-and-returns-true on the first match with
`now - tick <= 2`; a sweep drops expired heads on every call. No Bukkit, no
PE imports (netty-thread read — the suppressor calls it from the pipeline).

`FeedbackSoundTable` — pure name mapping, decided per lookup against the
injected version tuple:

```java
package me.vexmc.mental.v5.feature.feedback;

import java.util.Locale;
import java.util.Map;

/**
 * Era-correct sound-name resolution (the SweepCauses posture, applied to
 * names instead of constants): PacketEvents maps KNOWN names to per-version
 * ids, but an absent sound's id lookup returns garbage below 1.19.3 (no
 * inline-by-name there), so absence must be resolved HERE, against the
 * server version, before anything reaches a wrapper. Built-in layers carry
 * era fallbacks; an unknown custom name pre-1.19.3 resolves to "" (skip, one
 * warn at assemble).
 */
final class FeedbackSoundTable {

    private record Floored(int major, int minor, String fallback) {}

    private static final Map<String, Floored> FLOORS = Map.of(
            "block.lodestone.break", new Floored(1, 16, "block.stone.break"),
            "entity.breeze.deflect", new Floored(1, 21, "item.shield.block"));

    private final int major;
    private final int minor;
    private final boolean inlineByName; // 1.19.3+: sounds ship as inline holders

    FeedbackSoundTable(int major, int minor, boolean inlineByName) {
        this.major = major;
        this.minor = minor;
        this.inlineByName = inlineByName;
    }

    /** The name to send on this server, or "" when the sound cannot ship here. */
    String resolve(String raw) {
        String name = normalize(raw);
        Floored floor = FLOORS.get(name);
        if (floor != null && !atLeast(floor.major(), floor.minor())) {
            return floor.fallback();
        }
        if (floor == null && !inlineByName && !KnownSounds.UNIVERSAL.contains(name)) {
            // Pre-1.19.3 an unknown name has no trustworthy id — skip it.
            return "";
        }
        return name;
    }

    /** BLOCK_LODESTONE_BREAK → block.lodestone.break; strips minecraft: prefix. */
    static String normalize(String raw) {
        String name = raw.trim();
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        if (name.indexOf('.') < 0 && name.indexOf('_') >= 0) {
            name = name.toLowerCase(Locale.ROOT).replace('_', '.');
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private boolean atLeast(int wantMajor, int wantMinor) {
        return major > wantMajor || (major == wantMajor && minor >= wantMinor);
    }
}
```

plus a tiny `KnownSounds` holder with
`static final Set<String> UNIVERSAL = Set.of("entity.player.hurt", "entity.generic.hurt", "block.stone.break", "item.shield.block", "block.anvil.land")`
— the names the presets/fallbacks can emit below 1.19.3, all present since
1.9. (Any custom name outside this set on an old server is skipped with the
assemble-time warn — honest, not silent.) NOTE the year-scheme: derive the
tuple via `ServerEnvironment` helpers at assemble; a 26.x server is
`atLeast(anything legacy)` — check `ServerEnvironment.isAtLeast`'s
scheme-awareness and reuse it if injectable, else map year-majors to
`Integer.MAX_VALUE` majors when constructing the table.

`HurtSoundSuppressor` — copy `AttackSoundListener`'s shape verbatim
(PacketListenerAbstract NORMAL, reference-compare both sound packet types,
never throw), but: match `entity.player.hurt` exactly (bare or namespaced),
and cancel ONLY when a mark consumes: `ENTITY_SOUND_EFFECT` → 
`wrapper.getEntityId()` mark consume; `SOUND_EFFECT` → positional consume on
the wrapper's block position ×(1/8 fixed-point → use the wrapper's
`getEffectPosition()`-style accessor; javap the 2.13.0 wrapper for the exact
getter and its scaling). The tick "now" comes from the injected `TickClock`
(readable from any thread — `CounterTickClock` on Folia).

`HitFeedbackListener` — the Bukkit half:

```java
package me.vexmc.mental.v5.feature.feedback;

// imports: Bukkit event types, PacketEvents PlayerManager/User/wrappers,
// protocol sound/particle types, HitFeedbackSettings, FeedbackTrace, TickClock

/**
 * Emits the replacement hit feedback at EDBEE MONITOR — the once-per-landed-
 * melee seam (fires on the victim's region thread on every delivery path).
 * Marks the victim so the suppressor eats exactly this hit's vanilla
 * hurt-sound broadcast (fall/fire hurt sounds carry no mark and pass), then
 * plays the resolved sounds to vanilla's exact audience — nearby players
 * EXCLUDING the victim — and the particle bursts to everyone nearby.
 */
public final class HitFeedbackListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (event.getFinalDamage() <= 0.0) return;
        // 1. arm the suppressor for this hit
        // 2. resolve audience: Bukkit.getOnlinePlayers(), same world,
        //    within 16 * max(1, maxVolume) blocks of the victim, minus victim
        // 3. per resolved sound: WrapperPlayServerSoundEffect at the victim's
        //    position (SoundCategory.PLAYERS), vanilla pitch jitter when the
        //    preset is VANILLA; write via each audience member's User
        //    (null user → skip that viewer), single flush per viewer
        // 4. per particle spec: WrapperPlayServerParticle at chest (feet+1.2),
        //    count ThreadLocalRandom.nextInt(min, max+1); EMANATE = offset 0 +
        //    speed; SPREAD = offset σ + speed 0; audience INCLUDING victim
        // 4b. LOW-HEALTH EXTRA (settings fields from Task 11): when
        //    victim.getHealth() - finalDamage is > 0 AND below
        //    lowHealthThresholdHearts * 2, ALSO play the preset's low-health
        //    extra sound list (same audience as the normal sounds). A killing
        //    hit (health - finalDamage <= 0) plays the NORMAL set but NEVER
        //    the extra — death-effects owns the death moment.
        // 5. trace.record(...) with decision "EMITTED"/"EMITTED+LOW_HP" and a
        //    detail string naming the resolved sounds + counts (or "NO_VIEWERS")
    }
}
```

Write the real body (the comment block above is the contract, not the code —
expand each numbered point; the PE idioms are in `BurstSender`
(user lookup/write/flush/catch-Throwable-reconfiguration) and the wrapper
constructor shapes are in the exploration report: 
`WrapperPlayServerSoundEffect(Sound, SoundCategory, Vector3d, float, float)`,
`WrapperPlayServerParticle(Particle<?>, boolean, Vector3d, Vector3f, float, int)`.
Build PE `Sound` via `Sounds.getByNameOrCreate(resolved)`; block-state
particle data via `ParticleBlockStateData` + `WrappedBlockState.getByString`
guarded try/catch → degrade that spec to the `crit` particle with one warn
(the <1.13 legacy-data risk from the spec). Resolve sounds/particles ONCE at
listener construction (assemble time), not per hit.)

`HitFeedbackUnit`:

```java
public final class HitFeedbackUnit implements FeatureUnit {
    // ctor injects: Supplier<Snapshot>, TickClock, FeedbackTrace, java.util.logging.Logger
    @Override public Feature descriptor() { return Feature.HIT_FEEDBACK; }
    @Override public void assemble(Scope scope, Snapshot snapshot) {
        HitFeedbackSettings settings = snapshot.settings(Feature.HIT_FEEDBACK.settingsKey());
        HurtSoundMarks marks = new HurtSoundMarks();
        // resolve the sound table from ServerEnvironment + PE ServerVersion (1.19.3 gate)
        // log one line per fallback/skip engaged (the boot-report posture)
        scope.listen(new HitFeedbackListener(settings, marks, clock, trace, resolved...));
        scope.packets(new HurtSoundSuppressor(marks, clock));
    }
}
```

- [ ] **Step 4: Run** — `./gradlew :core:test --tests '*feedback*' -q` → PASS;
then full `./gradlew :core:test -q`.

- [ ] **Step 5: Commit** (`feat: hit-feedback runtime — mark-correlated suppression + PE emitters`, body explaining the two-audience model and why suppression is mark-scoped).

---

### Task 6: `hit-feedback` registration + smoke-level suite case

**Files:**
- Modify: `core/src/main/java/me/vexmc/mental/v5/MentalPluginV5.java` — construct one shared `FeedbackTrace` field with accessor `public FeedbackTrace feedbackTrace()`, register `new HitFeedbackUnit(...)` in `registerUnits()` beside the CADENCE block (inject `this::snapshot`-style supplier only if the unit needs live reads — it doesn't; assemble receives the snapshot), passing the plugin's `TickClock` (find the field the rim listeners use) and logger.
- Test: extend `core/src/test/java/me/vexmc/mental/v5/feature/` reconciler-level coverage ONLY if `ReconcilerTest`/`BukkitRegistrarTest` enumerate units explicitly (read them; most likely no change needed).

- [ ] **Step 1: Wire it** (registration is mechanical; the registry tests from Task 2 already gate the descriptor). 
- [ ] **Step 2: Run** — `./gradlew :core:test -q` → PASS.
- [ ] **Step 3: Commit** (`feat: register hit-feedback`).

---

### Task 7: `damage-indicators` runtime — text, packets, driver, unit

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/IndicatorText.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/IndicatorStandPackets.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/IndicatorDriver.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/DamageIndicatorsListener.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/DamageIndicatorsUnit.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/feature/feedback/IndicatorTextTest.java`
- Test: `core/src/test/java/me/vexmc/mental/v5/feature/feedback/IndicatorStandPacketsTest.java` (stub-PE pattern from `PacketTapStateTest`)

**Interfaces:**
- Consumes: Task 1 kernel math, Task 2 settings, Task 4 trace, `Scheduling` (read `platform/.../Scheduling.java` for the exact `repeatOn(Entity/Player, long periodTicks, Runnable)`-shaped method and its `TaskHandle`/closeable return — match it exactly), `DamageShaper.isLegacyCritical(Player)` (static, `feature/damage/DamageShaper.java`), PE `SpigotReflectionUtil.generateEntityId()` (source import `io.github.retrooper.packetevents.util.SpigotReflectionUtil` — relocated at shade).
- Produces: `IndicatorText.render(String template, double finalDamage)` → `String` (raw, post-{HEALTH}); `IndicatorText.hearts(double finalDamage)` → `String` ("3", "2.5"); `IndicatorStandPackets` builders (below).

- [ ] **Step 1: Failing tests.**

`IndicatorTextTest`:

```java
    @Test void heartsFormatOneDecimalStripsTrailingZero() {
        assertEquals("3", IndicatorText.hearts(6.0));
        assertEquals("2.5", IndicatorText.hearts(5.0));
        assertEquals("0.5", IndicatorText.hearts(1.0));
        assertEquals("1.3", IndicatorText.hearts(2.6));  // rounds to one decimal
    }
    @Test void templateSubstitutesBeforeLegacyCodes() {
        assertEquals("&f-2.5 &c❤&r", IndicatorText.render("&f-{HEALTH} &c❤&r", 5.0));
    }
```

`IndicatorStandPacketsTest` — install the stub PE API exactly as
`PacketTapStateTest.java:56-73` does (copy its setup), then:
build the spawn/metadata/destroy triple for a modern client version and assert:
entity type is `EntityTypes.ARMOR_STAND`; spawn position == the given spawn;
metadata contains index 0 byte `0x20`, index 3 boolean true, the armor-stand
status byte at **index 15 for a 1.17+ client** with value `(byte) 0x11`
(marker 0x10 | small 0x01), and index 2 an `Optional` component whose
legacy-serialized text equals the rendered template; destroy carries the id.
Then the same for a `V_1_12_2` client version: name at index 2 is a plain
§-string, status byte at index **11**. (Assert against the band helper
directly where wrapper internals resist inspection: also test
`IndicatorStandPackets.standFlagsIndex(ClientVersion)` returns
10 / 11 / 14 / 15 for 1.9 / 1.10–1.13.2 / 1.14–1.16.5 / 1.17+ — verify these
four bands against PE's per-version metadata mappings (wiki.vg) while
implementing; if 1.9 proves to share 11, collapse the band and fix the test.)

- [ ] **Step 2: Run** → compile FAIL.

- [ ] **Step 3: Implement.**

`IndicatorText`:

```java
package me.vexmc.mental.v5.feature.feedback;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** {HEALTH} templating: final damage rendered in HEARTS (2 damage = 1 heart). */
final class IndicatorText {

    private IndicatorText() {}

    static String hearts(double finalDamage) {
        BigDecimal hearts = BigDecimal.valueOf(finalDamage / 2.0)
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return hearts.toPlainString();
    }

    static String render(String template, double finalDamage) {
        return template.replace("{HEALTH}", hearts(finalDamage));
    }
}
```

`IndicatorStandPackets` — pure wrapper-building (no sends): 
`static int standFlagsIndex(ClientVersion v)` (the band table);
`List<PacketWrapper<?>> spawn(int entityId, UUID uuid, IndicatorPlacement.Spawn spawn, Component name, ClientVersion clientVersion, boolean modernSpawn)`
— `modernSpawn` (server 1.19+) picks `WrapperPlayServerSpawnEntity` vs
`WrapperPlayServerSpawnLivingEntity`; metadata list = entity flags 0x20 @0,
name @2 (`EntityDataTypes.STRING` + `TextPort.legacy(name)` for clients
< 1.13, else `OPTIONAL_ADV_COMPONENT` + `Optional.of(name)`), name-visible
@3, stand flags `(byte) 0x11` @`standFlagsIndex`; the driven y is
`spawn.y() - NAMEPLATE_OFFSET` with `static final double NAMEPLATE_OFFSET = 0.5`
(a marker stand's nameplate rides ~0.5 above its position — the constant is
the one place to retune);
`static PacketWrapper<?> move(int entityId, State from, State to)` — 
`WrapperPlayServerEntityRelativeMove` with the deltas;
`static PacketWrapper<?> destroy(int entityId)`.

`IndicatorDriver` — per-attacker: holds `List<Live>` 
(`record Live(int entityId, IndicatorBallistics.State state, double groundY, int ticksLeft)` — mutable holder class in practice), a `Scheduling` handle,
the attacker's PE `User`. `void add(...)` starts the 1-tick `repeatOn`
task lazily; each tick: for each live indicator — step the kernel math, send
one relative move, destroy+remove on `landed || --ticksLeft <= 0`; cancel the
task when empty; `void close()` destroys all + cancels (called from scope
close via the unit's registry of drivers); wrap every send in the
BurstSender-style catch. Zero entity/world reads in the tick body.

`DamageIndicatorsListener` — EDBEE MONITOR, same predicate as Task 5's
listener, plus: attacker's `User` null → 
`trace.record(..., "UNSENDABLE", ...)` and return; compute variant:
`boolean crit = DamageShaper.isLegacyCritical(attacker) || event.getFinalDamage() >= settings.critThresholdHearts() * 2.0`;
render template → legacy-ampersand deserialize (the `OffhandUnit.sendDenied`
precedent) → Component; placement via `IndicatorPlacement.place(...)` with
`ThreadLocalRandom.current()`; ground scan HERE (region thread): from
`floor(spawn.y())` down ≤6 blocks, first `block.getType().isSolid()` →
`groundY = blockY + 1.0`, none → `spawn.y() - 6`; entity id via
`SpigotReflectionUtil.generateEntityId()` in try/catch
(`IllegalStateException` → trace "ID_UNAVAILABLE", warn once, return); build
packets, send spawn+metadata (bundled on 1.19.4+ — reuse the BurstSender
bundle decision), hand to the attacker's driver;
`trace.record("damage-indicators", attacker, victim, crit ? "CRIT" : "NORMAL", "y=" + spawn.y() + " ttl=" + settings.lifetimeTicks() + " dmg=" + event.getFinalDamage())`.

`DamageIndicatorsUnit` — injects `Scheduling`, `FeedbackTrace`, logger;
`assemble` reads settings, creates a `ConcurrentHashMap<UUID, IndicatorDriver>`, registers the listener via `scope.listen`, and
`scope.task(() -> (AutoCloseable) () -> { drivers.values().forEach(IndicatorDriver::close); drivers.clear(); })`
so scope close tears every stand down. Expose `void forget(UUID player)`
(close+remove that driver) and register it as a session forget hook in Task 8.

- [ ] **Step 4: Run** — `./gradlew :core:test -q` → PASS.

- [ ] **Step 5: Commit** (`feat: damage-indicators runtime — packet stands under kernel ballistics`, body covering marker-hitbox rationale, frozen groundY, per-attacker lazy driver).

---

### Task 8: Register `damage-indicators` + forget hook

**Files:**
- Modify: `core/src/main/java/me/vexmc/mental/v5/MentalPluginV5.java` — `reconciler.register(new DamageIndicatorsUnit(scheduling, feedbackTrace, getLogger()))`; wire `sessions.addForgetHook(unit::forget)` following the existing pattern at `:298-303`/`:627-629` (hold the unit in a local/field as the existing hooked units do).

- [ ] **Step 1: Wire. Step 2: Run** `./gradlew :core:test -q` → PASS. **Step 3: Commit** (`feat: register damage-indicators`).

---

### Task 9: Tester `FeedbackSuite`

**Files:**
- Create: `tester/src/main/java/me/vexmc/mental/tester/suite/FeedbackSuite.java`
- Modify: `tester/src/main/java/me/vexmc/mental/tester/MentalTesterPlugin.java` — one `suite.addAll(FeedbackSuite.tests(mental, this));` line in the full-tier `else` branch (`:85-100`).

**Interfaces:**
- Consumes: `mental.feedbackTrace()` (Task 6), the toggle helper pattern (`ZeroTouchSuite.java:239-249`), `Arena`/`FakePlayer`/`Captors` (copy an existing suite's staging — `DamageRulesSuite` is the closest shape).

- [ ] **Step 1: Write the suite** — four cases, `tests(...)` returning the list:

1. `feedback zero-touch: disabled modules write no trace` — both modules OFF
   (assert via `mental.featureActive`), `trace.clear()`, stage a fresh fake
   pair, one real `attack`, `awaitUntil(damage captured)`, assert
   `trace.entries().isEmpty()` and `captors.knockbackAppliesTo(victim)` is
   whatever the knockback module's normal ownership is (unchanged — copy the
   assertion posture from `CosmeticSmokeSuite`), teardown in `finally`.
2. `hit-feedback records its decision and the hit still lands` — enable via
   the management seam, `trace.clear()`, stage + attack, awaitUntil damage,
   assert exactly one `hit-feedback` entry whose decision is `EMITTED` or
   `NO_VIEWERS` (fake players may be the only audience — either is a valid
   decision; the point is the decision was made and the hit landed), assert
   `captors.damageOf(victim) != null`; disable in `finally`, assert clean
   disable (`featureActive` false).
3. `damage-indicators records UNSENDABLE for a clientless attacker` — enable,
   `trace.clear()`, stage + attack, awaitUntil damage, assert one
   `damage-indicators` entry with decision `UNSENDABLE` (fake attacker has no
   PE user — this IS the honest assertable outcome; the sendable path's
   encode is unit-pinned), disable in `finally`.
4. `damage-indicators disable is clean mid-flight` — enable, attack, then
   immediately disable through the management seam and `awaitTicks(2)`;
   assert `featureActive` false and no exception surfaced (the D-9 log scan
   is the real gate here); re-check a subsequent vanilla hit lands.

   Suite rules that apply (from `live-server-testing`): fresh players per
   scenario, `setNoDamageTicks(0)` before staged hits, captors reset before
   the attack, awaitUntil not awaitTicks for event waits, arena inside the
   original chunk, remove players + unregister in `finally`, `context.note`
   for anything clientless-unobservable.

- [ ] **Step 2: Build the tester** — `./gradlew :tester:build -q` → PASS
(verifyTesterIsolation runs in core's check, not here; the suite must NOT
import PacketEvents types — it reads only Mental core types + Bukkit).

- [ ] **Step 3: Commit** (`test: FeedbackSuite — decision-trace assertions for the FEEDBACK family`).

---

### Task 10: Full local gate + one matrix smoke entry

- [ ] **Step 1:** `./gradlew build -q` — the whole check chain (unit tests,
japicmp, kernel-Bukkit-free, jvmdg gates D-8/jdk8Api — the new PE usages in
`feature/feedback` are shaded the same as existing ones; a `verifyJdk8Api`
failure here means a JDK-9+ API leaked into the new code — replace it).
Expected: BUILD SUCCESSFUL with zero gate failures.
- [ ] **Step 2:** One-entry integration sanity before the full matrix (pick the
newest entry named in `support-matrix.json`, e.g.
`./gradlew integrationTest<NewestSuffix>` then its paired check task — read
the task names via `./gradlew tasks --all | grep -i integration | head`).
Expected: `PASS nonce=<fresh>` and the check task green (D-9 scan clean).
- [ ] **Step 3:** Commit anything the gate forced (`fix: gate findings from the feedback round` — only if changes were needed).

The FULL `integrationTestMatrix` runs at the round's end (after the KB fixes
land on this branch) — one sequential run gates the release, per matrix-gate.

---

## Self-review notes (already applied)

- Spec coverage: presets ✓ (Task 2 constants + Task 3 parse + Task 5 resolve), suppression scope ✓ (Task 5 marks), audience ✓ (Task 5), indicator placement/physics/despawn/timeout ✓ (Tasks 1, 7), crit/threshold ✓ (Task 7), {HEALTH} hearts ✓ (Task 7 IndicatorText), trace/testability ✓ (Tasks 4, 9), zero-touch ✓ (default OFF + Task 9 case 1), GUI ✓ (automatic; preset-picker deliberately deferred — spec stretch scope).
- Known judgment calls an implementer must NOT "fix": suppression is mark-scoped (never unconditional); the victim keeps its self-derived vanilla sound (documented, not a bug); fake-attacker indicators are UNSENDABLE by design; kernel `fx` package stays JDK-only.
- Places where the plan defers to the repo on purpose (read, then match): `Scheduling`'s exact repeat method + handle type; `SnapshotTest`'s helper names; the PE sound wrapper's position accessor + fixed-point scaling; the stand-flags band table verification against PE mappings; `TickStamp` construction in core tests.

---

## Addendum (2026-07-10b, owner mid-round): Tasks 11–14

Execution order across the whole plan is now: 4 → 11 → 5 → 6 → 12 → 13 → 7 →
8 → 9 → 10. The spec addendum (same date, in the spec file) governs.

### Task 11: Low-health extra sound layer (settings + parse)

**Files:**
- Modify: `core/src/main/java/me/vexmc/mental/v5/config/settings/HitFeedbackSettings.java`
- Modify: `core/src/main/java/me/vexmc/mental/v5/config/SnapshotParser.java`
- Modify: `core/src/main/resources/config.yml`
- Modify: `core/src/test/java/me/vexmc/mental/v5/config/SnapshotTest.java`

**Interfaces:**
- Produces on `HitFeedbackSettings`: two NEW record components appended
  after `customParticles`: `List<SoundSpec> customLowHealthSounds`,
  `double lowHealthThresholdHearts`; constant
  `SIGNATURE_LOW_HEALTH_SOUNDS = List.of(new SoundSpec("entity.glow_squid.hurt", 0.9f, 1.2f))`;
  accessor `List<SoundSpec> lowHealthSounds()` dispatching
  VANILLA → `List.of()`, SIGNATURE → `SIGNATURE_LOW_HEALTH_SOUNDS`,
  CUSTOM → `customLowHealthSounds`; `DEFAULTS` becomes
  `(VANILLA, List.of(), List.of(), List.of(), 4.0)`.

- [ ] Update every existing construction of `HitFeedbackSettings` (Task 3's
  parser + tests) for the new arity. Parser: reuse the Task 3 sound-list
  helper for key `low-health-sounds`;
  `numberClamped("low-health-threshold-hearts", 4.0, 0.0, 100.0)`.
- [ ] config.yml `hit-feedback:` section gains (with comments: HEARTS unit;
  post-hit health; suppressed on the killing hit; signature's glow-squid
  layer falls back to `entity.squid.hurt` below 1.17):

```yaml
  low-health-threshold-hearts: 4.0
  low-health-sounds:
    - sound: entity.glow_squid.hurt
      volume: 0.9
      pitch: 1.2
```

- [ ] SnapshotTest: extend the DEFAULTS assertion (still `== DEFAULTS` on
  empty parse); extend the custom round-trip test with a low-health list +
  threshold read; a preset test pinning
  `SIGNATURE.lowHealthSounds() == SIGNATURE_LOW_HEALTH_SOUNDS`.
- [ ] `./gradlew :core:test -q` green; commit
  `feat(config): low-health extra sound layer for hit-feedback`.

### Task 12: `death-effects` descriptor + settings + parse + family rename

**Files:**
- Modify: `core/src/main/java/me/vexmc/mental/v5/feature/Family.java` —
  FEEDBACK display metadata becomes
  `FEEDBACK("Combat Effects", "NOTE_BLOCK", "Hit sounds and particles, pop-off damage indicators, and death effects.")`.
- Modify: `core/src/main/java/me/vexmc/mental/v5/feature/Feature.java` —
  HIT_FEEDBACK displayName becomes `"Hit Effects"` (yamlKey UNCHANGED);
  add third constant after DAMAGE_INDICATORS:

```java
    DEATH_EFFECTS("death-effects", Family.FEEDBACK, "Death Effects",
            "A cosmetic strike on player death — lightning, sound, and a burst.",
            "FIREWORK_ROCKET", false,
            new Facets(
                    Facets.none("cosmetic only, no gameplay state"),
                    Facets.handled(),
                    Facets.none("no damage contribution"),
                    Facets.none("no damage contribution")),
            new SettingsKey<>("death-effects", DeathEffectsSettings.class)),
```

- Create: `core/src/main/java/me/vexmc/mental/v5/config/settings/DeathEffectsSettings.java`:

```java
package me.vexmc.mental.v5.config.settings;

import java.util.List;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.ParticleSpec;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings.SoundSpec;

/**
 * The {@code death-effects} module's tunables: what plays at the moment a
 * player dies (any cause — PlayerDeathEvent). The VANILLA preset is a strict
 * nothing (enabled-but-vanilla is a no-op; the toggle owns zero-touch);
 * SIGNATURE is the owner's tune — a cosmetic packet lightning bolt (never a
 * real entity: no fire, no damage, no block interaction by construction),
 * the glow-squid death sound, and a white/yellow/gold firework-style burst.
 */
public record DeathEffectsSettings(
        Preset preset,
        boolean customLightning,
        List<SoundSpec> customSounds,
        List<ParticleSpec> customParticles) {

    public enum Preset { VANILLA, SIGNATURE, CUSTOM }

    public static final List<SoundSpec> SIGNATURE_SOUNDS =
            List.of(new SoundSpec("entity.glow_squid.death", 1.0f, 0.95f));

    /**
     * The signature burst: colored dust in &f/&e/&6 (white 0xFFFFFF, yellow
     * 0xFFFF55, gold 0xFFAA00) shaped like a firework blast, plus uncolored
     * firework sparks — vanilla's firework particle is not colorable, so the
     * mix approximates the ask honestly. Encoded as three dust specs (the
     * runtime maps spec.block "dust:RRGGBB" to ParticleDustData) + one spark.
     */
    public static final List<ParticleSpec> SIGNATURE_PARTICLES = List.of(
            new ParticleSpec("dust", "ffffff", 8, 12, HitFeedbackSettings.Mode.SPREAD, 0.0f, 0.5, 0.5, 0.5),
            new ParticleSpec("dust", "ffff55", 8, 12, HitFeedbackSettings.Mode.SPREAD, 0.0f, 0.5, 0.5, 0.5),
            new ParticleSpec("dust", "ffaa00", 8, 12, HitFeedbackSettings.Mode.SPREAD, 0.0f, 0.5, 0.5, 0.5),
            new ParticleSpec("firework", "", 10, 14, HitFeedbackSettings.Mode.EMANATE, 0.12f, 0, 0, 0));

    public static final DeathEffectsSettings DEFAULTS =
            new DeathEffectsSettings(Preset.VANILLA, false, List.of(), List.of());

    public boolean lightning() {
        return switch (preset) {
            case VANILLA -> false;
            case SIGNATURE -> true;
            case CUSTOM -> customLightning;
        };
    }

    public List<SoundSpec> sounds() {
        return switch (preset) {
            case VANILLA -> List.of();
            case SIGNATURE -> SIGNATURE_SOUNDS;
            case CUSTOM -> customSounds;
        };
    }

    public List<ParticleSpec> particles() {
        return switch (preset) {
            case VANILLA -> List.of();
            case SIGNATURE -> SIGNATURE_PARTICLES;
            case CUSTOM -> customParticles;
        };
    }
}
```

  NOTE the `dust` ParticleSpec reuses the `block` field as a hex color — the
  runtime (Task 13) interprets `particle == "dust"` that way. Comment this in
  the record and in config.yml.

- Modify: `SnapshotParser` — `case DEATH_EFFECTS -> parseDeathEffects(reader(main, "death-effects", "config.yml", issues));`
  parser reads `oneOf("preset", VANILLA, Preset.class)`,
  `flag("lightning", false)`, and reuses the Task 3 sound/particle list
  helpers for `sounds:` / `particles:`.
- Modify: `core/src/main/resources/config.yml` — `modules.death-effects: false`
  + a commented `death-effects:` section (preset semantics; the lightning is
  cosmetic-only; glow-squid sounds fall back to squid below 1.17; the burst
  is a dust approximation of a colorable firework blast).
- Modify: `FeatureRegistryTest.OPERATOR_CONTRACT_KEYS` — add `"death-effects"`.
- Modify: `SnapshotTest` — DEFAULTS assertion + a custom round-trip test +
  a signature preset-dispatch test.
- [ ] `./gradlew :core:test -q` green; commit
  `feat: death-effects descriptor + settings + Combat Effects family display`.

### Task 13: `death-effects` runtime + registration

**Files:**
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/DeathEffectsListener.java`
- Create: `core/src/main/java/me/vexmc/mental/v5/feature/feedback/DeathEffectsUnit.java`
- Modify: `core/src/main/java/me/vexmc/mental/v5/MentalPluginV5.java` (register)
- Test: `core/src/test/java/me/vexmc/mental/v5/feature/feedback/DeathEffectsPacketsTest.java` (stub-PE encode pin for the lightning spawn + dust color mapping)

**Contract:**
- `PlayerDeathEvent` at MONITOR (`ignoreCancelled` is irrelevant — the event
  is not cancellable on the floor API; do not set it). Victim =
  `event.getEntity()`; location frozen immediately.
- Audience: `Bukkit.getOnlinePlayers()`, same world, within 48 blocks of the
  death location (lightning render distance posture), no exclusions.
- Lightning (when `settings.lightning()`): ONE packet entity per audience
  member — `WrapperPlayServerSpawnEntity` with `EntityTypes.LIGHTNING_BOLT`
  at the death location (on 1.19+; below 1.19 the dedicated legacy
  spawn-global-entity path — check PE 2.13.0 for
  `WrapperPlayServerSpawnWeatherEntity` or the LIGHTNING_BOLT route on
  legacy protocols and javap it; if pre-1.19 proves unreliable, gate
  lightning to 1.19+ with one boot line — the sound+burst still fire).
  Entity id from `SpigotReflectionUtil.generateEntityId()`;
  `WrapperPlayServerDestroyEntities` for it after 20 ticks via
  `Scheduling.runGlobal`-style delayed task registered through the unit
  (belt-and-braces; clients self-expire the bolt render). NO thunder sound
  is sent — the sound list owns audio.
- Sounds: same emit path as hit-feedback (resolve via `FeedbackSoundTable`
  — add `entity.glow_squid.death` → fallback `entity.squid.death` (<1.17)
  and `entity.glow_squid.hurt` → `entity.squid.hurt` to its FLOORS map in
  this task if Task 5 didn't already), positional at the death location.
- Particles: same emit path as hit-feedback, at the death location +1.0y;
  `particle == "dust"` maps `block` field hex → `ParticleDustData(1.0f, r, g, b)`;
  below 1.13 dust degrades to `firework` sparks (one boot line).
- Trace: `trace.record(new Entry("death-effects", killerUuidOrNull, victim.getUniqueId(), "EMITTED"|"NO_VIEWERS", detail))`.
- Zero per-player state → no forget hook needed; the delayed destroy tasks
  must be scope-owned (a `scope.task` registry like the indicator drivers,
  closed on disable).
- Unit test: stub-PE encode pin — lightning spawn wrapper carries
  LIGHTNING_BOLT + the location; dust mapping "ffaa00" → (1.0, 0.667, 0.0)
  RGB floats (or PE's 0–255 ints — match `ParticleDustData`'s javap'd shape).
- [ ] `./gradlew :core:test -q` green; commit
  `feat: death-effects runtime — cosmetic lightning, sounds, burst`.

### Task 14: FeedbackSuite death-effects cases (amends Task 9)

Add to `FeedbackSuite` (Task 9 implements the file; if Task 9 already ran,
extend it here):
- `death-effects zero-touch: disabled writes no trace on a real death` —
  kill a fake (e.g. `victim.setHealth(0.0)` via `context.sync`), assert no
  `death-effects` trace entries.
- `death-effects signature records EMITTED on death` — enable via the
  management seam, set preset signature via the overlay
  (`management` write or config overlay key `death-effects.preset`), kill a
  fake pair victim via a real melee kill (lower victim health first so one
  hit kills), assert: exactly one `death-effects` entry, AND the
  `hit-feedback` entry for the killing hit does NOT carry the low-HP layer
  (decision == "EMITTED", not "EMITTED+LOW_HP") while hit-feedback is also
  enabled — this pins the killing-hit interaction rule live.
- disable both in `finally`; hit still lands afterward.

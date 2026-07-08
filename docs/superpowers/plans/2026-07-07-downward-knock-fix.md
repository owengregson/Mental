# 2026-07-07 — The downward-knock fix: floor the practice presets' final vertical at 0.0

**Branch:** `release/2.4.7-beta`.
**Owner bug:** "downward knockback on the 2nd (airborne) combo hit, all presets EXCEPT signature and velt."
**Before executing:** read `.claude/skills/mental-conventions/SKILL.md`,
`.claude/skills/era-accuracy/SKILL.md`, `.claude/skills/knockback-profiles/SKILL.md`,
and (for Tasks 5–6) `.claude/skills/live-server-testing/SKILL.md` +
`.claude/skills/matrix-gate/SKILL.md`.
**Evidence base:** the downward-knockback dossier (this plan's parent investigation);
the combat compendium (`docs/research/2026-06-06-combat-compendium.md`).

---

## Root-cause verdict

**Confirmed mechanism — Mental's own shipped vector.** In ADD mode the engine ships
`y = vy·friction.y + base.vertical (+ extra.vertical on a bonus hit)` with no
effective floor (`limits.verticalMin = −3.9` on every preset, a no-op restatement of
the packet clamp — `KnockbackEngine.finish`, `kernel/src/main/java/me/vexmc/mental/kernel/math/KnockbackEngine.java:527-529`,
and its read-only twin `shippedVertical` at `:381-383`). The vertical cap
(`base():512-514`) clamps only `y > limits.vertical()` — a negative y passes
untouched. So a sufficiently negative victim ledger `vy` ships a **downward** knock.
The negative-ship thresholds (`vy < −(base.v [+ extra.v])/friction.y`), hand-derived
from `Presets.java`:

| preset | friction.y | base.v | extra.v | plain threshold | sprint threshold |
|---|---|---|---|---|---|
| kohi | 0.5 | 0.35 | 0.085 | −0.700 | −0.870 |
| mmc | 0.5556 | 0.32 | 0.1 | −0.576 | −0.756 |
| lunar | 0.7634 | 0.44 | 0.0 | −0.5764 | −0.5764 |
| minehq | 0.5 | 0.36 | 0.09 | −0.720 | −0.900 |
| badlion | 0.5 | 0.34 | 0.085 | −0.680 | −0.850 |
| legacy-1.7/1.8 | 0.5 | 0.4 | 0.1 | −0.800 | −1.000 |
| velt | 0.1 | 0.36 | 0.0 | −3.600 | −3.600 |
| signature | 0.1 | 0.365 | 0.0 | −3.650 | −3.650 |

**The preset immunity pattern is exact:** the airborne decay terminal is −3.92
(`Decay.java:32`), so velt/signature bottom out at ship −0.032 / −0.0265 —
imperceptible — while friction.y 0.5–0.7634 lets the fall residual through at
−0.03…−1.5 scale. The reported "immune: signature and velt only" is this table.
The `vy` input is always the **MotionLedger residual** (never live entity velocity —
`EntityStates.captureVictim`), and on flat ground a *faithful* ledger cannot reach
these thresholds before touchdown (max faithful |vy| at flat touchdown ≈ 0.44–0.51);
the flat-ground repro requires the ledger to run more negative than the real flight
(the non-rising liftoff mis-stamp free-fall or a missed/late landing — the dossier's
§2/§4). The formula leak is the **final gate** either way: whatever divergence mode
fires, no negative can ship if the ship formula floors at 0.

**Era-authenticity verdict.** The era law is the same ADD law (`motY /= 2; motY +=
0.4; if (motY > 0.4) motY = 0.4` — upward-only cap, decompile
`decomp-1.7.10/sv.java:613`, compendium §1): era vanilla **could** ship a downward
knock, but ONLY when true server motY ran below −0.8/−1.0 — i.e. genuine long falls
(cliff knock-offs after 15+ real fall ticks). In flat play it never happened: era
servers re-equilibrated motY on every real landing (compendium §2, "collision-zero →
−0.0784 equilibrium"), boundary combo hits read the PRE-landing flight (vy ~0.25
declining, worst ≈ −0.44 — compendium §1.4), and **every measured era hit-2 vertical
is positive** (compendium wire tables: 1.8.9 hit2 0.3286; 1.7.10 chains
0.2184/0.2275/0.2236 stone, 0.3566/0.4150/0.4328 ice). The archived practice servers
ran these same values on true physics with true landings — their flat-arena play
never reached the thresholds. So: the flat-ground downward knock is a **Mental
infidelity in the vy input**, not era behavior; a 0.0 floor on the practice presets
is behavior-invariant for all era-normal play; and the **era presets (legacy-1.7/
legacy-1.8) must keep the unfloored law** — era vanilla did knock long-falling
victims downward, and `parse(empty) == LEGACY_17` byte-exactness is non-negotiable.

One doctrine point that unlocks the fix: **`limits.vertical-min` is NOT part of any
archived value set.** The archives carried no vertical floor knob — `−3.9` is
Mental's own schema filler (the LEGACY_17 default the port copied in). Changing it
does not falsify a historical record; the archived numbers (base/extra/friction/cap)
are untouched.

---

## Decision

Three candidate fixes, weighed against the invariants (zero-touch, era-exact no-op
defaults, kernel additive-only, archived values via SupersededPresets only, client
technique contract untouched). Owner intent is fixed: **no surprise downward knocks
on the shipped practice presets; signature/velt feel must not change at all.**

**(a) Raise `Limits.verticalMin` to 0.0 on the leaking practice presets, via the
SupersededPresets pristine-upgrade path — CHOSEN.**
- Value-level, **zero formula change**: the floor already exists in the engine
  (both `finish` and the servo's `shippedVertical` read the same
  `profile.limits().verticalMin()` — parity is structural, they cannot drift apart).
- Touches exactly five presets: **kohi, mmc, lunar, minehq, badlion**.
  - **legacy-1.7 / legacy-1.8 stay at −3.9** — era-exactness DEMANDS the legacy
    presets keep the possible downward: the era law has no floor, era vanilla shipped
    negatives on genuine falls, and `LEGACY_17` is the parse-empty pin. (On flat
    ground legacy's −0.8/−1.0 thresholds are also barely reachable even under the
    ledger-divergence scenario — legacy ships ≈ +0.01…+0.05 at the reported combo
    cadence — so the owner's practice-preset complaint is fully covered without
    touching era truth.)
  - **velt / signature stay at −3.9** — structurally immune (thresholds past
    terminal velocity), and "feel must not change at all" includes even the
    imperceptible terminal-velocity dip (−0.032/−0.0265): we pin it unchanged.
  - **custom stays LEGACY_17-identical** (`custom.sameValues(LEGACY_17)` pin holds).
- The floor is inert for every hit whose final y ≥ 0 — i.e. **all era-normal play is
  byte-identical**. It bites exactly the leak class, plus two flagged consequences
  (accepted, documented): era-authentic long-fall descents on practice presets now
  floor to 0 (a deliberate deviation, appropriate to flat practice arenas), and
  rod/projectile knocks on fast-falling victims floor too (`computeBase` shares
  `finish` — same leak class, same owner intent).
- Shipped-bundle changes go through **SupersededPresets**: the current (−3.9)
  revision of each of the five is archived verbatim, so an unedited install upgrades
  in place and any owner-tuned file is frozen forever — the exact 1.8.0/2.x
  precedent.
- Kernel additive-only holds: value changes to `public static final` profile
  constants and new private superseded revisions change no API signature (japicmp
  green — same mechanism the 1.8.0 archived-values round used).

**(b) Ledger-fidelity change (feed measured/clamped vy) — REJECTED as the primary.**
The divergence (non-rising liftoff mis-stamp, missed landing) is real, but fixing it
here is the risky move: the MotionLedger residual IS the 1.7.10 combo model — the
same `vy` estimate participates in the horizontal residual law (`motion×friction`)
that the era combo compounding, the pace-scaling A3 property, and the servo's
residual-carry projection are all built on. Reconciling vy against the PositionRing
would (1) change *every* combo hit's vertical input, not just the leak class,
perturbing wire values that are currently era-pinned to 4 decimal places; (2) split
the vy source from the vx/vz source mid-formula (a new hybrid state no era server
had — era motY was exactly this input-free machine, compendium §2); and (3) require
knowing WHICH divergence mode actually fires on the live boxer — the dossier's open
item, unresolvable without a packet capture. **Deferred as a diagnosis follow-up**
(DebugLog trace correlating ledger vy vs ring y at the 2nd hit); the floor guarantees
the owner-visible symptom is dead regardless of which mode fires.

**(c) A new profile knob (formula floor) defaulting era-exact — REJECTED (redundant).**
The knob already exists: `limits.vertical-min` is precisely "a formula floor
defaulting era-exact" (−3.9 ≡ off, parse fallback LEGACY_17). Adding a second
vertical floor would duplicate schema, double the docs/parse/pin surface
(KnockbackDocsTest, ProfileParser, every preset file), and violate the "smallest
change" rule for zero benefit. Option (a) *is* option (c), realized at value level
with zero new schema.

**Complements shipped with (a):** kernel unit pins for the leak arithmetic (every
touched preset, sprint and plain, plus the immune/era presets unchanged), the floor
ordering lock (post-air-multiplier), a ConfigStore pristine-upgrade proof, a tester
journal assertion (an airborne leak-class hit ships y ≥ 0 through the "what did we
actually ship" seam), and docs.

---

## What this does NOT change

- **The horizontal residual law.** `motion×friction − dir×push`, the ledger writers,
  the decay machine, combo compounding, pace scaling A3 — untouched. No formula in
  `KnockbackEngine` changes; only five preset **values** change.
- **legacy-1.7 / legacy-1.8 / custom.** Byte-identical, including the possible
  era-authentic downward knock on genuine long falls. `parse(empty) == LEGACY_17`
  unchanged.
- **velt / signature.** Not touched at all — not values, not YAML, not behavior,
  including the imperceptible terminal-velocity dips (pinned unchanged in Task 2).
- **The servo, beyond verticalStamp consistency.** `shippedVertical` reads the same
  knob as `finish`, so the servo's flight window sees the same floored stamp the wire
  ships (structural parity, no code change). A floored 0.0 stamp yields airTime <
  `MIN_AIR_TICKS_FOR_SERVO` (3) → the solve **declines** — behavior changes only on
  leak-class hits, from "declined or negative-stamp declined" to "declined"; every
  positive-stamp combo is byte-identical.
- **The valve / delivery / journal machinery.** DeliveryDesk arbitration, the
  quantized valve, pre-send, sweep semantics — untouched. (Post-2.4.6 the sweep never
  time-drops connected melee, so any remaining downward wire would necessarily be a
  journaled Mental SHIP — which is exactly what the Task 5 assertion forbids.)
- **The client-side technique contract.** 0.6 self-multiplier, w-tap, jump-resets —
  server-side value change only; nothing touches the client's integration.

---

## Task 1 — kernel: lock the floor's ordering (characterization pins)

Pin the floor mechanics BEFORE any value moves: the floor runs **after** the air
multiplier (engine step 7 ordering) and never lifts a positive vertical. These pass
against current code — they are the ground the value flip stands on.

**Modify** `kernel/src/test/java/me/vexmc/mental/kernel/math/KnockbackEngineTest.java`
— insert immediately after the existing `verticalMinFloorsTheFinalVertical()` test
method (after its closing brace, before the
`/* ----------------------- speed-conformal knockback (pace scaling) ----------------------- */`
divider):

```java
    @Test
    void verticalMinFloorAppliesAfterTheAirMultiplierAndNeverLiftsAPositive() {
        // The ordering lock for the 2.4.7 practice-floor round: the floor runs
        // AFTER the air multiplier (engine step 7), so a floored profile clamps
        // the POST-air vertical. Airborne victim falling at −2.0:
        //   base y = −2.0 × 0.5 + 0.4 = −0.6, air ×0.5 → −0.3, floor −0.2 → −0.2.
        // Floor-before-air would yield −0.2 × 0.5 = −0.1 instead — this pin
        // fails if the ordering ever moves.
        KnockbackProfile flooredAired = new KnockbackProfile(
                "floor-air", "Floor air", "", DEFAULTS.base(), VerticalMode.ADD, DEFAULTS.extra(),
                DEFAULTS.wtapExtra(), DEFAULTS.friction(),
                new KnockbackProfile.Limits(0.4, -0.2, -1.0),
                new KnockbackProfile.Push(1.0, 0.5), DEFAULTS.add(),
                DEFAULTS.rangeReduction(), 1.0, true, KnockbackDelivery.TRACKER,
                KnockbackDelivery.TRACKER, ResistancePolicy.NONE, true);
        KnockbackVector ordered = computed(
                attacker(0, 0, 0.0f, false, 0), airborneVictim(0, 4, 0, -2.0, 0), flooredAired, null);
        assertEquals(-0.2, ordered.y(), EPSILON);

        // A zero floor guarantees a non-negative ship outright on the same input
        // (−0.6 → air ×0.5 → −0.3 → floored 0.0) …
        KnockbackProfile zeroFloor = new KnockbackProfile(
                "floor-zero", "Floor zero", "", DEFAULTS.base(), VerticalMode.ADD, DEFAULTS.extra(),
                DEFAULTS.wtapExtra(), DEFAULTS.friction(),
                new KnockbackProfile.Limits(0.4, 0.0, -1.0),
                new KnockbackProfile.Push(1.0, 0.5), DEFAULTS.add(),
                DEFAULTS.rangeReduction(), 1.0, true, KnockbackDelivery.TRACKER,
                KnockbackDelivery.TRACKER, ResistancePolicy.NONE, true);
        KnockbackVector floored = computed(
                attacker(0, 0, 0.0f, false, 0), airborneVictim(0, 4, 0, -2.0, 0), zeroFloor, null);
        assertEquals(0.0, floored.y(), EPSILON);

        // … and NEVER lifts a positive: the grounded opener (0.4) is untouched.
        KnockbackVector opener = computed(
                attacker(0, 0, 0.0f, false, 0), victim(0, 4, 0, 0, 0, 0), zeroFloor, null);
        assertEquals(0.4, opener.y(), EPSILON);
    }
```

**Run:**
```bash
./gradlew :kernel:test --tests 'me.vexmc.mental.kernel.math.KnockbackEngineTest'
```
**Expected:** `BUILD SUCCESSFUL`, all KnockbackEngineTest cases pass (this is a
characterization pin of existing behavior).

**Commit:**
```
test(kernel): pin the vertical-min floor ordering before the practice-floor round

The floor runs after the air multiplier and never lifts a positive
vertical — the two properties the 2.4.7 downward-knock fix (a value-level
verticalMin change on the practice presets) stands on. Pinned now,
against the unchanged engine, so the value flip that follows cannot
silently ride an ordering drift.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01174ZCdfCNQhprYW7YHavYp
```

---

## Task 2 — kernel + bundled YAML: the practice floor (TDD, one green commit)

### 2a. Write the failing tests

**Create** `kernel/src/test/java/me/vexmc/mental/kernel/profile/PresetVerticalFloorTest.java`:

```java
package me.vexmc.mental.kernel.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.random.RandomGenerator;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import org.junit.jupiter.api.Test;

/**
 * The 2.4.7 practice floor. In ADD mode the engine ships
 * {@code y = vy × friction.y + base.vertical (+ extra.vertical on a bonus hit)}
 * with the {@code limits.verticalMin} floor as the only negative gate — and the
 * −3.9 filler every preset carried was a no-op, so a deep falling ledger vy
 * shipped a DOWNWARD combo knock on every practice preset (thresholds
 * −0.576 … −0.90) while velt/signature were immune purely because friction.y
 * 0.1 puts their thresholds (−3.6) past the −3.92 decay terminal. The archived
 * configs carried NO vertical floor knob (−3.9 was Mental's schema filler), and
 * the real servers' true physics never reached the thresholds in flat play —
 * every measured era hit-2 vertical is positive (combat compendium §1.4/§3) —
 * so the five practice presets now floor the final vertical at 0.0 while
 * legacy-1.7/1.8 keep the unfloored era law (era vanilla DID knock long-falling
 * victims downward) and velt/signature stay byte-identical.
 *
 * <p>The staged vy values are the airborne ledger free-fall
 * ({@code vy ← (vy − 0.08) × 0.98}) at the measured combo cadence: −0.78113
 * (tick 10, the plain-hit pins) and −0.90543 (tick 12, the sprint pins).</p>
 */
class PresetVerticalFloorTest {

    private static final double EPSILON = 1.0e-9;

    /** Airborne ledger free-fall at combo-cadence tick 10 (plain pins). */
    private static final double FREE_FALL_T10 = -0.78113;
    /** Airborne ledger free-fall at combo-cadence tick 12 (sprint pins). */
    private static final double FREE_FALL_T12 = -0.90543;
    /** The airborne decay terminal ({@code Decay} clamps at −3.92). */
    private static final double TERMINAL = -3.92;

    private static EntityState attacker(boolean sprinting) {
        return new EntityState(0, 0, 0, 0.0f, 0, 0, 0, true, sprinting, 0, 0);
    }

    private static EntityState airborneVictim(double vy) {
        return new EntityState(0, 0, 4, 0.0f, 0, vy, 0, false, false, 0, 0);
    }

    private static EntityState groundedVictim(double vy) {
        return new EntityState(0, 0, 4, 0.0f, 0, vy, 0, true, false, 0, 0);
    }

    private static double shippedY(KnockbackProfile profile, boolean sprinting, EntityState victim) {
        KnockbackVector vector = KnockbackEngine.compute(attacker(sprinting), victim, profile, null);
        assertNotNull(vector);
        return vector.y();
    }

    @Test
    void sprintLeakClassHitsFloorAtZeroOnEveryPracticePreset() {
        // vy −0.90543. Pre-floor ADD verticals (hand-computed, the leak class):
        //   kohi    −0.90543 × 0.5    + 0.35 + 0.085 = −0.017715
        //   mmc     −0.90543 × 0.5556 + 0.32 + 0.1   = −0.083056908
        //   lunar   −0.90543 × 0.7634 + 0.44 + 0     = −0.251205262
        //   minehq  −0.90543 × 0.5    + 0.36 + 0.09  = −0.002715
        //   badlion −0.90543 × 0.5    + 0.34 + 0.085 = −0.027715
        // Every practice preset shipped DOWN; the 0.0 floor clamps them all.
        assertEquals(0.0, shippedY(Presets.KOHI, true, airborneVictim(FREE_FALL_T12)), EPSILON);
        assertEquals(0.0, shippedY(Presets.MMC, true, airborneVictim(FREE_FALL_T12)), EPSILON);
        assertEquals(0.0, shippedY(Presets.LUNAR, true, airborneVictim(FREE_FALL_T12)), EPSILON);
        assertEquals(0.0, shippedY(Presets.MINEHQ, true, airborneVictim(FREE_FALL_T12)), EPSILON);
        assertEquals(0.0, shippedY(Presets.BADLION, true, airborneVictim(FREE_FALL_T12)), EPSILON);
    }

    @Test
    void plainLeakClassHitsFloorAtZeroOnEveryPracticePreset() {
        // vy −0.78113. Pre-floor ADD verticals (hand-computed):
        //   kohi    −0.78113 × 0.5    + 0.35 = −0.040565
        //   mmc     −0.78113 × 0.5556 + 0.32 = −0.113995828
        //   lunar   −0.78113 × 0.7634 + 0.44 = −0.156314642
        //   minehq  −0.78113 × 0.5    + 0.36 = −0.030565
        //   badlion −0.78113 × 0.5    + 0.34 = −0.050565
        assertEquals(0.0, shippedY(Presets.KOHI, false, airborneVictim(FREE_FALL_T10)), EPSILON);
        assertEquals(0.0, shippedY(Presets.MMC, false, airborneVictim(FREE_FALL_T10)), EPSILON);
        assertEquals(0.0, shippedY(Presets.LUNAR, false, airborneVictim(FREE_FALL_T10)), EPSILON);
        assertEquals(0.0, shippedY(Presets.MINEHQ, false, airborneVictim(FREE_FALL_T10)), EPSILON);
        assertEquals(0.0, shippedY(Presets.BADLION, false, airborneVictim(FREE_FALL_T10)), EPSILON);
    }

    @Test
    void terminalVelocityHitsFloorAtZeroInsteadOfSmashingDown() {
        // The worst case (a missed landing run to terminal): kohi sprint shipped
        // −3.92 × 0.5 + 0.35 + 0.085 = −1.525 — a block-scale downward smash.
        assertEquals(0.0, shippedY(Presets.KOHI, true, airborneVictim(TERMINAL)), EPSILON);
    }

    @Test
    void theFloorIsInertForEveryEraNormalHit() {
        // A faithful airborne combo hit (jump-curve vy −0.37390 at tick 10):
        // kohi sprint = −0.37390 × 0.5 + 0.35 + 0.085 = +0.24805 — positive,
        // floor untouched, byte-identical to the pre-floor engine.
        assertEquals(0.24805, shippedY(Presets.KOHI, true, airborneVictim(-0.37390)), EPSILON);

        // The grounded sprint opener at the −0.0784 equilibrium:
        // −0.0784 × 0.5 + 0.35 + 0.085 = +0.3958 — unchanged.
        assertEquals(0.3958, shippedY(Presets.KOHI, true, groundedVictim(-0.0784)), EPSILON);
    }

    @Test
    void veltAndSignatureAreUntouchedIncludingTheTerminalDip() {
        // The immunity pattern that identified the bug, pinned as UNCHANGED
        // (the owner's "signature/velt feel must not change at all"):
        //   velt      sprint @ −0.90543: −0.090543 + 0.36  = +0.269457
        //   signature sprint @ −0.90543: (−0.090543 + 0.365) × 0.98 = +0.26896786
        assertEquals(0.269457, shippedY(Presets.VELT, true, airborneVictim(FREE_FALL_T12)), EPSILON);
        assertEquals(0.26896786, shippedY(Presets.SIGNATURE, true, airborneVictim(FREE_FALL_T12)), EPSILON);

        // Even the imperceptible terminal-velocity dips still ship (floor −3.9):
        //   velt:      −3.92 × 0.1 + 0.36  = −0.032
        //   signature: (−3.92 × 0.1 + 0.365) × 0.98 = −0.02646
        assertEquals(-0.032, shippedY(Presets.VELT, true, airborneVictim(TERMINAL)), EPSILON);
        assertEquals(-0.02646, shippedY(Presets.SIGNATURE, true, airborneVictim(TERMINAL)), EPSILON);
    }

    @Test
    void legacyPresetsKeepTheUnflooredEraLaw() {
        // Era vanilla DID knock a long-falling victim downward (motY < −0.8):
        // legacy-1.7 plain @ −2.0 ships 0.5 × −2.0 + 0.4 = −0.6, exactly the
        // decompiled law — the era presets are deliberately NOT floored.
        assertEquals(-0.6, shippedY(KnockbackProfile.LEGACY_17, false, airborneVictim(-2.0)), EPSILON);
        assertEquals(-0.6, shippedY(Presets.LEGACY_18, false, airborneVictim(-2.0)), EPSILON);
    }

    @Test
    void projectilePathFloorsTheSameLeakClass() {
        // computeBase (rod/projectile knocks) shares finish(), so the floor
        // covers it too: kohi on a victim falling at −0.90543 pre-floors to
        // −0.90543 × 0.5 + 0.35 = −0.102715 → 0.0; velt ships its unchanged
        // −0.090543 + 0.36 = +0.269457 (no extras exist on this path).
        KnockbackVector kohi = KnockbackEngine.computeBase(
                airborneVictim(FREE_FALL_T12), 0.0, 0.0, Presets.KOHI, null,
                RandomGenerator.of("L64X128MixRandom"));
        assertNotNull(kohi);
        assertEquals(0.0, kohi.y(), EPSILON);

        KnockbackVector velt = KnockbackEngine.computeBase(
                airborneVictim(FREE_FALL_T12), 0.0, 0.0, Presets.VELT, null,
                RandomGenerator.of("L64X128MixRandom"));
        assertNotNull(velt);
        assertEquals(0.269457, velt.y(), EPSILON);
    }
}
```

**Modify** `kernel/src/test/java/me/vexmc/mental/kernel/profile/PresetsTest.java` —
insert after the line
`assertEquals(new PaceScaling(PaceScaling.Mode.ATTACKER, 0.95, 0.5, 2.0), signature.paceScaling());`
and before the `// Every OTHER preset stays OFF …` comment block:

```java
        // 2.4.7 — the practice floor: the five archived practice presets floor
        // the FINAL vertical at 0.0. The archives carried no vertical floor
        // knob (−3.9 was Mental's schema filler), and with it a deep falling
        // ledger vy shipped a DOWNWARD combo knock. velt/signature keep −3.9
        // (friction.y 0.1 puts the leak past the decay terminal — their feel
        // is pinned untouched), and the era presets keep −3.9 (era vanilla DID
        // knock long-falling victims downward; legacy stays byte-exact).
        assertEquals(0.0, kohi.limits().verticalMin());
        assertEquals(0.0, mmc.limits().verticalMin());
        assertEquals(0.0, lunar.limits().verticalMin());
        assertEquals(0.0, minehq.limits().verticalMin());
        assertEquals(0.0, badlion.limits().verticalMin());
        assertEquals(-3.9, velt.limits().verticalMin());
        assertEquals(-3.9, signature.limits().verticalMin());
        assertEquals(-3.9, legacy17.limits().verticalMin());
        assertEquals(-3.9, legacy18.limits().verticalMin());

```

**Modify** `kernel/src/test/java/me/vexmc/mental/kernel/profile/SupersededPresetsTest.java`
— add `import java.util.List;` to the imports (after the static imports, in the
regular import block before the `org.junit.jupiter.api.Test` import), and append this
test method before the class's closing brace:

```java
    /**
     * The 2.4.7 practice-floor round: each practice preset's pre-floor revision
     * (as shipped 1.8.0 → 2.4.6 — the current values with the −3.9 verticalMin
     * filler) is verbatim-superseded, the current 0.0-floor bundle is the
     * target (never re-flagged, no upgrade loop), and any owner edit is frozen.
     */
    @Test
    void uneditedPreFloorPracticePresetsUpgradeButTheCurrentBundlesDoNot() {
        for (String name : List.of("kohi", "mmc", "lunar", "minehq", "badlion")) {
            KnockbackProfile current = Presets.ALL.get(name);
            assertEquals(0.0, current.limits().verticalMin(), 0.0,
                    name + " must carry the 2.4.7 practice floor");

            KnockbackProfile preFloor = new KnockbackProfile(
                    current.name(), current.displayName(), current.description(),
                    current.base(), current.verticalMode(), current.extra(), current.wtapExtra(),
                    current.friction(),
                    new KnockbackProfile.Limits(
                            current.limits().vertical(), -3.9, current.limits().horizontal()),
                    current.air(), current.add(), current.rangeReduction(), current.sprintFactor(),
                    current.combos(), current.meleeDelivery(), current.projectileDelivery(),
                    current.resistance(), current.shieldBlockingCancels());
            assertTrue(SupersededPresets.isSupersededVerbatim(name, preFloor),
                    "an unedited pre-floor " + name + " must upgrade to the 2.4.7 floor");
            assertFalse(SupersededPresets.isSupersededVerbatim(name, current),
                    "the current " + name + " bundle is the target, not a superseded revision");

            // An owner edit (any tuned value) is frozen forever, old floor and all.
            KnockbackProfile edited = new KnockbackProfile(
                    preFloor.name(), preFloor.displayName(), preFloor.description(),
                    new KnockbackProfile.Push(
                            preFloor.base().horizontal() + 0.01, preFloor.base().vertical()),
                    preFloor.verticalMode(), preFloor.extra(), preFloor.wtapExtra(),
                    preFloor.friction(), preFloor.limits(), preFloor.air(), preFloor.add(),
                    preFloor.rangeReduction(), preFloor.sprintFactor(), preFloor.combos(),
                    preFloor.meleeDelivery(), preFloor.projectileDelivery(),
                    preFloor.resistance(), preFloor.shieldBlockingCancels());
            assertFalse(SupersededPresets.isSupersededVerbatim(name, edited),
                    "an owner-edited " + name + " must never be touched");
        }
    }
```

**Run (expect RED):**
```bash
./gradlew :kernel:test --tests 'me.vexmc.mental.kernel.profile.*' --tests 'me.vexmc.mental.kernel.math.KnockbackEngineTest'
```
**Expected failures (exactly these, nothing else):**
- `PresetVerticalFloorTest.sprintLeakClassHitsFloorAtZeroOnEveryPracticePreset`:
  `expected: <0.0> but was: <-0.017715>` (kohi leaks — the bug, reproduced in a pin)
- `PresetVerticalFloorTest.plainLeakClassHitsFloorAtZeroOnEveryPracticePreset`,
  `terminalVelocityHitsFloorAtZeroInsteadOfSmashingDown`,
  `projectilePathFloorsTheSameLeakClass` — same shape
- `PresetsTest.bundledPresetsCarryTheirCanonicalValues`: `expected: <0.0> but was: <-3.9>`
- `SupersededPresetsTest.uneditedPreFloorPracticePresetsUpgradeButTheCurrentBundlesDoNot`:
  `kohi must carry the 2.4.7 practice floor ==> expected: <0.0> but was: <-3.9>`
- `theFloorIsInertForEveryEraNormalHit`, `veltAndSignatureAreUntouchedIncludingTheTerminalDip`,
  `legacyPresetsKeepTheUnflooredEraLaw` must **already pass** (they pin unchanged behavior).

### 2b. Flip the five preset values

**Modify** `kernel/src/main/java/me/vexmc/mental/kernel/profile/Presets.java` — five
edits (each `old_string` is unique via its preceding lines):

KOHI —
```java
            new WtapExtra(false, 0.425, 0.085),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
```
becomes
```java
            new WtapExtra(false, 0.425, 0.085),
            new Friction(0.5, 0.5, 0.5),
            // verticalMin 0.0 — the 2.4.7 practice floor. The archived configs
            // carried NO vertical floor knob (−3.9 was Mental's schema filler,
            // a no-op), and the ADD vertical (vy × friction.y + base.vertical)
            // ships DOWNWARD once a falling ledger vy passes
            // −(base + extra) / friction.y (kohi sprint: −0.87) — a leak the
            // real servers' true physics never reached in flat play (every
            // measured era hit-2 vertical is positive; compendium §1.4). velt
            // and signature stay unfloored (friction.y 0.1 puts the threshold
            // past the −3.92 decay terminal); legacy-1.7/1.8 stay unfloored
            // (the era law knocks a long-falling victim downward, byte-exact).
            new Limits(0.4, 0.0, -1.0),
```

MMC —
```java
            new Friction(0.5556, 0.5556, 0.5556),
            new Limits(0.4, -3.9, -1.0),
```
becomes
```java
            new Friction(0.5556, 0.5556, 0.5556),
            new Limits(0.4, 0.0, -1.0), // verticalMin 0.0 — the 2.4.7 practice floor (see KOHI)
```

LUNAR —
```java
            new Limits(0.361735, -3.9, -1.0),
```
becomes
```java
            new Limits(0.361735, 0.0, -1.0), // verticalMin 0.0 — the 2.4.7 practice floor (see KOHI)
```

MINEHQ —
```java
            new WtapExtra(false, 0.45, 0.09),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
```
becomes
```java
            new WtapExtra(false, 0.45, 0.09),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, 0.0, -1.0), // verticalMin 0.0 — the 2.4.7 practice floor (see KOHI)
```

BADLION —
```java
            new WtapExtra(false, 0.48, 0.085),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
```
becomes
```java
            new WtapExtra(false, 0.48, 0.085),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, 0.0, -1.0), // verticalMin 0.0 — the 2.4.7 practice floor (see KOHI)
```

(Do NOT touch LEGACY_18's `new Limits(0.4, -3.9, -1.0)`, VELT's / SIGNATURE's
`new Limits(0.36, -3.9, -1.0)`, or CUSTOM.)

**Modify** `kernel/src/main/java/me/vexmc/mental/kernel/profile/SupersededPresets.java`:

1. Extend the class javadoc — after the existing `<p>The 2026-06-12 research round …
   presets shipped between 1.3.0 and 1.7.0.</p>` paragraph, add:

```java
 *
 * <p>The 2.4.7 downward-knock round floors the five practice presets'
 * {@code limits.verticalMin} at {@code 0.0} (the archives carried no vertical
 * floor knob — {@code −3.9} was Mental's schema filler, and it let a deep
 * falling ledger vy ship a DOWNWARD combo knock); the {@code *_1_8} revisions
 * below are those presets exactly as shipped 1.8.0 → 2.4.6.</p>
```

2. Insert the five pre-floor revisions after `LUNAR_1_3` (before the
   `SIGNATURE_2_2_0` javadoc):

```java
    /** kohi as shipped 1.8.0 → 2.4.6: the archived values with the −3.9 verticalMin filler. */
    private static final KnockbackProfile KOHI_1_8 = new KnockbackProfile(
            "kohi",
            "Kohi",
            "The canonical Kohi/HCF values — lower base, smaller per-level bonus"
                    + " (0.425/0.085), 1.7.10 ledger combos.",
            new Push(0.35, 0.35),
            VerticalMode.ADD,
            new Push(0.425, 0.085),
            new WtapExtra(false, 0.425, 0.085),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            true,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /** mmc as shipped 1.8.0 → 2.4.6: the archived values with the −3.9 verticalMin filler. */
    private static final KnockbackProfile MMC_1_8 = new KnockbackProfile(
            "mmc",
            "MMC",
            "Minemen Club's archived dev123 (2017) values — soft base, full"
                    + " vanilla sprint bonus, flat 1.8 delivery.",
            new Push(0.32, 0.32),
            VerticalMode.ADD,
            new Push(0.5, 0.1),
            new WtapExtra(false, 0.5, 0.1),
            new Friction(0.5556, 0.5556, 0.5556),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.IMMEDIATE,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /** lunar as shipped 1.8.0 → 2.4.6: the archived values with the −3.9 verticalMin filler. */
    private static final KnockbackProfile LUNAR_1_8 = new KnockbackProfile(
            "lunar",
            "Lunar",
            "Lunar Network's archived S5 values — heavy base, high residual"
                    + " survival, weak sprint differential.",
            new Push(0.54, 0.44),
            VerticalMode.ADD,
            new Push(0.38, 0.0),
            new WtapExtra(false, 0.38, 0.0),
            new Friction(0.6849, 0.7634, 0.6849),
            new Limits(0.361735, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            false,
            KnockbackDelivery.IMMEDIATE,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /** minehq as shipped 1.8.0 → 2.4.6: the archived values with the −3.9 verticalMin filler. */
    private static final KnockbackProfile MINEHQ_1_8 = new KnockbackProfile(
            "minehq",
            "MineHQ",
            "MineHQ's archived HCF values — between Kohi and vanilla, 1.7.10 ledger combos.",
            new Push(0.36, 0.36),
            VerticalMode.ADD,
            new Push(0.45, 0.09),
            new WtapExtra(false, 0.45, 0.09),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            true,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /** badlion as shipped 1.8.0 → 2.4.6: the archived values with the −3.9 verticalMin filler. */
    private static final KnockbackProfile BADLION_1_8 = new KnockbackProfile(
            "badlion",
            "Badlion",
            "Badlion's archived NoDebuff values — soft base 0.34, strong sprint"
                    + " differential, 1.7 ledger combos.",
            new Push(0.34, 0.34),
            VerticalMode.ADD,
            new Push(0.48, 0.085),
            new WtapExtra(false, 0.48, 0.085),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            true,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);
```

3. Replace the `BY_PRESET` map:

```java
    private static final Map<String, List<KnockbackProfile>> BY_PRESET = Map.of(
            "kohi", List.of(KOHI_1_3),
            "mmc", List.of(MMC_1_3),
            "lunar", List.of(LUNAR_1_3),
            "signature", List.of(SIGNATURE_2_2_0, SIGNATURE_2_2_1, SIGNATURE_2_4_0));
```
becomes
```java
    private static final Map<String, List<KnockbackProfile>> BY_PRESET = Map.of(
            "kohi", List.of(KOHI_1_3, KOHI_1_8),
            "mmc", List.of(MMC_1_3, MMC_1_8),
            "lunar", List.of(LUNAR_1_3, LUNAR_1_8),
            "minehq", List.of(MINEHQ_1_8),
            "badlion", List.of(BADLION_1_8),
            "signature", List.of(SIGNATURE_2_2_0, SIGNATURE_2_2_1, SIGNATURE_2_4_0));
```

### 2c. Move the bundled YAML files with the constants

**Modify** `core/src/main/resources/profiles/kohi.yml`:
```yaml
  limits:
    vertical: 0.4
    vertical-min: -3.9
    horizontal: -1
```
becomes
```yaml
  limits:
    vertical: 0.4
    # 0.0 since 2.4.7: a knock never points DOWN. The archived config carried
    # no vertical floor knob (-3.9 was Mental's schema filler); the ADD
    # vertical (vy x friction.y + base.vertical) went negative once a falling
    # victim's residual vy passed -0.87 (sprint) — a downward combo hit the
    # real server's flat play never produced. Restore -3.9 to unfloor.
    vertical-min: 0.0
    horizontal: -1
```

**Modify** `core/src/main/resources/profiles/mmc.yml`, `minehq.yml`, `badlion.yml`
(identical `old_string` in each file):
```yaml
  limits:
    vertical: 0.4
    vertical-min: -3.9
    horizontal: -1
```
becomes
```yaml
  limits:
    vertical: 0.4
    # 0.0 since 2.4.7: a knock never points DOWN (the archives carried no
    # vertical floor knob; kohi.yml has the full story). Restore -3.9 to unfloor.
    vertical-min: 0.0
    horizontal: -1
```

**Modify** `core/src/main/resources/profiles/lunar.yml`:
```yaml
  limits:
    # Below the 0.44 base on purpose: every grounded hit pins at the cap.
    vertical: 0.361735
    vertical-min: -3.9
    horizontal: -1
```
becomes
```yaml
  limits:
    # Below the 0.44 base on purpose: every grounded hit pins at the cap.
    vertical: 0.361735
    # 0.0 since 2.4.7: a knock never points DOWN (the archives carried no
    # vertical floor knob; kohi.yml has the full story). Restore -3.9 to unfloor.
    vertical-min: 0.0
    horizontal: -1
```

(Do NOT touch `velt.yml`, `signature.yml`, `custom.yml`, `legacy-1.7.yml`,
`legacy-1.8.yml` in this task.)

### 2d. Verify green

```bash
./gradlew :kernel:test
./gradlew :core:test --tests 'me.vexmc.mental.v5.config.ProfileParserTest' --tests 'me.vexmc.mental.v5.config.ConfigStoreTest' --tests 'me.vexmc.mental.v5.config.KnockbackDocsTest'
./gradlew build
```
**Expected:** all three `BUILD SUCCESSFUL`. In particular:
- `PresetVerticalFloorTest` — all 7 tests pass.
- `ProfileParserTest.everyBundledResourceParsesToItsPhase1PresetConstant` — green
  (YAML and constants moved together).
- `ProfileParserTest.emptyBlockParsesToLegacy17` — green, untouched (the era-exact
  no-op pin: `parse(empty) == LEGACY_17`, whose verticalMin stays −3.9).
- `ConfigStoreTest` existing tests — green (the MMC_1_3 fixture still parses to the
  1.3 revision and upgrades to the new bundle).
- `./gradlew build` includes the japicmp gate and the kernel-Bukkit-free assertion —
  both green (value-only change, no API shape moved).

**Commit:**
```
fix(knockback): floor the five practice presets' final vertical at 0.0

The owner's "downward knockback on the 2nd airborne combo hit, all
presets except signature and velt": in ADD mode the engine ships
y = vy·friction.y + base.vertical (+extra.vertical on a bonus hit) with
the -3.9 verticalMin filler as the only negative gate — a no-op — so a
deep falling ledger vy shipped a DOWNWARD knock. The immunity pattern is
exact arithmetic: velt/signature's friction.y 0.1 puts their negative
thresholds (-3.6) past the -3.92 airborne decay terminal, while
kohi/mmc/lunar/minehq/badlion leak at -0.576..-0.90.

The archives carried NO vertical floor knob (-3.9 was Mental's schema
filler, not a historical value), and the real servers' true physics
never reached the thresholds in flat play — every measured era hit-2
vertical is positive (compendium §1.4/§3) — so flooring the FINAL
vertical at 0.0 on the five practice presets is behavior-invariant for
all era-normal play and bites exactly the leak class. legacy-1.7/1.8
deliberately keep the unfloored era law (era vanilla DID knock a
long-falling victim downward; parse(empty)==LEGACY_17 unchanged), and
velt/signature are untouched down to their imperceptible terminal dips
(pinned). The shipped 1.8.0→2.4.6 revisions are archived in
SupersededPresets, so unedited installs upgrade pristine and every
owner-tuned file stays frozen.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01174ZCdfCNQhprYW7YHavYp
```

---

## Task 3 — core: pristine-upgrade proof + generalized upgrade log

### 3a. Generalize the upgrade log line (it cited one research round)

**Modify** `core/src/main/java/me/vexmc/mental/v5/config/ConfigStore.java`:
```java
            log.accept("profiles/" + preset + ".yml carried a superseded bundled revision"
                    + " unedited — upgraded to the corrected values"
                    + " (research 2026-06-12; delete the file to regenerate anytime)");
```
becomes
```java
            log.accept("profiles/" + preset + ".yml carried a superseded bundled revision"
                    + " unedited — upgraded to the corrected values"
                    + " (delete the file to regenerate anytime)");
```

### 3b. Prove the pre-floor upgrade end to end (TDD: write, run, green)

**Modify** `core/src/test/java/me/vexmc/mental/v5/config/ConfigStoreTest.java` —
append after the `tunedSupersededPresetIsNeverTouched` test:

```java
    /**
     * A kohi file exactly as 2.4.6 shipped it (vertical-min −3.9, every other
     * value the current archive) — parses to the KOHI_1_8 superseded revision.
     * Keys omitted here fall back to LEGACY_17 values, which for kohi equal the
     * constant's (friction 0.5, air 1.0, sprint 1.0, combos true, tracker wire).
     */
    private static final String KOHI_2_4_6_BODY = """
            display-name: Kohi
            description: "The canonical Kohi/HCF values — lower base, smaller per-level bonus (0.425/0.085), 1.7.10 ledger combos."
            knockback:
              base:
                horizontal: 0.35
                vertical: 0.35
              extra:
                horizontal: 0.425
                vertical: 0.085
              wtap-extra:
                enabled: false
                horizontal: 0.425
                vertical: 0.085
              limits:
                vertical: 0.4
                vertical-min: -3.9
                horizontal: -1
              delivery:
                melee: tracker
                projectile: tracker
              modifiers:
                combos: true
                armor-resistance: none
            """;

    @Test
    void unTunedPreFloorKohiUpgradesToThePracticeFloor() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("kohi").getParent());
        Files.writeString(profile("kohi"), KOHI_2_4_6_BODY, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration upgraded = YamlConfiguration.loadConfiguration(profile("kohi").toFile());
        assertEquals(0.0, upgraded.getDouble("knockback.limits.vertical-min"),
                "an unedited pre-floor kohi must gain the 2.4.7 practice floor");
        assertEquals(0.35, upgraded.getDouble("knockback.base.horizontal"),
                "the archived kohi values are untouched by the floor upgrade");
        assertTrue(logged.stream().anyMatch(line ->
                line.contains("kohi.yml") && line.contains("upgraded")),
                () -> "expected an upgrade report, logged: " + logged);

        // Idempotent: the upgraded file IS the current bundle — never re-flagged.
        String afterFirst = Files.readString(profile("kohi"), StandardCharsets.UTF_8);
        store.ensureDefaultFiles();
        assertEquals(afterFirst, Files.readString(profile("kohi"), StandardCharsets.UTF_8));
    }

    @Test
    void tunedPreFloorKohiKeepsItsOldFloorForever() throws Exception {
        ConfigStore store = store();
        Files.createDirectories(profile("kohi").getParent());
        String tuned = KOHI_2_4_6_BODY.replace("horizontal: 0.35", "horizontal: 0.37");
        Files.writeString(profile("kohi"), tuned, StandardCharsets.UTF_8);

        store.ensureDefaultFiles();

        YamlConfiguration kept = YamlConfiguration.loadConfiguration(profile("kohi").toFile());
        assertEquals(0.37, kept.getDouble("knockback.base.horizontal"),
                "owner-tuned values must survive every upgrade pass");
        assertEquals(-3.9, kept.getDouble("knockback.limits.vertical-min"),
                "a tuned file keeps its own floor — frozen forever");
        assertFalse(logged.stream().anyMatch(line ->
                line.contains("kohi.yml") && line.contains("upgraded")),
                () -> "no upgrade may be reported for a tuned file, logged: " + logged);
    }
```

**Run:**
```bash
./gradlew :core:test --tests 'me.vexmc.mental.v5.config.ConfigStoreTest'
```
**Expected:** `BUILD SUCCESSFUL`; both new tests pass on first run (the upgrade
mechanism is generic — these prove the KOHI_1_8 revision content is byte-correct
against the parser; a description/value mismatch in Task 2b fails here loudly).

**Commit:**
```
test(config): prove the pre-floor practice presets upgrade pristine

A kohi file exactly as 2.4.6 shipped it (vertical-min -3.9, archived
values otherwise) upgrades in place to the 2.4.7 floor; one tuned value
freezes it forever. Also generalizes the upgrade console line — it
hardcoded the 2026-06-12 research round, and the superseded mechanism
now serves multiple correction rounds.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01174ZCdfCNQhprYW7YHavYp
```

---

## Task 4 — docs + era-preset YAML comments

### 4a. `docs/knockback-profiles.md`

Insert after the paragraph ending
`[research/2026-06-12-archived-server-values.md](research/2026-06-12-archived-server-values.md).`
(before `## The knob vocabulary`):

```markdown

**The practice floor (2.4.7).** The five archived practice presets (`kohi`,
`minehq`, `badlion`, `mmc`, `lunar`) ship `limits.vertical-min: 0.0`: a knock
never points DOWN. The archives carried no vertical floor knob (`-3.9` was
Mental's schema filler, a no-op), and the ADD vertical
(`vy × friction.y + base.vertical`) goes negative once a falling victim's
residual `vy` runs past `−(base + extra) / friction.y` — −0.58 … −0.90 on these
presets, thresholds the archived servers' true physics never reached in flat
play. `velt` and `signature` are untouched (`friction.y 0.1` puts their
thresholds past the −3.92 decay terminal — the bug's own immunity pattern), and
the era presets (`legacy-1.7`, `legacy-1.8`) deliberately keep the unfloored
era law: real 1.7/1.8 DID knock a long-falling victim downward
(`motY < −0.8`), and legacy stays byte-exact to it.
```

Then update the schema comment:
```yaml
    vertical-min: -3.9      # floors the FINAL vertical; -3.9 = off
```
becomes
```yaml
    vertical-min: -3.9      # floors the FINAL vertical (post-air-multiplier);
                            # -3.9 = off. The five archived practice presets
                            # ship 0.0 — a knock never points DOWN (2.4.7)
```

### 4b. Era-preset YAML comments (comment-only; parsing unchanged)

**Modify** `core/src/main/resources/profiles/legacy-1.7.yml` AND
`core/src/main/resources/profiles/legacy-1.8.yml` (identical `old_string` in each):
```yaml
  limits:
    vertical: 0.4
    vertical-min: -3.9
    horizontal: -1
```
becomes
```yaml
  limits:
    vertical: 0.4
    # Deliberately unfloored: era vanilla DID knock a long-falling victim
    # downward (motY below -0.8) — legacy stays byte-exact to the era law.
    # The practice presets (kohi/minehq/badlion/mmc/lunar) floor at 0.0.
    vertical-min: -3.9
    horizontal: -1
```

**Run:**
```bash
./gradlew :core:test --tests 'me.vexmc.mental.v5.config.KnockbackDocsTest' --tests 'me.vexmc.mental.v5.config.ProfileParserTest'
```
**Expected:** `BUILD SUCCESSFUL` (docs still carry every knob key; comments don't
parse — every bundled file still equals its constant). No GUI surface exists for
`limits` (verified: the management GUI selects profiles by name only and displays no
per-knob values), so no GUI work.

**Commit:**
```
docs(knockback): document the 2.4.7 practice floor and the era exemption

The preset reference gains the practice-floor paragraph (which presets
floor, the exact leak arithmetic and thresholds, why velt/signature and
the era presets are exempt), the schema comment notes the post-air
ordering, and the legacy preset files say out loud why they stay
unfloored — era vanilla did knock long-falling victims downward.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01174ZCdfCNQhprYW7YHavYp
```

---

## Task 5 — tester: an airborne leak-class hit ships y ≥ 0 through the journal

The journal is the "what did we actually ship" seam; post-2.4.6 the sweep never
time-drops connected melee, so any downward wire is necessarily a journaled Mental
SHIP — exactly what this forbids.

**Modify** `tester/src/main/java/me/vexmc/mental/tester/suite/KnockbackSuite.java`.

1. Add the case to `tests(…)` — the `List.of(…)` becomes:

```java
        return List.of(
                new TestCase("knockback: plain hit matches engine + tracker delivery", context ->
                        runScenario(mental, tester, context, false)),
                new TestCase("knockback: sprint hit matches engine + tracker delivery", context ->
                        runScenario(mental, tester, context, true)),
                new TestCase("knockback: second hit stacks the ledger residual (1.7.10 combos)", context ->
                        runComboScenario(mental, tester, context)),
                new TestCase("knockback: a packetless attacker's sprint hit never poisons its ledger feed",
                        context -> runDomainPoisonScenario(mental, tester, context)),
                new TestCase("knockback: a packetless victim's server-side melee always journals a SHIP",
                        context -> runPacketlessMeleeJournalScenario(mental, tester, context)),
                new TestCase("knockback: an airborne leak-class hit on a practice preset never ships a downward knock",
                        context -> runAirborneFloorScenario(mental, tester, context)));
```

2. Append the scenario after `runPacketlessMeleeJournalScenario` (before the
   `countShips` helper):

```java
    /**
     * The 2.4.7 downward-knock pin (the owner's "2nd airborne combo hit knocks
     * DOWN" bug). The ADD vertical (vy × friction.y + base.vertical) goes
     * negative once the victim's LEDGER vy falls past −(base + extra)/friction.y
     * — kohi sprint: −0.87 — which a free-falling airborne ledger reaches
     * ~15–20 ticks after an airborne hit. velt/signature were immune
     * (friction.y 0.1); every practice preset leaked. The fix floors the five
     * practice presets' final vertical at 0.0 (limits.vertical-min), and this
     * stages exactly the leak class live and asserts the SHIP through the
     * journal — the "what did we actually ship" seam: y ≥ 0, exactly the
     * floored 0.0, and exactly the engine expectation (formula parity survives
     * the floor; the floored value is also tick-slack-immune, since one more
     * decay tick only deepens the pre-floor negative).
     *
     * <p>Staging: a clientless fake never moves from a knock and never lands its
     * ledger through the rim, so the ledger is driven airborne PHYSICALLY — the
     * per-tick upward motion (the ComboSuite float pattern) keeps the honest
     * combat-ground feed reading airborne, hit 1 records its stamp on the
     * airborne ledger branch, and the ledger then free-falls
     * (vy ← (vy − 0.08) × 0.98) into the leak zone while the victim stays
     * airborne. Latency compensation is disabled for the case so the region
     * path carries no vy override and the in-suite engine expectation (null
     * override) is exact.</p>
     */
    private static void runAirborneFloorScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            context.syncRun(() -> {
                mental.overlaySet("modules.latency-compensation", false);
                mental.reloadAll();
                context.expect(mental.management().setGlobalProfile("kohi"), "kohi preset missing");
            });
            context.awaitTicks(3);
            context.expect(context.sync(() ->
                            profileFor(mental, victim).limits().verticalMin() == 0.0),
                    "kohi must carry the 2.4.7 practice floor (vertical-min 0.0)");

            // Float the victim so the honest feed reads airborne every tick and
            // hit 1 records on the airborne ledger branch (the ComboSuite pattern).
            context.syncRun(() -> victim.preTick(() -> victim.setMotion(0.0, 0.42, 0.0)));
            context.awaitTicks(4);

            // Hit 1: an airborne sprint hit — the ledger now carries the shipped
            // stamp and free-falls tick by tick.
            context.syncRun(() -> {
                attacker.player().setSprinting(true);
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });

            // Wait for the free-fall to cross the leak threshold with margin:
            // kohi sprint ships negative below vy −0.87; −1.0 gives a pre-floor
            // y of −1.0 × 0.5 + 0.35 + 0.085 = −0.065 — unambiguously leak-class.
            CombatSession session = mental.sessions().sessionFor(victim.uuid());
            context.expect(session != null, "no combat session for the victim");
            context.awaitUntil(() -> {
                try {
                    return context.sync(() ->
                            EntityStates.captureVictim(victim.player(), session.ledger()).vy() <= -1.0);
                } catch (Exception failure) {
                    return false;
                }
            }, 80, "the airborne ledger to free-fall into the leak zone (vy <= -1.0)");

            // Hit 2 — the reported downward hit. Same-tick engine expectation
            // (null override: latency compensation is off for this case).
            KnockbackVector[] expected = new KnockbackVector[1];
            double[] ledgerVy = new double[1];
            int shipsBefore = context.sync(() -> {
                EntityState attackerState = EntityStates.capture(attacker.player());
                EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                ledgerVy[0] = victimState.vy();
                KnockbackProfile profile = profileFor(mental, victim);
                expected[0] = SuiteDelivery.melee(
                        KnockbackEngine.compute(attackerState, victimState, profile, null),
                        profile, victimState.grounded());
                victim.player().setNoDamageTicks(0);
                int before = countShips(mental, victim);
                attacker.attack(victim.player());
                return before;
            });
            context.expect(ledgerVy[0] <= -1.0,
                    "staging failed — the ledger vy must be leak-class (got " + ledgerVy[0] + ")");

            JournalEntry ship = awaitNewShip(context, mental, victim, shipsBefore);
            context.expect(ship != null && ship.shipped() != null,
                    "the airborne leak-class hit journaled no SHIP");
            context.expect(ship.shipped().y() >= 0.0,
                    "a practice-preset knock must NEVER point down (shipped y "
                            + ship.shipped().y() + " off ledger vy " + ledgerVy[0] + ")");
            context.expectNear(0.0, ship.shipped().y(), 1.0e-9,
                    "the leak-class hit must ship exactly the floored 0.0 vertical");
            context.expect(expected[0] != null, "engine returned no vector for the staged hit");
            context.expectNear(expected[0].y(), ship.shipped().y(), 1.0e-9,
                    "the journaled SHIP must equal the engine expectation (formula parity)");
        } finally {
            context.syncRun(() -> {
                victim.preTick(null);
                mental.overlaySet("modules.latency-compensation", true);
                mental.reloadAll();
                mental.management().setGlobalProfile("legacy-1.7");
                attacker.remove();
                victim.remove();
            });
        }
    }
```

(No new imports are needed — `KnockbackEngine`, `EntityState`, `KnockbackVector`,
`JournalEntry`, `KnockbackProfile`, `CombatSession`, `EntityStates`, and
`SuiteDelivery` are already in scope in this file.)

**Run (compile check now; the live run happens in the Task 6 gate):**
```bash
./gradlew :tester:compileJava
```
**Expected:** `BUILD SUCCESSFUL`.

**Commit:**
```
test(tester): pin airborne leak-class hits shipping a non-negative vertical

Stages the exact owner-reported class live on kohi: an airborne hit
records its stamp on the airborne ledger branch, the ledger free-falls
past the -0.87 sprint threshold into vy <= -1.0, and the follow-up hit's
journaled SHIP — the "what did we actually ship" seam — must carry
exactly the floored 0.0 vertical, matching the engine expectation.
Latency compensation is disabled for the case so the null-override
expectation is exact; the floored value is tick-slack-immune (a later
read only deepens the pre-floor negative).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01174ZCdfCNQhprYW7YHavYp
```

---

## Task 6 — version bump + the full gate

1. **Modify** `gradle.properties`: `version=2.4.6-beta` → `version=2.4.7-beta`
   (skip if the release round already bumped it — check first).

2. **Run the gate, in order (matrix-gate skill governs how to read results):**
```bash
./gradlew build
```
**Expected:** `BUILD SUCCESSFUL` — unit tests, japicmp, the kernel-Bukkit-free
assertion, and the four mega-jar gates (`verifyDowngrade`, `verifyJdk8Api`,
`verifyTesterIsolation`, `verifyRelocation`) all green.

```bash
scripts/integration-matrix.sh
./gradlew integrationTestFolia
```
**Expected:** every matrix entry PASS, and — the honesty rule — each
`test-results.txt` carries **this run's nonce** with PASS; never trust the
"BUILD SUCCESSFUL" banner alone. The new KnockbackSuite case must appear as a PASS
line in every entry's results (it needs no version gating — kohi, the ledger, and
the journal exist on every supported tier). Watch specifically for the Folia lane
(`teleport`-free staging — the scenario uses `preTick` motion only, no teleports,
so no Folia region hazard).

3. **Commit:**
```
chore(release): 2.4.7-beta — the downward-knock floor round

Gate: ./gradlew build + scripts/integration-matrix.sh +
integrationTestFolia, nonce-checked per entry.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01174ZCdfCNQhprYW7YHavYp
```

---

## Follow-up (explicitly OUT of this round)

The ledger-divergence diagnosis (which mode puts flat-ground vy below threshold on
the live SimpleBoxer: the non-rising liftoff mis-stamp vs a missed landing vs a
boxer-client packet quirk) needs a live capture of the boxer's movement packets
around hit 1 (onGround flag + y per packet) or a DebugLog chat-subscribe trace
correlating ledger vy against PositionRing y at the 2nd hit. The floor makes the
symptom unreachable regardless of the answer; the diagnosis decides whether a
ledger-fidelity round (option b) is ever warranted. File it as its own
investigation — do not fold it into this fix.

---

## Risk register

| # | Risk | The pin that catches it |
|---|---|---|
| 1 | The floor accidentally lifts or reorders a positive vertical (feel change in era-normal play) | `KnockbackEngineTest.verticalMinFloorAppliesAfterTheAirMultiplierAndNeverLiftsAPositive` (Task 1) + `PresetVerticalFloorTest.theFloorIsInertForEveryEraNormalHit` (0.24805 / 0.3958 exact) |
| 2 | velt/signature feel changes at all (owner's hard constraint) | `PresetVerticalFloorTest.veltAndSignatureAreUntouchedIncludingTheTerminalDip` (+0.269457 / +0.26896786, and the −0.032 / −0.02646 terminal dips still ship) |
| 3 | Era-exactness regression: legacy presets or the parse-empty default gain the floor | `PresetVerticalFloorTest.legacyPresetsKeepTheUnflooredEraLaw` (−0.6 @ vy −2.0) + `ProfileParserTest.emptyBlockParsesToLegacy17` + `PresetsTest` (−3.9 asserted on legacy-1.7/1.8) + `custom.sameValues(LEGACY_17)` |
| 4 | YAML and kernel constants drift (a regenerated preset differs from the constant) | `ProfileParserTest.everyBundledResourceParsesToItsPhase1PresetConstant` — fails on either side moving alone |
| 5 | A superseded revision is content-wrong (typo'd value or description) → unedited 2.4.6 installs silently DON'T upgrade | `SupersededPresetsTest.uneditedPreFloorPracticePresetsUpgradeButTheCurrentBundlesDoNot` (kernel, value-level) + `ConfigStoreTest.unTunedPreFloorKohiUpgradesToThePracticeFloor` (core, real YAML through the real parser) |
| 6 | Upgrade loop (the current bundle re-flags as superseded and rewrites every boot) | the same two tests assert `isSupersededVerbatim(current) == false` and byte-identical idempotence across a second `ensureDefaultFiles()` |
| 7 | An owner-tuned practice file gets clobbered by the upgrade | `ConfigStoreTest.tunedPreFloorKohiKeepsItsOldFloorForever` + the existing tuned-preset tests |
| 8 | The floor doesn't actually reach the wire (some delivery path bypasses `finish`) | the Task 5 journal assertion — the SHIP seam itself carries y = 0.0 on a staged live leak-class hit; post-2.4.6 no connected-melee path escapes the journal |
| 9 | Servo/verticalStamp divergence from the shipped vertical | structural: `shippedVertical` (`KnockbackEngine.java:381-383`) and `finish` (`:527-529`) read the same `profile.limits().verticalMin()` knob — no second constant exists to drift; leak-class stamps floor to 0.0 → airTime < 3 → the servo declines (`PocketServo.MIN_AIR_TICKS_FOR_SERVO`), already its behavior for negative stamps |
| 10 | Projectile/rod behavior change surprises (computeBase floors too) | `PresetVerticalFloorTest.projectilePathFloorsTheSameLeakClass` pins it deliberately; documented in the docs paragraph — same owner intent, same leak class |
| 11 | japicmp / kernel-additive violation | value-only changes to `public static final` constants + private superseded revisions; `./gradlew build` runs japicmp and the kernel-Bukkit-free assertion in Task 2d — the 1.8.0 archived-values round is the precedent |
| 12 | The tester staging is flaky (ledger never reaches −1.0, or the fake lands) | `awaitUntil(…, 80, …)` polls the actual ledger read (the free-fall needs ~20–25 ticks from the hit-1 stamp); the victim floats continuously so no landing can re-equilibrate; the explicit `ledgerVy[0] <= -1.0` expect fails LOUDLY (staging error, not a silent pass) if staging breaks |
| 13 | The flat-ground bug persists on legacy-1.7 (unfloored) and the owner reports it again | accepted + documented: legacy's thresholds (−0.8/−1.0) ship ≈ +0.01…+0.05 at the reported combo timing even under ledger divergence — near-zero but not downward; if a genuine legacy downward is ever reported flat-ground, that is the ledger-divergence follow-up's trigger, not a floor candidate (era law) |

# Strafe chase alignment — the combo servo prices the attacker's measured heading

**Branch:** `release/2.4.7-beta` · **Owner bug:** "combo solver works perfect
approaching straight-line, but strafing (W+A/W+D) overshoots a little, pushing
the victim just out of combo range. Fix must NOT change straight-line behavior
but must incorporate the attacker's movement vector when strafing."

**Ground truth:** the strafe evidence dossier (this round),
`docs/superpowers/research/2026-07-07-servo-undershoot-chase-floor.md` (the
2.4.6 floor's provenance), and
`docs/superpowers/research/2026-07-07-dynamic-chase-movement-constants.md`
(§3a: W+A/W+D sprint = pure-W magnitude rotated 45°; §3b: the w-tap dip).

---

## Root-cause verdict

**Confirmed, with the dossier's re-attribution.** Every MODEL chase channel in
`PocketServo.simulate()` — the dynamic ramp (priority channel for any
w-tapping modern attacker), the attribute fallback, and the 2.4.6
`CHASE_ALIGNMENT = 0.70` floor — prices the attacker's chase as if their
velocity lay ON the knock axis. `Entity.moveFlying` normalizes a W+A/W+D input
to the pure-W impulse magnitude rotated 45° off facing
(`legacy-lab/decomp-1.8.9/pk.java:652–665`), and a combo attacker keeps the
crosshair on the victim, so a strafer's true axis-closing rate is
`cos 45° ≈ 0.7071` of their speed — the aligned models over-price the close by
~41%, `σ*` over-solves, and the knock ships too hard.

Numbers (the dossier's representative mid-combo hit — d0 2.85, F 0.40, R 0.10,
V0 0.40 grounded stone launch → airTime 11, target 3.150, attr 0.10, w′ = 12,
slope D(12) = 4.916830084; true straight close 0.24 b/t, true strafe close
cos 45° × 0.24 = 0.1697 b/t; landing = d0 + (R + σF)·slope − trueClose·12):

| channel (priced chase) | σ* | σ shipped | landing vs target, straight truth | landing vs target, strafe truth |
|---|---|---|---|---|
| dynamic 3.183 | 1.521 | **1.35** (ceiling) | **−0.033** (perfect) | **+0.810** |
| measured→floored 2.357 | 1.101 | 1.101 | −0.523 | **+0.321** |
| true strafe price 2.036 | 0.938 | 0.938 (interior) | — | **±0.000** |

The σ* a strafe hit actually needs (0.94–1.14) sits INSIDE the shipped
[0.93, 1.35] band — the servo could land it; the alignment-1.0 channels price
it 0.4–0.7 too high in σ and ship +0.3…+0.8 blocks of extra separation. With
the boundary target riding `sepReach − jitterMargin = 3.15` against the era
3.30 reach cap, +0.3–0.8 puts the victim past the attacker's reach — exactly
"just out of combo range". Straight-line stays perfect because the 2.4.6
calibration (floor 0.70 + clamps [0.93, 1.35]) was fitted to straight-line
truth and IS right there — the strafe error is entirely the un-modeled cos 45°.

The unconditional floor is the *secondary* offender (its 0.19642 b/t rate is a
near-coincidental match for the clean-strafe 0.19842); the *primary* offender
is the dynamic channel, which violates `DynamicChase`'s own caller contract
("steadySpeed is already alignment- and technique-resolved by the caller",
`DynamicChase.java:17–19`) — the core caller never resolves alignment.

**Post-fix arithmetic** (alignment cos 45° folded into the model channels):
dynamic prices 0.7071×3.183 = 2.251 → σ* 1.047 (interior) → strafe landing
**+0.214** (down from +0.810); floored channel prices 0.7071×2.357 = 1.667 →
σ* 0.750 → min-clamps 0.93 → strafe landing **−0.016** (down from +0.321).
The residual +0.21 on the dynamic channel is the model's pre-existing ~10%
over-price of dipped sprint that the 1.35 ceiling absorbs on straight-line but
which ships interior on strafe — a future calibration item, noted in the risk
register; this fix removes the geometry error (74% of the strafe overshoot)
without touching the straight-line calibration.

---

## Decision

### Options weighed

**(a) Alignment-scaled models — CHOSEN.** Compute a measured attacker-heading
alignment `â = dot(normalized attacker horizontal heading, knock axis u)` from
the attacker's PositionRing net displacement (the same ring-delta doctrine as
the 2.4.6 fast-pot fix — never `Player.getVelocity()`, which is ~0 for a
grounded player under send-then-restore, `legacy-motion-physics` skill lines
37–40). Plumb it additively as `PredictorInputs.chaseAlignment` (NaN default ⇒
1.0 ⇒ byte-identical) and scale every MODEL channel — dynamic steadySpeed,
attribute rate, and the 2.4.6 floor — by
`clamp(â, cos 45°, 1.0)` inside `simulate()`. The MEASURED window channel is
**never** scaled: axis projection is its definition
(`PocketServo.windowChaseRate`), so it already embodies strafe geometry.

**(b) Trust the measured window when the gap is high-quality, floor only on
poor measurements — REJECTED.** The measured window's under-read is
*multiplicative structural dilution*, not gap noise: it time-averages net
displacement over the whole inter-hit gap including the at-range idle/back-off
phase (the 2.4.6 research measured ~0.11 b/t = 41% of sprint on
straight-line pursuit — research doc lines 26–30). A strafing attacker's
window reads ~41% of their already-cos45-reduced close (~0.078 b/t), so
"trusting" it on any gap-quality signal re-creates the 2.4.6 undershoot in
strafe form (σ* ≈ 0.48 at the representative hit → pinned at the min clamp,
victim held a half-block too close). De-diluting it would require a
burst-phase model that does not exist.

**(c) Hybrid (scale models AND lift measured-window trust) — REJECTED.** Adds
a second new estimator (window quality) for no demonstrated need: the floor
already lets a genuinely harder measured chase win (`chaseTravel <
alignedFloorTravel` is a one-sided lift), and option (a) alone lands the
strafe hit within ±0.21 of target.

### The binding constraint: straight-line is preserved EXACTLY

Alignment enters every model channel as a leading multiplication:

```
dynamic:   DynamicChase.projectTravel(alignment * steadySpeed, …)
attribute: chaseRate = alignment * SPRINT_GROUND_SPEED * chaseFactor(attr)
floor:     alignedFloorTravel = alignment * CHASE_ALIGNMENT * SPRINT_GROUND_SPEED
                                * chaseFactor(attr) * wPrime
```

For a straight-line approach the input is either `NaN` (no measurable heading
— a stationary or packetless attacker) or a measured dot ≈ 1.0; both resolve
through `chaseAlignmentFactor` to exactly `1.0`. IEEE-754 multiplication by
`1.0` is exact (`1.0 × x == x` bit-for-bit for every finite x), so every
downstream double is bit-identical: at the 2.4.6 touchdown pin,
`alignedFloorTravel = 1.0 × 0.70 × 0.2806 × 1.0 × 10 = 1.9642` (the identical
double), and

```
σ* = (2.85 − (2.55 − 1.9642) − 4.470559213·0.02) / (0.325·4.470559213)
   = 1.496827931   — unchanged to the last bit.
```

Task 3 pins this with **0.0-delta** (not epsilon) equality assertions between
the NaN-alignment and 1.0-alignment solves, and every existing 2.4.6 hand pin
(1.496827931, 0.965362591, 4.033269182) is left untouched and must still pass.

### Degenerate cases

- **Near-zero attacker velocity** (alignment undefined): the heading magnitude
  guard fires *before* normalization — `|heading| < MIN_HEADING_BLOCKS (0.05)`
  returns `Double.NaN`, never divides, and NaN ⇒ factor 1.0 ⇒ current behavior
  exactly. Covers stationary attackers, packetless fakes with no ring history
  (`recent()` < 2 samples ⇒ NaN), and standing jitter.
- **Backpedal** (negative dot): `headingAlignment` returns the RAW negative
  dot; `chaseAlignmentFactor` clamps it UP to `cos 45°`. Rationale: below the
  full-forward-strafe stance the attacker is not in a crosshair-on-victim
  chase — the combo's own end rules (gap / blowout / retaliation) are the
  honest authority — and pricing a near-zero or negative chase off one noisy
  4-tick heading (a w-tap instant, or the attacker's own received knock) would
  crash σ into the min clamp and resurrect the 2.4.6 undershoot in strafe
  form. The clamp bounds the model's downside at the deepest legitimate
  stance.
- **Knocked-while-jumping / knocked-mid-trade attacker** (their own received
  knock pollutes the heading): in the standard case the victim's retaliating
  hit ENDS the combo (`ComboSuite` retaliation rule) so the servo is inactive
  (σ = 1) before the polluted heading could price anything. A third-party
  knock can tilt the heading for ≤ 4 ticks; the cos 45° clamp bounds the
  effect to the strafe price and the next window self-corrects.
- **W-tap magnitude dip at the hit instant** (the trap that killed the 2.4.5
  pre-hit trend): only the SPEED dips in a w-tap; the direction survives. The
  heading is the NET displacement over 5 ring samples (4 tick deltas — longer
  than the 3–4-tick dip recovery), and only its direction is consumed.

---

## What this does NOT change

- **Straight-line solves** — bit-identical (the 0.0-delta pins in Task 3).
- **The clamps** — `ComboSettings.DEFAULTS` stays `[0.93, 1.35]`; the tester's
  `SERVO_MIN/SERVO_MAX` stay `0.93/1.35`.
- **The measured window channel** — `windowChaseRate`, its EMA, the gap
  guards, and `ServoMemory` are untouched (it already embodies strafe
  geometry).
- **`CHASE_ALIGNMENT = 0.70`** — the straight-line effectiveness fraction
  keeps its value and its calibration; the new factor composes with it.
- **The vertical stamp, delivery, valve, journal, freshEra/residual
  extraction, the v1 four-input sigma path** — untouched.
- **No new config knob** — the alignment is measured, not configured (zero
  parse-surface change; `parse(empty) == LEGACY_17` untouched). The combo
  module itself stays default-OFF (zero-touch).
- **No preset values, no client technique contract** (0.6 self-multiplier,
  w-tap, jump-resets — attacker-client-side, untouchable).
- **No `EntityState` growth, no `Solution` growth** — the whole seam is the
  one additive `PredictorInputs` component (the record's established growth
  pattern, fourth application).

---

## Task 0 — preflight

```bash
cd /Users/owengregson/Documents/StrikeSync
git status                      # expect: On branch release/2.4.7-beta, clean
./gradlew build                 # expect: BUILD SUCCESSFUL (baseline green)
```

---

## Task 1 — kernel: the alignment primitives (TDD)

**Files:**
- Modify `kernel/src/test/java/me/vexmc/mental/kernel/math/PocketServoPrecisionTest.java`
- Modify `kernel/src/main/java/me/vexmc/mental/kernel/math/PocketServo.java`

### Step 1.1 — write the failing pins

Append to `PocketServoPrecisionTest.java` (before the
`/* ── independent fold + brute references … */` section):

```java
    /* ── the strafe chase alignment (2.4.7) ────────────────────────────────── */

    @Test
    void chaseAlignmentFactorClampsTheStrafeBand() {
        // NaN (no heading signal) → the aligned 1.0 — byte-identical to every
        // pre-round solve. This is the era-exact no-op default of the round.
        assertEquals(1.0, PocketServo.chaseAlignmentFactor(Double.NaN), 0.0);
        // A measured dot inside the band passes through untouched.
        assertEquals(0.9, PocketServo.chaseAlignmentFactor(0.9), EPSILON);
        assertEquals(PocketServo.MIN_CHASE_ALIGNMENT,
                PocketServo.chaseAlignmentFactor(PocketServo.MIN_CHASE_ALIGNMENT), 0.0);
        // Above-aligned (numeric overshoot) clamps to 1.0; an orbit or backpedal
        // clamps to cos 45° — the servo never prices a sub-strafe or negative
        // chase off one noisy heading (the combo end rules own those stances).
        assertEquals(1.0, PocketServo.chaseAlignmentFactor(1.2), 0.0);
        assertEquals(PocketServo.MIN_CHASE_ALIGNMENT, PocketServo.chaseAlignmentFactor(0.3), 0.0);
        assertEquals(PocketServo.MIN_CHASE_ALIGNMENT, PocketServo.chaseAlignmentFactor(-0.5), 0.0);
        // The band floor is cos 45° exactly — the deepest crosshair-on-victim
        // stance (moveFlying normalizes W+A to pure-W magnitude rotated 45°).
        assertEquals(0.7071067811865476, PocketServo.MIN_CHASE_ALIGNMENT, 0.0);
    }

    @Test
    void headingAlignmentIsTheNormalizedAxisDot() {
        // A diagonal heading on a +x axis reads cos 45°: (1,1)/√2 · (1,0).
        assertEquals(0.7071067811865476, PocketServo.headingAlignment(1.0, 1.0, 1.0, 0.0), EPSILON);
        // Straight down the axis reads 1 — magnitude is irrelevant once measurable.
        assertEquals(1.0, PocketServo.headingAlignment(0.6, 0.8, 0.6, 0.8), EPSILON);
        // Backpedal reads the negative dot RAW — clamping is chaseAlignmentFactor's
        // job, so a lab consumer can still see the true stance.
        assertEquals(-1.0, PocketServo.headingAlignment(-0.5, 0.0, 1.0, 0.0), EPSILON);
        // Standing jitter (|heading| < 0.05 blocks over the span) has no direction:
        // NaN, never a division by the near-zero magnitude.
        assertTrue(Double.isNaN(PocketServo.headingAlignment(0.03, 0.03, 1.0, 0.0)));
        assertTrue(Double.isNaN(PocketServo.headingAlignment(0.0, 0.0, 1.0, 0.0)));
    }
```

Hand arithmetic: `(1,1)·(1,0)/|(1,1)| = 1/√2 = 0.7071067811865476`;
`|(0.03,0.03)| = 0.0424 < 0.05 → NaN`; `(0.6,0.8)` is already unit so the
self-dot is 1.

### Step 1.2 — run, expect compile failure (RED)

```bash
./gradlew :kernel:test --tests "me.vexmc.mental.kernel.math.PocketServoPrecisionTest"
```

Expected: compilation error — `chaseAlignmentFactor`, `headingAlignment`,
`MIN_CHASE_ALIGNMENT` do not exist.

### Step 1.3 — implement

In `PocketServo.java`, insert after the `CHASE_ALIGNMENT` constant
(line 93):

```java
    /**
     * The floor of the measured chase-alignment band (2.4.7 strafe fix): cos 45°.
     * A combo attacker steers with the crosshair ON the victim, so the deepest
     * sustained stance their velocity can hold off the knock axis is the full
     * forward-strafe (W+A / W+D) — moveFlying normalizes the (0.98, 0.98) diagonal
     * input to the pure-W impulse magnitude and rotates it 45° off facing
     * (dynamic-chase-movement-constants §3a). Below this the attacker is not
     * meaningfully chasing (an orbit or a backpedal) and the combo's own end rules
     * are the honest authority, so a noisier / lower measured dot clamps here
     * instead of over-shrinking the priced chase into a strafe-flavoured 2.4.6
     * undershoot.
     */
    public static final double MIN_CHASE_ALIGNMENT = Math.sqrt(0.5);

    /**
     * The least attacker net displacement (blocks, over the sampled heading span)
     * that counts as a measurable heading. Below it the direction is standing
     * jitter, not movement — the alignment degrades to {@link Double#NaN} (⇒ the
     * aligned 1.0 model, byte-identical to the pre-round solve).
     */
    public static final double MIN_HEADING_BLOCKS = 0.05;
```

Insert the two methods next to `windowChaseRate` (after it, before
`/* ── shared flight helpers … */`):

```java
    /**
     * The attacker's movement-heading alignment with the knock axis (2.4.7 strafe
     * fix): the dot of the NORMALIZED horizontal heading {@code (headingX,
     * headingZ)} — the attacker's net displacement over the last few ticks — with
     * the attacker→victim unit axis {@code (ux, uz)}. +1 = chasing straight down
     * the axis; ≈ +0.7071 = a full forward-strafe (W+A / W+D); ≤ 0 = orbiting or
     * backpedaling. Returned RAW (unclamped) so consumers can observe the true
     * stance; the solve clamps through {@link #chaseAlignmentFactor}. A heading
     * shorter than {@link #MIN_HEADING_BLOCKS} is standing jitter — no measurable
     * direction — and returns {@link Double#NaN} without dividing.
     */
    public static double headingAlignment(double headingX, double headingZ, double ux, double uz) {
        double magnitude = Math.sqrt(headingX * headingX + headingZ * headingZ);
        if (!(magnitude >= MIN_HEADING_BLOCKS)) {
            return Double.NaN;
        }
        return (headingX * ux + headingZ * uz) / magnitude;
    }

    /**
     * The chase-alignment factor the MODEL channels price (2.4.7 strafe fix):
     * {@link Double#NaN} (no signal) resolves to the aligned 1.0 — byte-identical
     * to every pre-round solve — and a measured dot clamps into
     * {@code [MIN_CHASE_ALIGNMENT, 1.0]}. The low clamp is deliberate: below
     * cos 45° the attacker is not in a crosshair-on-victim chase stance and the
     * combo's own end rules (gap / blowout / retaliation) own the outcome — the
     * servo must not price a near-zero or NEGATIVE chase off one noisy heading (a
     * w-tap instant, or the attacker's own received knock), which would over-shrink
     * σ and resurrect the 2.4.6 undershoot in strafe form.
     */
    public static double chaseAlignmentFactor(double alignment) {
        if (Double.isNaN(alignment)) {
            return 1.0;
        }
        return Math.max(MIN_CHASE_ALIGNMENT, Math.min(1.0, alignment));
    }
```

### Step 1.4 — run, expect green

```bash
./gradlew :kernel:test --tests "me.vexmc.mental.kernel.math.PocketServoPrecisionTest"
```

Expected: `BUILD SUCCESSFUL`, all tests pass (existing pins untouched).

### Step 1.5 — commit

```bash
git add kernel/src/main/java/me/vexmc/mental/kernel/math/PocketServo.java \
        kernel/src/test/java/me/vexmc/mental/kernel/math/PocketServoPrecisionTest.java
git commit -m "$(cat <<'EOF'
feat(kernel): measured heading-alignment primitives for the combo chase

A strafing (W+A/W+D) attacker's velocity sits 45 degrees off their
crosshair -- moveFlying normalizes the diagonal input to the pure-W
impulse magnitude rotated 45 degrees off facing -- so their true
axis-closing rate is cos45 of their speed, while every model chase
channel in the servo prices full alignment. headingAlignment() turns
the attacker's ring net-displacement into a raw normalized axis dot
(NaN below 0.05 blocks -- standing jitter has no direction, and the
guard fires before the normalization so it can never divide by zero);
chaseAlignmentFactor() maps NaN to the aligned 1.0 (byte-identical
pre-round behavior) and clamps a measured dot into [cos45, 1.0] -- the
deepest crosshair-on-victim stance bounds the model's downside so one
noisy heading (a w-tap instant, the attacker's own received knock)
can never price a sub-strafe or negative chase.

Pure JDK, additive only. Consumed by the solve in the next commit.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01174ZCdfCNQhprYW7YHavYp
EOF
)"
```

---

## Task 2 — kernel: `PredictorInputs.chaseAlignment` (additive growth)

**Files:**
- Modify `kernel/src/test/java/me/vexmc/mental/kernel/math/PocketServoPrecisionTest.java`
- Modify `kernel/src/main/java/me/vexmc/mental/kernel/math/PredictorInputs.java`

### Step 2.1 — write the failing pin

Append to `PocketServoPrecisionTest.java`:

```java
    @Test
    void chaseAlignmentDefaultsNaNAndSurvivesTheRebuildingWithers() {
        // Every pre-round arity defaults the alignment to NaN — the aligned 1.0
        // model, byte-identical (the era-exact no-op default of the round).
        PredictorInputs legacy = new PredictorInputs(
                true, 0.6, 0.6, 0.0, 0.0, Double.NaN, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0);
        assertTrue(Double.isNaN(legacy.chaseAlignment()), "pre-round arity defaults NaN");
        assertTrue(Double.isNaN(PredictorInputs.degraded(true, 0.6, 0.10).chaseAlignment()),
                "the degraded factory defaults NaN");
        PredictorInputs strafing = legacy.withChaseAlignment(0.71);
        assertEquals(0.71, strafing.chaseAlignment(), 0.0);
        // The two REBUILDING withers must CARRY the alignment: dropping it would
        // silently re-price a strafing touchdown-repriced or dynamic-chase hit as
        // fully aligned (the exact bug class this round fixes).
        assertEquals(0.71, strafing.withDynamicChase(0.28, 0, 0.5).chaseAlignment(), 0.0);
        assertEquals(0.71, strafing.asGroundedLaunch().chaseAlignment(), 0.0);
    }
```

### Step 2.2 — run, expect compile failure (RED)

```bash
./gradlew :kernel:test --tests "me.vexmc.mental.kernel.math.PocketServoPrecisionTest"
```

Expected: compilation error — `chaseAlignment()` / `withChaseAlignment` do not
exist.

### Step 2.3 — implement

In `PredictorInputs.java`:

**(1)** Grow the canonical record by one trailing component. Replace the
record header's closing components

```java
        double chaseSteadySpeed,
        int resetPhaseTicks,
        double chaseRampFactor) {
```

with

```java
        double chaseSteadySpeed,
        int resetPhaseTicks,
        double chaseRampFactor,
        // The measured attacker-heading alignment (2.4.7 strafe fix): the RAW
        // normalized dot of the attacker's recent movement heading with the knock
        // axis (PocketServo.headingAlignment) — +1 straight chase, ≈ +0.7071 full
        // forward-strafe, ≤ 0 orbit/backpedal. The solve clamps it into
        // [cos 45°, 1] (PocketServo.chaseAlignmentFactor) and scales every MODEL
        // chase channel (dynamic, attribute, floor) by it; the measured window
        // channel is already axis-projected and is never scaled. NaN ⇒ the aligned
        // 1.0 — byte-identical to every pre-round solve.
        double chaseAlignment) {
```

and add the matching `@param` line to the record javadoc, after the
`chaseRampFactor` block's comment:

```java
 * @param chaseAlignment          the measured attacker-heading alignment with the
 *                                knock axis (raw normalized dot; see
 *                                {@link PocketServo#headingAlignment}) — the solve
 *                                clamps it into [cos 45°, 1] and scales the MODEL
 *                                chase channels by it. {@link Double#NaN} ⇒ 1.0,
 *                                byte-identical (2.4.7 strafe fix).
```

**(2)** Preserve the old canonical signature as a compat constructor (the
record's established growth pattern — fourth application). Insert before the
existing 18-arg compat constructor:

```java
    /**
     * The pre-strafe-alignment arity (2.4.7 strafe fix): every input through the
     * input-driven dynamic chase, with NO measured attacker-heading alignment —
     * {@code chaseAlignment} defaults to {@link Double#NaN}, so every model chase
     * channel prices the aligned 1.0. Keeps every caller and pin that predates the
     * strafe round compiling — and solving byte-identically.
     */
    public PredictorInputs(
            boolean launchGrounded, double launchSlip, double landingSlip, double launchHeight,
            double driftAlongAxis, double chaseAlongAxis, double victimNormalizedSpeed,
            int attackerRttMillis, int victimRttMillis, double attackerHeadY, double victimEyeHeight,
            double victimYawVsAxisDeg, double victimYawRateDegPerTick, int groundedTicks,
            double priorChaseEma, double priorDynamicTarget, double launchVerticalVelocity,
            double cadenceEmaTicks, double chaseSteadySpeed, int resetPhaseTicks, double chaseRampFactor) {
        this(launchGrounded, launchSlip, landingSlip, launchHeight, driftAlongAxis, chaseAlongAxis,
                victimNormalizedSpeed, attackerRttMillis, victimRttMillis, attackerHeadY, victimEyeHeight,
                victimYawVsAxisDeg, victimYawRateDegPerTick, groundedTicks, priorChaseEma, priorDynamicTarget,
                launchVerticalVelocity, cadenceEmaTicks, chaseSteadySpeed, resetPhaseTicks, chaseRampFactor,
                Double.NaN);
    }
```

(The existing 18-arg compat constructor keeps delegating to the 21-arg
signature, which now lands here — the NaN chains through. No other compat
constructor changes.)

**(3)** Carry the alignment through BOTH rebuilding withers. In
`asGroundedLaunch()`, replace

```java
                priorChaseEma, priorDynamicTarget, Double.NaN, cadenceEmaTicks,
                chaseSteadySpeed, resetPhaseTicks, chaseRampFactor);
```

with

```java
                priorChaseEma, priorDynamicTarget, Double.NaN, cadenceEmaTicks,
                chaseSteadySpeed, resetPhaseTicks, chaseRampFactor, chaseAlignment);
```

In `withDynamicChase(...)`, replace

```java
                launchVerticalVelocity, cadenceEmaTicks, steadySpeed, phaseTicks, rampFactor);
```

with

```java
                launchVerticalVelocity, cadenceEmaTicks, steadySpeed, phaseTicks, rampFactor,
                chaseAlignment);
```

**(4)** Add the new wither after `withDynamicChase`:

```java
    /**
     * This input set with the measured attacker-heading alignment attached (2.4.7
     * strafe fix): the raw normalized dot of the attacker's recent ring heading
     * with the knock axis ({@link PocketServo#headingAlignment}). The solve clamps
     * it into {@code [cos 45°, 1]} and scales the MODEL chase channels by it; NaN
     * leaves every channel at the aligned 1.0 — byte-identical.
     */
    public PredictorInputs withChaseAlignment(double alignment) {
        return new PredictorInputs(
                launchGrounded, launchSlip, landingSlip, launchHeight, driftAlongAxis, chaseAlongAxis,
                victimNormalizedSpeed, attackerRttMillis, victimRttMillis, attackerHeadY, victimEyeHeight,
                victimYawVsAxisDeg, victimYawRateDegPerTick, groundedTicks, priorChaseEma, priorDynamicTarget,
                launchVerticalVelocity, cadenceEmaTicks, chaseSteadySpeed, resetPhaseTicks, chaseRampFactor,
                alignment);
    }
```

### Step 2.4 — run, expect green

```bash
./gradlew :kernel:test
```

Expected: `BUILD SUCCESSFUL` — the new pin passes, every existing kernel test
compiles and passes unchanged (the compat constructor absorbs all pre-round
arities). D-8 note: the new component and every new method use only `double`
primitives — no post-Java-8 JDK type enters any descriptor.

### Step 2.5 — commit

```bash
git add kernel/src/main/java/me/vexmc/mental/kernel/math/PredictorInputs.java \
        kernel/src/test/java/me/vexmc/mental/kernel/math/PocketServoPrecisionTest.java
git commit -m "$(cat <<'EOF'
feat(kernel): carry the attacker chase alignment through PredictorInputs

One additive record component -- chaseAlignment, the raw normalized dot
of the attacker's measured movement heading with the knock axis -- grown
by the record's established compat-constructor pattern (fourth
application): the old 21-component canonical signature survives verbatim
and defaults the alignment to NaN, so every pre-round caller and pin
compiles and solves byte-identically. Both rebuilding withers
(asGroundedLaunch, withDynamicChase) carry the new field explicitly --
dropping it there would silently re-price a strafing touchdown-repriced
or dynamic-chase hit as fully aligned, which is the exact bug class this
round exists to fix, so the carry is pinned.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01174ZCdfCNQhprYW7YHavYp
EOF
)"
```

---

## Task 3 — kernel: the solve prices the alignment (the fix proper)

**Files:**
- Modify `kernel/src/test/java/me/vexmc/mental/kernel/math/PocketServoPrecisionTest.java`
- Modify `kernel/src/main/java/me/vexmc/mental/kernel/math/PocketServo.java`

### Step 3.1 — write the failing pins

**Pin derivations (all hand-computed; `cos45 = 0.7071067811865476`,
`D_g(10) = 1 + 0.546·geo(9) = 4.470559212545951`,
`D_a(10) = geo(10) = 6.784265354243252`):**

*Strafe floor pin* — the 2.4.6 touchdown shape (d0 2.55, R 0.02, F 0.325,
measured chase 0.116 b/t, grounded reprice) with alignment cos 45°:

```
floor  = 0.7071067811865476 · 0.70 · 0.2806 · 1.0 · 10 = 1.388899140
         (measured 0.116·10 = 1.16 < 1.388899140 → still floored)
σ*     = (2.85 − (2.55 − 1.388899140) − 4.470559213·0.02) / (0.325·4.470559213)
       = 1.599487955 / 1.452931744
       = 1.100869302          (exact double: 1.100869302274982)
```

vs the aligned 1.496827931 — the strafe hit leaves the max clamp and solves
interior.

*Strafe airborne pin* — the 2.4.6 airborne-false-low shape with alignment
cos 45°:

```
σ*     = (2.85 − (2.55 − 1.388899140) − 6.784265354·0.02) / (0.325·6.784265354)
       = 1.553213833 / 2.204886240
       = 0.704441710          (exact double: 0.7044417096234582)
→ blended 0.704441710 < min 0.8 → σ = 0.8 (the SHIPPED_ANCHOR test clamp)
```

The lower σ* is CORRECT for a strafing attacker — they close ~29% slower, so
the victim needs less push to sit at the same pocket; the min clamp bounds it.

*Strafe dynamic pin* — the dynamic-priority shape with alignment cos 45°
(`projectTravel` is linear in steadySpeed, so the scaled travel is
`cos45 · 2.5202734375`):

```
travel   = 0.7071067811865476 · 2.5202734375 = 1.782102438
constant = 2.6 − 1.782102438 = 0.817897562   (exact double: 0.8178975618994193)
floor    = cos45 · 1.9642 = 1.388899140 < 1.782102438 → floor stays off
```

Append to `PocketServoPrecisionTest.java`:

```java
    @Test
    void alignedApproachIsBitIdenticalWithAndWithoutTheAlignmentInput() {
        // THE BINDING CONSTRAINT of the strafe round: a straight-line approach —
        // alignment measured at 1.0, or no signal at all (NaN) — must solve
        // BIT-identically to the pre-round arity on every channel the alignment
        // scales. IEEE gives this exactly (1.0 × x == x for every finite x), so the
        // deltas here are 0.0, not epsilons. This pin FAILS if the alignment fold
        // perturbs an aligned solve in any way.
        PredictorInputs floored = touchdown(0.30, -0.35, 0.116);
        PocketServo.Solution unaligned = PocketServo.solve(
                SHIPPED_ANCHOR, floored, 2.55, 0.02, 0.325, 0.35716, 0.10);
        PocketServo.Solution aligned = PocketServo.solve(
                SHIPPED_ANCHOR, floored.withChaseAlignment(1.0), 2.55, 0.02, 0.325, 0.35716, 0.10);
        assertEquals(unaligned.sigmaStar(), aligned.sigmaStar(), 0.0,
                "floor channel: alignment 1.0 is bit-exact");
        assertEquals(unaligned.sigma(), aligned.sigma(), 0.0);
        assertEquals(unaligned.chaseTravel(), aligned.chaseTravel(), 0.0);
        assertEquals(1.496827931, aligned.sigmaStar(), 1.0e-9,
                "the 2.4.6 hand pin stands untouched under alignment 1.0");
        // The dynamic channel: alignment 1.0 == the pre-round constant, bit-exact.
        PredictorInputs dynamic = new PredictorInputs(
                false, 0.6, 0.6, 0.2, 0.0, 0.28, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN).withDynamicChase(0.28, 0, 0.5);
        FlightPrediction dynNaN = PocketServo.predict(SERVO, dynamic, 2.6, 0.0, 0.4, 0.4, 0.10);
        FlightPrediction dynOne = PocketServo.predict(
                SERVO, dynamic.withChaseAlignment(1.0), 2.6, 0.0, 0.4, 0.4, 0.10);
        assertEquals(dynNaN.constant(), dynOne.constant(), 0.0,
                "dynamic channel: alignment 1.0 is bit-exact");
    }

    @Test
    void strafeAlignmentScalesTheChaseFloor() {
        // The 2.4.7 strafe fix, floor channel: the SAME touchdown hit as the 2.4.6
        // pin (d0 2.55, R 0.02, F 0.325, grounded reprice, D_g(10) = 4.470559213,
        // measured chase 0.116 b/t) thrown by a measured forward-strafing attacker
        // (alignment cos 45°). The floor scales by the stance geometry:
        //   floor = 0.7071067811865476 · 0.70 · 0.2806 · 1.0 · 10 = 1.388899140
        //   (measured 1.16 still below → floored)
        //   σ* = (2.85 − (2.55 − 1.388899140) − 4.470559213·0.02) / (0.325·4.470559213)
        //      = 1.599487955 / 1.452931744 = 1.100869302
        // vs the aligned 1.496827931 — the strafe hit no longer rides the max clamp.
        PocketServo.Solution s = PocketServo.solve(
                SHIPPED_ANCHOR,
                touchdown(0.30, -0.35, 0.116).withChaseAlignment(PocketServo.MIN_CHASE_ALIGNMENT),
                2.55, 0.02, 0.325, 0.35716, 0.10);
        assertTrue(s.launchRepriced(),
                "the alignment survives asGroundedLaunch through the touchdown reprice");
        assertEquals(1.100869302, s.sigmaStar(), 1.0e-9);
        assertEquals(1.100869302, s.sigma(), 1.0e-9, "interior — off the 1.2 ceiling");
        // The strafe σ* still lands exactly on target through the independent fold.
        double landed = independentLanding(
                touchdown(0.30, -0.35, 0.116)
                        .withChaseAlignment(PocketServo.MIN_CHASE_ALIGNMENT).asGroundedLaunch(),
                2.55, 0.02, 0.325, s.sigmaStar(), 0.35716, 0.10, 10);
        assertEquals(s.target(), landed, EPSILON);
    }

    @Test
    void strafeAlignmentHoldsTheAirborneChannelHonest() {
        // The airborne shape of theChaseFloorPreventsTheAirborneFalseLowMode,
        // strafing: the scaled floor prices the cos45 stance —
        //   σ* = (2.85 − (2.55 − 1.388899140) − 6.784265354·0.02) / (0.325·6.784265354)
        //      = 1.553213833 / 2.204886240 = 0.704441710
        // The LOWER σ* is correct for a strafing attacker (they close ~29% slower;
        // the victim needs less push to sit at the same pocket); the min clamp
        // bounds the reduction — this is honest strafe pricing, not the 2.4.6
        // false-low mispricing (which was a wrong drag schedule + a diluted
        // measurement, both unchanged here).
        PredictorInputs blind = new PredictorInputs(
                false, 0.6, 0.6, 0.30, 0.0, 0.116, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0)
                .withChaseAlignment(PocketServo.MIN_CHASE_ALIGNMENT);
        PocketServo.Solution s = PocketServo.solve(SHIPPED_ANCHOR, blind, 2.55, 0.02, 0.325, 0.35716, 0.10);
        assertEquals(0.704441710, s.sigmaStar(), 1.0e-9);
        assertEquals(0.8, s.sigma(), EPSILON, "the min clamp bounds the honest strafe reduction");
    }

    @Test
    void strafeAlignmentScalesTheDynamicChase() {
        // The dynamic-priority shape with a measured strafe heading: projectTravel is
        // linear in steadySpeed, so the priced travel scales by cos 45° —
        //   travel   = 0.7071067811865476 · 2.5202734375 = 1.782102438
        //   constant = 2.6 − 1.782102438 = 0.817897562
        // The scaled floor (cos45 · 1.9642 = 1.388899140) stays below the scaled
        // dynamic travel, so the dynamic channel still owns the price.
        PredictorInputs dynamic = new PredictorInputs(
                false, 0.6, 0.6, 0.2, 0.0, 0.28, 0.10,
                -1, -1, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN)
                .withDynamicChase(0.28, 0, 0.5)
                .withChaseAlignment(PocketServo.MIN_CHASE_ALIGNMENT);
        FlightPrediction dyn = PocketServo.predict(SERVO, dynamic, 2.6, 0.0, 0.4, 0.4, 0.10);
        assertEquals(0.8178975619, dyn.constant(), 1.0e-9);
    }

    @Test
    void strafeGridLandsExactlyOnTarget() {
        // The exact-inverse property survives the alignment scaling: across launch
        // branches × stamps × separations × alignments, σ* fed through the
        // INDEPENDENT fold (which mirrors the alignment-scaled attribute/floor
        // channels with its own arithmetic) lands on target within 1e-9. Measured
        // chase NaN → the attribute channel × alignment, which always exceeds the
        // floor × the SAME alignment (0.2806 > 0.7·0.2806), so the floor stays
        // inert exactly as in the aligned grid — the ordering is alignment-invariant.
        double[] alignments = {PocketServo.MIN_CHASE_ALIGNMENT, 0.85, 1.0};
        double[] verticalStamps = {0.30, 0.35716, 0.40};
        double[] separations = {2.00, 2.60};
        int checked = 0;
        for (boolean grounded : new boolean[] {true, false}) {
            double launchHeight = grounded ? 0.0 : 0.2;
            for (double stamp : verticalStamps) {
                for (double d0 : separations) {
                    for (double alignment : alignments) {
                        PredictorInputs in = new PredictorInputs(
                                grounded, 0.6, 0.6, launchHeight,
                                0.0, Double.NaN, PocketServo.WALK_BASELINE,
                                -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0)
                                .withChaseAlignment(alignment);
                        PocketServo.Solution s = PocketServo.solve(
                                SERVO, in, d0, 0.02, 0.4, stamp, 0.10);
                        assertFalse(s.declined());
                        double landed = independentLanding(
                                in, d0, 0.02, 0.4, s.sigmaStar(), stamp, 0.10, 10);
                        assertEquals(s.target(), landed, EPSILON,
                                "σ* lands on target: grounded=" + grounded + " stamp=" + stamp
                                        + " d0=" + d0 + " align=" + alignment);
                        checked++;
                    }
                }
            }
        }
        assertEquals(36, checked, "the 2×3×2×3 strafe cross exercised");
    }
```

**And mirror the alignment in the independent fold** (this is mandatory — the
fold models the floor verbatim; an unmirrored fold would make every strafe
landing pin lie). In `independentLanding`, replace

```java
        // Chase — mirror the solve's measured/attribute rate AND the 2.4.6
        // chase-alignment floor (the independent fold verifies the affine inversion,
        // so it must model the same floored chase the solve prices).
        double chaseRate = !Double.isNaN(in.chaseAlongAxis())
                ? in.chaseAlongAxis()
                : 0.2806 * ((attrA > 0 ? attrA : 0.10) / 0.10);
        double chaseTravel = Math.max(chaseRate * wPrime,
                PocketServo.CHASE_ALIGNMENT * PocketServo.SPRINT_GROUND_SPEED
                        * ((attrA > 0 ? attrA : 0.10) / 0.10) * wPrime);
```

with

```java
        // Chase — mirror the solve's measured/attribute rate, the 2.4.6
        // chase-alignment floor, AND the 2.4.7 strafe-alignment scaling of the
        // MODEL channels (re-stated locally, deliberately NOT calling the
        // production chaseAlignmentFactor — the fold stays independent). The
        // measured channel is never alignment-scaled, matching the solve.
        double align = Double.isNaN(in.chaseAlignment())
                ? 1.0
                : Math.max(Math.sqrt(0.5), Math.min(1.0, in.chaseAlignment()));
        double chaseRate = !Double.isNaN(in.chaseAlongAxis())
                ? in.chaseAlongAxis()
                : align * 0.2806 * ((attrA > 0 ? attrA : 0.10) / 0.10);
        double chaseTravel = Math.max(chaseRate * wPrime,
                align * PocketServo.CHASE_ALIGNMENT * PocketServo.SPRINT_GROUND_SPEED
                        * ((attrA > 0 ? attrA : 0.10) / 0.10) * wPrime);
```

### Step 3.2 — run, expect RED

```bash
./gradlew :kernel:test --tests "me.vexmc.mental.kernel.math.PocketServoPrecisionTest"
```

Expected failures: `strafeAlignmentScalesTheChaseFloor` (gets 1.496827931, the
alignment is not yet priced), `strafeAlignmentHoldsTheAirborneChannelHonest`
(gets 0.965362591), `strafeAlignmentScalesTheDynamicChase` (gets
2.6 − 2.5202734375 = 0.0797265625), `strafeGridLandsExactlyOnTarget` (the fold
now mirrors alignment but the solve does not — landing misses target on the
sub-1.0 cells). `alignedApproachIsBitIdentical…` PASSES already (nothing reads
the field yet) — it exists to fail on any FUTURE aligned-path perturbation.

### Step 3.3 — implement in `simulate()`

In `PocketServo.java`, replace the chase ladder block

```java
        // (4) Chase — the input-driven dynamic chase (ramp-aware, over the ACTUAL
        // window) when the reset model is present, else the measured attacker-velocity
        // trend, else the 0.2806×attr model (spec 2026-07-07; the fallback ladder).
        double chaseRate;
        double chaseTravel;
        boolean chaseMeasured;
        if (!Double.isNaN(inputs.chaseRampFactor()) && !Double.isNaN(inputs.chaseSteadySpeed())) {
            // The attacker's ramped re-accel out of their sprint reset, projected over
            // the flight window from the observed reset phase.
            chaseTravel = DynamicChase.projectTravel(
                    inputs.chaseSteadySpeed(), inputs.resetPhaseTicks(), inputs.chaseRampFactor(), wPrime);
            chaseRate = wPrime > 0 ? chaseTravel / wPrime : 0.0;
            chaseMeasured = true;
        } else if (!Double.isNaN(inputs.chaseAlongAxis())) {
            chaseRate = inputs.chaseAlongAxis();
            chaseTravel = chaseRate * wPrime;
            chaseMeasured = true;
        } else {
            chaseRate = SPRINT_GROUND_SPEED * chaseFactor(attackerNormalizedSpeed);
            chaseTravel = chaseRate * wPrime;
            chaseMeasured = false;
        }
```

with

```java
        // (4a) The measured attacker-heading alignment (2.4.7 strafe fix). Every
        // MODEL channel below prices the chase as if the attacker's velocity lay
        // ON the knock axis — true for a straight-line (W) pursuit, but a strafing
        // (W+A / W+D) attacker's velocity sits 45° off their crosshair (moveFlying
        // normalizes the diagonal input to the pure-W magnitude, rotated 45° off
        // facing), so their true axis-closing rate is cos 45° ≈ 0.7071 of their
        // speed and the aligned models over-price the close by ~41% — σ*
        // over-solves and the strafe combo overshoots the pocket by up to ~0.8
        // blocks (the 2.4.7 strafe dossier). The MEASURED window channel is never
        // scaled: axis projection is its definition, so it already embodies the
        // strafe geometry. NaN (no heading signal) resolves to 1.0 —
        // byte-identical for every straight-line and pre-round solve.
        double alignment = chaseAlignmentFactor(inputs.chaseAlignment());

        // (4) Chase — the input-driven dynamic chase (ramp-aware, over the ACTUAL
        // window) when the reset model is present, else the measured attacker-velocity
        // trend, else the 0.2806×attr model (spec 2026-07-07; the fallback ladder).
        double chaseRate;
        double chaseTravel;
        boolean chaseMeasured;
        if (!Double.isNaN(inputs.chaseRampFactor()) && !Double.isNaN(inputs.chaseSteadySpeed())) {
            // The attacker's ramped re-accel out of their sprint reset, projected over
            // the flight window from the observed reset phase. The alignment resolves
            // HERE: DynamicChase's contract wants an already-alignment-resolved
            // steadySpeed, and this solve is that caller.
            chaseTravel = DynamicChase.projectTravel(
                    alignment * inputs.chaseSteadySpeed(), inputs.resetPhaseTicks(),
                    inputs.chaseRampFactor(), wPrime);
            chaseRate = wPrime > 0 ? chaseTravel / wPrime : 0.0;
            chaseMeasured = true;
        } else if (!Double.isNaN(inputs.chaseAlongAxis())) {
            chaseRate = inputs.chaseAlongAxis();
            chaseTravel = chaseRate * wPrime;
            chaseMeasured = true;
        } else {
            chaseRate = alignment * SPRINT_GROUND_SPEED * chaseFactor(attackerNormalizedSpeed);
            chaseTravel = chaseRate * wPrime;
            chaseMeasured = false;
        }
```

Then scale the floor. Replace

```java
        double alignedFloorTravel =
                CHASE_ALIGNMENT * SPRINT_GROUND_SPEED * chaseFactor(attackerNormalizedSpeed) * wPrime;
```

with

```java
        // Since 2.4.7 the floor itself scales by the measured heading alignment:
        // CHASE_ALIGNMENT (0.70) is the STRAIGHT-line effectiveness fraction (w-tap
        // dip + imperfect tracking, the 2.4.6 calibration), the alignment factor is
        // the stance GEOMETRY — a forward-strafing attacker's floor is cos 45°
        // lower, which is exactly the over-price that pushed strafe combos out of
        // reach. At alignment 1.0 the product is bit-identical to the 2.4.6 floor.
        double alignedFloorTravel = alignment
                * CHASE_ALIGNMENT * SPRINT_GROUND_SPEED * chaseFactor(attackerNormalizedSpeed) * wPrime;
```

### Step 3.4 — run, expect green

```bash
./gradlew :kernel:test
```

Expected: `BUILD SUCCESSFUL`. Verify explicitly that ALL of the following
pre-existing pins still pass unchanged (they are the straight-line
byte-identity evidence): `touchdownBoundaryHitRepricesAsAGroundedLaunch`
(σ* 1.496827931), `theChaseFloorPreventsTheAirborneFalseLowMode`
(σ* 0.965362591), `gapWindowPricesTheGroundReturnTail` (σ* 4.033269182),
`dynamicChaseIsConsumedAndTakesPriorityOverTheMeasuredRate`,
`gridLandsExactlyOnTarget` (600 cells).

### Step 3.5 — commit

```bash
git add kernel/src/main/java/me/vexmc/mental/kernel/math/PocketServo.java \
        kernel/src/test/java/me/vexmc/mental/kernel/math/PocketServoPrecisionTest.java
git commit -m "$(cat <<'EOF'
fix(combo): price the model chase channels by the measured strafe alignment

A strafing (W+A/W+D) attacker overshot the pocket by up to ~0.8 blocks:
every MODEL chase channel -- the dynamic ramp (the priority channel for
any w-tapping attacker), the attribute fallback, and the 2.4.6
chase-alignment floor -- priced the attacker's close as if their velocity
lay ON the knock axis, while a crosshair-on-victim strafer's true
axis-closing rate is cos45 of their speed (moveFlying normalizes the
diagonal input to pure-W magnitude rotated 45 degrees). Sigma*
over-solved by 0.4-0.7 and the answer-denial target shipped +0.3..+0.8
blocks past the attacker's reach -- "just out of combo range".

The solve now resolves one clamped alignment factor
(chaseAlignmentFactor: NaN -> 1.0, else [cos45, 1.0]) and scales the
three model channels by it. The dynamic scaling finally honors
DynamicChase's own caller contract (steadySpeed "already alignment- and
technique-resolved"); the MEASURED window channel is never scaled --
axis projection is its definition. Straight-line solves are
bit-identical (1.0 x is exact in IEEE-754), pinned with 0.0-delta
assertions; hand pins: floored strafe touchdown sigma* 1.100869302 (vs
aligned 1.496827931), airborne strafe 0.704441710 (min clamp bounds the
honest reduction), dynamic strafe constant 0.817897562; the independent
landing fold mirrors the scaling and a 36-cell strafe grid holds the
exact-inverse property.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01174ZCdfCNQhprYW7YHavYp
EOF
)"
```

---

## Task 4 — core: read the attacker's ring heading into the solve

**Files:**
- Modify `core/src/main/java/me/vexmc/mental/v5/feature/combo/ComboPredictor.java`

(No new Bukkit reads: the attacker's `PositionRing` history is already passed
into `build` at both delivery seams — `KnockbackUnit.java:290` and
`HitRegistrationUnit.java:359` — and ring reads are netty-thread-safe by the
class's own contract. The ring is fed for every player, packetless fakes
included, by the session's per-tick `positions.record(...)`
(`SessionService.java:353`), the same PositionRing-delta doctrine as the 2.4.6
fast-pot fix.)

### Step 4.1 — implement

**(1)** Add the sampling constant after `DRIFT_SAMPLES`:

```java
    /**
     * How many ring samples the attacker-heading alignment reads (2.4.7 strafe
     * fix): 5 samples = up to 4 tick deltas — longer than the 3–4-tick w-tap dip
     * recovery, so one wrong-phase instant cannot flip the DIRECTION read. Only
     * the direction is consumed (the magnitude dips in a w-tap; the heading
     * survives it), which is why this sidesteps the 2.4.5 pre-hit-trend lesson.
     */
    private static final int ALIGNMENT_SAMPLES = 5;
```

**(2)** In `build(...)`, after the axis derivation (the `ux`/`uz` block),
insert:

```java
        // The measured attacker-heading alignment (2.4.7 strafe fix): the
        // attacker's net ring displacement over the last few ticks, normalized and
        // dotted with the knock axis. A strafing (W+A / W+D) attacker reads
        // ≈ cos 45°; the solve clamps the factor into [cos 45°, 1] and scales its
        // MODEL chase channels (dynamic, attribute, floor) by it, while the
        // measured window channel keeps its own axis projection. Too little
        // movement — or a short ring — reads NaN ⇒ the aligned 1.0 models,
        // byte-identical for a straight-line chase and for a stationary attacker.
        double alignmentRaw = attackerHeadingAlignment(positions, attackerId, ux, uz);
```

**(3)** Attach it to the one shared build product so BOTH return paths carry
it. Replace the final lines of `build`:

```java
        if (attackerReset != null && attackerReset.known()
                && attackerReset.sprinting() && !attackerReset.blocking()) {
            double attackerAttr = attackerView != null
                    ? attackerView.moveSpeedAttr() : PocketServo.WALK_BASELINE;
            double steadySpeed = PocketServo.SPRINT_GROUND_SPEED * PocketServo.chaseFactor(attackerAttr);
            return base.withDynamicChase(steadySpeed, attackerReset.phaseTicks(), CHASE_RAMP_FACTOR);
        }
        return base;
```

with

```java
        // The heading alignment rides EVERY return path (withDynamicChase carries
        // it): the dynamic steadySpeed stays technique-resolved only — the kernel
        // solve resolves the alignment itself from this input, honoring the
        // DynamicChase caller contract in one place.
        base = base.withChaseAlignment(alignmentRaw);
        if (attackerReset != null && attackerReset.known()
                && attackerReset.sprinting() && !attackerReset.blocking()) {
            double attackerAttr = attackerView != null
                    ? attackerView.moveSpeedAttr() : PocketServo.WALK_BASELINE;
            double steadySpeed = PocketServo.SPRINT_GROUND_SPEED * PocketServo.chaseFactor(attackerAttr);
            return base.withDynamicChase(steadySpeed, attackerReset.phaseTicks(), CHASE_RAMP_FACTOR);
        }
        return base;
```

(Requires changing `PredictorInputs base = …` to a non-final local — it
already is a plain local.)

**(4)** Add the private helper next to `estimateDrift`:

```java
    /**
     * The attacker's ring-heading alignment with the knock axis (2.4.7 strafe
     * fix): the net displacement over the last {@link #ALIGNMENT_SAMPLES} ring
     * samples (oldest→newest, the ring's own order), normalized and dotted with
     * the axis by the kernel {@link PocketServo#headingAlignment}. NaN when the
     * ring is short or the attacker has not measurably moved — the solve then
     * prices the aligned 1.0 models, byte-identical.
     */
    private static double attackerHeadingAlignment(
            PositionRing positions, UUID attackerId, double ux, double uz) {
        List<PositionRing.Sample> ring = positions.recent(attackerId, ALIGNMENT_SAMPLES);
        if (ring.size() < 2) {
            return Double.NaN;
        }
        PositionRing.Sample first = ring.get(0);
        PositionRing.Sample last = ring.get(ring.size() - 1);
        return PocketServo.headingAlignment(
                last.x() - first.x(), last.z() - first.z(), ux, uz);
    }
```

**(5)** Surface it in the debug line (the lab's field-verification seam) —
in `debugLine`, after the chase field, insert:

```java
                + " align=" + fmt(inputs.chaseAlignment())
```

so the line reads `… chase=…(measured|model) align=0.7071 yawVsAxis=…`.
(Additive key=value pair; the lab parser is keyed, not positional.)

### Step 4.2 — build

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL` — unit tests, japicmp (the kernel change is
additive: the old 21-arg constructor survives verbatim, one component accessor
and two methods added), and the kernel-Bukkit-free gate all pass.

### Step 4.3 — commit

```bash
git add core/src/main/java/me/vexmc/mental/v5/feature/combo/ComboPredictor.java
git commit -m "$(cat <<'EOF'
fix(combo): read the attacker's ring heading into the servo alignment

The attacker's measured movement vector was plumbed to the solve site
and dropped on the floor: both delivery seams already hand
ComboPredictor.build the attacker's PositionRing history, but only the
victim consumed it (drift). The build now derives the attacker's
heading -- net ring displacement over 5 samples (4 tick deltas, longer
than the w-tap dip recovery; direction only, the 2.4.5 wrong-phase trap
does not apply) -- and attaches its raw axis dot as the new
chaseAlignment input on every return path. Player.getVelocity() remains
unusable here (send-then-restore reads ~0 for a grounded player); the
ring delta is the same doctrine as the 2.4.6 fast-pot fix. A stationary
or packetless attacker reads NaN and solves byte-identically; the debug
line grows an align= field so the lab can verify the stance per hit.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01174ZCdfCNQhprYW7YHavYp
EOF
)"
```

---

## Task 5 — tester: the strafing-attacker scenario

**Files:**
- Modify `tester/src/main/java/me/vexmc/mental/tester/suite/ComboSuite.java`

`SERVO_MIN`/`SERVO_MAX` stay `0.93`/`1.35` — no constant changes. The existing
chain scenario (stationary attacker → NaN alignment → factor 1.0) is the
byte-identity integration check and needs no edits; its `expected == journaled`
assertion re-derives through the same `ComboPredictor.build`, so it stays
self-consistent by construction.

### Step 5.1 — register the new case

In the `cases()` list, after the chain scenario entry, add:

```java
                new TestCase("combo: a strafing (45°) attacker's servo hit prices the measured alignment",
                        context -> runStrafeAlignmentScenario(mental, tester, context)),
```

### Step 5.2 — implement the scenario

Insert after `runChainScenario` (imports: `Location` is already imported;
no new imports needed):

```java
    /* -------------------- scenario 1b: the strafing attacker -------------------- */

    /**
     * The 2.4.7 strafe fix, end to end: with the combo active, the attacker
     * "strafes" — stepped diagonally (45° off the live attacker→victim axis,
     * closing component positive) one small teleport per tick, so the position
     * ring carries a genuine strafe heading — and the next servo hit must
     * (a) build inputs whose measured alignment reads cos45-class, (b) journal a σ
     * equal to the re-derived solve over those same inputs, and (c) stay inside
     * the honesty clamps. The stationary chain scenario covers the NaN-alignment
     * (byte-identical) side of the same seam.
     */
    private static void runStrafeAlignmentScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            setUp(mental, context, attacker, victim, true);
            buildActiveCombo(mental, context, attacker, victim);

            // Stage the strafe: five 0.20-block steps, one per tick, each at 45°
            // off the CURRENT attacker→victim axis with a positive closing
            // component — the ring's recent(5) then reads a heading whose axis dot
            // is ≈ cos 45°. Steps ride the owning thread (Folia-safe teleports);
            // total gap stays far under the combo's maxGapTicks, and the ~0.7
            // blocks of diagonal close keeps the pair inside attack reach.
            for (int step = 0; step < 5; step++) {
                context.syncRun(() -> {
                    Location a = attacker.player().getLocation();
                    Location v = victim.player().getLocation();
                    double dx = v.getX() - a.getX();
                    double dz = v.getZ() - a.getZ();
                    double len = Math.sqrt(dx * dx + dz * dz);
                    double ux = dx / len;
                    double uz = dz / len;
                    // Rotate the closing axis +45°: (ux,uz) → ((ux−uz)/√2, (ux+uz)/√2).
                    double hx = (ux - uz) * Math.sqrt(0.5);
                    double hz = (ux + uz) * Math.sqrt(0.5);
                    teleportOnOwningThread(mental, attacker,
                            a.clone().add(hx * 0.20, 0.0, hz * 0.20));
                });
                context.awaitTicks(1);
            }

            // Re-derive the expected σ from the SAME build + solve in the SAME sync
            // tick as the attack (the chain scenario's pattern) and capture the
            // measured alignment the inputs carried.
            double[] expected = new double[1];
            double[] alignment = new double[1];
            int shipsBefore = context.sync(() -> {
                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                PlayerView view = session.view();
                context.expect(view != null && attacker.uuid().equals(view.comboAttackerId()),
                        "the combo must still be active after the strafe staging");
                KnockbackProfile profile = KnockbackSuite.profileFor(mental, victim);
                EntityState attackerState = EntityStates.capture(attacker.player());
                EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                PocketServoConfig servo = comboSettings(mental).servo();
                PredictorInputs inputs = ComboPredictor.build(
                        attacker.uuid(), victim.uuid(),
                        attackerState.x(), attackerState.z(), victimState.x(), victimState.z(),
                        view, mental.sessions().viewOf(attacker.uuid()),
                        mental.sessions().positions(), new LatencyModel(), mental.clock().current(),
                        ResetModel.UNKNOWN);
                alignment[0] = inputs.chaseAlignment();
                expected[0] = KnockbackEngine.computePaced(
                        attackerState, victimState, profile, null, new Random(0L), false, servo, inputs)
                        .comboFactor();
                victim.player().setNoDamageTicks(0);
                int before = countShips(mental, victim);
                attacker.attack(victim.player());
                return before;
            });
            JournalEntry ship = awaitNewShip(context, mental, victim, shipsBefore);
            context.expect(ship != null && ship.shipped() != null,
                    "the strafing-combo hit journaled no SHIP");
            context.expect(alignment[0] > 0.55 && alignment[0] < 0.85,
                    "a 45° strafe heading must read a cos45-class alignment (got " + alignment[0] + ")");
            double sigma = ship.comboFactor();
            context.expect(sigma >= SERVO_MIN - 1e-9 && sigma <= SERVO_MAX + 1e-9,
                    "the strafing servo factor must sit inside the [0.93, 1.35] clamps (got " + sigma + ")");
            context.expectNear(expected[0], sigma, SIGMA_EPSILON,
                    "the journaled σ must equal the production solve on the same strafe inputs");
        } finally {
            teardown(mental, context, attacker, victim);
        }
    }
```

(The alignment band is [0.55, 0.85] rather than a point pin because the axis
rotates as the attacker closes across the 4 sampled deltas — the heading's dot
with the FINAL axis wanders a few degrees around cos 45°; a NaN alignment
fails the band check honestly, catching any ring-feeding regression.)

### Step 5.3 — build + targeted matrix smoke on a modern entry

```bash
./gradlew build
scripts/integration-matrix.sh          # or a single-entry run if the script supports it
```

Expected: `BUILD SUCCESSFUL`; the ComboSuite section of `test-results.txt`
shows the new case PASS on every entry (per `matrix-gate`: trust only the
nonce-stamped PASS, never the Gradle banner).

### Step 5.4 — commit

```bash
git add tester/src/main/java/me/vexmc/mental/tester/suite/ComboSuite.java
git commit -m "$(cat <<'EOF'
test(combo): stage a strafing attacker through the servo suite

Steps the attacker diagonally (45 degrees off the live knock axis, one
owning-thread teleport per tick) so the position ring carries a genuine
strafe heading, then asserts the built inputs read a cos45-class
alignment, the journaled sigma equals the re-derived production solve
on the same inputs, and the factor stays inside the [0.93, 1.35]
honesty clamps. The stationary chain scenario remains the NaN-alignment
byte-identity check of the same seam. The alignment assertion is a band
(0.55..0.85), not a point pin: the axis rotates as the attacker closes
across the sampled deltas; a NaN read fails the band honestly, so a
ring-feeding regression cannot pass silently.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01174ZCdfCNQhprYW7YHavYp
EOF
)"
```

---

## Task 6 — the full gate

```bash
./gradlew build                  # unit tests + japicmp + kernel-Bukkit-free + jvmdg gates
scripts/integration-matrix.sh    # every Paper entry, concurrent local variant
./gradlew integrationTestFolia   # the Folia entry
```

Expected: every suite PASS with THIS run's nonce echoed in
`test-results.txt` (the honesty rule — a leftover result fails the check
structurally). Version bump / release notes are the release step's job, not
this plan's.

If the matrix is green, this branch is ready for the owner's strafe playtest.
The playtest question to answer (see risk 7): does the residual ~+0.2-block
dynamic-channel strafe overshoot still read as "out of range" in hand? If yes,
the follow-up is a calibration round on the dynamic channel's dip modeling —
not more geometry.

---

## Risk register

| # | Risk | The pin/gate that catches it |
|---|---|---|
| 1 | The alignment fold perturbs a straight-line solve (any channel) | `alignedApproachIsBitIdenticalWithAndWithoutTheAlignmentInput` (0.0-delta, both channels) + the untouched 2.4.6 hand pins (1.496827931 / 0.965362591 / 4.033269182) + the 600-cell aligned grid + the ComboSuite chain scenario's journal-equality |
| 2 | `asGroundedLaunch`/`withDynamicChase` drop the new field (a strafing touchdown/dynamic hit silently re-prices aligned) | `chaseAlignmentDefaultsNaNAndSurvivesTheRebuildingWithers` + `strafeAlignmentScalesTheChaseFloor` (drives the reprice path: 1.100869302 ≠ the aligned 1.496827931) |
| 3 | The independent fold desyncs from the solve (every landing pin lies) | `strafeGridLandsExactlyOnTarget` (36 cells, 1e-9) — the fold re-states the clamp/scaling with its own arithmetic |
| 4 | A noisy heading (w-tap instant, attacker's own received knock, third-party knock) prices a sub-strafe or negative chase → strafe-flavoured undershoot | the `[cos 45°, 1]` clamp (`chaseAlignmentFactorClampsTheStrafeBand`: −0.5 → cos 45°) + the 4-delta net-displacement heading + retaliation ends the combo before the common polluted case can price |
| 5 | Division by zero / NaN propagation on a stationary attacker | `headingAlignmentIsTheNormalizedAxisDot` (the magnitude guard fires before normalization; 0-heading → NaN → factor 1.0) |
| 6 | Suite teleports in OTHER scenarios pollute the heading and shift their σ | toward-victim staging teleports read dot ≈ +1 → clamp 1.0 → byte-identical; every σ assertion in the suite is either a 1.0-expectation (servo inactive) or re-derived through the shared `build` (self-consistent); the matrix run is the gate |
| 7 | Residual strafe overshoot (~+0.21) on the dynamic channel — the model's ~10% over-price of dipped sprint that the 1.35 ceiling absorbs on straight but ships interior on strafe | accepted and documented (74% of the strafe error removed); owner playtest decides whether a dip-calibration follow-up round is needed — a calibration question, not geometry |
| 8 | Deep-strafe σ* under the 0.93 min clamp (~−0.02 blocks held-close error) | the min clamp bounds it by design (the honesty boundary); the dossier's watch-item — revisit only with playtest evidence |
| 9 | Kernel additive-only / japicmp / D-8 violations | old 21-arg constructor kept verbatim; new API is `double`-only (no post-Java-8 types in descriptors); `./gradlew build` runs japicmp + the jvmdg descriptor gates |
| 10 | A stale-ring attacker (just spawned/teleported far) reads a garbage heading | the ring holds ≤ 40 recent samples and `recent(5)` reads only the last 5 owning-thread ticks; a single large teleport delta dominates the heading but clamps into [cos 45°, 1] — bounded to the strafe price for ≤ 4 ticks |

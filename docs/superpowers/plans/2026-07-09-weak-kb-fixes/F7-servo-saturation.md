# F7 — PocketServo saturation deadband for sprint-fresh min-clamp pinning

## 1. Problem

For sprint-fresh hits (freshEra ≈ 0.9 = LEGACY_17 base 0.4 + aligned sprint extra 0.5) the pocket servo's inverse solve (`kernel/src/main/java/me/vexmc/mental/kernel/math/PocketServo.java:339-342`: `sigmaStar = (target − constant − slope·residual)/(freshEra·slope)`, then clamp to `[config.min(), config.max()]`) lands at σ* ≈ 0.10–0.51 across the entire 2.5–3.5-block chase band under EVERY chase channel — always below the 0.93 min clamp (`ComboSettings.DEFAULTS`, `core/src/main/java/me/vexmc/mental/v5/config/settings/ComboSettings.java:61`). Every hit-3+ chase sprint hit therefore ships a deterministic ×0.93 fresh shave (≈0.28 blocks of settle lost: 4.4706·0.9·0.07 = 0.2816) while the victim STILL lands ~1–3 blocks past the 2.85 pocket the shave was supposed to hold — the shave buys nothing but era deviation. Root cause: the 2.4.6/2.4.7 clamp-band calibration (`docs/superpowers/research/2026-07-07-servo-undershoot-chase-floor.md:11-13`, `PocketServo.CHASE_ALIGNMENT` javadoc :86-93) was measured at plain-hit freshEra 0.40 only; sprint-fresh was never covered (finding `chase-pocket-sprint-hits-pin-min-clamp-everywhere`, CONFIRMED).

## 2. Design decision

**A saturation deadband, derived — not a knob.** When the min clamp binds (`blended < min`) AND the unclamped solve sits further below the min clamp than the clamp's own whole shave budget — i.e. **σ* < min − (1 − min) = 2·min − 1** — the solve DECLINES the shave and ships the era σ = 1.0.

Derivation of the threshold (this is the "pocket-vs-landing gap" margin, in the solve's own units): the predicted landing is affine in σ, `dNext(σ) = constant + slope·(residual + σ·freshEra)`, and `dNext(σ*) = target` exactly, so the pocket miss at any σ is `slope·freshEra·(σ − σ*)`.
- Miss remaining at the clamp: `M = slope·freshEra·(min − σ*)`.
- Total benefit the shave can ever buy (σ=min vs era σ=1): `B = slope·freshEra·(1 − min)`.
Both scale by the same `slope·freshEra` (the settle sensitivity — ≈ 4.47·0.9 ≈ 4.0 blocks/unit-σ for a grounded 10-tick sprint window; the doc's "~4.6 blocks per unit σ" figure), so `M > B ⇔ σ* < min − (1 − min)`, geometry-free. Past that boundary the clamped shave recovers less than half of the residual pocket miss — for the actual sprint-fresh band (σ* 0.10–0.51 vs floor 0.86) it recovers 8–15%, i.e. it costs 0.28 blocks of era knock for a pocket it cannot approach. With min 0.93 the floor is 0.86; sprint-fresh hits sit far below it (never straddle), plain interior solves (σ* ≈ 0.95–1.1 post-2.4.6-floor) are untouched, and shallow min-pins (σ* ∈ [0.86, 0.93)) keep the calibrated 0.93 shave.

Why this shape over alternatives (document these for the owner in the commit body; do NOT implement):
- **Widen/lower the min clamp for sprint-fresh** (freshEra-aware min): would hold the pocket but ships σ ≈ 0.5 — a 50% fresh shave, a huge era deviation on the strongest era hit. Rejected.
- **Keep as-is**: deterministic 7% era shave that also misses the pocket — worst of both.
- **Continuous taper toward 1.0 below the floor**: smoother than the hard deadband but more math, still era-deviating mid-zone. The hard deadband is deterministic and era-favoring (determinism is the product — era-accuracy anti-features).
- Known bounded discontinuity: sigma jumps 0.93→1.0 as σ* crosses 0.86. Sprint hits never straddle it (σ* ≤ 0.51); plain hits near the boundary jump ≈ 0.07·0.4·slope ≈ 0.13 blocks — modest, and strictly toward era.

No new config knob, no `PocketServoConfig` change, no schema/parser/preset change. The kernel v1 four-input `sigma()` (:206-228) stays **verbatim** (its javadoc promises byte-identity; production melee uses only the precision `solve()` via `KnockbackEngine.servoFactor`).

## 3. Exact changes

### 3a. `kernel/src/main/java/me/vexmc/mental/kernel/math/PocketServo.java`

**(i) New public helper** (additive API — place after `chaseAlignmentFactor`, before the "shared flight helpers" section):

```java
/**
 * The min-clamp saturation floor (the sprint-fresh deadband; the 2026-07-09
 * weak-KB round): below {@code σ* < minFactor − (1 − minFactor)} even the
 * fully-clamped shave (σ = min) leaves the victim farther past the pocket than
 * the whole budget the shave spent — the pocket miss at the clamp,
 * {@code slope·F·(min − σ*)}, exceeds the shave's entire benefit,
 * {@code slope·F·(1 − min)} — so the solve declines the shave and ships the
 * era stamp (σ = 1). Both sides scale by the same {@code slope·freshEra}, so
 * the boundary is exactly {@code 2·min − 1} for every geometry: derived, not a
 * knob. Sprint-fresh hits (F ≈ 0.9) solve σ* 0.10–0.51 across the whole chase
 * pocket — permanently below this floor at the shipped 0.93 min — while the
 * 2.4.6 [0.93, 1.35] calibration was measured at plain-hit F 0.40 only.
 */
public static double saturationFloor(double minFactor) {
    return minFactor - (1.0 - minFactor);
}
```

**(ii) `solve()` — the deadband** (the :339-343 region). Before:

```java
double slope = fold.dragSum;
double sigmaStar = (fold.target - fold.constant - slope * residualCarry) / (freshEra * slope);
double blended = 1.0 + config.gain() * (sigmaStar - 1.0);
double sigma = Math.max(config.min(), Math.min(config.max(), blended));
double predictedDNext = fold.constant + slope * (residualCarry + sigma * freshEra);
```

After:

```java
double slope = fold.dragSum;
double sigmaStar = (fold.target - fold.constant - slope * residualCarry) / (freshEra * slope);
double blended = 1.0 + config.gain() * (sigmaStar - 1.0);
double sigma = Math.max(config.min(), Math.min(config.max(), blended));
// The saturation deadband (the 2026-07-09 sprint-fresh finding): when the min
// clamp binds AND σ* sits below the saturation floor, the clamped shave leaves
// the victim farther past the pocket than everything the shave bought — a
// pointless 7% era deviation on exactly the hit class (sprint-fresh, F ≈ 0.9)
// the [0.93, 1.35] band was never calibrated for. Ship the era stamp instead.
// The Solution stays fully priced (declined = false, σ* preserved) so the
// debug sink reads the saturation as σ = 1 with σ* far below min.
if (blended < config.min() && sigmaStar < saturationFloor(config.min())) {
    sigma = 1.0;
}
double predictedDNext = fold.constant + slope * (residualCarry + sigma * freshEra);
```

The `blended < config.min()` guard keeps the deadband inert wherever the min clamp does not bind (gain-damped configs, min ≥ 1 configs, interior/max-side solves) — those paths stay bit-identical. The returned `Solution` keeps `declined = false` and the full fold intermediates: `Solution.declined`'s documented meaning ("geometry reported as 0, not evaluated") is preserved, and `ComboPredictor.debugLine` (already prints `sigmaStar` and `sigma`) shows a saturated hit unambiguously as `sigma=1.0000 sigmaStar=0.6601`. `predictedDNext` automatically becomes the era landing (`constant + slope·(residual + freshEra)`).

**(iii) `CHASE_ALIGNMENT` javadoc (:86-93)** — qualify the calibration claim. Change "Calibrated so σ* centres near 1.0–1.1 at a typical mid-combo residual, landing inside the {@code [0.93, 1.35]} clamp band (owner playtest, 2026-07-07)." to add one sentence after it: "That calibration was measured at plain-hit freshEra 0.40; sprint-fresh hits (F ≈ 0.9) saturate below the band on every chase channel and take the {@link #saturationFloor} deadband instead."

Do NOT touch: the v1 `sigma()`/`predict()`, `simulate()`, `predict(precision)` (no clamping there), `PocketServoConfig.java` (listed in the brief for context only — no knob is added).

### 3b. `core/src/main/java/me/vexmc/mental/v5/config/settings/ComboSettings.java`

Javadoc only (no code): in the class javadoc sentence "the clamps {@code [0.8, 1.2]} are the honesty boundary — past them the pocket is honestly lost and era physics wins.", append: "On the min side the loss can be TOTAL: when the solve lands below {@code PocketServo.saturationFloor(minFactor)} (= 2·min − 1) even the clamped shave cannot approach the pocket, and the servo declines to the full era knock instead of shipping a pointless min-factor shave (sprint-fresh hits always saturate there)."

### 3c. `core/src/main/resources/config.yml` (the YAML comments are the docs)

Extend the `min-factor`/`max-factor` comment block (currently at :352-356, "…past them the pocket is honestly lost and era physics wins over a non-era knock."). Append two comment lines:

```
#                  When the solve lands so far below min-factor that even the clamped
#                  shave cannot approach the pocket (sigma* < 2*min-factor - 1 — every
#                  sprint-fresh chase hit does), the servo declines the shave entirely
#                  and ships the FULL era knock instead of a pointless min-factor shave.
```

No key changes; the commented-out defaults block (:393-405) is untouched.

### 3d. `docs/superpowers/research/2026-07-07-servo-undershoot-chase-floor.md`

Append an addendum section at the end:

```
## Addendum (2026-07-09): the calibration covered plain hits only — sprint-fresh saturates

The instrument's freshEra 0.40 is a PLAIN hit. A sprint-fresh hit (freshEra ≈ 0.9 =
base 0.4 + aligned sprint extra 0.5) more than doubles the σ-sensitivity, and the
inverse solve lands σ* ≈ 0.10–0.51 across the whole 2.5–3.5-block chase band under
EVERY chase channel (un-saturating needs chaseTravel ≈ 4.1 blocks, above the max
attribute channel's ~3.37) — permanently below the 0.93 min clamp. Result: a
deterministic ×0.93 fresh shave (≈ 0.28 blocks of settle: 4.4706·0.9·0.07) on every
hit-3+ chase sprint hit that ALSO missed the 2.85 pocket by ~1–3 blocks. Fixed by the
saturation deadband: σ* < PocketServo.saturationFloor(min) = 2·min − 1 declines the
shave to the era σ = 1 (the clamped shave would recover less than half the residual
pocket miss there). Finding: chase-pocket-sprint-hits-pin-min-clamp-everywhere in
docs/superpowers/research/2026-07-09-weak-kb-close-range-pathway-findings.md.
```

### 3e. `tester/src/main/java/me/vexmc/mental/tester/suite/ComboSuite.java` (chain scenario hardening)

`runChainScenario`'s measured hit is a plain stationary-fake hit whose staged solve can be deeply min-pinned (large drifted d0, near-zero measured chase); with the deadband its journaled σ may legitimately become 1.0, which the current assertion at :188-189 (`Math.abs(sigma - 1.0) > 1e-6`, "must be servo-shaped, not 1.0") would fail. Harden it to accept a priced saturation decline while still proving the servo engaged:

1. Add `PocketServo.Solution[] solution = new PocketServo.Solution[1];` beside `double[] expected` (:148). Inside the same `context.sync` block, immediately before the `computePaced` call (:175), add:
```java
solution[0] = KnockbackEngine.explainServo(
        attackerState, victimState, profile, null, false, servo, inputs);
```
2. Replace :188-189 with:
```java
context.expect(Math.abs(sigma - 1.0) > 1e-6
                || solution[0].sigmaStar() < PocketServo.saturationFloor(SERVO_MIN),
        "an active-combo hit must be servo-shaped or honestly saturation-declined (σ=" + sigma
                + " σ*=" + solution[0].sigmaStar() + ")");
```
3. Add the import `me.vexmc.mental.kernel.math.PocketServo` (imports always, never inline-qualified — note the existing `fmt`-style code elsewhere in the file; keep this new code import-based).

D-8 check: `explainServo`'s descriptor carries only kernel types + `Double`/`boolean` (no `RandomGenerator`), and `saturationFloor(double)` is pure `double` — no post-Java-8 JDK type crosses the tester→Mental boundary. The strafe scenario (:276-278), retaliation/grounded (assert 1.0), and zero-touch scenarios need no change: none assert σ ≠ 1.0, and their `expectNear(expected, sigma)` re-derives from the same solve so both sides carry the deadband.

## 4. Threading analysis

`PocketServo.solve`/`saturationFloor` are pure static kernel math — no fields, no new state, no allocation shared across calls. Callers are unchanged: `KnockbackUnit` (region thread, authoritative pass) and `HitRegistrationUnit` (netty pre-send) both compute through `KnockbackEngine.servoFactor` → `PocketServo.solve` on immutable inputs (`PredictorInputs`, `PocketServoConfig`, `EntityState`); a deterministic pure function keeps pre-send and authoritative bit-agreed on identical inputs exactly as today. The tester's `explainServo` re-derivation runs inside the same sync block as the attack, consuming the same published state as the production solve (the suite's existing pattern). No single-writer domain gains a writer; DeliveryDesk remains the sole velocity/journal writer.

## 5. Era / zero-touch analysis

- `modules.combo-hold` defaults **false** (config.yml:273, no preset enables it): a disabled module passes `PocketServoConfig.INACTIVE`, and `solve()` short-circuits at :331 BEFORE the deadband — byte-identical, zero-touch holds trivially.
- No profile-schema knob is added: `parse(empty) == LEGACY_17` is untouched (no `KnockbackProfile`/`ProfileParser`/preset-yml/`KnockbackDocsTest` changes).
- The v1 `sigma()` stays verbatim (its byte-identity promise and pins stand).
- With combo-hold ON, the deadband only ever moves σ **toward 1.0** (toward era), never away.
- **Expected feel change on the owner's server (he runs the Combo Solver):** every hit-3+ chase sprint-fresh hit stops shipping ×0.93 fresh and ships the full era stamp — ≈ **+0.28–0.29 blocks of settle per such hit** (4.4706·0.9·0.07 = 0.2816). Additionally, deeply saturated PLAIN hits (heavy residual or strafe-stance solves with σ* < 0.86 under the shipped band) also revert from 0.93 to era 1.0 (≈ +0.09–0.13 blocks each) — state this in the commit body so the owner can field-judge both.

## 6. Tests (hand-computed)

Shared constants: `D_g(10) = 1 + 0.546·geo(9) = 4.470559213` (already pinned); attribute chase at attr 0.10 over w′=10 = 0.2806·10 = 2.806 (alignment NaN→1.0; floor 0.7·0.2806·10 = 1.9642 does not bind); `0.9·4.470559213 = 4.023503291`.

### New tests in `kernel/src/test/java/me/vexmc/mental/kernel/math/PocketServoPrecisionTest.java` (new section "the sprint-fresh saturation deadband"; add `private static final PocketServoConfig SHIPPED_BAND = PocketServoConfig.of(2.85, 1.0, 0.93, 1.35, 10);`)

1. **`saturationFloorIsTwiceMinMinusOne`**: `assertEquals(0.86, PocketServo.saturationFloor(0.93), EPSILON)` (0.93 − (1 − 0.93) = 0.86); `assertEquals(0.6, PocketServo.saturationFloor(0.8), EPSILON)`.
2. **`sprintFreshSaturatedSolveDeclinesTheShaveToTheEraStamp`**: inputs `degradedGround(true, 0.0)` (grounded stone, chase NaN, drift 0, no ping, NaN yaw → static target), `solve(SHIPPED_BAND, in, 3.0, 0.0, 0.9, 0.35716, 0.10)`. Arithmetic: airTime(0.35716) = 10 → w′ = 10, tLand = 10, dragSum = D_g(10) = 4.470559213; chase = 2.806 (attribute; floor 1.9642 below); constant = 3.0 − 2.806 = 0.194; σ* = (2.85 − 0.194 − 0)/(0.9·4.470559213) = 2.656/4.023503291 = **0.660121245**. blended = σ* (gain 1) < 0.93 and σ* < 0.86 → deadband. Block-space check: miss at min = 4.023503291·(0.93 − 0.660121245) = 1.085858061 blocks > benefit 0.281645230 blocks. Assert: `assertEquals(0.660121245, s.sigmaStar(), 1.0e-9)`; `assertEquals(1.0, s.sigma(), 0.0)`; `assertFalse(s.declined())` (fully priced, not a decline); `assertEquals(4.217503291, s.predictedDNext(), 1.0e-9)` (era landing 0.194 + 4.023503291).
3. **`justAboveTheSaturationFloorStillShavesToMin`**: inputs `new PredictorInputs(true, 0.6, 0.6, 0.0, 0.0, 0.38, 0.10, -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0)` (measured chase 0.38 b/t), `solve(SHIPPED_BAND, in, 3.0, 0.0, 0.9, 0.35716, 0.10)`. chaseTravel = 0.38·10 = 3.8 (> floor); constant = 3.0 − 3.8 = −0.8; σ* = (2.85 + 0.8)/4.023503291 = 3.65/4.023503291 = **0.907169632**; 0.86 ≤ σ* < 0.93 → the calibrated shave stands. Assert `assertEquals(0.907169632, s.sigmaStar(), 1.0e-9)`; `assertEquals(0.93, s.sigma(), EPSILON)`.
4. **`interiorPlainSolveIsUntouchedUnderTheShippedBand`**: the existing 2.4.6 airborne "blind" geometry under SHIPPED_BAND — inputs `new PredictorInputs(false, 0.6, 0.6, 0.30, 0.0, 0.116, 0.10, -1, -1, Double.NaN, Double.NaN, Double.NaN, 0.0, 0)`, `solve(SHIPPED_BAND, in, 2.55, 0.02, 0.325, 0.35716, 0.10)`: σ* = (2.85 − (2.55 − 1.9642) − 6.784265354·0.02)/(0.325·6.784265354) = **0.965362591** (the pinned 2.4.6 value; floor chase 1.9642 binds over measured 1.16), interior in [0.93, 1.35] → `assertEquals(0.965362591, s.sigmaStar(), 1.0e-9)`; `assertEquals(s.sigmaStar(), s.sigma(), EPSILON)`.

### New test in `kernel/src/test/java/me/vexmc/mental/kernel/math/KnockbackEngineServoTest.java`

5. **`saturatedSprintChaseHitShipsTheEraVector`**: `PocketServoConfig shipped = PocketServoConfig.of(2.85, 1.0, 0.93, 1.35, 10);` attacker `attacker(0, -3.0, true)` (3 blocks along −z, yaw 0 faces +z → axis-aligned sprint), victim `victim(0, 0)`. Engine arithmetic: freshEra = (0.4 base + 1.0·0.5 extra)·1.0 = 0.9; verticalStamp = 0·0.1 + 0.4 + 0.1 = 0.5, airTime(0.5) = 14 ≥ 10 → same D_g(10) fold; σ* = 0.660121245 < 0.86 → σ = 1.0. Assert: `active = computePaced(attacker, victim, DEFAULTS, null, rng(), false, shipped)` vs `inactive = computePaced(attacker, victim, DEFAULTS, null, rng(), false, PocketServoConfig.INACTIVE)`; `assertEquals(1.0, active.comboFactor(), 0.0)`; vectors bit-equal with delta 0.0 on x, y, z (expected (0, 0.5, 0.9)).

### Existing pins that must NOT change (verified against a fold mirror)

- `PocketServoTest` — all (v1 solve untouched, including `tooFarClampsToTheLowerBound` 0.8).
- `PocketServoPrecisionTest.gridLandsExactlyOnTarget` (worst grid σ* = 0.0098 but the grid asserts only σ*-landing/target/!declined — `declined` stays false under the deadband) and `strafeGridLandsExactlyOnTarget` (worst σ* 0.7364, asserts σ* only).
- `theChaseFloorPreventsTheAirborneFalseLowMode` (σ* 0.965362591 interior), `touchdownBoundaryHitRepricesAsAGroundedLaunch` (max side 1.2), `gapWindowPricesTheGroundReturnTail` (max side), `strafeAlignmentScalesTheChaseFloor` (interior 1.100869302), `strafeAlignmentHoldsTheAirborneChannelHonest` (σ* 0.704441710 → 0.8: floor for min 0.8 is 0.6, 0.7044 > 0.6, blended < min but above floor → still shaves — unchanged), `alignedApproachIsBitIdenticalWithAndWithoutTheAlignmentInput` (max/interior).
- `KnockbackEngineServoTest` — all existing (the σ-derived assertions share the solve; `servoScalesTheFreshHorizontal…` solves σ* 1.4294 → 1.2 max side; `aFasterAttackerClosesMoreSoTheServoPushesHarder` solves 1.0379/1.9793 → both above min).

## 7. Verification

- `./gradlew build` — all kernel/core unit tests green including the 5 new pins; japicmp green (only an ADDED public static method on a kernel class); kernel-Bukkit-free gate untouched (pure JDK change); the four artifact gates unaffected (no descriptor with post-Java-8 JDK types: `saturationFloor(double)` and `explainServo`'s kernel-typed signature satisfy D-8).
- `./gradlew integrationTestMatrix` (sequential; or `scripts/integration-matrix.sh` locally — remember it does not build, run the build first). The ComboSuite tier must pass with the hardened chain assertion; honor the nonce rule — accept only this run's UUID + PASS in test-results.txt, never the BUILD SUCCESSFUL banner. FakePlayer note (live-server-testing): the chain scenario's fakes are clientless/stationary — the hardened assertion (3e) is exactly the accommodation for their drifting-d0 staging; no new suite staging is required.
- Commit: `fix(combo): decline the servo shave when the min clamp is saturation-pinned` with a prose body covering the freshEra-0.40-only calibration gap, the 2·min−1 derivation, the owner-visible feel change (+0.28–0.29 blocks on hit-3+ chase sprint hits; deep-saturated plain hits also revert to era), and the documented alternatives (freshEra-aware min clamp, taper, keep-as-is).

## 8. Risks + rollback

- **Feel risk (owner-facing, by design)**: sprint follow-ups in held combos get ~0.29 blocks more settle; if the owner judges combos now escape too easily, the counter-lever is his — lower `min-factor` (which also lowers the deadband floor 2·min−1) or revisit with a freshEra-aware band. Say so in the commit body.
- **Discontinuity at σ* = 0.86**: only plain hits can straddle it (jump ≈ 0.13 blocks); sprint hits sit ≤ 0.51. Accepted, era-favoring, deterministic.
- **ComboSuite flake risk**: if the staged chain hit's σ lands exactly interior on some entries and saturated on others, the hardened OR-assertion covers both outcomes; the `expectNear(expected, sigma)` self-consistency check remains the load-bearing assertion.
- **Rollback**: single self-contained commit (one kernel branch + helper, test/doc/comment edits); `git revert` restores the 2.4.7 behavior exactly — no config migration, no schema, no state.

## Files touched

- `kernel/src/main/java/me/vexmc/mental/kernel/math/PocketServo.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/math/PocketServoPrecisionTest.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/math/KnockbackEngineServoTest.java`
- `tester/src/main/java/me/vexmc/mental/tester/suite/ComboSuite.java`
- `core/src/main/java/me/vexmc/mental/v5/config/settings/ComboSettings.java`
- `core/src/main/resources/config.yml`
- `docs/superpowers/research/2026-07-07-servo-undershoot-chase-floor.md`

## Cross-lane conflicts (integrator notes)

No direct region overlap with the batch. Watch three soft spots: (1) core/src/main/resources/config.yml — F8's config-minor batch may edit the same file (my change is comment-only lines appended to the combo-hold min-factor/max-factor block at ~:352-356; trivially mergeable); (2) tester ComboSuite.java — F1 (sprint retro-clear) or F5/F6 (registration-yaw/enchant capture) could add suite staging in the same file if their designers chose ComboSuite for scenarios (my edit is confined to runChainScenario :148-191 plus one import); (3) F5/F6 touch HitRegistrationUnit/EntityStates attacker capture, which feeds KnockbackEngine.servoFactor's inputs — if F6's registration-yaw stamp changes the attacker yaw the servo's freshEra projection sees, the servo pins here are unaffected (kernel tests stage EntityState directly) but the live ComboSuite expected-σ re-derivation stays self-consistent by construction. PocketServo.java/PocketServoConfig.java are exclusive to F7 in this batch.

## Open questions

None blocking implementation. One owner FEEL decision to surface at review (not needed to code): the deadband also reverts deeply-saturated PLAIN hits (σ* < 0.86 under the shipped band — e.g. strafe-stance or high-residual chase hits) from the 0.93 shave to full era; if the owner wants the deadband restricted to sprint-fresh only, that requires threading freshEra-class into the condition (a one-line change: apply the deadband only when freshEra > 0.6), but the recommended design keeps the geometry-derived, hit-class-free rule.
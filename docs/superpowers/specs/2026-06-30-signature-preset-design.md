# `signature` — Mental's signature knockback (a velt derivative)

**Date:** 2026-06-30
**Status:** approved, implementing
**Target release:** 2.2.0

## Problem

Comboing under the `velt` preset, the **second (and later) hit lands the
victim a hair too far** to hold the perfect reach pocket — the attacker has to
creep forward to re-reach, costing the "sweet spot" combo cadence. The opener
(hit 1) feels right; the airborne follow-ups drift.

Root cause is a physics asymmetry, not a velt mis-value: the opener lands on a
**grounded** victim, whose launch tick decays at ground drag (stone ≈ 0.546);
every follow-up lands on an **airborne** victim, whose launch tick decays at
air drag (0.91). For the same launch velocity the airborne hit keeps
`0.91 / 0.546 ≈ 1.67×` as much horizontal on that first tick and carries the
victim slightly past the pocket. velt does not correct for this
(`air.horizontal = 1.0`), so the drift is visible at velt's residual-wipe
shape where nothing else damps it.

## Goal

Ship a velt derivative — `signature`, "Mental's signature KB" — that keeps
velt's feel exactly (residual wipe, pinned 0.36 vertical, full 0.5 sprint
horizontal) but **trims the airborne combo hits just enough** to hold the
reach pocket. New selectable preset; the default stays `legacy-1.7`
(era-exact no-op default invariant preserved).

## The one tweak

`air.horizontal: 0.92` (velt: `1.0`). Everything else is velt verbatim.

`KnockbackEngine.finish` applies the `air` multipliers **only to airborne
victims** (`if (!victim.grounded())`). So `air.horizontal` is the single knob
that distinguishes the airborne follow-ups (hits 2+) from the grounded opener
(hit 1): lowering it trims exactly the over-travelling hits and **nothing
else** — hit 1, the sprint bonus, the 0.36 vertical, and the 0.1 residual wipe
are all untouched. The multiplier lands after the sprint extra is added, so it
trims the whole airborne horizontal push (base + sprint), which is what we
want.

### Why 0.92 (the magnitude)

- **No correction** is `1.0`; **full grounded-parity over the 2-tick combo
  window** is ≈ `0.81` (grounded 2-tick displacement `1 + 0.546 = 1.546` vs
  airborne `1 + 0.91 = 1.91`; `1.546 / 1.91 = 0.809`). Full parity (a 19% cut)
  over-damps — the victim feels glued — and isn't what was asked.
- `0.92` sits ~40% of the way from `1.0` toward `0.81`: a deliberate **partial**
  correction. On velt's ≈ `0.78` sprint-hit launch it removes ≈ `0.063`
  (~8%) of horizontal launch velocity per airborne hit, ~a tenth of a block of
  pocket creep per inter-hit interval at combo cadence — enough to hold reach,
  small enough to keep velt's punch.
- Vertical is left at `1.0` (0.36 pinned). The complaint is horizontal
  distance, not height, and the pinned vertical is velt's signature.

The trim is a uniform airborne-horizontal multiplier, so an airborne
rod/projectile hit is also trimmed 8% — negligible at velt's residual wipe
(rod boosts already die on arrival) and consistent with the melee intent.

## Provenance

Not a ported fork config — an **original Mental tuning** derived from the
archived `velt` values. The file header says so; no archive citation (there is
no archived "signature" server).

## Components touched

- **NEW** `core/src/main/resources/profiles/signature.yml` — velt body, header
  rewritten, `air.horizontal: 0.92`.
- `ConfigStore.BUNDLED_PROFILES` — add `signature`.
- `KnockbackMenu.PROFILE_ICONS` — `signature → NETHER_STAR` (slots/menu list are
  data-driven and already have room).
- `knockback.yml`, `docs/knockback-profiles.md`, `README.md` — list it.
- `build.gradle.kts` → 2.2.0.

## Testing

- `MentalConfigTest.bundledPresetsCarryTheirCanonicalValues` — pin signature =
  velt values with `air().horizontal() == 0.92` and `air().vertical() == 1.0`
  (a regenerated preset can never drift). `bundledFilesMatchRecordDefaults`
  stays consistent automatically (`Set.copyOf(BUNDLED_PROFILES)`).
- `KnockbackEngineTest` — pin the behavioral guarantee: under the signature
  profile a sprinting combo hit on an **airborne** victim is `0.92×` the same
  hit on a **grounded** victim (the opener), and the opener equals velt's.
- Gate: `./gradlew build` then `scripts/integration-matrix.sh` (verify fresh
  PASS, never the banner). No new runtime code path, so no Folia-specific risk.

## Invariants

- **Zero-touch / era-exact default:** signature is opt-in; `legacy-1.7` stays
  the default and `parse(empty) == LEGACY_17` is unchanged.
- **velt preserved:** velt's own file and pins are untouched.

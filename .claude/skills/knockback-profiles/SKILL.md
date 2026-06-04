---
name: knockback-profiles
description: Use when changing the knockback engine, profile schema, presets, or config files — the profile system's resolution model, engine order of operations, preset provenance, and the checklist for adding a knob without breaking era exactness.
---

# The knockback profile system

## Model

- A profile = one complete feel (`KnockbackProfile` record), one file under
  `profiles/<name>.yml`. Six shipped presets: `legacy-1.7` (default),
  `legacy-1.8`, `kohi`, `mmc`, `lunar`, `custom`. Presets are extracted only
  when missing — owner edits are sacred, deleting regenerates pristine.
- Resolution is by the **VICTIM**: player override → per-world map
  (knockback.yml) → default. Overrides survive world changes, clear on quit,
  validate against the live set; `PlayerKnockbackProfileChangeEvent` fires on
  change (owning thread). The netty pre-send reads the profile FROZEN into
  the victim's per-tick snapshot, so prediction and truth share one profile.
- Config is split by concern: config.yml (module switches + policy),
  knockback.yml (selection + rod/fishing/projectile), hit-registration.yml,
  latency-compensation.yml, profiles/. v1 single-file configs migrate
  automatically (backup `config-v1-backup.yml`; tuned knockback becomes
  profiles/custom.yml, selected).

## Engine order of operations (KnockbackEngine)

1. LEGACY resistance roll (all-or-nothing, may cancel)
2. Direction from source POSITION (attacker/angler/shooter — never the
   projectile); range-reduction taper shaves the horizontal push
   (melee-only, 3D distance)
3. Base: `motion×friction − dir×push`; vertical ADD (`vy×f + base`) or SET
   (assigned); horizontal cap rescales; vertical cap clamps BASE only
4. Extras along attacker yaw (sprint levels × extra-or-wtap + enchant levels
   × extra; the vertical bonus is ONE flat term — vanilla never scales it
   per level; wtap freshness comes from SprintTracker: toggle arms,
   authoritative hit consumes, pre-send peeks)
5. Air multipliers (airborne victims) → add offsets (sign-matched,
   axis-ratio) → vertical-min floor → SCALING resistance (horizontal) →
   ±3.9 packet clamp

## Adding a knob — the checklist

1. Default must be an era-exact no-op; `parse(empty) == LEGACY_17` stays true.
2. Knob goes in `KnockbackProfile.parse` + ALL preset yml files + custom.yml
   with full comments + docs/knockback-profiles.md.
3. Unit-pin the math in KnockbackEngineTest (hand-computed expectations) AND
   the no-op-at-default property.
4. Preset era pins live in MentalConfigTest (`bundledPresetsCarryTheir
   CanonicalValues`) — a regenerated preset can never drift.
5. Provenance: kohi values are confirmed ×3; mmc is the community REMAKE
   (label-swap resolved; not the private server's values); lunar is [likely].
   Cite in the preset header. Research base:
   docs/research/2026-06-04-improved-knockback.md.

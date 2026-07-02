---
name: ocm-coexistence
description: Use when touching anything that overlaps OldCombatMechanics — damage shaping, knockback ownership, fishing/projectile mechanics, hit-delay/cooldown questions, or the OcmBinding/Arbiter. Encodes the ownership split and the rules that keep the two plugins from fighting.
---

# OldCombatMechanics coexistence

## The split (owner directive, structural)

Mental owns **knockback + hit delivery ONLY**. Combat *rules* — attack
cooldown, sword blocking, regen, armour, golden apples, and crucially
**hit delay / noDamageTicks** — belong to OCM. Never absorb a rules knob into
Mental; pair with OCM instead (`feedback-min-interval-ms: auto` follows the
victim's live hurt window for exactly this reason).

## Per-mechanic deciders (whose modeset wins)

| Mechanic | Decider |
| --- | --- |
| Melee knockback, tool damage, crits | the ATTACKER's modeset |
| Fishing (knock + reel-in) | the rodder |
| Thrown-projectile knockback | the victim |
| Arrows | always Mental (OCM has no arrow module) |

`OcmBinding.mentalOwns(token, decider)` answers it (over the kernel
`ArbiterCore`, keyed by `MechanicToken`): BOUND mode uses OCM's service API per
player (`isModuleEnabledForPlayer`, reflective, no compile dep, owning-thread
only — the verdicts are frozen into the per-tick views / `HitContext` for netty
use); CONFIG mode falls back to global conservative verdicts from OCM's config
(doubled knockback is worse than deferred). Profiles only shape knocks Mental
owns.

## Damage handoff

When OCM shapes damage for an attacker, the fast path must hand the hurt
pipeline **vanilla-shaped** damage (attribute base, 1.9 crit rules, 1.9
sharpness), NOT legacy-composed: OCM decomposes the event into vanilla
components at the attacker's live cooldown charge and re-composes. The fast
path never resets the charge, so the round-trip is lossless (the era pin:
sharpness-5 diamond sword ⇒ 14.25, not 17.5).

## The two OCM defaults that silently bury Mental's feel (addendum 4)

Both ship ENABLED in OCM's default config and both read as "the selected
Mental profile is wrong" — check them FIRST on any live feel report:

- **`old-player-knockback` is in the default `old` modeset** (and
  `worlds.__default__` defaults players into `old`): OCM owns every melee
  knock, Mental yields by design, both profiles go inert for melee. OCM's
  formula reads `victim.getVelocity()` — the server's stale deltaMovement,
  a zombie field for real clients — so combo verticals are erratic
  0.19–0.40 and never decline. Removing the module: the fork validates
  placement, so MOVE the line into `disabled_modules` (deleting it throws
  "Module not assigned to any list").
- **`attack-frequency` ships `playerDelay: 18` in `always_enabled_modules`**
  (native 1.8 = 20): 9-tick combo cadence, one less free-fall tick, every
  combo vertical runs high even when Mental owns the knock.

Mental warns at startup on both (the `OcmCompatUnit` / `OcmBinding.warnings`
boot checks over `CoexistWarnings`, was `OcmCompatModule.warnFeelOverlaps`); the
management GUI's dashboard shows the OCM coordination mode and whether OCM is
installed (the `/mental kb` overview that used to flag melee ownership is gone
— management moved into the in-game menu in 2.1.0).

## Testing it

- Stage the fork jar: build `~/Documents/BukkitOldCombatMechanics`
  (`./gradlew shadowJar`) → copy to `run/ocm-jar/OldCombatMechanics.jar`;
  the gate then boots floor+ceiling with OCM and the tester auto-switches to
  OcmCoexistenceSuite.
- The ownership discriminator is `KnockbackApplyEvent` (fires only for
  Mental-owned knocks) — Mental-1.7.10 and OCM-1.8 first-hit vectors are
  identical by design, so values alone prove nothing. On the wire, OCM-owned
  hits betray themselves by drifting horizontals (stale `/2` feed-through:
  0.40→0.45) where Mental ships clean profile values.

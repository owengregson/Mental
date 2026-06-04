---
name: ocm-coexistence
description: Use when touching anything that overlaps OldCombatMechanics — damage shaping, knockback ownership, fishing/projectile mechanics, hit-delay/cooldown questions, or the OcmGate. Encodes the ownership split and the rules that keep the two plugins from fighting.
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

`OcmGate.handles(mechanic, decider)` answers it: BOUND mode uses OCM's
service API per player (`isModuleEnabledForPlayer`, reflective, no compile
dep, owning-thread only — freeze answers into snapshots for netty use);
CONFIG mode falls back to global conservative verdicts from OCM's config
(doubled knockback is worse than deferred). Profiles only shape knocks
Mental owns.

## Damage handoff

When OCM shapes damage for an attacker, the fast path must hand the hurt
pipeline **vanilla-shaped** damage (attribute base, 1.9 crit rules, 1.9
sharpness), NOT legacy-composed: OCM decomposes the event into vanilla
components at the attacker's live cooldown charge and re-composes. The fast
path never resets the charge, so the round-trip is lossless (the era pin:
sharpness-5 diamond sword ⇒ 14.25, not 17.5).

## Testing it

- Stage the fork jar: build `~/Documents/BukkitOldCombatMechanics`
  (`./gradlew shadowJar`) → copy to `run/ocm-jar/OldCombatMechanics.jar`;
  the gate then boots floor+ceiling with OCM and the tester auto-switches to
  OcmCoexistenceSuite.
- The ownership discriminator is `KnockbackApplyEvent` (fires only for
  Mental-owned knocks) — Mental-1.7.10 and OCM-1.8 first-hit vectors are
  identical by design, so values alone prove nothing.

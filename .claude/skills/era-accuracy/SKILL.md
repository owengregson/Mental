---
name: era-accuracy
description: Use when a change claims 1.7.10/1.8.9 authenticity or could affect combat feel — the era ground truths, what is client-side and therefore untouchable, the known myths, and the anti-features the community history warns against.
---

# Era accuracy: ground truths, contracts, and anti-features

## Ground truths (decompiled, in-repo: SYNOPSIS.md + docs/legacy-combat.md)

- 1.7.10 and 1.8.9 knockback MATH is byte-identical; the era differences are
  DELIVERY, measured on the real servers
  (docs/research/2026-06-05-era-wire-measurements.md): 1.7.10 ships every
  knock tracker-decayed one tick (ground hits lose ×0.546 horizontal) and
  never restores (combos compound); 1.8.9 melee sends in attack() then
  restores (flat). Measured flights: 1.8.9 plain ≈ 1.99 / sprint ≈ 4.95;
  1.7.10 plain ≈ 0.99 / sprint ≈ 2.54 — "1.7 ≈ half of 1.8" IS the wire
  decay. Era combo verticals DECLINE (the jump stamp free-falls: 1.8.9
  combo hit two ships vy 0.3478); victims stayed LOW in era combos.
  Standing-hit vertical is 0.3608 (equilibrium baseline), never 0.4.
- Legacy damage: sharpness 1.25/level, crit-before-enchant with no sprint
  exclusion, pre-1.9 tool tables (diamond sword = 8), rod/projectile knocks
  from the SHOOTER'S POSITION, probabilistic armor resistance.

## The client-side technique contract (NEVER touch server-side)

These work because movement is client-authoritative; Mental preserves them by
construction and must keep doing so:

- The 0.6 self-velocity multiplier per sprint attack (why high CPS reduces
  received KB — 0.6^clicks — and why straightline locks/sumo reducing exist)
- W-tap / s-tap sprint re-arming; jump-resets; A/D strafe redirection
- A real victim's walk/jump never entered the legacy server's motion fields
  — held input is integrated by the client ON TOP of the knock packet

## Myths (do not implement, do not "fix")

- Victim sprint-key spamming reducing knockback: placebo (the flag changes no
  server math — pinned live by the era suite).
- "1.7 animations reduce KB": placebo; the real incident was a patched 1.12
  Lunar client velocity bug (Nov 2020).

## Anti-features (community history says no)

- Randomized knockback (MMC's most-hated era; determinism IS the product).
- CPS-scaled anything / "anti-reduce" normalization (GommeHD: ~80%
  disapproval, top-player exodus). Fairness features must be opt-in choices.
- Hit-delay knobs (combat rules → OldCombatMechanics).

## Verifying authenticity

Vector-level: KnockbackSuite/ProfileSuite (engine expectation == applied
velocity). Position-level: EraParitySuite (settled endpoint vs the EraOracle
legacy integrator, ≤0.005-block tolerance; combo era gap and held-input
effects demonstrated live). Wire-level: the legacy-lab harness against the
live netty path (the suites never traverse HitPacketListener — FakePlayers
attack server-side). A claim of era accuracy without a pin in one of these
is not yet a claim.

## Era trade feel (measured 2026-06-05 — set expectations BEFORE "fixing")

- Vanilla 1.7.10 sprint wire is HALF of 1.8.9: (0.4914, 0.3731) vs
  (0.9, 0.4607). Mental's live path matches both to 4 decimals
  (legacy-1.7 / legacy-1.8 presets). "Weak trade knockback" reports on
  legacy-1.7 describe vanilla 1.7.10 itself, not a delivery bug.
- The trade-opener opposition: base 0.4 pushes away from the ATTACKER'S
  POSITION, sprint extra 0.5 along the ATTACKER'S YAW. A victim past the
  attacker (face-hug) gets ~0.05 h — era-real, identical on actual 1.7.10.
- The community's "comboable 1.7" memory is modified-spigot feel (Kohi,
  MCSG: ~1.8-strength horizontal). Combo-friendly servers want legacy-1.8
  (era-verified 1.8.9 wire) or kohi (flat 0.42 h, low vy, no compounding);
  legacy-1.7 is the vanilla museum piece.
- Modern vanilla reference (Paper 1.21.11 bare): standing (0.4, 0.3608),
  sprint (0.7, 0.4) — what players are calibrated to with Mental off.

---
name: era-accuracy
description: Use when a change claims 1.7.10/1.8.9 authenticity or could affect combat feel — the era ground truths, what is client-side and therefore untouchable, the known myths, and the anti-features the community history warns against.
---

# Era accuracy: ground truths, contracts, and anti-features

## Ground truths (decompiled, in-repo: SYNOPSIS.md + docs/legacy-combat.md)

- 1.7.10 and 1.8.9 knockback MATH is byte-identical; the era differences are
  DELIVERY, measured on the real servers
  (docs/research/2026-06-05-era-wire-measurements.md, esp. ADDENDUM 2):
  1.7.10's tracker wire was **JOIN-ORDER BIMODAL** — a victim who joined
  before their attacker received the FULL stamp (identical to 1.8.9:
  standing (0.4, 0.3608) ≈ 1.99 blocks, sprint (0.9, 0.4607) ≈ 4.95), a
  victim who joined after got one decay tick less (standing (0.2184,
  0.2751) ≈ 0.99 blocks, sprint (0.4914, 0.3731) ≈ 2.54 — and "relog for
  less KB" folklore). 1.7.10 never restores → combos compound in BOTH
  orders; 1.8.9 melee sends in attack() then restores (flat, order-
  independent — why 1.8 KB is remembered as consistent). Mental's
  `tracker` ships the full stamp; `tracker-decayed` is the opt-in
  later-joiner wire. A "players only move ~1 block" report means the
  decayed wire is shipping — that is a misconfiguration or a bug, NOT
  era truth (this verdict was reversed once already; see addendum 2).
  Era combo verticals DECLINE (the jump stamp free-falls: 1.8.9
  combo hit two ships vy ~0.25–0.28 depending on stamp phase); victims
  stayed LOW in era combos (apex dips to ~0.5 every other hit).
  Standing-hit vertical is 0.3608 (equilibrium baseline), never 0.4.
- **The era's within-tick attack ordering** (addendum 4): an attack was
  processed in the attacker's connection slot BEFORE the victim's
  same-tick movement packets — a combo hit thrown the instant its victim
  touches down (the spam norm) reads the PRE-landing flight and ships the
  declining vertical, never a grounded 0.3608 re-stamp. Mental replicates
  via tick-stamped packet records + the `MotionLedger`'s end-of-previous-tick
  read (was `VictimMotion.currentExcludingTick`; the published `PlayerView` IS
  that freeze), the +1 staleness allowance in the view's immune check (was
  `Snapshot.isDamageImmune`), and the `auto` feedback window at `(max/2 − 1)`
  ticks. "Combos feel floaty / too much vertical" =
  boundary hits shipping grounded re-stamps = one of those three broke.
- Legacy damage: sharpness 1.25/level, crit-before-enchant with no sprint
  exclusion, pre-1.9 tool tables (diamond sword = 8), rod/projectile knocks
  from the SHOOTER'S POSITION, probabilistic armor resistance.
- The combat compendium (docs/research/2026-06-06-combat-compendium.md) is
  the authoritative mechanic-by-mechanic reference: knockBack ignores its
  strength param (0.4 hardcoded); sprint and KB-enchant are LEVELS of one
  extra (0.5 h/level + flat 0.1 v — KB II ships 1.4, sprint+KB II 1.9);
  crits add ZERO knockback; blocking halves damage AFTER knockback (era
  blocked hits knock FULL — Mental cancels only FULL blocks); a stronger
  hit mid-invuln deals difference damage with NO knock and no flinch;
  PURE-vanilla snowballs/eggs/rod bobbers never knock PLAYERS (the
  EntityPlayer zero-damage gate, both eras) — era rodding is CraftBukkit-
  lineage behavior, which Mental's rod/projectile modules target; the era
  jump stamp includes Jump Boost; ice nearly doubles 1.7 knockback
  (slipperiness × 0.91 ground drag); attack() ×0.6's the attacker's own
  server fields. Each claim is decompile-cited and wire-measured there.

## The client-side technique contract (NEVER touch server-side)

These work because movement is client-authoritative; Mental preserves them by
construction and must keep doing so:

- The 0.6 self-velocity multiplier per sprint attack (why high CPS reduces
  received KB — 0.6^clicks — and why straightline locks/sumo reducing exist)
- W-tap / s-tap sprint re-arming; jump-resets; A/D strafe redirection
- A real victim's walk/jump never entered the legacy server's motion fields
  — held input is integrated by the client ON TOP of the knock packet

**The one exception that needs server-side reconstruction — block-hitting's
sprint reset.** In 1.7/1.8 starting a block dropped the sprint flag (the client
sent STOP_SPRINTING), and the re-engage on release re-armed the sprint KB bonus
— that's why block-hitting maintained the bonus like an easy w-tap. Modern
clients (1.17+) KEEP the sprint flag through an item-use block (you're slowed
but still flagged sprinting — visible as sprint particles while blocking), so
that STOP/START never crosses the wire and block-hitting silently stops
resetting sprint. Mental reconstructs it: the `SwordBlockingUnit` (was
`SwordBlockingModule.resetSprintForBlock`) re-arms the sprint ledgers on the
block right-click via the `SprintWire` (was `SprintTracker.armSprintReset`),
gated on the RAW client sprint flag (the wire's client-sprint read, was
`SprintTracker.isClientSprinting` — the only signal that survives Mental's own
post-hit `setSprinting(false)`) so a stationary defensive block never gains a
phantom bonus. NOTE the local-player sprint-particle visual during a block is
client-authoritative rendering and cannot be removed server-side (same family as
the right-click "item rises" cosmetic) — only the FUNCTIONAL reset is restored.

## Myths (do not implement, do not "fix")

- Victim sprint-key spamming reducing knockback: placebo (the flag changes no
  server math — pinned live by the era suite).
- "1.7 animations reduce KB": placebo; the real incident was a patched 1.12
  Lunar client velocity bug (Nov 2020).

## Anti-features (community history says no)

- Randomized knockback (MMC's most-hated era; determinism IS the product).
- CPS-scaled anything / "anti-reduce" normalization (GommeHD: ~80%
  disapproval, top-player exodus). Fairness features must be opt-in choices.
- Hit-delay knobs (a rejected anti-feature — see combo-hold design).

## Verifying authenticity

Vector-level: KnockbackSuite/ProfileSuite (engine expectation == applied
velocity). Position-level: EraParitySuite (settled endpoint vs the EraOracle
legacy integrator, ≤0.005-block tolerance; combo era gap and held-input
effects demonstrated live). Wire-level: the legacy-lab harness against the
live netty path (the suites never traverse the parse rim, was
`HitPacketListener` — FakePlayers attack server-side). A claim of era accuracy
without a pin in one of these
is not yet a claim.

## Era trade feel (measured 2026-06-05; corrected same day, addendum 2)

- The consistent era wire is the FULL stamp: standing (0.4, 0.3608),
  sprint (0.9, 0.4607) — 1.8.9 always, and 1.7.10 whenever the victim
  joined first. The HALF wire ((0.4914, 0.3731) sprint) was 1.7.10's
  later-joiner mode only; shipping it universally was a measurement
  artifact (the lab's fixed connect order) that survived one full release
  and one "working as intended" verdict. As of 1.5.0 every preset ships
  the full stamp; `tracker-decayed` opts back into the half wire.
- The trade-opener opposition: base 0.4 pushes away from the ATTACKER'S
  POSITION, sprint extra 0.5 along the ATTACKER'S YAW. A victim past the
  attacker (face-hug) gets ~0.05 h — era-real, identical on actual 1.7.10.
- The community's "comboable 1.7" memory: Kohi/MCSG forks AND vanilla's
  own full-stamp mode. legacy-1.7 (full stamp + ledger combos) now matches
  it; legacy-1.8 is the flat 1.8.9 wire; kohi is the fork feel.
- Modern vanilla reference (Paper 1.21.11 bare): standing (0.4, 0.3608),
  sprint (0.7, 0.4) — what players are calibrated to with Mental off.

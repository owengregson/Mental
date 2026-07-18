---
name: legacy-motion-physics
description: Use when reasoning about knockback, velocity, trajectories, player movement, or any motion math in Mental — the exact Minecraft motion model (identical 1.7.10 → modern for knocked players), its constants, integration order, and the traps that produce block-scale errors.
---

# The Minecraft motion model (1.7.10 ≡ 1.8.9 ≡ modern, for knocked players)

## Constants

| Thing | Value |
| --- | --- |
| Gravity | 0.08 / tick |
| Vertical drag | × 0.98 (after gravity) |
| Horizontal drag, airborne | × 0.91 |
| Horizontal drag, on stone | × 0.546 (= 0.6 slipperiness × 0.91) |
| Walk / sprint ground accel | 0.1 / 0.13 per tick |
| Air accel (held key) | 0.02 / 0.026 (sprint) |
| Jump impulse | 0.42 |
| Velocity packet clamp | ±3.9 per axis (motion × 8000 as shorts) |
| Terminal walk/sprint speed | accel × 0.546/0.454 stored ⇒ moves 0.22 / 0.286 per tick (4.3 / 5.6 m/s) |

## Integration order per tick (every version)

1. Input acceleration (held keys) — client-side for real players
2. **Friction factor chosen from the PRE-move ground state**
3. Move by motion (collisions land the entity; vertical collision zeroes motY)
4. `motY = (motY − 0.08) × 0.98`; horizontal × the factor from step 2

## The traps (each cost a debugging round)

- **Pre-move friction**: a knock's LAUNCH tick decays at ground drag even
  though the move lifts the victim. Modeling friction post-move overshoots a
  0.4 knock by a full block (3.06 vs the real ~2.06).
- **onGround only refreshes while moving downward.** Zeroing motY on a
  standing entity makes it read airborne; the next launch then takes air
  friction. Clear horizontal axes only; leave the −0.078 gravity equilibrium.
- **Send-then-restore**: melee knockback to player victims is sent as a packet
  and the server's own motion copy is restored immediately (1.8.9+ vanilla,
  still true today). The CLIENT integrates the trajectory. Server-side motion
  of a player victim after a melee hit is NOT the trajectory.
- **Knockback math never changed; the WIRE did** (measured on real vanilla:
  project/docs/research/2026-06-05-era-wire-measurements.md). 1.7.10 shipped knocks
  via the next tick's tracker, and the wire was **JOIN-ORDER BIMODAL**
  (addendum 2, measured both orders): the tracker runs BEFORE the
  per-connection phase, whose slots run in join order with each player's
  physics fused to their own slot — a victim who joined BEFORE their
  attacker received the FULL stamp (identical to 1.8.9), a victim who
  joined AFTER got it one decay tick late (ground hits lose ×0.546
  horizontal — the era's "relog for less KB" folklore). 1.8.9 melee sent
  inside attack() then RESTORED the pre-hit fields — order-independent,
  hence "consistent" in era memory. 1.7.10 never restored → combos
  compounded (both orders). Rod/projectile rode the tracker on BOTH eras.
  `KnockbackDelivery` models this per profile: `tracker` ships the full
  stamp (the dominant mode); `tracker-decayed` opts into the later-joiner
  wire; never both — determinism is the product.
- **The era vy baseline is a state machine, not the delivered knock**:
  servers ticked player physics input-free, so standing victims park at the
  −0.0784 equilibrium (standing-hit vertical = 0.3608, NOT 0.4), and the
  movement handlers' jump bookkeeping OVERWRITES motY = 0.42 at any rising
  liftoff (+0.2 facing push if sprinting) — a knocked victim's baseline one
  tick later is jump-impulse FREE-FALL (1.8.9 combo hit 2 ships vy
  0.2477–0.2862 = 0.42 eight-or-nine gravity steps later, stamp-phase
  dependent, wire-measured). The `MotionLedger` + `GroundFsm` (was
  `VictimMotion` + `GroundTransitionWatcher`; packet-fed via the parse rim, was
  `GroundPacketTap`, for real clients, session tick sampler for packetless ones)
  replicate exactly this; era combo verticals DECLINE, which is why combo
  victims stayed low.
- **Within a tick, the attack was judged BEFORE the victim's movement
  packets** (the attacker's connection slot ran first): a combo hit thrown
  the same tick its victim touches down — the spam-cadence norm — reads the
  PRE-landing flight. Mental: tick-stamped packet records + the `MotionLedger`'s
  end-of-previous-tick read (was `VictimMotion.currentExcludingTick`) give the
  published `PlayerView` the end-of-previous-tick view. A same-tick landing
  smuggled into the attack
  read ships a grounded 0.3608 re-stamp the era never sent — apexes stop
  dipping, combos read floaty (the live symptom that found this, addendum 4).
- Reference flights (flat ground, measured): full stamp plain ≈ 1.99
  blocks, sprint ≈ 4.95 (1.8.9 always; 1.7.10 victim-joined-first);
  decayed wire plain ≈ 0.99, sprint ≈ 2.54 (1.7.10 victim-joined-after
  only — an artifact-prone half; a "players move ~1 block" report means
  the decayed wire is shipping). Jump impulse alone rises ≈ 1.25.
- **Ground drag is slipperiness × 0.91 from the block UNDER the feet**
  (compendium §2): stone 0.546, ICE/packed ice 0.8918, slime 0.728 — a
  1.7.10 packed-ice lane ships hit 1 at 0.4 × 0.8918 = 0.3567 (decayed
  wire) and residuals compound between hits (settle 5.37 vs stone 2.99 —
  ice nearly DOUBLES era knockback). The ledger reads it per segment
  (GroundFriction); the bot harness does NOT model floor slip, so its
  SETTLE under-reads on ice — trust the per-hit wire values there.
- **A knock's residual takes TWO grounded pre-move decays** (knock tick +
  liftoff tick) before the air segment: era chains pin
  0.4 × slip² × 0.91^k exactly (ice hit 2 = 0.4821 = 0.4×0.8918²×0.91⁷).
  Ledger implementation: submit-time launch state (the live flag flips on
  the hit tick!), launch-tick drag in recordLiftoff, grounded seed on a
  player's first observed state.
- **The era jump stamp includes Jump Boost**: motY = 0.42 + 0.1×(amp+1) +
  the 0.2 sprint push (decompiled jump(), both eras; measured: a boosted
  victim's combo hit 2 ships vy 0.3286). The watcher caches the impulse
  per player.
- **attack() also multiplies the ATTACKER's server fields ×0.6** on every
  bonus-knockback hit (beside the sprint clear, both eras): a player
  knocked mid-trade who counter-hits compounds the next received knock off
  the smaller residual. Ledger: scaleHorizontal on accepted bonus-KB hits.
- Mental's `friction` knobs are SURVIVING-FRACTION multipliers (vanilla ÷2 ≡
  0.5, the NachoSpigot convention). Forks publishing divisors (Panda/Sport/
  Wind, typically 2.0) port as 1/d — pasting a divisor unchanged inverts the
  feel. Full research: project/docs/research/2026-06-04-improved-knockback.md.

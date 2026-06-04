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
- **Knockback math never changed**; 1.7.10 vs 1.8.9 differ only in delivery —
  1.7.10 never reverted the victim's fields, so hits compounded (combos). The
  VictimMotion ledger reproduces this; `getVelocity()` cannot.
- Reference distances (flat ground): plain 0.4/0.4 knock ≈ 2.06 blocks;
  sprint hit ≈ 5.07; jump impulse alone rises ≈ 1.25.
- Mental's `friction` knobs are SURVIVING-FRACTION multipliers (vanilla ÷2 ≡
  0.5, the NachoSpigot convention). Forks publishing divisors (Panda/Sport/
  Wind, typically 2.0) port as 1/d — pasting a divisor unchanged inverts the
  feel. Full research: docs/research/2026-06-04-improved-knockback.md.

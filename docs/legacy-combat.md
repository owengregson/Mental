# The 1.7.10 Combat Model

Mental mirrors 1.7.10 combat, not 1.8.9. The two versions share every
knockback *formula* byte for byte — what separates them is **delivery**,
and delivery is what made 1.7 PvP feel the way it did. This document
records exactly what Mental reproduces, how, and where the line of
possibility runs for a server-side plugin.

## The defining difference: residual motion

A 1.7.10 server never told the victim's motion fields about reality. A
knockback wrote into them, friction decayed them, and movement packets
never touched them. So when a second hit arrived shortly after a first,
its `motion/2 + 0.4` computed from the **residual of the previous knock**
— and successive hits compounded. That stacking is the mechanical core of
1.7-style combos.

1.8.9 killed it with one block of code: melee knockback to a player is
sent immediately and the server's motion copy is **reverted**, so every
melee hit computes from pre-hit state and lands flat. Modern servers keep
that revert to this day.

### Mental's residual ledger

Mental cannot un-revert vanilla, and the server's `getVelocity()` for
players is reverted or stale on every supported version. Instead it keeps
the legacy fields itself — the `MotionLedger`:

- Every velocity actually delivered to a victim (Mental's knockback, rod
  and projectile knocks, vanilla velocities Mental left alone, other
  plugins' velocities) is recorded by the **delivery desk** in the one
  velocity-event apply that ships it — the same operation, same thread, same
  object that writes the wire value, so no second listener and no side channel
  can disagree about what was delivered.
- Reads decay the recorded vector through the legacy friction model (`Decay`):
  vertical `(v − 0.08) × 0.98` per tick, horizontal `× 0.91` airborne or
  `× 0.546` grounded, dead after sixty ticks.
- The knockback engine consumes the ledger — never `getVelocity()` — for
  player victims. Mobs are server-simulated, so their live velocity is
  already the legacy-correct input.

Client-only motion (walking, jumping, W-tapping into the knock) never
enters the ledger, exactly as it never entered the legacy server's
fields. That is why W-tapping reduces *felt* knockback without reducing
*computed* knockback — in 1.7.10 and in Mental.

### The era switch

`knockback.modifiers.combos` selects the delivery era:

- `true` (default) — 1.7.10: every applied knock persists in the ledger
  and feeds the next hit's friction halving.
- `false` — 1.8.9: melee applies are *not* recorded (the send-then-revert
  semantics), while rod, arrow and thrown-projectile knocks still are —
  reproducing 1.8.9's hybrid, where a rod or arrow residual feeds the
  next sword hit exactly once.

## What else is mirrored

| Mechanic | 1.7.10 rule | Where |
| --- | --- | --- |
| Melee knockback | friction `v/2`, base `0.4/0.4`, vertical cap on the BASE before bonus, sprint/enchant `+0.5/+0.1` per level along yaw, additive and resistance-blind | `KnockbackEngine` |
| Knockback resistance | probabilistic all-or-nothing (`armor-resistance: legacy`); the era item pool had no partial sources, so `none` is the default | `ResistancePolicy` |
| Velocity packet clamp | each axis to ±3.9, the short-encoding limit | `KnockbackEngine.clamp` |
| Projectile knockback | pushes away from where the **shooter stood**, never along flight; snowball/egg/pearl are full zero-damage hits | `ProjectileKnockbackUnit` |
| Arrow Punch | `0.6/level` along horizontal flight, `+0.1` vertical, additive, resistance-blind; level fixed at shoot time | `ProjectileKnockbackUnit` / `PunchMath` |
| Rod bobber | a real zero-damage hit: full base knock away from the **angler's position**, arms the 20-tick hurt window — so a rod hit suppresses the knockback of a melee hit inside ten ticks (the era's rod-then-sword interplay) | `FishingKnockbackUnit` |
| Rod reel-in | pull toward the angler `Δ × 0.1` per axis plus `√distance × 0.08` lift (`reel-in: legacy`) | `FishingKnockbackUnit` |
| Rod cast | speed 0.6, gaussian spread 0.0075, hook gravity restored to the legacy 0.04 | `RodVelocityUnit` |
| Sharpness | `1.25 × level` (1.9 changed it to `0.5×level + 0.5`) | `DamageTables` |
| Crit | ×1.5 on the **weapon damage before enchantments** (the legacy order); sprinting does not block crits — that exclusion is a 1.9 rule | `DamageTables` |
| Tool damage | pre-1.9 tables: sword `4+tier` (a diamond sword deals 8), axe `3+tier`, pickaxe `2+tier`, shovel `1+tier` (`legacy-tool-damage`) | `DamageTables` |
| Attack cooldown | none: the fast path cancels the vanilla attack packet so `Player#attack` never runs, and the amount is composed off the attribute base — the 1.9 charge meter is never consulted, structurally | `DamageShaper` |
| Hurt-window gating | the 20/10 partial-hit rule runs in vanilla's own pipeline (Mental damages through it), so partial hits deal difference-only damage and never knock back | by construction |
| Sweep attacks | never happen: the fast path cancels the vanilla attack packet, and `Player#attack` — where sweeps live — never runs | by construction |

The latency-compensation module is unchanged in role and now reads its
vertical input from the same ledger the engine uses: the hint is the
ledger's Y extrapolated forward by the victim's compensated ping. One
motion model, two consumers.

## What a server cannot mirror

These are client-side mechanics. No plugin can reach them, Mental
included; they are listed so nobody wastes an evening looking for the
config key.

- **The miss penalty.** 1.7.10 clients let you swing at air for free;
  1.8.9 clients lock attacking for 10 ticks on a whiffed click, and
  modern clients also reset the attack-charge meter. The lockout swallows
  clicks *inside the client* — the server never sees them. What Mental
  *does* neutralize is the damage side: charge never scales damage on the
  fast path, so a whiff never weakens the next hit that lands.
- **Entity targeting at 3.0–4.5 blocks.** 1.8.9 clients convert an
  aimed-at-but-out-of-range entity into a penalized miss; 1.7.10 clients
  charged nothing. Client raycast, client rule.
- **The sprint-reset hitch.** All eras un-sprint the attacker inside a
  bonus-knockback hit — that part holds everywhere. But the "client
  re-sprints next tick with the key held" half does NOT hold for a modern
  client at spam cadence: it never drops its local sprint flag (its own
  clear sits behind the ≥90%-charge gate it rarely reaches), so there is no
  re-engage and no fresh START. Mental therefore treats the sprint extra as a
  **per-engagement resource** — its `SprintWire` consumes the engagement on
  each bonus hit and re-arms only on a client-expressed re-gesture (a w-tap /
  s-tap / GUI STOP→START, or the block-hit re-arm): one engagement, one sprint
  knock (era-measured: a no-w-tap double flew 7.2 blocks, a w-tap double 11.4).
  Mental never writes the server's sprint flag DOWN — on 1.21.2+ that echoes to
  the attacker's own modern client and latches its sprint off (the modern-client
  echo latch) — so the flag stays client-authoritative.
- **The attack indicator.** Modern clients render the charge meter from
  their own attributes. Mental makes charge irrelevant; it cannot hide
  the crosshair UI.

## Interactions worth knowing

- **One hit, one knock.** All sources submit to one **delivery desk**;
  arbitration is last-submitter-wins per victim per tick, which is how a rod
  hit's vector beats the melee vector its own damage call provokes.
- **`KnockbackApplyEvent`** now fires for every Mental knockback source
  (melee, rod, projectile, arrow), on the victim's owning thread, with
  the attacker when one exists.
- **Anticheat posture is unchanged.** Everything above flows through
  `PlayerVelocityEvent` and real damage calls — server-authoritative by
  construction. The only out-of-band behavior remains the optional netty
  pre-send, governed by `anticheat.mode` exactly as before.
- **OldCombatMechanics divides this cleanly with Mental.** OCM owns the
  combat *rules* (cooldown, blocking, regen, armour); Mental owns knockback
  and hit delivery — and wherever OCM's own knockback/fishing/projectile
  modules are enabled, Mental yields those interactions automatically, per
  player modeset. The ownership table and mechanics live in
  [ocm-coexistence.md](ocm-coexistence.md).

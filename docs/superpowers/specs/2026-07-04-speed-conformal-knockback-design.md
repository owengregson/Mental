# Speed-conformal knockback ("pace scaling") — design

> Owner ask (2026-07-04): base-speed combos on the signature preset are
> buttery; with Speed III on both players nobody can combo anyone. Find the
> fundamental reason; design a Mental-specific solution so combos feel smooth
> across the whole speed range. "Potentially the system might dynamically
> scale knockback based on the speed of the attacker or some other factors."

## 1. The fundamental reason: an absolute stamp inside attribute-scaled locomotion

Era knockback is an **absolute velocity stamp** (sprint wire ≈ (0.9, 0.46),
decaying ×0.91/tick airborne — compendium §motion, ledger-pinned). It was
tuned, implicitly, for **base-speed locomotion** (movement-speed attribute
0.1; sprint ×1.3 ⇒ ~0.28 b/t chase).

A combo is a **spacing equilibrium between three speeds**:

| # | Speed | Scales with the Speed attribute? |
|---|---|---|
| S1 | victim's knock-flight speed (the stamp + air drag) | **NO** — the stamp is absolute, and airborne player acceleration is a fixed constant (~0.02/t, attribute-independent; assumption A1, verify at impl) |
| S2 | attacker's ground chase speed | YES (×1.6 at Speed III) |
| S3 | victim's grounded flee speed | YES (×1.6 at Speed III) |

At base speed the numbers happen to balance: during the victim's ~10–14-tick
knock flight the attacker closes the gap at slightly better than flight
speed, arriving back at the reach edge roughly when the 10-tick immunity
window expires — a **sweet-spot dwell time** of many ticks per cycle, wide
enough for human click timing. That equilibrium IS "buttery."

Speed III multiplies S2 and S3 by 1.6 and leaves S1 untouched. Two failures
follow immediately:

- **Overshoot:** the attacker (0.45 b/t) overtakes the victim's flight
  (unchanged, avg ~0.35 b/t) mid-arc — inside the immunity window — and runs
  through/past the hitbox. The sweet spot is crossed in 1–3 ticks instead of
  dwelt in.
- **No re-spacing:** the instant the victim lands, S3 = S2, so the spacing
  the attacker needs to set up the next hit never re-establishes; the fight
  oscillates between body-blocking and a dead-equal chase.

The dwell time collapses below human reaction — *nobody can combo anyone*.
It is not damage, reach validation, or hit registration: it is the spacing
equilibrium, and every fixed-stamp knockback system (all of era 1.7/1.8 —
this is exactly why speed-potion kits famously "had no combos") breaks the
same way.

## 2. The design: scale SPACE by the pace factor, never TIME

Everything temporal in a combo is fixed by things we must not touch: click
cadence (human), the 10-tick immunity window (era rule), flight duration
(vertical stamp + gravity), jump-reset and w-tap timing (client contract).
Everything **spatial** scales with the Speed attribute except the knock.

**So make the knock conform:** multiply the **horizontal components only** of
every Mental-delivered melee knock by the attacker's pace factor

```
s = clamp(min, max, (attackerMoveSpeedAttr / eraBaseline(stance))^exponent)
```

- `eraBaseline(stance)` = 0.13 for a sprint hit, 0.10 otherwise — the
  attribute value a base-speed attacker has in that stance, so plain
  sprinting yields s = 1.0 and the era stamp ships byte-identically.
- Speed III sprint ⇒ attr 0.208 ⇒ s = 1.6 (exponent 1).

**Why this exactly restores the feel:** with both players at Speed III,
S2 and S3 are ×1.6 by the game, and S1 becomes ×1.6 by us — every LENGTH in
the combo system (chase per tick, flee per tick, flight displacement) scales
by 1.6 while every TIME (flight duration, cadence, immunity) is unchanged.
The Speed-III combo is a **spatially-zoomed replica of the base-speed combo
with identical rhythm** — same dwell ticks in the sweet spot, same
jump-reset windows, same declining combo verticals. Conformal in space,
invariant in time.

**Why horizontal only:** scaling the vertical stretches flight TIME (higher
arc), which desynchronizes the combo rhythm from the immunity window and the
era boundary-hit ordering — the one thing the transformation must preserve.
Vertical stays era-exact, always.

**Why the attacker's ATTRIBUTE and not impact velocity:** a
momentum-transfer variant (knock ∝ closing velocity at impact) also fixes
overshoot but makes every knock depend on instantaneous approach speed —
hit-to-hit variance, i.e. feel randomness, the opposite of what era combat
is loved for. The attribute is stable within a fight, deterministic
(same stance + same potion ⇒ same knock), and captures Slowness for free
(s < 1: slowed players stay comboable instead of being pinned in place).
Rejected variants recorded: victim-attribute scaling (makes fast victims
*less* catchable in flight — inverts the goal), relative-speed scaling
(couples both attributes; asymmetric fights get double-counted), vertical
co-scaling (breaks rhythm, above).

**The honest limit:** reach (3.0) cannot scale — it is a client/era
constant. As s grows, the equilibrium spacing approaches the reach edge, so
extreme factors (Speed V+) still compress the margin; the `max` clamp
(default 2.0) marks where the conformal window ends. The asymmetric case
(victim fast, attacker slow) is kiting, not comboing — no knock scale fixes
a grounded flee the attacker cannot outrun; out of scope by design.

## 3. Profile schema (era-exact no-op default — invariant preserved)

New optional block in the knockback profile (all knobs shown with defaults):

```yaml
speed-scaling:
  mode: off        # off | attacker   (off = byte-identical era behavior)
  exponent: 1.0    # s^k temper; 1.0 = fully conformal
  min: 0.5         # clamp on the final factor
  max: 2.0
```

`parse(empty) == LEGACY_17` holds: absent block ⇒ mode off ⇒ the engine
multiplies by nothing. Archived-server presets (kohi/lunar/mmc/…) are
UNTOUCHED — they are historical records. The **mental signature preset**
opts in (`mode: attacker`), since it is Mental's own preset and the owner's
explicit ask. GUI: the profile screen exposes the block like other knobs.

## 4. Placement and mechanics

- **Kernel (pure, additive):** `PaceScale.factor(attackerAttr, sprinting,
  config)` — hand-computed unit pins (s=1 at base sprint/walk; 1.6 at
  Speed III sprint; clamps; exponents). `KnockbackEngine` applies the factor
  to the horizontal components of the final vector AFTER the era math and
  the sprint/wtap extras, BEFORE the ±3.9 packet clamp.
- **Scale at stamp creation** so the `MotionLedger` sees the scaled motion:
  multiplicative scaling commutes with the ledger's linear decay, so decayed
  combo re-stamps stay coherent with the conformal geometry (A3).
- **State plumbing:** `EntityState` (attacker capture) gains the
  movement-speed attribute value, read at the platform seam via the existing
  `Attributes` resolver (API present 1.9+; boot-probed fallbacks below —
  where unreadable, attr resolves to the era baseline ⇒ s=1, loud in the
  boot report, never silent). The netty fast path pre-sends from published
  views — the attacker's attribute rides the per-tick `PlayerView` publish
  (additive field), so pre-sent knocks scale identically to tick-path knocks
  (one stamp, one truth).
- **Projectiles/rod:** unaffected in v1 (era rod/arrow knocks are
  shooter-position stamps; combo equilibrium is a melee phenomenon). Knob
  scope documented.
- **Folia:** per-hit capture, no cross-region reads beyond what melee
  delivery already does.

## 5. Assumptions to verify at implementation (never assumed silently)

- **A1:** airborne player acceleration is attribute-independent across the
  range (fixed jump/flying factor; Speed affects ground only) — javap on a
  legacy + a modern server; this is the cornerstone of §1's table.
- **A2:** Speed/Slowness modify `generic.movement_speed` multiplicatively
  (±20%/level transient modifier) on every supported version; the Attributes
  seam reads the EFFECTIVE value (base × modifiers).
- **A3:** ledger interplay pins: a scaled hit-1 stamp followed by a combo
  hit-2 produces exactly s × the base-speed hit-2 wire values (unit pin +
  era-parity live case at s=1.6).

## 6. Verification

Unit: PaceScale pins; engine pins (s=1 byte-identity with every existing
pin — the no-op proof; s=1.6 horizontal-only scaling; clamp/exponent edges).
Live (tester): a Speed-III fake pair on the signature preset — assert hit-1
and combo-hit-2 wire stamps are exactly ×1.6 horizontal / unchanged vertical
vs the base-speed pins; assert mode-off byte-identity under Speed III.
Feel validation stays owner-side (SimpleBoxer sparring at Speed 0–III).

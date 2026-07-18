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

## 7. Implementation outcome (2026-07-04)

Shipped exactly as designed. Branch `feat/pace-scaling`.

**Assumptions (javap on real 1.12.2 spigot-mapped + 1.21.11 Mojang-mapped
jars; full evidence in `docs/superpowers/research/2026-07-04-pace-scaling-
assumptions.md`):**

- **A1 — TRUE both eras.** The movement-speed attribute is selected for the
  horizontal move-relative speed ONLY inside the `if (onGround())` branch —
  modern `LivingEntity.getFrictionInfluencedSpeed` (`getSpeed()×0.216/f³` when
  grounded, `getFlyingSpeed()` = fixed `0.02f` when airborne); legacy
  `EntityLiving.a(FFF)` offsets 1081–1106 (`cy()` grounded vs field `aR`
  airborne). A knocked player's flight never scales with Speed. Premise holds;
  no escalation.
- **A2 — TRUE both eras.** SPEED = a `MOVEMENT_SPEED` modifier `0.2`/level,
  `ADD_MULTIPLIED_TOTAL` (modern clinit); legacy `MobEffectList.a(int,mod)` =
  `amount×(amplifier+1)`; `CraftAttributeInstance.getValue()` delegates to the
  NMS effective value both eras. Measured attr matches the design exactly:
  sprint baseline 0.13, Speed III sprint 0.208 ⇒ s = 1.6.

**The one design refinement (§4.1 prose → the exact A3 pin).** §4.1 reads
"scale the horizontal components of the final vector"; taken literally with
the ledger storing the delivered (scaled) motion (§4.2, confirmed — the desk
records the shipped vector), a combo hit-2 would double-count the residual and
ship `s²`, breaking A3. The exact realization that satisfies A3 (the required
pin) and the conformal goal is to scale the **fresh knock** (base push +
sprint/wtap/enchant extras) and NOT the friction-carried residual, which
already carries its own stamp's scaling. Then every hit's wire is `s ×` its
base value, so hit-2 = exactly `s ×` base hit-2 (`s²` if the residual were
re-scaled). This is faithful to the task's binding wording ("scaling at stamp
creation so the ledger sees scaled motion" + the exact A3 pin); documented at
`KnockbackEngine` and pinned in `KnockbackEngineTest.comboHitTwoIsExactly…_A3`.
Verified numerically (LEGACY_17, s=1.6): base hit-2 z = R·0.5+0.4, scaled
hit-2 z = 1.6R·0.5 + 0.64 = 1.6×(R·0.5+0.4). Not a spec/invariant conflict —
a precise reading of imprecise prose — so implemented, not escalated.

**Pin arithmetic (the 1.6 case).** Speed III sprint attr = 0.1×1.3×1.6 =
0.208; 0.208 / 0.13 = 1.6 (exponent 1). Sprint hit-1 straight down +z on
LEGACY_17: base z = 0.4 push + 0.5 sprint = 0.9; scaled = 1.6×0.4 + 1.6×0.5 =
0.64 + 0.8 = 1.44 = 1.6×0.9. Vertical 0.5 → unchanged. All fresh (no residual)
⇒ clean ×1.6.

**Gate (fresh-nonce PASS, quoted verbatim):**

- `./gradlew build` — GREEN (unit + apiCompat japicmp api-2.3.2 vs 2.2.2 +
  kernel-Bukkit-free + all four mega-jar gates; new attribute reads Java-8-clean
  in the base tree). Final-tree nonces below (re-run after the `SupersededPresets`
  addition so the nonce matches the committed tree):
- `checkIntegrationTest_1_9_4` — PASS `nonce=eba758cb-7b2d-46b4-bb39-a53ab6f82c02`
- `checkIntegrationTest_1_21_11` — PASS `nonce=834b7b65-7b02-4774-92fe-c425071538de`
- `checkIntegrationTest_26_1_2` — PASS `nonce=6b133933-6704-49f4-a9af-5fece336c44f`
- All three pace cases (×1.6, inverse control, A3 combo) RAN (not skipped, 3/3)
  on every entry, legacy floor included.

**Discovered (legacy).** On the signature preset (air multipliers non-identity)
a clientless fake reads `isOnGround()=false` on the 1.9/1.10 NMS, so the
production `captureVictim` selects the airborne air-multiplier branch. The
tester pace expectation now uses the same production `captureVictim` (not the
physical-grounded `restingVictim`), so the grounded flag + air multipliers
match the wire on every version. legacy-1.7's identity air multipliers had
hidden the same divergence in the existing suites.

**GUI/preset decisions.** The knockback GUI screen is a profile picker with a
read-only per-profile lore summary (not a per-knob editor), so pace scaling
gets one read-only lore line shown ONLY when a profile opts in — the nine OFF
presets stay uncluttered; no bespoke screen was warranted.

Preset rollout via the `SupersededPresets` pristine-upgrade precedent: the
signature preset shipped 2.2.1–2.3.1 without a `speed-scaling` block, so an
UNEDITED signature.yml on an existing install parses to those values with pace
OFF — which would NOT match the new bundled default (ATTACKER) and so would
silently miss the feature. So the pre-pace signature (current values, pace
OFF) is registered as `SupersededPresets.SIGNATURE_2_2_1`; `ConfigStore`
recognises an unedited pre-pace file as verbatim-superseded and regenerates it
in place with the new block (pace ATTACKER), exactly as the 1.8.0 archived-
values round rolled out. An OWNER-EDITED signature.yml (any value differs) is
never touched. `sameValues` now compares `paceScaling`, so the OFF-on-both
match still holds for kohi/mmc/lunar's existing superseded revisions. Every
OTHER preset is untouched (OFF), and `parse(empty) == LEGACY_17` holds.

**Deviations:** none beyond the §4.1-prose refinement above (all pins,
invariants, and the parse-empty == LEGACY_17 test hold with the new block
absent).

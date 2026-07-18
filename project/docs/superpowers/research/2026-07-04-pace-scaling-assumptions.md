# Speed-conformal knockback — physics assumptions verified (A1/A2)

Source: `javap` against the real server jars on disk (nms-archaeology
procedure), one legacy + one modern, per the design's §5 assumptions protocol
(`docs/superpowers/specs/2026-07-04-speed-conformal-knockback-design.md`).
Both assumptions are the load-bearing premise of §1's three-speeds table; both
are **CONFIRMED**. Evidence is bytecode, not memory.

- Legacy jar: `run/legacy-probe/1.12.2/cache/patched_1.12.2.jar` (spigot-mapped,
  `net.minecraft.server.v1_12_R1`).
- Modern jar: `run/1.21.11/versions/1.21.11/paper-1.21.11.jar` (Mojang-mapped).

## A1 — airborne player acceleration is attribute-independent — TRUE

> The movement-speed attribute feeds GROUND movement only; the airborne factor
> is a fixed constant. (Speed affects ground chase, never knock-flight air
> acceleration — the cornerstone of §1's S1 row.)

### Modern (1.21.11)

`LivingEntity.getFrictionInfluencedSpeed(float slipperiness)` is the sole
selector of the horizontal move-relative speed, and it branches on
`onGround()`:

```
private float getFrictionInfluencedSpeed(float);
   1: invokevirtual onGround:()Z
   4: ifeq          24                 // NOT on ground -> jump to airborne branch
   7: getSpeed()F                      // <-- movement_speed attribute (the `speed` field)
  11: ldc_w  float 0.21600002f
  14..19: fload_1 fmul fload_1 fmul fdiv   //  * (0.21600002 / slipperiness^3)
  21: goto 28
  24: getFlyingSpeed()F                // <-- airborne branch: NO attribute term
  28: freturn
```

`getSpeed()` just returns the `speed` field, which `aiStep()` refreshes from the
`MOVEMENT_SPEED` attribute each tick — so it carries the sprint + Speed/Slowness
modifiers. It is read **only** inside the `if (onGround())` branch. The airborne
branch calls `getFlyingSpeed()`:

```
protected float getFlyingSpeed();
   // controllingPassenger instanceof Player ? getSpeed()*0.1 (a ridden mount) : 0.02f
  21: ldc_w  float 0.02f
  24: freturn
```

For a normally-knocked survival player nothing is riding them, so
`getFlyingSpeed()` returns the fixed `0.02f`. `Player.getFlyingSpeed()` overrides
only the *creative-flight* case (reads `abilities.getFlyingSpeed()`, the flight
speed field — still not `MOVEMENT_SPEED`, still not touched by Speed potions). A
knocked player's per-tick air acceleration is a fixed constant.

### Legacy (1.12.2)

`EntityLiving.a(float, float, float)` (moveEntityWithHeading) at offsets
1081–1106 is the identical shape:

```
1081: getfield onGround:Z
1085: ifeq 1100                 // NOT on ground -> airborne branch
1088: invokevirtual cy:()F      // getAIMoveSpeed() -> field bC, the movement_speed cache
1092: fload 11  fmul            //  * (0.16277136 / friction^3)   (computed at 1067..1079)
1095: fstore 10
1097: goto 1106
1100: getfield aR:F             // airborne branch: the bare air-movement-factor float field
1104: fstore 10
1106: invokevirtual b:(FFFF)V   // moveFlying(strafe, up, forward, factor=local10)
```

`cy()` returns field `bC` (the cached `getAIMoveSpeed`, refreshed from the
`GENERIC_MOVEMENT_SPEED` attribute) and is reached **only** when `onGround` is
true. The airborne branch reads the plain float field `aR` — the classic
`jumpMovementFactor` (0.02, the same `float 0.02f` constant that recurs
throughout the class), attribute-independent.

**Verdict A1: TRUE on both a legacy and a modern server.** The attribute-backed
speed is gated behind `if (onGround())` on both; the airborne factor is a fixed
constant (`0.02f` / field `aR`). The design's premise that S1 (knock-flight
speed) does NOT scale with the Speed attribute holds across the range. No
escalation.

## A2 — Speed/Slowness are ±20%/level multiplicative on movement_speed, and Bukkit reads the effective value — TRUE

### Modern (1.21.11) — the SPEED effect registration (`MobEffects` clinit)

```
14: getstatic Attributes.MOVEMENT_SPEED
22: ldc2_w  double 0.20000000298023224d          // 0.2 per level
25: getstatic AttributeModifier$Operation.ADD_MULTIPLIED_TOTAL
34: putstatic SPEED
```

`MOVEMENT_SPEED`, `0.2` per level, operation `ADD_MULTIPLIED_TOTAL` — a
`value *= (1 + amount)` transient modifier. (Slowness is the same shape with a
negative amount.) The effect applies `amount × (amplifier + 1)`, so Speed III
(amplifier 2) contributes `0.2 × 3 = +0.6`, i.e. ×1.6.

### Legacy (1.12.2) — the amplifier scaling

`MobEffectList.a(int amplifier, AttributeModifier m)`:

```
1: invokevirtual AttributeModifier.d:()D   // the modifier amount
4: iload_1  iconst_1  iadd  i2d             // (amplifier + 1)
8: dmul                                     // amount * (amplifier + 1)
9: dreturn
```

Confirms the per-level multiplication; the SPEED modifier is registered against
`GENERIC_MOVEMENT_SPEED` with operation 2 (multiply-total), the era-standard
`0.2`. Sprinting rides the same attribute: `SPEED_MODIFIER_SPRINTING` =
`+0.30000001192092896`, also multiply-total (modern clinit; the same
`662A6B8D-…` sprint modifier has existed since 1.8).

### Bukkit reads the EFFECTIVE (modified) value — both eras

```
// 1.12.2 CraftAttributeInstance.getValue -> handle.getValue()  (NMS effective)
// 1.21.11 CraftAttributeInstance.getValue -> handle.getValue()  (NMS effective)
```

Both delegate straight to NMS `AttributeInstance.getValue()`, which returns
base × all modifiers. So `entity.getAttribute(MOVEMENT_SPEED).getValue()` — the
value the platform `Attributes` seam reads — is the modified value, including
sprint and Speed/Slowness.

**Verdict A2: TRUE on both a legacy and a modern server.** Effective
movement-speed attribute values the implementation relies on:

| Stance | Attribute value | / baseline | s (exponent 1) |
|---|---|---|---|
| walk, no potion | 0.1 × 1.0 = **0.10** | /0.10 | 1.0 |
| sprint, no potion | 0.1 × 1.3 = **0.13** | /0.13 | 1.0 |
| sprint, Speed III | 0.1 × 1.3 × 1.6 = **0.208** | /0.13 | **1.6** |
| walk, Speed III | 0.1 × 1.6 = **0.16** | /0.10 | 1.6 |
| sprint, Slowness II | 0.1 × 1.3 × 0.7 = **0.091** | /0.13 | 0.7 |

The design's `eraBaseline(stance)` = 0.13 sprint / 0.10 walk and the
Speed-III-sprint attr 0.208 ⇒ s = 1.6 are exactly the measured values.

## Note carried into implementation

Baseline selection uses the same `sprinting` flag the engine uses for the sprint
bonus (the wire `SprintVerdict`), while the attribute value is read live (it
carries the sprint modifier while the server thinks the player is sprinting). In
the common case they agree; at the ±1-tick sprint/walk boundary they can briefly
disagree, producing a small transient scale — acceptable per the design's
"attacker attribute, stable within a fight" rationale, and documented at the
`PaceScale.factor` call sites.

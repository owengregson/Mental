# The era combat compendium — every motion-writing mechanic on 1.7.10 / 1.8.9

Status: living document, measurement campaign in progress.
Sources: CFR decompiles of the real jars (`legacy-lab/decomp-1.7.10`,
`decomp-1.8.9` — obfuscated names cited so hunks can be re-found), wire
measurements via `legacy-lab/harness/measure.js` against the real servers,
and the prior research line (2026-06-04 improved-knockback,
2026-06-05 era-wire-measurements + addenda 1–4).

Legend per mechanic: **[CODE]** decompile-pinned · **[WIRE]** measured on a
real era server · **[MENTAL ✓/✗/—]** replicated / known gap / out of scope.

---

## 1. The knockback core

### 1.1 `EntityLivingBase.knockBack` — identical bytes, both eras
`decomp-1.7.10/sv.java:613` · `decomp-1.8.9/pr.java:~620` **[CODE] [WIRE] [MENTAL ✓]**

```java
void knockBack(Entity src, float strength, double dx, double dz) {
    if (rand.nextDouble() < knockbackResistanceAttribute) return;  // all-or-nothing roll
    isAirBorne = true;
    f3 = sqrt(dx*dx + dz*dz);
    f4 = 0.4f;                       // ← `strength` param IGNORED (era quirk)
    motX /= 2; motY /= 2; motZ /= 2;
    motX -= dx / f3 * 0.4;
    motY += 0.4;
    motZ -= dz / f3 * 0.4;
    if (motY > 0.4) motY = 0.4;      // the vertical cap (base only)
}
```

- The `strength` parameter is **ignored** — every melee/projectile base knock
  is 0.4 regardless of caller. (Modern restored the parameter; era did not.)
- Knockback resistance is a **probabilistic all-or-nothing roll** (Mental's
  `resistance: legacy`). Era players had NO resistance items: the roll never
  fired in PvP. Armored zombies did — mob-side nuance only.
- The cap applies to the BASE only; `addVelocity` extras stack past it
  (sprint hit vy = 0.3608 + 0.1 = 0.4608) **[WIRE ✓]**.
- Standing victims sit at the −0.0784 grounded equilibrium, so the standing
  base vertical is −0.0784/2 + 0.4 = **0.3608, never 0.4** **[WIRE ✓]**.

### 1.2 `attackEntityFrom` — the gatekeeper
`decomp-1.8.9/pr.java:495` (1.7.10 sv.java equivalent) **[CODE] [MENTAL ✓ via vanilla chain]**

Branch map, in order:
1. invulnerable / remote world / dead → false.
2. fire damage + fire-resistance potion → false.
3. anvil/falling-block + helmet → helmet durability, damage ×0.75.
4. **Mid-invuln** (`hurtResistantTime > max/2`): weaker-or-equal hit →
   `false` (nothing); stronger hit → `damageEntity(amount − lastDamage)`,
   `fullHit = false` → **no knockback, no hurt animation, no sound**.
5. Fresh hit: `lastDamage = amount; hurtResistantTime = 20; hurtTime = 10`,
   full damage.
6. `fullHit` only: `setEntityState(byte 2)` (flinch to all),
   `setBeenAttacked()` (→ `velocityChanged = true` — BEFORE the resistance
   roll, so a resisted knock still ships a no-op velocity packet; mob-only
   relevance in era), then direction = **`source.getEntity()` POSITION**
   (the SHOOTER for projectiles, the player for melee) with the
   coincident-position re-roll being a `while` loop (< 1.0e-4 → random
   ±0.01 each axis), then `knockBack(entity, amount, dx, dz)`.

Consequences worth feeling out:
- **Any accepted 0-damage hit knocks at full strength and arms the full
  20-tick window** (rod bobbers, snowballs, eggs — §3).
- **A stronger hit mid-invuln deals difference damage with ZERO knockback**
  — rod→sword inside 10 ticks moves the victim only by the rod.
- Knock direction is always positional (never attacker yaw); only the
  sprint/enchant EXTRAS are yaw-directed.

### 1.3 `EntityPlayer.attack` — the melee composer
`decomp-1.7.10/yz.java:~960 (r(sa))` · `decomp-1.8.9/wn.java:744 (f(pk))`
**[CODE] [WIRE] [MENTAL ✓ core; ✗ one gap, §1.4]**

Identical in both eras EXCEPT the send block:

```java
damage = attackDamageAttribute;                  // 1.7.10: 1.0 fist + tool; legacy tables
enchBonus = EnchantmentHelper vs creature;       // sharpness etc.
kbLevels  = knockbackEnchantLevel;
if (isSprinting()) ++kbLevels;                   // sprint = +1 knockback level
crit = fallDistance > 0 && !onGround && !onLadder && !inWater
       && !blindness && !riding && target is living;
// NO SPRINT EXCLUSION in EITHER era (1.9 added it)
if (crit && damage > 0) damage *= 1.5;           // crit BEFORE enchant add
damage += enchBonus;
if (fireAspect && !burning) target.setFire(1);   // pre-set, extinguished on miss
save d4,d5,d6 = target.motX/Y/Z;                 // pre-ATTACK fields
if (target.attackEntityFrom(player(this), damage)) {
    if (kbLevels > 0) {
        target.addVelocity(-sin(yaw)·lvl·0.5, 0.1, cos(yaw)·lvl·0.5);  // the extra
        this.motX *= 0.6; this.motZ *= 0.6;      // ← SERVER-side self-slow (§1.5)
        this.setSprinting(false);                // ← the server half of w-tap
    }
    // 1.8.9 ONLY:
    if (target instanceof EntityPlayerMP && target.velocityChanged) {
        send S12PacketEntityVelocity(target);    // in-attack send
        velocityChanged = false;
        target.motX=d4; motY=d5; motZ=d6;        // RESTORE pre-attack fields
    }
    // 1.7.10: no block — the knock rides the end-of-tick tracker
    crit/enchant particles, thorns, durability, stats, exhaustion 0.3
}
```

- Sprint and Knockback-enchant are the SAME mechanism: levels of the same
  extra (0.5 h / flat 0.1 v per level, along attacker yaw). Sprint+KB II =
  3 levels = 1.5 h extra **[MENTAL ✓ engine `extra` × levels]**.
- The 1.8.9 restore reverts to PRE-ATTACK fields — it erases the base knock
  AND the extras (flat delivery, melee never feeds itself) **[MENTAL ✓
  `combos: false` ledger skip]**.
- 1.7.10 has NO restore: fields persist & decay → combos compound, and the
  wire is the join-order-bimodal tracker (addendum 2) **[MENTAL ✓ tracker
  delivery + ledger combos]**.
- Crit adds **no knockback** of its own, both eras.

### 1.4 The within-tick ordering contract (addendum 4)
**[CODE] [WIRE] [MENTAL ✓ since 1.6.0]**

Era servers processed an attack in the attacker's connection slot BEFORE
the victim's same-tick movement packets; combo hits racing the victim's
touchdown read the PRE-landing flight (vy ~0.25 declining, apex dipping).
Mental: tick-stamped packet records + `currentExcludingTick` snapshot
freeze + the `isDamageImmune` staleness allowance + the `(max/2 − 1)`-tick
feedback gate.

### 1.5 The attacker-side 0.6 self-multiply — ALSO a server-field write
`yz.java / wn.java` attack hunks **[CODE] [MENTAL ✗ — gap, fix planned]**

`this.motX *= 0.6; this.motZ *= 0.6` runs on the SERVER's copy of the
attacker's motion on every bonus-knockback hit. The client does its own 0.6
independently (the untouchable client contract); the server write matters
for ONE thing: the attacker's own residual machine. A player knocked
mid-trade who counter-sprint-hits within their flight halves their own
server residual — the NEXT knock they receive compounds off the smaller
fields. On 1.7.10 (persisting fields) this shapes trade exchanges; on
1.8.9 it is mostly invisible (restore wipes fields on every melee hit
anyway, but rod/arrow residuals between melee hits still see it).
**Mental gap**: Mental's ledger never applies the attacker-side ×0.6.
Fix: on an accepted bonus-knockback melee hit, scale the ATTACKER's ledger
horizontals ×0.6 (KnockbackModule, owning thread).

### 1.6 The jump bookkeeping
`decomp-1.7.10/sv.java:905 (bj)` + `nh.java:185` · `decomp-1.8.9/pr.java:912 (bF)` + `lm.java`
**[CODE] [WIRE] [MENTAL ✓ base; ✗ jump-boost, fix planned]**

Handler, both eras: `if (player.onGround && !packet.onGround && dy > 0)
player.jump()` — then the move replication and flag adoption. `jump()`:

```java
motY = 0.42;
if (jumpBoost) motY += 0.1 * (amplifier + 1);    // ← POTION ADDS TO THE STAMP
if (isSprinting()) { motX -= sin(yaw)*0.2; motZ += cos(yaw)*0.2; }
```

**Mental gap**: `VictimMotion.JUMP_IMPULSE` is flat 0.42 — a Jump Boost
victim's era stamp is 0.52+ and their combo verticals ride it (kit-PvP
relevance). Fix: per-player jump-impulse cache (gravityCache pattern).

---

## 2. The victim residual physics (the input-free server machine)

`decomp-1.7.10/sv.java:918 (e(float,float))` · 1.8.9 pr.java equivalent
**[CODE] [MENTAL ✓ air/stone; ✗ slipperiness; — water/lava/ladder/web]**

| State | Horizontal/tick | Vertical/tick | Notes |
| --- | --- | --- | --- |
| Airborne | × 0.91 | (v − 0.08) × 0.98 | ledger ✓ |
| Grounded | × **slipperiness × 0.91** | collision-zero → −0.0784 equilibrium | ledger hardcodes 0.546 (stone 0.6) |
| └ ice / packed ice | × **0.8918** (0.98 × 0.91) | 〃 | **[MENTAL ✗]** ice-arena combos compound much harder in era |
| └ slime block (1.8) | × 0.728 (0.8 × 0.91) | 〃 + bounce | **[MENTAL ✗]** same fix |
| └ soul sand | × 0.546 (slip 0.6) + collision ×0.4 inset | 〃 | block slows via `onEntityCollided` motion ×0.4 — measure |
| In water | × 0.8 (ALL axes) | × 0.8 then − 0.02 | + edge-hop motY=0.3 on horizontal collision near surface · **[MENTAL —]** boundary documented |
| In lava | × 0.5 (ALL axes) | × 0.5 then − 0.02 | **[MENTAL —]** |
| On ladder | clamp ±0.15 h, motY ≥ −0.15 | fallDistance reset | climb-hop motY=0.2 on horizontal collision · **[MENTAL —]** |
| In cobweb | move ×0.25 h, ×0.05 v (pre-move, then web flag clears) | | near-zero received KB — measure for the table · **[MENTAL —]** |

Friction selection is **PRE-move** (the launch tick decays at ground drag
even though the move lifts the victim) — both eras, ledger ✓.

The ground-accel constant `0.16277136/f4³` scales WALKING accel by
slipperiness (client-side for real players) — why ice walking feels slow to
start; irrelevant to the residual but documented for completeness.

---

## 3. Projectiles and the rod

### 3.1 Fishing rod (the era PvP staple)
`decomp-1.7.10/xe.java:159` · 1.8.9 ur.java **[CODE] [WIRE pending] [MENTAL ✓ module — validating]**

- Bobber collision: `entityHit.attackEntityFrom(causeThrownDamage(bobber,
  angler), **0.0f**)` → full 0.4 knockBack (direction from the ANGLER's
  position — `getEntity()` = angler), **arms the full 20-tick hurt window
  at lastDamage = 0**.
- Therefore: rod → sword inside 10 ticks = difference damage, NO knockback
  (§1.2.4). Rod-then-immediate-sword does not double-knock. Sword first,
  rod second inside the window: rod's 0.0 ≤ lastDamage → rod does NOTHING.
- Wire: the bobber knock rides the TRACKER on both eras (no in-attack send
  for non-melee paths) — 1.7.10 join-order bimodality applies to rods too.
- Reel-in pull (retrieve with a hooked entity) is a separate motion write
  toward the angler — Mental's FishingRodVelocityModule territory.

### 3.2 Arrows
`decomp-1.7.10/zc.java:194` **[CODE] [WIRE pending] [MENTAL ✓ arrows always Mental]**

- BASE arrow knock: via `attackEntityFrom(causeArrowDamage(arrow, shooter),
  dmg)` → knockBack from the **SHOOTER's position**.
- PUNCH extra: `addVelocity(arrowMotX · lvl · 0.6 / hSpeed, 0.1,
  arrowMotZ · lvl · 0.6 / hSpeed)` — along **arrow flight**, 0.6 h/level +
  flat 0.1 v, on top of the base.
- Arrow crits (full draw + random) affect damage only.

### 3.3 Snowballs / eggs
**[CODE via thrown-entity pattern] [WIRE pending] [MENTAL ✓ projectile module]**

`attackEntityFrom(causeThrownDamage(projectile, thrower), 0.0)` → the same
0-damage full-knock + full-window behavior as the rod. Direction from the
THROWER's position.

### 3.4 Splash potions, fire ticks, thorns
No knockBack call on the potion-damage path with a null direct entity →
verify by measurement; fire ticks and thorns deal damage without motion
writes. **[WIRE pending]**

---

## 4. Damage-side rules that gate knockback

- Blocking (sword): `EntityPlayer.damageEntity` halves damage
  (`(1+dmg)·0.5`) AFTER `knockBack` already ran — **era blocking takes FULL
  knockback** `wn.java:634` **[CODE]**. Mental's era presets ship
  `shield-blocking-cancels: true`, which deviates whenever a BLOCKING
  damage modifier appears (OCM sword-block) — validation item, likely
  preset default change.
- Hit delay: `maxHurtResistantTime = 20` both eras; effective full-hit
  cadence = 10 ticks. OCM's `attack-frequency` ships playerDelay 18 —
  NOT era (addendum 4).
- PvP rule, friendly fire, invulnerable gamemodes: gate the damage call
  entirely (no knock).
- Armor/protection/potions shape damage numbers only — never motion.
  Legacy damage tables: sharpness +1.25/level, diamond sword 8 (1.7 tables),
  probabilistic armor resistance — see docs/legacy-combat.md.

---

## 5. The client-side contracts (untouchable, documented for completeness)

- The victim's client REPLACES its motion with every velocity packet
  (S12/0x12): era knockback is client-integrated; held input stacks on top
  client-side and never enters the server fields.
- The attacker's client halves its OWN motion ×0.6 per bonus-KB attack
  (the CPS-reduces-received-KB mechanic, 0.6^clicks) — plus the SERVER copy
  (§1.5).
- W-tap/S-tap: client sprint drop + re-engage re-arms the +1 level server
  -side (the server cleared the flag in attack(); the re-engage is the
  client's entity-action).
- Jump-resets: victim jumps reduce effective knockback via the jump-stamp
  replacing fall momentum — all client physics + the §1.6 server stamp.
- Sprint window mechanics, 1.8.9: the attack-time sprint flag read happens
  INSIDE attack(), before the client's same-flush STOP_SPRINTING arrives
  (Mental stamps it at registration — 1.5.0).

---

## 5b. The wire tables (real era jars, 2026-06-06 campaign)

All on flat ground, victim standing 3 blocks +Z, attacker-first join
unless noted. Velocity = the exact packet the victim's client received.

**1.8.9 melee (in-attack send):**

| scenario | velocity (x, vy, z) | damage | settle |
| --- | --- | --- | --- |
| plain standing | (0, 0.3608, 0.4) | 1 | 1.994 |
| KB I sword | (0, 0.4607, **0.9**) | 8 | 4.948 — byte-identical to a sprint hit |
| KB II sword | (0, 0.4607, **1.4**) | 8 | 7.697 — vertical bonus stays FLAT 0.1 |
| sprint + KB II | (0, 0.4607, **1.9**) | — | 10.446 — levels stack: 0.4 + 3×0.5 |
| crit (mid-fall fist) | (0, 0.3608, 0.4) | 1.5 | — crit adds ZERO knockback |
| blocking victim (sword block) | (0, 0.3608, 0.4) | 4.5 = (1+8)×0.5 | 1.994 — FULL knockback while blocking |
| fist → sword at +5t | one packet only | 1, then 7 = 8−1 | difference rule: mid-window stronger hit = damage, NO knock |
| Jump Boost I victim, double | hit2 vy **0.3286** | — | stamp = 0.52 free-falling (8 decays → −0.1426/2+0.4 = 0.3287 predicted) |
| snowball / rod bobber | **no packet, no damage** | 0 | the EntityPlayer zero-damage gate (§3.5) |

**1.7.10 melee (tracker send, attacker-first = decayed wire):**

| scenario | velocities | settle |
| --- | --- | --- |
| chain-plain ×3, stone | (0, 0.2751, 0.2184/0.2275/0.2236) | 2.985 — flights end before the next hit; no compounding at 10-tick cadence on stone |
| chain-plain ×3, **packed ice** | (0, 0.2751, **0.3566/0.4150/0.4328**) | **5.371** |
| victim counter-knock (KB I) on the earlier-joined attacker | (0, 0.4607, −0.9) | full stamp — bimodality reproduced in the reverse slot order |

Ice decomposition: hit1 = 0.4 × (0.98 × 0.91) = 0.35672 exactly — the
1.7.10 decay-on-send friction IS the block-under-feet slipperiness; the
grounded residual survives between hits at ×0.8918/tick (stone: ×0.546
kills it) and compounds. **Ice nearly doubles 1.7.10 knockback.**
1.8.9 melee is ice-immune by construction (in-attack send, restore).

### 3.5 (correction): the zero-damage gate
`decomp-1.7.10/yz.java:565` · `decomp-1.8.9/wn.java:587` **[CODE] [WIRE]**

BOTH eras' `EntityPlayer.attackEntityFrom` ends with
`if (amount == 0) return false` — **pure-vanilla snowballs, eggs and rod
bobbers never knock PLAYER victims** (mobs lack the gate and do get
knocked). The era "rodding" everyone remembers is the CraftBukkit-lineage
behavior every actual server ran (and what OCM's old-fishing-knockback
module restores — the same knockBack(0.4) shape, though OCM directs it
from the HOOK position where era-CB used the angler). Mental's rod and
projectile modules deliberately target the as-played truth; §3.1–3.3's
direction/window mechanics describe the code path that runs whenever the
damage is accepted.

## 6. Mental parity scoreboard (post-validation, 2026-06-06)

Every ✓wire row below was measured on BOTH sides the same day: the real
era jar and Paper+Mental on the named profile, same harness, same staging.

| Mechanic | Era truth | legacy-1.8 | legacy-1.7 |
| --- | --- | --- | --- |
| Standing melee (0.4/0.3608) | wire | ✓wire 1.994 | ✓wire full stamp |
| Sprint melee (0.9/0.4608) | wire | ✓wire 4.948 | ✓wire |
| Combo verticals decline / boundary ordering | wire | ✓wire (0.2862 hit2, settle 7.36 vs era 7.1–7.3) | ✓wire |
| W-tap vs no-w-tap separation | wire | ✓wire 11.671/7.205 vs era 11.65/7.21 | ✓ |
| Sprint-clear + attack-time sprint truth | code+wire | ✓ 1.5.0 | ✓ |
| KB enchant levels / sprint stacking | wire | ✓wire KB II (0.4608, 1.4) settle 7.697 — era-identical | ✓ same engine |
| Crit (no KB change, no sprint exclusion) | wire | ✓wire (0.3608/0.4 at 1.5 dmg) | ✓ same engine |
| Blocking: partial block knocks FULL, full block cancels | wire | ✓wire OCM sword-block: (0.3608, 0.4) at 4.5 dmg — era-identical | ✓ same rule |
| Mid-invuln stronger hit: difference dmg, NO knock | wire | ✓wire (one packet, dmg 1 then 7) | ✓ same window |
| Jump Boost in the jump stamp | wire | ✓wire hit2 vy 0.2909 (the 0.52 stamp @9 decays; era 0.3286 @8 — same machine, one phase) | ✓ same ledger |
| Ice/slime slipperiness in residual decay | wire | n/a (1.8 melee restores) | ✓wire ice hit2 0.4763 vs era 0.4821; hit3 0.5097 vs 0.4920; stone settle 5.57 vs 5.51 |
| Attacker-side ×0.6 server residual | code (decompile-exact, both eras) | ✓ ledger.scaleHorizontal on bonus-KB hits, unit-pinned | ✓ (1.7 trade feel) |
| 0-damage knocks: pure vanilla NONE for players | code+wire (the EntityPlayer gate) | as-played (CraftBukkit lineage): Mental's rod/projectile modules supply it by design | ✓ |
| Arrow base (shooter pos) + Punch (flight dir ×0.6/lvl + 0.1) | code | engine models direction-from-shooter; Punch values pinned | ✓ |
| Water/lava/ladder/web residual states | code | — documented boundary (ledger models air/ground only) | — |
| Mob victims | different system (AI knockback, resistance rolls) | — Mental owns player victims only | — |
| Explosions/TNT | separate system (exposure-scaled) | — out of scope (vanilla owns) | — |

### The residual model, final form (wire-derived)

An era knock's horizontal residual decays as
`0.4 × slip·0.91 (knock tick) × slip·0.91 (liftoff tick) × 0.91^k (airborne)`
— TWO grounded pre-move decays before the air segment (the ice chains pin
it exactly: era hit2 = 0.4×0.8918²×0.91⁷ = 0.4822, measured 0.4821).
Mental's ledger implements this as: launch state captured at SUBMIT (the
live flag flips on the hit tick; the era victim hadn't moved), the
liftoff record applying the launch-tick ground decay to carried
horizontals, per-segment slipperiness from the block under the victim
(GroundFriction: ice 0.98, slime 0.8, blue ice 0.989, default 0.6), and a
grounded seed on a player's first observed state.

---

## Appendix: how each claim is re-verified

- Decompile hunks: `legacy-lab/decomp-*/[file].java:[line]` as cited.
- Wire: `legacy-lab/harness/measure.js <ver> <port> <scenario>` (servers:
  `srv-1.7.10` :25790, `srv-1.8.9` :25890, `srv-mental` :25999).
- Mental gate: `./gradlew build && scripts/integration-matrix.sh`, results
  in `run/**/MentalTester/test-results.txt`.

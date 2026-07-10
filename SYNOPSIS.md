# Minecraft Knockback & Combat: 1.7.10 vs 1.8.9 vs 26.1.2

**Methodology.** All findings below come from the actual game jars downloaded from Mojang's piston-meta CDN (client + server for all three versions). 1.7.10 and 1.8.9 were deobfuscated with the official MCP `joined.srg` mappings (SpecialSource) + MCP stable CSVs, then decompiled with CFR. 26.1.2 ships **unobfuscated** (Mojang ended obfuscation with the 2026 releases — the version manifest no longer even lists mapping downloads), and was decompiled with Vineflower. Decompiled sources are in `src/`, data-driven enchantment JSONs in `data-26.1.2/`. Class/line references point at these decompiled files.

---

## 0. TL;DR of what actually differs

| Mechanic | 1.7.10 | 1.8.9 | 26.1.2 |
|---|---|---|---|
| Base hit KB math (`knockBack`) | `m/2 ± 0.4`, Y always boosted | **identical to 1.7.10** | resistance is multiplicative; **Y boost only if on ground** |
| KB resistance | probabilistic all-or-nothing | same as 1.7.10 | deterministic scaling `power × (1−res)` |
| Sprint bonus | +1 "knockback level" (= +0.5 hor, +0.1 Y, additive) | same | flat +0.5 *power* via `knockback()` (compounding, capped Y), only if attack ≥90% charged |
| Melee KB delivery to player victims | end-of-tick tracker packet; **server keeps the velocity** → hits combo/stack | **immediate packet + server motion reverted** → consistent flat KB | same immediate-send + revert as 1.8.9 |
| Miss penalty ("hit delay") | **none** on swing-at-air | **10-tick lockout** on MISS; entities 3.0–4.5 blocks away are *converted to MISS* | 10-tick lockout kept, **plus** missing resets the attack-charge meter |
| Attack cooldown | none | none | full 1.9-style charge system (`0.2 + t²·0.8` damage, KB/crit/sweep gated on charge) |
| Arrow base KB direction | away from **shooter's position** | same | along the **arrow's velocity vector** |
| Punch enchant | `addVelocity(arrowDir · lvl · 0.6)`, ignores KB res | same | same magnitude, but scaled by `(1−kbRes)`; data-driven |
| Fishing rod bobber | 0-dmg `attackEntityFrom` → full base KB | same | **no hurt call at all — bobber does zero knockback** |
| Rod reel-in | pull `0.1·Δ` + `√dist·0.08` extra Y | same | pull `0.1·Δ` only (no extra Y term); local-player pull predicted client-side |
| Attack reach | client 3.0 (entity), server ≤6.0 center-to-center | same | weapon-defined `AttackRange` (spear up to 4.5+), server checks eye→AABB ≤ range+3 |
| Sword block | −50% damage, **no** KB reduction | same | gone; shields: full block ⇒ **no KB packet to victim** + attacker counter-KB 0.5 |
| Velocity packet | short, `motion×8000`, clamp ±3.9 | same | `LpVec3` float encoding, **no ±3.9 clamp** |
| New KB sources | — | — | mace smash AoE, wind charge, spear stab/kinetic, Lunge self-impulse, sweep KB |

Everything below is the full detail with code.

---

## 1. The shared foundation (all three versions)

The "one hit = one knock" pipeline has the same skeleton everywhere:

1. A damage event reaches the victim (`attackEntityFrom` legacy / `hurtServer` modern).
2. If the hit "counts", the victim gets a **base knockback of strength 0.4** away from the damage source.
3. The *attacker-side* code may then add **bonus knockback** (sprint, Knockback enchant) along the attacker's yaw, and slows the attacker (`motion ×0.6` horizontally) + un-sprints them.
4. For player victims the result is shipped in an entity-velocity packet which **replaces** the victim client's motion vector (explosions are the exception — they *add*).

The interesting part is everything that changed around that skeleton.

---

## 2. 1.7.10 — the baseline

### 2.1 Base knockback — `EntityLivingBase.knockBack` (`EntityLivingBase.java:659`)

```java
public void knockBack(Entity attacker, float damage, double dx, double dz) {
    if (rand.nextDouble() < kbResistance) return;     // all-or-nothing roll
    isAirBorne = true;
    float dist = sqrt(dx*dx + dz*dz);                  // dx = attacker.x - victim.x
    float f = 0.4f;
    motionX /= 2;  motionY /= 2;  motionZ /= 2;        // halve EVERYTHING incl. Y
    motionX -= dx/dist * 0.4;                          // push away from attacker
    motionY += 0.4;                                    // always +0.4 vertical
    motionZ -= dz/dist * 0.4;
    if (motionY > 0.4) motionY = 0.4;                  // Y cap
}
```

Notes:
- The `damage` parameter is **unused** — knockback strength never scales with damage.
- KB resistance (`knockbackResistance` attribute) is a *probability* of ignoring KB entirely, not a scale.
- Direction comes from `attackEntityFrom` (`EntityLivingBase.java:586-594`): `d = attacker.posX − victim.posX`, with a degeneracy fallback `(random−random)·0.01` while `d²+d2² < 1e-4`.
- Vertical: `motionY = min(0.4, motionY/2 + 0.4)` — applied **regardless of being airborne or grounded**.

### 2.2 Hit gating — `attackEntityFrom` (`EntityLivingBase.java:531`)

- `hurtResistantTime` (i-frames) = 20 ticks on a fresh hit, decremented every tick (for `EntityPlayerMP` in `EntityPlayerMP.java:203`, for everything else in `onEntityUpdate`).
- While `hurtResistantTime > 10` (second half of the window): a new hit only registers if `damage > lastDamage`, deals only the *difference*… and crucially sets `bl=false`, so **no knockback and no velocity packet** is produced for these partial hits.
- A 0-damage hit (rod bobber, snowball, egg) is a fully valid hit: it sets `lastDamage = 0`, starts the 20-tick window, and **applies full base knockback**. Side effect: for the next 10 ticks any melee hit with damage `> 0` does its damage *but no knockback* (it's inside the window). This is identical in 1.8.9.

### 2.3 Player melee — `EntityPlayer.attackTargetEntityWithCurrentItem` (`EntityPlayer.java:789`)

```java
float dmg  = ATTACK_DAMAGE attribute;                       // base 1.0 + weapon
int   i    = EnchantmentHelper.getKnockbackModifier(this, target);  // KB enchant level
if (isSprinting()) ++i;                                     // sprint = +1 level
crit = fallDistance > 0 && !onGround && !ladder && !inWater
       && !blindness && ridingEntity == null && target instanceof EntityLivingBase;
if (crit) dmg *= 1.5;
dmg += enchantBonus;                                        // sharpness etc.
if (target.attackEntityFrom(playerDamage(this), dmg)) {     // base KB happens in here
    if (i > 0) {
        target.addVelocity(-sin(yaw)·i·0.5, 0.1, cos(yaw)·i·0.5);  // bonus KB
        this.motionX *= 0.6; this.motionZ *= 0.6;           // attacker slowdown
        this.setSprinting(false);                           // sprint reset
    }
    ...
}
```

- Bonus KB is `addVelocity` — **purely additive** on top of the base knock, **ignores knockback resistance**, and adds `+0.1` Y *after* the base 0.4 cap → a sprint hit can give Y velocity 0.5.
- Total first-hit sprint knock on a standing target ≈ **0.9 horizontal** (0.4 positional + 0.5 yaw-directional), **0.5 vertical**.
- Bonus KB direction uses the attacker's *yaw*, base KB uses relative *positions* — they generally differ slightly.
- Crit (1.5×) affects **damage only**, never knockback, in every version examined.

### 2.4 How the victim actually receives it — the **defining 1.7.10 trait**

There is *no velocity-packet code* in the attack method. Instead `setBeenAttacked()` sets `velocityChanged = rand.nextDouble() >= kbResistance` (`EntityLivingBase.java:1343` — note: a *separate* random roll from the one inside `knockBack`), and the **entity tracker** flushes it at end of tick (`EntityTrackerEntry.java:224`):

```java
if (trackedEntity.velocityChanged) {
    func_151261_b(new S12PacketEntityVelocity(trackedEntity));  // to trackers AND the victim itself
    trackedEntity.velocityChanged = false;
}
```

The packet clamps each axis to **±3.9** and encodes as `(int)(motion·8000)` shorts (`S12PacketEntityVelocity.java:25`). The client handler **replaces** motion: `entity.setVelocity(packet/8000)` (`NetHandlerPlayClient.java:384`).

**The server's copy of the victim's motion is left mutated.** Movement packets never write `EntityPlayerMP.motionX/Y/Z` (verified: `NetHandlerPlayServer` only *reads* them for speed checks), so the knockback vector lingers in the server-side fields, decaying only via friction when `onUpdateEntity → onLivingUpdate → moveEntityWithHeading` runs. Consequence:

> **1.7.10 KB stacking ("combos").** Hit a player twice in quick succession and the second `knockBack` computes `residual/2 + 0.4` where `residual` is the still-large previous knockback. Successive hits compound, launching targets progressively further. This is the mechanical core of 1.7-style combo PvP.

### 2.5 Client-side hit, reach, and the absence of a miss penalty

`Minecraft.clickMouse()` (`client Minecraft.java:1076`) — called once per attack-key *press*:

```java
if (leftClickCounter > 0) return;
thePlayer.swingItem();
if (objectMouseOver == null) { log; if (survival) leftClickCounter = 10; return; }
switch (objectMouseOver.typeOfHit) {
    case ENTITY: playerController.attackEntity(...); break;
    case BLOCK:  if (material == air) { if (survival) leftClickCounter = 10; }
                 else clickBlock(...);
}
// NO case for MISS. Swinging at air does NOTHING but swing.
```

`EntityLivingBase.rayTrace` (`client EntityLivingBase.java:1394-1398`) calls `rayTraceBlocks(..., returnLastUncollidableBlock=true)`, which returns a **MISS-type** `MovingObjectPosition` at the ray end when nothing is hit (`MovingObjectPosition.java:23-24`: the `false` constructor flag sets `typeOfHit = MISS`). So clicking open air yields `MISS`, which falls through the switch unhandled → **no `leftClickCounter`, no penalty, swing-spam freely**. (The `leftClickCounter = 10` branches exist but are only reachable via a null hit-result or the pathological "BLOCK whose material is air" case.)

Reach (`EntityRenderer.getMouseOver`, `EntityRenderer.java:271`):
- Block ray = `getBlockReachDistance()` = **4.5** survival / 5.0 creative (`PlayerControllerMP.java:189`).
- Entity pick: in survival the *entity ray itself* is truncated — `if (d2 > 3.0) d2 = 3.0; d = d2;` → entities are only targetable to **3.0 blocks** (creative: 6.0 via `extendedReach()`). Entity AABBs are inflated by `getCollisionBorderSize()` = **0.1** for the intercept test.
- Server validation (`NetHandlerPlayServer.processUseEntity`, `:555`): `distanceSq(player→target) < 36.0` (6 blocks center-to-center; 9.0 if not seen). The server never enforces 3.0 — the 3-block limit is purely client.

Attack prediction: `PlayerControllerMP.attackEntity` sends `C02PacketUseEntity(ATTACK)` *then* runs `attackTargetEntityWithCurrentItem` locally. `EntityOtherPlayerMP.attackEntityFrom` returns `true` (`EntityOtherPlayerMP.java:44`), so against *players* the attacker's client locally applies the bonus-KB `addVelocity` to its copy of the victim, slows itself ×0.6, and un-sprints — instant feedback before the server packet arrives. Against *mobs*, `attackEntityFrom` returns `false` on the client (remote world check) → no prediction.

### 2.6 Sprint rules (client authority, `EntityPlayerSP.onLivingUpdate`, `:92-171`)

- Start by double-tap: needs `onGround`, `moveForward ≥ 0.8`, previous tick *not* moving forward, food > 6, not using item, no blindness; 7-tick window (`sprintToggleTimer = 7`).
- Start by sprint key: same conditions **minus onGround**.
- Stop when `moveForward < 0.8` **or** horizontal collision **or** food ≤ 6.
- Using an item multiplies `moveForward ×0.2` first, so block-hitting drops you below 0.8 and kills sprint the same tick.
- Sprint state reaches the server via `C0BPacketEntityAction` START/STOP_SPRINTING; the *server's* `isSprinting()` flag at the moment the attack packet is processed decides the +1 KB — this is what W-tapping manipulates. The era client's held-key re-sprint on the very next tick sent a genuine START (that is how era key-holders re-armed the bonus), but a *modern* client at spam cadence never drops its local sprint flag and so sends no new START — Mental therefore treats the sprint extra as a **per-engagement resource**, consumed by the bonus hit and re-armed only by a client re-gesture (w-tap / s-tap / GUI STOP→START, or the block-hit re-arm); measured era separation 7.2 vs 11.4 blocks (no-w-tap vs w-tap doubles).

### 2.7 Projectiles & rod

**Arrow** (`EntityArrow.java:195-228`):
- Damage = `ceil(|velocity| · damage)` (`damage` field = 2.0 base, +0.5·power+0.5 from Power), crit adds `rand.nextInt(dmg/2+2)`.
- Base KB: via `attackEntityFrom(causeArrowDamage(arrow, shooter))` — `damageSource.getEntity()` is the **shooter**, so base 0.4 KB pushes away from *where the shooter stands*, not along arrow flight.
- Punch (`knockbackStrength`, set from the bow's Punch level at `ItemBow.java:49-51`):
  ```java
  if (knockbackStrength > 0 && horizSpeed > 0)
      target.addVelocity(motionX·kb·0.6/horizSpeed, 0.1, motionZ·kb·0.6/horizSpeed);
  ```
  i.e. **0.6 per level along the arrow's horizontal flight direction** + 0.1 Y, additive, ignores KB resistance. Delivered to player victims via the tracker (server motion persists → arrows *do* contribute to 1.7-style stacking, and likewise in 1.8.9).

**Fishing rod** (`EntityFishHook.java`):
- Bobber launch: speed 0.4 × 1.5 = **0.6**, gaussian spread 0.0075 (`:75-79, :86-101`).
- Bobber→entity collision (`:183`): `entityHit.attackEntityFrom(causeThrownDamage(hook, angler), 0.0f)` — **a 0-damage hit ⇒ full base 0.4/0.4 knockback away from the angler's position**, plus it opens the 20-tick hurt window (see §2.2 for the melee-KB-suppression interplay). Bobber AABB inflated 0.3 for collision.
- Reel-in (`handleHookRetraction`, `:326-340`): pulls the *caught* entity **toward** the angler: `motion += Δ·0.1` per axis **plus** `motionY += √(dist)·0.08`.

**Snowball/Egg** (`EntitySnowball.java:28`): `attackEntityFrom(..., 0)` (3 vs blaze) — free knockback, same as rod.

**Mob melee** (`EntityMob.attackEntityAsMob`, `:101`): identical shape to the player method (KB enchant level, `addVelocity(±sin/cos·n·0.5, 0.1, …)`, ×0.6 self-slow), but **no sprint bonus and no velocity-revert special-casing in any version** — mob knockback always goes through the tracker.

**Sword blocking**: `EntityPlayer.damageEntity` (`:704`): `f = (1+f)·0.5` (≈50% damage reduction). Applied *after* the knockback decision — **blocking never reduces knockback** in 1.7.10/1.8.9.

---

## 3. 1.8.9 — same math, two huge behavioral changes

Verbatim-identical to 1.7.10 (verified by direct decompile diff): `knockBack` math, hurt-window logic, sprint +1 / enchant ×0.5 bonuses, attacker slowdown & sprint reset, arrow damage/Punch code, bobber 0-damage hit and reel-in math, snowball, mob melee, server 36.0/9.0 reach check, ±3.9 velocity clamp, ×8000 encoding, tracker velocity flush, sword-block 50%, collision border 0.1, block reach 4.5/5.0, client 3.0 entity-pick limit, `EntityOtherPlayerMP.attackEntityFrom → true` prediction.

What changed:

### 3.1 ★ Immediate velocity send + server-side motion revert (the "consistent KB" change)

`EntityPlayer.attackTargetEntityWithCurrentItem` (`1.8.9 EntityPlayer.java:842-859`):

```java
double ox = target.motionX, oy = target.motionY, oz = target.motionZ;   // snapshot BEFORE the hit
boolean hit = target.attackEntityFrom(playerDamage(this), dmg);
if (hit) {
    if (i > 0) { target.addVelocity(...); motionX *= 0.6; ...; setSprinting(false); }
    if (target instanceof EntityPlayerMP && target.velocityChanged) {
        ((EntityPlayerMP)target).playerNetServerHandler
            .sendPacket(new S12PacketEntityVelocity(target));   // ship it NOW
        target.velocityChanged = false;                          // tracker won't re-send
        target.motionX = ox; target.motionY = oy; target.motionZ = oz;  // REVERT server copy
    }
    ...
}
```

Consequences (all PvP-only — the branch requires an `EntityPlayerMP` victim):

1. The knockback packet leaves in the **same tick** as the attack, instead of at the end-of-tick tracker flush.
2. The server's motion fields are restored, so the next melee hit computes `motion/2` from **pre-hit** state ⇒ every melee hit produces (nearly) the same knockback. **This single block is the death of 1.7-style melee combos.**
3. It only intercepts *melee* knockback. Arrows, rod bobbers, snowballs, eggs, and mob hits still go through the tracker and still leave residual server-side motion. So in 1.8.9 a **rod-then-sword** combo still stacks: the bobber's residual motion gets halved into the sword hit's packet (`residual/2 + 0.4 + bonus`), and is then *restored* for any further hits.
4. Mob victims are unaffected (mobs are server-simulated; their motion must persist).

### 3.2 ★ The miss penalty ("hit delay when missing")

`Minecraft.clickMouse()` (`1.8.9 client Minecraft.java:1180-1208`) restructured the switch:

```java
switch (objectMouseOver.typeOfHit) {
    case ENTITY: attackEntity(...); break;
    case BLOCK:  if (!air) { clickBlock(...); break; }    // air falls through ↓
    default:     if (playerController.isNotCreative())
                     this.leftClickCounter = 10;          // ← MISS lands here
}
```

While `leftClickCounter > 0` (decremented once per tick, `:1428`):
- `clickMouse()` returns immediately — clicks are **swallowed entirely** (no swing, no attack packet);
- `sendClickBlockToController` refuses to start/continue block breaking (`:1166`).

So a whiffed swing in survival costs a hard **10-tick (0.5 s) attack lockout**. In 1.7.10 the identical MISS case did nothing.

And it's worse than just air-swings, because of:

### 3.3 ★ Out-of-range entities become MISS

`EntityRenderer.getMouseOver` (`1.8.9 EntityRenderer.java:312-382`) no longer truncates the entity ray to 3.0. It picks entities along the full 4.5-block ray, then post-filters:

```java
if (pointedEntity != null && survivalFlag && eyePos.distanceTo(hitVec) > 3.0) {
    pointedEntity = null;
    objectMouseOver = new MovingObjectPosition(MISS, hitVec, null, pos);  // ← forged MISS
}
```

In 1.7.10, aiming at a player 3.0–4.5 blocks away leaves `objectMouseOver` as whatever the block ray found (a block, or an unhandled MISS) — clicking costs nothing. In 1.8.9 the same click is converted into a **MISS ⇒ 10-tick lockout**. This is exactly the "hit delay when you barely miss" that 1.8 PvPers feel: misjudge range by half a block and you're locked out for half a second while eating counter-hits.

### 3.4 Minor 1.8.9 deltas

- Double-tap sprint start additionally requires **not sneaking** (`EntityPlayerSP.java:533`); everything else in the sprint state machine is unchanged.
- `sendClickBlockToController` also early-outs while `isUsingItem()` (`:1166`).
- Packets are queued to the main thread (`PacketThreadUtil.checkThreadAndEnqueue`) instead of being handled on the network tick — sub-tick timing differences only.
- `INTERACT_AT` added to `C02PacketUseEntity`; attack path unchanged.
- `EntityLivingBase` dropped the vestigial `attackTime` field (1.7.10 `:73`) — it was never used for player attacks, no PvP impact.
- `prevHealth` bookkeeping removed from `attackEntityFrom` — cosmetic.

---

## 4. 26.1.2 — a redesigned system

The modern pipeline: `ServerboundAttackPacket → ServerGamePacketListenerImpl.handleAttack → Player.attack → Entity.hurtServer → LivingEntity.knockback` + `Player.causeExtraKnockback`. Damage events and knockback are heavily data-driven (damage-type tags, data-driven enchantments, item data components).

### 4.1 Base knockback — `LivingEntity.knockback` (`LivingEntity.java:1623`)

```java
public void knockback(double power, double xd, double zd) {
    power *= 1.0 - getAttributeValue(KNOCKBACK_RESISTANCE);   // deterministic scaling
    if (power <= 0) return;
    needsSync = true;
    Vec3 m = getDeltaMovement();
    while (xd*xd + zd*zd < 1e-5) { xd = (rnd-rnd)*0.01; zd = (rnd-rnd)*0.01; }
    Vec3 v = new Vec3(xd, 0, zd).normalize().scale(power);
    setDeltaMovement(
        m.x/2 - v.x,
        onGround() ? min(0.4, m.y/2 + power) : m.y,           // ← Y only when grounded
        m.z/2 - v.z);
}
```

vs legacy `knockBack`:

| | legacy | 26.1.2 |
|---|---|---|
| KB resistance | random full-cancel (`rand < res ⇒ nothing`) | multiplies power: `power·(1−res)`; partial resistance always partially works (netherite armor: −10%/piece) |
| strength | hardcoded 0.4 (damage param ignored) | `power` parameter is real — callers pass 0.4 base, 0.5 sprint, 0.5·lvl enchant, 0.5 shield-counter… |
| vertical | always `min(0.4, y/2 + 0.4)` | **`min(0.4, y/2 + power)` only if `onGround()`; airborne targets keep their Y untouched** (no Y-halving either) |
| horizontal | `m/2 − dir·0.4` | identical shape, scaled by `power` |
| degenerate dir | handled in caller (<1e-4) | handled inside (<1e-5) |

The on-ground-only vertical rule is one of the biggest feel changes: juggling airborne targets upward repeatedly is impossible; conversely airborne targets keep their fall velocity through a hit.

### 4.2 Hit gating — `LivingEntity.hurtServer` (`LivingEntity.java:1169`)

Same 20/10 skeleton: `invulnerableTime = 20`, while `> 10` only `damage > lastHurt` registers the difference and `tookFullDamage=false` ⇒ no knockback. New:

- `DamageTypeTags.BYPASSES_COOLDOWN` can exempt damage types from the window (vanilla ships the tag empty).
- `DamageTypeTags.NO_KNOCKBACK` (`data/.../no_knockback.json`) suppresses the base knock entirely — includes `explosion`, `player_explosion`, `ender_pearl`, **`spear`**, all environmental damage. (Explosions/wind charges do their own knockback; spear KB is applied explicitly — see below.)
- Direction (`:1234-1246`):
  ```java
  if (source.getDirectEntity() instanceof Projectile p) {
      pair = p.calculateHorizontalHurtKnockbackDirection(this, source);  // = projectile velocity (x,z)
      xd = -pair.left;  zd = -pair.right;
  } else if (source.getSourcePosition() != null) {
      xd = source.x − this.x;  zd = source.z − this.z;     // legacy-style positional
  }
  this.knockback(0.4, xd, zd);
  ```
  **Projectile base KB now pushes along the projectile's flight direction** (`Projectile.java:385` returns `deltaMovement.x/z`), not away from the shooter's position as in 1.7/1.8.
- Shields (`BLOCKS_ATTACKS` data component, `applyItemBlocking` `:1292`): blocked damage is subtracted before everything. `markHurt()` (which arms the velocity packet) requires `!blocked || damage > 0` — a **full shield block produces no velocity packet to a player victim** (the server-side `knockback(0.4)` still runs but is reverted/never delivered), and the *attacker* is counter-knocked: `blockedByItem → attacker.knockback(0.5, …)` (`:1367-1369`, melee only — projectiles excluded at `:1319`).

### 4.3 Player melee — `Player.attack` (`Player.java:945`)

```java
float dmg   = ATTACK_DAMAGE;                       // base 1.0; sword adds 3+material; spear etc.
float scale = getAttackStrengthScale(0.5f);        // (ticker+0.5) / (20/ATTACK_SPEED), clamped 0..1
float magic = scale · (enchantedDamage − dmg);     // enchant damage scales LINEARLY with charge
dmg  *= 0.2f + scale²·0.8f;                        // base damage: 20%..100% by charge²
boolean full      = scale > 0.9f;
boolean kbAttack  = isSprinting() && full;         // sprint bonus REQUIRES ≥90% charge
dmg += item.getAttackDamageBonus(...);             // mace smash etc.
boolean crit = full && fallDistance>0 && !onGround && !onClimbable && !inWater
               && !mobilityRestricted && !passenger && target is LivingEntity
               && !isSprinting();                  // ← crits exclude sprinting (1.9 rule)
if (crit) dmg *= 1.5f;
boolean sweep = isSweepAttack(full, crit, kbAttack);   // full && !crit && !kb && onGround
                                                       // && horizSpeed < 2.5·getSpeed() && SWORDS tag
Vec3 old = target.getDeltaMovement();
if (target.hurtOrSimulate(src, dmg + magic)) {     // base 0.4 KB inside hurtServer
    causeExtraKnockback(target,
        getKnockback(target, src) + (kbAttack ? 0.5f : 0.0f), old);
    if (sweep) doSweepAttack(...);
    ...
}
this.onAttack();                                   // resets attackStrengthTicker to 0
```

`getKnockback` (`LivingEntity.java:1522`): `(ATTACK_KNOCKBACK attribute + EnchantmentHelper.modifyKnockback(...)) / 2` — players have `ATTACK_KNOCKBACK = 0`, and `knockback.json` adds exactly `level`, so a Knockback-N sword contributes **0.5·N power**, same magnitude as legacy but routed through `knockback()` (⇒ scaled by resistance, Y-capped, compounding `m/2` semantics instead of pure addition).

`Player.causeExtraKnockback` (`Player.java:1114`):

```java
if (amount > 0) {
    livingTarget.knockback(amount, sin(yaw), −cos(yaw));   // bonus knock, same direction math as legacy
    setDeltaMovement(getDeltaMovement().multiply(0.6, 1, 0.6));  // attacker slowdown
    setSprinting(false);                                   // sprint reset survives to 2026
}
if (target instanceof ServerPlayer && target.hurtMarked) {
    serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(target));  // immediate send
    target.hurtMarked = false;
    target.setDeltaMovement(oldMovement);                  // ← the 1.8.9 revert lives on
}
```

So 26.1.2 keeps the 1.8.9 immediate-send-and-revert exactly (and extends it: the revert happens even when there was no bonus KB, because the snapshot/revert is outside the `amount > 0` branch).

Worked example — full-charge sprint hit on a standing player (kbRes 0):
- legacy 1.8.9: horizontal ≈ `0.4 + 0.5 = 0.9`, vertical `0.5`
- 26.1.2: `hurtServer` knock: h=0.4, Y=min(0.4, 0.4)=0.4; then bonus knock 0.5: h = `0.4/2 + 0.5 = 0.7`, Y = `min(0.4, 0.2+0.5) = 0.4` ⇒ **horizontal ≈ 0.7, vertical 0.4** — modern sprint hits are measurably weaker and flatter, and the two knocks compound (`m/2`) instead of adding.

### 4.4 The attack-charge (cooldown) system

- `ATTACK_SPEED` attribute (default 4.0; swords −2.4 ⇒ 1.6; spear `1/attackDuration − 4`); full charge takes `20/ATTACK_SPEED` ticks (sword: 12.5).
- `attackStrengthTicker` increments each tick; **reset to 0 on every attack** (`onAttack`), on item swap, **and on a missed swing** (client `Minecraft.startAttack` calls `resetAttackStrengthTicker()` on MISS — `Minecraft.java:1717`).
- Gates: damage `0.2+t²·0.8`, enchant damage `×t`, sprint-KB and crit and sweep all require `t > 0.9`.
- New 26.x: `MINIMUM_ATTACK_CHARGE` data component — the spear (1.0) simply *cannot attack* until fully charged; enforced client-side (`cannotAttackWithItem(item, 0)`) **and** server-side with 5-tick tolerance (`handleAttack` `:1814`).

### 4.5 Sweep attack (`Player.doSweepAttack`, `Player.java:1146`)

Victims: `LivingEntity` within `primaryTarget.AABB.inflate(1.0, 0.25, 1.0)` and `< 3` blocks from attacker, not allied. Damage `1 + SWEEPING_DAMAGE_RATIO·attackDamage` (ratio = `lvl/(lvl+1)` from `sweeping_edge.json`), then each sweep victim gets `knockback(0.4, sin(yaw), −cos(yaw))` **in addition to** the positional 0.4 from their own `hurtServer` — two compounding 0.4 knocks.

### 4.6 Knockback delivery & sync model

- `Entity.push(...)` sets `needsSync`; `knockback()` sets `needsSync`; `markHurt()` sets `hurtMarked`.
- `ServerEntity.sendChanges` (`ServerEntity.java:229`): `hurtMarked ⇒ sendToTrackingPlayersAndSelf(SetEntityMotion)` at end of tick — the 1.7.10-style path, still used for **arrows, mobs, maces (extra direct send), wind charges** hitting players. `needsSync` additionally triggers motion-sync packets to *trackers only* (visual).
- Melee on players: intercepted in `causeExtraKnockback` (immediate + revert) — 1.8.9-style.
- So 26.1.2 = same hybrid as 1.8.9: *melee KB is stateless, projectile/mob KB leaves server-side residual motion*.
- Packet: `ClientboundSetEntityMotionPacket` now encodes motion with `LpVec3` (15-bit mantissa + shared scale, max ≈1.7e10) — **the legacy ±3.9 clamp is gone**. Client applies via `lerpMotion = setDeltaMovement` (replace), explosions still *add* (`packet.playerKnockback().ifPresent(player::addDeltaMovement)`).

### 4.7 Client-side: miss penalty, reach, prediction, sprint

**Miss penalty kept and sharpened** (`Minecraft.startAttack`, `client Minecraft.java:1663`):
```java
case MISS:
    if (gameMode.hasMissTime()) missTime = 10;     // same 10-tick lockout (survival only)
    player.resetAttackStrengthTicker();            // ← AND your charge meter restarts
```
`continueAttack` clears `missTime` when the button is released (`:1640`) — like legacy, the lockout mainly punishes held/spammed clicks; but unlike 1.8.9 the *cooldown reset* punishes every whiff regardless.

**Reach**: per-item `AttackRange` component `(minReach, maxReach, minCreative, maxCreative, hitboxMargin, mobFactor)`; default `(0, ENTITY_INTERACTION_RANGE=3.0, 0, 3.0, 0, 1)` (`AttackRange.java:55`). Spears: `(2.0, 4.5, 2.0, 6.5, 0.125, 0.5)` — **min reach 2.0**: a spear cannot hit targets closer than 2 blocks. Server validates `isWithinAttackRange(weapon, targetAABB, 3.0)` — eye-position→AABB distance against `maxReach + 3.0` buffer (`handleAttack`, `ServerGamePacketListenerImpl.java:1802`) — much tighter geometry than legacy's center-to-center 6.0, and there's now a dedicated `ServerboundAttackPacket` (attack split out of the interact packet).

**Prediction**: `MultiPlayerGameMode.attack` sends the packet then runs `player.attack` locally; `RemotePlayer.hurtClient → true` (`RemotePlayer.java:32`) reproduces the legacy `EntityOtherPlayerMP` behavior (predict KB on player victims, slowdown, sprint-reset; mobs return false).

**Sprint** (`LocalPlayer`, `:768-829, :1135-1148`):
- `hasForwardImpulse()` = `moveVector.y > 1e-5` — **any** forward input, not the legacy ≥0.8.
- Start: forward impulse + food (>6) + not slowed-by-item + not sneak-slowed; double-tap window is the `sprintWindow` option (configurable, was hardcoded 7); **no onGround requirement, no blindness check anymore**.
- Stop (`shouldStopRunSprinting` `:922`): food/forward-impulse loss, or horizontal collision *unless minor* (<8° off course). **Using an item no longer stops an ongoing sprint** (it only blocks starting one), and items carry a `USE_EFFECTS` component that can even allow sprint-starts while in use.

### 4.8 Projectiles, rod, and the new knockback sources

**Arrows** (`AbstractArrow.onHitEntity` `:425` + `doKnockback` `:521`):
- Base 0.4 KB along arrow velocity (see §4.2), via `knockback()` ⇒ resistance-scaled, grounded-Y rule.
- Punch — now the generic `minecraft:knockback` enchant effect gated on `direct_attacker ∈ #arrows` (`punch.json`):
  ```java
  kb = EnchantmentHelper.modifyKnockback(weapon, …, 0.0f);          // = punch level
  res = max(0, 1 − KNOCKBACK_RESISTANCE);
  push(normalize(arrowVel.xz) · kb · 0.6 · res, 0.1, …);            // additive like legacy
  ```
  Same `0.6/level + 0.1Y` magnitude as 1.7/1.8 but **multiplied by (1−kbRes)**. Tridents inherit all of this (`ThrownTrident extends AbstractArrow`).
- Arrow crit bonus unchanged: `rand.nextInt(dmg/2 + 2)`.

**Fishing rod** (`FishingHook.java`):
- `onHitEntity` (`:276`) only does `setHookedEntity(...)` — **no hurt call ⇒ zero knockback, zero i-frames from bobber contact**. Legacy rod-KB is gone entirely.
- Reel-in `pullEntity` (`:510`): `motion += (owner − hook) · 0.1` — the legacy `√dist·0.08` vertical bonus is **removed**. When the hooked entity is a *player*, entity-event 31 makes the **victim's own client** apply the pull locally (`:503`) instead of relying on a velocity packet.

**Snowball/Egg**: unchanged concept — 0-damage hurt (3 vs blaze) ⇒ base 0.4 KB (`Snowball.java:55`, `ThrownEgg.java:59`), now along projectile velocity.

**Mob melee** (`Mob.doHurtTarget`, `Mob.java:1384`): `causeExtraKnockback(target, getKnockback(...))` where mobs can carry a real `ATTACK_KNOCKBACK` attribute (0–5; ravager etc.). Mobs use the `LivingEntity.causeExtraKnockback` overload (`:2694`) which has **no send/revert block** — mob KB on players persists server-side, as in every version.

**Mace** (`MaceItem.java`): smash attack (fallDistance > 1.5, not gliding) adds fall-scaled damage (`4·d` to 12 @3, then `2/block` to 22 @8, then `1/block`) and an **AoE knockback**: every LivingEntity within 3.5 blocks gets
`push(dir·(3.5 − dist)·0.7·(fallDist>5 ? 2 : 1)·(1−kbRes), 0.7, …)` — fixed **0.7 vertical**, with a *direct* immediate velocity packet to each affected ServerPlayer (no revert). Attacker's own motion packet is also force-sent (smash arrest).

**Wind charge** (`WindCharge.java`, `ServerExplosion.hurtEntities` `:171`): radius-1.2 "TRIGGER" explosion, entity damage off, `knockbackMultiplier = 1.22`. Generic explosion KB formula (all modern explosions): `dir(eye−center) · (1 − dist/2r) · seenFraction · multiplier · (1 − EXPLOSION_KNOCKBACK_RESISTANCE)` — additive `push`, shipped to players inside the explode packet and **added** client-side. Note `EXPLOSION_KNOCKBACK_RESISTANCE` is its own attribute in modern.

**Spear** (new 26.x weapon; `Item.Properties.spear(...)` `Item.java:498`, `KineticWeapon.java`, `Player.stabAttack` `Player.java:1198`):
- `PIERCING_WEAPON`: attack swings stab along a ray (separate `STAB` player-action packet) and can hit multiple targets.
- `KineticWeapon`: while charging forward (riding/sprinting), collision auto-triggers `stabAttack` with damage `base + floor(relativeSpeed · damageMultiplier)`; per-material conditions decide whether it deals damage / knockback / dismounts (e.g. iron spear: dismount window 2.5s @ speed ≥11, KB ≥5.1, damage ≥11.25 relative speed — values straight from `Items.java:1865`).
- Stab knockback: `causeExtraKnockback(target, 0.4 + getKnockback(...))` — and since damage type `minecraft:spear` is in `#no_knockback`, the generic positional 0.4 from `hurtServer` is suppressed; spear KB is **pure yaw-directional**.
- `Lunge` enchant (`lunge.json`): on piercing attack, gives the **attacker** a forward impulse of `0.458·level` (Y zeroed) — attacker-side mobility, costs 4 exhaustion/level, blocked when riding/gliding/swimming/food<7.

---

## 5. Cross-version mechanic ledger (every difference, with math)

### 5.1 Base knock applied by a plain hit (victim standing on ground, kbRes 0)
- **1.7.10 / 1.8.9**: `v_h = |old_h/2 − 0.4·dir_pos|`, `v_y = min(0.4, old_y/2 + 0.4)`. `dir_pos` = horizontal unit vector victim→attacker(position).
- **26.1.2**: identical numbers for a grounded victim; `dir` = positions for melee, **projectile velocity** for projectiles; airborne victim ⇒ `v_y = old_y` (untouched).

### 5.2 Sprint bonus
- legacy: always (+1 level ⇒ `+0.5` horizontal add, `+0.1` Y add, after Y cap ⇒ up to 0.5 Y).
- 26.1.2: only when charge >0.9; `knockback(0.5)` ⇒ halves existing motion first, Y folded into the 0.4 cap. Sprint flag is reset server-side after the bonus in all three; client predicts the reset in all three.

### 5.3 Knockback enchant (melee)
- legacy: `addVelocity(yaw_dir · lvl · 0.5, 0.1, …)` — additive, resistance-ignoring.
- 26.1.2: `knockback(lvl · 0.5)` — compounding, resistance-scaled, no extra Y for airborne. Data: `knockback.json` linear(base 1, +1/level) ÷ 2 in `getKnockback`.

### 5.4 Punch (arrows)
- legacy: `addVelocity(arrowFlight_h · lvl · 0.6 / |v_h|, 0.1, …)`.
- 26.1.2: `push(normalize(arrowFlight_h) · lvl · 0.6 · (1−kbRes), 0.1, …)`.

### 5.5 i-frames interplay
All three: 20-tick window; hits in ticks 0–10 of the window need `damage > lastDamage`, deal only the difference, and **never knock back**. A 0-damage hit (legacy rod/snowball/egg; modern snowball/egg) arms the window with `lastDamage = 0` ⇒ follow-up melee within 10 ticks does damage but **no KB**. Modern adds `BYPASSES_COOLDOWN`/`NO_KNOCKBACK` tag hooks.

### 5.6 Delivery semantics (PvP victims)
- 1.7.10: tracker end-of-tick, server motion persists ⇒ melee+melee stacks (`r/2 + 0.4 + 0.5…`), rod+melee stacks, arrow+melee stacks.
- 1.8.9: melee = immediate send + revert ⇒ melee+melee flat; rod/arrow residuals still feed the *next melee packet* once (then get restored).
- 26.1.2: same hybrid as 1.8.9 (`hurtMarked` consumed in `causeExtraKnockback`; `ServerEntity` handles the rest end-of-tick to trackers **and self**).
- Packet maths: legacy short `motion×8000` clamp ±3.9; modern `LpVec3`, effectively unclamped. All three **replace** client motion for velocity packets and **add** for explosion knockback.

### 5.7 Miss penalty / attack gating (client)
- 1.7.10: none for MISS. Entity targeting hard-capped at 3.0 blocks (ray truncated), so a "near-miss" click usually targets the world (block or nothing) at zero cost.
- 1.8.9: MISS ⇒ `leftClickCounter = 10` (clicks swallowed, block-breaking blocked, survival only); entity hits beyond 3.0 (up to the 4.5 ray) are *rewritten into MISS* ⇒ penalized.
- 26.1.2: MISS ⇒ `missTime = 10` **and** attack-charge reset; per-item range pre-check before even sending the attack; spear additionally can't swing until fully charged.

### 5.8 Reach validation (server)
- legacy: `distSq(center,center) < 36` (seen) / `9` (unseen). No charge validation (no charge exists).
- 26.1.2: `dist(eye, targetAABB) < itemMaxReach + 3`, plus `MINIMUM_ATTACK_CHARGE` check with 5-tick tolerance, plus dedicated attack packet.

### 5.9 Blocking
- legacy both: sword block = damage `(1+f)·0.5`, KB unchanged.
- 26.1.2: shields (`BLOCKS_ATTACKS`); full block ⇒ no KB delivered to a player victim, melee attacker takes `knockback(0.5)` counter-KB; pierce-capable arrows (`pierceLevel > 0`) bypass shields entirely.

### 5.10 Rod
- legacy both: bobber contact = 0-dmg hit ⇒ full 0.4/0.4 KB from angler position + arms i-frame window; reel-in pulls hooked entity `0.1·Δ + √d·0.08·ŷ`.
- 26.1.2: bobber contact = hook only, **no KB**; reel-in `0.1·Δ` flat, victim-side client prediction via entity event 31.

### 5.11 Things that exist only in 26.1.2
Attack-charge system; sweeps (extra 0.4 yaw-knock per AoE victim); mace AoE `((3.5−d)·0.7·{1|2}·(1−res), Y 0.7)`; wind-charge explosion KB `×1.22` with its own resistance attribute; spear stab/kinetic KB `0.4+bonus` (positional KB suppressed via `#no_knockback`), min-reach 2.0, range 4.5–6.5; Lunge attacker impulse `0.458·lvl`; `ATTACK_KNOCKBACK` mob attribute; deterministic partial KB resistance; `hurtClient/hurtServer` split; data-driven everything.

### 5.12 Things that are *identical everywhere* (verified, despite folklore)
Attacker slowdown `×0.6` h + sprint reset on bonus-KB hits; crit = ×1.5 damage with zero KB effect; the 0.4 base strength; the `m/2` pre-halving; 0.1 entity hitbox pick margin (legacy) — and between 1.7.10↔1.8.9 specifically: literally all knockback *math* (only delivery, miss handling, and targeting changed).

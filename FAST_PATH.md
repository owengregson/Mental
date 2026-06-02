# StrikeSync Fast Path — exact pipeline analysis

This document is the deep-dive companion to the [README](README.md). It walks
the exact byte-level pipeline of a player attack on vanilla Paper, on the
StrikeSync v4.0.0 fast path with the velocity pre-send disabled, and on the
fast path with pre-send enabled. Every saving (and every non-saving) is
called out explicitly.

## Vanilla pipeline

### Phase 1 — Packet arrival (netty I/O thread)

```
T+0       client clicks
T+RTT/2   server's netty IoEventLoopGroup decodes ServerboundInteractPacket
          → Connection.channelRead0
          → ServerGamePacketListenerImpl.handleInteract(packet)
```

### Phase 2 — Vanilla queues for main thread

`handleInteract` immediately calls
`PacketUtils.ensureRunningOnSameThread(packet, listener, server)`. Since the
caller is on a netty thread, this throws `RunningOnDifferentThreadException`.
The catch above wraps the work in a `TickTask(currentTick, runnable)` and
`tell()`s it onto `MinecraftServer.pendingRunnables`, a `Queue<TickTask>`.

### Phase 3 — Main thread polls the queue

The server main thread alternates `tickServer()` with `waitUntilNextTick()`,
polling `pendingRunnables` each loop iteration. When it pulls our TickTask,
`handleInteract` re-runs on the main thread:

- `Level.getEntity(int)` → hash-map lookup
- Range check: square distance ≤ 36 (6 blocks)
- Calls `attacker.attack(target)`

### Phase 4 — `Player#attack`

- `damage = Attribute.ATTACK_DAMAGE.getValue()`
- `bonus = EnchantmentHelper.getDamageBonus(weapon, target.mobType)`
- `cooldownMul = getAttackStrengthScale(0.5f)`
- `damage *= 0.2 + cooldownMul² * 0.8`, `bonus *= cooldownMul`
- Crit detection (`cooldownMul > 0.9 && fallDistance > 0 && !onGround && ...`)
- If crit: `damage *= 1.5`
- Sweep / sprint-attack detection
- `target.hurt(damageSource, total)`

### Phase 5 — `LivingEntity#hurt`

- Invulnerability check
- CraftBukkit fires `EntityDamageByEntityEvent` — **all listeners run, including
  StrikeSync's `LatencyCompensationModule@HIGHEST` and `KnockbackModule@MONITOR`**
- If not cancelled:
  - `actuallyHurt` — armor reduction, health change
  - `knockback(0.4, dx, dz)` → `setDeltaMovement` → `hurtMarked = true`
  - Setting velocity on a Player fires `PlayerVelocityEvent` — **`KnockbackModule@HIGH`
    overrides with the engine-computed vector**
- Hurt sound via `Level.playSound`
- Hurt-animation packet queued via `level.broadcastEntityEvent`

### Phase 6 — `Player#attack` post-hurt

Sprint knockback bonus, sweep attack damage to nearby entities, Fire Aspect /
Bane of Arthropods enchantment effects, crit + sweep particles,
`resetAttackStrengthTicker`, weapon durability damage,
`causeFoodExhaustion(0.1f)`, `awardStat(Stats.DAMAGE_DEALT)`,
`MOB_KILLS` if killed, advancement triggers.

### Phase 7 — Tracker broadcast

End of `tickServer()`: each `TrackedEntity.serverEntity.sendChanges()` runs.
For our victim, `hurtMarked = true` causes an immediate broadcast of
`ClientboundSetEntityMotionPacket(entityId, dx*8000, dy*8000, dz*8000)` to
all viewers (including the victim's own connection).

### Phase 8 — Client renders

```
server netty out  →  T+RTT/2 + server-side delay
client receives   →  applies velocity, renders motion
```

### Total vanilla wall-clock

```
T+0     click
+ RTT/2 network in
+ 0–50ms (queue → main thread)
+ μs    (main-thread work)
+ 0–50ms (rest of tick → tracker broadcast)
+ RTT/2 network out
= T+RTT + 25–75 ms server-side dead time on average
```

---

## Fast path, `pre-send-feedback: false` (opt-out mode)

### Phase 1 (modified)

PacketEvents' netty event-loop intercepts `INTERACT_ENTITY` **before**
vanilla's `Connection.channelRead0` hands it to `handleInteract`.

### Phase 2 — `HitPacketListener.onPacketReceive` (still on netty)

- Type filter: `PacketType.Play.Client.INTERACT_ENTITY`
- Action filter: `WrapperPlayClientInteractEntity.getAction() == ATTACK`
- `HitRegSettings.enabled()` check
- `CpsLimiter.tryAcquire(attackerId, maxCps, now)` — per-UUID sliding-window
- Async entity lookup: `SpigotConversionUtil.getEntityById(world, id)`
- Basic validity: alive, attackable, PvP gamerule, gamemode
- Fires `AsyncHitRegisterEvent` (truly async, `super(true)`) — other plugins veto
- If `fastPath` enabled: `event.setCancelled(true)` — **vanilla never sees the
  packet, never queues a TickTask, never runs `handleInteract` on main, never
  calls `Player#attack`**

### Phase 3 — Schedule damage

`HitDispatcher.dispatch(damageable, () -> applier.apply(...))`:
- `target.getScheduler().run(plugin, scheduled -> task.run(), null)`
- On Paper non-Folia: runs on next main-thread tick (delegates to global tick scheduler)
- On Folia: runs on the target's region tick thread

### Phase 4 — `HitApplier.apply` (main thread / region thread)

- Re-resolve attacker by UUID, re-resolve target by id (iterate world entities)
- Re-validate: still online, alive, attackable, in reach (square distance ≤ 49)
- `DamageCalculator.calculate(attacker, target, simulateCrits)`:
  - `Attribute.ATTACK_DAMAGE.getValue()`
  - Sharpness bonus `1.0 + 0.5*(level-1)`
  - 1.8 crit multiplier (1.5×) if `simulateCrits` and attacker is in 1.8 critical posture
- `damageable.damage(amount, attacker)` — **identical event chain to vanilla**:
  - Vanilla's `hurt` runs (armor, invulnerability)
  - `EntityDamageByEntityEvent` fires → compensation + knockback modules run
  - Vanilla's knockback path → `setDeltaMovement` → `PlayerVelocityEvent` → knockback override
  - Hurt animation, hurt sound, `hurtMarked = true`
- Optional `attacker.resetCooldown()` if `resetAttackCooldown: true`

### Phases 5–6 — Same as vanilla

Tracker broadcast at end of tick; client receives.

### Net result

Vanilla parity. Validation moves to netty (saves tens of µs main-thread CPU),
cancellable async event runs earlier, and `Player#attack` post-processing
(sweep, durability, hunger, statistics, advancement triggers) is skipped.
**Damage timing and outbound velocity timing are identical to vanilla.**

---

## Fast path, `pre-send-feedback: true` (default — headline feature)

### Phases 1–2 — same as off-pre-send.

### Phase 3 (modified) — pre-send velocity + hurt animation, then schedule

**Invulnerability gate (runs first).** Vanilla only applies a knockback-bearing
hit about once per victim *invulnerability window* (~10 ticks): a hit inside the
window deals no knockback unless it out-damages the previous one. The pre-send
runs on the netty thread *before* main-thread damage, so it cannot see vanilla's
invulnerability state directly — it must reproduce the cadence itself, or every
spam-click that clears `max-cps` ships a fresh velocity packet and the victim's
client re-launches on each one (a spam-hit player goes flying). Two guards, both
biased toward *skipping* the pre-send — a skipped pre-send merely falls back to
vanilla's next-tick velocity, never a phantom:

- **`Snapshot.isDamageImmune()`** — cached `noDamageTicks > maxNoDamageTicks / 2`
  (vanilla's own `invulnerableTime > 10` test). Catches a victim already
  invulnerable from this hit, another attacker, or e.g. fall damage.
- **`HitFeedbackGate.tryPreSend(victimId, now, feedback-min-interval-ms)`** — a
  per-*victim*, race-free rate gate admitting at most one pre-send per window
  (default 500 ms = 10 ticks). Keyed by victim, so multiple attackers spamming
  one target still can't exceed vanilla's knockback rate, and atomic, so a
  sub-tick burst of clicks resolves to a single admitted pre-send.

The authoritative main-thread path is already gated (vanilla's `hurt`
invulnerability check), so only the pre-send needed this. If either guard
rejects, Phase 3 stops here and only the damage dispatch (Phase 4) proceeds.

1. Lookup `PlayerStateCache` snapshots for attacker and victim. The cache is
   refreshed by `HitRegModule`'s period-1 `runTaskTimer` task — every tick,
   every online player gets a fresh `Snapshot{x, y, z, yaw, vx, vy, vz,
   onGround, sprinting, knockbackResistance, mainHandKnockbackLevel,
   noDamageTicks, maxNoDamageTicks, entityId}` published via
   `ConcurrentHashMap`. Reads from the netty thread are lock-free.
2. Compute knockback via `KnockbackEngine.computeFromCache(attSnap, vicSnap,
   ks, null)`:
   - Pure function — no Bukkit access, no I/O
   - Routes through the same private `computeImpl` as the main-thread
     overload, so the formula is provably identical
3. **Velocity packet**: construct `WrapperPlayServerEntityVelocity(vicSnap.entityId(),
   Vector3d(x, y, z))`. Send via `PacketEvents.getAPI().getPlayerManager()
   .sendPacket(victim, packet)`. PacketEvents writes to the victim's channel
   via netty's `writeAndFlush`. **On the wire NOW from the netty thread,
   T+RTT/2 from the original click.**
4. **Hurt animation packet**: compute `hurtYaw = atan2(attacker.z − victim.z,
   attacker.x − victim.x) * 180/π − victim.yaw` (vanilla's
   `LivingEntity#hurt` formula). Construct `WrapperPlayServerHurtAnimation(
   vicSnap.entityId(), hurtYaw)` and send to:
   - the **victim** (drives the screen-shake / red-flash on their HUD), and
   - the **attacker** (drives the visible flinch on the target's model — the
     dominant hit-confirmation cue in PvP).
   Other nearby viewers receive the animation on the next entity-tracker
   pulse from vanilla's hurt chain, same as today.
5. Then schedule the damage application as before.

### Phase 4 — same as off-pre-send

Damage runs on main/region thread; events fire normally; knockback module
overrides; vanilla's hurt path sets `hurtMarked = true`.

### Phase 5 — tracker broadcasts a SECOND velocity + hurt-animation pair

End of tick; same path as vanilla.

- **Velocity, same value as pre-sent**: client treats as redundant, no
  visual change. Common case when the cache was fresh and no late
  compensation hint shifted the math.
- **Velocity, different value**: client snaps to the new velocity. Visible
  as a small mid-air correction. Happens when latency-compensation kicked
  in late, or player teleported between cache update and hit.
- **Hurt animation**: vanilla broadcasts a second hurt animation through
  the entity tracker. The client de-duplicates via its own animation timer
  — already-animating entities don't re-trigger the flash, so the second
  packet is invisible.

### Phase 6 — client renders

### Net result

- **Pre-sent velocity AND hurt-animation reach the client at T+RTT**
  (one-way out, both issued from netty immediately on hit).
- Vanilla would have delivered them at T+RTT + (queue→main) + (rest of
  tick → tracker) = T+RTT + 25–100 ms.
- **Wall-clock saved on perceived knockback + visible flinch + screen
  shake: 25–100 ms typical**.

---

## Side-by-side timing table

|                                  | Vanilla | Fast path off | Fast path on, pre-send off | Fast path on, pre-send on |
| -------------------------------- | --- | --- | --- | --- |
| Click → server netty             | T+RTT/2 | T+RTT/2 | T+RTT/2 | T+RTT/2 |
| Validation runs on               | main thread | main thread | netty | netty |
| `Player#attack` runs?            | yes | yes | **no** | **no** |
| Damage applied via               | `Player#attack` → `hurt` | `Player#attack` → `hurt` | `damage(amount, attacker)` → `hurt` | `damage(amount, attacker)` → `hurt` |
| Sweep / durability / stats       | yes | yes | **no** | **no** |
| Outbound velocity sent           | tracker tick after damage | tracker tick after damage | tracker tick after damage | **immediately from netty + tracker tick (redundant)** |
| Outbound hurt animation sent     | tracker tick after damage | tracker tick after damage | tracker tick after damage | **immediately from netty + tracker tick (redundant)** |
| Client receives velocity at      | T+RTT + 25–100 ms | T+RTT + 25–100 ms | T+RTT + 25–100 ms | **T+RTT** (plus a redundant second packet) |
| Client sees red flash / shake at | T+RTT + 25–100 ms | T+RTT + 25–100 ms | T+RTT + 25–100 ms | **T+RTT** (plus a redundant second packet) |

---

## What's preserved 1:1 with vanilla

- `EntityDamageByEntityEvent` — fires once, all listeners run
- `PlayerVelocityEvent` — fires once, all listeners run, knockback module overrides
- Armor reduction, invulnerability ticks, server-side health change
- Hurt animation packet, hurt sound
- Mob AI reaction (`lastHurtBy`)
- Server-side velocity (`setDeltaMovement` happens via vanilla's hurt path inside `damage()`)
- StrikeSync's compensation module input-correction flow
- StrikeSync's knockback module vector override on `PlayerVelocityEvent`
- Knockback cadence — the async pre-send is gated to the victim's
  invulnerability window (Phase 3), so spam-clicks can't exceed vanilla's
  once-per-~10-ticks knockback the way an ungated pre-send would

## What's omitted vs vanilla `Player#attack`

| Feature | Why omitted | How to restore |
| --- | --- | --- |
| Sweep attack | 1.9+ feature; off-target for 1.8 PvP | Plugin on `EntityDamageByEntityEvent` |
| Weapon durability damage | Skipped for 1.8 simplicity | Plugin |
| `Stats.DAMAGE_DEALT`, `Stats.MOB_KILLS` | Bukkit's `damage()` doesn't increment | Plugin |
| Advancement triggers (e.g. `monsters_hunted`) | Same | Plugin |
| Hunger cost (`causeFoodExhaustion(0.1)`) | Same | Plugin |
| Crit / sweep particles (heart "damage indicator" still fires) | Not driven by `damage()` | Plugin |
| Fire Aspect, Bane of Arthropods, Smite-vs-undead | `DamageCalculator` models Sharpness only | Extend `DamageCalculator` |
| Attack-cooldown reset | Configurable; on by default (`reset-attack-cooldown: true`) | Already toggleable |

## Compatibility matrix

| Plugin pattern | Works? |
| --- | --- |
| Listens for `EntityDamageByEntityEvent` (anti-cheat, region pvp, custom damage) | ✓ — fires identically |
| Listens for `PlayerVelocityEvent` (knockback mods) | ✓ — fires identically |
| Listens for `AsyncHitRegisterEvent` (StrikeSync's own API) | ✓ — earlier than vanilla, async-safe |
| Hooks into `Player#attack` via reflection or NMS | ✗ — never called when fast path is on |
| Tracks vanilla statistics for damage/kills | ✗ — see omitted list |
| Anti-cheats that flag "client-acknowledged knockback without server velocity change" | ⚠ — server velocity does update on next tick; tight checks may flag the brief gap |
| Folia | ✓ — `HitDispatcher` uses `Entity#getScheduler()` which is region-aware |

## Edge cases

1. **First hit after join.** No `PlayerStateCache` snapshot yet → pre-send
   returns early without sending. Damage path unaffected. The next hit
   benefits from the cache.
2. **Cache staleness from teleport / ability swap.** Up to 50ms of staleness;
   if the player teleported in that window, the async vector uses old
   position. Main-thread vector uses fresh state, second velocity packet
   corrects. Client sees a small snap.
3. **Compensation hint applies late.** Async vector is uncompensated;
   main-thread vector is compensated. Same correction-snap behavior as case 2.
4. **Other plugin cancels `EntityDamageByEntityEvent` on main thread.**
   Pre-sent velocity AND hurt animation already on the wire. Damage doesn't
   apply; no second velocity / hurt-animation packet from vanilla's hurt
   chain. Client sees a brief knockback + flash without health loss.
   Mitigation: don't enable pre-send on servers with frequent cancellations
   (heavy region/safezone systems).
5. **Server heavily lagging (MSPT > 50ms).** Vanilla's queue is delayed
   proportionally. Pre-send is unaffected — velocity still arrives at T+RTT.
6. **CPS exceeded.** Async listener cancels at netty; no damage, no pre-send.
7. **Action is INTERACT or INTERACT_AT.** Listener returns early; vanilla
   handles normally.
8. **Non-player target.** Pre-send is skipped (mobs have no client to
   receive packets); damage path runs through entity scheduler same as for
   players.
9. **Spam-clicking inside the invulnerability window.** The pre-send is gated
   per-victim to one packet per `feedback-min-interval-ms` (default 500 ms),
   matching vanilla's once-per-~10-ticks knockback cadence. Excess clicks still
   reach the main thread as damage attempts (where vanilla's invulnerability
   check rejects them, exactly as for vanilla spam), but emit **no** extra
   velocity or hurt-animation packets — so a spam-clicked victim is knocked back
   at the vanilla rate, not launched. Set `feedback-min-interval-ms: 0` to
   disable the gate (re-introduces the spam-knockback exploit; not recommended).

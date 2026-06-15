---
name: netty-fast-path
description: Use when touching the packet layer — HitPacketListener, FeedbackSenders/FeedbackBurst, pre-send behavior, reach validation, PacketEvents usage, or anything that runs on the netty thread.
---

# The netty fast path (hit-registration)

## Thread split (the load-bearing decision)

Registration on the netty thread (validate against tick-frozen snapshots,
cancel the vanilla packet, optionally pre-send feedback), DAMAGE on the
victim's owning thread (re-resolve, re-validate, `damage(amount, attacker)` —
the full vanilla event chain). This is the surviving conclusion of the
async-knockback fork lineage; full-async entity mutation was abandoned by
everyone who tried it. Pipeline walk-through: docs/fast-path.md.

## Folia: the netty thread may NOT read live entity state (cost a deep round)

"Validate against tick-frozen snapshots" is **load-bearing on Folia, not a
style note.** Folia guards `CraftEntity.getHandle()` with
`ensureTickThread(...)` — any read that goes through the handle THROWS off the
owning region thread (`IllegalStateException: ... Accessing entity state off
owning region's thread`), and world entity queries throw
`Asynchronous ... getEntities/getNearbyEntities call!`. The netty loop is
never a region thread. Measured on Folia 1.21.11 (probe), from a netty-like
thread:

- THROW: `world.getEntities()`, `world.getNearbyEntities()`,
  `SpigotConversionUtil.getEntityById` (on a cache miss → NMS lookup),
  `entity.getEntityId()`, `entity.getName()`, `player.getGameMode()` — any
  `getHandle()`-routed accessor.
- SAFE (cached / config): `getLocation()`, `getWorld()`, `getUniqueId()`,
  `getType()`, `isValid()`, `isDead()`, `world.getPVP()`,
  `Bukkit.getPlayer(uuid)`, `Bukkit.getOnlinePlayers()`.

The 2.0.x regression that taught this: `HitPacketListener.onPacketReceive`
resolved the target with `getEntityById` and called
`isAttackable(...)` → `getGameMode()` on the netty thread. On Folia BOTH
throw; the catch-all (`catch (Throwable) → allow packet through`) then handed
**every player-vs-player melee to vanilla** — vanilla knockback, no era model,
no pre-send. The user saw "knockback partially non-functional, vanilla
verticals when jumping." Fix: resolve player victims through the frozen
`PlayerStateCache.playerIdByEntityId` index + `Bukkit.getPlayer(uuid)`, gate
attackability on the snapshot's `creative()` flag + the safe `world.getPVP()`,
and let the owning-thread applier (`HitApplier.applyPlayer`, resolves both
parties by UUID — never `getEntities()`) be the authoritative validator.
Non-player targets can't be resolved off-region, so on Folia they pass through
to vanilla (mob combat / armour stands keep working); Paper keeps the live
scan. Melee guarantees attacker and victim share a region, so the applier's
attacker reads are region-correct.

Two corollaries:
- **Debug suppliers run on the netty thread too.** A `() -> "..." +
  player.getName()` message throws on Folia and (without protection) the
  catch-all eats the hit — re-introducing the bug whenever debug is enabled.
  `DebugLog.log` now swallows supplier throwables, and netty-thread call sites
  use `safeName(player)` (the cached PacketEvents username, UUID fallback).
- **There is no Folia combat coverage.** The tester runs the boot suite only
  on Folia (`MentalTesterPlugin`: gameplay suites drive cross-region state from
  one context, which Folia forbids). Combat changes can't be matrix-verified on
  Folia — reason from this skill, the probe technique above, and the owner's
  real Folia + SimpleBoxer setup.

## Pre-send composition rules (FeedbackBurst — pure, unit-pinned)

- Velocity BEFORE hurt, always.
- Bundle delimiters wrap the burst on 1.19.4+ (`fast-path.bundle-feedback`),
  one `User.writePacket` each + single `flushPackets()` — velocity and flinch
  land in the same client frame. Below 1.19.4: bare, back-to-back.
- Never a single-packet bundle: a suppressed velocity ships hurt bare.
- Velocity suppressors (hurt still ships): AnticheatGate, OCM owning the
  attacker's knockback, pending LEGACY resistance roll, missing snapshots,
  the per-victim feedback window (`auto` = live maxNoDamageTicks/2).
- A victim with NO PacketEvents user (in-process bots like SimpleBoxer,
  synthetic players) gets NO burst and must never be accounted
  wire-delivered — the registration-time vector is `submitPinned` instead:
  the authoritative pass adopts the era-moment VALUES (snapshot-read, the
  in-order processing instant) but ships them through the normal velocity
  event, no suppressor armed. `submitPreDelivered` (wire carried it, dup
  suppressed) vs `submitPinned` (no wire, ship once) is load-bearing: a
  pre-delivered marker for an unsendable burst would let any non-PE-level
  suppression mechanism silently eat the victim's knockback.
- Pre-send HURT_ANIMATION (explicit yaw = directional tilt), **never
  DAMAGE_EVENT**: clients couple damage-type effects to DAMAGE_EVENT and the
  authoritative re-send would double-fire them. Pre-1.19.4 falls back to
  entity-status 2.
- Duplicates are fine by design: the authoritative path re-emits through
  vanilla; clients treat them as no-op corrections.

## PacketEvents specifics

- Shaded + relocated (`me.vexmc.mental.lib.packetevents.*`) — external code
  (tester) cannot reference PE types at runtime; test seams must be pure
  Mental-side classes instead.
- Built/loaded in `onLoad`, `init()` after modules register listeners,
  terminated after they unregister.
- `getUser(player)` is null for synthetic/disconnecting players — guard and
  skip; never assume a live connection.
- **Listeners fire for EVERY connection state** (handshaking, status, login,
  configuration, play). NEVER downcast `event.getPacketType()` to
  `PacketType.Play.*` — a real client's join traffic throws CCE per packet
  (shipped in 1.3.0; integration suites can't catch it because FakePlayers
  inject in Play state). Compare by REFERENCE against specific constants:
  Configuration has its own KEEP_ALIVE/PONG which must not match the Play
  guard — cancelling one times the client out mid-(re)configuration.
  Regression pin: `ProbeListenerStateTest` (stub `PacketEvents.setAPI` +
  `NettyManagerImpl` + netty on the test classpath make events constructible
  in plain unit tests).
- Sending a Play wrapper to a player mid-(re)configuration (1.20.2+) throws
  inside PE — wrap sends whose target may be reconfiguring in catch-all and
  drop (a missed probe/feedback beats a pipeline exception).

## The vanilla-attack obligations the cancelled packet leaves behind

Cancelling ATTACK means `Player.attack` never runs — anything era-relevant
it did server-side must be re-implemented or it silently vanishes:

- **Sprint-flag clear**: vanilla ends every sprint-bonus hit with
  `setSprinting(false)` (the server half of w-tap). `KnockbackModule`
  clears it after each accepted melee hit from a sprinting player — without
  it, no-w-tap seconds keep the sprint extra forever (measured: no-w-tap
  and w-tap doubles both flew 10.09 blocks; real 1.8.9 separates them
  7.2 vs 11.4).
- **Attack-time sprint truth**: a faithful client sends STOP_SPRINTING in
  the same flush as its attack; vanilla read the flag INSIDE
  `Player.attack`, ahead of that packet, while the owning-thread damage
  runs after the inbound queue. The listener stamps the sprint answer it
  used at registration (`SprintTracker.stampAttackVerdict` — sprint AND
  wire freshness); the authoritative pass consumes it
  (`takeAttackVerdict`) instead of a live read. Without the stamp,
  invuln-boundary (perfect-timing) sprint hits ship plain.
- **The wire sprint view (wtap-registration, default on)**: the era queue
  applied STOP/START/ATTACK in arrival order — a w-tap registered however
  fast the tap. The tick-frozen snapshot is up to a tick OLDER than that
  contract, so registration reads `SprintTracker.peekWire` first: the
  attacker's entity-action packets replayed at arrival (fed by
  `GroundPacketTap`, same-thread program order with their ATTACK), armed
  freshness on START arrival, vanilla's in-attack clear mirrored by
  `clearWireSprint` beside the live-flag clear, and an owning-thread
  per-tick `reconcileWire` (watcher sample) that seeds and re-adopts
  server-granted `setSprinting` after 150 ms of wire silence. `peekWire`
  null (module off, synthetic players) = snapshot fallback, byte-identical
  to pre-1.7.0. Bukkit toggle events must NEVER write the wire view — they
  fire at packet application, so a boundary-applied STOP would overwrite a
  newer wire START.
- Deliberate omissions stay deliberate: sweep, durability, statistics,
  hunger (1.7.10 target feel).

## The boundary contracts (perfect-cadence combos — addendum 4)

A spam combo throws each hit EXACTLY when the hurt window halves and the
previous flight touches down; three boundary contracts keep those hits
era-exact (each was measured broken once):

- `Snapshot.isDamageImmune` carries a +1 staleness allowance: the frozen
  `noDamageTicks` predates its tick's decrement, so the boundary-legal hit
  reads `max/2 + 1`. Phantom-safe: the deferred damage runs ≥1 tick after
  the freeze, so every admitted hit is accepted there (pinned by gap-9
  spam: velocities == damage events).
- The `auto` feedback window is `(max/2 − 1)` ticks — a window equal to
  the legal cadence makes every legal hit race the gate on ms jitter.
- The pre-send's victim state is the snapshot, and the snapshot's ledger
  read is `currentExcludingTick` — the residual as of the END of the
  previous tick. Era servers processed the attack in the attacker's
  connection slot before the victim's same-tick movement packets; a
  same-tick landing must stay invisible or boundary hits ship grounded
  0.3608 verticals where the era ships the pre-landing ~0.25.
- `GroundPacketTap` (plugin-level, read-only, MONITOR) feeds the watcher
  the client's own movement + sprint-action packets in arrival order —
  the era bookkeeping's exact cadence; the tick sampler only serves
  packetless players (fake players; their NO_TICK records keep the
  inclusive view the suites pin).

## Reach validation (P5, default OFF)

Ping-rewound sanity gate, ClubSpigot-lite: 40-sample/tick position ring per
player; ATTACK passes unless EVERY candidate (history around
now − ping − interpolation-offset, plus live) puts the victim's 0.6×1.8 AABB
beyond `max(max-reach, entity-interaction-range attribute) + leniency` from
the attacker's eye. Bias to allow: creative attackers, untracked parties, and
any detected anticheat (gate defers — reach is its department) all skip.
It is a blatant-reach filter, not an anticheat.

---
name: netty-fast-path
description: Use when touching the packet layer — the parse rim (PacketTap/BurstSender/ProbeRim/ValveListener), FeedbackPlan/pre-send behavior, reach validation, PacketEvents usage, or anything that runs on the netty thread.
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
  `entity.getEntityId()`, `entity.getName()`, `player.getGameMode()`,
  `Bukkit.getCurrentTick()` (→ `RegionizedServer.getCurrentTick()`, throws
  `IllegalStateException("No currently ticking region")` off a region thread —
  it cost a debugging round when a swallowed throw silently killed the
  packet-fed ground ledger) — any `getHandle()`-routed accessor.

The boundary attack-ordering exclusion needs a tick at two sites — the netty
packet stamp (`GroundFsm`, was `GroundTransitionWatcher`) and the owning-thread
view read (the published `PlayerView`, was `PlayerStateCache`). Both go through
the `TickClock` port (was `ServerTickClock`, since 2.1.2): `PaperTickClock`
wraps `Bukkit.getCurrentTick()` (byte-identical), `CounterTickClock` is a
global-region-task counter on Folia that any thread may read. It returns a
`TickStamp` value type (no raw int compares) and is **initialised to `NO_TICK`,
not 0** — load-bearing: both sites read the same clock, so a
never-started/stalled counter that read a stuck `0` would make every stamp equal
every excludeTick and fire the exclusion **universally and permanently**
(server-wide too-sinky). NO_TICK init degrades a dead counter to no-exclusion
(inclusive) instead, and the view's end-of-previous-tick ledger read
(`currentExcludingTick`'s successor) also requires the boundary sample to be
RECENT (4 ticks) so a stale match cannot false-exclude.
- SAFE (cached / config): `getLocation()`, `getWorld()`, `getUniqueId()`,
  `getType()`, `isValid()`, `isDead()`, `isOnGround()` (javap-proven: reads
  the raw NMS `Entity.onGround()` field directly, NOT through `getHandle()`,
  so no `ensureTickThread` — at worst a benign stale-boolean read; do NOT
  assume it throws), `world.getPVP()`, `Bukkit.getPlayer(uuid)`,
  `Bukkit.getOnlinePlayers()`.

The 2.0.x regression that taught this (v5 seam names bracketed): the packet
listener [the rim's `PacketTap`, was `HitPacketListener.onPacketReceive`]
resolved the target with `getEntityById` and called
`isAttackable(...)` → `getGameMode()` on the netty thread. On Folia BOTH
throw; the catch-all (`catch (Throwable) → allow packet through`) then handed
**every player-vs-player melee to vanilla** — vanilla knockback, no era model,
no pre-send. The user saw "knockback partially non-functional, vanilla
verticals when jumping." Fix: resolve player victims through the frozen
entityId→UUID index [now the published `PlayerView` / `ConnectionDomains`, was
`PlayerStateCache.playerIdByEntityId`] + `Bukkit.getPlayer(uuid)`, gate
attackability on the view's `creative()` flag + the safe `world.getPVP()`, and
let the owning-thread authoritative pass [the session damage task /
`DamageRouter`, was `HitApplier.applyPlayer` — resolves both parties by UUID,
never `getEntities()`] be the authoritative validator.
Non-player targets can't be resolved off-region, so on Folia they pass through
to vanilla (mob combat / armour stands keep working); Paper keeps the live
scan. Melee guarantees attacker and victim share a region in essentially all
real play, so the applier's attacker reads are region-correct — EXCEPT a
region-boundary straddle or an attacker that pearls/teleports across a region
in the dispatch tick. The authoritative knockback path reads the live ATTACKER
on the VICTIM's region thread (the session damage task AND the `KnockbackUnit`'s
EDBEE handler [was `HitApplier.applyPlayer` AND
`KnockbackModule.onEntityDamageByEntity`] — sprint flag, position, the attacker
self-slow); a cross-region attacker makes each throw `ensureTickThread`. The
trap (cost a design-panel round): the EDBEE-handler reads run **inside the
synchronous `EntityDamageByEntityEvent` dispatch**, so Bukkit's per-listener
`catch(Throwable)` in the event bus SWALLOWS the throw — a try/catch around the
applier can never intercept it, the event isn't cancelled, and vanilla applies
a raw knock with a half-mutated sprint/freshness ledger. Fix (2.1.2): an
up-front `Scheduling.isOwnedByCurrentRegion(attacker)` gate in BOTH handlers
(always true on Paper — one region; the real check on Folia) skips the
cross-region hit before any attacker read. Only call `isOwnedByCurrentRegion`
from a region/owning thread, never netty. The applier keeps a backstop catch
scoped to `IllegalStateException` (the off-region throw) so a genuine bug still
surfaces on Folia.

Two corollaries:
- **Debug suppliers run on the netty thread too.** A `() -> "..." +
  player.getName()` message throws on Folia and (without protection) the
  catch-all eats the hit — re-introducing the bug whenever debug is enabled.
  `DebugLog.log` now swallows supplier throwables, and netty-thread call sites
  use `safeName(player)` (the cached PacketEvents username, UUID fallback).
- **Folia combat coverage is one smoke wide (Phase 5 run C).** The tester now
  runs the boot suite AND a `FoliaCombatSmoke` on real Folia (26.x, release
  gate): a same-region fake pair driven entirely on their owning region threads
  (spawn via `runAt`, attack via `runOn(attacker)`, journal read + teardown via
  `runOn(player)`), journal-asserted against the kernel `KnockbackEngine`'s
  canonical standing vector (0,0.3608,0.4), plus a zero-touch case. The v5
  two-realm engine needed ZERO Folia changes to pass it. Still un-drivable by a
  fake player: CROSS-region melee — NMS `Player.attack` reads the victim's live
  state and so throws `ensureTickThread` off-region before any Mental code runs
  (real melee always shares a region; the `KnockbackUnit`'s
  `isOwnedByCurrentRegion(attacker)` guard is the boundary-straddle /
  dispatch-tick-pearl belt, and the smoke pins the same-region check is
  consulted). Broader cross-region gameplay suites still can't drive state from
  one context — reason from this skill, the probe technique above,
  `live-server-testing`, and the owner's real Folia + SimpleBoxer setup.

## Pre-send composition rules (FeedbackPlan — pure, unit-pinned)

- Velocity BEFORE hurt, always.
- Bundle delimiters wrap the burst on 1.19.4+ (`fast-path.bundle-feedback`),
  one `User.writePacket` each + single `flushPackets()` — velocity and flinch
  land in the same client frame. Below 1.19.4: bare, back-to-back.
- Never a single-packet bundle: a suppressed velocity ships hurt bare.
- Velocity suppressors (hurt still ships; the transaction records the reason):
  the anticheat gate, pending LEGACY resistance roll, missing/stale views, the
  per-victim feedback window (`auto` = live maxNoDamageTicks/2).
- A victim with NO PacketEvents user (in-process bots like SimpleBoxer,
  synthetic players) gets NO burst and must never be accounted
  wire-delivered — the transaction resolves `PINNED` instead of `PRE_SENT`:
  the authoritative pass adopts the era-moment VALUES (view-read, the in-order
  processing instant) but ships them through the normal velocity event, never a
  valve. `PRE_SENT` (wire carried it, the valve suppresses the dup) vs `PINNED`
  (no wire, ship once, never a valve) is load-bearing and now a transaction
  STATE, not a submit flag — conflating them is unrepresentable in the type: a
  pre-delivered marker for an unsendable burst would let any non-PE-level
  suppression mechanism silently eat the victim's knockback.
- Pre-send HURT_ANIMATION (explicit yaw = directional tilt), **never
  DAMAGE_EVENT**: clients couple damage-type effects to DAMAGE_EVENT and the
  authoritative re-send would double-fire them. Pre-1.19.4 falls back to
  entity-status 2.
- Duplicates are fine by design: the authoritative path re-emits through
  vanilla; clients treat them as no-op corrections.

## Two knockback events — Mental writes the LATE one; mirror the early one

There are TWO server knockback events and Mental only writes to the second:

- `LivingEntity.knockback(...)` fires **`EntityKnockbackEvent`** (Paper, 1.20.6+)
  with vanilla's DELTA, *inside* the damage pass — `setDeltaMovement(current +
  delta)` runs AFTER the event. Airborne vertical is
  `onGround() ? min(0.4, dm.y/2+strength) : dm.y`, so an airborne victim's delta
  `y` is **0**.
- `ServerEntity.sendChanges` (the entity tracker, per-region on Folia) later
  fires **`PlayerVelocityEvent`** just before `ClientboundSetEntityMotionPacket`.
  This is the ONLY event the `DeliveryDesk` (via the core `DeskRouter`, was
  `KnockbackPipeline.onPlayerVelocity`) overrides, so the real client gets
  Mental's value on both Paper and Folia.

The trap (cost the entire 2026-06-29 Folia audit — ~19 fixes that changed
nothing): **anything reading `EntityKnockbackEvent` sees vanilla's value, never
Mental's.** SimpleBoxer on Folia (`eventBasedKnockback = autoTicksEntities() &&
eventAvailable()`) reads exactly that event → `current + delta` = the falling
velocity on the airborne 2nd combo hit = "downward knockback." On classic Paper
it polls `hurtMarked` (the final, post-override deltaMovement) so it reads
correctly — which is why the bug looked Folia-specific. The symptom was a
measurement artifact; real clients were always fine.

Fix (the mirror, was `KnockbackEventMirror`, 2.1.3): on 1.20.6+
(`Capabilities.knockbackEvent`), the core `MirrorListener` at
HIGH/`ignoreCancelled` asks the desk for the *same pending decision object* and
sets `knockback = target − victim.getVelocity()` so the mid-pass delta any
observer (anticheat, SimpleBoxer) reads equals what the velocity event will
ship. It does NOT fire `KnockbackApplyEvent` and does NOT consume the pending —
`PlayerVelocityEvent` stays the single authoritative apply, so the final wire
velocity is unchanged (era-exact). Because it reads the desk's ONE pending
decision (not a second computation, as the old `peekMirror`/`preDeliveredWins`
sharing had to guarantee by hand), it cannot diverge from the apply — there is
no second source of truth. `getVelocity()` in the handler is region-safe — the
event fires on the victim's owning region thread.

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
  Regression pin: `PacketTapStateTest` (was `ProbeListenerStateTest`) (stub `PacketEvents.setAPI` +
  `NettyManagerImpl` + netty on the test classpath make events constructible
  in plain unit tests).
- Sending a Play wrapper to a player mid-(re)configuration (1.20.2+) throws
  inside PE — wrap sends whose target may be reconfiguring in catch-all and
  drop (a missed probe/feedback beats a pipeline exception).

## The legacy transport (pre-1.17 — the 2026-07-02 backport)

Below Paper 1.17 the play PING/PONG channel does not exist on the wire, so the
latency probe rides **window-confirmation TRANSACTIONs** instead — a windowId-0
transaction the vanilla client echoes (the anticheat-ecosystem standard). One
probe rim is registered at boot by `ServerEnvironment`: `ProbeRim` (PING) at/above
1.17, `TransactionProbeRim` below. The receive side matches a strict triple —
windowId 0, an action inside our disjoint `ProbeTransactions` namespace (negative
shorts descending from −24575, kept clear of vanilla's own container ids), and the
id actually outstanding in the player's `LatencyModel.Record` — and cancels ONLY
on a full match, so third-party container transactions flow through untouched. The
kernel `LatencyModel` is transport-agnostic (opaque long ids, `onProbeSent` /
`onResponse` unchanged); `ProbeStrategy.resolveEffective` is the one seam that maps
config→wire (PING below 1.17 resolves to TRANSACTION via a loud info line; KEEPALIVE
stays retired). The rest of the fast path is version-graceful and needs no legacy
variant: the `PacketTap` (parse rim), the `BurstSender`, and the duplicate-
`ENTITY_VELOCITY` `ValveListener` all exist and run pre-1.17 — only the pre-send
FEEDBACK degrades by version (bare back-to-back below 1.19.4, no bundle delimiter;
entity-status 2 instead of `HURT_ANIMATION` below 1.19.4). Fake players never echo
the transaction (no PE user), so the legacy RTT round-trip is verified out-of-band,
not in the matrix (see `live-server-testing`).

## The vanilla-attack obligations the cancelled packet leaves behind

Cancelling ATTACK means `Player.attack` never runs — anything era-relevant
it did server-side must be re-implemented or it silently vanishes:

- **Sprint-flag clear**: vanilla ends every sprint-bonus hit with
  `setSprinting(false)` (the server half of w-tap). Mental does **NOT**
  reconstruct that server-flag write (removed 2026-07-10, the modern-client
  sprint latch fix). On 1.21.2+ a server-side `setSprinting(false)` is ECHOED
  to the attacker's OWN client (`sendDirtyEntityData →
  sendToTrackingPlayersAndSelf`, javap-verified 26.1.2): the client adopts it,
  drops its local sprint, and confirms with one STOP_SPRINTING — and no START
  ever returns (item-use block-hitting blocks a fresh sprint start; 1.21.2+
  sends START only on a rising edge), so the raw `clientSprinting` latches
  false and every later melee verdict reads plain — a one-way latch (measured:
  ~900 consecutive owner hits all `sprint=f`). Real vanilla never produced that
  side effect at spam cadence: its clear sits behind the ≥90%-charge gate,
  where the client simultaneously predicts the same clear and re-engages, so
  the echo is always redundant. **The new contract is one engagement, one sprint
  knock** (2.5.1, the owner's directive — supersedes the 2.5.0 post-clear
  re-arm). The hit's wire clear (`SprintWire.onServerClear`) CONSUMES the
  engagement: `sprinting` + `armed` drop and `clearedAt` opens the SPEND LATCH.
  While that latch is open, `reconcile` is BLOCKED from adopting the stale-high
  server flag — a held-W modern client's flag stays true forever (vanilla's own
  clear lives inside the cancelled ATTACK, so its STOP never crosses the wire),
  and re-adopting it would resurrect the engagement the hit just spent, arming
  every held hit as a sprint knock (the 2.5.0 bug — holding W comboed forever).
  Nothing re-arms automatically: re-arming takes a CLIENT-EXPRESSED re-gesture —
  a wire STOP→START cycle (w-tap / s-tap / GUI open), or the `SwordBlockingUnit`
  block re-arm (`onBlockSprintReset`) for block-hitting, since a modern item-use
  never drops the client flag. The latch (`clearedAt`) closes on any client
  START/STOP, the block re-arm, or an applied adopt, and only adopt-TRUE is
  blocked (a genuine external un-sprint, adopt-FALSE, is never blocked).
  PLAYER_INPUT on 1.21.2+ DOES carry a sprint bit (0x40, raw `keySprint.isDown()`
  intent — false for double-tap sprinters, true for stationary ctrl-holders; the
  server ignores it) — a re-arm corroborator for the two SWORD_BLOCKING block-hit
  gates only, never a verdict source (empirical 1.21.11 extraction,
  `docs/superpowers/research/2026-07-10-modern-client-sprint-wire.md`). This is
  the measured era server contract: real 1.8.9 consumed the flag inside every
  bonus attack and re-armed only on a client START — a no-w-tap double flew 7.2
  blocks where a w-tap double flew 11.4; the 2.5.0 auto re-arm collapsed that
  separation (no-w-tap and w-tap doubles both flew 10.09, its bug signature).
  Never re-add the deferred `setSprinting(false)`.
- **Attack-time sprint truth**: a faithful client sends STOP_SPRINTING in
  the same flush as its attack; vanilla read the flag INSIDE
  `Player.attack`, ahead of that packet, while the owning-thread damage
  runs after the inbound queue. Registration stamps the `SprintVerdict` it used
  (sprint AND wire freshness, was `SprintTracker.stampAttackVerdict`) into the
  `HitContext`; the authoritative pass consumes that stamp (was
  `takeAttackVerdict`) instead of a live read. Without the stamp,
  invuln-boundary (perfect-timing) sprint hits ship plain.
- **The wire sprint view (wtap-registration, default on)**: the era queue
  applied STOP/START/ATTACK in arrival order — a w-tap registered however
  fast the tap. The tick-frozen `PlayerView` is up to a tick OLDER than that
  contract, so registration reads the `SprintWire`'s arrival-order view first
  (was `SprintTracker.peekWire`): the attacker's entity-action packets replayed
  at arrival (fed by the parse rim into `GroundFsm`, was `GroundPacketTap`,
  same-thread program order with their ATTACK), freshness armed on START
  arrival, vanilla's in-attack clear mirrored onto the wire (`onServerClear`,
  was `clearWireSprint`), and an owning-thread per-tick session reconcile (was
  `reconcileWire`) that seeds and re-adopts server-granted `setSprinting` after
  ≥3 ticks of wire silence (TickStamp compare — replaced the old 150 ms
  constant), EXCEPT adopt-TRUE is latch-guarded (2.5.1): while a hit-consume is
  outstanding (`clearedAt` known — a bonus hit spent the engagement and no client
  gesture followed) the reconcile does NOT re-adopt the stale-high server flag,
  because a held-W modern client's flag stays true forever and re-adopting it
  would resurrect the spent engagement — one engagement, one sprint knock. The
  latch closes on any client START/STOP, the block re-arm, or an applied adopt;
  adopt-FALSE (a genuine external un-sprint) is never blocked. The deferred
  server-flag `setSprinting(false)` that used to run beside the wire clear is GONE
  (its modern-client echo latched sprint off), so the wire clear alone consumes
  the engagement. A null wire view (feature off, synthetic players) =
  published-view fallback, byte-identical to pre-1.7.0 — and it has NO engagement
  semantics: with no wire there is no latch, so every held hit carries the bonus
  there, deliberate for packetless synthetics. Bukkit toggle events must NEVER
  write the wire view — they fire at packet application, so a boundary-applied
  STOP would overwrite a newer wire START (in D1 they don't exist at all).
- Deliberate omissions stay deliberate: sweep, durability, statistics,
  hunger (1.7.10 target feel).

## The boundary contracts (perfect-cadence combos — addendum 4)

A spam combo throws each hit EXACTLY when the hurt window halves and the
previous flight touches down; three boundary contracts keep those hits
era-exact (each was measured broken once):

- The view's immune check (was `Snapshot.isDamageImmune`) carries a +1 staleness
  allowance: the frozen `noDamageTicks` predates its tick's decrement, so the
  boundary-legal hit reads `max/2 + 1`. Phantom-safe: the deferred damage runs
  ≥1 tick after the freeze, so every admitted hit is accepted there (pinned by
  gap-9 spam: velocities == damage events).
- The `auto` feedback window is `(max/2 − 1)` ticks — a window equal to
  the legal cadence makes every legal hit race the gate on ms jitter.
- The pre-send's victim state is the published `PlayerView`, and the view's
  ledger read is the residual as of the END of the previous tick (was
  `currentExcludingTick`; in v5 it holds by construction — the view is published
  at session-tick start). Era servers processed the attack in the attacker's
  connection slot before the victim's same-tick movement packets; a same-tick
  landing must stay invisible or boundary hits ship grounded 0.3608 verticals
  where the era ships the pre-landing ~0.25.
- The parse rim (read-only) feeds `GroundFsm` (was `GroundPacketTap` → the
  watcher) the client's own movement + sprint-action packets in arrival order —
  the era bookkeeping's exact cadence; the session-side tick sampler only serves
  packetless players (fake players; their NO_TICK records keep the inclusive
  view the suites pin), and stands down permanently once packets are seen.

## Reach validation (P5, default OFF)

Ping-rewound sanity gate, ClubSpigot-lite: 40-sample/tick position ring per
player; ATTACK passes unless EVERY candidate (history around
now − ping − interpolation-offset, plus live) puts the victim's 0.6×1.8 AABB
beyond `max(max-reach, entity-interaction-range attribute) + leniency` from
the attacker's eye. Bias to allow: creative attackers, untracked parties, and
any detected anticheat (gate defers — reach is its department) all skip.
It is a blatant-reach filter, not an anticheat.

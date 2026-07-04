---
name: live-server-testing
description: Use when writing or debugging integration suites in tester/ — FakePlayer's NMS bootstrap and its clientless-player pitfalls, suite timing rules, assertion patterns, and the client-emulation model trajectory tests require.
---

# Live-server suite patterns (tester/)

## FakePlayer facts

- Built straight on NMS (ported from BukkitOldCombatMechanics), names routed
  through reflection-remapper — **parse the reobf mappings once per JVM**
  (the shared-remapper cache); per-instance parsing stalls older servers.
- `placeNewPlayer` first, direct PlayerList registration as fallback. The
  fallback must also fill the **by-NAME map (lowercased)** or
  `Bukkit.getPlayerExact` misses the player (breaks name-targeted commands).
- Spawn clears join protection across THREE layouts (≤1.21.1
  `ServerPlayer.spawnInvulnerableTime`; 1.21.2–1.21.x `Player.clientLoaded` +
  timer; 26.x listener `clientLoadedTimeoutTimer`+`waitingForRespawn`, read
  back off the player because placeNewPlayer replaced our listener). Suites
  therefore wait only ~5 ticks after spawn, not 70.
- The fake pipeline VOIDS all outbound traffic (release + complete promise):
  EmbeddedChannel is single-threaded, and 1.19/1.20's PlayerChunkLoader
  writes to player connections cross-thread mid-tick — without the void
  handler its buffer corrupts (null-promise NPEs) and wedges the main
  thread forever. Likewise the Gradle run tasks pass
  `-Ddisable.watchdog=true`: a slow-runner tick stall trips the legacy
  watchdog, whose forced shutdown deadlocks old servers into hung
  processes. Both only ever reproduced on cold CI runners.
- Fake players tick real physics via their task — but they are **clientless**:
  melee knockback (send-then-restore) never moves them. Trajectory tests must
  client-emulate: apply each velocity packet to the entity exactly once
  (`setMotion`, no events), skipping persisted-motion paths (decay-matching).
- Fake players also join **directly in Play state** — they never emit the
  handshake/login/configuration traffic a real client does, so packet
  listeners that misbehave pre-Play (e.g. downcasting `getPacketType()` to a
  Play type) pass every suite and explode on real joins. Pin that contract in
  UNIT tests against synthetic events (see `PacketTapStateTest`, was
  `ProbeListenerStateTest`), not in the live matrix.
- After `attack()`, clear the victim's HORIZONTAL motion only — the restore
  would leak one tick of pre-knock motion; zeroing motY un-grounds them
  (see legacy-motion-physics).
- Held movement input goes through the `preTick` hook (runs before the
  physics tick, where a client integrates keys), with the oracle integrating
  the identical input model into the expectation.

## FakePlayer on Folia (Phase 5 run C — the first live Folia combat coverage)

FakePlayer bootstraps UNDER regionized threading, but only from the right
thread. All three fixes are gated on `scheduling.describe() == "folia"`, so
Paper stays byte-identical.

- **Spawn on the target's OWNING REGION thread (`Scheduling.runAt`), not the
  global `runGlobal` hop.** The Paper-shaped sync spawn threw
  `NullPointerException: … ServerLevel.getCurrentWorldData() is null` from
  inside `PlayerList.placeNewPlayer` — javap confirmed it does
  `getCurrentWorldData().connections.add(connection)`, and
  `getCurrentWorldData()` returns the region-thread-local `RegionizedWorldData`
  (null off a region tick). Driving the whole spawn on the target location's
  region thread clears it: `placeNewPlayer` succeeds, `PlayerJoinEvent` fires, a
  `CombatSession` is created for both fakes, and its `repeatOn` entity-scheduler
  task ticks.
- **The final relocate uses `teleportAsync`.** Folia bans synchronous
  `Entity#teleport` while region-threading
  (`UnsupportedOperationException: Must use teleportAsync …`).
- **`FakePlayer.remove` SKIPS its direct reflective `PlayerList.remove` on
  Folia** (the one bug that earned a debugging round). `kickPlayer` already
  queues a disconnect that Folia's `tickConnections` turns into a single removal
  + `EntityScheduler` retire; a second direct remove retires the scheduler AGAIN
  → `IllegalStateException("Already retired")`, an uncaught region-tick failure
  that HARD-CRASHES the region a tick later — NOT catchable at the call site
  (it throws inside `tickConnections`, which is why the first suite run died
  mid-test with no result). javap on the crash stack pinned the double-retire.
- **Drive combat entirely on the region threads**: `FoliaCombatSmoke` spawns via
  `runAt`, attacks via `runOn(attacker)`, and reads the journal + tears down via
  `runOn(player)` — asserting the desk-journal's canonical standing vector
  (0,0.3608,0.4) against the kernel `KnockbackEngine`, plus a zero-touch case.
- **CROSS-region melee is not fake-drivable.** `FakePlayer.attack` routes through
  NMS `Player.attack(victim)`, which reads the victim's live state and so throws
  Folia's `ensureTickThread` off-region BEFORE any Mental code runs — a
  two-region melee cannot be staged. Real melee always shares a region; the
  `KnockbackUnit`'s `isOwnedByCurrentRegion(attacker)` guard is the
  boundary-straddle / dispatch-tick-pearl belt, and the smoke pins the
  same-region check is consulted and returns true.

## FakePlayer on legacy (1.9.4–1.16.5 — the 2026-07-02 backport)

The FakePlayer NMS bootstrap has a legacy branch, boot-selected by versioned
packages (`net.minecraft.server.<rev>` / `org.bukkit.craftbukkit.<rev>`,
`ReflectionRemapper.noop()` — spigot names ARE the runtime names pre-1.17). All
per-revision NMS shapes are javap-pinned in
`docs/superpowers/research/2026-07-02-legacy-fakeplayer-nms-shapes.md` (with the
1.14.4 straddle rows in `docs/superpowers/research/2026-07-03-v1_14_R1-shapes.md`)
— read them, don't guess. The whole legacy backport is documented in
`docs/superpowers/plans/2026-07-02-mental-legacy-backport.md`; the full-range
campaign (1.14.4 + the mega-jar) in
`docs/superpowers/plans/2026-07-03-mental-full-range.md`.

- **Synchronous join below the chunk-gated async path — the split is 1.15.2+,
  NOT 1.14.** 1.15.2+ join is async/chunk-gated
  (`onPlayerJoinFinish(EntityPlayer, WorldServer, String)`); 1.9.4–**1.14.4**
  register synchronously (`onPlayerJoin(EntityPlayer, String)`). **1.14.4 is a
  straddle**: MODERN NMS shapes (`PlayerInteractManager(WorldServer)`,
  `Vec3D mot`/`setMot` — both begin at 1.14.4, not 1.15.2) driven through the
  1.13-era synchronous join; 1.9.4–1.13.2 use `PlayerInteractManager(World)` and
  the legacy motion accessors. The `legacyAsyncJoin()` probe routes each
  correctly (zero code changes were needed for v1_14_R1). Spawn/join is driven
  synchronously and the suite waits ~5 ticks, same as modern.
- **NMS `EntityHuman.attack(Entity)` for melee — NOT `LivingEntity#attack`.**
  Bukkit `HumanEntity.attack` is absent on all 7; `LivingEntity#attack` exists
  only 1.15.2+. Below that the fake attacks via the NMS `attack` method (spigot
  name is literally `attack` every revision). The generic Bukkit
  `LivingEntity#attack` routes the generic hurt path and silently deals NO damage
  for a clientless player — a vacuous pass trap.
- **No motion integration pre-1.11 (the boot-tier wedge, now the 8 skips).**
  Clientless victims read `isOnGround() == false` on 1.9/1.10 NMS even while
  Mental delivered the correct grounded value — the server does not integrate a
  connectionless player's motion before 1.11, so trajectory/flight tests cannot
  fly the victim. Those 8 assertions are **loud first-class skips** on 1.9.4/1.10.2
  (`context.note`, visible in the log), never silent passes. The knock VALUES are
  fully pinned there — the skip is a harness limit, not a gameplay one.
- **Position-derived ground truth, not the `isOnGround()` flag.** The suite's
  grounded expectation is keyed on physical truth — the block under the feet plus a
  settled velocity, the SAME read production uses (`GroundDistance` block-geometry
  fallback; bottom-slab over-estimate is documented) — because the flag proved to
  LIE on 1.9/1.10. The flag is kept only as a divergence diagnostic (zero
  divergence on modern → no pin changes).
- **No PacketEvents user, no echo (the wire blind spot).** Fake players inject no
  PE user and void all outbound traffic, so they never round-trip the
  transaction/PING probe and never carry a wire burst — the fast path's WIRE layer
  is unverifiable in the matrix (event-level behaviour and the send-path encode are
  live-pinned; wire proof is legacy-lab + real clients, out-of-band).
- **Per-version server-behaviour bugs live here (era-accuracy bar).** e.g. 1.15.2
  fires `ProjectileHitEvent` AFTER the projectile's own damage (opposite of
  1.16.5), so an invuln gate reading live `noDamageTicks` misfired — fixed by
  reading the frozen `PlayerView` (pre-hit by construction on every version).
  Decompile-cite, don't guess.

## Suite rules

- Wait in GAME ticks (`context.awaitTicks`), never wall time; stamp event
  timing with `Bukkit.getCurrentTick()`, never `System.nanoTime` — wall time
  lies under concurrent load.
- `setNoDamageTicks(0)` before every staged hit; expectations come from the
  ENGINE on the same pre-hit state (end-to-end pins formula + delivery).
- Wall/game tick skew ⇒ compute expectation CANDIDATES at elapsed ± 1 and
  accept the best match; settle trajectories ~2s so endpoints are
  tick-insensitive before comparing positions.
- Pin event COUNTS, not just values — a blocked hit can otherwise let a
  scenario pass vacuously on its setup motion alone.
- Physics-dependent staging (hook/snowball flight) that fails to connect gets
  a `context.note` skip, never a fail — the formula is unit-pinned elsewhere.
- Captors observe at MONITOR; `KnockbackApplyEvent` count is the ownership
  discriminator (fires only for Mental-owned knocks).
- Fresh players per scenario (the residual ledger contaminates across hits);
  always remove players and unregister listeners in `finally`.
- **Captors are last-write-wins; spawn placement emits its own velocity
  event.** `captors.reset()` immediately before the staged attack, then
  `context.awaitUntil(velocityOf != null)` — a fixed `awaitTicks(3)` races
  matrix load and reads the stale spawn-window event (a float-cast
  −0.0784 equilibrium vector is the signature).
- **Arena actors must stay inside the original chunk (x,z < 112)**: 1.20.6
  does not tick clientless players in the neighbouring chunk — an unticked
  victim never settles onto the floor and reads an airborne ZERO baseline
  (vy 0.5 sprint / 0.35 kohi instead of the 0.4608/0.3108 equilibrium
  values). Extend the platform only along the knock direction.
- **The matrix run worlds spawn hostile mobs** on a dark stone platform; a
  zombie's 2.5 damage and full-strength 0.4 knock at an arbitrary bearing
  read exactly like phantom velocity events / doubled flights. Arena forces
  `doMobSpawning false` and purges nearby Monsters every prepare — keep it
  that way.

## Wire-level verification (legacy-lab)

The suites verify EVENT-level behavior; fake players cannot verify the
WIRE (voided outbound, no PE injection). Packet-level claims — single-stamp
suppression, pre-send bundles, actual shipped values, hint behavior at real
ping — are verified with `legacy-lab/harness/measure.js`: protocol fake
clients against a real local server (vanilla 1.7.10/1.8.9 for era ground
truth, Paper+Mental for acceptance), with injectable ping via delayed pong
responses. See docs/research/2026-06-05-era-wire-measurements.md for the
measured era values and the harness's protocol traps.

- `chain-plain` (N plain hits at gap G + per-hit wire vy + per-flight
  apexes) is the combo-vertical probe; boundary behavior is phase-chaotic
  at sub-tick grain (era too), so judge the PATTERN (declining vs flat
  verticals, dipping vs flat apexes, settle distance), not a single run.
- PING_MS delays only the play-ping PROBE channel — it changes the
  server's MEASURED latency (drives compensation hints), not transport.
  Numbers shifting at PING_MS>0 are the compensation module working, not
  an era-ordering signal.
- Fake players never reach the parse rim's `GroundFsm` (was `GroundPacketTap` —
  no inbound packets): the suites exercise the session-side tick-sampler fallback
  by construction; packet-path era claims live in kernel unit pins
  (`MotionLedgerTest` / `DecayTest`, was `VictimMotionTest`) + this harness only.

## Modern-bot staging traps (each cost a debugging round)

- **1.21.6+ compacted `entity_action`**: `start_sprinting` = 1, not 3 (sneak
  moved to `player_input`; numeric 3 = `start_horse_jump`, silently ignored
  off a horse). nmp models the field as a protodef MAPPER — write the NAME
  (`'start_sprinting'`), never a number.
- **Attack BEFORE swing**: Paper's `ServerPlayer.swing()` resets the
  attack-strength ticker. A swing-first bot attacks with a ~0.1 meter and
  vanilla skips the sprint-knockback/crit branches even while sprinting.
  Real clients send use_entity first, arm_animation after — every era.
- **The sound oracle** (`WATCH_SOUND=1`): which `entity.player.attack.*`
  the victim hears names the attack branch the server took
  (weak = uncharged meter, knockback = sprint bonus, crit, sweep) — branch
  visibility with zero server access. Map ids via the server's own
  `--reports` registry dump; minecraft-data's `sounds.json` drifts.
- **Sanitize every lab server** before measuring: `difficulty peaceful` +
  kill non-players. Slimes chewing the victim mid-scenario produce death
  sounds and perturbed ledgers that masquerade as delivery bugs.
- **Stage trade hits at reach-entry (~2.4–2.8 blocks)**: a charging victim
  allowed to overrun the attacker measures the era-real base-vs-sprint-extra
  opposition (~0.05 h) instead of the representative trade opener.
- Shell cwd resets between Bash calls — `cd` into `legacy-lab/harness`
  inside the SAME command that runs node, every time.

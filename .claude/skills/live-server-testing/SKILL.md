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
  UNIT tests against synthetic events (see `ProbeListenerStateTest`), not in
  the live matrix.
- After `attack()`, clear the victim's HORIZONTAL motion only — the restore
  would leak one tick of pre-knock motion; zeroing motY un-grounds them
  (see legacy-motion-physics).
- Held movement input goes through the `preTick` hook (runs before the
  physics tick, where a client integrates keys), with the oracle integrating
  the identical input model into the expectation.

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
- Fake players never reach `GroundPacketTap` (no inbound packets): the
  suites exercise the tick-sampler fallback by construction; packet-path
  era claims live in unit pins (`VictimMotionTest`) + this harness only.

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

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
- Fake players tick real physics via their task — but they are **clientless**:
  melee knockback (send-then-restore) never moves them. Trajectory tests must
  client-emulate: apply each velocity packet to the entity exactly once
  (`setMotion`, no events), skipping persisted-motion paths (decay-matching).
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

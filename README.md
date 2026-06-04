# Mental

> Latency-compensated 1.8 combat for modern Minecraft — async hit registration, authentic knockback, fishing rod and projectile mechanics. One jar, Paper 1.17.1 through 26.1.2, Folia-ready.

[![Java](https://img.shields.io/badge/Java-17+-orange?style=flat-square)](https://adoptium.net/)
[![Paper](https://img.shields.io/badge/Paper-1.17.1%20→%2026.1.2-blue?style=flat-square)](https://papermc.io/)
[![Folia](https://img.shields.io/badge/Folia-supported-purple?style=flat-square)](https://github.com/PaperMC/Folia)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

---

## What it does

Mental intercepts attack packets on the netty event loop, validates them
against tick-frozen state snapshots, and dispatches damage straight to the
victim's owning thread — skipping the 25–100 ms vanilla spends queueing the
hit through the main thread and waiting for the next entity-tracker pulse.
Damage still flows through Bukkit's normal event chain, so protection and
region plugins keep working untouched.

On top of that core, Mental ships the full 1.8 combat feel:

| Module | What it restores |
| --- | --- |
| **Hit Registration** | Netty-thread attack interception, CPS limiting, pre-sent combat feedback |
| **Knockback** | The 1.8 melee formula — friction, sprint and enchant bonuses, authentic vertical-cap ordering |
| **Latency Compensation** | Ping-aware vertical knockback correction (spike-filtered probes, gravity simulation) |
| **Fishing Knockback** | Rod hooks damage and shove what they hit; reel-in pull suppressed |
| **Rod Velocity** | 1.8 cast speed, spread, and in-flight hook gravity |
| **Projectile Knockback** | Snowballs, eggs and ender pearls knock players back again (pre-1.21.2 servers) |
| **Anticheat Compat** | Detects GrimAC/Vulcan and suppresses the one prediction-unsafe behavior automatically |

## Version support

One jar covers every server below — verified by booting real Paper servers
in the integration matrix on every release:

| Server | Status |
| --- | --- |
| Paper 1.17.1 → 1.20.4 | ✅ (Java 17+ runtime) |
| Paper 1.20.5 → 1.21.11 | ✅ |
| Paper 26.x (year scheme) | ✅ |
| Folia (1.19.4+) | ✅ (`folia-supported`, region-aware scheduling throughout) |

The compatibility layer is structural, not stringly-typed: the core compiles
against the 1.17 API so the common path is binary-safe by construction, and
version-specific code (Folia schedulers, Brigadier commands) lives in
separately-compiled units that are only class-loaded behind feature
detection. The two binary breaks in the range — the 1.21.3 `Attribute`
enum→interface change and the 1.20.5 enchantment renames — are absorbed by
tiny boot-time resolvers.

## Install

1. Drop `Mental-<version>.jar` into `plugins/`. **No dependencies** —
   PacketEvents is shaded and relocated inside.
2. Start the server. Config generates at `plugins/Mental/config.yml`.

## Commands

The root command is an interactive dashboard: every module with its live
state, click-to-toggle buttons, and hover documentation. On Paper 1.20.6+
the tree renders natively through Brigadier — clients validate arguments as
they type, and branches you lack permission for never appear at all.

| Command | Permission | Description |
| --- | --- | --- |
| `/mental` | `mental.command.use` | Interactive dashboard |
| `/mental module <name> <on\|off\|status>` | `mental.command.module` | Manage any module (persists to config) |
| `/mental ping [player]` | `mental.command.ping` | Measured RTT, jitter, spike state, probe strategy |
| `/mental debug <on\|off>` | `mental.command.debug` | Toggle verbose logging |
| `/mental debug category <name> <on\|off>` | `mental.command.debug` | Per-category control |
| `/mental debug subscribe` | `mental.command.debug` | Receive debug lines in-game |
| `/mental reload` | `mental.command.reload` | Atomic config reload, timed |
| `/mental version` | `mental.command.use` | Version, platform, capability report |
| `/mental help` | `mental.command.use` | Clickable command list |

Alias: `/mtl`. Note: on 1.20.6+ servers, registering native commands
disables Bukkit's `/reload` (Paper lifecycle-API behavior) — use
`/mental reload` for config changes.

## Configuration

Defaults reproduce the 1.8 PvP feel with the latency win enabled. Every key
is documented inline in `config.yml`; reloads swap the typed snapshot
atomically, so no hit is ever processed against a half-applied config.

```yaml
modules:
  hit-registration:
    enabled: true
    max-cps: 20
    fast-path: {enabled: true, pre-send-feedback: true, feedback-min-interval-ms: 500,
                simulate-crits: true, reset-attack-cooldown: true}
  knockback:
    enabled: true
    base: {horizontal: 0.4, vertical: 0.4}
    extra: {horizontal: 0.5, vertical: 0.1}
    limits: {vertical: 0.4, horizontal: -1}
    friction: {x: 0.5, y: 0.5, z: 0.5}
    modifiers: {sprint: 1.0, armor-resistance: false, shield-blocking-cancels: true}
  latency-compensation:
    enabled: true
    probe-strategy: PING        # dedicated 1.17+ ping/pong channel; KEEPALIVE available
    ping-offset-ms: 25
    spike-threshold-ms: 20
    probe-interval-ticks: 5
    combat-timeout-ticks: 30
    off-ground-sync: true
  fishing-knockback: {enabled: true, damage: 0.0001, cancel-dragging-in: players,
                      knockback-non-player-entities: false}
  rod-velocity: {enabled: true}
  projectile-knockback: {enabled: true, damage: {snowball: 0.0001, egg: 0.0001, ender-pearl: 0.0001}}

anticheat:
  mode: auto                    # auto | force-safe | off
  known: [GrimAC, Vulcan]

debug:
  enabled: false
  categories: {hitreg: false, knockback: false, compensation: false, fishing: false,
               projectile: false, packets: false, anticheat: false, scheduling: false,
               commands: false, config: false}
```

## Anticheat compatibility

Movement-prediction anticheats (GrimAC, Vulcan) verify client motion against
the velocity the server's own pipeline produced. Everything gameplay-relevant
in Mental is applied through that pipeline — `PlayerVelocityEvent`, real
`damage()` calls — and is therefore compatible by construction. The single
out-of-band behavior is the netty-thread velocity pre-send; in `auto` mode
Mental suppresses exactly that while a known anticheat is installed (hits
still land at full speed, feedback reverts to vanilla cadence) and logs the
adjustment. No flags are cancelled, no exemptions requested.

## API

```java
// Veto hits before vanilla ever sees them (fires on a packet worker thread):
@EventHandler
public void onHit(AsyncHitRegisterEvent event) {
    if (!myRegionAllowsPvp(event.getAttacker(), event.getTarget())) {
        event.setCancelled(true);
    }
}

// Adjust or veto the final knockback vector (fires on the owning thread):
@EventHandler
public void onKnockback(KnockbackApplyEvent event) {
    event.velocity(event.velocity().multiply(0.8));
}

// Query the facade:
Mental.MentalApi mental = Mental.get();
OptionalDouble ping = mental.pingMillis(player);
```

## Building

```bash
./gradlew build                    # compile + unit tests → core/build/libs/Mental-<version>.jar
./gradlew integrationTest          # boots real Paper servers on 1.17.1 and 26.1.2
./gradlew integrationTestMatrix    # the full seven-version matrix
```

Integration tests spin up live servers with synthetic players and assert the
actual combat outcomes — the knockback suite verifies the applied velocity
vector matches the engine's math to three decimal places, end to end.
JDK 17 and 25 are provisioned automatically via Gradle toolchains.

For the byte-level pipeline analysis, see [docs/fast-path.md](docs/fast-path.md).

## Acknowledgements

- Latency-compensation algorithm adapted from
  [CASELOAD7000/knockback-sync](https://github.com/CASELOAD7000/knockback-sync) (GPL-3.0).
- Async hit-registration concept inspired by
  [frash23/SmashHit](https://github.com/frash23/SmashHit).
- 1.8 fishing-rod and projectile mechanics, and the real-server integration
  test approach, derived from
  [kernitus/BukkitOldCombatMechanics](https://github.com/kernitus/BukkitOldCombatMechanics) (MPL-2.0).
- Packet layer by [PacketEvents](https://github.com/retrooper/packetevents) (shaded).

> Mental is the ground-up rewrite of StrikeSync; this repository carries its
> history. (Repository rename to `Mental` pending.)

## License

MIT. See [LICENSE](LICENSE).

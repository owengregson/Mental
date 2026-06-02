# StrikeSync

> Asynchronous, latency-compensated hit-registration and knockback for modern Minecraft.

[![Java](https://img.shields.io/badge/Java-25+-orange?style=flat-square)](https://adoptium.net/)
[![Paper](https://img.shields.io/badge/Paper-26.1.2-blue?style=flat-square)](https://papermc.io/)
[![PacketEvents](https://img.shields.io/badge/PacketEvents-2.12.1-purple?style=flat-square)](https://github.com/retrooper/packetevents)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

---

## About

StrikeSync intercepts attack packets on PacketEvents' netty event loop and
ships the velocity + hurt-animation packets directly from there to the
relevant clients — skipping the 25–100 ms vanilla normally spends queueing
the hit through the main thread and waiting for the next entity-tracker
pulse.

Damage itself still flows through Bukkit's normal event chain so
existing plugins keep working.

Also includes a ported version of 1.8-style knockback calculations and per-player
ping-aware vertical-knockback compensation.

For the byte-level pipeline analysis, see [FAST_PATH.md](FAST_PATH.md).

## Requirements

| Component    | Version                                 |
| ------------ | --------------------------------------- |
| Paper        | `1.21.x` matching `api-version: 26.1.2` |
| Java         | 25+                                     |
| PacketEvents | `2.12.1` (installed as its own plugin)  |

## Install

1. Install [PacketEvents](https://modrinth.com/plugin/packetevents).
2. Drop the StrikeSync jar into `plugins/`.
3. Start the server. Default config at `plugins/StrikeSync/config.yml`.

## Build from source

```bash
git clone https://github.com/owengregson/StrikeSync.git
cd StrikeSync
mvn clean package    # → target/StrikeSync-4.0.0.jar
```

## Commands

| Command                                      | Permission                        | Description                       |
| -------------------------------------------- | --------------------------------- | --------------------------------- |
| `/ss help`                                   | `strikesync.command.use`          | Show command list                 |
| `/ss authors`                                | `strikesync.command.use`          | Plugin authors                    |
| `/ss reload`                                 | `strikesync.command.reload`       | Reload config & bounce modules    |
| `/ss toggle`                                 | `strikesync.command.toggle`       | Toggle async hit registration     |
| `/ss knockback <enable\|disable\|status>`    | `strikesync.command.knockback`    | Manage 1.8-style knockback module |
| `/ss compensation <enable\|disable\|status>` | `strikesync.command.compensation` | Manage latency compensation       |
| `/ss ping [player]`                          | `strikesync.command.ping`         | RTT, jitter, spike state          |

Alias: `/strikesync` ↔ `/ss`. Tab completion is permission-filtered.

## Config

Defaults reproduce 1.8 PvP feel with the latency-saving pre-send **on**.
See `config.yml` for inline documentation of every key.

```yaml
async-hitreg:
    enabled: true
    max-cps: 20
    fast-path:
        enabled: true # cancel vanilla packet, run plugin pipeline
        pre-send-feedback: true # ship velocity + hurt animation from netty
        feedback-min-interval-ms: 500 # victim invulnerability window; stops spam-knockback
        simulate-crits: true # 1.8-style crit multiplier
        reset-attack-cooldown: true

knockback:
    enabled: true
    base: { horizontal: 0.4, vertical: 0.4 }
    extra: { horizontal: 0.5, vertical: 0.1 }
    limits: { vertical: 0.4, horizontal: -1 }
    friction: { x: 0.5, y: 0.5, z: 0.5 }
    modifiers: { sprint: 1.0, armor-resistance: false }

compensation:
    enabled: true
    ping-offset-ms: 25
    spike-threshold-ms: 20
    probe-interval-ticks: 5
    combat-timeout-ticks: 30
    off-ground-sync: true

debug:
    enabled: false
```

`/ss reload` swaps the config snapshot atomically — no hit ever sees a
torn configuration.

If a downstream plugin is incompatible with the fast path (heavy
region/safezone systems that cancel `EntityDamageByEntityEvent`), set
`async-hitreg.fast-path.enabled: false` to fall back to the passive
listener.

## API

Hook the cancellable async event to veto hits before vanilla applies them:

```java
@EventHandler
public void onHit(AsyncHitRegisterEvent event) {
    if (!myRegionAllowsPvp(event.getAttacker(), event.getTarget())) {
        event.setCancelled(true);
    }
}
```

Listeners run on a packet worker thread — **do not mutate Bukkit state
directly**. Schedule back to the main / region thread if needed.

## Acknowledgements

Latency-compensation algorithm adapted from
[caseload/knockbacksync](https://github.com/caseload/knockbacksync) (GPL-3.0).
Async-hit-registration concept inspired by
[frash23/SmashHit](https://github.com/frash23/SmashHit).

## License

MIT. See [LICENSE](LICENSE).

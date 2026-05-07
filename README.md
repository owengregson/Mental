# StrikeSync

> Asynchronous hit registration, latency-compensated 1.8.x knockback, and a
> clean module/command framework for Paper / Minecraft 26.1.

[![Java](https://img.shields.io/badge/Java-21+-orange?style=flat-square)](https://adoptium.net/)
[![Paper](https://img.shields.io/badge/Paper-26.1.2-blue?style=flat-square)](https://papermc.io/)
[![PacketEvents](https://img.shields.io/badge/PacketEvents-2.12.1-purple?style=flat-square)](https://github.com/retrooper/packetevents)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

---

## What it does

StrikeSync solves three connected PvP problems on modern Paper servers:

1. **Async hit registration.** Player attack packets are inspected on
   PacketEvents' netty event loop, rate-limited per-player, and only then
   handed to vanilla — taking the per-tick CPS gate and the validation cost
   off the main thread. A cancellable `AsyncHitRegisterEvent` lets other
   plugins veto a hit before vanilla ever sees it.
2. **1.8.x-style knockback.** A pure, configurable knockback engine
   (base/extra horizontal & vertical, friction, sprint factor, knockback
   enchant, optional armor-resistance honoring) reproduces the legacy 1.8 PvP
   feel on top of modern Paper without NMS or reflection.
3. **Latency compensation.** Per-player RTT is measured natively via
   `KEEP_ALIVE` round-trips (nanoTime), spike-protected, and used to predict
   whether the victim's client thinks they're on the ground at hit time. The
   compensator publishes a *hint* with the client-expected vy, which the
   knockback engine consumes as **input** — the engine output is never
   overwritten, so your tuned 1.8 feel is preserved 1:1.

## Quick start

### Requirements

| Component  | Version                               |
| ---------- | ------------------------------------- |
| Paper      | `1.21.x` matching `api-version: 26.1.2` |
| Java       | 21+                                   |
| PacketEvents | `2.12.1` (installed as its own plugin) |

### Install

1. Download the latest [release jar](../../releases/latest).
2. Drop it (and the [PacketEvents](https://modrinth.com/plugin/packetevents)
   plugin) into your server's `plugins/` directory.
3. Start the server. A default `plugins/StrikeSync/config.yml` is generated.

### Build from source

```bash
git clone https://github.com/owengregson/StrikeSync.git
cd StrikeSync
mvn clean package
# → target/StrikeSync-3.0.0.jar
```

## Commands

| Command                                   | Permission                       | Description                                |
| ----------------------------------------- | -------------------------------- | ------------------------------------------ |
| `/ss help`                                | `strikesync.command.use`         | Show command list                          |
| `/ss authors`                             | `strikesync.command.use`         | Plugin authors / GitHub link               |
| `/ss reload`                              | `strikesync.command.reload`      | Reload config & bounce modules             |
| `/ss toggle`                              | `strikesync.command.toggle`      | Toggle async hit registration              |
| `/ss knockback <enable\|disable\|status>` | `strikesync.command.knockback`   | Manage 1.8-style knockback module          |
| `/ss compensation <enable\|disable\|status>` | `strikesync.command.compensation` | Manage latency compensation module         |
| `/ss ping [player]`                       | `strikesync.command.ping`        | Show measured RTT, jitter, and spike state |

Alias: `/strikesync` ↔ `/ss`. Tab completion is permission-filtered.

## Configuration

Defaults reproduce 1.8 PvP feel; tune to taste. See `config.yml` for inline
documentation of every key. The high-level sections:

```yaml
async-hitreg:
  enabled: true
  max-cps: 20            # 0 disables the cap

knockback:
  enabled: true
  base: { horizontal: 0.4, vertical: 0.4 }
  extra: { horizontal: 0.5, vertical: 0.1 }
  limits: { vertical: 0.4, horizontal: -1 }
  friction: { x: 0.5, y: 0.5, z: 0.5 }
  modifiers: { sprint: 1.0, armor-resistance: false }

compensation:
  enabled: true
  ping-offset-ms: 25       # subtract from measured RTT
  spike-threshold-ms: 20   # ignore one-shot spikes > this delta
  probe-interval-ticks: 5  # KEEP_ALIVE probe cadence in combat
  combat-timeout-ticks: 30
  off-ground-sync: true    # experimental airborne compensation

debug:
  enabled: false
```

`/ss reload` reloads atomically — config is read into immutable `record`
snapshots and swapped via `AtomicReference`, so no hit ever sees a torn
configuration.

## Architecture

```
me.vexmc.strikesync
├─ StrikeSyncPlugin        Bootstrap; PacketEvents lifecycle (onLoad/onEnable/onDisable)
├─ core/
│   ├─ Module               Lifecycle interface (enable / disable / reload)
│   ├─ ModuleManager        Strict exception-isolated module orchestration
│   └─ StrikeSyncService    Plugin + ConfigManager + Logging + class-keyed export registry
├─ config/
│   ├─ ConfigManager        Atomic snapshot reload
│   ├─ HitRegSettings       (record)
│   ├─ KnockbackSettings    (record)
│   ├─ CompensationSettings (record)
│   └─ DebugSettings        (record)
├─ module/
│   ├─ hitreg/              Async INTERACT_ENTITY listener + per-UUID CPS limiter
│   ├─ knockback/           Pure 1.8 math + Bukkit listener composing on EntityDamage/PlayerVelocity
│   └─ compensation/        Native RTT (KEEP_ALIVE) + jitter + ground prediction → KB engine hint
├─ command/
│   ├─ CommandTree          Tree-based dispatcher with per-node permissions
│   └─ nodes/               Help / Authors / Reload / Toggle / Knockback / Compensation / Ping
├─ message/
│   ├─ Brand                StrikeSync gradient prefix
│   └─ Messages             Centralized Adventure components
├─ api/
│   └─ event/
│       └─ AsyncHitRegisterEvent  Cancellable, properly async
└─ util/
    └─ Logging              Level-gated wrapper around plugin logger
```

### How the modules compose without stepping on each other

```
   ┌────────────────────────────────────────────────┐
   │ ProtocolEvents netty loop                      │
   │   HitPacketListener                            │
   │   · INTERACT_ENTITY → ATTACK?                  │
   │   · CpsLimiter.tryAcquire()                    │
   │   · AsyncHitRegisterEvent (truly async)        │
   │   · pass-through to vanilla unless cancelled   │
   └─────────────────────┬──────────────────────────┘
                         │ vanilla applies hit on next tick
                         ▼
   ┌────────────────────────────────────────────────┐
   │ Bukkit main thread                             │
   │  EntityDamageByEntityEvent                     │
   │   ├─ HIGHEST: LatencyCompensationModule        │
   │   │   computes Hint(victimYOverride) and stashes│
   │   └─ MONITOR: KnockbackModule                  │
   │       reads hint, runs KnockbackEngine,        │
   │       stashes the result vector                │
   │                                                │
   │  PlayerVelocityEvent                           │
   │   └─ HIGH: KnockbackModule                     │
   │       applies stashed vector via setVelocity   │
   └────────────────────────────────────────────────┘
```

The compensator only adjusts the **input** to the engine — never the output.
This is by design (see [DESIGN.md](#why-the-compensator-doesnt-rewrite-the-output)
below).

### Why the compensator doesn't rewrite the output

Upstream knockback-sync was built for vanilla **1.9+** knockback and uses
constants like `0.36080000519752503` plus a knockback-resistance Y-subtraction
that are specific to that formula. Letting those constants overwrite our 1.8
engine would silently undo the configured `base.vertical / extras / friction`.

So compensation is a **pure input correction**: it replaces `victim.vy` with
the value the client believes it has at hit time (typically `0` when the
client thinks the victim is on the ground). The engine then computes
`0 × frictionY + base.vertical` — exactly the user's configured 1.8 hit, full
strength. 1:1.

## Public API

Other plugins can hook the cancellable `AsyncHitRegisterEvent` to veto hits
under arbitrary conditions (region checks, cooldowns, etc.). **Listeners run
on a packet worker thread; do not mutate Bukkit state directly.**

```java
@EventHandler
public void onHit(AsyncHitRegisterEvent event) {
    Player attacker = event.getAttacker();
    Damageable target = event.getTarget();
    if (!myRegionAllowsPvp(attacker, target)) {
        event.setCancelled(true);
    }
}
```

## Acknowledgements

The latency-compensation algorithm — RTT measurement strategy, IQR-filtered
jitter, spike protection, ground-state prediction, and gravity simulation —
is adapted from [caseload/knockbacksync](https://github.com/caseload/knockbacksync)
(GPL-3.0). Implementation here is original and integrates with our 1.8 engine
via input correction rather than output overwrite (see above).

The async-hit-registration concept was originally explored by
[frash23/SmashHit](https://github.com/frash23/SmashHit) for pre-1.10
Minecraft. StrikeSync rebuilds it from scratch on PacketEvents and adds the
cancellable async event.

## License

MIT. See [LICENSE](LICENSE).

# StrikeSync

> Off-tick hit registration, latency-compensated 1.8.x knockback, and a
> small modular framework for Paper / Minecraft 26.1.

[![Java](https://img.shields.io/badge/Java-21+-orange?style=flat-square)](https://adoptium.net/)
[![Paper](https://img.shields.io/badge/Paper-26.1.2-blue?style=flat-square)](https://papermc.io/)
[![PacketEvents](https://img.shields.io/badge/PacketEvents-2.12.1-purple?style=flat-square)](https://github.com/retrooper/packetevents)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

---

## What it does

StrikeSync wires three things together for modern Paper-based PvP:

1. **A fast hit-registration path.** Player attack packets are intercepted on
   PacketEvents' netty event loop. The plugin runs validation and a
   cancellable `AsyncHitRegisterEvent` async, then optionally sends the
   resulting velocity packet to the victim *directly from the netty thread* —
   skipping vanilla's next-tick dispatch and entity-tracker pulse. Damage
   itself is applied through Bukkit's normal event chain on the target's
   owning thread, so existing event-driven plugins keep working.

2. **A 1.8.x-style knockback engine.** A pure, configurable formula
   (base/extra horizontal & vertical, friction, sprint factor, knockback
   enchant, optional armor-resistance honoring) reproduces the legacy 1.8 PvP
   feel without NMS or reflection.

3. **Latency compensation.** Per-player RTT is measured natively via
   `KEEP_ALIVE` round-trips (nanoTime), spike-protected, and used to predict
   whether the victim's client thinks they are on the ground at hit time.
   The compensator publishes a hint with the client-expected vy, which the
   knockback engine consumes as **input** — the engine output is never
   overwritten, so a tuned 1.8 feel is preserved 1:1.

## Where the latency wins (and don't) come from

The honest accounting:

| Stage of vanilla's pipeline | Saving with StrikeSync |
|---|---|
| Netty receives `INTERACT_ENTITY` and queues it for the main thread | None — netty receive is already nanosecond-level. |
| Main thread runs `ServerGamePacketListenerImpl#handleInteract` (validation, range, gamemode) | Tens of µs (validation done async; vanilla's main-thread re-validation skipped when fast-path is on). |
| Main thread runs `Player#attack` → `hurt` → knockback → `setDeltaMovement` | None — damage runs on the main/owning thread either way. |
| Vanilla queues outbound `EntityVelocity` for the next entity-tracker pulse (default: every 2 ticks for players) | **25–100 ms typical**, when `pre-send-velocity` is enabled. The velocity packet ships from the netty thread the moment the hit is accepted. |

The pre-send option is what genuinely beats vanilla. Without it, the fast
path is at parity (or microseconds ahead) — useful as cleaner architecture,
not as a perceivable PvP improvement.

## Quick start

### Requirements

| Component    | Version                                |
| ------------ | -------------------------------------- |
| Paper        | `1.21.x` matching `api-version: 26.1.2`|
| Java         | 21+                                    |
| PacketEvents | `2.12.1` (installed as its own plugin) |

### Install

1. Download the latest [release jar](../../releases/latest).
2. Drop it (and the [PacketEvents](https://modrinth.com/plugin/packetevents)
   plugin) into the server's `plugins/` directory.
3. Start the server. A default `plugins/StrikeSync/config.yml` is generated.
4. (Optional) Edit the config to enable `async-hitreg.fast-path.pre-send-velocity`
   if the headline latency win is desired.

### Build from source

```bash
git clone https://github.com/owengregson/StrikeSync.git
cd StrikeSync
mvn clean package
# → target/StrikeSync-4.0.0.jar
```

## Commands

| Command                                      | Permission                        | Description                                |
| -------------------------------------------- | --------------------------------- | ------------------------------------------ |
| `/ss help`                                   | `strikesync.command.use`          | Show command list                          |
| `/ss authors`                                | `strikesync.command.use`          | Plugin authors / GitHub link               |
| `/ss reload`                                 | `strikesync.command.reload`       | Reload config & bounce modules             |
| `/ss toggle`                                 | `strikesync.command.toggle`       | Toggle async hit registration              |
| `/ss knockback <enable\|disable\|status>`    | `strikesync.command.knockback`    | Manage 1.8-style knockback module          |
| `/ss compensation <enable\|disable\|status>` | `strikesync.command.compensation` | Manage latency compensation module         |
| `/ss ping [player]`                          | `strikesync.command.ping`         | Show measured RTT, jitter, and spike state |

Alias: `/strikesync` ↔ `/ss`. Tab completion is permission-filtered.

## Configuration

Defaults reproduce 1.8 PvP feel with the latency-saving pre-send disabled.
See `config.yml` for inline documentation of every key. High-level sections:

```yaml
async-hitreg:
    enabled: true
    max-cps: 20
    fast-path:
        enabled: true              # cancel vanilla packet, run plugin pipeline
        pre-send-velocity: false   # send velocity from netty thread (headline win)
        simulate-crits: true       # 1.8-style crit multiplier
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
│   ├─ hitreg/
│   │   ├─ HitRegModule           Module + Bukkit listener + per-tick cache task
│   │   ├─ HitPacketListener      PacketEvents async INTERACT_ENTITY listener
│   │   ├─ CpsLimiter             Per-UUID sliding-window rate limiter
│   │   ├─ PlayerStateCache       Per-tick async-readable snapshots
│   │   ├─ DamageCalculator       1.8-style damage math
│   │   ├─ HitDispatcher          Folia-aware main-thread / region-thread dispatch
│   │   ├─ HitApplier             Main-thread damage application
│   │   └─ AsyncVelocitySender    Direct netty-thread WrapperPlayServerEntityVelocity
│   ├─ knockback/
│   │   ├─ KnockbackModule        Bukkit listener composing on EntityDamage / PlayerVelocity
│   │   ├─ KnockbackEngine        Pure 1.8 math (3 entry points; one private impl)
│   │   └─ KnockbackVector        Result record
│   └─ compensation/
│       ├─ LatencyCompensationModule  Hint provider for the engine
│       ├─ PingProbe                  KEEP_ALIVE-based RTT measurement
│       ├─ LatencyTracker             Per-player outstanding probes + sample history
│       ├─ JitterCalculator           IQR-filtered std-dev jitter
│       ├─ CombatTracker              Combat-tag bookkeeping
│       ├─ MotionMath                 Vanilla-gravity simulation
│       └─ GroundProbe                4-corner block raytrace
├─ command/                    Tree-based command framework with per-node permissions
├─ message/                    Centralized Adventure components
├─ api/event/AsyncHitRegisterEvent  Cancellable async public event
└─ util/Logging                Level-gated logger wrapper
```

### Hit pipeline (fast path with pre-send enabled)

```
                          PacketEvents netty event loop
client click ─► INTERACT_ENTITY ──► HitPacketListener
                                         │
                                         ├─ action == ATTACK ?
                                         ├─ CpsLimiter.tryAcquire ?
                                         ├─ async entity lookup
                                         ├─ AsyncHitRegisterEvent ── plugins veto?
                                         ├─ event.setCancelled(true)
                                         │
                       ┌─────────────────┤
                       │                 │
            [pre-send velocity]    [schedule damage]
                       │                 │
                       ▼                 ▼
   AsyncVelocitySender.send       HitDispatcher.dispatch
     (cached state +                (target.getScheduler().run)
      KnockbackEngine.computeFromCache)
                       │                 │
                       ▼                 ▼
   client receives velocity       owning thread:
   at T + RTT (~25–100 ms before    HitApplier.apply →
   vanilla would have)              victim.damage(amount, attacker) →
                                    EntityDamageByEntityEvent →
                                    KnockbackModule (latency hint hook) →
                                    PlayerVelocityEvent →
                                    KnockbackModule overrides velocity
```

The fast path is on by default; `pre-send-velocity` is opt-in.

### Why the compensator never overwrites the engine output

KnockbackSync (the algorithmic source) was built for vanilla **1.9+**
knockback and uses constants like `0.36080000519752503` plus a knockback-
resistance Y-subtraction step that are specific to that formula. Letting
those constants overwrite the 1.8 engine would silently undo every
configured `base.vertical / extras / friction`.

So compensation is a **pure input correction**: the victim's vy is replaced
with the value the client believes it has at hit time (typically `0` when
the client thinks the victim is on the ground). The engine then computes
`0 × frictionY + base.vertical` — exactly the configured 1.8 hit, full
strength.

## Public API

Other plugins can hook the cancellable `AsyncHitRegisterEvent` to veto hits
under arbitrary conditions (region checks, cooldowns, etc.). Listeners run
on a packet worker thread; **do not mutate Bukkit state directly** — schedule
back to the main thread via Paper's entity / region scheduler if needed.

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

## What's deliberately not implemented

The fast path bypasses vanilla's `Player#attack` and re-applies damage via
the Bukkit primitive. The following are deliberately omitted to keep the
implementation cross-version stable:

- **Sweep attack.** 1.9+ feature; the target audience is 1.8 PvP.
- **Weapon durability damage.** Skipped; the cost is small and most 1.8
  servers don't track it.
- **Player statistics & advancement triggers** (`DAMAGE_DEALT`, attack-related
  advancements). Bukkit's `damage` does not invoke these from a non-vanilla
  call path.
- **Critical-hit / sweep particles.** The damage indicator (heart particle)
  still fires from vanilla's hurt chain.

These can be added by a downstream plugin that listens for
`EntityDamageByEntityEvent` if needed.

## Acknowledgements

The latency-compensation algorithm — RTT measurement strategy, IQR-filtered
jitter, spike protection, ground-state prediction, and gravity simulation —
is adapted from [caseload/knockbacksync](https://github.com/caseload/knockbacksync)
(GPL-3.0). The implementation here is original, integrates with the 1.8
engine via input correction rather than output overwrite, and never uses
KnockbackSync's 1.9+-specific constants.

The async-hit-registration concept was originally explored by
[frash23/SmashHit](https://github.com/frash23/SmashHit) for pre-1.10
Minecraft. StrikeSync rebuilds it from scratch on PacketEvents and adds the
cancellable async event.

## License

MIT. See [LICENSE](LICENSE).

# Mental

## WARNING: This plugin is still in beta. MMC KB Recreation is WIP.

**Async Hit-reg, Ping compensation, & Legacy Knockback for modern Paper servers.**

Mental brings back the way PvP used to feel: 1.7/1.8 knockback, fishing rod hits, and snowball trades. It also registers hits faster than vanilla and evens out the playing field for players on higher ping.

[![Paper](https://img.shields.io/badge/Paper-1.17.1%20→%2026.1.2-blue?style=flat-square)](https://papermc.io/)
[![Folia](https://img.shields.io/badge/Folia-supported-purple?style=flat-square)](https://github.com/PaperMC/Folia)
[![Java](https://img.shields.io/badge/Java-17+-orange?style=flat-square)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

## Why Mental?

- **Attacks register faster.** Mental processes attacks asynchronously via the netty thread, meaning there is zero server latency for hit animations. On 1.19.4+ the velocity and hurt animation arrive bundled, in the same client frame.
- **W-taps register at any speed.** Sprint toggles are read in packet-arrival order, sub-tick — a w-tap or s-tap counts even when it lands in the same tick as the follow-up hit, where vanilla quantizes both to tick boundaries. The fastest sprint-reset detection physically possible from the server side.
- **Knockback uses 1:1 replicated 1.7/1.8 formula**, line for line. Sprint hits, Knockback enchantment bonuses, the exact vertical behavior, critical hits and Sharpness damage.
- **Combos work like 1.7.10.** The server never wipes a victim's knockback residual between hits, so quick successive hits stack and launch — the mechanical core of legacy combo PvP. Fishing rods and projectiles knock away from where the shooter stands, like they used to.
- **Knockback profiles.** One file per feel under `plugins/Mental/profiles/` — `legacy-1.7` (the default), `legacy-1.8`, the archived configs of the era's best servers (`kohi`, `minehq`, `badlion`, `velt`, `mmc`, `lunar` — real archived values, not remakes), or your own — assignable per server, per world, and per player at runtime (`/mental kb`, or the API for practice cores).
- **The "Ping Problem" is fixed.** Mental measures each player's connection during combat and corrects the knockback they receive, so getting hit feels the same on 20 ms as it does on 150 ms.
- **Fishing rods restored to legacy mechanics.** Hooks damage and shove players on contact, casts fly like in 1.8, and the reel-in pull is gone.
- **Projectiles knock back.** Snowballs, eggs and ender pearls push players.
- **Anticheat compatible.** Mental supports and recognizes common anticheats like GrimAC and Vulcan. Mental is also compatible with most others by design. Servers running none can opt into Mental's own ping-rewound reach validation.

## Compatibility

| | |
| --- | --- |
| **Server** | Paper 1.17.1 → 26.1.2 · Folia 1.19.4+ |
| **Java** | 17 or newer |
| **Dependencies** | None |

Mental plugin jars are universal and work on all supported versions.

## Installation

1. Download `Mental-<version>.jar` from the [releases page](../../releases).
2. Drop it into your server's `plugins/` folder.
3. Restart.

The defaults already give you the classic combat. The configuration is split by concern under `plugins/Mental/` — every option is explained in its file.

## Commands

Use **`/mental`** (or `/mtl`). It opens an interactive dashboard in chat where you can click to toggle and hover for what each module does.

| Command | What it does |
| --- | --- |
| `/mental` | The dashboard. View and toggle every module by clicking. |
| `/mental module <name> <on\|off\|status>` | Toggle a module from console or scripts (saved to config). |
| `/mental kb` | Knockback profiles: list, `info <profile>`, `set <profile> [player]`, `reset [player]`. |
| `/mental ping [player]` | A player's measured ping, jitter and connection quality. |
| `/mental reload` | Apply config changes without a restart. Safe mid-combat. |
| `/mental debug <on\|off>` | Verbose logging for troubleshooting (see the FAQ). |
| `/mental debug category <name> <on\|off>` | Toggle one of the ten debug categories individually. |
| `/mental debug subscribe` | Stream debug lines to your in-game chat. |
| `/mental version` | Version, server platform and feature report. |
| `/mental help` | Clickable list of every command you can use. |

Module names for the `module` command: `hit-registration`, `wtap-registration`, `knockback`, `latency-compensation`, `fishing-knockback`, `rod-velocity`, `projectile-knockback`.

## Configuration

Split by concern under `plugins/Mental/`, every option explained in its file:

| File | What lives there |
| --- | --- |
| `config.yml` | The control panel: module switches, anticheat policy, OCM coordination, debug. |
| `knockback.yml` | Which profile applies where (default + per-world), plus rod, fishing and projectile mechanics. |
| `hit-registration.yml` | The async hit pipeline, its fast path, and reach validation. |
| `latency-compensation.yml` | Ping-aware knockback correction. |
| `profiles/*.yml` | One knockback feel per file. Presets regenerate when deleted; your edits stay. |

```yaml
# knockback.yml — pick the feel
knockback:
  profile: legacy-1.7        # legacy-1.7 · legacy-1.8 · kohi · minehq ·
                             # badlion · velt · mmc · lunar · custom
  per-world:
    duels: kohi              # optional per-world overrides
```

Profiles are the whole engine knob set — base/extra pushes, friction, vertical assign-vs-add, distance taper, air multipliers, w-tap bonuses and more. The guide (with the preset provenance and the fork-porting hazard table) is [docs/knockback-profiles.md](docs/knockback-profiles.md).

`/mental reload` applies changes instantly, and a fight in progress never sees a half-applied config. A v1 single-file `config.yml` migrates automatically on first start: tuned knockback values become `profiles/custom.yml` and stay selected, and the original file is kept as `config-v1-backup.yml`.

## FAQ

**Will it clash with my anticheat?**
GrimAC and Vulcan are detected automatically, and Mental adjusts the one behavior their movement prediction would object to. Hits still register at full speed. Running something else? Set `anticheat.mode: force-safe` and Mental behaves the same way unconditionally.

**Will it clash with WorldGuard, GriefPrevention or my combat logger?**
No. Mental deals damage through the standard Bukkit event chain, so anything that cancels or modifies damage keeps working exactly as before.

**Does it remove the 1.9 attack cooldown?**
No, and that's deliberate. Mental never scales damage by charge, so the cooldown has no effect on hits Mental registers — but the meter itself, sword blocking, regen and the rest of the combat *rules* are a different plugin's job. Pair Mental with [OldCombatMechanics](https://github.com/kernitus/BukkitOldCombatMechanics) (its `disable-attack-cooldown` module) for those; the two are built to run together.

**Can I run it alongside OldCombatMechanics?**
Yes — that's the intended setup for a complete 1.8-style server. OCM handles the combat rules, Mental handles knockback and hit delivery, and where OCM's own knockback/fishing/projectile modules are enabled, Mental detects it and steps aside automatically, per player modeset, so nothing is ever applied twice. Disable those OCM modules to get Mental's combos, positional projectiles and ping compensation instead. The full ownership table is in [docs/ocm-coexistence.md](docs/ocm-coexistence.md).

**Why doesn't the projectile module do anything on my 1.21.2+ server?**
Because it doesn't need to. Mojang restored projectile knockback against players in vanilla 1.21.2.

**Is Folia supported?**
Yes, Mental is natively region-aware and localized.

**Something feels off. How do I see what's happening?**
`/mental debug on`, then `/mental debug subscribe` to stream diagnostics to your chat in-game. Ten categories can be toggled individually so you only see the system you're chasing. If it looks like a bug, [open an issue](../../issues) with what you find.

## For developers

### How it's put together

Mental is a multi-module Gradle build:

| Module | Purpose |
| --- | --- |
| `api` | The small public API other plugins compile against |
| `common` | Pure logic with no Bukkit dependency: combat math, config model, command tree |
| `core` | The plugin itself: combat modules, packet layer, Bukkit wiring |
| `compat-folia` | Folia schedulers, loaded only when Folia is detected |
| `compat-brigadier` | Native command registration, loaded only on 1.20.6+ |
| `tester` | The in-server integration test harness |

The wide version range works without reflection on hot paths: `core` compiles against the oldest supported API (1.17), which makes the common path binary-safe everywhere, and anything newer lives in the `compat-*` modules behind runtime feature detection. The two binary breaks in the range (the 1.21.3 `Attribute` change and the 1.20.5 enchantment renames) are absorbed by small boot-time resolvers.

If you're curious how a hit travels from packet to knockback, [docs/fast-path.md](docs/fast-path.md) walks the whole pipeline. For what "1.7.10 combat" means precisely — the residual ledger behind combos, the legacy damage tables, and the few client-side mechanics no server can mirror — see [docs/legacy-combat.md](docs/legacy-combat.md). The knockback-profile system (presets, provenance, the divisor↔multiplier porting hazard) is in [docs/knockback-profiles.md](docs/knockback-profiles.md), with the research behind it in [docs/research/](docs/research/). How Mental divides combat with OldCombatMechanics (and proves it on live servers) is in [docs/ocm-coexistence.md](docs/ocm-coexistence.md).

### API

Two events and a small facade, under `me.vexmc.mental.api`. Add the Mental jar to your compile classpath and `softdepend: [Mental]` to your plugin.yml.

```java
// Veto hits before they happen (fires on a packet thread — keep it fast):
@EventHandler
public void onHit(AsyncHitRegisterEvent event) {
    if (!allowed(event.getAttacker(), event.getTarget())) {
        event.setCancelled(true);
    }
}

// Adjust or cancel knockback right before it is applied:
@EventHandler
public void onKnockback(KnockbackApplyEvent event) {
    event.velocity(event.velocity().multiply(0.8));
}

// Query Mental's state:
OptionalDouble ping = Mental.get().pingMillis(player);

// Pin a duel to a knockback profile (practice cores):
Mental.get().setKnockbackProfile(victim, "kohi");   // fires PlayerKnockbackProfileChangeEvent
```

### Building and testing

```bash
./gradlew build                    # compile + unit tests → core/build/libs/Mental-<version>.jar
./gradlew integrationTest          # boots real Paper servers (oldest + newest supported)
./gradlew integrationTestMatrix    # the full seven-version matrix
```

The integration tests are the interesting part. They start actual Paper servers, spawn synthetic players, throw real punches, and assert that the resulting velocity matches the 1.8 math to three decimal places. Gradle provisions the right JDK for each server version automatically, so a plain clone and `./gradlew build` is all you need.

Contributions are welcome. For anything bigger than a bugfix, open an issue first so we can talk it through.

## Credits

Mental is built on the following:

- [knockback-sync](https://github.com/CASELOAD7000/knockback-sync) — the latency-compensation approach
- [BukkitOldCombatMechanics](https://github.com/kernitus/BukkitOldCombatMechanics) — 1.8 rod and projectile mechanics, and the real-server testing approach
- [SmashHit](https://github.com/frash23/SmashHit) — the async hit-registration concept
- [PacketEvents](https://github.com/retrooper/packetevents) — the packet layer (bundled inside the jar)

## License

MIT. See [LICENSE](LICENSE).

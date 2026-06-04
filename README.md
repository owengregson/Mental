# Mental

**Classic 1.8 combat for modern Paper servers.**

Mental brings back the way PvP used to feel: the knockback, the rod hits, the snowball trades. It also registers hits faster than vanilla and evens out the playing field for players on higher ping. One jar runs on every Paper version from 1.17.1 through 26.1.2, Folia included, with no dependencies.

[![Paper](https://img.shields.io/badge/Paper-1.17.1%20→%2026.1.2-blue?style=flat-square)](https://papermc.io/)
[![Folia](https://img.shields.io/badge/Folia-supported-purple?style=flat-square)](https://github.com/PaperMC/Folia)
[![Java](https://img.shields.io/badge/Java-17+-orange?style=flat-square)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

## Why Mental?

If you run a PvP server, you know the problem. Modern Minecraft combat doesn't feel like 1.8, and it's not just the attack cooldown: knockback behaves differently, fishing rods stopped being a weapon, snowballs and eggs do nothing, and a player on 150 ms ping fights at a disadvantage that has nothing to do with skill.

Mental fixes the whole picture:

- **Hits land faster.** A vanilla server sits on every attack for a couple of game ticks before the victim feels anything. Mental processes attacks the moment they arrive, shaving roughly 25–100 ms off every hit. Combat feels crisp and immediate, especially up close.
- **Knockback is the real 1.8 formula**, not an approximation. Sprint hits, Knockback enchantment bonuses, the exact vertical behavior, 1.8 critical hits and Sharpness damage. Fights feel the way veterans remember them.
- **Ping doesn't decide fights.** Mental quietly measures each player's connection during combat and corrects the knockback they receive, so getting hit feels the same on 20 ms as it does on 150 ms.
- **Fishing rods are weapons again.** Hooks damage and shove players on contact, casts fly like they did in 1.8, and the reel-in pull that ruins rod fights is gone.
- **Projectiles knock back.** Snowballs, eggs and ender pearls push players around like they used to.
- **Your anticheat keeps working.** Mental recognizes GrimAC and Vulcan on its own and stays inside what their movement checks expect. No false flags, no exemptions, nothing to set up.
- **Your other plugins keep working.** Every hit still flows through the normal Bukkit damage events, so protection plugins, region flags and combat loggers all see ordinary combat.

## Compatibility

| | |
| --- | --- |
| **Server** | Paper 1.17.1 → 26.1.2 · Folia 1.19.4+ |
| **Java** | 17 or newer |
| **Dependencies** | None. Everything Mental needs ships inside the jar. |

The same jar works on every supported version. Each release is verified by actually booting servers across the range and checking the combat behavior in-game, not just by compiling against the API.

## Installation

1. Download `Mental-<version>.jar` from the [releases page](../../releases).
2. Drop it into your server's `plugins/` folder.
3. Restart.

That's the whole setup. The defaults already give you the classic 1.8 feel; if you want to tune anything, a fully documented config appears at `plugins/Mental/config.yml`.

## Commands

Start with **`/mental`** (or `/mtl`). It opens an interactive dashboard in chat: every module with its live status, click to toggle, hover for what each one does. Most owners never need anything else.

| Command | What it does |
| --- | --- |
| `/mental` | The dashboard. View and toggle every module by clicking. |
| `/mental module <name> <on\|off\|status>` | Toggle a module from console or scripts (saved to config). |
| `/mental ping [player]` | A player's measured ping, jitter and connection quality. |
| `/mental reload` | Apply config changes without a restart. Safe mid-combat. |
| `/mental debug <on\|off>` | Verbose logging for troubleshooting (see the FAQ). |
| `/mental version` | Version, server platform and feature report. |
| `/mental help` | Clickable list of every command you can use. |

Module names for the `module` command: `hit-registration`, `knockback`, `latency-compensation`, `fishing-knockback`, `rod-velocity`, `projectile-knockback`.

By default, regular players can open the dashboard and check ping; anything that changes state needs op or the matching `mental.command.*` permission. `mental.*` grants everything.

One quirk worth knowing: on Paper 1.20.6 and newer, Mental registers its commands natively, so clients validate arguments as you type. Paper disables the old `/reload` command for servers using that system. Use `/mental reload` for config changes (you should anyway).

## Configuration

Everything lives in `plugins/Mental/config.yml`, and every option is explained right there in the file, so this section stays short. A few knobs people ask about:

```yaml
modules:
  knockback:
    base: {horizontal: 0.4, vertical: 0.4}   # the classic 1.8 numbers
    modifiers: {sprint: 1.0}                 # scale sprint-hit knockback

  latency-compensation:
    enabled: true                            # ping-aware knockback correction

  hit-registration:
    max-cps: 20                              # clicks per second before extras are ignored

anticheat:
  mode: auto                                 # back off automatically when an anticheat is present
```

`/mental reload` applies changes instantly, and a fight in progress never sees a half-applied config.

## FAQ

**Will it clash with my anticheat?**
GrimAC and Vulcan are detected automatically, and Mental adjusts the one behavior their movement prediction would object to. Hits still register at full speed. Running something else? Set `anticheat.mode: force-safe` and Mental behaves the same way unconditionally.

**Will it clash with WorldGuard, GriefPrevention or my combat logger?**
No. Mental deals damage through the standard Bukkit event chain, so anything that cancels or modifies damage keeps working exactly as before.

**Does it remove the 1.9 attack cooldown?**
Effectively, yes: Mental resets the cooldown on every hit, so the recharge meter never weakens 1.8-style click fighting. (Toggleable via `reset-attack-cooldown` if you want cooldowns kept.)

**Why doesn't the projectile module do anything on my 1.21.2+ server?**
Because it doesn't need to. Mojang restored projectile knockback against players in vanilla 1.21.2, so Mental steps aside there instead of doubling it up.

**Does it really run on Folia?**
Yes, natively. Mental was built region-aware from the start rather than patched for it afterwards.

**Does it phone home?**
Only anonymous usage stats via [bStats](https://bstats.org/plugin/bukkit/Mental/31788), the standard for Bukkit plugins. Disable it globally in `plugins/bStats/config.yml` if you prefer.

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

If you're curious how a hit travels from packet to knockback, [docs/fast-path.md](docs/fast-path.md) walks the whole pipeline.

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

Mental stands on prior art:

- [knockback-sync](https://github.com/CASELOAD7000/knockback-sync) — the latency-compensation approach
- [BukkitOldCombatMechanics](https://github.com/kernitus/BukkitOldCombatMechanics) — 1.8 rod and projectile mechanics, and the real-server testing approach
- [SmashHit](https://github.com/frash23/SmashHit) — the async hit-registration concept
- [PacketEvents](https://github.com/retrooper/packetevents) — the packet layer (bundled inside the jar)

## License

MIT. See [LICENSE](LICENSE).

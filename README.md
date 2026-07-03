# Mental

**Latency-compensated 1.7 / 1.8 combat for modern Paper & Folia.**

Mental brings back the way PvP used to feel — 1.7/1.8 knockback, real combos, fishing-rod hits and snowball trades — while registering hits *faster* than vanilla and levelling the field for players on higher ping.

[![Release](https://img.shields.io/github/v/release/owengregson/Mental?style=flat-square&label=release&color=brightgreen)](../../releases)
[![Paper](https://img.shields.io/badge/Paper-1.9.4%20→%2026.x-blue?style=flat-square)](https://papermc.io/)
[![Folia](https://img.shields.io/badge/Folia-supported-purple?style=flat-square)](https://github.com/PaperMC/Folia)
[![Java](https://img.shields.io/badge/Java-17+-orange?style=flat-square)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

> **Stable release.** Mental is out of beta — verified across the full Paper 1.9.4 → 26.x range and on Folia, with a live integration matrix on every commit. Drop it in and play.

---

## Highlights

- **Hits register faster than vanilla.** Attacks are processed on the netty thread, so there's zero server-tick latency on hit feedback. On 1.19.4+ the velocity and hurt animation arrive bundled in the same client frame.
- **Real 1.7/1.8 knockback**, replicated line-for-line: sprint hits, Knockback-enchant bonuses, the exact vertical behaviour, crits and Sharpness damage.
- **Combos work like 1.7.10.** The server never wipes a victim's knockback residual between hits, so fast successive hits stack and launch — the mechanical core of legacy combo PvP.
- **W-taps register at any speed.** Sprint toggles are read in packet-arrival order, sub-tick — a w-tap counts even in the same tick as the follow-up hit. The fastest sprint-reset detection physically possible server-side.
- **The "ping problem" is fixed.** Mental measures each player's connection mid-fight and corrects the knockback they receive, so getting hit feels the same at 150 ms as at 20 ms.
- **Legacy rods & projectiles.** Fishing hooks shove players on contact and cast like 1.8; snowballs, eggs and pearls knock back — all away from where the shooter stands, like they used to.
- **Knockback profiles.** One file per feel — the archived configs of the era's best servers, plus Mental's own. Swap the whole game's feel from an in-game menu. (See [recommended presets](#recommended-presets).)
- **Anticheat-friendly.** GrimAC and Vulcan are detected and accommodated automatically; most others work by design. Running none? Opt into Mental's own ping-rewound reach check.
- **Optional full 1.8 ruleset.** 16 combat-rule modules (all default OFF) let you build a complete 1.8 server with no companion plugin. See [combat rules modules](#combat-rules-modules-optional).

## Recommended presets

Mental ships the **real archived knockback configs** of the era's best servers (measured from server archives, not remakes) — plus `signature`, Mental's own. These are the ones we think feel best:

| Preset | The feel |
| --- | --- |
| **`signature`** | **Mental's own.** velt's dead-consistent wipe, tuned so combo'd players hold the perfect reach pocket instead of drifting out of range. The pick for modern combo PvP. |
| **`lunar`** | Lunar Network's archived Season 5 — heavy base and a controlled, grounded "hold-W" feel, exactly as the era played it. |
| **`badlion`** | Badlion's archived NoDebuff values — soft base, strong sprint differential, true 1.7 ledger combos. W-tapping decides trades. |
| **`velt`** | VeltPvP's archived HCF values — a near-total residual wipe, so every hit lands identically. The "dead consistent" practice feel. |

Pick one in-game under **`/mental` → Knockback**, or set `knockback.profile` in `knockback.yml`. The full list (`legacy-1.7`, `legacy-1.8`, `kohi`, `minehq`, `badlion`, `velt`, `mmc`, `lunar`, `signature`, `custom`) with provenance is in the [profile guide](docs/knockback-profiles.md).

## Compatibility

| | |
| --- | --- |
| **Server** | Paper 1.9.4 → 26.x · Folia 1.19.4+ |
| **Java** | 17 or newer — **required**, including on legacy servers (see below) |
| **Dependencies** | None — Mental is a self-contained 1.8 combat suite. OldCombatMechanics is optional. |

One universal jar runs the whole version range.

**Running a legacy server (1.9.4–1.16.5)?** Mental ships Java 17 classfiles, so the server itself must run on **Java 17 or newer** — the same JVM that loads the plugin, not just your own machine. Paper 1.9.4–1.12.2 boot on Java 17 with no extra flags; **Paper 1.13.2, 1.15.2 and 1.16.5** need `-DPaper.IgnoreJavaVersion=true` added to the server's own JVM arguments (those builds carry a soft Java-version guard that this flag bypasses). **Paper 1.14.4 is the one gap in the range** — its terminal build hard-caps at Java 13 with no bypass, so a Java-17 plugin cannot load on any 1.14.4 server on a modern JVM; every other 1.9–1.16 version is covered.

### Per-version coverage

Every supported version runs a live suite on a real Paper server in the release gate, fresh-nonce verified on each build:

| Version | Coverage |
| --- | --- |
| **1.11.2 – 1.16.5** | Full era suite |
| **1.17.1 → 26.x** | Full era suite |
| **1.9.4, 1.10.2** | Full era suite ¹ |
| **Folia 1.19.4+** | Boot suite + same-region combat smoke |
| ~~1.14.4~~ | Not supported (Java-13 hard cap) |

¹ On 1.9.4 and 1.10.2 the suite runs in full but explicitly **skips eight trajectory/flight assertions** (loud in the log, never silent): a clientless test player's motion is not server-integrated before 1.11, so the harness cannot fly a connectionless victim to measure its arc. The knockback **values** are fully pinned on both versions — the skips are a test-harness limit, not a gameplay one, and real clients are unaffected. Additionally, **1.9.4 alone** has no thrown-projectile knockback (snowball, egg, ender-pearl): the `ProjectileHitEvent#getHitEntity` API used to attribute the hit to its victim is absent there. Arrow and Punch-enchant knockback work on every legacy version.

## Installation

1. Download `Mental-<version>.jar` from the [releases page](../../releases).
2. Drop it into your server's `plugins/` folder.
3. Restart.

That's it — the defaults already give you the classic combat. Everything else is tunable in-game or in the config files.

## Management

Run **`/mental`** (or `/mtl`) to open one unified, hand-designed menu — it works on every supported version and on Folia. Changes apply atomically the instant you click: no restart, safe mid-combat.

The dashboard shows a live status plate (version, platform, scheduling backend, active profile, modules, anticheat & OCM posture) and one screen per area:

- **Knockback** — pick the server-wide profile (the active one glows, each tile previews its values) and toggle the sources (fishing, projectile, rod).
- **Hit Registration · Combat Rules · Damage · Potions & Food · Player** — flip any module on or off live.
- **Compatibility** — cycle the anticheat posture and OldCombatMechanics coordination.
- **Debug** — toggle any of ten verbose-logging channels and stream them to your chat.

Prefer YAML? Editing the files by hand still works — the one surviving command is **`/mental reload`** (the console can't open a menu), mirrored by the dashboard's reload button.

> **Knockback is global.** A profile applies to the whole server at once (an optional per-world map in `knockback.yml` is still honoured). There's no per-player profile — the old `/mental kb set <player>` override was removed in 2.1.0.

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
  profile: signature         # legacy-1.7 · legacy-1.8 · kohi · minehq · badlion
                             # · velt · mmc · lunar · signature · custom
  per-world:
    duels: kohi              # optional per-world overrides
```

A profile is the whole engine knob set — base/extra pushes, friction, vertical assign-vs-add, distance taper, air multipliers, w-tap bonuses and more. `/mental reload` applies changes instantly, and a fight in progress never sees a half-applied config. A pre-2.0 single-file `config.yml` migrates automatically on first start (your tuned values become `profiles/custom.yml`; the original is kept as `config-v1-backup.yml`).

## Combat rules modules (optional)

Mental ships 16 combat-rule modules ported from OldCombatMechanics, **all default OFF** — an untouched config behaves exactly as vanilla-plus-Mental-knockback (zero-touch). Enable what you want under `modules:` in `config.yml` to build a full 1.8 server with no companion plugin.

| Config id | What it does |
| --- | --- |
| `attack-cooldown` | Removes the 1.9 attack cooldown and spoofs `attack_speed` so the charge meter and greyed-swing vanish client-side (server attribute untouched). |
| `disable-attack-sounds` | Silences the 1.9 swing-result sounds. |
| `disable-sword-sweep` | Disables the 1.9 sweep attack and its particle. |
| `disable-crafting` | Makes configured items uncraftable (default: SHIELD). |
| `disable-offhand` | Blocks off-hand use; supports a whitelist/blacklist item filter. |
| `old-golden-apples` | 1.8 golden/notch apple effects and the 8-gold-block notch recipe. |
| `disable-enderpearl-cooldown` | Removes the 1.9 ender-pearl throw cooldown. |
| `old-player-regen` | The 1.8 natural regeneration model. |
| `old-armour-strength` | 1.8 flat armour reduction (4 % per point, no toughness). |
| `old-armour-durability` | The 1.8 armour Unbreaking durability behaviour. |
| `old-potion-durations` | 1.8 potion durations. |
| `old-potion-values` | 1.8 Strength/Weakness damage values (applies on Mental's fast-path hits). |
| `old-critical-hits` | Era crit rule for non-fast-path melee. |
| `old-tool-durability` | Weapon durability on Mental's fast-path hits. |
| `sword-blocking` | 1.7-style right-click sword blocking (a data-component pose on 1.21+, off-hand-shield fallback on 1.17.1–1.20.6). |
| `old-hitboxes` | Era melee reach + hitbox margin (entity attribute on 1.20.5+, `AttackRange` component on 1.21.5+; no-op below). |

All 16 are Folia-correct. They're **OCM-agnostic** — if you run OldCombatMechanics too, enable each rule in *one* plugin only, or it double-applies.

## FAQ

**Will it clash with my anticheat?**
GrimAC and Vulcan are detected automatically and Mental adjusts the one behaviour their movement prediction would object to — hits still register at full speed. Running something else? Set `anticheat.mode: force-safe`.

**Will it clash with WorldGuard, GriefPrevention or my combat logger?**
No. Mental deals damage through the standard Bukkit event chain, so anything that cancels or modifies damage keeps working.

**Does it remove the 1.9 attack cooldown?**
Enable the `attack-cooldown` module (default OFF). It removes the cooldown *and* hides the charge meter and greyed swing client-side. Mental never scales damage by charge anyway, so hits already feel instant — the module just cleans up the visual.

**Can I run without OldCombatMechanics?**
Yes — Mental's 16 optional modules cover the full 1.8 ruleset, so it's a self-contained 1.8 combat suite.

**Can I run it *alongside* OldCombatMechanics?**
Yes. Mental detects OCM and auto-yields (via `OcmGate`) for the mechanics OCM owns — knockback, fishing and projectiles step aside per player modeset so nothing applies twice. The new rules modules are OCM-agnostic, though, so pick one plugin per rule. Full ownership table in [docs/ocm-coexistence.md](docs/ocm-coexistence.md).

**Why doesn't the projectile module do anything on my 1.21.2+ server?**
Because it doesn't need to — Mojang restored projectile knockback against players in vanilla 1.21.2.

**Is Folia supported?**
Yes — Mental is natively region-aware.

**Something feels off. How do I see what's happening?**
Open `/mental` → **Debug**, enable logging and click **Receive in chat** to stream diagnostics in-game. If it looks like a bug, [open an issue](../../issues) with what you find.

## For developers

Mental is a multi-module Gradle build:

| Module | Purpose |
| --- | --- |
| `api` | The small public API other plugins compile against |
| `common` | Pure logic, no Bukkit dependency: combat math, config model, command tree |
| `core` | The plugin itself: combat modules, packet layer, Bukkit wiring |
| `compat-folia` | Folia schedulers, loaded only when Folia is detected |
| `compat-brigadier` | Native command registration, loaded only on 1.20.6+ |
| `tester` | The in-server integration test harness |

`core` compiles against the Paper 1.17.1 API floor, so the common path is binary-safe across the modern range; anything newer lives in a `compat-*` module behind runtime feature detection. Support reaches down to Paper 1.9.4 at *runtime* — every API absent below the 1.17.1 compile floor is reached through a boot-time resolver that picks the era-correct fallback and prints its choice in the boot report (the same pattern that absorbs the 1.21.3 `Attribute` change and the 1.20.5 enchantment renames). Legacy material and text names are normalised once at the platform seam, so the kernel's vocabulary stays modern and version-blind.

Deeper reading: [docs/fast-path.md](docs/fast-path.md) (packet → knockback pipeline), [docs/legacy-combat.md](docs/legacy-combat.md) (what "1.7.10 combat" means precisely), [docs/knockback-profiles.md](docs/knockback-profiles.md) (presets, provenance, the divisor↔multiplier porting hazard), [docs/ocm-coexistence.md](docs/ocm-coexistence.md).

### API

Two events and a small facade, under `me.vexmc.mental.api`. Add the Mental jar to your compile classpath and `softdepend: [Mental]` to your `plugin.yml`.

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

// Query and control Mental's state (knockback is server-wide):
OptionalDouble ping = Mental.get().pingMillis(player);
Mental.get().setKnockbackProfile("kohi");   // fires KnockbackProfileChangeEvent
```

### Building and testing

```bash
./gradlew build                    # compile + unit tests → core/build/libs/Mental-<version>.jar
./gradlew integrationTest          # boots real Paper servers (oldest + newest supported)
./gradlew integrationTestMatrix    # the full version matrix
```

The integration tests start actual Paper servers, spawn synthetic players, throw real punches and assert the resulting velocity matches the 1.8 math to three decimals. Gradle provisions the right JDK per server version automatically, so a plain clone and `./gradlew build` is all you need.

Contributions are welcome. For anything bigger than a bugfix, open an issue first so we can talk it through.

# OCM → Mental Standalone Combat Suite — Program Roadmap

> **For agentic workers:** This is the PROGRAM index, not an executable plan. Each
> subsystem below has (or will have) its own complete-code plan under
> `docs/superpowers/plans/2026-06-14-ocm-<subsystem>.md`. Execute those with
> `superpowers:subagent-driven-development` or `superpowers:executing-plans`.

**Goal:** Make Mental a self-sufficient 1.7/1.8 combat plugin by porting the
OldCombatMechanics (OCM) feature set as optional, Folia-correct `CombatModule`s —
so a server can run authentic legacy combat with Mental alone, no OCM required.

**Architecture:** Each ported feature is a new `CombatModule` (registry-owned
lifecycle, `listen()` auto-unhook), **default OFF** (zero-touch), reading an
immutable per-area `Settings` record from the atomic config `Snapshot`. All
entity work flows through `Scheduling.runOn/repeatOn` (Folia region-correct).
Version-gated behavior is decided once at module enable via `Capabilities` +
platform resolvers (`Attributes`, `Enchantments`, and new resolvers as needed),
never by version-string branching on the hot path. Pure math/era formulas live
in their own classes with hand-computed unit pins, mirroring `DamageCalculator`,
`RodLaunchMath`, `KnockbackEngine`.

**Tech Stack:** Java 17 (release target), Paper API floor 1.17.1, PacketEvents
(shaded `me.vexmc.mental.lib.packetevents`), Folia schedulers via `compat-folia`,
Gradle multi-module. Range: Paper/Folia 1.17.1 → 26.x.

---

## Owner decisions (2026-06-14)

1. **Standalone, OCM-agnostic.** New modules do **not** wire to `OcmGate` and do
   **not** yield to OCM. The "pair with OCM for rules" positioning is dropped.
2. **Keep the existing `OcmGate` mechanism in place** (it governs the *already
   shipped* knockback/projectile/fishing/tool-damage coexistence and is inert in
   `ABSENT` mode). Ripping it out is a separate destructive refactor — explicitly
   **out of scope** for this program unless the owner later requests it. Action
   here is limited to **docs/positioning**: update README + CLAUDE.md to describe
   Mental as standalone-capable.
3. **Full extra scope** beyond the original 7: sweep removal (+particles),
   golden/notch apple, ender-pearl cooldown, potion effects, armour durability,
   old-crit fast-path-off coverage, player regen, attack sounds, disable-crafting.
4. **Hitboxes: deepest server-side fidelity** via NMS, accepting version-specific
   reflection risk — with honest documentation of the client-authoritative limit.
5. **Attack cooldown: gone functionally AND visually.** Remove the residual
   cooldown at a low level and **fully suppress the client-side cooldown
   animation** (client-only attack-speed spoof — server attribute untouched).

## Already-built (verified in the audit — NOT re-implemented)

- **Old bow/arrow knockback** — `ProjectileKnockbackModule` (era base from shooter
  position, Punch enchant, 1.21.2 flight→positional restore, snowball/egg/pearl).
  Only gap: trident KB (out of scope unless requested).
- **Old tool damage + old sharpness + old crit rule** — `DamageCalculator`, but
  **only on the fast path** (player-vs-player melee Mental registers). The
  event-based path for mob/projectile/environmental and fast-path-off is the gap
  (covered by Plan 5).
- **Cooldown's damage effect** — already void: the fast path cancels the vanilla
  attack and applies damage directly, so the 1.9 charge never scales a Mental hit.
  Plan 2 handles only the *residual cooldown + client animation*.

## Binding Folia constraints (from the official threading docs)

These govern every task; a violation is silent data corruption, not a crash.

- No main thread. Events fire on the **owning region's** thread. For an
  `EntityDamageEvent`/`EntityDamageByEntityEvent`, that thread owns the **VICTIM**
  — so reading/writing the victim (setDamage, setVelocity, noDamageTicks, armour,
  potions) is safe **inline**. The **attacker may be in another region**: read
  only frozen primitives, or hop with `attacker.getScheduler()`.
- All entity/inventory/attribute/item-meta mutation must run on that entity's
  owning thread. Inside the entity's own event → safe; otherwise
  `Scheduling.runOn(entity, …)` (Folia `EntityScheduler`).
- Delayed restores (sword-block revert): `EntityScheduler.runDelayed` via
  `Scheduling` — follows teleports, fires the `retired` callback on logout. Never
  `BukkitScheduler.runTaskLater`.
- Repeating per-entity work (regen): `EntityScheduler.runAtFixedRate` via
  `Scheduling.repeatOn`. **Never** iterate `world.getLivingEntities()`/all players
  in one task and mutate them — each may be owned by a different region.
- Netty-thread code reads ONLY frozen snapshots (`PlayerStateCache`); never live
  entities. Packet sends to a possibly-reconfiguring client wrap-and-drop.
- Store every `TaskHandle` and cancel it in `onDisable()` (tasks are NOT
  auto-cleaned the way `listen()` listeners are).

## Shared foundation (prerequisite for all plans) — Plan 0

`plugin.yml` already declares `folia-supported: true`. The repeated wiring each
new module needs (this is the pattern, detailed per-module in each sub-plan):

1. **Config toggle**: add the module id to `config.yml`'s `modules:` map,
   **default `false`** (new modules are off until enabled — zero-touch).
2. **Settings record** under `core/.../config/`: immutable record + `static final
   DEFAULTS` (era-exact no-op) + `static parse(boolean enabled, ConfigReader)`.
   Reads via `ConfigReader` (`flag/intAtLeast/number/oneOf/sub`), never raw YAML.
3. **Snapshot wiring**: add the record as a new component of
   `MentalConfig.Snapshot`, to `Snapshot.defaults()`, to `MentalConfig.reload()`
   (new `parse` call), and a `public X xArea()` accessor. New config file (e.g.
   `combat-rules.yml`) threads through `ConfigSources`/`ConfigStore` if used.
4. **Module**: extend `CombatModule`, `configEnabled()` returns the settings'
   `enabled()`, register listeners via `listen(this)` in `onEnable()`, store and
   cancel any `TaskHandle` in `onDisable()`. Register in
   `MentalPlugin.registerModules()` (order matters: reverse teardown).
5. **Platform safety**: read version-fragile constants via `Attributes` /
   `Enchantments` resolvers (extend them, never reference enum constants
   directly). Gate optional behavior on `Capabilities`.
6. **Packet features**: register the PacketEvents listener in `MentalPlugin`
   before `init()`; gate its behavior on a config flag (`listen()` only manages
   Bukkit listeners).

Plan 0 also: (a) **docs/positioning** update (README "Dependencies: None" →
standalone-capable; CLAUDE.md invariant reworded); (b) a shared
**`LegacyDamageReshaper`** seam — a single `EntityDamageByEntityEvent` listener
(victim-region-safe) that the damage-model modules (Plan 5) compose into, so they
don't each register competing handlers; (c) a per-version capability probe for the
new component/attribute levers the research identifies.

## Subsystem sequence (low-risk → high-risk; each ships working software)

| # | Sub-plan | Delivers | Depends on | Risk | Needs NMS research |
|---|---|---|---|---|---|
| 0 | Foundation + docs | config/module pattern, reshaper seam, positioning | — | low | no |
| 1 | Feel quick-wins | `disable-attack-sounds`, `disable-sword-sweep` (+particles), `disable-crafting` | 0 | low | partial (sound/particle packet ids) |
| 2 | Attack-cooldown | low-level removal + **client animation suppression** (attr spoof) | 0 | med | **yes** (cooldown) |
| 3 | Offhand | `disable-offhand` (whitelist/blacklist, Folia world-change strip) | 0 | low | no |
| 4 | Consumables/health | `old-golden-apples`, `disable-enderpearl-cooldown`, `old-player-regen` | 0 | med | **yes** (consumables-health) |
| 5 | Damage model | `LegacyDamageReshaper` + `old-armour-strength`, `old-armour-durability`, `old-potion-effects`, `old-critical-hits` (event path), `old-tool-damage` extensions (durability, table, event path) | 0 | **high** | **yes** (armour, potions) |
| 6 | Sword blocking | `sword-blocking` (per-version component + offhand fallback) + `shield-damage-reduction` | 0, 5 (reduction shares reshaper) | **high** | **yes** (sword-block) |
| 7 | Hitboxes | deep-NMS reach/AABB tuning per version + documented limits | 0 | **high** | **yes** (hitboxes) |

Rationale: 1 & 3 are near-trivial and shake out the foundation; 2 is the owner's
emphasized ask and self-contained; 4 is independent gameplay; 5 is the coupled
damage cluster (must coexist with the existing fast-path `DamageCalculator`
without double-shaping); 6 builds on 5's reduction seam and is the most stateful;
7 is the most version-fragile. Build, run the matrix gate, and commit between
subsystems.

## File structure (new code)

```
core/src/main/java/me/vexmc/mental/
  module/rules/                         # new "combat rules" module family
    sound/AttackSoundModule.java        # Plan 1 (netty)
    sweep/SweepModule.java              # Plan 1 (event cancel + netty particle)
    crafting/DisableCraftingModule.java # Plan 1
    cooldown/AttackCooldownModule.java  # Plan 2
    cooldown/CooldownSpoofSender.java   # Plan 2 (PacketEvents attr spoof)
    offhand/OffhandModule.java          # Plan 3
    consumable/GoldenAppleModule.java   # Plan 4
    consumable/EnderPearlCooldownModule.java
    health/RegenModule.java             # Plan 4
    damage/LegacyDamageReshaper.java    # Plan 0/5 (shared EDBE seam)
    damage/ArmourStrengthModule.java    # Plan 5
    damage/ArmourDurabilityModule.java  # Plan 5
    damage/PotionEffectsModule.java     # Plan 5
    damage/CritFallbackModule.java      # Plan 5 (event-path crits)
    block/SwordBlockingModule.java      # Plan 6
    block/SwordBlockComponents.java     # Plan 6 (per-version component driver)
    block/ShieldReductionModule.java    # Plan 6
    hitbox/HitboxModule.java            # Plan 7
  combat/                               # pure era math (unit-pinned)
    ArmourMath.java  PotionMath.java  GoldenAppleEffects.java  RegenMath.java
  platform/                             # extend existing resolvers
    Components.java                     # new: blocks_attacks/consumable/attack_range resolver
  config/                              # new Settings records (one per module family)
tester/                                # new integration suites per subsystem
```

## Verification strategy

- **Unit pins** (`./gradlew build`, always first): every pure era formula
  (`ArmourMath`, `PotionMath`, `GoldenAppleEffects`, `RegenMath`, cooldown spoof
  value, sword-block reduction) gets hand-computed assertions from the
  decompile-cited constants the research produced. Config `parse(empty)==DEFAULTS`
  and `parse(disabled)` no-op pins.
- **Zero-touch**: extend `ZeroTouchSuite` so each new module disabled = no game
  effect (no listener, no packet, no attribute/inventory change).
- **Integration** (`scripts/integration-matrix.sh`, every matrix version): a suite
  per subsystem (e.g. armour reduction endpoint, golden-apple effects applied,
  cooldown spoof packet delivered, offhand block). Verify
  `run/**/plugins/MentalTester/test-results.txt` are FRESH and read PASS — never
  trust the banner (see `matrix-gate`).
- **Cross-version**: the version-gated levers (cooldown attr key rename at 1.21.2,
  sword-block component availability, reach attribute) must pass on 1.17.1, 1.20.6,
  1.21.4, 1.21.11, 26.1.2 — the boundaries where behavior flips.
- **Folia**: there is no Folia server in the matrix; correctness is by
  construction (all entity work via `Scheduling`, asserted by code review against
  the binding constraints above) plus the `BukkitScheduling`/`FoliaScheduling`
  parity the abstraction guarantees.

## Ground truth (filled by the NMS research workflow, run 2026-06-14)

Per-version mechanisms + exact 1.8 constants for cooldown, sword-block components,
hitbox levers, armour/potion/crit/golden-apple/regen math land in each sub-plan's
"Ground truth" section as they are produced. No sub-plan ships placeholder code:
each formula carries its decompile citation and a unit pin.

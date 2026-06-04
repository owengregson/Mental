# Running Mental with OldCombatMechanics

Mental and [OldCombatMechanics](https://github.com/kernitus/BukkitOldCombatMechanics)
(OCM) are built to run together, and they divide combat cleanly:

- **OCM owns the combat rules** — the attack cooldown, sword blocking, shield
  damage, player regen, golden apples, potion durations, armour strength,
  attack frequency (i-frames), sweep removal.
- **Mental owns knockback and hit delivery** — async hit registration, the
  1.7.10 knockback model with combos, fishing-rod and projectile knockback,
  ping compensation.

Mental deliberately implements *nothing* from OCM's half. It never touches
the attack-speed attribute, never swaps shields, never adjusts regen. The one
rules-adjacent thing Mental does — ignoring the cooldown's damage scaling on
its fast path — composes with OCM's `disable-attack-cooldown` rather than
competing with it.

## The overlap, and who yields

Six OCM modules reach into Mental's half. With both plugins installed (and
`compatibility.old-combat-mechanics: auto`, the default), Mental yields each
of these **exactly where OCM is configured to handle it**, interaction by
interaction:

| OCM module | Mechanic | Who decides ownership |
| --- | --- | --- |
| `old-player-knockback` | melee knockback | the **attacker**'s modeset |
| `old-fishing-knockback` | rod hits *and* reel-in | the **rodder**'s modeset |
| `fishing-rod-velocity` | rod cast speed/gravity | the **caster**'s modeset |
| `projectile-knockback` | snowball/egg/pearl hits | the **victim**'s modeset |
| `old-tool-damage` | weapon damage + sharpness | the **attacker**'s modeset |
| `old-critical-hits` | the crit multiplier | the **attacker**'s modeset |

These deciders mirror OCM's own rules ("offensive direct PvP mechanics follow
the attacker's mode; environmental follows the affected player"), so the two
plugins always agree on who owns a hit. Arrows are the deliberate exception:
OCM has no arrow module, so Mental's arrow knockback (positional base plus
legacy Punch) stays active alongside OCM.

On a **default OCM install** every player is in the `old` modeset and the
fishing/projectile modules are always-enabled — meaning OCM owns melee
knockback, rods and projectiles for everyone, and Mental contributes hit
registration, ping measurement, and arrow handling. To give a mechanic to
Mental (combos, positional projectile knockback, angler-direction rods, ping
compensation), remove the corresponding OCM module from the modeset or
disable it in OCM's config; Mental picks it up immediately, even per player
on mixed-mode servers — one arena can run OCM's 1.8 knockback while another
runs Mental's 1.7.10 ledger combos.

Mental logs the coordination state at startup and whenever it changes:

```
OCM coordination (startup): OldCombatMechanics service bound — modesets decide per player: ...
```

## How it works

- **Per-player precision** comes from OCM's service API
  (`OldCombatMechanicsAPI.isModuleEnabledForPlayer`), bound reflectively —
  Mental does not compile against OCM and has no hard dependency. On OCM
  builds without the API, Mental falls back to reading OCM's config file and
  yields globally and conservatively: if OCM *could* be handling a mechanic
  anywhere, Mental yields it everywhere, because doubled knockback is worse
  than deferred knockback.
- **Melee:** when OCM owns the hit, Mental submits nothing and OCM's velocity
  handler applies its 1.8 knock unopposed. Mental's netty velocity pre-send
  is suppressed for the same hits (it would mispredict OCM's vector); the
  hurt-animation pre-send stays, so OCM-owned hits still *register* at packet
  speed. OCM's knockback is recorded into Mental's residual ledger like any
  other velocity, so a Mental-owned rod hit after an OCM melee hit still
  reads the victim's true motion.
- **Damage:** OCM's damage machinery decomposes every damage event back into
  vanilla components before substituting its configured values. When
  `old-tool-damage`/`old-critical-hits` govern an attacker, Mental's fast
  path therefore hands the hurt pipeline *vanilla-shaped* damage (attribute
  base, 1.9 crit rules, 1.9 sharpness) so OCM's transformation is exact —
  legacy-composed damage would double-apply sharpness and crits. Attackers
  OCM does not govern keep Mental's 1.7.10 damage tables.
- **I-frames:** OCM's `attack-frequency` retunes hurt windows (18 ticks for
  players by default). Mental's feedback gate follows each victim's live
  window (`feedback-min-interval-ms: auto`), and all of its invulnerability
  checks read live values, so no configuration is needed.
- **Mental's modules, disabled, do nothing at all.** Turning off any Mental
  module (config or `/mental module <name> off`) leaves zero footprint — no
  event mutation, no packet cancellation, no entity writes — verified live by
  the zero-touch integration suite. OCM (or any other plugin) can own any
  mechanic outright.

## Verifying it yourself

The repository's integration harness can boot real Paper servers with both
plugins installed:

```bash
# in the BukkitOldCombatMechanics repo:
./gradlew shadowJar
cp build/libs/OldCombatMechanics.jar <mental repo>/run/ocm-jar/

# in the Mental repo:
./gradlew integrationTestOcm
```

The coexistence suite asserts, on live servers at both ends of the supported
range: the API binds, melee ownership follows the attacker's modeset with no
double knockback in either direction, fast-path damage comes out of OCM's
pipeline at exact era values, and rod/projectile hits defer while knockback
still arrives.

## Limits worth knowing

- **Folia:** OCM does not run on Folia; Mental owns everything there.
- **Same-tick cross-owner collisions:** if two *differently-owned* knockback
  sources hit the same victim in the same tick (e.g. a Mental-owned rod hit
  and an OCM-owned melee hit landing together), last-writer-wins on the one
  velocity event — vanilla resolves the same situation the same way.
- **`compatibility.old-combat-mechanics: ignore`** turns all of this off and
  both plugins will fight over velocity events. It exists for unusual
  debugging setups, not for servers.

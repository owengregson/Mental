# Hit Feedback & Damage Indicators — 2.5.2 design

Two new opt-in cosmetic modules under a new **FEEDBACK** family. Both default
OFF; zero-touch and the era-exact no-op hold by construction (the reconciler
only assembles a unit on the off→on edge). Neither touches gameplay state:
`Facets(serverRule=none, clientPresentation=handled, fastPathDamage=none,
vanillaPathDamage=none)`.

Grounding: the five-reader exploration of 2026-07-10 (feature system, hit
pipeline, config machinery, packet/FX surface, tester harness). Key upstream
facts this design rests on:

- `EntityDamageByEntityEvent` fires on the victim's owning region thread on
  every delivery path (fast-path pre-sent, pinned, vanilla) — the one
  once-per-landed-hit hook with damage, attacker, victim, and crit posture
  available (`KnockbackUnit` reads `getFinalDamage()` at MONITOR today).
- The vanilla hurt sound has a **two-audience model**: bystanders get a
  server sound packet (`entity.player.hurt`, victim excluded); the victim
  self-derives the sound client-side from DAMAGE_EVENT (1.19.4+) or
  entity-status 2 (below). Documented at `KnockbackUnit.deliverBlockedKnock`.
- PacketEvents 2.13.0 (shaded, relocated) version-maps packet/sound/particle
  ids per client version; the Bukkit `Sound`/`Particle` enums drift
  (REDSTONE→DUST at 1.20.5, enum→interface at ~1.21.3) so **all FX ship as
  PE packets, never Bukkit FX API calls**.
- The shade relocates PE's `net.kyori` reference into Mental's own relocated
  Adventure, so PE's `OPTIONAL_ADV_COMPONENT` metadata accepts the same
  `Component` type `TextPort` builds.
- Fake players have no PE user and void outbound traffic — cosmetic effects
  are matrix-unobservable. Testability therefore comes from a **decision
  trace seam** (journal pattern) plus unit pins (kernel math, parse, packet
  encode via the stub-PE-API pattern).

---

## Module A — `hit-feedback` (hit sounds & particles)

### Behavior

On every landed player-vs-player **melee** hit (`EntityDamageByEntityEvent`,
MONITOR, `ignoreCancelled=true`, `cause == ENTITY_ATTACK`, attacker and victim
both `Player`, `getFinalDamage() > 0`):

1. **Suppress the vanilla hurt-sound broadcast for exactly this hit.** The
   EDBEE handler marks the victim (entity id + position + `TickStamp`) in a
   small concurrent expectation map; a scoped outbound PE listener cancels
   `SOUND_EFFECT`/`ENTITY_SOUND_EFFECT` packets whose sound id is
   `entity.player.hurt` **only when they match a live mark** (entity id match,
   or position within 1 block for the positional form, inside a 2-tick
   window), consuming the mark. Fall/fire/drowning hurt sounds carry no mark
   and pass through untouched. The mark map is bounded and self-expiring
   (TickClock compare — readable from any thread).
2. **Play the configured sounds in vanilla's exact audience**: every online
   player in the victim's world within audible range (`16 × max(1, volume)`)
   **excluding the victim** — the same audience vanilla's broadcast reaches.
   The victim keeps its own client-derived vanilla hurt sound (DAMAGE_EVENT /
   entity-status 2), which is out of server reach without breaking the
   victim's flinch; documented in the config comments. Each sound is one
   `WrapperPlayServerSoundEffect` (positional, at the victim, category
   PLAYERS) per audience member, written via that player's PE `User`
   (null user → skip that viewer; reconfiguration-guard catch — the
   `BurstSender` idioms).
3. **Emit the configured particle bursts** at the victim's **mid-chest**
   (feet + 1.2): one `WrapperPlayServerParticle` per spec per audience member
   (victim included for particles — they render on the victim's body).
   Two modes per spec: `spread` (offset = per-axis Gaussian σ, speed 0) and
   `emanate` (offset 0, speed > 0 — particles burst outward from the point).
   `count` is a `min..max` range randomized per hit.

### Presets

`hit-feedback.preset: vanilla | signature | custom` — in-code constants (the
`MeleeFormula`-style lightweight shape; the file-based knockback preset
machinery is deliberately NOT reused):

- **vanilla** (the parse default): sounds = `entity.player.hurt` vol 1.0
  pitch `1.0 + (r₁−r₂)·0.2` (the vanilla pitch formula already used at
  `KnockbackUnit:600`); particles = none. Net audible result ≈ vanilla.
- **signature**: sounds = `block.lodestone.break` 1.0/1.0 +
  `entity.generic.hurt` 0.85/0.75 + `entity.breeze.deflect` 0.75/1.15;
  particles = redstone-block break particles (`block` particle,
  `minecraft:redstone_block` state), count 6–8, emanate-from-source.
- **custom**: the `sounds:`/`particles:` lists are parsed from the section.

### Cross-version sound/particle resolution

A `platform/FeedbackSounds` resolver (the `SweepCauses` probe-once shape):
each built-in maps `{resource name, min version, era fallback}` —
`block.lodestone.break` floors at 1.16 (fallback `block.stone.break`),
`entity.breeze.deflect` floors at 1.21 (fallback `item.shield.block`),
`entity.player.hurt`/`entity.generic.hurt` are universal. Resolution is
decided once at assemble against `ServerEnvironment`, printed as one boot
line when a fallback engages. Custom names resolve through PE
`Sounds.getByName`; unknown + below 1.19.3 → skipped with one warn (PE's
`getId` returns garbage for absent sounds — never trust it to fail loudly);
1.19.3+ sends inline-by-name. Particles: the signature preset's `block`
particle carries `ParticleBlockStateData`; below 1.13 PE's legacy write path
only carries `LegacyConvertible` data, so the resolver degrades the block
particle to `CRIT` there if the data proves non-convertible (implementer
verifies against PE 2.13.0; one boot line either way).

---

## Module B — `damage-indicators`

### Behavior

On the same landed-melee predicate (attacker must additionally be a **real**
player — PE `User` present; a bot attacker has no client to show anything
to → decision recorded as `UNSENDABLE`, nothing ships):

1. **Placement.** Spawn point = victim feet + `1.2` (chest) `± U(0,
   height-jitter)` vertically (default jitter 0.3), on a **front-half ring**:
   bearing = victim→attacker azimuth `+ U(−90°, +90°)`, radius
   `ring-radius` (default 0.6). Pure kernel math (`IndicatorPlacement`),
   seeded `java.util.Random`, unit-pinned.
2. **The stand.** One packet-only armor stand, sent **only to the attacker's
   PE `User`**: spawn (`WrapperPlayServerSpawnEntity` on 1.19+,
   `WrapperPlayServerSpawnLivingEntity` below — armor stands are living
   pre-1.19 and PE does NOT auto-route), then `WrapperPlayServerEntityMetadata`:
   entity flags `0x20` (invisible, index 0), custom name (index 2 —
   `STRING`/§-legacy below 1.13 via `TextPort.legacy`, `Optional.of(Component)`
   with `OPTIONAL_ADV_COMPONENT` on 1.13+), custom-name-visible (index 3),
   and the armor-stand status byte `0x10|0x01` (**marker** + small — marker
   zeroes the client-side hitbox so the stand can never intercept the
   attacker's crosshair mid-combo, and pins the nameplate ~0.5 above the
   entity y). The status-byte index drifts by client version band
   (11 on 1.9–1.13.2, 14 on 1.14–1.16.5, 15 on 1.17+) — chosen per the
   user's `ClientVersion`, verified against PE's wiki data at implementation.
   Entity id from the shaded PE-spigot `SpigotReflectionUtil.generateEntityId()`
   (a genuinely server-unused id; `IllegalStateException` → skip the
   indicator fail-soft with one warn). Spawn + metadata ride one bundle on
   1.19.4+ (BurstSender's bundle idiom) so the stand never flashes unnamed.
3. **Text.** Non-crit: `&f-{HEALTH} &c❤&r`. Crit variant (`&c&l** -{HEALTH}
   ❤ **`) when the hit is an era crit (`DamageShaper.isLegacyCritical
   (attacker)` posture) **or** `finalDamage ≥ crit-threshold-hearts × 2`
   (threshold in HEARTS, default 5.0 hearts = 10 damage). `{HEALTH}` = final
   damage in hearts, one decimal, trailing `.0` stripped. Raw template →
   `{HEALTH}` substitution → `LegacyComponentSerializer.legacyAmpersand()`
   (the `OffhandUnit.sendDenied` precedent) → Component.
4. **Physics.** All integration is pure kernel math (`IndicatorBallistics`),
   zero per-tick world reads: at spawn (on the EDBEE region thread — the only
   place block reads are region-legal) the unit scans down ≤6 blocks from the
   spawn point for the first solid top and freezes `groundY`; the per-tick
   step is then `y += vy; vy = (vy − gravity) × drag; horizontal += outward
   drift × drag` with defaults `launch-vertical 0.25`, `launch-outward 0.06`
   (along the ring bearing, away from the victim), `gravity 0.05`,
   `drag 0.98` — an item-pop feel: ~5 ticks up ~0.7 blocks, then the fall.
   The client is sent one `WrapperPlayServerEntityRelativeMove` per tick
   (short-Δ, clients interpolate — smooth) and never simulates anything
   itself; the marker stand's nameplate sits ~0.5 above the driven y, so the
   driven point is `text y − 0.5`.
5. **Despawn.** `WrapperPlayServerDestroyEntities(id)` the instant the text
   point reaches `groundY` (`y ≤ groundY + 0.05`) or at `lifetime-ticks`
   (default 40), whichever first.
6. **Driver.** One lazy per-attacker task (`Scheduling.repeatOn(attacker)`,
   1-tick period) drains that attacker's live-indicator list — usually a
   handful of concurrent indicators, one flush per tick per attacker; the
   task cancels itself when the list empties. Ballistics are pure and packet
   sends are thread-agnostic, so the task body performs **zero** entity or
   world reads (Folia-safe by construction). Scope close destroys every live
   stand and cancels every driver; a session forget hook drops per-attacker
   state on quit (client is gone — no packets owed).

### Config (both modules: sections in `config.yml`, no fifth file)

```yaml
modules:
  hit-feedback: false
  damage-indicators: false

hit-feedback:
  preset: vanilla            # vanilla | signature | custom
  sounds:                    # read when preset: custom
    - sound: entity.player.hurt
      volume: 1.0
      pitch: 1.0
  particles:                 # read when preset: custom
    - particle: block
      block: redstone_block  # block particles only
      count-min: 6
      count-max: 8
      mode: emanate          # emanate | spread
      speed: 0.15            # emanate outward speed
      spread: {x: 0.2, y: 0.3, z: 0.2}   # spread mode σ

damage-indicators:
  lifetime-ticks: 40
  ring-radius: 0.6
  height-jitter: 0.3
  launch-vertical: 0.25
  launch-outward: 0.06
  gravity: 0.05
  drag: 0.98
  text: "&f-{HEALTH} &c❤&r"
  crit-text: "&c&l** -{HEALTH} ❤ **"
  crit-threshold-hearts: 5.0
```

`parse(empty) == DEFAULTS` holds for both records (module toggles are OFF, so
the no-op default is doubly guaranteed). All numeric knobs parse through
`ConfigReader` warn-and-fallback with sane clamp bands (volumes 0–4, pitches
0.5–2, counts 0–64, radii 0–4, lifetime 1–200). The sounds/particles lists
are a genuinely new parse shape (`getMapList` iteration — no ConfigReader
list-of-records helper exists); each entry field still routes through the
warn-and-fallback primitives.

---

## Architecture / wiring (both modules)

| Piece | Where |
| --- | --- |
| `Family.FEEDBACK` | 9th family constant; dashboard V-funnel auto-lays it |
| `Feature.HIT_FEEDBACK`, `Feature.DAMAGE_INDICATORS` | enum constants, default OFF, clientPresentation-only facets |
| `HitFeedbackSettings`, `DamageIndicatorsSettings` | records + `DEFAULTS` in `config/settings/` |
| `SnapshotParser` | two `settingsFor` cases + `parseHitFeedback`/`parseDamageIndicators` |
| `feature/feedback/` (new package) | `HitFeedbackUnit` (EDBEE listener + outbound suppressor via `scope.listen`/`scope.packets`), `DamageIndicatorsUnit` (EDBEE listener + per-attacker drivers via `scope.task`), sender helpers |
| `kernel/.../fx/` | `IndicatorPlacement`, `IndicatorBallistics` — pure JDK, unit-pinned |
| `platform/FeedbackSounds` | probe-once name resolver with era fallbacks + boot lines |
| `FeedbackTrace` (core) | bounded, thread-safe decision ring written at decision time (sounds chosen/suppression armed; indicator variant, spawn, TTL, sendability), exposed to the tester — the journal-pattern seam that makes clientless suites able to assert anything |
| GUI | toggle is automatic (registry-derived). A FEEDBACK preset-picker menu (Knockback-picker shape) is stretch scope — droppable without affecting the modules |

The packet listeners live under `feature/feedback/`, NOT `rim/` — the rim
architecture pin constrains only the always-on parse rim
(`AttackSoundListener`/`SweepParticleListener` are the precedent), and
feature-scoped listeners must die with their scope.

Threading: the EDBEE handler runs on the victim's region thread (block scan +
audience iteration are legal there; audience via `Bukkit.getOnlinePlayers()`
+ same-world distance filter — both netty-safe reads, per the Folia
safe-list). Packet writes are thread-agnostic. The suppressor's send-event
handler reads only the mark map and the wrapper (never live entities).

## Testing

- **Kernel pins**: `IndicatorPlacementTest` (front-half bearing bounds,
  radius, jitter bounds, seeded determinism), `IndicatorBallisticsTest`
  (hand-computed tick table for the default constants; apex ~tick 5; ground
  intercept; timeout).
- **Core parse pins**: `SnapshotTest` additions (both records at DEFAULTS on
  empty parse, zero issues), clamp tests, preset-selection tests, the
  `{HEALTH}` templating + legacy-code test, `FeatureRegistryTest`
  OPERATOR_CONTRACT_KEYS + default-OFF (automatic).
- **Packet-encode pins** (stub-PE-API pattern from `PacketTapStateTest`):
  indicator spawn/metadata/destroy wrapper fields (type ARMOR_STAND,
  invisible+marker flags, name component/string per version band), sound
  wrapper fields, suppressor cancel/pass decisions against synthetic
  send events (marked melee vs unmarked fall damage).
- **Integration** (`FeedbackSuite`, full tier — no support-matrix change
  needed): toggle each module on through the real management seam; stage a
  real fake-pair hit; assert the `FeedbackTrace` decision record (sounds
  list resolved, suppression consumed/expired, indicator variant + spawn
  position + TTL, `UNSENDABLE` for the fake attacker); assert the hit still
  lands and knockback is untouched; zero-touch case asserts trace stays
  empty and vanilla behavior when OFF. Effect-level audio/visuals are
  note-skipped (clientless), matching the `disable-attack-sounds` posture.
- **D-9 discipline**: every drift-prone name resolves through the platform
  resolver — no getstatic of version-absent constants anywhere a listener
  can link it.

## Rejected alternatives

- **Bukkit `World.playSound`/`spawnParticle`**: enum drift (REDSTONE→DUST,
  enum→interface ~1.21.3) makes the floor-compiled constants linkage bombs on
  modern servers; the `SoundCategory` overload floors at 1.11 besides.
- **Unconditional hurt-sound cancel** (AttackSoundListener-style): would
  silence fall/fire/drowning hurt sounds — the expectation-mark correlation
  scopes suppression to exactly the hits Mental re-sounded.
- **Cancelling the victim's DAMAGE_EVENT / entity-status 2** to silence the
  victim's self-derived vanilla sound: removes the victim's flinch/tint on
  non-pre-sent paths; not worth the risk for an audience the module's value
  barely targets. Documented caveat instead.
- **Real (server-side) armor stands**: entity tracking, region ownership,
  persistence risk, other-player visibility, anticheat noise — packets to one
  client are strictly better on every axis.
- **A fifth config file / reusing the knockback preset machinery**: six-file
  cost or knockback-typed machinery for what is two records and an enum.
- **Per-indicator scheduled tasks**: one task per attacker with a drain list
  is strictly cheaper under combo cadence.

## Open risks (carried into implementation)

1. PE legacy (<1.13) particle-data conversion for `block` particles —
   verify `ParticleBlockStateData` legacy convertibility; degrade to `CRIT`.
2. Armor-stand status-byte index bands vs ViaVersion clients — indexes are
   chosen per PE `ClientVersion`; verify bands against PE mappings.
3. `entity.breeze.deflect` availability band (1.20.5 data vs 1.21 mob) —
   resolver floor set where PE's sound mappings actually carry it.
4. The victim's self-derived vanilla hurt sound remains under custom sounds
   (two-audience model) — accepted, documented.

---

## Addendum (2026-07-10, owner mid-round): Combat Effects category, low-HP extras, death-effects

Three additions, same round:

**1. The family is "Combat Effects".** `Family.FEEDBACK` keeps its enum name
(already merged) but its display metadata becomes
`"Combat Effects"` / blurb covering all three modules. Feature display names:
"Hit Effects" (`hit-feedback`), "Damage Indicators" (`damage-indicators`),
"Death Effects" (`death-effects`). The family's GUI menu is automatic
(registry-derived), satisfying "has its own menu category".

**2. Low-health extra sounds (`hit-feedback`).** When the victim's health
AFTER the hit falls below `low-health-threshold-hearts` (default 4.0 hearts)
AND the hit does not kill, an optional EXTRA sound list plays on top of the
normal set, same audience. Signature preset extra:
`entity.glow_squid.hurt` volume 0.9 pitch 1.2 (glow squid floors at 1.17 —
era fallback `entity.squid.hurt`, universal). Vanilla preset extra: empty.
Custom: a `low-health-sounds:` list + threshold key. **Killing-hit rule:** on
the hit that kills, the NORMAL hit sounds/particles still play, the low-HP
extra is suppressed (death-effects owns the death moment).

**3. `death-effects` module** (third Feature in the family, default OFF).
Fires once per player death — `PlayerDeathEvent` at MONITOR (any cause; the
"was hit to cause death" case is covered because hit-feedback already played
its normal sounds on that killing EDBEE). Presets:

- **vanilla** (parse default): NOTHING — enabled-but-vanilla is a strict
  no-op (zero-touch is the module toggle; the preset is data).
- **signature**: (a) cosmetic lightning at the death location — a
  client-side packet LIGHTNING_BOLT entity sent to nearby viewers (never a
  real entity: no fire, no damage, no block/drop interaction by
  construction; destroyed after ~20 ticks belt-and-braces since clients
  self-expire the bolt render); the vanilla thunder sound is NOT sent;
  (b) sound `entity.glow_squid.death` volume 1.0 pitch 0.95 (fallback below
  1.17: `entity.squid.death`); (c) a firework-blast burst at the death
  location in white/yellow/gold (&f/&e/&6 → 0xFFFFFF/0xFFFF55/0xFFAA00):
  colored DUST particles in a fireworks-spark-shaped burst plus a few
  uncolored `firework` sparks for the blast read — the vanilla firework
  particle is not colorable, so the mix approximates it honestly (config
  comment says so); below 1.13 dust color degrades → sparks only.
- **custom**: `lightning: true|false`, `sounds:` list, `particles:` list
  (same spec shapes as hit-feedback; `DeathEffectsSettings` reuses
  `HitFeedbackSettings.SoundSpec`/`ParticleSpec`).

Audience for death effects: every player within range of the death location
(the victim is dead/respawning — no exclusion needed). Decisions are traced
in `FeedbackTrace` (module `"death-effects"`, decision `EMITTED`/`NO_VIEWERS`)
so the suite can assert them clientless.

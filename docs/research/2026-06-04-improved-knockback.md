# What Makes "Improved Knockback" Servers Tick

Research synthesis, 2026-06-04. Ten parallel research agents swept GitHub
source trees, the Wayback Machine, Hypixel/Badlion/CubeCraft/GommeHD forums,
SpigotMC/BuiltByBit listings, protocol references and plugin ecosystems to
answer one question: **why do Minemen Club and its peers have famously
smooth, combo-conducive, consistent knockback — and what of that can Mental
adopt?** No plugin changes accompany this document; it is the evidence base
for a future design round.

Provenance labels used throughout: **[confirmed]** = primary source (code,
archived first-party statement), **[likely]** = multiple independent
secondary sources, **[rumor]** = single unverified claim or leak.

---

## 1. What Minemen Club actually runs

- **A private 1.8.8 fork, "ClubSpigot."** Lineage [confirmed]:
  CobbleSword **KnockbackSpigot/NachoSpigot** → **WindSpigot** →
  **ClubSpigot** (`club.minemen.spigot.*`). Modern clients (1.21.x) reach
  it through a proxy + ViaVersion; the gameplay servers stay 1.8.8 NMS.
- Two public "ClubSpigot" artifacts exist and **disagree on the formula**:
  - `Clarity-Services/spigotx` — claims to be the *leaked* ClubSpigot.
    Pure **Kohi model**: `friction 2.0` (X/Y/Z divisor), `horizontal 0.35`,
    `vertical 0.35` (added), `vertical-limit 0.4`, `extra-horizontal 0.425`,
    `extra-vertical 0.085`, `/kb` runtime editor. [rumor→likely]
  - `MatiasNoble3/ClubSpigot` — a *fanmade remake* ("replicate minemen's
    knockback 1:1 … feels very close"), WindSpigot-based, default profile
    literally named `mmc`. **Range-reduction model** (below). [likely for
    structure, rumor for exact constants]
- The honest conclusion: nobody outside MMC has the current formula
  byte-for-byte. What the artifacts **agree** on is the architecture and
  philosophy — and that is the transferable part.

### The MMC-remake formula (the structurally interesting one)

```java
// EntityLiving.a(Entity opponent, double x, double z, DamageSource source)
frictionH = 2.0 - (1.0 - horizontal);              // == 1 + horizontal
f2        = 0.4 * (1.0 - 0.4 * (1.0 - horizontal));
motX /= frictionH;  motZ /= frictionH;
motY  = vertical;                                   // ASSIGNED, not added
motX -= x/f1 * (f2 - rangeReduction);
motZ -= z/f1 * (f2 - rangeReduction);
if (verticalLimit && motY > verticalLimitValue) motY = verticalLimitValue;

// rangeReduction: 0 within start-range-reduction (3.0 blocks), else
// min(range-factor * (distance - max-range-reduction), min-range)
// defaults: factor 0.025, max-reduction 1.2, min-range cap 0.12
```

Shipped `mmc` profile: `vertical 0.9055, horizontal 0.25635,
sprint-multiplier 1.0, range-factor 0.025, max-range-reduction 1.2,
start-range-reduction 3.0, min-range 0.12, vertical-limit-value 4.0`
(the remake has a horizontal/vertical label swap between loader and POJO —
treat exact numbers as the remake author's tuning, not gospel).

Sprint bonus in the remake's `EntityHuman.attack`: a **flat per-hit**
impulse `±sin/cos(yaw) · sprintMultiplier · 0.5` horizontal, `+0.1`
vertical — **ungated by the vanilla `i > 0` sprint/enchant check, no
attacker `motX *= 0.6` recoil, no `setSprinting(false)`**. The vanilla
sprint mechanic was deliberately flattened. [confirmed in-source]

### Why it reads as "static"

1. **Vertical is assigned, not accumulated** — victim jump/fall state can't
   change the launch. The single biggest "every hit lands the same" lever.
2. **Constant friction-divide on incoming motion** before a constant push —
   deterministic function of yaw + knob, zero randomness anywhere in the
   path (the only `random` is vanilla's knockback-resistance gate).
3. **Distance taper** — full KB inside 3.0 blocks, gently reduced beyond
   (capped at −0.12) so max-reach hits launch slightly less.
4. **Immediate velocity packet inside the hit** plus `fallDistance = 0`,
   then server motion restored (the universal send-then-restore idiom).
5. **No ping scaling of KB magnitude.** MMC's "lag compensation" is
   ping-**rewound reach validation** (position history ring buffer:
   `pingOffset 92 ms, historySize 40, timeResolution 30 ms` → rewound
   *distance* check + line-of-sight raytrace, not full hitbox-rewind
   registration) — plus regional matchmaking. Consistency is partly
   **infrastructure**, not formula. [confirmed in-source]

### Delivery architecture [confirmed in-source]

- **Async combat thread = a packet write/flush offloader, nothing more.**
  It diverts only `PacketPlayOutEntityVelocity` / position / flying packets
  (after a >5-packet warm-up latch), wraps each write in a runnable, and a
  single drain task is rescheduled **onto the channel's own netty event
  loop** (`channel.pipeline().lastContext().executor()`) which does the
  writes then one flush. Ordering and thread-safety come from re-entering
  the event loop. KB math and damage stay on the main thread.
  Notably `async.knockback` ships **disabled by default** even there.
- NachoSpigot's `async-kb-hit` branch *also* offloads inbound ATTACK
  packets to a hit-detection thread (entity state mutated off-main) —
  **ClubSpigot dropped that** as too risky. Mental's split (registration
  on netty, damage on the owning thread) is the same conclusion.
- Hit delay is a config knob (`settings.hit-delay`, default 20) that
  overwrites `maxNoDamageTicks` per entity every tick. Boxing/combo modes
  in the ecosystem run ~0–5 ticks.

### Cautions on the MMC mythology

- The "Hypixel licensed AntiGamingChair from MMC and removed randomized KB
  (Apr 12 2021)" patch-notes thread is a **community fake** (authored by a
  regular user, not staff). The `MinemenCIub/AntiGamingChair` GitHub org
  spells the name with a capital-I impersonation. Treat the whole story as
  [rumor]. The KB *philosophy* claim survives on other evidence anyway.
- MMC's KB was **not always good**: the early-2021 season shipped a
  CPS-sensitive variant players called "completely random" ("New MMC kb is
  horrible", Mar 23 2021). The "static" reputation is the post-backlash
  equilibrium. Determinism won because randomness was *hated*.
- The Jul 2021 "Season 4 beta" changelog (recovered via Wayback OCR)
  contained **no combat changes** — content only.

---

## 2. The formula family (cross-fork knob semantics)

Vanilla 1.8.8 (`EntityLiving.a`): `motX/Y/Z /= 2`, then
`motX -= dir·0.4, motY += 0.4, motZ -= dir·0.4`, cap `motY ≤ 0.4000000059604645`.
Sprint/enchant bonus in `EntityHuman.attack` when `i = enchant + (sprinting?1:0) > 0`:
`entity.g(±sin/cos(yaw)·i·0.5, 0.1, …)` (NOT re-clamped), attacker
`motX *= 0.6` + `setSprinting(false)`. The only randomness in the entire
path is the resistance gate `random.nextDouble() >= knockbackResistance`.

| Fork | Friction | Vertical apply | Clamp | Sprint extra | Novel knobs | Profiles |
|---|---|---|---|---|---|---|
| Vanilla 1.8 | `/2.0` fixed | `+=` | base only, 0.4 | 0.5 / 0.1 · `i` | — | — |
| PandaSpigot | divisor, `friction 2.0` | `+=` | `vertical-limit` | `extra-h 0.5 / extra-v 0.1` | — | plugin (PandaKnockback) |
| SportPaper | divisor − KB-resistance | `+=` | `vertical-limit` | configurable | per-entity `knockbackReduction` API | `/knockback` live |
| NachoSpigot | **multiplier** `*0.5` (h/v split) | `+=` | `vertical-max` **+ `vertical-min` −1.0** | extra-h/v, `stop-sprint` toggle (`setExtraKnockback(false)` desync fix) | **per-projectile h/v** (rod/arrow/pearl/snowball/egg) | `/kb` + per-player |
| WindSpigot | **divisor** 2.0 (explicit "change to division" comment) | `+=` | min/max | extra-h/v **+ `wtap-extra-h/v`** branch on `isExtraKnockback()` | `add-h/add-v` (sign-matched, ratio-distributed, pre-clamp) | `/kb` + per-player |
| ClubSpigot (remake) | `1+h` divisor (X/Z only) | **`=` assigned** | `vertical-limit-value` 4.0 | flat `sprintMult·0.5`, every hit | **distance taper** | config-only, per-entity API |
| PandaKnockback (plugin) | `pre-multiplier` 0.5 | `+=` | `limit.vertical` | sprint-bonus **separate from enchant-bonus** (0.5/0.1 vs 0.5/0.05) | per-hit `min-max` random ranges (opt-in) | per-player + WorldGuard region flag |
| KnockbackMaster (plugin) | n/a (absolute model) | absolute | — | sprint .8/.42 vs normal .4/.36 | **air ×0.6/×0.8**, w-tap window 200 ms, damager ×1.05/1.03 vs damaged ×0.95/0.97, randomizer ±4 %/2 %, combo-mode | per-world |

**The #1 porting hazard**: friction means *divisor* in Panda/Sport/Wind
(2.0) and *multiplier* in Nacho/PandaKnockback (0.5). Same math, opposite
knob direction. Mental's `friction.x/y/z` (survival fraction, 0.5) is the
Nacho convention — any "MMC config" import feature must translate.

Canonical community profile values (for preset shipping):

- **Kohi / "MMC-shaped"** [confirmed across 3 sources]:
  `friction 2.0 ÷ · h 0.35 · v 0.35 · limit 0.4 · extra-h 0.425 · extra-v 0.085`
- **Lunar recreation** [likely]:
  `friction 1.5 ÷ · h 0.46 · v 0.3535 · limit 0.3535 · sprint-h 1.3 · slowdown-h 0.6`
- **Vanilla-1.8**: `2.0 ÷ · 0.4 · 0.4 · 0.4 · 0.5 · 0.1`
- ImanitySpigot ships three KB *modules* — Regular, **MinemenClub**
  ("attempts to replicate"), Advanced — with per-player
  `setKnockback(profile)` API and a per-kit practice addon
  (StrikePractice `KitSelectEvent` → profile swap). Its NoDebuff profile
  set names the tuning axes: Default / **Slowdown (high-CPS)** /
  **Vertical** / **BlockHit** variants (numbers paywalled).

---

## 3. Delivery, timing, and the modern-version levers

- **Vanilla 1.8 already sends melee KB to player victims immediately**
  inside `attack()` (PlayerVelocityEvent → send → restore motion → clear
  `velocityChanged`); end-of-tick tracker flush covers everything else.
  The forks' contribution is keeping that hot, offloading the flush, and
  never letting a same-tick move packet clobber it.
- **Known packet-ordering hazards** (regression-test material):
  - "Random friction" — velocity packet racing the client friction tick;
    the CustomKnockback plugin shipped a `kb_fix: true` reorder band-aid.
  - MC-52881 — login-order-dependent KB (fixed 14w20a) — KB differing by
    entity-tracker state.
  - `EntityDamageByEntityEvent → PlayerVelocityEvent` is **not** a
    guaranteed sequence (OCM PR #552's stale-map bug).
  - Paper #11055 — zero-damage hits silently dropped player KB on 1.21
    b45+ until #11058. Mental damages with real amounts, but the class of
    bug is worth a matrix test.
  - MC-14861 — teleports zero velocity; never pair KB with a teleport tick.
- **The Bundle packet (1.19.4+) is an unexploited smoothness lever.**
  Bundled packets are processed in one client frame. Vanilla only bundles
  entity spawns; nobody ships bundled combat feedback. Wrapping Mental's
  pre-sent hurt animation + velocity (+ sound) in one bundle would make
  the feedback atomic on-screen. PacketEvents has `WrapperPlayServerBundle`.
- **1.19.4+ damage protocol**: `DAMAGE_EVENT` (with source position →
  directional screen tilt) and `HURT_ANIMATION` replace Entity Status 2.
  Pre-sent feedback should drive these on modern protocols for correct
  tilt direction.
- **ViaVersion**: cross-version KB complaints trace to hitboxes + latency,
  not velocity mistranslation (velocity shorts pass through 1:1; the
  1/8000-per-tick encoding quantizes at 0.000125 blocks/tick). MMC serves
  modern clients through Via and eats that penalty — a native modern
  plugin does not. That is Mental's structural advantage.
- **knockback-sync** (Mental's latency-comp ancestor): Y-only adjustment,
  hardcoded 25 ms offset, spike threshold 20 ms, gravity 0.08 / drag 0.98
  simulation, only acts when `lastDamageTicks ≤ 8` and near-ground
  (≤ 1.3 blocks). Known issues: Vulcan false flags (Grim is the
  recommended pairing), Bedrock/NPCs excluded, off-ground sync
  experimental. Mental already generalizes most of this.

---

## 4. The player-technique contract (what must keep working)

Measured era values (community gold standard, LAN, 10-decimal coordinate
readers):

| Condition | 1.8 blocks | 1.7 blocks |
|---|---|---|
| No-sprint first hit | 1.984 | 0.988 |
| No-sprint holding W | 1.704 | — |
| Sprint first hit / sprint-reset | 4.938 | ~2.54 |
| Consecutive airborne sprint-resets | 6.3–7.2 | 3.7–6.1 |
| Rod / snowball / bow (any state) | **1.984 constant** | — |

- **1.7 ≈ half of 1.8** in raw distance (the folk "10 % difference" is
  wrong). Mental's era tables already encode the formula-level truth.
- **The 0.6 self-velocity multiplier is the keystone of CPS lore**:
  every sprint-bonus attack the *victim* throws multiplies their own
  horizontal motion ×0.6 — **client-side prediction**, which is why high
  CPS reduces received KB (0.6^clicks), why straightline locks exist, and
  why sumo "reducing" works. A server plugin cannot and must not touch
  it; it keeps working as long as Mental stays client-authoritative about
  attacker movement. Mental's ledger correctly never records client-side
  input, mirroring the legacy server.
- **Real techniques**: w-tap/s-tap re-arm the sprint bonus (attacker-side);
  jump-reset within ~1 tick converts horizontal launch to vertical;
  A/D-strafe redirects KB through the residual model. All fall out of
  era-correct formula + client authority — preserved by construction.
- **Myths (do not implement)**: victim sprint-key spamming as a KB
  reducer [placebo]; "1.7 animations reduce KB" [placebo — the real Lunar
  Client incident was a 1.12-native velocity bug, CubeCraft-verified ~50 %
  and patched, Nov 2020]; CPS granting extra damage [i-frame-gated].

---

## 5. Negative results and scope lessons

- **Randomized KB is an anti-feature.** MMC's "random" era drew the
  loudest backlash in its history; KnockbackMaster's ±4 %/2 % randomizer
  exists but the consistency reputation is what players chase.
- **Flattening skill expression is also an anti-feature.** GommeHD's
  anti-reduce anticheat (KB normalized to be CPS-independent above
  ~10 CPS) hit ~80 % disapproval and an exodus of top players — "fair and
  consistent" can read as "boring" to a skilled 1.8 base. Fairness
  features (ping comp included) should be **choices**, not silent
  defaults that erase technique.
- **Hit delay is combat rules, not knockback.** Every fork tunes
  `maxNoDamageTicks` next to KB (combo modes 0–5 ticks), but in Mental's
  1.2.0 ownership split that knob belongs to OldCombatMechanics'
  attack-frequency module. Mental's `feedback-min-interval-ms: auto`
  already follows whatever window OCM sets. Document the pairing; do not
  absorb the knob.
- **Hitbox-rewind hit registration is plugin-feasible** (Islandscout's
  ServerSideHitDetection is a pure Bukkit plugin; ClubSpigot runs the
  lighter rewound-reach-validation variant) but changes event semantics
  and anticheat interplay. If ever pursued: the ClubSpigot-lite shape
  (history ring buffer → rewound distance gate) behind the AnticheatGate
  pattern, never the full cancel-and-replace.

---

## 6. Gap analysis — Mental 1.2.0 vs the meta

Already at or beyond state of the art:

- **Residual model**: the VictimMotion ledger gives true 1.7.10 compounding
  that the 1.8-era forks can't (their send-then-restore wipes residuals);
  `combos: false` reproduces the fork-flat behavior.
- **Delivery**: netty fast path + pre-send ≈ the async-kb-hit lineage
  conclusion (registration off-main, state on-main, writes on the event
  loop), already gated by AnticheatGate/OcmGate.
- **Knobs**: base h/v, extra h/v, vertical/horizontal limits, per-axis
  friction (multiplier semantics), sprint levels, resistance policies,
  per-projectile modules — most of the Nacho/Wind vocabulary exists.
- **Latency comp**: generalizes knockback-sync (configurable offset/spike,
  off-ground sync, PING channel).

Missing vs the ecosystem:

1. **Named knockback profiles** — per-world assignment, per-player API,
   practice-core hook, shipped presets (`legacy-1.7`, `legacy-1.8`,
   `kohi`, `mmc-taper`, `lunar`). The single most-expected feature; every
   "MMC KB" forum request is really a request for this.
2. **`vertical-mode: add | set`** — the assign-vertical lever behind
   "static" KB.
3. **Distance taper** (`start 3.0 / factor 0.025 / max-reduction 1.2 /
   min 0.12`) — MMC's signature reach-hit softening.
4. **W-tap-distinct sprint bonus** (`wtap-extra` vs `extra`) and
   **air multipliers** — the WindSpigot/KnockbackMaster refinements.
5. **`add-h/v` post-impulse offsets and `vertical-min`** — completeness.
6. **Bundle-atomic pre-send + modern DAMAGE_EVENT tilt** — delivery polish
   no one else ships.
7. **Adaptive ping offset** — jitter-aware widening instead of the flat
   25 ms constant (Mental already computes jitter).
8. (Optional, separate) **rewound-reach validation** for the fast path.

---

## 7. Proposed modifications (design candidates, no code yet)

**P1 — Knockback profiles.** Engine settings become named profile
instances; `knockback.profiles.<name>` config blocks + `profile:` default +
per-world map; `Mental.get().knockbackProfile(player)/setKnockbackProfile`
API + event for practice cores; `/mental kb <profile> [player]` runtime
assignment. Ship the five presets with provenance comments. Default
remains today's exact 1.7.10 profile → zero behavior change.

**P2 — Engine knob completion.** `vertical-mode: add|set`,
`range-reduction` block, `wtap-extra` pair (branch on sprint-state
freshness, which the hit pipeline can already observe), `air` multipliers,
`add-h/v`, `limits.vertical-min`. All defaulting to era-exact no-ops.
Document divisor↔multiplier friction translation prominently for config
porters.

**P3 — Delivery atomicity.** Bundle the pre-send burst on 1.19.4+
(PacketEvents bundle wrapper); send `DAMAGE_EVENT` with attacker source
position on modern protocols for correct directional tilt; add matrix
regression tests for the wrong-tick/ordering hazard class (§3).

**P4 — Latency-comp v2.** Jitter-adaptive ping offset (widen safety margin
under high jitter, tighten when stable); keep the flat constant as a
config escape hatch. Frame ping comp explicitly as a fairness *choice* in
docs (GommeHD lesson).

**P5 — (Stretch) Rewound-reach validation** as an opt-in fast-path check,
ClubSpigot-lite shape, behind the anticheat gate.

**Non-goals**, recorded deliberately: hit-delay/noDamageTicks knobs (OCM's
attack-frequency owns them — pair, don't absorb); KB randomizers; victim
sprint-toggle reducers; CPS-scaled anything; misplace emulation. The
client-side technique contract (0.6 self-multiplier, w-tap, jump-reset,
strafe redirection) is preserved by Mental's client-authoritative design
and must stay untouched.

---

## 8. Source appendix (load-bearing)

Fork source: `MatiasNoble3/ClubSpigot` (remake; `club.minemen.spigot`,
`mmc` profile) · `Clarity-Services/spigotx` (claimed leak; Kohi values) ·
`CobbleSword/NachoSpigot` + `KnockbackSpigot` (+ `Argarian-Network`
`async-kb-hit` branch) · `Wind-Development/WindSpigot` (divisor comment,
wtap/add knobs) · `Electroid/SportPaper` patch 0188 · `hpfxd/PandaSpigot`
patch 0014 + PandaKnockback · `Islandscout/ServerSideHitDetection` ·
`HGLabor/knockback-api` · ImanitySpigot wiki/changelog/PracticeAddon.

Forums/archives: Hypixel threads 2360655 (measured KB tables), 4014543
(sprint-reset taxonomy), 4009831 + Badlion 187216 (0.6^CPS), 3121542
(sumo lore), 4033748 ("New MMC kb is horrible", Mar 2021), 5702402
(MMC-vs-Hypixel consistency), 333445 (1.7 vs 1.8), 1635496 (misplace);
CubeCraft 270336 (Lunar Client verdict, Nov 12 2020); GommeHD anti-reduce
threads (931246, 618021, 932436); BuiltByBit 718065/705536 via Wayback;
KnockbackMaster config gist (`xDefcon`); Bot-Practice `knockback.yml`
(Minemen/Lunar/Velocity/Classic presets); knockback-sync source + issues.

Protocol/platform: wiki.vg Entity Velocity (1/8000 short) · minecraft.wiki
Knockback/Damage/Attribute · Bundle Delimiter semantics · Paper #11055/
#11058/#13270 · MC-52881/MC-14861/MC-202355 · ViaVersion hitbox config ·
Grim #1133 · OCM PR #552.

Known fakes flagged: the "Hypixel Duels 04/12/21 AGC patch notes" thread
(user-authored); the `MinemenCIub` (capital-I) GitHub org; BuiltByBit
integer-scale "69/12/50" troll configs.

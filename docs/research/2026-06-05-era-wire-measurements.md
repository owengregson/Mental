# Era wire measurements — vanilla 1.7.10 / 1.8.9 ground truth

Protocol-level fake clients (legacy-lab harness, `legacy-lab/harness/measure.js`)
against REAL vanilla servers, cross-checked against CFR decompiles of both
jars. Every number below is a measured `entity_velocity` packet (blocks/tick),
not a formula evaluation. This campaign root-caused the 1.3.0 field reports
("vertical too high everywhere, combos impossible") and drives the 1.4.0
model.

## The measured wire values

| Scenario | 1.7.10 ships | 1.8.9 ships |
| --- | --- | --- |
| Standing hit | `(0.2184 h, 0.2751 v)` | `(0.4 h, 0.3608 v)` |
| Sprint hit | `(0.4914 h, 0.3731 v)` | `(0.9 h, 0.4607 v)` |
| Combo hit 2 (gap ~10) | `(0.5101 h, 0.3731 v)` | `(0.9 h, 0.3478 v)` |
| Combo hit 3 (airborne) | `(0.8515 h, 0.2096 v)` | `(0.87 h, 0.42 v)` |
| Charge-combo (both sprint, w-tap) | victim pinned at 0.07–0.27 blocks, v 0.21–0.37 | flat h 0.78–0.87, v declining |

Flights: 1.8.9 standing 1.994 blocks, sprint 4.95; 1.7.10 standing 0.994,
sprint 2.54. **1.7.10 really did hit at roughly half of 1.8.9** — the math is
identical; the wire is not.

## The mechanisms (decompiled + measured)

1. **Grounded equilibrium.** Legacy servers tick full player physics
   server-side (`EntityPlayerMP.onUpdateEntity → super.onUpdate`, both eras),
   input-free. A standing victim's `motY` parks at `(0 − 0.08) × 0.98 =
   −0.0784`, so the standing-hit vertical is `−0.0784/2 + 0.4 = 0.3608` — a
   `0` baseline overshoots every standing hit by ~10%.
2. **Tracker delivery (1.7.10, and rod/projectile on BOTH eras).** The
   velocity packet leaves with the end-of-tick entity tracker, after the
   victim's connection tick has decayed the just-written fields once: ground
   hits lose a full slip step (`× 0.546` horizontal), verticals one gravity
   step. 1.8.9 melee alone sends in `attack()` itself (then RESTORES the
   pre-hit fields — `wn.java`: snapshot, send, put back).
3. **The jump stamp.** Both net handlers treat `serverOnGround &&
   !packet.onGround && Δy > 0` as a jump and OVERWRITE `motY = 0.42`
   (`nh.java:184`, `lm.java:217`), +0.2 facing push while sprinting. A
   knock's liftoff fires it one tick after the hit, so the era baseline for
   airborne victims is the jump impulse free-falling — NOT the delivered
   knock decaying. Measured: 1.8.9 combo hit 2 ships `vy 0.3478` = 0.42
   nine gravity steps later, to four decimals.
4. **One stamp per knock.** Neither era ever re-sent a knock's velocity.
   (Mental 1.3.0's pre-send + authoritative pair shipped two, ~1 tick apart,
   and they could disagree once the latency hint applied to the second.)

## Protocol-archaeology footnotes

- 1.7 serverbound position wire order is `X, FEET, HEAD(stance), Z`
  (`je/jf.java` read order vs the `jd` getters); node-minecraft-protocol's
  1.7 schema names the feet slot `stance`. The clientbound teleport y is
  feet + 1.62 (`nh.java:236`), and the server silently discards all movement
  until the client echoes the teleport (`r` flag).
- 1.21.9+ `entity_velocity` uses Mojang's packed `LpVec3`;
  node-minecraft-protocol ≤ 1.x decodes the middle word little-endian where
  Mojang writes `writeInt` (big-endian) — every modern velocity decodes as
  garbage without the harness's codec patch (worth an upstream report).
- Join invulnerability gates everything for 60 ticks (`bQ` field, both eras).
- Modern (1.21.4+) bots must ACK chunk batches and send `player_loaded`, or
  the server ignores movement and player commands (`hasClientLoaded` gate).

## How to re-run

```bash
cd legacy-lab
# vanilla servers (Java 17 runs both)
(cd srv-1.7.10 && java -Xmx512M -jar ../vanilla-1.7.10.jar nogui &)
(cd srv-1.8.9  && java -Xmx512M -jar ../vanilla-1.8.9.jar nogui &)
cd harness && npm install
node measure.js 1.7   25710 sprint
node measure.js 1.8.8 25890 combo 11 4
node measure.js 1.21.11 25999 charge-combo 11 4 60   # Mental + 60ms injected ping
```

---

## Addendum (2026-06-05, second pass): the sprint cell, measured for real

The original acceptance above carried a caveat: the modern bot's sprint
"never engaged", so every modern sprint number was the standing wire plus an
analytically-applied delta. A live report ("almost no knockback in sprint
trades") forced that cell closed. Two harness bugs — not plugin bugs — had
made a true modern sprint hit impossible to stage:

1. **1.21.6+ compacted the `entity_action` enum** when sneak moved into
   `player_input`: `start_sprinting` is **1** (was 3), `stop_sprinting` **2**
   (was 4). The old numeric 3 lands on `start_horse_jump`, which a server
   silently ignores for a player not on a horse. nmp models the field as a
   protodef mapper whose write side takes the NAME, not a number
   (`'start_sprinting'` → varint 1, verified by serializer byte-dump).
2. **Paper's `ServerPlayer.swing()` resets the attack-strength ticker**, and
   the bot swung before attacking. Real clients attack first and swing after
   (`Minecraft.clickMouse`, every era). A swing-first bot attacks with a
   ~0.1 meter: vanilla then skips the sprint-knockback and crit branches
   (`isSprinting() && attackStrengthScale > 0.9`) and plays
   `entity.player.attack.weak` — while the sprint FLAG is genuinely true.

The attack-branch **sound oracle** (which `entity.player.attack.*` the victim
hears) discriminates uncharged/sprint/crit/sweep without any server access,
and is now in the harness behind `WATCH_SOUND=1`. Map sound ids through the
server's own `--reports` registry dump — minecraft-data's `sounds.json` ids
were stale for 1.21.11 (1246 is `attack.weak`, not `attack.sweep`).

### Measured with the fixed instrument (all single-stamp, victim wire)

| scenario, Paper 1.21.11 | wire (h, vy) | reference |
| --- | --- | --- |
| bare vanilla, standing | (0.4000, 0.3608) | modern non-sprint |
| bare vanilla, sprint | (0.7000, 0.4000) | modern sprint (dm/2 + 0.5, vy capped 0.4) |
| Mental legacy-1.7, sprint | (0.4914, 0.3732) | **= real 1.7.10 (0.4914, 0.3731)** |
| Mental legacy-1.8, sprint trade | (0.9000, 0.4608) | **= real 1.8.9 (0.9, 0.4607)** |
| Mental kohi, sprint trade | (0.4231, 0.3095) | flat per hit, no compounding |

Trade combos through Mental legacy-1.7 compound exactly like the era server:
hit1 0.4914 → hits 2-4 in the 0.55–0.89 band on both Mental and real vanilla
1.7.10 under identical staging (per-hit variance is gap-phase noise — the
victim's airborne state at each hit).

### The trade-opener geometry (why "no knockback" is sometimes era-correct)

The 1.7.10 formula is two opposed terms at close range: the base 0.4 pushes
the victim away from the **attacker's position**, the sprint extra 0.5 pushes
along the **attacker's yaw**. A victim who has charged past the attacker gets
the base term pointing backward and the sprint term pointing forward:
measured hit1 at 0.14 blocks separation = **0.0546 horizontal** — on real
vanilla 1.7.10 semantics, by the same two terms. At realistic first-hit range
(2.4–2.8 blocks) the terms agree and the full 0.4914 ships. Face-hug trades
producing near-zero openers is authentic 1.7.10 behavior, not a delivery bug.

### 26.1.2 (protocol 775) chain verification

No 775-speaking protocol client exists (nmp ≤ 1.66.2 tops at 1.21.11), so the
user-version chain was closed link by link instead:

- PacketEvents 2.12.1's `LpVector3d` is byte-identical to
  `net.minecraft.network.LpVec3` (same pack `round((d*0.5+0.5)*32766)`, same
  bit lanes 3/18/33, same `shortLE + intBE` order, same continuation varint;
  only the zero-collapse threshold differs by 1/32766 vs 2⁻¹⁵ — harmless).
- PE 2.12.1 ships an explicit 26.1 table: `ENTITY_VELOCITY` 0x65,
  `BUNDLE` 0x00, `HURT_ANIMATION` 0x2a — all equal to the 26.1.2 server's
  own `--reports` packet dump.
- The 26.1.2 **client** (unobfuscated since 26.x) applies a self-targeted
  `SET_ENTITY_MOTION` unconditionally: `handleSetEntityMotion` →
  `entity.lerpMotion(v)` → `setDeltaMovement(v)`, no local-authority gate
  (unlike position-sync, which has one).
- The event-level pipeline on 26.1.2 Paper itself is covered by the
  integration matrix (9/9 incl. 26.1.2 and 26.1.2+OCM).

### What the presets mean for trade feel

Vanilla 1.7.10's sprint wire is **half** of 1.8.9's — that is the measured
era truth, and `legacy-1.7` reproduces it to four decimals. The "1.7 PvP"
the community remembers as comboable ran on modified spigots (Kohi, MCSG,
eSports forks) with roughly 1.8-strength horizontal. Servers wanting
combo-friendly trades should run `legacy-1.8` (the full 1.8.9 wire,
era-verified) or `kohi`; `legacy-1.7` is the vanilla-1.7.10 museum piece.

---

## Addendum 2 (2026-06-05, third pass): the tracker decay is JOIN-ORDER
## BIMODAL — every claim above that reads "1.7.10 ships every knock
## tracker-decayed" is half the truth

A live report ("legacy presets barely knock back — players in 1.8 did not
end up 1 block away") forced a re-audit of the campaign itself. The original
runs only ever staged ONE connection order — `measure.js` hard-connected the
attacker first, the victim second — and that order turns out to select which
of two wires vanilla 1.7.10 ships.

### The mechanism (decompiled: `MinecraftServer.v()`, `nc.c()`, `ej.a()`, `nh.a(jd)`, `mw.h()/i()`)

Per 1.7.10 tick: the `"levels"` phase ticks worlds and then the per-world
entity **tracker** (`mt2.r().a()`) — which ships any pending
`velocityChanged` motion — and only THEN the `"connection"` phase
(`nc.c()`) iterates connections **in join order**, each slot draining that
connection's queued packets and then running that player's physics
(`nh.a(jd)` → `mw.i()` → `super.h()`; the world-phase `mw.h()` is gutted).
So after an attack stamps the victim's fields:

- **victim's slot AFTER the attacker's** (victim joined later): the victim's
  physics runs in the same connection phase, decaying the stamp once —
  next tick's tracker ships the DECAYED wire.
- **victim's slot BEFORE the attacker's** (victim joined first): the
  victim's physics for that tick already ran pre-hit; the tracker ships the
  stamp **UNDECAYED** — byte-identical to the 1.8.9 wire.

### Measured (real vanilla, `JOIN=victim-first` vs default; 3/3 deterministic reps each)

| scenario, 1.7.10 | victim joined AFTER attacker | victim joined BEFORE attacker |
| --- | --- | --- |
| standing | `(0.2184, 0.2751)` → 0.994 blocks | `(0.4000, 0.3608)` → 1.994 blocks |
| sprint | `(0.4914, 0.3731)` → 2.54 blocks | `(0.9000, 0.4607)` → ~4.9 blocks |

1.8.9 control: `(0.4, 0.3608)` / `(0.9, 0.4607)` in BOTH orders — the
in-attack send bypasses the tracker entirely, which is exactly why 1.8.9
knockback was remembered as *consistent* and 1.7.10's as erratic (the era's
"relog for less knockback" folklore is literally this: relogging appends
you to the connection list, putting your physics slot after your
attacker's).

### What this changes

- "1.7.10 really did hit at roughly half of 1.8.9" (above) is true only for
  victims who joined after their attacker — a per-pairing coin flip on a
  real server. The dominant remembered experience, and the only consistent
  era wire, is the FULL stamp.
- `KnockbackDelivery.TRACKER` therefore ships the full stamp as of 1.5.0;
  the decayed wire is preserved as the opt-in `tracker-decayed` (the
  later-joiner mode). Determinism is the product — Mental does not
  replicate the coin flip.
- The 1.4.0-era suite pins to `(0.4914, 0.3731)` et al. were pins to the
  artifact mode and have been re-pinned to the full stamp.
- Combo compounding (the no-restore ledger residual) exists in BOTH join
  orders and survives unchanged; it remains the genuinely-1.7 mechanic.

Re-run either mode:

```bash
node measure.js 1.7 25710 standing                     # attacker-first (decayed wire)
JOIN=victim-first node measure.js 1.7 25710 standing   # victim-first (full wire)
```

---

## Addendum 3 (2026-06-05, fourth pass): the five canon 1.8 scenarios, and
## three fast-path era bugs they flushed out

Owner-supplied canon distances for vanilla 1.8 (sources + own demo testing)
validated against the REAL 1.8.9 jar and Mental `legacy-1.8` on Paper
1.21.11, with the harness's new `double-*` scenarios (second hit at gap 10,
"right as invuln ends", attacker chasing) and touchdown/settle reporting:

| scenario | canon | real 1.8.9 (touchdown / settle) | Mental legacy-1.8 |
| --- | --- | --- | --- |
| plain single | ≈1.5 | 1.788 / 1.994 | 1.994 (wire-identical) |
| sprint single | ≈4.4 | 4.599 / 4.948 | 4.948 (wire-identical) |
| plain double | ≈3.4 | 3.473 / 3.699 | 3.782 (landing-tick straddle) |
| sprint double, no w-tap | ≈7.4 | 6.893 / 7.205 | 7.153 |
| sprint double, w-tap | ≈11.6 | 10.689 / 11.648 | 11.881 |

The canon numbers sit inside the touchdown↔settle envelope everywhere —
"lands about X blocks away" eyeballs the touchdown, the settle adds the
ground slide. Three Mental defects surfaced on the way to that table:

1. **The fast path never cleared the attacker's sprint flag.**
   `Player.attack` ends every sprint-bonus hit with `setSprinting(false)` —
   the server half of the w-tap mechanic — and the fast path cancels
   `Player.attack`. Before the fix, no-w-tap and w-tap doubles measured
   IDENTICALLY (both 10.09); real 1.8.9 separates them by 4.4 blocks.
   `KnockbackModule` now clears the flag after every accepted melee hit
   from a sprinting player (owning thread, after every flag read).
2. **The deferred damage read the sprint flag after the client's own
   post-attack drop.** A faithful client sends STOP_SPRINTING in the same
   flush as the attack; vanilla read the flag INSIDE `Player.attack`,
   ahead of that packet — Mental's owning-thread damage ran after the
   inbound queue and lost the race, so perfectly-timed (invuln-boundary)
   sprint hits shipped plain. The fast path now stamps the tick-frozen
   snapshot's sprint state at registration (`SprintTracker.stampAttackSprint`)
   and the authoritative pass consumes it.
3. **The ground watcher judged knock liftoffs `rising=false`.** Modern
   servers flip a knocked player's own onGround flag on the hit tick,
   before the client's first airborne movement packet arrives (the era
   flag was packet-driven only); the per-tick sampler saw
   flag-flipped-but-unmoved and anchored the ledger at the equilibrium
   instead of stamping the 0.42 jump impulse — combo second hits shipped
   vy ~0.07 where real 1.8.9 ships 0.2846. The watcher now defers the
   transition until the client's position actually moves, reconstructing
   the exact packet pair the era jump bookkeeping evaluated.

Lab-hygiene findings with debugging-round costs: the matrix run worlds
spawn hostile mobs on the dark arena platform (a zombie's 2.5 damage and
full-strength 0.4 knock at an arbitrary bearing read as phantom velocity
events — Arena now forces `doMobSpawning false` and purges nearby
monsters), and 1.20.6 does not tick clientless players outside the arena's
original chunk (actors stay below x,z 112; the platform extends only along
the knock direction).

## Addendum 4 (2026-06-06, fifth pass): combo VERTICALS and the era's
## within-tick attack ordering — plus the two OCM defaults that bury it all

The owner's live report ("too much vertical knockback when trying to
combo" on legacy-1.8 + OCM, and legacy-1.7 likewise) prompted a per-hit
vertical sweep none of the earlier passes had run: `chain-plain` (N plain
hits at the legal cadence, attacker chasing) with per-hit wire vy and
per-flight apex tracking.

### The era truth: combo verticals DECLINE, and the boundary hit reads
### the PRE-landing flight

Real vanilla 1.8.9, plain 4-chain at gap 10 (both join orders):

| | vy per hit | apexes | settle |
| --- | --- | --- | --- |
| attacker-first | 0.3608, **0.2477**, 0.3608, **0.2477** | 0.97, **0.50**, 0.97, 0.50 | 7.145 |
| victim-first | 0.3608, **0.2846**, 0.3608, 0.2846 | 0.97, 0.95*, 0.97, 0.95* | 9.095 |

(*victim-first's flights chain mid-air — phase shift, not a higher knock;
the wire vy still declines.) The spam-combo norm throws the next hit the
same tick the previous flight touches down, and the era read that hit's
victim state PRE-landing: legacy servers processed an attack in the
attacker's connection slot BEFORE the victim's same-tick movement packets
applied. A landing arriving a tick earlier reads grounded on era servers
too — the boundary is phase-chaotic at sub-tick grain, but the wire NEVER
re-stamps a grounded 0.3608 on a hit that races its own touchdown packet.

### Mental's three boundary bugs (all fixed, wire-verified)

Pre-fix, the same staging on Paper+Mental legacy-1.8 shipped
0.3608/0.3608/0.2862/0.3608 (apexes ~0.97 across the board, settle 8.3
vs era 7.1) — the floaty-combo signature, decomposed:

1. **The snapshot's invulnerability predicate was stale by one tick.**
   `noDamageTicks` freezes before the tick's decrement, so a boundary-legal
   hit read `nd = 11 > max/2` and skipped its pre-send; the authoritative
   next-tick fallback then computed from post-landing state. Fixed:
   `isDamageImmune` allows the staleness (`nd > max/2 + 1`) — phantom-safe
   because the deferred damage always runs ≥1 tick after the freeze, so
   every admitted hit arrives there with `nd <= max/2` (gap-9 spam pin:
   five thrown, three shipped, each paired with a damage tick).
2. **The `auto` feedback window equaled the legal cadence.** Perfect-cadence
   hits (500 ms apart on a 20-tick window) raced the gate boundary on
   millisecond jitter. Fixed: auto = `(max/2 − 1)` ticks.
3. **The tick sampler applied transitions a sample late and instantly.**
   The jump stamp landed a tick after the era's packet-driven bookkeeping
   (airborne verticals ran ~0.04 high; worse at ping), and a landing was
   visible to a same-tick attack the era would have judged first. Fixed
   structurally: `GroundPacketTap` feeds the watcher the client's own
   movement/sprint packets on the netty thread in arrival order (the era
   handler's exact cadence), records are tick-stamped, and the snapshot
   freeze reads `currentExcludingTick` — the residual as of the END of the
   previous tick, which is precisely the era's attack-slot view. The
   sampler stays as the fallback for packetless players (fake-player
   records carry NO_TICK and keep the inclusive view, so the suites pin
   unchanged behavior).

Post-fix wire (same staging): 0.3608, **0.2492** (era 0.2477 — one short
quantum), 0.3608, 0.3608 with settle 7.317; the canon five all hold or
tighten (standing 1.994, sprint 4.948, plain double 3.78, no-wtap double
**7.205** vs era 7.21, wtap double **11.671** vs era 11.65 — the double
seconds now ship era airborne verticals, 0.2862/0.3861, instead of
0.4-capped re-stamps).

### The OCM defaults that reproduce the symptom on a live box

Both ship enabled out of the box and both read as "Mental's profile is
wrong":

- **`old-player-knockback` is in OCM's default `old` modeset** (and
  `__default__` worlds default to `old`): OCM owns every melee knock,
  Mental yields by design, and BOTH profiles go inert for melee. OCM's
  formula reads `victim.getVelocity()` — the server's stale deltaMovement,
  a zombie field for real clients — measured wire: vy 0.3608/0.3608/
  0.1889/0.3608 with horizontals wandering 0.40→0.45. Erratic, never the
  era decline. Fix: remove `old-player-knockback` from the modeset lists
  (the fork validates placement — move the line into `disabled_modules`).
- **`attack-frequency` ships `playerDelay: 18` in `always_enabled_modules`**
  — a 9-tick combo cadence server-wide where native 1.8 is 20/10. One less
  free-fall tick per gap raises every combo vertical even when Mental owns
  the knock. Fix: `playerDelay: 20`.

Mental now warns at startup when either condition holds (OcmCompatModule
`warnFeelOverlaps`) and `/mental kb` flags melee-knockback ownership.

### Harness notes

`chain-plain` + per-flight apex tracking live in `measure.js`. PING_MS
delays only the play-ping probe channel (the server's MEASURED latency,
which drives the compensation hints) — it is not transport delay; the
boundary-ordering model is transport-invariant because it keys on packet
ARRIVAL order, which ping shifts uniformly.

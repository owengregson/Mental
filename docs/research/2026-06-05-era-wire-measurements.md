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

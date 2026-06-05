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

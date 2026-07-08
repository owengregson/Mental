# The ledger-vy divergence — live diagnosis (2026-07-07)

Live packet-level diagnosis of WHY the MotionLedger's vy estimate at melee-hit
time runs far more negative than the victim's real flight — the input
infidelity underneath the downward-knock family (the 2.4.7 practice floor and
the 2.4.8 whole-bundle floor mask the symptom; this documents the cause and
the designed input fix for the next round).

Method: instrumented Mental (per-hit `ledgerVy` vs measured `PositionRing` Δy
vs ground-feed state vs undrained inbox) on Paper 1.21.4, SimpleBoxer
boxer-vs-boxer, 914 hits across legacy-1.8 and legacy-1.7, staged as a wall
juggle (the open-arena combo is FAITHFUL — the victim lands cleanly between
hits and the ledger resets; the divergence needs a victim that never touches
ground).

## Verdict

**The dominant mode is model over-decay of a JUGGLED victim.** During
15–28-tick airborne stretches the ledger free-falls from the last stamp toward
the −3.92 terminal, reaching −0.96…−1.50, while the real victim hovers at
−0.05…−0.34 (re-lifted by each knock). On legacy-1.8 (`combos=false`)
`Deliveries.recordDelivered` never records melee knocks, so nothing resets the
decay clock; no landing fires because the victim genuinely never lands.
Divergence up to 1.16 b/t → `y = vy·friction.y + base.vertical` ships
downward.

- **Missed/late landing — REFUTED.** `undrainedInbox=[]` on 100% of divergent
  hits; the sampler enqueues and `tickStep` drains the Landing in the same
  `SessionService.tick`; landings fire correctly on real touchdown.
- **Non-rising liftoff mis-stamp — CONFIRMED secondary accelerant.**
  `GroundFsm.onMovement` (`rising = knownY > lastY`) stamps a knock-induced
  liftoff at the −0.0784 free-fall equilibrium when the sampled Δy read
  non-rising against the wall — the decay then starts deep instead of at
  +0.42.
- **legacy-1.7 barely diverges** (0/127 downward): `combos=true` records every
  melee knock, resetting the decay clock (max 12 ticks-since-record vs 28).
  A combo gap >~16 airborne ticks would still cross −0.8.
- The SimpleBoxer victim is PACKETLESS to Mental (fake PacketEvents channels;
  0 rim taps) — it rides the tick-sampler ground feed, so rim/netty inbox
  ordering never applied to the reported scenario.

| profile | hits | airborne-read | downward shipped | \|ledger−measured\|>0.30 airborne | worst ledgerVy |
|---|---|---|---|---|---|
| legacy-1.8 (combos=false) | 608 | 145 | 5 | 12 | −1.504 |
| legacy-1.7 (combos=true) | 127 | 65 | 0 | few | −0.445 |

## Exemplar traces

```
# FAITHFUL (inert control)
ledgerVy=-0.30153 measuredRingVy=-0.30998 grounded=false shippedVy=+0.34923 ticksSince=8  last=LIFTOFF(+0.42)

# BUG (dominant mode): juggled victim, ledger free-falls, DOWNWARD ship
ledgerVy=-0.96345 measuredRingVy=-0.04836 grounded=false shippedVy=-0.08173 ticksSince=18 last=LIFTOFF(+0.42)
ledgerVy=-1.50428 measuredRingVy=-0.34144 grounded=true  shippedVy=-0.25214 ticksSince=28 last=LIFTOFF(+0.42)
# landing fires the NEXT tick — it explains 1 tick, not the −1.50

# BUG (accelerant): non-rising liftoff mis-stamp
APPLY LIFTOFF stampVy=-0.07840
ledgerVy=-1.13946 measuredRingVy=-0.14371 grounded=false shippedVy=-0.06973 ticksSince=15 last=LIFTOFF(-0.0784)
```

## The designed input fix (next round — NOT yet implemented)

**A measured-reality clamp on the ledger-vy read at the capture seams:**

```
vyRead = max(ledgerVy, measuredRingVy − MARGIN)     // MARGIN ≈ 0.15
```

with `measuredRingVy` = the victim's `PositionRing` recent-2 per-tick Δy (the
same doctrine as `FastPotsUnit.throwerVelocity` and the strafe heading).
Seams: `EntityStates.captureVictim` (region path) and the fast-path
`preVictimState` twin (the published view would carry the measured vy — the
view's established additive growth pattern).

Why this one: era servers computed knockback from the victim's real
server-side motY, which tracked the real client; the ring Δy IS that measured
truth, and the ledger is only a latency-compensated estimate of it — bounding
the estimate by the measurable truth makes the input MORE era-faithful.
Inert-when-faithful, quantified: it would have bitten 5/608 hits on legacy-1.8
and 0/127 on legacy-1.7 in this capture — exactly the leak class. Vertical
only: the vx/vz residual law, decay machine, and combo compounding are
untouched. MARGIN absorbs the ring's sub-tick under-read so a faithful
rising/short-air hit is never clamped.

Rejected with evidence: draining/peeking pending ground events before the read
(a no-op — the inbox is empty) and retroactively applying a wire landing at
read time (no landing exists to apply — the victim genuinely hasn't landed).

**Implementation cautions for the round that builds this:**
- The clamp interacts with the LIVE floor scenarios in KnockbackSuite: their
  float-staged ledger free-fall diverges from the fake's measured motion BY
  DESIGN, so the clamp would defuse the staged leak class. The scenarios must
  restage as a genuine measured plummet — teleport the fake downward ~1 b/t
  from a high airspace so the ring Δy and the ledger fall TOGETHER (this also
  works on ≤1.10.2 where clientless fakes have no physics: teleports are
  position writes, and the packetless ground feed is position-based).
- With corrected (higher) verticals on juggle-class hits, the pocket servo's
  verticalStamp activates where it previously declined (airTime ≥ 3) — a
  juggle-feel change that needs owner playtest.
- Also worth fixing in the same round: the non-rising liftoff mis-stamp
  (GroundFsm stamping a knock-liftoff at −0.0784).

## Staging traps (for the next live round)

- PaperMC v2 download API is sunset — use the v3 `fill.papermc.io` endpoint;
  a copied cached run dir fails paperclip's classpath derivation
  (`joptsimple/OptionException`) — fresh paperclip download.
- SimpleBoxer boxers are packetless to Mental (fake PacketEvents channels).
- Open-arena combos are faithful — the divergence needs a wall juggle: pin a
  `dummy` boxer against a wall, `movement stand`, one `aimbot`/`rush`
  attacker, `wtap false`, reach ~4 (wtap/high-KB pushes the victim out of
  juggle range).
- Headless console: `mkfifo` held open by a `tail -f /dev/null` writer.

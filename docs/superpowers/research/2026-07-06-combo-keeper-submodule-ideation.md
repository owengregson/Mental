# Combo-keeper submodule ideation

**Date:** 2026-07-06
**Status:** ideation / ranked proposal — no code, no owner sign-off yet
**Family:** `Family.COMBO` (all candidates default OFF, era-exact no-op at default knobs)

## Purpose

Propose new **combo-keeping** submodules beyond the two we already ship, each
grounded in a *real* mechanic of how legacy (1.7/1.8) combos are kept or broken.
The owner has been explicit: he wants **sweet-spot / geometry / knockback-shaping**
keepers, **not** arcade crutches (hit-delay, trade-dampening, registration-grace,
combo-mode `maxNoDamageTicks` juggling are all rejected). Every idea below is a
placement/timing/direction shaper, not an assist.

## Grounding

- Era physics + wire: `.claude/skills/legacy-motion-physics`, `.claude/skills/era-accuracy`;
  `docs/research/2026-06-05-era-wire-measurements.md`, `docs/research/2026-06-06-combat-compendium.md`.
- The pocket-servo geometry: `docs/superpowers/specs/2026-07-04-combo-hold-pocket-servo-design.md` §2,
  `docs/superpowers/research/2026-07-04-pocket-servo-precision-derivation.md` §3.
- Shipped seams (verified this session): `ComboTracker` / `ComboSnapshot` / `ComboRules`
  (`kernel/.../combo/`), `KnockbackEngine.computePaced(...)` (the shared shaping seam,
  `fresh = (base − rangeTaper) × pace × combo`), `PocketServo` (constants: `HIT_EDGE 3.0`,
  `VICTIM_REACH 2.9`, `target 2.85`, `hitCap 2.95`, `LANDING_SLACK 0.17`), `PlayerView`
  (the only cross-domain read; carries `motion`, `measuredVx/Vz`, `grounded`, `groundedTicks`,
  `slipperiness`, `sprinting`, `yaw`, `yawRateDegPerTick`, `eyeHeight`, `kinematics`,
  `comboAttackerId`, `noDamageTicks`, `maxNoDamageTicks`, `moveSpeedAttr`, `pingMillis`),
  `JournalEntry.comboFactor`, the `ComboReachHandicap` three-leg reversal pattern.

---

## The geometry we are shaping (the reach triangle)

A legacy combo lives or dies on one asymmetric triangle (derivation doc §3.1). With
the victim launched to feet height `y(t)` and horizontal separation `h`:

- **Attacker's shot is the flat base.** The victim's tall AABB (1.8) presents a point
  near the attacker's aim level, so the attacker connects while `h ≤ r_a` (≈ 3.0),
  nearly independent of the victim's elevation.
- **Victim's answer is the hypotenuse.** The victim, lifted by `Δ(t) = max(0, y(t) − 0.18)`,
  must aim *down* at the attacker's head: they answer only iff
  `h ≤ h_safe(t) = 0.3 + √(r_v² − Δ(t)²)`, with practical `r_v ≈ 2.9`.
- **Un-retaliatable band:** `√(r_v² − Δ²) < h ≤ r_a`. It is empty when `Δ ≈ 0` (a
  low victim at `h = 2.9` answers freely) and **widens as `Δ` grows**. At era launch
  heights `√(r_v² − Δ²) ≈ 2.7–2.8`, so the servo's `target 2.85` sits just inside the
  band; staying hittable needs `h ≲ 2.95`.

A combo therefore breaks in exactly four ways, each mapping to a lever:

| Break | Geometric cause | Lever | Status |
|---|---|---|---|
| Victim knocked past `r_a` | `h` too large | **horizontal placement** | `combo-hold` (servo) ✅ |
| Victim answers back | `r_v` too generous vs `h` | **reach radius** | `combo-reach-handicap` ✅ |
| Victim drops low / floats out | `Δ` wrong (band collapses or victim leaves reach) | **elevation** | *untapped* |
| Victim scatters off-axis / face-hugs | wrong **direction** of `h` | **push direction** | *untapped* |

Plus two non-geometric legs the servo can't touch: **cadence timing** (the era
i-frame rhythm that decides whether a rhythm hit even carries knock) and **combo
initiation** (the servo only engages from the active state — hits 1–2 are unshaped).

The candidates below fill the two untapped geometric legs, the two timing legs, and
add one safety clamp. Every one shapes the **fresh** knock only (never the residual —
the A3 law) and gates on `PlayerView.comboAttackerId == this attacker` (zero-touch
when no combo is held).

---

## Candidate 1 — `combo-vertical-trim`

**Summary:** A combo-gated, damp-only shaper on the *fresh vertical* knock that
enforces the era's declining-vertical profile, holding the victim in the low,
re-hittable bob instead of floating up out of the attacker's reach.

**Mechanic it exploits/defends — knockback verticality + the elevation leg (Δ).**
Era combo verticals *decline* (the jump stamp free-falls; 1.8.9 combo hit 2 ships
vy ≈ 0.25–0.28, apex dips to ~0.5 every other hit — measured). Victims stayed *low*.
The live "combos feel floaty / too much vertical" symptom is exactly a victim whose
vertical *fails* to decline (boundary hits shipping grounded re-stamps, OCM owning
the knock). When `Δ` climbs, the victim eventually crosses out of the attacker's
usable vertical reach and the chase collapses — the vertical mirror of the
horizontal blow-out.

**Mechanism.** Owned by the same D2/kernel seam as the servo. At
`KnockbackEngine.computePaced(...)` (engine step 5, beside the existing airborne
`air.vertical` multiplier), introduce a *combo-gated* airborne-only factor
`τ ≤ 1.0` applied to the **fresh vertical add** (never SET verticals, never the
`vy·f` residual carry). It reads: the victim's shipped vertical stamp,
`kinematics().distanceToGround()` and `motion().vy()` (rising vs falling),
`groundedTicks()`. It writes: a clamped `τ` into the vector and a new
`JournalEntry.verticalTrim` field (old-arity additive ctor defaults 1.0). The
target is a *ceiling* on predicted apex `Δ`, not a lift; when the natural era
vertical already declines, `τ = 1.0` and nothing happens.

**Why it keeps the combo.** Capping `Δ` at the top of the band keeps the victim
inside the attacker's flat-base reach on every rhythm hit while still buying the
`Δ²` margin that denies the victim's hypotenuse. It removes the single most common
modern combo-break (the victim floating up and away), *and* it restores era feel —
the low bob is what made 1.7/1.8 combos hold.

**Era-authenticity risk — LOW, and arguably era-restoring.** Damp-only means it can
never add lift, so it cannot manufacture the floaty/arcade feel; it only ever pushes
the vertical profile back *toward* the measured era decline. `parse(empty)` → trim
disabled (`τ = 1.0` always) = byte-identical legacy. **The tempting anti-era edge:**
a *lift-floor* variant that *holds* `Δ` elevated to widen the band would be higher
raw value but directly contradicts the measured declining-vertical truth (victims
did touch down) and would read as arcade float — do **not** ship a floor. This
module is the trim, not the floor.

**Feasibility — 2.** The airborne-multiplier machinery already exists in engine
step 5 and in presets (`signature`'s `air.vertical 0.98`); this is that machinery
made combo-gated and target-driven. No packet/NMS work. Tension: must be pinned in
`KnockbackEngineTest` as a strict no-op at default and proven never to touch SET
verticals or residual; the era suite (EraParitySuite) must confirm the declining
profile is preserved, not distorted.

---

## Candidate 2 — `combo-line-hold`

**Summary:** A directional shaper that suppresses the *lateral* (perpendicular-to-
attacker-aim) component of the fresh knock so the victim tracks straight down the
attacker's line, defeating the off-axis scatter and face-hug breaks.

**Mechanic it exploits/defends — knockback direction (base-from-position vs
sprint-along-yaw) and the face-hug degeneracy.** Era knockback direction is the base
`0.4` away from the attacker's **position** plus the sprint `0.5` along the attacker's
**yaw** (compendium). When the attacker circles/strafes, those two diverge and the
victim gets flung off the attacker's forward line — a legitimate scatter break. The
extreme is the face-hug: a victim who ends up *past* the attacker gets ~0.05 h
(near-cancelled direction) and breaks free; the horizontal servo can't fix this
because scaling a degenerate ~0.05 vector by 1.2 is still ~0.06.

**Mechanism.** At the direction step (engine step 2/4), decompose the fresh
horizontal into components parallel and perpendicular to the attacker's aim line
(from `PlayerView.yaw()` + attacker→victim vector). Apply a combo-gated factor
`λ ∈ [0,1]` to the *perpendicular* component only (never the magnitude of the
parallel push), then renormalize so total fresh horizontal magnitude is preserved
(the servo still owns magnitude). Reads: attacker yaw and relative position; writes:
a rotated direction unit vector + `JournalEntry.lineFactor`. `λ = 1.0` = no
suppression = era.

**Why it keeps the combo.** Straightening the push keeps the victim directly ahead
of the attacker's line so the attacker's next step-in lands cleanly, and it converts
the face-hug degeneracy into a normal boundary hit instead of a free escape — the
victim can never "wrap around" the attacker to break out.

**Era-authenticity risk — MEDIUM (this is the closest to the arcade line).**
Directional scatter is a *real skill mechanic* — good comboers avoid it by
positioning, so automating anti-scatter is an assist, not pure geometry. It changes
feel (circling no longer flings the victim). Make it era-exact-no-op by default
(`λ = 1.0`) and gate it behind a loud opt-in; recommend a conservative default `λ`
(e.g. 0.7, partial suppression) if enabled, never full lock. Flag it explicitly as
the candidate nearest the owner's rejected-assist boundary.

**Feasibility — 3.** Pure kernel direction math, but the decompose/renormalize must
be unit-pinned and proven not to alter magnitude (that's the servo's job) or leak
into the residual. Needs the attacker→victim vector at the compute seam (available
from position rings / published views).

---

## Candidate 3 — `combo-source-freshness`

**Summary:** Compute the knockback *direction origin* from the attacker's
lag-compensated **current** position rather than the stale hit-time position, so a
chasing attacker pushes the victim along their *actual* forward line — a
latency-honest refinement, not an assist.

**Mechanic it exploits/defends — direction-from-source-position under latency.**
Era direction is "away from the attacker's position." During a fast chase the
attacker is advancing every tick; by the time the knock resolves, the attacker has
moved, so a direction computed from the *hit-time* position pushes the victim from
where the attacker *was*, nudging them slightly off the attacker's current line and
seeding drift that compounds over a combo. Mental already rewinds for reach
validation — this extends the same honesty to the direction origin.

**Mechanism.** At the direction step, when a combo is active, source the attacker's
position from the lag-compensated horizon already used by reach validation instead
of the raw hit-time coordinate. Cleanest on the netty pre-send (D1): the attacker is
the connection that owns the packet, so its own position ring is "its own state"
(domain-legal). At the region authoritative pass (D2), read the attacker's *published*
`PlayerView` position (never a live entity). Writes: the direction vector only;
journal a boolean `sourceFresh`. Default OFF → hit-time position = today's behavior.

**Why it keeps the combo.** Pushing the victim along the attacker's true forward line
(not their stale line) keeps the victim centered in front of the chaser, so the next
step-in stays in the pocket instead of accumulating lateral drift — a tighter combo
purely by being latency-correct.

**Era-authenticity risk — LOW.** It is still "push away from the attacker's position,"
just the *honest* position. Arguably *more* era-faithful than shipping a stale origin
(on a zero-latency LAN the two positions coincide → identical to era). No new force,
no magnitude change. Default OFF preserves current behavior exactly. Fits Mental's
core latency-compensation thesis better than any other candidate.

**Feasibility — 3.** The lag-comp position infra exists (reach rewind, position
rings), but wiring the attacker's compensated position into the victim's
direction-compute across both realms (netty own-ring; region published-view) needs
care to stay inside the single-writer domain rules. Unit-pin: on zero drift the
direction is identical to hit-time.

---

## Candidate 4 — `combo-blowout-guard`

**Summary:** A cheap, era-exact predictive **ceiling** on total fresh-horizontal
displacement while a combo is active, so a stray over-strength hit can never launch
the victim past the attacker's reach and end the combo.

**Mechanic it exploits/defends — the "knocked too far" break under knockback
variance.** The servo solves for a *target*, but real hits carry variance the solve
doesn't fully own: a KB-enchant level, an ice-lane residual (ice nearly doubles 1.7
knockback), packet jitter, or a mis-predicted friction branch can over-launch the
victim past `r_a` in a single hit. That's the classic "knocked out of reach" break.

**Mechanism.** At `computePaced(...)`, after the servo/pace factors resolve, compute
the predicted terminal separation for this hit (the servo already predicts this) and,
if it exceeds `capEff = HIT_EDGE − 0.5·chaseEma − LANDING_SLACK` (the same cap the
dynamic servo already derives, `PocketServo.capEff(...)`), rescale the fresh
horizontal down so predicted separation lands at the cap. It is a one-sided *max*
clamp — it only ever *reduces* an over-launch, never boosts. Reads: the servo's own
`FlightPrediction`; writes: reuses `comboFactor` (or a distinct `blowoutFactor`
journal field). Default cap = `+∞` (disabled) → no-op.

**Why it keeps the combo.** It guarantees no single hit ejects the victim past the
attacker's reach, so the combo survives knockback variance the precise servo can't
anticipate. It hardens *every* other COMBO module (and the servo itself) against the
one failure they all share.

**Era-authenticity risk — LOWEST.** A one-sided cap that only clamps *toward* the
era reach boundary is era-faithful by construction (it prevents non-era over-launch,
never adds force). `parse(empty)` → cap `+∞` = byte-identical legacy. No feel change
in the common case; it only bites on the outlier hits that would have broken the
combo anyway.

**Feasibility — 1.** Reuses the servo's existing prediction and `capEff`; a few lines
at the compute seam plus one journal field and a no-op-at-default pin. It could even
ship as a bolt-on mode of `combo-hold` rather than a separate `Feature`, but a
distinct default-OFF `Feature` keeps it independently auditable and usable without
the full servo.

---

## Candidate 5 — `combo-iframe-cadence`

**Summary:** While a combo is held, pin the victim's damage-invulnerability window to
the **era 20-tick** cadence, so every rhythm hit that lands on the era beat carries
knock and hits inside the window correctly carry none — keeping the era combo rhythm
honest on servers where i-frames have drifted.

**Mechanic it exploits/defends — no-damage-ticks / invulnerability frames.** Era
`maxNoDamageTicks = 20`: a hit landing while the victim is still invulnerable deals
only difference-damage with **no knockback and no flinch** (compendium). The era
combo cadence (~10-tick spam) is tuned to that window — the attacker's rhythm hits
land just as i-frames clear, each carrying full knock. If something shortens the
window (OCM ships `playerDelay 18`, an arcade rule ships `3`), the rhythm shifts and
either hits waste inside i-frames (no knock → victim settles → break) or juggle
arcade-style.

**Mechanism.** Ride the `ComboEvents` transition seam (the reach-handicap precedent):
on combo START set the victim's `setMaximumNoDamageTicks(20)`, restore the prior
value on **every** END reason, with the same three-leg reversal (inline strip on
RETIRED/quit, join-sweep, inline restore on disable). Reads: `PlayerView.maxNoDamageTicks`
to capture the prior value; writes: a Bukkit attribute, fully reversible. Default OFF.

**Why it keeps the combo.** It guarantees each era-cadence rhythm hit actually
*delivers* knock (no silent within-invuln misfires that let the victim settle and
retaliate), locking the combo to the era beat regardless of what other plugins did
to the window.

**Era-authenticity risk — LOW, but conditional value.** Setting the window to **20**
is *exactly* vanilla legacy and modern-vanilla — on a bare server it is a true no-op
(the value is already 20), so it never changes feel. It is the anti-arcade of the
rejected `maxNoDamageTicks(3)` juggle: it *restores* the era window, never shortens
it. **OCM-coexistence tension:** if OCM owns `playerDelay` (default modeset ships
`old-player-knockback` + `playerDelay 18`), forcing 20 during a combo fights OCM —
this must route through the `MechanicToken`/`ArbiterCore` yield (see
`ocm-coexistence`) or be documented as "pick one." Its value only materializes when
something has moved i-frames off era 20, hence conditional.

**Feasibility — 2.** Attribute set/restore on the transition seam (proven pattern),
but the reversal must be leak-proof (same NBT-save-ordering hazard the reach handicap
solved) and the OCM arbitration must be wired so the two plugins don't thrash the
window.

---

## Candidate 6 — `combo-primer`

**Summary:** Shape the *warming* hits (before the combo is active) toward the pocket
so the linking hit reliably lands, raising the combo **initiation** rate — the servo
only engages once the combo is already active, leaving hits 1–2 unshaped.

**Mechanic it exploits/defends — the initiation window / the 2nd-hit lock-in.** A
combo is "active from the 2nd uninterrupted hit" (`minHits 2`), and the servo gates
on `comboAttackerId`, which is set only *after* the combo goes active — so the opener
and the linking hit ship *unshaped* era placement. A combo that never reaches the
active state is never held; good comboers land the first hit to set spacing for the
second. This is the initiation leg the maintenance servo can't touch.

**Mechanism.** A separate gate: when the `ComboTracker` is *warming* (`hits ≥ 1`,
within `maxGapTicks`, not yet active — readable from `ComboSnapshot`), apply a *mild*
placement bias (a gentle, tightly-clamped servo pass, much narrower than the main
servo's `[0.8, 1.2]`) toward the pocket so the linking hit lands inside reach. Reads:
`ComboSnapshot` warming state + the same servo inputs; writes: `comboFactor` on the
warming hit, journaled. Default OFF, and a no-op bias band that collapses to 1.0.

**Why it keeps the combo.** It converts near-misses on the linking hit into landed
hits, so more exchanges reach the active state where the full servo can hold them —
it grows the *number* of combos, upstream of every maintenance keeper.

**Era-authenticity risk — MEDIUM.** The opener is the most-scrutinized hit in any
fight; even a mild bias is detectable to a good player and departs from pure era
placement on hits the era shipped unshaped. Keep the bias band very narrow, default
OFF, and pin the no-op. If it reads at all "sticky" on the opener it should be
dropped — the owner prizes the honest opener.

**Feasibility — 2.** Reuses the servo pipeline with a warming gate and a tighter
clamp; the main cost is a second gate on `ComboSnapshot` state and careful no-op
pinning. Composes with `combo-hold` (primer for hits 1–2, servo for hits 3+).

---

## Candidate 7 — `combo-attacker-reach-extend`

**Summary:** The mirror of the reach handicap — instead of shrinking the victim's
reach, mildly extend the *attacker's* `entity_interaction_range` while they hold a
combo so their boundary hits keep landing as they chase.

**Mechanic it exploits/defends — the reach radii (`r_a` vs `r_v`).** The
un-retaliatable band is `√(r_v² − Δ²) < h ≤ r_a`. The handicap widens it by shrinking
`r_v`; this candidate widens it from the other side by growing `r_a`, letting the
attacker hold a slightly larger `h` (further from the victim's answer envelope) while
still connecting.

**Mechanism.** Ride `ComboEvents` on the *attacker* (not the victim): on START apply
a `mental:combo-attacker-reach` additive `MULTIPLY_SCALAR_1` modifier to the
attacker's `entity_interaction_range` (the exact machinery of
`AttributeModifiers.comboReach`, applied to the aggressor), remove on every END.
Same three-leg reversal. Default scale `1.0` (no-op); 1.20.5+ only.

**Why it keeps the combo.** A slightly longer `r_a` lets the attacker sit at a larger
`h`, deeper inside the un-retaliatable band, so the victim's hypotenuse answer is even
more out of range — while the chaser still lands every rhythm hit.

**Era-authenticity risk — HIGH (weakest of the set).** Extending reach *beyond* the
era ~3.0 reads as a reach-hack — the exact "feels like cheating" territory the
handicap deliberately avoids by nerfing the victim instead of buffing the attacker.
Even a small extension is more perceptible than an equal victim-side shrink, and it
is largely *redundant* with the reach handicap (which already opens the band). No-op
at scale 1.0, but the useful range is anti-era. Include only as a documented mirror
if the victim-side handicap proves insufficient; do not lead with it.

**Feasibility — 2.** Identical machinery to `combo-reach-handicap`, applied to the
attacker; the reversal and leak-sweep patterns transfer directly.

---

## Scoring & ranking

Score = **era-authenticity × combo-keeping value ÷ (implementation risk + 1)**.
(era & value: 1–5, higher better; risk: 0–5, higher worse.)

| Rank | Candidate | Era | Value | Risk | Score | Untapped leg it fills |
|---|---|---|---|---|---|---|
| 1 | `combo-blowout-guard` | 5 | 3 | 1 | **7.50** | safety clamp (variance) |
| 2 | `combo-vertical-trim` | 5 | 4 | 2 | **6.67** | elevation (Δ) |
| 3 | `combo-iframe-cadence` | 5 | 3 | 2 | **5.00** | cadence timing |
| 4 | `combo-line-hold` | 3 | 4 | 3 | **3.00** | direction (scatter/face-hug) |
| 5 | `combo-source-freshness` | 4 | 3 | 3 | **3.00** | direction (latency origin) |
| 6 | `combo-primer` | 3 | 3 | 2 | **3.00** | initiation |
| 7 | `combo-attacker-reach-extend` | 2 | 4 | 2 | **2.67** | reach radius (mirror) |

(Ties at 3.00 broken by strategic judgment below.)

## Recommendation — top 3 worth speccing

The raw rubric rewards cheap-and-safe; layering strategic value on top, the three to
spec first are:

1. **`combo-vertical-trim`** — the strongest strategic pick. It fills the single
   *untapped high-leverage geometric leg* (elevation `Δ`), it is era-**restoring**
   rather than merely era-safe (it enforces the measured declining-vertical profile
   that made 1.7/1.8 combos hold), and it reuses the proven airborne-multiplier
   machinery, so risk is low. This is the natural third geometric sibling to the
   horizontal servo and the reach handicap. Spec the **trim** (ceiling) only —
   explicitly forbid the lift-floor variant as anti-era.

2. **`combo-blowout-guard`** — the cheapest, most era-exact win, and it hardens every
   other COMBO module (including the servo) against the one break they all share
   (single-hit over-launch from KB variance). Near-free insurance; reuses the servo's
   existing `capEff` prediction. Ship it alongside vertical-trim.

3. **`combo-iframe-cadence`** — low-risk era-cadence honesty. Its value is conditional
   (it only bites where i-frames have drifted from era 20), but that condition is
   *common* in the mixed-plugin / OCM environments Mental targets, and it is the
   anti-arcade of the rejected `maxNoDamageTicks(3)` juggle. Spec it with the OCM
   `playerDelay` arbitration resolved up front.

The directional pair (`combo-line-hold`, `combo-source-freshness`) is a promising
**round 2**: `combo-source-freshness` is the more era-faithful of the two (a latency
refinement that fits Mental's thesis) and should be preferred; `combo-line-hold` is
the closest to the owner's rejected-assist boundary and must stay behind a loud
opt-in with partial (never full) suppression. `combo-primer` is worth a small
experiment once maintenance keepers are solid. `combo-attacker-reach-extend` is the
weakest — a reach-hack-flavored mirror — and should only be revisited if the
victim-side handicap proves geometrically insufficient.

---

## Appendix — considered and rejected (anti-era / arcade)

Recorded so they are not re-proposed. All violate `era-accuracy`'s anti-feature list
or the owner's explicit rejections.

- **Lift-floor / keep-airborne** (hold `Δ` elevated to widen the band): contradicts
  the measured declining-vertical truth (victims *did* touch down; apex dips ~0.5
  every other hit) — reads as arcade float. The vertical module must be trim-only.
- **Victim-sprint denial** (suppress the victim's sprint to deny the sprint-hit
  break): touches the sacred client-side technique contract and is an assist.
- **Magnetize / pull-in** (pull a drifting victim back toward the pocket): era
  knockback only ever pushes *away*; a pull is pure arcade.
- **Trade-dampening** (reduce the knock the attacker receives from a counter-hit):
  explicitly rejected by the owner; fairness/trade shaping belongs to nobody here.
- **I-frame *reduction*** (`maxNoDamageTicks → 3` combo-mode juggle): the owner's
  headline rejection; MMC's most-hated arcade kit.
- **CPS-scaled anything / anti-reduce normalization**: GommeHD-era ~80% disapproval,
  determinism is the product.
- **Registration-grace / hit-delay**: rejected; combat-rules territory that belongs
  to OldCombatMechanics, not a knockback shaper.

# Cross-feature interaction audit — all features enabled together

**Provenance:** owner directive 2026-07-04 ("the systems need to work together
properly … you need to explicitly know this, not assume"). Executed as a
seven-agent workflow (wf_220eea29-3de) on release/2.4.3-beta @ 04cd4fe: a
seam map over every shared-state meeting point, five parallel deep lanes with
per-pair verdicts, and an adversarial completeness critic that re-walked the
feature registry, challenged the weakest verdicts, and found the missed
pairs. Every verdict is file:line-evidenced in the workflow output; this doc
is the durable summary. Fixes land on `fix/interaction-audit` (one commit per
conflict, citing this audit).

## Proven to COMPOSE (the load-bearing ones)

- **Vertical latency compensation × the pocket servo** (the directive's named
  example): the compensation hint threads into the engine as the vertical
  override, and the servo's flight solve reads the SHIPPED vertical through
  the same override in the same order (`KnockbackEngine.shippedVertical`
  mirrors `base()`/`finish()` exactly), so the σ-solve simulates precisely
  what flies — and σ never scales the vertical, so the two cannot contend.
- **The engine factor pipeline**: `fresh = (base − rangeTaper) × pace × σ`,
  extras inside the scaled bonus, residual and vertical never touched (A3),
  byte-identity when factors are 1.0. Each component touched exactly once
  (`freshFactor = pace * combo`, one expression).
- **The servo application gate**: both compute sites (netty pre-send, region
  recompute) read the one frozen `comboAttackerId` from the published view;
  applied factors are carried per-transaction, so an adopted pre-send
  journals its own factors, never a recompute.
- **The combo feed funnel**: every melee delivery path (region, adopted
  pre-send/pinned, blocked redelivery) resolves through the single
  `DeskRouter` ship seam that feeds the tracker; retaliation feeds from the
  attacker-obligations seam; time-driven ends run before the view build.

## Confirmed CONFLICTS (10 — all fixed this round)

1. **[HIGH] Sword-blocking temp shield × the full-block knock cancel
   (≤1.20.6):** Mental's own injected REAL shield vanilla-full-blocks frontal
   melee, zeroing damage — the knockback unit's `shieldBlockingCancels`
   branch then withdraws the era knock. The flagship legacy block feel
   (blocked hits still knock full) inverts whenever both features are on.
   Fix: exempt hits blocked by Mental's own decoration; route through the
   blocked-knock redelivery.
2. **[MED-HIGH] Quit-path attribute persistence:** the reach-handicap
   modifier, the attack-speed base (1024), and the era-reach base all restore
   via `runOn`, which defers past the disconnect save — a combat-log persists
   combat-visible attributes into playerdata (permanent if the feature is
   then disabled). Fix: inline pre-save restores on the quit path.
3. **[MED] HITBOX's ATTACK_RANGE weapon component (1.21.5+) voids the reach
   handicap** for armed victims (the explicit component's window wins over
   the scaled attribute). Fix: strip the era component on combo start while
   the handicap is active; re-apply on end; HitboxUnit skips re-stamps
   mid-handicap.
4. **[MED] CRIT_FALLBACK × ARMOUR_STRENGTH same-priority order:** with the
   fast path off, the crit BASE raise lands after the armour cascade
   depending on registration order (EnumMap/Feature-ordinal + toggle
   history) — up to ~2× era damage error, nondeterministic across GUI toggle
   cycles. Fix: order-independent crit fold.
5. **[MED] OFFHAND's enable-pass strip eats Mental's own temp shield**
   (mid-block reload → item loss + duplicate shield). Fix: decoration marker
   query in the strip policy.
6. **[MED] The fast path doesn't enforce the reach handicap** for the
   handicapped victim's own attacks (vanilla's interaction-range gate is
   replaced by fixed windows) — dishonest clients keep full reach. Fix:
   `passesReach` clamps by the attacker's own active handicap from the frozen
   view.
7. **[MED] CRIT_FALLBACK's global fast-path gate** leaves the whole
   vanilla-fallback slice un-critted (all Folia mob combat, stale-view and
   packetless-attacker hits). Fix: per-hit transaction check instead of the
   global config gate.
8. **[LOW] The chase-EMA/slew memory commits only inside the debug-sink
   gate** — `target-mode: dynamic` without debug regresses to the v1
   knife-edge. Fix: commit whenever the servo is active in DYNAMIC mode.
9. **[LOW] OCM double-apply warnings are boot/enable-frozen** — a GUI toggle
   after boot double-applies silently. Fix: re-derive warnings on every
   converge that changes the token set.
10. **[LOW] Sword-blocking's software `setDamage(double)` rescales the era
    armour cascade** (Spigot re-scales all modifiers proportionally) —
    silent era drift on blocked hits at software tiers. Fix: granular BASE
    reduction + era re-cascade.

## Explicitly KNOWN, deliberately deferred (documented, not assumed)

- **Compensation inside long combo gaps (13–20 ticks):** a landed-verdict
  compensation can adjust a mid-combo stamp the σ-solve doesn't model —
  low severity (triple timing intersection required); fix sketch recorded
  (thread the compensation verdict into `PredictorInputs`).
- **Servo answer-envelope radius (2.9) vs the era hitbox margin (+0.1,
  1.21.5+):** ≤1-tick exposure-budget drift; a lab-calibration item.
- **UNPROVEN: attack-speed spoof × handicap attribute sync** share the
  UPDATE_ATTRIBUTES wire seam — needs a live packet probe.
- **UNPROVEN: fast-pots 3× self-projectile × external anticheats** — the
  compat unit's two levers deliberately don't gate it (server-authoritative);
  a live posture test would settle whether it should.

## Corrections the critic made (worth remembering)

- The seam map mis-cited ArmourDurabilityUnit into the EDBEE cluster (it
  listens PlayerItemDamageEvent).
- The EDBEE router-order concern was OVERTURNED (DamageRouter registers
  before the reconciler exists; re-registrations only ever append after it) —
  the real residual was the intra-cluster crit×armour order (conflict 4).
- The temp-shield conflict was upgraded from UNPROVEN to CONFIRMED by
  re-derivation; the offhand strip was confirmed but narrowed to the
  enable-pass window.

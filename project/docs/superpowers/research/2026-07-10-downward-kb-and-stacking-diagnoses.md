# The 2.5.2 knockback diagnoses — downward hit-2 verticals & the spam-stacking blast

Two owner/user reports, investigated in parallel (2026-07-10) by independent
finder fleets, each synthesis adversarially verified by three lenses; both
diagnoses were upheld 3/3. Full agent transcripts live in the session
workflow journals; this doc is the durable record the 2.5.2 release notes
reference.

## Report 1 — downward knockback on the second hit (legacy presets)

**Symptom (owner):** hit 2 of a double/combo ships "downward, vanilla-like"
knockback on legacy-1.7, legacy-1.8, mmc, minehq, kohi; lunar, signature,
velt feel correct.

**Verdict (high confidence): a two-cause stack behind a mask.**

- **ROOT (long-open):** the ledger-vy divergence. For an airborne victim the
  `MotionLedger` residual vy free-falls to −0.8..−1.5 while the real client
  hovers at −0.05..−0.34 (measured live on 914 boxer hits, 2026-07-07
  research doc). `EntityStates.captureVictim` feeds that diverged residual
  straight into the ADD-mode vertical `y = victimVy·friction.y +
  base.vertical`. The measured-reality clamp proposed in the 2026-07-07 doc
  was never implemented (no measured vy exists in the `PlayerView` — only
  measuredVx/Vz).
- **TRIGGER (what made it owner-visible now):** the 2.5.1 engagement
  contract (ae7bb79 — correct, owner-directed, NOT to be reverted). A held-W
  attacker's hit 2 is now a PLAIN hit (the engagement was consumed on hit 1;
  the spend latch correctly refuses to resurrect it off the stale-high
  server flag). A plain hit skips the entire sprint-extra block — including
  `+extra.vertical` (0.085–0.1), which had been masking the ROOT by keeping
  hit-2 verticals positive.
- **MASK:** the 2.4.7/2.4.8 `vertical-min 0.0` floor is present, parsed, and
  applied on every affected preset — the engine cannot ship a genuine
  negative on pristine configs. The owner-visible "downward" is a floored
  **exact-0.0 zero-lift stamp on a still-falling victim**, perceptually
  identical to vanilla's airborne kept-falling-y knock.

**Why the immune trio is immune:** `extra.vertical` is the unique exact
splitter. Affected five carry extra.vertical > 0 (legacy-1.7/1.8/mmc 0.1,
minehq 0.09, kohi 0.085) — 2.5.1 deleted that lift from held-W hit 2. The
immune three all carry extra.vertical = 0.0 — their hit-2 vertical is
bit-identical before and after 2.5.1 (velt/signature additionally structural
via friction.y 0.1). **Lunar is the decisive discriminator:** highest
friction.y in the bundle (0.7634) means under any floor-absent/unfloored-file
theory lunar would leak first and worst; the owner reports it immune, which
kills every such theory. (Badlion, untested by the owner, is predicted
affected.)

**Alternatives refuted:** floor gone/bypassed (present at every path,
finish() applies vertical-min last); deployed-file drift (cannot exclude
lunar); delivery-layer vanilla leak (every vanilla-ships branch is
profile-blind — cannot produce a per-profile split; the 2.4.6/2.4.9 leak
fixes are intact); the 2.5.0 formula split (legacy path byte-untouched,
ModernKnockback OFF everywhere).

**Fix (this round):** implement the measured-reality vy clamp — publish a
measured vy beside measuredVx/Vz (the PositionRing sample carries y; the
previous-sample delta machinery already exists in `SessionService`) and
clamp the victim capture at `EntityStates.captureVictim` AND its pre-send
twin (`HitRegistrationUnit.preVictimState`) to
`max(ledgerVy, measuredVy − 0.15)`, no-op when no fresh measurement exists
(packetless fakes keep their exact current behavior — suites stay green).
With the real hover vy, hit-2's plain vertical returns era-positive
(+0.23..+0.37 on the affected five) and the 0.0 floor stops being
load-bearing. Era-faithful: the era server's motion fields tracked the
client's real movement (jump bookkeeping re-stamped them); the ledger's
runaway free-fall is a model bug, not era truth. The engagement contract is
untouched.

**Residual (named by the verifiers):** the "downward" is a perceptual read
of the floored zero-lift — a live JOURNAL capture showing shippedVy == 0.0
on a kohi hit 2 would close the last gap; if an owner journal ever shows a
genuinely NEGATIVE shippedVy, check the deployed profiles/ for an edited
(frozen) file still carrying vertical-min −3.9.

## Report 2 — close-range spam-hit stacking blast (custom preset)

**Symptom (user, pre-2.5.1 build):** custom preset (base 0.4/0.4 ADD,
immediate, combos true, sprint ×1.0, scaling resistance), vanilla immunity
on: spam clicks at close range — hits 1–2 modest, by hit 3 the victim is
blasted ~10 blocks.

**Verdict (medium confidence): a stack, dominated by a since-fixed code bug.**

- **PRIMARY (the reported build):** the 2.5.0 auto sprint re-arm re-adopted
  the held-W spammer's stale-high sprint flag one tick after every hit —
  every legal knock shipped the full 0.9 sprint stamp with zero technique.
  On this profile that rides two era-authentic amplifiers: combos=true
  carries ~0.5× the surviving residual (0.91¹⁰ ≈ 0.389 airborne → horizontal
  ~0.9 → 1.075 → 1.109, bounded), and ADD base.vertical 0.4 re-launches
  ~0.5 vy per hit at 10-tick cadence so the victim never lands — air time
  stretches and DISPLACEMENT ramps ~4.9 → ~6.5 → ~8–9.5 blocks by hit 3.
  The blast is displacement ramping through juggle air-time, not literal
  velocity multiplication. **Already fixed at HEAD** by 2.5.1's spend latch
  (held-W now gets sprint on hit 1 only: 0.9 / 0.575 / 0.512 — no blast).
- **SECONDARY (latent, version-independent, fixed this round):** all ledger
  decay rides the single per-player `SessionService.tick`, which runs
  several throw-capable steps before `session.tickStep()` (the sole
  `ledger.tick` caller), and no scheduler seam armors the task body. A
  recurring throw freezes decay while combat keeps working through the
  vanilla EDBEE path — the ONLY regime that reproduces the exact monotone
  horizontal ramp 0.9 → 1.35 → 1.575 (fixed point 1.8 ≈ 9.6 blocks). No
  confirmed trigger (it would spam the console), but it is the one
  code-level hazard that survives at HEAD.

**Hypotheses refuted structurally:** rejected/mid-invuln hits depositing
residual (the ONLY melee ledger write is `Deliveries.recordDelivered`,
resolve-gated; immune spam never fires EDBEE); double-record (record
REPLACES all three axes — idempotent); supersede-carry summing
(replacePending replaces, journals shipped=null); friction-1.0 defaults
(custom.yml ships the era 0.5).

**Fix (this round):** armor the session tick — isolate each step so a throw
in one can never starve `tickStep()`/ledger decay, with a loud rate-limited
error line (B10: no silent degradation). The era compounding itself is
authentic and untouched; `delivery: immediate` being a dead knob at HEAD is
noted for a future round, not changed here.

**Residuals (named by the verifiers):** a genuine w-tapper at HEAD still
earns per-hit 0.9 sprint knocks and can juggle ~7.6–9.5 blocks of
displacement by hit 3 — era-authentic by project doctrine (one engagement,
one sprint knock; technique re-arms), so it ships as-is; if the reporter
was on ≤2.4.9 rather than a 2.5.0 build, held-W spam produced WEAK knocks
there and their report would need re-taking against 2.5.2.

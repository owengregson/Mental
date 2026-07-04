# 2.4.1 combat-fix round — sprint desync, blocked-hit hurt sound, sub-floor gaps, pace 0.95

> Decision record + phase briefs for the 2.4.1 bugfix round. Ground truth for
> the findings is the four-agent investigation of 2026-07-04 (workflow
> wf_3b5f6ea3-57d); every claim below was verified there with file:line or
> javap evidence against the code the owner runs (release/2.4.0, 7660e9e) and
> the real Paper jars in `run/`.

**Goal:** fix the owner-reported intermittent weak/non-sprint knockback (both
families), the missing hurt sound for a sword-blocking victim, and the two
pre-existing sub-floor listener gaps; ship the signature pace exponent at 0.95.
Release as **2.4.1** (full release, takes Latest).

## Findings (condensed — six mechanisms, four real)

- **F1 (pace stance desync, NEW in 2.4.0, signature-only, ~23% weak).** The
  pace factor pairs a wire-fresh sprint boolean with an attribute captured at a
  different instant. Fast path: `HitRegistrationUnit.preAttackerState`
  (:356-358) pairs `verdict.sprinting()` (netty, sub-tick) with
  `view.moveSpeedAttr()` (end of previous tick). Region path:
  `KnockbackUnit.onEntityDamageByEntity` (:199-206) pairs the stamped verdict
  with a LIVE `Attributes.valueOr` read at the deferred EDBEE — by which time
  the client's same-flush STOP_SPRINTING has removed the ×1.3 modifier. Either
  way `PaceScale` divides an attr from one stance by the baseline of the other:
  0.10/0.13 → 0.769 (survives the [0.5,2.0] clamp). Region-path exposure is
  wide: every hit whose velocity pre-send was paced out by the FeedbackGate's
  450 ms per-victim window (`HitRegistrationUnit:293-319`) computes there —
  the spam cadence against a blocking victim funnels hits into it.
- **F2 (retro-clear + visibility races, PRE-DATES 2.4.0, reads "non-sprint",
  ~60% cut).** Vanilla clears attacker sprint synchronously inside attack;
  Mental's `applyAttackerObligations` runs at the deferred EDBEE (T+1) and
  calls `SprintWire.onServerClear()` (kernel `SprintWire:56-61`) —
  **unconditional**, no guard against a NEWER wire write — from the victim's
  region thread, then defers `setSprinting(false)` one further tick. A
  re-engage START_SPRINTING arriving in [T, T+2] is retroactively destroyed →
  next hit ships with no sprint extra. Separately, SprintWire's plain fields
  are written by three threads (netty PacketTap, KnockbackUnit on the victim's
  region thread, SwordBlockingUnit on the attacker's) with no happens-before —
  a standing visibility race, worst on Folia. This is the owner's long-standing
  bug family.
- **F3 (resetSprintForBlock gate, pre-dates 2.4.0).** `SwordBlockingUnit`
  (:198-205) gates the block-hit re-arm on `wire.verdictAt(...).sprinting()` —
  the very flag F2's `onServerClear` destroys. The era-accuracy skill (:78-86)
  documents the contract: the gate must read the RAW client sprint flag, "the
  only signal that survives Mental's own post-hit setSprinting(false)". v5
  lost that property.
- **F4 (hurt sound, root cause pinned by javap on paper-1.21.11).**
  `LivingEntity.hurtServer` still calls `playHurtSound` for a partial
  BLOCKS_ATTACKS hit (sound tail gated on the fresh-hit flag, bci 638-650,
  NOT the blocked flag) — but `Player.playSound` passes ITSELF as the
  except-entity into `PlayerList.broadcast`, so every tracker EXCEPT the
  victim hears it. The victim's own hurt sound since 1.19.4 is CLIENT-derived
  from `ClientboundDamageEventPacket` — exactly the packet the blocked branch
  replaces with `BlocksAttacks.onBlocked` (bci 365-391), a total no-op with
  Mental's `blockSound=Optional.empty()`. Mental's HURT_ANIMATION burst is
  soundless by design (`animateHurt` sets tilt fields only). So the missing
  audience is exactly one client: the victim. The 2.4.0 record's claim
  "vanilla playHurtSound still fires so the sound plays" was right about the
  branch, wrong about the audience; `KnockbackUnit`'s javadoc (:301-303)
  encodes the error. The ≤1.20.6 software tier is unaffected (its partial
  reductions ride the normal presentation, `broadcastDamageEvent` included).
- **F5 (blocked re-mint hygiene; the vector itself is exonerated).**
  `deliverBlockedKnock` ships the ORIGINAL carried/computed vector — no
  recompute-after-sprint-reset bug. But: `DeliveryDesk.withdraw` (:62-67) is
  journal-silent and the fresh tx is minted with `SprintVerdict(false, …)`
  (:351-356), so blocked hits lose journal correlation; the retired fallback
  `() -> {}` silently drops the knock; the `runOn` hop ships one tick late even
  though the EDBEE already runs on the victim's owning thread.
- **F6 (Folia sweep-vs-damage ordering, unverified live, has console
  signature).** CounterTickClock skew ±1 vs region phases can let the session
  sweep drop a PRE_SENT tx before its deferred damage task runs; the resolve
  final-else then calls `adopted()` on a RECORDED tx → IllegalStateException
  INSIDE the PlayerVelocityEvent handler (console: "Could not pass event
  PlayerVelocityEvent"), vanilla vector ships + doubled wire.
- **Sub-floor GAP 1 (GoldenApplesUnit, 1.9.4–1.11.2).** Bukkit's
  `registerEvents` reflects over EVERY declared method descriptor;
  `private NamespacedKey nappleKey()` (:213) throws NoClassDefFoundError →
  Bukkit logs one SEVERE line and registers ZERO handlers ("has failed to
  register events for class …GoldenApplesUnit because org/bukkit/NamespacedKey
  does not exist", `run/1.9.4/logs/latest.log:152`). The recipe half (task)
  worked; the era consume effects silently never applied. NamespacedKey lands
  at 1.12 (javap: 0 matches in patched_1.9.4/1.10.2/1.11.2, 1 in 1.12.2).
  **New doctrine hazard: no sub-floor Bukkit type may appear in ANY method
  descriptor of a class handed to registerEvents** — the Bukkit-listener
  analog of the D-8 rule.
- **Sub-floor GAP 2 (SweepDamageListener, 1.9.4/1.10.2).** The
  `ENTITY_SWEEP_ATTACK` getstatic (SweepDamageListener:28) throws
  NoSuchFieldError on EVERY EntityDamageEvent (sticky constant-pool failure),
  swallowed per-event by the bus (`run/1.9.4/logs/latest.log:621-654`). The
  constant lands at 1.11 (javap). Below 1.11 sweep splash EXISTS but arrives
  as plain ENTITY_ATTACK raw 1.0 — indistinguishable by cause; the heuristic
  fallback (cancel same-tick second sword hit of raw 1.0) is REJECTED as a
  zero-touch violation risk. Registered by BOTH SweepUnit (:34) and
  AttackCooldownUnit (:72).
- **Pace exponent 0.95 is base-speed-free.** Anchor verified: live base-sprint
  attr is 0.10000000149011612 × 1.30000001192092896 = 0.13000000312924387 →
  factor epsilon ≤ 2.4e-8; the e=1.0 vs e=0.95 shipped-vector delta at base
  speed is < 1e-9 blocks/tick — far below the wire quantum (1.25e-4), so
  packets are byte-identical. Full change list mapped (see Phase 1).

## Decision log

- **D-1 (stance-free pace).** Fix F1 by normalizing the attribute, not by
  stamping more state: every capture site reads `isSprinting()` and
  `getValue()` back-to-back on the entity's owning thread (a coherent pair —
  the server flag and the ×1.3 modifier move together inside setSprinting) and
  divides out `1.0 + (double) 0.3f` when sprinting. `PaceScale` then compares
  against the single 0.10 walk baseline. The sprint modifier — the only
  stance-churning term — cancels exactly; Speed/Slowness (genuine speed) still
  move the factor; wire-vs-server stance disagreement becomes irrelevant to
  pace. This fixes BOTH the fast path and the region path with no new stamped
  state. `EntityState.moveSpeedAttr`/`PlayerView.moveSpeedAttr` keep their
  names (kernel additive) with javadoc redefined to "walk-stance-normalized".
- **D-2 (newer-wire-write-wins).** F2: `onServerClear` gains a stamp-guarded
  form — it no-ops when the wire saw a write strictly newer than the hit it
  pertains to (the rule `reconcile` already implements). The deferred
  `setSprinting(false)` obligation is skipped under the same guard. SprintWire
  state becomes a single immutable snapshot swapped by reference
  (AtomicReference/CAS — the codebase idiom) so cross-thread reads are safe;
  the single-writer doctrine note in ConnectionDomains is updated to name the
  two sanctioned cross-thread writers and the atomicity that licenses them.
  Same-tick retro-clear (START after ATTACK inside one tick) is an accepted
  residual — physically implausible for real input.
- **D-3 (raw client flag).** F3: SprintWire tracks `clientSprinting` — written
  ONLY by packet START/STOP, never by onServerClear — and
  `resetSprintForBlock` gates on it, restoring the documented pre-v5
  semantics.
- **D-4 (victim-targeted sound).** F4: `deliverBlockedKnock` plays
  `Sound.ENTITY_PLAYER_HURT` to the VICTIM only (`victim.playSound`, pitch
  `1.0f + (r1 − r2) * 0.2f` mirroring vanilla `handleDamageEvent`), in BOTH
  the pre-sent and burst branches (the pre-sent case shipped its soundless
  HURT_ANIMATION at registration). Vanilla already serves every other tracker;
  a world broadcast would double the sound for bystanders. Direct Bukkit
  constant per the live-proven ToolWear precedent (`ToolWear:76`); the path
  only triggers on the BLOCKS_ATTACKS tier (1.21.5+), so no legacy name
  fallback. Fix the javadoc lie at KnockbackUnit:301-303.
- **D-5 (blocked-hit hygiene + same-tick ship).** F5: the re-minted tx carries
  the ORIGINAL SprintVerdict; withdraw writes a journal record (reason
  `blocked-redeliver`, superseded-by the fresh id); the retired fallback
  journals a drop instead of `() -> {}`; the delivery runs INLINE when the
  current thread already owns the victim (the EDBEE does), falling back to
  `runOn` otherwise — removes the 1-tick lag and the retire race.
- **D-6 (journal attribution).** Add `paceFactor` (double, 1.0 when off) to
  `JournalEntry` — the one field that makes a weak knock attributable in a
  single journal read (0.769-class = pace desync; 0.325-class = false
  verdict). Kernel additive: old-arity convenience ctor kept (KnockbackProfile
  precedent). Thread the factor out of the engine via an additive
  compute-result overload; journal it on every ship/suppress.
- **D-7 (F6 hardening).** `DeliveryDesk.resolve`'s final else must never throw
  on a RECORDED pending (pass through + journal a `late-resolve` note instead
  of the illegal `adopted()`); the sweep drops a pending only when it is ≥2
  ticks older than now (absorbs the Folia counter skew). Any suite waits
  keyed to next-tick sweeps get re-checked.
- **D-8 (sub-floor seams).** GAP 1: hoist the keyed-recipe lifecycle out of
  the Listener class — platform `Recipes` resolver (probe once: keyed ctor /
  keyed get-remove) + a non-Listener helper owning the NamespacedKey; the
  already-written legacy branch (pre-keyed ShapedRecipe ctor + recipeIterator
  + GOLDEN_APPLE:1) then WORKS on 1.9.4–1.11.2. GAP 2: platform `SweepCauses`
  resolver — `DamageCause.valueOf("ENTITY_SWEEP_ATTACK")` probed once, never a
  getstatic; where absent, the owning units skip listener registration at
  assemble and print the degrade line. Both get manifest entries
  (`capability:recipe_key` OptionalSince 1.12.0, `capability:sweep_cause`
  OptionalSince 1.11.0), boot-report describe() strings, and BootSuite pins.
  The descriptor-doctrine hazard is added to the paper-cross-version skill.
- **D-9 (gate honesty extension).** `checkIntegrationTest` doLast scans the
  captured per-entry console log and FAILS on: Bukkit listener-registration
  failures naming `me.vexmc.mental`, `Could not pass event .* to Mental`, and
  linkage errors (NoSuchField/NoSuchMethod/NoClassDefFound) with mental
  frames. `scripts/integration-matrix.sh` mirrors the grep (PASS downgraded
  to FAIL). Same honesty model as the nonce: a green gate now structurally
  excludes swallowed listener errors. (This also gives F6 a permanent live
  tripwire on the Folia entry.)
- **D-10 (exponent 0.95).** Signature preset exponent 1.0 → 0.95 with a
  SIGNATURE_2_4_0 superseded entry (18-arg, identity strings byte-identical to
  the bundle) so pristine 2.4.0 installs roll forward; pre-pace pristine
  installs still roll via SIGNATURE_2_2_0/2_2_1 straight to the new bundle.
  `ProfileSuite.EXPECTED_S = Math.pow(1.6, 0.95)` (expression, not literal).
- **D-11 (out of scope).** The immunity-boundary tick classification residual
  (2.4.0, documented) stays; the F6 live Folia repro (SimpleBoxer) is deferred
  — D-7 removes the hazard's teeth and D-9 trips on any recurrence.

## Phase 1 — combat cluster (kernel + core + tester)

One executor. Failing tests first where unit-testable (kernel pins), suite
cases land with the fix (verified at the full gate). Covers D-1…D-7, D-10:
PaceScale/KnockbackEngine + capture sites, SprintWire + obligations +
resetSprintForBlock, deliverBlockedKnock (sound, verdict, journal, inline),
DeliveryDesk (withdraw journal, resolve hardening, sweep age), JournalEntry
paceFactor, Presets/SupersededPresets/signature.yml + docs, ProfileSuite
(EXPECTED_S; new stance-mismatch pins: server-sprinting attacker with absent
verdict must journal paceFactor 1.0 at base / 1.6 at Speed III — old code
shipped 1.3 / clamped 2.0), BlockingSuite journal-correlation update.

## Phase 2 — sub-floor seams + gate hardening (platform + core + tester + build)

One executor, disjoint files. Covers D-8, D-9: platform Recipes + SweepCauses
resolvers with describe() + manifest entries, GoldenApplesUnit descriptor
hoist, SweepUnit/AttackCooldownUnit assemble-time skip + degrade lines,
BootSuite (handler-registered assertion + manifest pins), ConsumableRulesSuite
legacy staging un-skip, InventoryRulesSuite explicit degrade assertion,
checkIntegrationTest + integration-matrix.sh log scans, paper-cross-version
skill doctrine addition.

## Verification

`./gradlew build` per executor; after merge and line-review the full gate on
release/2.4.1: build → integrationTestMatrix (16 entries + folia, nonce+PASS
per entry, now with the D-9 log scan live) → integrationTestOcm. The 1.9.4
and 1.10.2 entries must show the two boot lines and ZERO swallowed-error
matches; the sub-floor golden-apples consume pins must run (not skip) down to
1.9.4. Release: version bump commit, PR to main, auto-release, Latest.

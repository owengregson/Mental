# Mental 2.5.5-beta — the feedback-coherence round

Owner-reported issues in 2.5.3/2.5.4-beta, diagnosed 2026-07-10. Ground truth
for the fix round. Root causes verified against the decompiled
paper-1.21.11.jar (`legacy-lab/decomp-1.21.11/`) and the era decomps
(1.8.9 `pr.java`, 1.7.10 `sv.java` — byte-identical immunity semantics).

## Diagnosis (verified, not hypothesized)

### D1. Hit indicators + hit sounds skip together in runs (the reported bug)

Both cosmetics listeners hook `EntityDamageByEntityEvent` at MONITOR
(`finalDamage > 0`). Vanilla's immunity gate
(`LivingEntity.hurtServer:1414-1418`) **returns false before any Bukkit event
is constructed** when `invulnerableTime > max/2 && amount <= lastHurt` — so
window-rejected swings are structurally invisible to both listeners, and they
skip together. At ~10 CPS constant weapon damage that is ~4 rejected swings
per accepted hit ("chains of 4-5"). Era truth: those swings deal no damage,
no knockback, no sound in genuine 1.7/1.8 either — silence there is correct.

The actual DEFECT is the incoherent band: the netty fast path pre-sends the
victim's VELOCITY+HURT burst off the FROZEN boundary view
(`PlayerView.damageImmune()` = `noDamageTicks > max/2 + 1`, the 1.6.0 "+1
staleness allowance") *before* vanilla decides. In the ~1-tick race sliver
(damage task landing before the victim's counter decrement, or a stale view
under load) vanilla then rejects the hit: the client visibly flinches and
flies, but no EDBEE fires — a **knocked-but-silent hit**, violating the era
contract (rejected hits get NO knock and NO flinch) and reading to the player
as "the effects didn't play". The pre-sent pending is never withdrawn (a
connected victim's pending is held until superseded — the 2.4.6 rule).

Window-UPGRADE hits (`amount > lastHurt`) fire EDBEE with
`getFinalDamage()` = the delta actually subtracted (modern Paper injects an
`INVULNERABILITY_REDUCTION` modifier; legacy CraftBukkit passes the delta as
the event damage) — cosmetics play, no knock ships, era-correct.

### D2. Low-health layer inconsistent + victim never hears it

`HitFeedbackListener.emit` appends the low-health packets to the same list
that is shipped only to viewers whose UUID != victim — the victim receives
particles only, never the layer. Inconsistency is D1's dropped events plus
the absolute-hearts threshold.

### D3. Indicator accuracy

`getFinalDamage()` already equals red-heart health lost for every accepted
hit (including upgrade deltas). Residual inaccuracies: (i) overkill — a
killing hit renders full finalDamage though only pre-hit health was lost;
(ii) nothing folds a same-tick plugin bonus's overkill either.

### D4. No healing observability

StarEnchants heals via `setHealth` (`DispatchSinkBase.java:300-306`), Mental's
own RegenUnit heals via `setHealth`, and RegenUnit *cancels* SATIATED regain
events — `EntityRegainHealthEvent` alone provably misses "any heal source".
Only a per-tick health-delta sample on the victim's session tick sees them all.
No victim→last-attacker state exists anywhere (only combo-scoped
`comboAttackerId`).

## The fixes

### F1. Boundary adoption (the D1 fix) — `HitRegistrationUnit`

At the region apply (`damageWithSlot`), immediately before
`victim.damage(amount, attacker)`: when the transaction carries a
**wire-committed knock** (state PRE_SENT or PINNED) and the LIVE victim would
window-reject the hit (`getNoDamageTicks() > getMaximumNoDamageTicks()/2 &&
amount <= getLastDamage()`), clamp `victim.setNoDamageTicks(max/2)` so the
hit lands as the boundary-fresh hit the pre-send already committed to. This
enforces the documented 1.6.0 design intent (an admitted boundary hit applies
full damage); the perturbation is ≤ the race sliver's own width, and only
fires when Mental has already rendered the knock on the victim's client —
knock ⇔ damage ⇔ EDBEE ⇔ cosmetics realign into one truth.

- Never clamp the upgrade branch (`amount > lastDamage`): vanilla accepts it
  as a delta hit; clamping would deal FULL damage where the era dealt the
  difference.
- Never clamp a REGISTERED (uncommitted) hit: era silence stays era silence.
- Pure decision helper (`static boolean adoptBoundary(boolean committed, int
  noDamageTicks, int maxNoDamageTicks, double amount, double lastDamage)`),
  unit-pinned with hand-computed cases.
- Journal it: overwrite the tx presend disposition with
  `"<prior>+boundary-adopted"` (the F9 open namespace; commitPreSendState's
  overwrite is the precedent) so the journal Capture discriminates adopted
  hits for suites and the JOURNAL debug channel.
- `getNoDamageTicks`/`getMaximumNoDamageTicks`/`getLastDamage`/`setNoDamageTicks`
  are flat Bukkit API across 1.9.4→26.x (no version seam needed).

### F2. Low-health layer → percentage + victim-audible — `HitFeedbackListener`

- Threshold becomes **percent of max health**: fire when
  `postHitHealth < victim.getMaxHealth() * pct / 100.0` (per-hit region-legal
  read; `getMaxHealth()` deliberately — the Attribute enum constant renamed in
  1.21.2+, the deprecated accessor is stable across the whole range).
  `postHitHealth = getHealth() − finalDamage` stays (finalDamage is red-heart
  accurate; absorption rides the modifier chain already).
- The low-health packets become their own list: normal sounds stay
  victim-excluded (vanilla-audience contract), the low-health layer ships to
  the non-victim viewers AND the victim, all via `writePacketSilently` (the
  suppressor-dodging posture — mandatory).
- Decision strings unchanged (`EMITTED+LOW_HP` etc.); a lowHealth hit with the
  victim as the only nearby player must now read `EMITTED+LOW_HP` (the victim
  IS a viewer of the layer), not `NO_VIEWERS`.
- Killing-hit suppression stays (death-effects owns the moment); empty-layer
  never reads LOW_HP (the 2.5.2 rule).

### F3. Indicator accuracy — `DamageIndicatorsListener` + `IndicatorMergeBook`

- Clamp every rendered/merged per-event damage to health actually lost:
  `displayed = min(finalDamage, victim.getHealth())` at event time (pre-hit
  health = red hearts available). Merge sums clamp per-event before summing.
- Fold window stays same-tick (the anti-phantom discipline holds).
- The immunity-cadence suite (below) live-pins `Σ displayed == health lost`
  per matrix version, which also proves the legacy delta-event semantics.

### F4. Healing indicators — new machinery inside DAMAGE_INDICATORS

- **Detection**: a health-delta sample on the session tick (D2, victim region
  thread). `CombatSession` grows a session-owned `lastHealth` (double, NaN
  initial) updated every tick from the live player read the tick already
  performs; `SessionService` gets a volatile `HealSampler` slot (installed by
  `DamageIndicatorsUnit.assemble` when `heal-text` is non-blank, cleared on
  scope close — zero-touch: module off ⇒ no sampler ⇒ nothing happens; the
  always-on lastHealth tracking is observation-only by type). When
  `health > lastHealth` (and `lastHealth > 0` — respawn/join guard), the
  sampler receives `(victimId, delta, tick)`. This catches setHealth heals
  (StarEnchants, RegenUnit), event heals (pots, regen, gapples), and
  cancelled-event non-heals correctly (only applied health counts).
- **Attribution**: the listener keeps a module-scoped
  `ConcurrentHashMap<UUID victim, LastHit(attackerId, tick)>` stamped on every
  qualifying melee EDBEE (attacker != victim). A heal consumes it only within
  **200 ticks** (10 s) of the stamp. Cleared per player by the unit's forget
  hook (both roles: as victim key and as stamped attacker).
- **Folding/pacing**: per-victim `HealFold` (pure, unit-tested): accumulate
  deltas; ship at most one heal indicator per victim per **10 ticks**,
  carrying the accumulated sum — a pot burst ships immediately, regen drips
  aggregate instead of spamming stands.
- **Render**: `heal-text` template (new damage-indicators key; `{HEALTH}` =
  healed hearts, signature `"&a+{HEALTH} &c❤&r"`, DEFAULTS `""` = off — the
  era-exact no-op). Ships to the LAST ATTACKER's client via the existing
  drivers map / `IndicatorDriver.add` / `IndicatorStandPackets` stack.
  NEVER touches the `IndicatorMergeBook` (same-tick damage folding must not
  cross-contaminate). Placement: `IndicatorPlacement.place` with the
  attacker's location best-effort (try-catch — cross-region under Folia falls
  back to a random bearing around the victim); ground scan reuses the
  listener's frozen-plane scan (we are on the victim's region thread).
- **Trace**: decisions `HEAL` (shipped, detail carries hearts) and
  `HEAL_UNSENDABLE` (attribution fresh but attacker offline/no PE user) — the
  matrix-assertable seam.

### F5. Preset restructure — vanilla removed, signature ships as YAML

KB-profile model completed: the bundled YAML is the tune's source of truth.

- `EffectsPreset.DEFAULT_NAME = "signature"`. The `VANILLA`, `SIGNATURE`,
  `CUSTOM` in-code preset constants and the `SIGNATURE_*` value constants in
  `HitFeedbackSettings`/`DeathEffectsSettings` are DELETED. `VANILLA_SOUNDS`
  survives (it powers `vanillaTune()`, a value test). The settings `DEFAULTS`
  records stay vanilla-VALUED (parse(empty) == the era-exact no-op; the
  omitted-key fallback in every user preset file is unchanged — the LEGACY_17
  rule).
- `Library.effective()` falls back to the parsed `signature` entry; the
  last-ditch in-code fallback (tests, torn installs) is a DEFAULTS-valued
  preset named "signature" (package-private). `Snapshot.Builder` defaults
  likewise.
- `ConfigStore`: `BUNDLED_EFFECTS_PRESETS = [signature, custom]`; when
  `signature.yml` on disk is missing or unparseable, serve the JAR RESOURCE
  text instead (loud line) — the signature tune survives a torn install.
- Drift guard inverts: `EffectsPresetParserTest` carries TEST-LOCAL expected
  records (the pins move into the test, out of production);
  `custom.yml parses value-identical to signature.yml` stays.
- `Migrations`: the 3→4 signature/vanilla import branches keep working via
  PRIVATE FROZEN copies of the 2.5.2 effective values inlined into
  `Migrations` (documented as historical constants — they must never track
  the live preset).
- New migration 4→5 (`CURRENT_VERSION = 5`): (i) delete
  `effects/presets/vanilla.yml` when byte-pristine vs the shipped
  2.5.3/2.5.4 bundle (backup to `config-backup-v4/`), leave an edited copy in
  place (stays selectable via directory discovery); (ii) textual flip of
  `effects.yml`'s `  preset: vanilla` → `  preset: signature` when the
  pristine file was removed or absent (edited-vanilla keeps its selection);
  (iii) scrub an overlay `effects.preset: vanilla` the same way. The
  unknown-name loud fallback (→ signature) is the runtime safety net either
  way.
- `SupersededEffectsPresets`: register the outgoing 2.5.3/2.5.4
  `signature.yml` hash so pristine installs upgrade in place to the new file
  (percent key + heal-text). `custom.yml` stays exempt forever; its stale keys
  are covered by the loud legacy-key parse below.
- Threshold key: `low-health-threshold-percent` (clamp 0–100, DEFAULTS 35.0 —
  inert while the DEFAULTS layer is empty, so the no-op holds; signature 35).
  Legacy read: `low-health-threshold-hearts` present and percent absent ⇒
  warn once + convert `hearts × 10` (hearts of a 20-max player as percent);
  both present ⇒ percent wins with a warn. (Migration-written custom.yml
  files carry the hearts key on disk — the B10 no-silent-drops rule.)
- `effects.yml` + preset headers rewritten (vanilla feel = disable the
  modules); `config.yml` FEEDBACK comments fixed (they still reference the
  retired per-module files); GUI: icon map drops vanilla, preview renders
  "below N% of max health"; `EffectsPresetMenu` list is directory-driven
  already.
- Tester: `FeedbackSuite`'s restore/staging target becomes "signature".

## Verification

1. Unit: boundary-adoption pure pins; HealFold pins; parser pins (percent,
   legacy conversion, heal-text, fallback-to-signature, parse(empty) ==
   DEFAULTS unchanged); merge-book clamp pins; migration 4→5 cases.
2. New tester scenarios (ImmunityCadence, in/beside FeedbackSuite): a
   no-noDamageTicks-clear barrage at 2-tick cadence pins per version that
   (i) EDBEE count == cosmetics decisions count (they never diverge),
   (ii) Σ indicator damage == victim health actually lost (F3 across the
   legacy delta semantics), (iii) accepted-hit cadence ~ every 10-11 ticks.
   A heal scenario stamps attribution via a real melee, heals via
   `setHealth` (+ a `heal()` variant), asserts the HEAL trace decision and
   the fold pacing. Percent-threshold scenarios stage max-health windows.
   NOTE: fakes swing via the NMS attack path (no packets), so the matrix
   cannot reach F1's committed-knock branch — F1 is pinned at the pure seam
   and validated on the live lab (SimpleBoxer barrage) where real
   INTERACT_ENTITY packets drive the fast path.
3. `./gradlew build` (parse-equality, japicmp, kernel-Bukkit-free) →
   local PR-smoke integration → full matrix on remote CI (the accepted
   release gate).

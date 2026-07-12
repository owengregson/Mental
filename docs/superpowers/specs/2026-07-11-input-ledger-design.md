# 2.6.0-beta — the InputLedger round (+ StarEnchants compat, heal threshold)

Owner-approved 2026-07-11 (design presented and accepted in-session; spec review
waived). Three deliverables: the standalone **InputLedger** (the sprint-reset
ledger that makes fast-wtap registration real and provable), the three
**StarEnchants-compat** fixes, and the **sub-1-heart heal indicator threshold**.

## 0. Verified diagnosis this round builds on

Every mechanism below was adversarially verified (independent refuter agents,
code-cited); the load-bearing artifacts are bytecode-pinned.

### W-tap (the "fast-wtap is broken" report)

- The `SprintWire` spend-latch machine is **correct**: a replay harness driving
  the real packet cadences (movement-packet reconciles, deferred EDBEE clears
  at every interleaving) re-arms on every wire STOP→START. The failure is
  **signal starvation**, not state logic.
- **Confirmed defect — the block-hit reset door does not exist on defaults**:
  the era block-hit sprint reset lives solely inside `SwordBlockingUnit`
  (DAMAGE family, default OFF); modern clients emit no STOP/START during
  item-use, so with the feature off block-hitting can never re-arm.
- **Confirmed defect — vanilla-mint consumes**: a verdict minted off the live
  server flag (no wire provenance: fast-path early-returns, third-party
  ENTITY_ATTACK) spends the wire engagement through the same-tick-blind
  TickStamp guard (`SprintWire` stamp compare is strictly-newer), able to
  retro-eat a same-tick re-arm.
- **Confirmed structure — zero observability**: every re-arm funnels through
  one seam (`PacketTap.onEntityAction`); nothing counts, journals, or reports
  a starved feed. Pre-2.5.1 auto-re-adopt masked any dead feed entirely, which
  is why "broken since the beginning" was undatable.
- **Refuted — protocol starvation on 26.1.2**: shaded PacketEvents 2.13.0 maps
  protocol 775 exactly; the 69-entry serverbound-play id table matches Paper
  26.1.2's registration 1:1 (ENTITY_ACTION=42, PLAYER_INPUT=43); the ≥1.21.6
  sprint-action remap is correct; PLAYER_INPUT bit layout (sprint 0x40) is
  bit-identical. Newer-than-table protocols clamp gracefully onto the newest
  era table.
- **Bytecode-pinned — toggle-sprint w-taps DO cross the wire**: vanilla
  toggle-ON makes `keySprint.isDown()` a persistent latch; W-release kills
  forward impulse → STOP crosses; W-re-press re-arms via the level-triggered
  `keyPresses.sprint()` branch the same tick → START crosses. S-tap and
  GUI-open/close likewise produce full STOP→START pairs (GUI-open uniquely
  also drops the PLAYER_INPUT sprint bit for vanilla-toggle users — the
  remote signature that distinguishes vanilla toggle from Lunar-style mods).
- **Bytecode-pinned era contract — same-tick ATTACK-first**: on 1.8.9
  (real jar, obfuscated names cross-checked against the deobfuscated corpus)
  `Minecraft.runTick` processes clicks (`clickMouse` → C02 ATTACK, offset
  1765) BEFORE `updateEntities` (offset 2025) whose player tick emits the
  sprint diff (C0B START, `onUpdateWalkingPlayer`); 1.7.10 is
  source-identical (bytecode unverified — no client jar on disk); 1.21.11 has
  the same order (`handleKeybinds` @373 before the tick's input diff @436).
  **A hit thrown on the same client tick as the sprint-re-engaging W edge
  ships ATTACK before START and is a plain hit — era truth on every client
  generation. The ledger keeps pure arrival order; no reordering rule.**
  (Era detail: 1.8.9 sends ANIMATION before ATTACK; modern reverses. No
  bearing on the sprint question.)
- The feature-disabled behavior (every held-sprint hit carries the bonus) is
  the **documented published-view fallback** — no engagement semantics by
  design (packetless synthetics depend on it). Not a bug; unchanged.

### StarEnchants cosmetics skip (three confirmed mechanisms)

Mental's hit sound and damage indicator both emit from EDBEE MONITOR
listeners guarded by `ignoreCancelled=true` and `finalDamage > 0`, after the
netty fast path already pre-sent the flinch+knock burst. Three SE behaviors
occasionally prevent a damage-carrying EDBEE from reaching MONITOR:

1. **Cancel procs** (dodge 1–6%, ethereal-dodge, inversion 6–12%, the immune
   applier) cancel the melee EDBEE at HIGH → all three MONITOR consumers skip
   (sound, indicator, AND the authoritative knock) while the pre-sent client
   knock stands — a knock the server never applied.
2. **Bare `victim.damage()` procs** (DAMAGE effects, lightning, bleed DoT)
   re-arm `noDamageTicks=20`/`lastHurt`; a melee with `amount <= lastHurt`
   is window-rejected — **no EDBEE at all** — and boundary adoption correctly
   refuses foreign re-arms (`b1b3979`).
3. **Blacksmith fold-to-zero** (verifier-upgraded to shipped-content): the
   attack-side `-50` × `attack-scale: 5.0` drives
   `base × max(0, 1 + cappedOutgoing×attackScale)` to exactly 0
   (`DamageFold.java:87-89`); a chance proc (2.75–11.25%) produces a
   non-cancelled 0-damage melee → both cosmetics trip the `finalDamage <= 0`
   guard, no suppressor mark arms, and the **vanilla** hurt sound plays where
   the custom one should be.

### Heal indicators

`HealFold.poll` ships any positive sum; the only epsilon is a float-noise
guard on the per-tick delta. Aggregation is sum-and-flush per 10-tick window,
first ship immediate. Ambient (unattributed) regen is already silent.

## 1. The InputLedger

### 1.1 Goals / non-goals

Goals: (a) one authoritative, observable, per-player ledger of sprint-reset
input events on the netty path — sub-tick, arrival-ordered; (b) every reset
form wired always-on (w-tap, s-tap, block-hit one-credit, GUI cycle);
(c) the one-engagement-one-sprint-knock contract preserved byte-exactly;
(d) every verdict self-explaining in the journal.

Non-goals: no change to knockback values, the client-side technique contract,
the feature-disabled fallback, or the era ATTACK-first arrival semantics. No
key-edge promotion to reset verdicts (era-wrong — see 1.4).

### 1.2 Placement & threading

New kernel package `me.vexmc.mental.kernel.ledger` (pure JDK, build-asserted
Bukkit-free). One `InputLedger` per player, owned by the D1 connection domain.

- **Appender**: the parse rim (netty read thread) is the sole event appender.
- **Consume**: arrives from D2 (the deferred EDBEE consume on the region
  thread) — cross-thread, so ledger state lives behind one
  `AtomicReference<LedgerState>` with CAS transitions (the SprintWire
  discipline, kept).
- **Verdict**: read at ATTACK parse on D1 in program order with the attacker's
  own packets — sub-tick, arrival-ordered (the era queue).
- Bukkit toggle events never write the ledger (they fire at packet
  application; a boundary-applied STOP would overwrite a newer wire START).

### 1.3 The event model

Bounded per-player ring (128) of immutable `InputEvent` records:

| kind | source | versions | notes |
| --- | --- | --- | --- |
| `SPRINT_START` / `SPRINT_STOP` | ENTITY_ACTION (PLAYER_COMMAND) | all | the reset gestures |
| `INPUT_SNAPSHOT` | PLAYER_INPUT (7 bits) | ≥1.21.2 | edges derived at append (W fall/rise, S press/release, sneak, sprint 0x40) — **evidence, never verdict** |
| `USE_ITEM` / `RELEASE_USE_ITEM` | use-item / player-action packets | all | the block-hit lane (fed always-on) |
| `WINDOW_CLOSE` | close-window | all | GUI lane (GUI *open* has no serverbound packet; its signature is the all-bits-drop snapshot + the STOP that follows) |
| `ATTACK` | the attack parse | all | trace context + verdict anchor |
| `MOVE` | movement packets | all | reconcile carrier (unchanged cadence) |
| `SEED` / `ADOPT_TRUE` / `ADOPT_FALSE` / `ADOPT_BLOCKED` | reconcile decisions | all | every reconcile outcome becomes a visible event |
| `BLOCK_RESET` | the always-on block door | all | grants the one-shot credit |
| `CONSUME` | D2 write-back | all | the engagement spend |
| `SERVER_FALL` | published-flag falling edge | all | the latch failsafe (1.6) |

Each event: `kind`, `eventSeq` (monotonic, every event), `tick`
(`TickStamp`), packed `bits` payload. Derived state updates O(1) at append;
the ring exists for the journal, the tester, and live diagnosis — never for
verdict scans.

### 1.4 The seq partition (the resurrection-trap defense) and the reset vocabulary

Two counters, structurally separated:

- `eventSeq` — bumps on **every** event. Observability only.
- `resetSeq` — bumps **only** on genuine reset gestures: `SPRINT_START`,
  `SPRINT_STOP`, `BLOCK_RESET`, an applied adopt. The deferred consume guard
  (protecting a re-arm that lands between the verdict peek and the EDBEE
  consume) compares `resetSeq`.

Rationale: PLAYER_INPUT snapshots fire on any of 7 bits changing — strafing
chatter. If snapshots bumped the guarded counter, every consume would no-op
and every held hit would read fresh — the 2.5.1 consume-veto bug rebuilt.
This partition is the load-bearing design decision of the ledger; it gets a
dedicated kernel pin.

Reset vocabulary (era-derived): only client-expressed sprint-state cycles
re-arm — STOP→START pairs (how w-tap, s-tap, and GUI resets all manifest on
every client mode; bytecode-pinned), the block-hit door, adopt-FALSE, and the
`SERVER_FALL` failsafe. PLAYER_INPUT key edges are recorded evidence and
block-gate corroborators only: a double-tap client that single-re-presses W
is genuinely not sprinting, and era servers shipped those hits plain.

### 1.5 Verdict & consume

`verdictAt(attack)` (D1, at parse): `sprinting` (wire truth at this arrival
instant), `fresh` (a reset gesture with `resetSeq > lastConsumeResetSeq`, or
the block credit), the `resetSeq` stamp for the guard, provenance = WIRE.
`SprintVerdict`'s consumer-facing shape (desk, engine, `HitContext` stamp) is
unchanged.

Consume (D2, the accepted bonus hit's EDBEE): CAS a `CONSUME` event —
sprinting+armed drop, the spend latch opens — guarded by `resetSeq` (no-ops
if any genuine reset gesture followed the peeked verdict). **Vanilla-mint
fix**: a verdict without wire provenance never spends the wire engagement —
`applyAttackerObligations` skips the ledger consume for non-wire verdicts
(the published-flag fallback keeps its no-engagement semantics; nothing else
changes for those hits).

### 1.6 Reconcile & the latch failsafe

Same semantics as today, re-encoded as visible events: seed once per domain
lifetime from the published flag (carries the latch), adopt on
wire/view disagreement after the quiet window, **adopt-TRUE latch-blocked**
while a consume is outstanding, adopt-FALSE never blocked. New failsafe:
the published server flag's **falling edge** observed while the latch is open
appends `SERVER_FALL` and closes the latch (a client STOP provably reached
vanilla even if the wire missed it — era-sound: a STOP is a latch-closing
gesture; it does not arm). Graceful degradation for unknown
proxy/translation environments.

### 1.7 The always-on block-hit door (one hit per reset)

A thin always-on listener (`BlockResetTap`, knockback core) on the
born-cancelled `RIGHT_CLICK_AIR` interact and the victim-aimed
interact-entity feeds `onBlockSprintReset()` under the existing eligibility
gate (raw client sprint, or key-intent corroboration where PLAYER_INPUT
exists); the `RELEASE_USE_ITEM` lane rides the rim as trail evidence. The
item gate: shields always (the only blocking gesture a default config has);
SWORD_BLOCKING contributes its decorated-sword test while enabled.

Semantics (owner-directed, supersedes 2.5.1's fresh-chain-while-held —
refined during implementation, 2026-07-11): the block re-arm is **exactly a
START-shaped gesture**, so the ordinary one-engagement spend already scopes
the grant to ONE hit per reset — no override flag exists to chain. An
UNSPENT re-arm survives the block release (era-exact: the era client's
release re-engage is what re-armed the bonus, so a block-tap earns the same
single fresh hit a w-tap does); a STOP still drops the wire view (no phantom
bonus while standing). `SwordBlockingUnit` keeps its damage rules — no
double doors. Zero-touch holds: the door changes nothing unless a sprint hit
already consumed an engagement, and a non-blockable item never reaches it.

### 1.8 Observability

- Journal per-hit tokens gain `resetseq=` and `trail=` — the last few ledger
  events with tick offsets relative to the hit
  (`sprint=f trail=STOP-2,START+0` reads "the START trailed the attack inside
  the same tick; era-plain"). Same-tick trailing STARTs get `note=start-trailed`.
- `wire=starved` fires when a consume happens on a ledger that has never seen
  a `SPRINT_START` (per-domain counters: entity-actions seen, starts seen).
- The boot report prints the active input lanes per tier (ENTITY_ACTION
  always; PLAYER_INPUT ≥1.21.2; use-item/window always).

### 1.9 Version tiers & migration

ENTITY_ACTION, use-item/player-action, close-window exist across the full
range (PE maps per version); the PLAYER_INPUT lane is boot-probed and absent
below 1.21.2 — evidence lane off, verdicts unaffected (era-correct fallback
doctrine). Migration: `SprintWire`'s machine is subsumed by the ledger's
derived state; `SprintVerdict` consumers untouched; all SprintWire unit pins
re-targeted; the diagnosis replay harness (S1–S5) lands as permanent kernel
tests; new pins per reset form; the era same-tick ATTACK-first contract gets
a cited pin. `WTAP_REGISTRATION` keeps its toggle and its disabled fallback.
`SprintWire` is removed once nothing references it (kernel is additive-only
at the API surface — `SprintWire` is kernel-internal, not `api/`; japicmp
gates the real API).

## 2. StarEnchants compat

### 2.1 Mental: phantom-knock reconcile

New MONITOR `ignoreCancelled=false` EDBEE path (delivery family): when
`event.isCancelled()` and the session's active transaction has a committed
knock (PRE_SENT/PINNED) — withdraw the desk pending, ship a corrective
velocity carrying the victim's true server velocity, journal
`reason=cancelled-by-plugin`. The window-rejection variant is pre-detected in
`damageWithSlot`: committed knock + foreign re-arm
(`noDamageTicks > max/2+1`) + `amount <= lastDamage` (vanilla will reject
with no event) → the same reconcile, `reason=foreign-window-reject`. No
window clamping — the pile-on DPS-bypass analysis in the adoptBoundary
javadoc stands. Cosmetic silence for a genuinely dodged/rejected hit is
correct and stays.

### 2.2 Mental: 0-damage coherence

`HitFeedbackListener`: arm the `HurtSoundMarks` mark ABOVE the
`finalDamage <= 0` guard and voice the custom hit sound for 0-damage
connected hits (vanilla-snowball semantics — the hit connected). The
indicator keeps its 0-guard (no "0" stands). The vanilla hurt broadcast for
such hits is now suppressed by the armed mark, ending the
vanilla-sound-where-custom-should-be incoherence.

### 2.3 StarEnchants: fold same-hit damage

In the SE repo (own branch/release): victim-targeted zero-delay DAMAGE
effects on the current event join the `DamageFold` (`addFlatDamage`) instead
of a bare second `hurt()` — same-hit bonus damage never arms a second
immunity window. Genuinely separate procs (WAIT DoT ticks, reflects) route
through `damage(amount, attacker)` so downstream plugins see an attributed
event. SE's dodge/immune cancel procs are untouched (§2.1's reconcile makes
them coherent from Mental's side).

### 2.4 Delta-hit era silence (folded in 2026-07-11, owner-directed)

The owner's "hit sound/effect plays double on crits / in the air" report,
root-caused and adversarially verified during the round: a stronger hit
inside the victim's half-open invulnerability window takes vanilla's UPGRADE
branch, which fires the Bukkit event with the DELTA but zeroes the fresh-hit
flag gating every client effect — no hurt sound, no flinch, no knockback
(era-exact per the compendium). With `simulate-crits` on (default), a falling
attacker's 1.5× regularly converts silenced window hits into voiced delta
events — the double. Fix: `HitFeedbackListener` goes ERA-SILENT (no sounds,
no particles, no suppressor mark — vanilla broadcasts nothing to suppress) on
delta hits, recording an `ERA_SILENT_DELTA` trace decision; the delta damage
INDICATOR deliberately stays (a display choice — the number is information).
The predicate — `noDamageTicks > max/2` during the event — mirrors the
server's own branch selector and is bytecode-verified band-by-band
(1.9.4/1.12.2/1.17.1/1.21.11): the fresh branch re-arms the window only AFTER
its event returns on every band, so no version gate. Two verified-latent
issues deliberately NOT in scope (filed): the one-mark-vs-per-viewer-packet
suppression gap (≥2 bystanders hear vanilla's hurt beside the custom sound)
and the missing same-tick voice dedup (indicators merge, sounds don't).

## 3. Heal indicator threshold

`HealFold.poll`: `MIN_SHIP_HEALTH = 2.0` health points (1 heart); the
early-return becomes `sum < MIN_SHIP_HEALTH` and — because poll only zeroes
after deciding to ship — sub-threshold sums keep accumulating, so trickle
regen crossing a heart still ships once, summed. Strict `<` (a full-heart
heal shows). Hard constant, no knob. `dripAggregatesToTenPerWindow` is
rewritten (sub-2.0 holds, ships once crossed); tester pins added: a
sub-heart attributed heal writes NO heal decision; two +1.0 heals in one
window ship exactly one 1-heart decision.

## 4. Verification

1. `./gradlew build` — kernel ledger pins (seq partition, one-credit door,
   consume guards, same-tick ATTACK-first, S1–S5 replays), re-targeted
   SprintWire pins, HealFold pins, listener-descriptor doctrine.
2. **Live wire suite** — legacy-lab `measure.js` (real protocol client) at
   1.21.11 against a staged current build, asserting JOURNAL
   `sprint=/fresh=/trail=` per hit: w-tap at offset, same-tick both orders
   (START-then-ATTACK fresh; ATTACK-then-START plain + `note=start-trailed`),
   s-tap, block-hit one-credit cycle (hit fresh → hit plain → release,
   re-block → hit fresh), GUI reset, no-reset control (plain). Four harness
   additions (release-use-item, s-tap, GUI, ATTACK-first order variant),
   each ≤15 lines. SimpleBoxer is packetless to Mental (in-process dispatch)
   and cannot serve wire assertions — view-fallback regression only.
3. The matrix (nonce rule): 26.1.2 parity carried by the matrix + the
   bytecode-verified decode (the protocol library ceiling is 1.21.11).
4. Post-release: one JOURNAL capture on the owner's live server — now
   self-explaining via `trail=`.

## 5. Release shape

Mental `2.6.0-beta` (new kernel subsystem) from this branch
(`release/2.6.0-beta`); StarEnchants fix on its own branch off SE main,
versioned there. Docs updated alongside: the netty-fast-path skill's stale
reconcile description (it says owning-thread per-tick; the code reconciles on
the netty thread per movement packet) and the wire-view section rewritten for
the ledger.

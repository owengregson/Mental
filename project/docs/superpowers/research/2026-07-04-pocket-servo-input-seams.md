# Pocket-servo predictor inputs — the per-seam inventory (precision round)

Companion to the combo-hold design's §3.2b precision mandate
(`docs/superpowers/specs/2026-07-04-combo-hold-pocket-servo-design.md`).
Inventoried against **release/2.4.1 @ `1d31f75`** (2026-07-04). The v1
combo-hold implementation is landing concurrently on `feat/combo-hold`; at
inventory time that branch carries ONLY the spec commits — **no
`ComboTracker`, `PocketServo`, or `comboAttackerId` code exists anywhere in
the tree yet** (verified: branch head `00cbc56`, worktree clean, grep empty).
Where the v1 branch's planned seams overlap a precision input, the per-input
row says so; every file:line below is the release/2.4.1 source of truth.

## 0. The two knock-compute sites

| Site | Entry | Reads |
| --- | --- | --- |
| **Netty pre-send** (attacker's netty read thread, D1) | `HitRegistrationUnit.plan` — `core/src/main/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationUnit.java:254`; states built at `preAttackerState`:348 / `preVictimState`:363 | Published `PlayerView`s (`sessions.viewOf`), `PositionRing.latest`, `ConnectionDomains.Domain` (lastYaw, SprintWire), `LatencyModel`. Never a live entity (RimArchitectureTest discipline). |
| **Region compute** (victim's owning region thread, D2) | `KnockbackUnit.onEntityDamageByEntity` (EDBEE, MONITOR) — `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java:122`; captures at `EntityStates.captureVictim`:73 / `capture`:42 (`core/src/main/java/me/vexmc/mental/v5/EntityStates.java`) | Live victim + live attacker (attacker reads guarded by `isOwnedByCurrentRegion` at KnockbackUnit.java:149), the `MotionLedger` residual, the session's own `PlayerView`, `LatencyModel`. |

Both call the same kernel entry (`KnockbackEngine.computePaced`,
`kernel/src/main/java/me/vexmc/mental/kernel/math/KnockbackEngine.java:109`);
the servo's σ seam sits beside `pace` at :124. A pre-sent/pinned vector is
**adopted** at the region site (KnockbackUnit.java:189–199), so for fast-path
hits the netty site is where σ is actually decided — the region site computes
fresh only for the REGISTERED path (server-side melee, suppressed pre-sends).

Ordering fact the drift math leans on: `SessionService.tick`
(`core/src/main/java/me/vexmc/mental/v5/session/SessionService.java:227–242`)
runs `buildView → tickStep (publish) → positions.record`. So at any netty
read, `PositionRing.latest` is the CURRENT tick's position while
`view.motion()` is the residual as of the END of the previous tick — the
delta of the last two ring samples measures exactly the tick the published
residual just finished, which is what makes `measured − residual` a coherent
input-drift signal.

## 1. Per-input inventory

### (a) Measured per-tick horizontal velocity (victim) — the drift signal

- **Netty site:** the samples exist — one owning-thread `PositionRing` sample
  per tick (`kernel/src/main/java/me/vexmc/mental/kernel/wire/PositionRing.java:52`,
  written at SessionService.java:239–241), 40 deep, synchronized ring, netty
  reads today via `latest()` (:77) and `samplesAround()` (:63). **No delta
  accessor exists** — nothing returns the last TWO samples, so per-tick
  velocity is not currently derivable at this seam without growth.
  Staleness once built: sample is same-tick; the view's 4-tick `fresh` gate
  (PlayerView.java:62) already fences stale players.
- **Region site:** the ring is written by this thread, so it may read it
  freely; or live `getLocation()` minus the previous ring sample. The ledger
  residual for the subtraction is `session.ledger().current()`
  (captureVictim, EntityStates.java:78).
- **Gap + cost (choose one, view growth preferred):**
  1. **View components** `measuredVx`/`measuredVz` (preferred): in
     `SessionService.tick`, read `positions.latest(id)` BEFORE recording the
     new sample (= last tick's position), delta against the current
     `getLocation()`, hand to `ViewBuilder.build`. Measured motion and the
     ledger residual then freeze at the SAME publish — the drift signal
     `(measuredVx − motion().vx(), measuredVz − motion().vz())` is coherent
     by construction at both sites. D2 single-writer, no new locks.
  2. **Ring accessor** `latestPair(UUID)`/`recent(UUID, n)` (kernel-additive,
     same synchronized section): no view growth, but the netty reader then
     pairs a ring delta (current tick) with a view residual (previous tick)
     — a documented 1-tick skew instead of one frozen truth.
- **v1 overlap:** none — v1's solve consumes the residual only; this is the
  precision round's flagship addition (§3.2b item 1).

### (b) Yaw / facing (victim; attacker already covered)

- **Netty site (today):** `ConnectionDomains.Domain.lastYaw`
  (`core/src/main/java/me/vexmc/mental/v5/rim/ConnectionDomains.java:54`),
  written by the rim tap on the victim's own netty thread
  (`core/src/main/java/me/vexmc/mental/v5/rim/PacketTap.java:78`), read
  today from the ATTACKER's netty thread for the hurt-yaw tilt
  (HitRegistrationUnit.java:312–316). Two precision caveats: it is a plain
  non-volatile float (a cross-D1-connection read; JMM staleness tolerated for
  a cosmetic tilt, not precision-grade), and packetless players (FakePlayer,
  in-process SimpleBoxer bots — no `ConnectionDomains` entry, :81) read a
  permanent 0. Note `preVictimState` passes yaw 0 today
  (HitRegistrationUnit.java:368) — the engine never consumed victim yaw.
- **Region site:** live and exact — `location.getYaw()` already captured
  into `EntityState.yaw` (EntityStates.java:80–84).
- **Gap + cost:** publish `yaw` as a `PlayerView` component from `buildView`
  (`location` is already in hand, SessionService.java:283) — frozen,
  coherent, packetless-safe. The arrival-fresh domain float stays available
  as the sub-tick refinement where a wire exists.

### (c) The player_input stream (wire: 1.21.2+; design bound 1.21.4+)

- **Today: NOT tapped.** No reference to `PLAYER_INPUT` /
  `WrapperPlayClientPlayerInput` anywhere in core/kernel/platform/api/tester
  (grep over release/2.4.1, 2026-07-04). The rim parses only movement
  (`PLAYER_POSITION`/`…AND_ROTATION`/`ROTATION`/`FLYING`) and
  `ENTITY_ACTION` (PacketTap.java:53–68).
- **What a tap costs at the rim** (`me.vexmc.mental.v5.rim`, pinned by
  `core/src/test/java/me/vexmc/mental/v5/rim/RimArchitectureTest.java:35–42`):
  - The shaded PacketEvents **2.12.1 already carries the wrapper**:
    `PacketType.Play.Client.PLAYER_INPUT` +
    `WrapperPlayClientPlayerInput` with `isForward/isBackward/isLeft/isRight/
    isJump/isShift/isSprint` (javap-verified against the resolved
    `packetevents-api-2.12.1` jar). No dependency change.
  - One reference-compared branch in `PacketTap.onPacketReceive` (the
    connection-state discipline: never downcast, compare against the
    `Play.Client` constant — the 1.3.0 CCE regression rule).
  - A `Domain`-side holder: pack the 7 flags into one int, write to a single
    `volatile` field (or one `AtomicLong` carrying flags+TickStamp). ONE
    writer — the victim's own connection thread (D1 single-writer holds);
    cross-thread readers (the attacker's netty thread at plan-time, the
    region thread) get an atomic coherent snapshot. This is the
    `ConnectionDomains` licensing rule (ConnectionDomains.java:15–25): a
    multi-bit value must NOT repeat `lastYaw`'s plain-field shortcut.
  - Tests: RimArchitectureTest scans every rim source automatically (no test
    change needed to stay pinned); add a `PacketTapStateTest`-style pin that
    a Configuration-state packet never reaches the parse.
  - Version behavior: below 1.21.2 the packet never arrives — the holder
    stays empty and the predictor degrades to (a)'s measured drift. No boot
    probing, no capability flag needed (absence is the era-correct null).
- **Region site:** read the same volatile snapshot (any-thread-safe), or
  mirror it into the view per tick (adds ≤1 tick staleness). For blending
  with (a), the netty-fresh direct read is the better netty-site source; the
  view mirror is the "one frozen truth" option if the lab round shows the
  sub-tick freshness doesn't earn its cross-connection read.

### (d) Per-connection RTT — BOTH players

- **Where it lives:** the kernel `LatencyModel`
  (`kernel/src/main/java/me/vexmc/mental/kernel/wire/LatencyModel.java:33`,
  `Record` :47 — volatile `pingMillis`/`previousPingMillis` + rolling
  `jitterMillis()`), a concurrent map keyed by UUID: **readable from any
  thread for any player** — reading another player's record is just
  `latency.forPlayer(otherId).pingMillis()`.
- **Netty site (today):** already reads the VICTIM's record
  (HitRegistrationUnit.compensationFor:416–423). The ATTACKER's half-RTT
  (§3.2b item 4, the prediction-horizon shift) is the same call with
  `attackerId` — zero growth. The reach gate meanwhile uses the view's
  Bukkit ping (`attackerView.pingMillis()`, :454).
- **Region site (today):** same model, same reads
  (KnockbackUnit.compensationFor:250–265).
- **Freshness/gating:** probes ride `LatencyCompensationUnit`
  (`core/src/main/java/me/vexmc/mental/v5/feature/knockback/LatencyCompensationUnit.java`),
  which marks **both parties** of every melee EDBEE as combatants (:96–101)
  and probes each every `probeIntervalTicks` (default **5 ticks = 250 ms**,
  `CompensationSettings.DEFAULTS`, combat timeout 30 ticks) — so both RTTs
  are combat-fresh whenever `LATENCY_COMPENSATION` is enabled. When that
  feature is OFF, `pingMillis()` is null → fall back to
  `PlayerView.pingMillis` (int, published every tick from `Pings.of` —
  `platform/src/main/java/me/vexmc/mental/platform/Pings.java`, Bukkit
  keepalive-grade). `jitterMillis()` is available for the dynamic target's
  ping slack. Legacy (<1.17) transparently rides the TRANSACTION transport —
  same model, no caller change.

### (e) Victim's OWN walk-normalized move-speed attribute

- **Netty site:** **already published** — `PlayerView.moveSpeedAttr` is built
  for EVERY player each tick (SessionService.buildView:295 via
  `Attributes.movementSpeedWalkNormalized`,
  `platform/src/main/java/me/vexmc/mental/platform/Attributes.java:103–110`);
  the javadoc frames it as the attacker's input, but the field is
  per-player — the VICTIM's view carries their own value today.
  `preVictimState` simply drops it (11-arg `EntityState`,
  HitRegistrationUnit.java:368–371).
- **Region site:** `EntityStates.captureVictim` uses the 11-arg ctor
  (EntityStates.java:80–84) — add the same one-line
  `Attributes.movementSpeedWalkNormalized(victim)` read (owning-thread
  stance-coherent, the F1 rule).
- **Gap + cost:** ZERO record growth — the 12-arg `EntityState` arity already
  exists (EntityState.java:42–56, sentinel
  `MOVE_SPEED_UNAVAILABLE` :35). Both victim captures just start passing the
  value. Below the attribute API the sentinel resolves to the 0.10 walk
  baseline — the ground-tail drift term (§3.2b item 5) then uses factor 1.0.

### (f) Grounded state + consecutive grounded ticks

- **Grounded (exists at both):** netty = `view.grounded()`
  (client-reported flag frozen at buildView, SessionService.java:285) +
  `view.kinematics().clientOnGround()`; region = live `player.isOnGround()`
  (captureVictim, EntityStates.java:77 — the handle-free read the
  netty-fast-path skill javap-proved safe).
- **Consecutive grounded ticks: NO source today.** What exists: the packet
  `GroundFsm` emits Landing/Liftoff `LedgerEvent`s with TickStamps
  (`kernel/src/main/java/me/vexmc/mental/kernel/wire/GroundFsm.java:71,97`)
  into the session inbox; `MotionLedger.groundedView()` (:136) knows the
  current segment's grounded bit but its tick counter is private and
  since-last-RECORD, not since-landing. Nothing counts the run.
- **Cost:** a D2 counter — in `SessionService.tick`/`CombatSession.tickStep`,
  `groundedTicks = grounded ? groundedTicks + 1 : 0` — published as a
  `PlayerView` int component. Single-writer, one line of state.
- **v1 overlap (real):** the v1 `ComboTracker` counts a grounded run for its
  END condition (spec §3.1 `groundedRunTicks`) inside its D2 state, fed by
  the same ledger ground events. The precision predictor needs the count at
  the NETTY site too, so the view component is required either way —
  coordinate with the combo-hold branch so the run is counted ONCE (session
  counter feeding both the tracker and the view component), not twice.

### (g) Heights / eye positions — the live triangle

- **Both parties' Y at hit:** netty = `PositionRing.latest(id).y()` (feet,
  current-tick owning-thread sample — already the pre-send position source,
  HitRegistrationUnit.java:349–353, 364–367) and the victim's
  `view.kinematics().y()`; region = live `getLocation().getY()`.
- **Victim flight arc:** physics from the shipped vertical stamp — the
  `CompensationQuery` flight-sim precedent
  (`kernel/src/main/java/me/vexmc/mental/kernel/wire/CompensationQuery.java:22–44`)
  plus `view.kinematics().distanceToGround()` (measured at buildView via
  `GroundDistance.measure`, SessionService.java:299–300) for touchdown
  truncation. All inputs exist.
- **ATTACKER head Y — the named gap:** at the netty site the only eye source
  is the standing constant `ReachValidator.EYE_HEIGHT = 1.62`
  (`kernel/src/main/java/me/vexmc/mental/kernel/wire/ReachValidator.java:23`,
  used for the reach eye at :459). Pose is invisible: a sneaking attacker's
  eye is 1.27 (1.14+) / 1.54 (legacy) — up to 0.35 of error straight into
  the Δ² margin. Region site is exact and live:
  `attacker.getEyeLocation().getY()` (region-guarded attacker reads,
  KnockbackUnit.java:149).
- **Cost:** publish `eyeHeight` as a `PlayerView` component
  (`Player#getEyeHeight()` is pose-aware and owning-thread-read at
  buildView), defaulting to 1.62 in the old-arity ctor. The same component
  on the victim's view serves the dynamic target's victim-eye term. (Cheap
  alternative — accept the constant: combo holders essentially never sneak
  mid-combo since sneaking kills sprint; the lab round can price the error
  before spending the component.)

## 2. Exact additive growth (the precision round's component list)

**PlayerView** (`kernel/src/main/java/me/vexmc/mental/kernel/model/PlayerView.java:14–21`,
currently 20 components) — **+5 components, ONE new canonical arity**, the
existing 20-arg ctor becomes the old-arity default (the `moveSpeedAttr`
precedent at :35–47):

| # | Component | Type | Old-arity default | Written by |
| --- | --- | --- | --- | --- |
| 21 | `measuredVx` | double | 0.0 | D2 session tick (prev ring sample → current location delta) |
| 22 | `measuredVz` | double | 0.0 | same |
| 23 | `yaw` | float | 0.0f | D2 buildView (`location.getYaw()`) |
| 24 | `eyeHeight` | double | 1.62 (`ReachValidator.EYE_HEIGHT`) | D2 buildView (`getEyeHeight()`) |
| 25 | `groundedTicks` | int | 0 | D2 session counter (shared with the v1 tracker's grounded-run end) |

Sequencing with v1: `feat/combo-hold` will add its OWN view component
(`comboAttackerId` / `ComboState`, spec §3.1) through the same old-arity-ctor
pattern. Whichever branch lands second rebases to produce ONE newest arity;
both keep their old-arity ctors so neither breaks the other's constructions
(`ViewBuilder.build`, `core/src/main/java/me/vexmc/mental/v5/session/ViewBuilder.java:30`,
is the single production caller to grow).

**EntityState** (`kernel/src/main/java/me/vexmc/mental/kernel/model/EntityState.java`)
— **zero growth.** The 12-arg arity (`moveSpeedAttr`) already exists (:42–56);
the two victim captures (`EntityStates.captureVictim`,
`HitRegistrationUnit.preVictimState`) start passing the victim's value, and
`preVictimState` starts passing the victim's view yaw instead of 0. If the
servo solve needs drift/target/ping as ENGINE inputs, do NOT grow
EntityState: pass a new kernel record (e.g. `PredictorInputs(driftVx,
driftVz, attackerRttMillis, victimRttMillis, victimYaw, attackerHeadY,
groundedTicks)`) beside it into the combo overload — `computePaced` stays
byte-identical (kernel additive-only invariant) and the record is one value
both sites can freeze.

**HitContext** (`kernel/src/main/java/me/vexmc/mental/kernel/model/HitContext.java:20–24`)
— **+0 mandatory, +1 optional.** Pre-sent/pinned hits carry σ inside the
vector (adopted at EDBEE, so no re-derivation happens). If the precision
round wants the REGISTERED region recompute to adopt registration-time
predictor inputs instead of re-reading live (the compute-once R5 rule; the
`compensationY` precedent, :17), add ONE nullable component (the
`PredictorInputs` record above) with an old-arity ctor. The journal carrier —
`HitTransaction.comboFactor` mutable field + `JournalEntry.comboFactor`
additive component (the `paceFactor` precedent,
`kernel/.../delivery/HitTransaction.java:46–69`,
`kernel/.../model/JournalEntry.java:21–33`) — is a v1-spec item (§3.2
"Journaled"), not precision-round growth.

## 3. Single-writer implications per new capture

| Capture | Domain | Writer | Readers | Rule |
| --- | --- | --- | --- | --- |
| measuredVx/Vz, yaw, eyeHeight, groundedTicks | D2 → view | The session tick (owning thread), via ViewBuilder | Netty (published view), region (own view) | No new writers, no locks — rides the existing `AtomicReference` publish (CombatSession.tickStep:102–111). |
| player_input holder | D1 (Domain) | The victim's OWN connection netty thread (rim tap) | Attacker's netty thread (plan), region thread (optional) | Must be an atomic snapshot (volatile packed int / AtomicLong) — the ConnectionDomains licensing rule; never written from Bukkit events (the SprintWire boundary-STOP lesson). |
| Ring delta accessor (if chosen) | D2-write / D1-read | unchanged (session tick) | unchanged (netty) | Same synchronized ring; additive method only. |
| Attacker RTT at netty | D3-shared | Probe rims (netty) / LatencyCompensationUnit (async) | Any thread | Already concurrent by design (volatile record fields); read-only growth. |
| Cross-connection D1 reads (victim domain from attacker's netty thread: lastYaw today, input flags next) | D1↔D1 | — | — | Licensed ONLY for atomic-snapshot state; each such read gets a javadoc note (the existing lastYaw hurt-tilt read is the tolerated-staleness precedent, not a license for multi-bit state). |

Packetless players (FakePlayer, in-process SimpleBoxer bots): views, rings,
and the tick-sampler ground feed all work (SessionService.sampleGround:256);
Domain-based inputs (yaw wire, player_input) never exist for them
(`ConnectionDomains.has`:81) — every Domain-sourced input needs the
view-published fallback to keep the lab suites meaningful.

## 4. Blockers (inputs with NO existing source) and build costs

1. **player_input (c)** — no rim tap exists. Build: one reference-compared
   branch in `PacketTap` + a volatile packed-int holder on `Domain` + state
   pin test. Small (≈1 file touched, 1 field, PE 2.12.1 wrapper already
   shaded); wire-absent below 1.21.2 degrades naturally.
2. **Consecutive grounded ticks (f)** — no run counter anywhere. Build: one
   D2 session counter + the `groundedTicks` view int. Trivial; coordinate
   with v1's ComboTracker grounded-run end so it is counted once.
3. **Pose-aware attacker head Y at netty (g)** — only the standing 1.62
   constant. Build: the `eyeHeight` view component (or lab-price the
   constant's error first — sneaking mid-combo is rare).
4. **Measured per-tick velocity (a)** — samples exist, no delta seam. Build:
   the two view components (preferred, publish-coherent) or a ring accessor.

Nothing requires a new domain, a new thread, or a non-additive change; every
gap closes with the old-arity-ctor pattern already proven twice
(`moveSpeedAttr`, `paceFactor`).

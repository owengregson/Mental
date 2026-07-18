# The modern client sprint wire — empirical extraction (1.21.11)

Date: 2026-07-10. Owner directive: replace every sprint-behavior assumption with
empirically hunted fact. Method: the OFFICIAL 1.21.11 client.jar + Mojang client
mappings (downloaded from piston-meta) and the deployed Paper 1.21.11 server jar,
disassembled with javap; 5 extractors, 60 claims, every claim independently
re-derived by an adversarial Opus verifier against the same binaries (0 errors;
5 prior assumptions corrected, marked `contradicts-prior-assumption`).

## The scenario → wire matrix (the distilled truth)

| Scenario (1.21.11 client) | What crosses the wire |
| --- | --- |
| Sprint start (key-hold/toggle/double-tap) | ONE `PlayerCommand START_SPRINTING` from the per-tick diff sender (`sendIsSprintingIfNeeded` — the ONLY sprint-packet emitter in the jar) |
| Holding sprint, spam-clicking attacks (charge ≤0.9) | NOTHING — no un-sprint prediction (`causeExtraKnockback` gated on `sprinting && charge>0.9`), no packets |
| Full-charge sprint hit (or CADENCE spoof), key HELD, moving, not blocking | NOTHING — the predicted un-sprint re-arms in the SAME tick's `aiStep` (level-triggered, before the diff sender): wire-silent |
| Full-charge sprint hit while item-use blocked (block-holding) | ONE `STOP_SPRINTING` (re-arm blocked by `isSlowDueToUsingItem`), then `START_SPRINTING` on the first un-blocked tick |
| Server-side `setSprinting(false)` (the old Mental echo) | Echoed to self (`sendToTrackingPlayersAndSelf`, unconditional for ServerPlayer); client adopts silently (no local-player exclusion, no separate field); THEN key-holders re-arm same/next tick (often wire-silent), item-use-blocked clients emit one STOP + later START, double-tap sprinters LATCH until a full re-gesture |
| Blockhit cycle (RMB hold → LMB → release) | RMB: `INTERACT_AT`+`INTERACT` (on-victim) then `USE_ITEM`; LMB during block-hold emits NOTHING (attack clicks drained by `handleKeybinds`); release: `PlayerAction RELEASE_USE_ITEM`; attack possible next tick: `Interact ATTACK` → charge-gated prediction → `Swing` |
| PLAYER_INPUT | Sent on key-state CHANGE only, BEFORE PlayerCommand/movement within the tick; carries a SPRINT bit (0x40) = raw `keySprint.isDown()` intent (NOT sprint state: false for double-tap sprinters, true for stationary ctrl-holders); the server consumes ONLY the shift bit + stores the record — nothing ever re-derives the sprint flag from it |

## Corrections to prior assumptions (all bytecode-verified)

1. `ServerboundPlayerInputPacket` DOES carry a sprint bit (0x40) — the sprint-dropout round's "no sprint bit, only sneak migrated" claim was wrong. It is key intent, not state; the server ignores it (advancement predicates aside).
2. The one-way latch is UNCONDITIONAL ONLY for double-tap-W sprinters. Key-hold/toggle clients re-engage automatically every tick (`aiStep`'s hold path is level-triggered inside the `canStartSprinting` gate) — the echo was wire-invisible for them unless item-use blocked the re-arm every tick (the owner's continuous blockhitting was exactly that case).
3. A CADENCE-spoofed key-holder's full-charge un-sprint is WIRE-SILENT (same-tick re-arm precedes the diff sender) — the latch plan's bucket "spoofed modern clients send STOP/START each click → w-tap required" is wrong for key-holders. The S1 re-arm rule (surviving `clientSprinting`) handles them correctly regardless.
4. The legacy ≥0.8 forward-impulse sprint-start gate is GONE (`hasEnoughImpulseToStartSprinting` no longer exists; the test is normalized `moveVector.y > 1e-5`). Sneak-forward/diagonal starts are no longer blocked.
5. The double-tap window is a live option now (`options.sprintWindow`, default 7, range 0–10) — not a hardcoded 7 ticks.

## Shipped-fix validation (v2.5.0 latch fixes) — see the review addendum in this doc's history

S1 (echo removal + wire re-arm from surviving `clientSprinting`) is CONFIRMED against ground truth: killing the echo provably prevents the client flag from dropping at spam cadence; `shouldStopRunSprinting` has NO item-use term, so a never-echoed block-hitting client keeps sprinting and sends no STOP — the wire re-arm mirrors its true state. S2's trigger fix matches the real gesture (on-victim blockhits ride `INTERACT`/`INTERACT_AT` → the entity-interact leg; air blockhits ride `USE_ITEM` → the born-cancelled interact leg — exactly the two handlers S2 covers). PLAYER_INPUT's sprint bit is a valid future re-arm CORROBORATOR (absence = unknown; never a verdict source). Double-tap sprinters requiring a genuine re-gesture after a true un-sprint is era-authentic w-tap behavior, not a defect.

**Addendum (2026-07-10, the 2.5.1 one-engagement contract).** S1(b) — the auto re-arm from a surviving `clientSprinting` — is SUPERSEDED. The owner ruled that every held-W hit arming as a sprint knock is a bug, not the era cadence: the era server consumed the flag INSIDE every bonus attack and re-armed only on a client START (a no-w-tap double flew 7.2 blocks, a w-tap double 11.4), and a modern client at spam cadence sends exactly ONE START per engagement (its local flag never drops), so one engagement earns one sprint knock. `SprintWire` now CONSUMES the engagement at the wire clear — `clearedAt` opens a spend latch that BLOCKS `reconcile` from re-adopting the stale-high server flag — and re-arms only on a client-expressed re-gesture (a wire STOP→START, or the `SwordBlockingUnit` block re-arm). S1(a) (the echo kill — dropping the deferred `setSprinting(false)` so the client flag never falls at spam cadence) STANDS unchanged. Every "S1(b)" / auto-re-arm reference in the Mental-implication lines throughout this doc must be read under this supersession: the surviving-`clientSprinting` / PLAYER_INPUT signal remains a valid re-arm CORROBORATOR (the block-hit gates) but no longer drives an automatic per-tick re-engage. The empirical claims below are unchanged — only the S1(b) Mental implication is retired.

---

## Extractor map: `client-sprint-fsm`

1.21.11 local sprint = shared-flag bit 3 (Entity.isSprinting cgk.cA = getSharedFlag(3), setSprinting cgk.i(Z) = setSharedFlag(3,·)). Exactly three writer families exist client-side: LocalPlayer.aiStep (hnh.d_, 4 sites), Player.causeExtraKnockback (ddm.a(cgk,F,ftm), client attack prediction), and server metadata adoption (hig.a(agp) writes any entity's SynchedEntityData, self included; no sprint hook in onSyncedDataUpdated). The wire is a pure per-tick diff: LocalPlayer.tick (hnh.g) runs super.tick (aiStep inside) FIRST, then sendPosition (Q) whose first instruction is sendIsSprintingIfNeeded (R): if isSprinting() != wasSprinting (cX) send ServerboundPlayerCommandPacket START (ajj$a.b) / STOP (ajj$a.c) and update cX. R is the ONLY sprint-packet emitter in the entire client jar (full-jar constant-pool scan). Rise is LEVEL-triggered: every tick, canStartSprinting() && keyPresses.sprint() → setSprinting(true); keySprint is a ToggleKeyMapping (hold mode = raw isDown; toggle mode = latch flipped on press, release ignored). Double-tap path: rising forward edge (pre-input.tick() hasForwardImpulse sample vs post-tick canStartSprinting) with sprintTriggerTime>0; first edge arms timer = options.sprintWindow (NEW option, default 7, 0–10); shift/backward/slow-item-use zero it. canStartSprinting = !sprinting && hasForwardImpulse (moveVector.y>1e-5 — the legacy 0.8 impulse gate is GONE) && !isSlowDueToUsingItem (isUsingItem && !USE_EFFECTS.canSprint; component default canSprint=false) && isSprintingPossible (= !BLINDNESS && (foodLevel>6 || mayfly, or vehicleCanSprint when riding) && !isInShallowWater) && fall-fly/moving-slowly underwater clauses. Fall per tick while sprinting: lost forward impulse, blindness, food≤6, shallow water, non-minor horizontal collision (swim variant analogous). ITEM USE NEVER STOPS an ongoing sprint — it only blocks STARTING one. The only vanilla in-attack clear is causeExtraKnockback, gated by kb strength>0 (sprint && charge>0.9, or KB enchant) — absent at spam cadence, exactly as the latch plan assumed. After an external (adopted) un-sprint: re-engage is automatic next aiStep for hold-key and toggle players once canStartSprinting passes — NO fresh key edge required; if that happens before R(), ZERO packets are emitted; if blocked ≥1 tick (item in use), exactly one STOP ships and a genuine START returns the moment the gate reopens. Double-tap sprinters holding W never see a forward rising edge → never auto-re-engage → they are the true one-way-latch victims. Mental: the observed lone STOP is an echo-confirm, not intent; killing the echo (S1a) provably prevents the client flag from ever dropping at spam cadence.

## Extractor map: `player-input-packet`

1.21.11 wire truth (all from disassembled bytecode of client-1.21.11.jar + paper-1.21.11.jar): ServerboundPlayerInputPacket (ajk) wraps the Input record (ddk: forward/backward/left/right/jump/shift/SPRINT) and its codec (ddk$1) packs ONE byte — sprint is bit 0x40. The packet therefore DOES carry a sprint bit; the prior "no sprint bit" claim is wrong for this version. The bit's SOURCE is KeyboardInput.tick (hng.a): keyPresses is rebuilt every client tick purely from Options key mappings — sprint = keySprint.isDown(), where keySprint is a ToggleKeyMapping ("key.sprint", key 341). It is KEY INTENT, not sprint state: double-tap-forward sprinting never sets it; holding/toggling ctrl while sneaking, standing, or block-hitting sets it anyway; server entity-data echoes cannot touch it (rebuilt from keys only). SEND SITE: LocalPlayer.tick (hnh.g) diffs keyPresses against lastSentInput (da) and sends ONLY on change, before sendPosition/sendIsSprintingIfNeeded — so within a tick PLAYER_INPUT precedes PLAYER_COMMAND and movement. sendIsSprintingIfNeeded (hnh.R) is the confirmed edge-trigger: isSprinting (cA) vs wasSprinting (cX), START_SPRINTING/STOP_SPRINTING only on change. SERVER: handlePlayerInput consumes ONLY the shift bit (PlayerToggleSneakEvent → setShiftKeyDown) plus a diff-gated PlayerInputEvent, then stores the whole Input via setLastClientInput. It never invokes Input.sprint(). Jar-wide, getLastClientInput's consumers are: advancement PlayerPredicate/InputPredicate (only vanilla reader of the stored sprint bit), CraftPlayer.getCurrentInput / getSideways/ForwardsMovement (movement bits only), and ServerPlayer.getLastClientMoveIntent (movement bits only; minecart behaviors). NOTHING re-derives the sprint flag from stored input, ever. setSprinting caller set on this exact jar for players: handlePlayerCommand (edge, behind cancellable PlayerToggleSprintEvent) and Player.causeExtraKnockback (clear-on-attack, Paper-config-guarded) — confirming the 26.1.2 scan; other references are mob AI (Cat/Ocelot on themselves), the LivingEntity attribute-modifier override, and the Bukkit CraftPlayer API. MENTAL IMPLICATION: PLAYER_INPUT's sprint bit is exactly the "surviving client belief" S1(b) wants — the echo-induced STOP_SPRINTING latch never changes the key bit, so the last-received PLAYER_INPUT keeps saying sprint=true for a held/toggled key. It is a valid RE-ARM/intent corroborator for SprintWire, but NOT a verdict source (false for double-tap sprinters, true for non-sprinting ctrl-holders), and it is change-driven with no periodic refresh — absence must read as unknown.

## Extractor map: `echo-adoption`

ECHO PATH (Paper 1.21.11, mojang-mapped, javap of run/1.21.11/versions/1.21.11/paper-1.21.11.jar): CraftPlayer.setSprinting -> ServerPlayer(LivingEntity).setSprinting -> Entity.setSprinting -> setSharedFlag(3,b) -> SynchedEntityData.set (dirties ONLY when value differs). Next tracker tick, ServerEntity.sendChanges -> sendDirtyEntityData -> packDirty -> ClientboundSetEntityDataPacket -> Synchronizer.sendToTrackingPlayersAndSelf; ChunkMap$TrackedEntity's impl self-sends UNCONDITIONALLY whenever entity instanceof ServerPlayer. The echo to the attacker's own client is real on 1.21.11 — the latch plan's load-bearing assumption is confirmed on the deployed version, no longer 26.1.2 inference.

ADOPTION (official client 1.21.11, obf names cited in findings): handleSetEntityData does level.getEntity(id) -> entityData.assignValues with NO local-player exclusion, and the LocalPlayer IS in the id lookup. There is NO separate local sprint field: Entity.isSprinting()==getSharedFlag(3) reads the same SynchedEntityData byte assignValues overwrites, and client-side setSprinting writes it. Adoption is total and silent — onSyncedDataUpdated for SHARED_FLAGS only handles fall-flying; sprintTriggerTime, wasSprinting, and key/toggle state are untouched.

DIFF SENDER: LocalPlayer.tick -> super.tick(aiStep) THEN sendPosition -> sendIsSprintingIfNeeded: if isSprinting()!=wasSprinting send ONE START/STOP_SPRINTING, latch wasSprinting. So after adoption the wire outcome is decided by the ONE aiStep that runs before the next diff.

RE-ENGAGE (the correction): inside aiStep, gated by canStartSprinting() (= !isSprinting && moveVector.y>1e-5 && isSprintingPossible(food/mobility) && !isSlowDueToUsingItem), TWO paths: (1) hold-path — if input.keyPresses.sprint() -> setSprinting(true) EVERY tick; (2) double-tap — only on a rising edge of forward impulse within the options.sprintWindow() window. Therefore: a ctrl-hold/toggle-sprint client re-adopts sprint=true at the very next aiStep unless item-use blocks it — often before any STOP ships, making the echo wire-invisible; "no START ever returns" holds only while canStartSprinting stays false every tick (block-hitting: isUsingItem && !USE_EFFECTS.canSprint). A W-double-tap client mid-hold has no rising edge — it latches until a full release+double-tap gesture. shouldStopRunSprinting does NOT check item-use, so absent the echo a block-hitting client never un-sprints on its own — S1 (kill the echo) reproduces vanilla-client truth; S1(b)'s auto re-arm mirrors the hold-path client exactly, and ctrl-holders emit a genuine rising-edge START after item-use ends.

## Extractor map: `blockhit-item-use`

1.21.11 blockhit cycle (sprinting, sword bearing BLOCKS_ATTACKS, no USE_EFFECTS override), all from disassembled client bytecode. Tick order inside Minecraft.tick (gfj.x): handleKeybinds (gfj.by, offset 373) runs BEFORE ClientLevel.tickEntities (hif.g, offset 436) where LocalPlayer.tick flushes ServerboundPlayerInput (on keypress diff) -> PlayerCommand START/STOP_SPRINTING (sendIsSprintingIfNeeded R(), called first inside sendPosition Q()) -> MovePlayer.

RMB press: startUseItem (gfj.bv). Crosshair on victim in entity-interaction range: ServerboundInteractPacket(INTERACT_AT) + ServerboundInteractPacket(INTERACT) (both locally PASS for a player target) then FALLS THROUGH to MultiPlayerGameMode.useItem -> ServerboundUseItemPacket(hand,seq,yRot,xRot); Item.use sees BLOCKS_ATTACKS (ki.M) -> player.startUsingItem, returns CONSUME (SwingSource NONE) -> NO swing packet. Aim on air: USE_ITEM only. NO sprint edge: startUsingItem never touches sprint; shouldStopRunSprinting (hnh.V) has no item-use term (only isSprintingPossible + raw-key hasForwardImpulse + collision), so the client KEEPS sprinting while blocking. Slowdown is input-side only: modifyInput scales by UseEffects.speedMultiplier (DEFAULT=new UseEffects(false,true,0.2f)) when isUsingItem && !passenger; aiStep zeroes sprintTriggerTime every using tick.

LMB while holding block: handleKeybinds' isUsingItem branch DRAINS keyAttack.consumeClick() without calling startAttack; continueAttack early-returns on isUsingItem. The classic attack-during-block gesture emits NOTHING — no ATTACK, no swing. Attacking requires a tick that begins with isUsingItem=false.

RMB release: ServerboundPlayerActionPacket(RELEASE_USE_ITEM, BlockPos.ZERO, DOWN) + local stopUsingItem. No sprint edge.

LMB after release: ServerboundInteractPacket(ATTACK, isShiftKeyDown) then local Player.attack; sprint-clear lives in causeExtraKnockback guarded by strength>0 where strength=getKnockback(=attack_knockback attr/2, 0 unenchanted)+(0.5 iff isSprinting && attackStrengthScale(0.5)>0.9). Spam cadence -> no local clear, NO STOP. Full charge -> 0.6 self-slow + setSprinting(false) -> one STOP flushes later that tick, AFTER the ATTACK. Then ServerboundSwingPacket. Same-tick re-block use clicks drain AFTER attack clicks: ATTACK/SWING precede USE_ITEM.

Sprint restart: only aiStep, gated by canStartSprinting (hnh.aa) = !isSprinting && rawForward && possible && !isSlowDueToUsingItem && ...; inside it, held/toggled sprint key (keyPresses.sprint()) re-sprints at the FIRST non-using tick (START crosses); double-tap needs a forward-impulse rising edge (never while W held) and its window is zeroed during use — the absolute latch. Server echoes: handleSetEntityData assigns synched data to ANY entity including self; isSprinting()=sharedFlag(3), so an echoed clear is adopted and R() confirms with exactly one STOP.

## Extractor map: `attack-unsprint-gate`

1.21.11 client attack chain (all javap-verified): Minecraft.tick(x) → handleKeybinds(by, offset 373) → startAttack(bu) → MultiPlayerGameMode.attack(hio.a): (1) sends ServerboundInteractPacket.createAttackPacket(target, isShiftKeyDown) IMMEDIATELY (Connection.send flush=true), (2) THEN runs Player.attack(ddm.e), (3) then resetAttackStrengthTicker(hf, both tickers→0). Player.attack: charge=getAttackStrengthScale(0.5f)=clamp((ticker+0.5)/(20/ATTACK_SPEED),0,1); damage always scales smoothly (p()=0.2+0.8·charge²; ench bonus ×charge — no threshold). strong = charge > 0.9f (strict, literal 0.9f). strong gates THREE attack effects: sprint-kb bonus flag = isSprinting() && strong (plays PLAYER_ATTACK_KNOCKBACK, adds +0.5f kb), crit = strong && canCriticalAttack (×1.5), sweep = isSweepAttack(strong,…). The un-sprint and ×0.6 live in causeExtraKnockback(target, kb, capturedDelta), executed only if hurtOrSimulate succeeded AND kb>0: victim knockback, then attacker setDeltaMovement(delta.multiply(0.6,1.0,0.6)) and setSprinting(false). Client-side kb = LivingEntity.getKnockback = ATTACK_KNOCKBACK attr/2 (default 0.0; Knockback enchant applies only in the ServerLevel branch) + 0.5 sprint bonus ⇒ on the client, un-sprint/self-slow fire IFF sprinting && charge>0.9, and only vs targets whose hurtClient returns true — RemotePlayer returns true; Entity returns false and LivingEntity does NOT override, so vs mobs the client never predicts anything. Packet after the predicted setSprinting(false): NONE directly — sprint crosses only via the diff sender sendIsSprintingIfNeeded (R: isSprinting vs wasSprinting → PlayerCommand START/STOP), called from sendPosition inside LocalPlayer.tick, which runs in tickEntities (offset 436) AFTER handleKeybinds in the SAME Minecraft.tick. Crucially aiStep runs BEFORE the diff: canStartSprinting (!sprinting && hasForwardImpulse && sprintPossible && !isSlowDueToUsingItem && …) + sprint key held → setSprinting(true) same tick → the diff sees no change → NO STOP crosses for a forward-moving key-holder not using an item. STOP crosses (same tick as INTERACT, later flush) only when re-arm is blocked — block-hitting (isSlowDueToUsingItem) foremost. With CADENCE attack_speed=1024: delay=20/1024≈0.02 ticks → charge=1.0 every click → full era per-click contract (0.6 self-slow, kb bonus, sprint sounds) restored client-side, wire-silent while sprint held. Paper 1.21.11 server: handlePlayerInput never calls setSprinting; only handlePlayerCommand does.

---

# All 60 verified claims

### `wire-edge-sole-sender` (client-sprint-fsm) — confirmed-from-bytecode, verified

**Claim:** On 1.21.11 the ONLY code in the entire client that sends ServerboundPlayerCommandPacket START_SPRINTING/STOP_SPRINTING is LocalPlayer.sendIsSprintingIfNeeded, a stateful diff of isSprinting() against the wasSprinting field, run once per tick.

**Evidence:** hnh.R() (LocalPlayer.sendIsSprintingIfNeeded): `cA() (Entity.isSprinting); if_icmpeq wasSprinting(cX) → return; flag? ajj$a.b (START_SPRINTING) : ajj$a.c (STOP_SPRINTING); new ajj(this, action); connection.send; cX=flag`. Full-jar scan for classes whose constant pool references ajj (ServerboundPlayerCommandPacket): only hnh (LocalPlayer), gro (InBedChatScreen, STOP_SLEEPING only), aia (GameProtocols registry), ayi (ServerGamePacketListenerImpl, server side), ajj itself. Within hnh, ajj$a.b/.c appear only inside R() (disasm lines 625/627); other actions used are d (sendRidingJump), f (sendOpenInventory), g (START_FALL_FLYING in aiStep).

**Mental implication:** SprintWire's feed is complete: there is no second client pathway that could re-emit START; whatever R()'s diff says is the whole modern sprint wire.

### `tick-order-aistep-before-diff` (client-sprint-fsm) — confirmed-from-bytecode, verified

**Claim:** Within one client tick the flag-mutating logic (aiStep) runs BEFORE the wire diff (sendIsSprintingIfNeeded), so any clear+re-engage completing inside one tick emits ZERO packets; the diff is also skipped entirely while riding a vehicle the client does not control.

**Evidence:** hnh.g() (LocalPlayer.tick): instr 19 `invokespecial hne.g` (super.tick; LivingEntity.tick invokes d_/aiStep — chl disasm line 7277 `invokevirtual d_:()V`); then instr 71 `cq() (isPassenger)`: if false → instr 143 Q() (sendPosition) whose instruction 0 is `invokevirtual R()`; if passenger → R() called at instr 137 only inside `if (getRootVehicle() du() != this && rootVehicle.isLocalInstanceAuthoritative() dv())`.

**Mental implication:** An echo-adopt that the client re-engages from within the next aiStep is INVISIBLE on the wire (no STOP/START pair); Mental only ever sees the STOP when re-engagement was blocked for at least the tick the diff ran — exactly the block-hitting case in the owner's capture.

### `rise-conditions-level-triggered-key` (client-sprint-fsm) — confirmed-from-bytecode, verified

**Claim:** The sprint-key path is level-triggered, not edge-triggered: every tick that canStartSprinting() holds and the sprint KeyMapping reads down, aiStep calls setSprinting(true); the double-tap path fires on a rising edge of forward impulse while a sprintTriggerTime window (armed by the previous rising edge to options.sprintWindow, default 7, range 0-10) is still positive.

**Evidence:** hnh.d_ instr 376-443: `aa() (canStartSprinting) ifeq skip; if (!local3 /*hnf.c() hasForwardImpulse sampled at instr 75-82 BEFORE input.tick() at 154*/) { if (sprintTriggerTime e>0) i(true) (setSprinting) else e = options.au() (sprintWindow OptionInstance).get() }; if (keyPresses.g() (Input.sprint)) i(true)`. gfo (Options) ctor instr 2196-2237: sprintWindow = new OptionInstance("options.sprintWindow", IntRange(0,10), default 7). Timer decrement at d_ instr 0-14; timer zeroed at instr 340-375 by shift (Input.f), (isSlowDueToUsingItem T() && !isPassenger cq()), or backward (Input.b).

**Mental implication:** START from a modern client encodes 'the key is down and the gates opened this tick', not a discrete player gesture; SprintWire freshness semantics must not assume a deliberate w-tap behind every START.

### `canstartsprinting-exact-conjunction` (client-sprint-fsm) — confirmed-from-bytecode, verified

**Claim:** canStartSprinting() on 1.21.11 is exactly: !isSprinting && input.hasForwardImpulse && isSprintingPossible(abilities.flying) && !isSlowDueToUsingItem && (!isFallFlying || isUnderWater) && (!isMovingSlowly || isUnderWater); food, blindness, vehicle and shallow-water live inside isSprintingPossible = !isMobilityRestricted[=hasEffect(BLINDNESS)] && (isPassenger ? vehicle.canSprint&&vehicle.isLocalInstanceAuthoritative : foodLevel>6||mayfly) && (flyingArg || !isInShallowWater).

**Evidence:** hnh.aa(): `cA ifne false; hnf.c() ifeq false; A(gL().b /*Abilities.flying*/) ifeq false; T() ifne false; gj() (isFallFlying) → require bC() (isUnderWater); E() (isMovingSlowly) → require bC()`. hnh.A(Z) (isSprintingPossible): `hl() (Player.isMobilityRestricted = d(cfo.o) hasEffect(BLINDNESS)) ifne false; cq()? d(dz()) (vehicleCanSprint = cgk.ef canSprint && cgk.dv isLocalInstanceAuthoritative) : gV() (hasEnoughFoodToDoExhaustiveManoeuvres = dhe.b() foodLevel>6.0f || ddi.c mayfly); if (!arg) require !bD() (isInShallowWater)`.

**Mental implication:** The plan's 'item-use blocks STARTING a sprint' is confirmed at instruction level, and the full modern gate list (blindness, food>6, shallow water, non-authoritative vehicle) defines every condition under which the S1 latch-fix's expected client re-engage can legitimately not happen.

### `item-use-gate-is-useeffects-cansprint` (client-sprint-fsm) — confirmed-from-bytecode, verified

**Claim:** The item-use sprint gate is the data-driven USE_EFFECTS component: isSlowDueToUsingItem() = isUsingItem() && !useItem.getOrDefault(DataComponents.USE_EFFECTS, DEFAULT).canSprint(), with DEFAULT = UseEffects(canSprint=false, interactVibrations=true, speedMultiplier=0.2f) — so by default ANY active item use (eating, sword-blocking) blocks sprint STARTING, but a server/datapack could set canSprint=true per item.

**Evidence:** hnh.T(): `fZ() (isUsingItem) && !useItem(bT).a(ki.g /*DataComponents.USE_EFFECTS*/, dph.a /*UseEffects.DEFAULT*/).a() (canSprint)`. dph <clinit>: `new dph(iconst_0, iconst_1, 0.2f)` → DEFAULT canSprint=false. hnh.U() (itemUseSpeedMultiplier) returns the same component's c() (speedMultiplier).

**Mental implication:** Block-hitting suppresses the modern client's re-engage exactly while the use is active and by component default — Mental could in principle neutralize the latch for blockhit by giving swords USE_EFFECTS canSprint=true, but the S1 echo-kill makes that unnecessary.

### `impulse-threshold-08-gone` (client-sprint-fsm) — contradicts-prior-assumption, verified

**Claim:** hasEnoughImpulseToStartSprinting no longer exists on 1.21.11; the sprint-start impulse test is ClientInput.hasForwardImpulse() = normalized moveVector.y > 1.0E-5, so the legacy >=0.8 forward-impulse requirement (which blocked sneak-forward and diagonal-dominant starts) is gone from the client.

**Evidence:** hnh mapping block lists no hasEnoughImpulseToStartSprinting; hnf.c() (ClientInput.hasForwardImpulse): `moveVector(ftl b).k (Vec2.y) fcmpl 1.0E-5f`. hng.a() (KeyboardInput.tick) builds keyPresses straight from Options key mappings s/u/t/v/w/x/y (keyUp/keyDown/keyLeft/keyRight/keyJump/keyShift/keySprint).isDown() and sets moveVector = new Vec2(leftImpulse, forwardImpulse).normalized() — impulses are raw ±1, no sneak/use scaling (scaling happens later in LocalPlayer.modifyInput).

**Mental implication:** Any Mental-side heuristic inherited from legacy clients that assumes sprint requires near-full forward impulse is wrong for modern attackers; crouch/slow modifiers no longer gate the impulse test itself (they gate via isMovingSlowly/isSlowDueToUsingItem instead).

### `stop-conditions-item-use-never-stops` (client-sprint-fsm) — confirmed-from-bytecode, verified

**Claim:** The per-tick sprint STOP logic is: if sprinting, (isSwimming ? shouldStopSwimSprinting : shouldStopRunSprinting) → setSprinting(false), where shouldStopRunSprinting = !isSprintingPossible(flying) || !hasForwardImpulse || (horizontalCollision && !minorHorizontalCollision); STARTING to use an item does NOT stop an ongoing sprint anywhere in the client tick — sprint-blockhitting/sprint-eating keeps the local flag true and sends no STOP.

**Evidence:** hnh.d_ instr 443-484: `cA() ifeq skip; cB() (isSwimming)? W() → i(false) : V() → i(false)`. hnh.V() (shouldStopRunSprinting): `!(A(abilities.flying) && hnf.c() && !(ad (Entity.horizontalCollision) && !ag (minorHorizontalCollision)))`. hnh.W() (shouldStopSwimSprinting): `!A(true) || !by() (isInWater) || (!hnf.c() && !aV() (onGround) && !keyPresses.f() (shift))`. No isUsingItem/T() call in either; the only i(Z)V call sites in the whole class are the four aiStep sites (constant #1117). LocalPlayer.startUsingItem (c(cdb)) and gf (stopUsingItem) touch only startedUsingItem df.

**Mental implication:** Absent Mental's echo, an unspoofed modern block-hitter's client flag NEVER drops at spam cadence — so after S1a (echo removed) no STOP arrives and the wire's clientSprinting stays true, which is precisely the surviving-belief signal S1b re-arms from.

### `vanilla-attack-clear-charge-gated` (client-sprint-fsm) — confirmed-from-bytecode, verified

**Claim:** The only attack-side sprint clear on the client is Player.causeExtraKnockback: if knockback strength > 0 it knocks the target, multiplies the attacker's own velocity by (0.6,1,0.6), and calls setSprinting(false); Player.attack computes strength = getKnockback(target,source) + (isSprinting && attackStrengthScale>0.9 ? 0.5 : 0), with no client/server guard, so the client PREDICTS the clear only for charged sprint hits (or KB enchant) — never at spam cadence.

**Evidence:** ddm.a(cgk,F,ftm) (Player.causeExtraKnockback): `fload_2 fcmpl 0 ifle skip; chl.o(DDD) knockback / cgk.i(DDD) push; setDeltaMovement(dN().d(0.6,1.0,0.6)); i(false) (setSprinting)`. ddm.e(cgk) (Player.attack) instr 101-137: local7 = getAttackStrengthScale(0.5f) I(F) > 0.9f; local8 = cA() (isSprinting) && local7; instr 252-276: `b(cgk,cex) (getKnockback) + (local8? 0.5f : 0f)` passed to a(cgk,F,ftm), called after cgk.b(cex,F) (hurt) returns true, no isClientSide branch around it.

**Mental implication:** Confirms the plan's rationale for S1a verbatim: the real vanilla clear is charge-gated and self-predicted, so Mental's per-hit deferred setSprinting(false) echo produces a client-visible state change vanilla never produces at combo cadence.

### `adoption-is-silent-and-echo-confirmed` (client-sprint-fsm) — confirmed-from-bytecode, verified

**Claim:** Server metadata reaches the local player's sprint flag with no interception: handleSetEntityData assigns packed values to whatever entity matches the id (self included), isSprinting() reads the synched byte directly, and LocalPlayer.onSyncedDataUpdated special-cases only the item-use bits and the fall-flying bit — so an adopted sprint=false silently flips isSprinting() and the next diff emits one STOP; symmetrically, a server-pushed sprint=true would make the client emit a genuine START.

**Evidence:** hig.a(agp) (ClientPacketListener.handleSetEntityData): `ensureRunningOnSameThread; level.getEntity(packet.id()); if nonnull entity.getEntityData(aD()).assignValues(packet.packedItems())` — no self-exclusion. cgk.cA: `i(3) getSharedFlag` reading entityData byte az/aA (DATA_SHARED_FLAGS_ID). hnh.a(alw) (LocalPlayer.onSyncedDataUpdated): handles bk (DATA_LIVING_ENTITY_FLAGS, bit1 → start/stopUsingItem) and aA only for `gj() (isFallFlying) && !wasFallFlying → elytra sound`; no sprint-bit handling.

**Mental implication:** The lone STOP in the owner's journal is a protocol echo-confirm of Mental's own metadata, not player intent — and metadata is a two-way lever: Mental could flip a modern client's sprint flag back ON and receive a confirming START, though S1 chooses not to rely on this.

### `reengage-no-key-edge-needed` (client-sprint-fsm) — confirmed-from-bytecode, verified

**Claim:** After an external un-sprint, re-engagement requires NO fresh key edge for hold-key or toggle-sprint players: the level-triggered `if (keyPresses.sprint()) setSprinting(true)` fires on the very next aiStep once canStartSprinting() passes (ToggleKeyMapping's latch is untouched by the adoption — setDown flips it only on physical press when toggleSprint is on, release is ignored); double-tap players holding W never re-engage automatically because no forward rising edge occurs and sprintTriggerTime is 0.

**Evidence:** hnh.d_ instr 425-440 (`keyPresses.g() → i(true)`) gated only by aa() at 376, which requires !cA() — true immediately after adoption. gfw.a(Z) (ToggleKeyMapping.setDown): `if (needsToggle.getAsBoolean()) { if (press) super.setDown(!isDown()) } else super.setDown(arg)`. gfo ctor instr 2433-2464: keySprint = new gfw("key.sprint", 341, MOVEMENT, toggleSprint(cF)::get, true). Double-tap: rise needs `!local3` (pre-tick hasForwardImpulse false) — impossible while W held; timer e already 0.

**Mental implication:** The plan's step-4 'no START ever returns' is conditional, not absolute: it holds only while canStartSprinting stays false (continuous item use) or for double-tap sprinters; a hold-ctrl/toggle attacker with gaps between block-hits WILL emit a genuine START — S1b's era-gap re-arm must coexist with these organic STARTs (it does: they arrive as real STARTs and arm freshness).

### `blindness-still-gates-both-edges` (client-sprint-fsm) — confirmed-from-bytecode, verified

**Claim:** Blindness still suppresses sprint on 1.21.11, relocated into Player.isMobilityRestricted() = hasEffect(BLINDNESS), which isSprintingPossible checks — and because shouldStopRunSprinting/shouldStopSwimSprinting also call isSprintingPossible, blindness now actively STOPS an ongoing sprint each tick, not just blocks starting.

**Evidence:** ddm.hl(): `d(cfo.o) (hasEffect(MobEffects.BLINDNESS))`. Mapping: MobEffects BLINDNESS -> o. hnh.A(Z) instr 0-4 requires !hl(); hnh.V()/W() both call A(...). LocalPlayer itself contains no MobEffects reference besides cfo.h (JUMP_BOOST) in updateAutoJump.

**Mental implication:** No Mental change needed, but any future era comparison should note the modern client hard-stops sprint under blindness where the 1.8 client merely blocked starts.

### `no-serversprintstate-field` (client-sprint-fsm) — confirmed-from-bytecode, verified

**Claim:** There is no `serverSprintState` field on 1.21.11 LocalPlayer; the last-transmitted-state tracker is the boolean field wasSprinting (obf cX), and no other sprint bookkeeping field exists on the class.

**Evidence:** hnh mapping block field list: `boolean wasSprinting -> cX`, `int sprintTriggerTime -> e` are the only sprint-related fields; R() reads/writes cX exclusively.

**Mental implication:** The latch-plan's vocabulary should reference wasSprinting; the client keeps no memory of what the SERVER believes beyond the shared flag itself, so adopted state and self-set state are indistinguishable to the diff.

### `packet-application-scheduling-unverified` (client-sprint-fsm) — unresolvable-from-binaries, verified

**Claim:** The exact scheduling of inbound packet application relative to LocalPlayer.tick within a client frame (i.e., whether an adopted sprint clear can land between aiStep and sendIsSprintingIfNeeded of the SAME tick, which would force a STOP even for a hold-key player) was not established from the binaries.

**Evidence:** hig.a(agp) instr 0-9 shows PacketUtils.ensureRunningOnSameThread rescheduling onto the Minecraft (gfj) executor, but Minecraft.runTick/tick ordering of the task queue versus level/player ticking was not disassembled in this pass; only the intra-LocalPlayer.tick ordering (aiStep at instr 19 before R at 137/143) is bytecode-proven.

**Mental implication:** For S1 verification, expect MOSTLY zero-packet or single-STOP outcomes per echo depending on arrival timing; a live capture, not further disassembly of hnh, is the right tool to pin the frame-boundary case.

**Verifier correction:** Claim stands. Minor note: the cited "aiStep at instr 19" is literally invokespecial hne.g() = super.tick() (AbstractClientPlayer.tick); aiStep (d_) executes within that super-tick chain, so the aiStep-before-R ordering is still bytecode-supported. All other citations (PacketUtils.ensureRunningOnSameThread reschedule onto Minecraft's PacketProcessor at hig.a(agp) instr 0-9; R at instr 137, Q-branch at 143) are exact.

### `F1` (player-input-packet) — contradicts-prior-assumption, verified

**Claim:** ServerboundPlayerInputPacket on 1.21.11 DOES carry a sprint bit: it wraps the 7-field Input record and the wire codec packs sprint as flag 0x40 in a single byte.

**Evidence:** Client jar: ajk = net.minecraft.network.protocol.game.ServerboundPlayerInputPacket has one field `Input input -> b` (ddk = net.minecraft.world.entity.player.Input: forward->c, backward->d, left->e, right->f, jump->g, shift->h, sprint->i, FLAG_SPRINT->p). Codec ddk$1.a(wx,ddk) [Input$1.encode] ORs iconst_1/2/4, bipush 8/16/32, and `bipush 64` gated on `invokevirtual ddk.g:()Z` [Input.sprint()], then writeByte via wx.l(I); decode masks `bipush 64; iand` into the 7th ctor arg of ddk.<init>(ZZZZZZZ).

**Mental implication:** The prior agent's 'no sprint bit' claim is false for 1.21.11 (plausibly a read of the pre-1.21.2 vehicle-steer packet shape, which is not assertable from these binaries). PLAYER_INPUT is a real candidate signal for SprintWire on modern clients.

### `F2` (player-input-packet) — confirmed-from-bytecode, verified

**Claim:** The vanilla client sends PLAYER_INPUT on CHANGE ONLY (at most once per tick), by diffing current keyPresses against a lastSentInput field.

**Evidence:** LocalPlayer.tick() (hnh.g()): after `invokespecial hne.g()` (super.tick), `getfield da [lastSentInput]; getfield c.a [input.keyPresses]; invokevirtual ddk.equals; ifne 71` — only when unequal: `new ajk; <init>(Lddk;); invokevirtual hig.b [connection.send]; putfield da`. lastSentInput is seeded from a constructor parameter (putfield #190 at <init> offset 96), so there is no periodic refresh and no guaranteed initial send.

**Mental implication:** SprintWire must treat PLAYER_INPUT as a change-stream: absence means 'no change since last', and a plugin attaching mid-session may have no sample at all until any of the 7 bits flips — absence must be modeled as unknown, never as sprint=false. Project memory's 'boxers stream it' is about SimpleBoxer's own client brain, not vanilla cadence.

### `F3` (player-input-packet) — confirmed-from-bytecode, verified

**Claim:** The sprint bit is raw sprint-KEY intent (keySprint.isDown()), rebuilt every client tick purely from key mappings — it is NOT the client's derived sprinting state.

**Evidence:** KeyboardInput.tick() (hng.a()) constructs `new ddk(gfo.s.f(), gfo.u.f(), gfo.t.f(), gfo.v.f(), gfo.w.f(), gfo.x.f(), gfo.y.f())` where mappings pin gfo.s=keyUp, u=keyDown, t=keyLeft, v=keyRight, w=keyJump, x=keyShift, y=keySprint and gfh.f()=KeyMapping.isDown(). No read of isSprinting or any entity flag appears in hng.a.

**Mental implication:** PLAYER_INPUT sprint=true does not mean sprinting (ctrl held while sneaking/standing/using-item still sets it), and sprint=false does not mean not sprinting (double-tap sprint never touches the key). It is an intent channel, not a state channel — usable to corroborate re-arm, unusable as a bonus verdict source.

### `F4` (player-input-packet) — confirmed-from-bytecode, verified

**Claim:** keySprint is a ToggleKeyMapping: with toggle-sprint enabled, one press latches isDown()=true until the next press; the toggle state is only ever changed by key press paths, never by server echoes.

**Evidence:** Options.<init> (gfo): `new gfw [ToggleKeyMapping]; ldc "key.sprint"; sipush 341; ... putfield y`. gfw.a(Z) [setDown]: when the BooleanSupplier (toggle option) is true and down=true it flips via `gfh.a(!f())`; gfw has no code path reading entity/shared flags.

**Mental implication:** For toggle-sprint users the bit behaves like a persistent intent latch — even better for the S1(b) 'surviving client belief' read; and crucially, Mental's deferred setSprinting(false) echo cannot corrupt this bit, because keyPresses regeneration touches only key mappings.

### `F5` (player-input-packet) — confirmed-from-bytecode, verified

**Claim:** Within one client tick the send order is: PLAYER_INPUT (if changed) BEFORE PLAYER_COMMAND START/STOP_SPRINTING and BEFORE movement packets.

**Evidence:** hnh.g() [LocalPlayer.tick]: the ajk send sits at offsets 22-57; the non-passenger branch then calls Q() [sendPosition, offset 144] whose first instruction is `invokevirtual R()` [sendIsSprintingIfNeeded] followed by the position/rotation packet block; the passenger branch calls R() at offset 137 after paddle/vehicle sends.

**Mental implication:** An arrival-ordered SprintWire can rely on seeing the key-intent bit no later than (and on a fresh key-press tick, before) the START_SPRINTING edge — safe to use as a pre-context for the same tick's ENTITY_ACTION.

### `F6` (player-input-packet) — confirmed-from-bytecode, verified

**Claim:** START/STOP_SPRINTING is strictly edge-triggered on the client's own isSprinting flag (sent only when it differs from wasSprinting), confirming the latch plan's step 4 for 1.21.11.

**Evidence:** hnh.R() [LocalPlayer.sendIsSprintingIfNeeded]: `invokevirtual cA() [isSprinting]; getfield cX [wasSprinting]; if_icmpeq 48(return)`; on change selects ajj$a.b [START_SPRINTING] or ajj$a.c [STOP_SPRINTING] (enum constants pinned by mappings), sends `new ajj [ServerboundPlayerCommandPacket]`, then `putfield cX`. Called from Q() [sendPosition] head and the passenger branch of tick().

**Mental implication:** The one-way latch mechanics assumed by docs/superpowers/plans/2026-07-10-modern-sprint-latch.md hold verbatim on 1.21.11: after an echo-adopted un-sprint plus one STOP, no START returns while the flag edge never rises.

### `F7` (player-input-packet) — confirmed-from-bytecode, verified

**Claim:** Paper 1.21.11's handlePlayerInput consumes ONLY the shift bit (PlayerToggleSneakEvent → setShiftKeyDown, gated on hasClientLoaded) plus a diff-gated PlayerInputEvent, then stores the whole Input; it never reads the sprint bit and never calls setSprinting.

**Evidence:** net.minecraft.server.network.ServerGamePacketListenerImpl.handlePlayerInput (mojang-mapped Paper jar, run/1.21.11/versions/1.21.11/paper-1.21.11.jar): ensureRunningOnSameThread; input.equals(getLastClientInput) → PlayerInputEvent; Input.shift() compared old-vs-new → PlayerToggleSneakEvent → if uncancelled && hasClientLoaded → ServerPlayer.setShiftKeyDown; setLastClientInput(input); resetLastActionTime; shift+parrot-config → removeEntitiesOnShoulder. The only Input accessor invoked is shift() (offsets 77/84/102/144/158/179); Input.sprint() never appears.

**Mental implication:** The server discards the sprint intent for gameplay: PLAYER_INPUT can never set or clear the server sprint flag, so reading it in Mental adds information the server itself ignores — no double-application risk with vanilla.

**Verifier correction:** Claim as stated is confirmed. Minor evidence-citation slip: the six-offset list "77/84/102/144/158/179" for shift() is wrong at 158 — offset 158 is invokevirtual ServerPlayer.setLastClientInput, not Input.shift(). Actual Input.shift() invocations are the five offsets 77, 84, 102, 144, 179. The substantive assertions all hold: sprint() never appears anywhere in the method (grep exit 1), setSprinting is never called, setShiftKeyDown(input.shift()) is gated on !cancelled && hasClientLoaded (offsets 123/130/147), PlayerInputEvent is diff-gated on Input.equals (ifne at 26), and shift+parrot-config drives removeEntitiesOnShoulder. Input.sprint()/FLAG_SPRINT exist on the type but are unused here. Mental implication stands: the server discards sprint intent from PLAYER_INPUT for this handler's gameplay effects.

### `F8` (player-input-packet) — confirmed-from-bytecode, verified

**Claim:** No per-tick server logic re-derives sprint from the stored client input: every consumer of ServerPlayer.lastClientInput either ignores the sprint bit or is advancement/API surface.

**Evidence:** Field #527 lastClientInput inside ServerPlayer is read only by getLastClientInput, getLastClientMoveIntent (invokes only Input.left/right/forward/backward), and the constructor putfield. Jar-wide binary grep for getLastClientInput hits exactly: PlayerPredicate (calls InputPredicate.matches(Input) — advancement criteria, the only vanilla reader that can see sprint), ServerGamePacketListenerImpl (the diff in F7), CraftPlayer (getCurrentInput Bukkit API; getSidewaysMovement/getForwardsMovement read movement bits only), ServerPlayer itself. getLastClientMoveIntent consumers: New/OldMinecartBehavior only.

**Mental implication:** The stored sprint bit is inert server-side; SprintWire reading PLAYER_INPUT at the rim would be the FIRST gameplay consumer, and Bukkit's Player#getCurrentInput() exposes the very same stored value if a non-packet read is ever preferred (region-thread, last-received semantics).

### `F9` (player-input-packet) — confirmed-from-bytecode, verified

**Claim:** The complete player-relevant setSprinting caller set on Paper 1.21.11 is handlePlayerCommand (START/STOP edge, behind cancellable PlayerToggleSprintEvent) and Player.causeExtraKnockback (clear-on-sprint-attack, skipped when Paper's disableSprintInterruptionOnAttack is set) — the 26.1.2 scan result holds on this exact jar.

**Evidence:** Binary grep over all extracted classes for setSprinting: SGPLI.handlePlayerCommand offsets 181/192 (iconst_1/iconst_0 after the Action tableswitch, preceded by the PlayerToggleSprintEvent cancel-return at 112); Player.causeExtraKnockback offset 135 `iconst_0; invokevirtual setSprinting` guarded by WorldConfiguration$Misc.disableSprintInterruptionOnAttack (ifne 138), in the same method as the 0.6/1.0/0.6 setDeltaMovement self-multiplier. Remaining references: Cat/Ocelot AI (invoked on the mob itself), LivingEntity.setSprinting override (super + MOVEMENT_SPEED SPEED_MODIFIER_SPRINTING attribute), Entity.setSprinting (the declaration), CraftPlayer.setSprinting (Bukkit API delegate), EntityFlagsPredicate$Builder.setSprinting (predicate builder, unrelated).

**Mental implication:** Mental's packet-cancel path bypassing causeExtraKnockback plus the edge-only handler means the server flag genuinely has no recovery path once latched false — the S1 premise (kill the echo, re-arm from client belief) is the correct shape; no hidden vanilla re-setter exists to conflict with it.

**Verifier correction:** Claim confirmed on this exact jar. Minor note: the offsets 181/192 (handlePlayerCommand) and 135 (causeExtraKnockback) point at the invokevirtual setSprinting instruction, not the iconst_1/iconst_0 the claim labels them — those consts sit one offset earlier (180/191/134). Immaterial to every assertion.

### `F10` (player-input-packet) — confirmed-from-bytecode, verified

**Claim:** The echo-induced latch does NOT propagate into PLAYER_INPUT: after the client adopts a server un-sprint and sends STOP_SPRINTING, its keyPresses.sprint stays true while the key is held/toggled, and since the Input record is then UNCHANGED, no new PLAYER_INPUT is sent — the server's stored lastClientInput keeps sprint=true through the entire latch.

**Evidence:** Composition of F2+F3: keyPresses is rebuilt each tick solely from KeyMapping.isDown (hng.a), which no entity-flag write touches; the ajk send in hnh.g fires only on ddk.equals mismatch. A sprint-flag drop changes isSprinting (feeding R()'s STOP) but no field of ddk, so lastSentInput remains equal and no packet ships; Paper stores the last received Input untouched (F7).

**Mental implication:** For the latch fix, the last-received PLAYER_INPUT sprint bit (or CraftPlayer#getCurrentInput().isSprint()) is a durable 'client still wants sprint' witness that survives exactly the scenario that latches clientSprinting false via STOP — a stronger re-arm corroborator than the wire's raw clientSprinting, with the F3 caveat that double-tap sprinters never set it (so it can only ADD re-arms, and must OR with, not replace, the S1(b) clientSprinting branch).

### `F11` (player-input-packet) — unresolvable-from-binaries, verified

**Claim:** Whether 1.21.2–1.21.10 clients/servers share this exact packet shape and send discipline cannot be established from the provided binaries — only 1.21.11 artifacts were examined.

**Evidence:** The task supplied exactly one client jar (client-1.21.11.jar + its official mappings) and one server jar (paper-1.21.11.jar). No other version's bytecode was available to disassemble, and per the no-inference rule no cross-version claim is made from memory.

**Mental implication:** If SprintWire adopts PLAYER_INPUT as an intent source across the modern band, each protocol boundary Mental supports (esp. the 1.21.2 packet-introduction edge and any pre-1.21.2 vehicle-steer shape) needs its own artifact check before the reader is enabled there.

**Verifier correction:** Not refuted; one nuance for precision. The evidence line "the task supplied exactly one client jar and one server jar" is loosely stated on the server side: the run/ tree also contains run/1.21.4/versions/1.21.4/paper-1.21.4.jar — a single point INSIDE the 1.21.2–1.21.10 band (outside the task's 1.21.11 server pointer). This does not overturn the claim: (a) send discipline is client-emitted and there is no 1.21.4 (or any sub-1.21.11) client jar, so it stays unestablishable; (b) one 1.21.4 server point does not cover the eight other versions (1.21.2/3/5/6/7/8/9/10) of the band. The core assertion — cross-version packet shape AND send discipline for 1.21.2–1.21.10 cannot be established from the provided binaries — is accurate.

### `srv-setSprinting-sharedflag` (echo-adoption) — confirmed-from-bytecode, verified

**Claim:** On Paper 1.21.11, Player.setSprinting(false) is a pure shared-flag SynchedEntityData write: CraftPlayer.setSprinting -> ServerPlayer.setSprinting (resolves to LivingEntity.setSprinting) -> Entity.setSprinting -> setSharedFlag(3, b) -> entityData.set(DATA_SHARED_FLAGS_ID, byte with bit 3 masked).

**Evidence:** javap of paper-1.21.11.jar (mojang-mapped): org.bukkit.craftbukkit.entity.CraftPlayer.setSprinting = getHandle() + invokevirtual ServerPlayer.setSprinting:(Z)V; net.minecraft.world.entity.LivingEntity.setSprinting = invokespecial Entity.setSprinting then removeModifier/addTransientModifier of SPEED_MODIFIER_SPRINTING on Attributes.MOVEMENT_SPEED; Entity.setSprinting = iconst_3 + invokevirtual setSharedFlag:(IZ)V; Entity.setSharedFlag reads entityData.get(DATA_SHARED_FLAGS_ID) byte, ors/ands bit (1<<flag), entityData.set(...).

**Mental implication:** The deferred KnockbackUnit clear was never a private server-side toggle — every setSprinting(false) is a synched-data mutation destined for the tracker, so removing the obligation (S1a) is the only way to keep the flag off the wire.

### `srv-dirty-only-on-change` (echo-adoption) — confirmed-from-bytecode, verified

**Claim:** SynchedEntityData.set marks the item and the container dirty ONLY when the new value differs from the stored one (ObjectUtils.notEqual gate); a redundant setSprinting(false) when the flag is already false produces no packet.

**Evidence:** javap paper-1.21.11.jar net.minecraft.network.syncher.SynchedEntityData.set(EntityDataAccessor,Object,boolean): offset 7 'iload_3 ifne 23' (force flag), offsets 11-20 invokestatic org/apache/commons/lang3/ObjectUtils.notEqual with DataItem.getValue, 'ifeq 50' (return without write); otherwise DataItem.setValue, holder.onSyncedDataUpdated, DataItem.setDirty(true), isDirty=true.

**Mental implication:** The echo only fired when Mental actually flipped a true flag to false — i.e., exactly after a genuine client START had armed the wire; the latch always bites the sprinting attacker, never idles.

### `srv-echo-self-send` (echo-adoption) — confirmed-from-bytecode, verified

**Claim:** On Paper 1.21.11 the dirty shared-flag flush reaches the player's OWN connection: ServerEntity.sendChanges -> private sendDirtyEntityData -> new ClientboundSetEntityDataPacket(entity.getId(), packDirty()) -> Synchronizer.sendToTrackingPlayersAndSelf, and ChunkMap$TrackedEntity.sendToTrackingPlayersAndSelf self-sends unconditionally when the tracked entity is a ServerPlayer (the ONLY self-inclusion condition is 'instanceof ServerPlayer').

**Evidence:** javap paper-1.21.11.jar: ServerEntity.sendDirtyEntityData offsets 8-44: SynchedEntityData.packDirty, ifnull skip, new ClientboundSetEntityDataPacket, invokeinterface ServerEntity$Synchronizer.sendToTrackingPlayersAndSelf; sendChanges invokes sendDirtyEntityData on all three branches (offsets 297, 469, 1205). ChunkMap$TrackedEntity.sendToTrackingPlayersAndSelf: invokevirtual sendToTrackingPlayers, then 'instanceof ServerPlayer / ifeq 30 / getfield ServerPlayer.connection / invokevirtual ServerGamePacketListenerImpl.send'.

**Mental implication:** The latch plan's core assumption ('sendDirtyEntityData -> sendToTrackingPlayersAndSelf', previously javap'd only on 26.1.2) is now confirmed on the deployed 1.21.11 itself — the echo was real on the owner's capture server, upgrading the S1 diagnosis from inference to bytecode fact.

### `cli-handler-no-self-exclusion` (echo-adoption) — confirmed-from-bytecode, verified

**Claim:** The 1.21.11 client's ClientPacketListener.handleSetEntityData applies incoming entity data to WHATEVER entity the id resolves to, with no branch excluding the local player, and the LocalPlayer is registered in that id lookup.

**Evidence:** Client jar, hig.a(agp) [ClientPacketListener.handleSetEntityData(ClientboundSetEntityDataPacket)]: PacketUtils(abb).ensureRunningOnSameThread, then getfield B:Lhif [ClientLevel level] -> hif.a(I) [ClientLevel.getEntity(int)] -> ifnull skip -> cgk.aD() [Entity.getEntityData] -> ama.a(List) [SynchedEntityData.assignValues]. Separately hig bytecode at the login/respawn handler: gfj.s [Minecraft.player:Lhnh] gets hnh.e(I) [setId] from the packet then 'invokevirtual hif.d:(Lcgk;)V' [ClientLevel.addEntity] — the LocalPlayer is in the lookup.

**Mental implication:** Nothing client-side shields the local player from server entity-data writes; every echoed shared-flag byte lands in the local player's own SynchedEntityData.

### `cli-no-separate-sprint-field` (echo-adoption) — confirmed-from-bytecode, verified

**Claim:** The 1.21.11 client LocalPlayer has NO separate local sprint field: isSprinting() is Entity.getSharedFlag(3) reading the same SynchedEntityData byte that assignValues overwrites, and no class in the LocalPlayer hierarchy overrides isSprinting; client-side setSprinting writes that same byte.

**Evidence:** Mappings: only Entity declares isSprinting (cgk.cA) and only Entity/LivingEntity declare setSprinting (cgk.i(Z)/chl.i(Z)); no isSprinting in ddm/hne/hnh. Client cgk.cA: 'iconst_3; invokevirtual i:(I)Z'; cgk.i(int) [getSharedFlag]: entityData az.a(aA) [get(DATA_SHARED_FLAGS_ID)] byte, (1<<flag)&, ireturn; cgk.b(int,boolean) [setSharedFlag] or/ands the same aA byte via ama.a(alw,Object); ama.a(List) [assignValues] -> a(DataItem,DataValue) stores DataValue.value into the same itemsById slot; chl.i(Z) [LivingEntity.setSprinting] = invokespecial cgk.i(Z) + sprint speed modifier.

**Mental implication:** Server flag writes DO perturb a modern client's own sprint belief — Mental's (now-removed) setSprinting(false) echo genuinely un-sprinted the client; conversely with the echo removed (S1a) the server has no remaining pathway that touches the client's sprint state.

### `cli-diff-sender-one-stop` (echo-adoption) — confirmed-from-bytecode, verified

**Claim:** The client's sprint packet emission is a pure diff: LocalPlayer.sendIsSprintingIfNeeded compares isSprinting() (the shared flag) against the last-sent wasSprinting field and emits exactly one ServerboundPlayerCommandPacket START_SPRINTING/STOP_SPRINTING on change; adopting the echo therefore yields at most ONE STOP_SPRINTING, after which wasSprinting latches false.

**Evidence:** hnh.R() [LocalPlayer.sendIsSprintingIfNeeded]: 'invokevirtual cA:()Z; istore_1; if_icmpeq 48 vs getfield cX:Z [wasSprinting]; new ajj(this, flag ? ajj$a.b : ajj$a.c); hig.b(packet); putfield cX'. Mappings: ajj = ServerboundPlayerCommandPacket, ajj$a.b = START_SPRINTING, ajj$a.c = STOP_SPRINTING. Call order: hnh.g() [tick] runs super.tick (aiStep inside) then Q() [sendPosition], and Q()'s first instruction is 'invokevirtual R:()V'.

**Mental implication:** Confirms latch step 3 verbatim: echo -> adoption -> one STOP -> SprintWire clientSprinting latches false; and confirms the wire will see a genuine rising-edge START whenever the client later re-engages, which S1(b)'s clientSprinting-gated re-arm relies on for w-tap separation.

### `cli-hold-path-reengages` (echo-adoption) — contradicts-prior-assumption, verified

**Claim:** CONDITIONAL, not absolute: 'no START ever returns' holds only while canStartSprinting() stays false every tick. aiStep's hold path re-runs setSprinting(true) EVERY tick the sprint key/toggle reads down ('if (input.keyPresses.sprint()) setSprinting(true)' inside the canStartSprinting() gate), and aiStep precedes the diff sender — so a ctrl-hold or toggle-sprint client not blocked by item-use re-adopts sprint at the next aiStep, in the common case BEFORE any STOP ships, making the echo wire-invisible for that client.

**Evidence:** hnh.d_() [aiStep] offsets 376-440: 'invokevirtual aa:()Z [canStartSprinting]; ifeq 443'; inner block offsets 383-424 (double-tap, guarded by iload_3 rising-edge flag); offsets 425-440: 'getfield c:Lhnf [input]; getfield hnf.a:Lddk [keyPresses]; invokevirtual ddk.g:()Z [sprint()]; ifeq 443; iconst_1; invokevirtual i:(Z)V [setSprinting(true)]'. ddk record accessor order (mappings 110144+: forward()->a, backward()->b, left()->c, right()->d, ...) plus hnf.d() [makeJump] copying shift via f() and sprint via g() pins g()=sprint(). Tick order: hnh.g() -> super.tick(aiStep) -> Q() -> R().

**Mental implication:** The plan's step 4 wording over-generalizes: the one-way latch is guaranteed only for clients whose canStartSprinting stays false (block-hit item-use, or W-double-tap sprinters with no rising edge); a plain ctrl-holder spam-clicking WITHOUT block-hitting would self-heal from the echo. This doesn't weaken S1 (killing the echo is still correct and strictly safer) but it refines which clients the old echo actually latched — matching the owner's block-hitting capture.

### `cli-itemuse-blocks-start` (echo-adoption) — confirmed-from-bytecode, verified

**Claim:** Item-use blocks STARTING a sprint on the 1.21.11 client via canStartSprinting: it requires !isSlowDueToUsingItem(), where isSlowDueToUsingItem = isUsingItem() && !useItem.get(DataComponents.USE_EFFECTS, UseEffects.DEFAULT).canSprint() — so a block-hitting client (using a sword given a blocking/consumable use, default USE_EFFECTS) cannot re-engage sprint while the use is active.

**Evidence:** hnh.aa() [canStartSprinting]: '!cA() [isSprinting]; hnf.c() [input.hasForwardImpulse: moveVector.y > 1.0E-5]; A(gL().b) [isSprintingPossible(abilities.flying)]; T() ifne 70 [!isSlowDueToUsingItem]; gj()/E() edge cases requiring bC() [isUnderWater]'. hnh.T(): 'invokevirtual fZ:()Z [isUsingItem]; getfield bT:Ldlt [useItem]; dlt.a(ki.g, dph.a) [ItemStack.getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT)]; invokevirtual dph.a:()Z [UseEffects.canSprint]; ifne 33' — true iff using and !canSprint. Mappings: ki.g = USE_EFFECTS, dph = UseEffects (canSprint()->a). hnh.A(Z) [isSprintingPossible] = !hl() [Player.isMobilityRestricted] && (isPassenger ? vehicleCanSprint : gV() [hasEnoughFoodToDoExhaustiveManoeuvres]) && (!flying-arg || !bD()).

**Mental implication:** Confirms why the owner's block-hit spam latched permanently under the old echo, and why S2's blockhit re-arm is the only reconstruction for those clients; note the USE_EFFECTS.canSprint escape hatch — an item whose component sets canSprint=true would NOT block re-engage.

### `cli-no-bookkeeping-reset` (echo-adoption) — confirmed-from-bytecode, verified

**Claim:** Adopting sprint=false from the server resets NO client sprint-gesture bookkeeping: assignValues only invokes onSyncedDataUpdated, and LocalPlayer's override handles LIVING_ENTITY_FLAGS (start/stop item-use mirroring) and SHARED_FLAGS solely for the fall-flying elytra sound — sprintTriggerTime, wasSprinting, and key/toggle state are untouched by adoption.

**Evidence:** ama.a(List) [assignValues]: per DataValue 'a(DataItem,DataValue)' then 'invokeinterface alz.a(alw) [holder.onSyncedDataUpdated(accessor)]', finally alz.a(List). hnh.a(alw) [LocalPlayer.onSyncedDataUpdated]: invokespecial hne.a(alw); branch 1 on bk [DATA_LIVING_ENTITY_FLAGS] -> c(cdb)/gf() [start/stopUsingItem] guarded by df [startedUsingItem]; branch 2 on aA [DATA_SHARED_FLAGS_ID] -> gj() [fall-flying check] && !dk [wasFallFlying] -> play elytra sound; no store to e [sprintTriggerTime] or cX [wasSprinting] anywhere in the method (cX is written only in <init> and R()).

**Mental implication:** For a W-double-tap sprinter holding W, adoption leaves no path back: the double-tap block runs only on a rising edge of forward impulse (aiStep 'iload_3; ifne 425' where flag3 = hnf.c() captured before input.tick), so re-engage demands a full release + double-tap within options.sprintWindow() — the 'fresh gesture' the latch diagnosis described, now cited to the exact instructions.

### `cli-ongoing-sprint-survives-itemuse` (echo-adoption) — confirmed-from-bytecode, verified

**Claim:** An ONGOING client sprint is NOT stopped by item-use: shouldStopRunSprinting checks only isSprintingPossible, input.hasForwardImpulse (threshold a mere moveVector.y > 1.0E-5, so use-slowdown cannot drop it while W is held), and horizontal collision — no isUsingItem term. Absent Mental's echo, a block-hitting modern client that was already sprinting keeps its flag true and never sends STOP.

**Evidence:** hnh.V() [shouldStopRunSprinting]: 'A(gL().b) [isSprintingPossible]; hnf.c() [hasForwardImpulse]; getfield ad [horizontalCollision] / getfield ag [minorHorizontalCollision]' composed as !(possible && impulse && (!collision || minor)); no fZ/T call. hnf.c(): 'getfield b:Lftl [moveVector]; getfield ftl.k:F [y]; ldc 1.0E-5f; fcmpl'. aiStep stop block offsets 443-484: only 'if (cA()) { if (cB() [isSwimming]) W() else V() -> i(false) }'.

**Mental implication:** S1(a)'s vanilla-baseline rationale is bytecode-true: unmodified modern vanilla never un-sprints a block-hitting attacker client-side, so killing the echo restores exactly the client-authoritative truth the wire should mirror, and SprintWire's raw clientSprinting stays true through block-hits.

### `cli-packet-drain-point` (echo-adoption) — unresolvable-from-binaries, verified

**Claim:** The exact drain point of the queued entity-data task relative to the local player's tick within the same client frame was not established from the binaries (only that handleSetEntityData re-dispatches to the main thread via PacketUtils.ensureRunningOnSameThread); therefore whether the STOP ships on tick N or N+1 after the echo is unresolved — but in every ordering exactly one aiStep separates adoption from the next diff, so the wire outcome (one STOP, or silent re-engage) is unaffected.

**Evidence:** hig.a(agp) offsets 0-9: 'invokestatic abb.a:(Laay;Lxk;Lxl;)V' [PacketUtils.ensureRunningOnSameThread(packet, listener, BlockableEventLoop)] — mappings pin abb = net.minecraft.network.protocol.PacketUtils. The Minecraft run-loop/BlockableEventLoop drain scheduling versus ClientLevel entity ticking was not disassembled.

**Mental implication:** Harmless for Mental: the SprintWire consumes arrival order, not client tick indices; the only latency question (which tick the STOP lands) does not change the latch semantics or the S1(b) one-tick-gap re-arm design.

### `BH-1` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** A right-click with a BLOCKS_ATTACKS item starts item use purely from the component: Item.use has a dedicated BLOCKS_ATTACKS branch that calls startUsingItem and returns CONSUME

**Evidence:** Item.use = dlp.a(dwo,ddm,cdb): after CONSUMABLE(ki.z) and EQUIPPABLE(ki.H) branches, `dlt.c(ki.M)` (has BLOCKS_ATTACKS) -> `ddm.c(cdb)` (Player.startUsingItem) -> return `cdc.c`; InteractionResult <clinit> builds cdc.c=CONSUME as new Success(SwingSource cdc$e.a=NONE,...). Item.getUseDuration (dlp.a(dlt,chl)) returns 72000 for ki.M; getUseAnimation (dlp.b) returns dlv.d (BLOCK) for ki.M.

**Mental implication:** Mental's server-side blocks_attacks patch on swords is sufficient for the client to enter shield-style use on right-click; no consumable/use_duration components needed, and because CONSUME's swing source is NONE the block-start itself produces no swing packet.

### `BH-2` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** Starting item use crosses NO sprint edge: neither LivingEntity.startUsingItem nor LocalPlayer.startUsingItem touches the sprint flag, and no sprint packet is emitted at block-start

**Evidence:** LivingEntity.startUsingItem = chl.c(cdb): sets useItem(bT), useItemRemaining(bU), and (server-side only, guarded by dwo.B_) the living-entity flags — no setSprinting call anywhere. LocalPlayer override hnh.c(cdb): super + startedUsingItem(df)=true + usingItemHand(dg). sendIsSprintingIfNeeded (hnh.R) only fires on isSprinting()!=wasSprinting(cX), which use-start does not change.

**Mental implication:** A modern block-hitter's wire shows no STOP at block-start; SprintWire's raw clientSprinting stays true through the whole blockhit hold — the S1 re-arm predicate (!sprinting && clientSprinting) is reachable exactly as the plan assumes.

### `BH-3` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** ONGOING item use does NOT stop an active run sprint: shouldStopRunSprinting has no item-use term, and hasForwardImpulse reads the RAW key vector, unaffected by the 0.2 use slowdown

**Evidence:** hnh.V (shouldStopRunSprinting) = !(isSprintingPossible(A(abilities.flying)) && input.hasForwardImpulse(hnf.c) && (!horizontalCollision(ad) || minorCollision(ag))). hnf.c = moveVector.y(ftl.k) > 1.0E-5f; KeyboardInput.tick (hng.a) builds moveVector as raw ±1 from KeyMapping.isDown pairs. The use slowdown lives downstream in modifyInput (hnh.a(ftl)): scale 0.98 then, iff fZ()&&!cq(), scale by itemUseSpeedMultiplier U().

**Mental implication:** A sprinting player who raises block KEEPS the client-side sprint flag for the entire hold; the only sprint-false sources in the gesture are the full-charge attack self-clear or an external server echo — i.e., Mental's deferred setSprinting(false) echo is the sole STOP producer at spam cadence, exactly the latch source S1 removes.

### `BH-4` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** STARTING a sprint is blocked during use via canStartSprinting's !isSlowDueToUsingItem term — which is component-driven (UseEffects.canSprint), not a bare isUsingItem check; the double-tap window is additionally zeroed every using tick

**Evidence:** hnh.aa (canStartSprinting) = !cA() && input.hasForwardImpulse && A(abilities.flying) && !T() && (!gj()||underWater) && (!isMovingSlowly||underWater). hnh.T (isSlowDueToUsingItem) = fZ() && !useItem.getOrDefault(ki.g USE_EFFECTS, dph.a DEFAULT).canSprint(); dph <clinit>: DEFAULT = new UseEffects(false, true, 0.2f). aiStep (hnh.d_ offsets 340-375): sprintTriggerTime=0 if shift || (T() && !isPassenger) || keyPresses.backward.

**Mental implication:** The plan's step-4 premise 'item-use blocks STARTING a sprint' is confirmed for vanilla swords (no use_effects => canSprint=false); note a use_effects component with canSprint=true would lift the block — if Mental ever patches components onto swords it must not add USE_EFFECTS, or the latch analysis changes.

### `BH-5` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** The 1.21.11 client CANNOT attack while holding right-click block: attack clicks during isUsingItem are consumed and discarded, and held-attack (continueAttack) early-returns on isUsingItem — no packet, no swing

**Evidence:** Minecraft.handleKeybinds = gfj.by offsets 615-698: `if (player.fZ()) { if (!options.keyUse(gfo.C).isDown(gfh.f)) gameMode.releaseUsingItem(hio.b); while(keyAttack(gfo.D).consumeClick(gfh.h)); while(keyUse.consumeClick()); while(keyPickItem(gfo.E).consumeClick()); }` — startAttack (gfj.bu) is only in the else branch. Minecraft.continueAttack = gfj.e(Z) offsets 9-26: returns if missTime>0 or player.fZ().

**Mental implication:** The classic 1.7/1.8 blockhit gesture (LMB while blocking) is INERT on a modern client — every real hit from a modern block-hitter arrives in a tick that began un-blocked (release -> attack -> re-block). SwordBlockingUnit's re-arm therefore sees a USE_ITEM-driven interact on every re-block cycle, one cycle per hit, and Mental will never see ATTACK while the victim-side use flag is up from the same client tick.

### `BH-6` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** Releasing block sends exactly ServerboundPlayerActionPacket(RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN) plus local stopUsingItem; it restores nothing sprint-related

**Evidence:** MultiPlayerGameMode.releaseUsingItem = hio.b(ddm): ensureHasSentCarriedItem(l()) -> connection.send(new aji(aji$a.f RELEASE_USE_ITEM, is.c, iz.a)) -> ddm.ge() (LivingEntity.releaseUsingItem) -> gf() (stopUsingItem) clears useItem/useItemRemaining and LocalPlayer.df. No sprint or velocity code on the release path.

**Mental implication:** RELEASE_USE_ITEM is a clean per-cycle boundary marker on the wire; Mental could use it to bound the block window, but must not expect any sprint packet adjacent to it from the release itself.

### `BH-7` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** The attack's client-side sprint self-clear (and 0.6 self-slow) moved INTO Player.causeExtraKnockback and only fires when total extra-knockback strength > 0 — i.e. only at attackStrengthScale(0.5)>0.9 while sprinting (or with attack_knockback attribute); spam-cadence attacks clear nothing and send no STOP

**Evidence:** Player.attack = ddm.e(cgk): flag7 = getAttackStrengthScale(0.5)(I(F)) > 0.9f; flag8 = cA() && flag7; on hurtOrSimulate(cgk.b(cex,F)) success calls a(cgk, b(cgk,cex) + (flag8?0.5f:0f), pos). causeExtraKnockback = ddm.a(cgk,F,ftm): `if (f>0) { knockback target; setDeltaMovement(mul 0.6d,1.0,0.6d); i(false) setSprinting }`. LivingEntity.getKnockback = chl.b(cgk,cex): ATTACK_KNOCKBACK attr (cis.e)/2 client-side (enchant half only under ServerLevel) — 0 for an unenchanted player.

**Mental implication:** At blockhit spam cadence the modern client never predicts the era in-attack un-sprint, so vanilla-side no STOP crosses per hit — Mental's echoed setSprinting(false) is behaviorally NOVEL to the client (not redundant as in the full-charge case), confirming S1's rationale for killing the echo; conversely CADENCE attack-speed spoofing that pushes charge past 0.9 will make the client itself clear+STOP each hit (the plan's 'CADENCE-spoofed modern sends STOP' is real).

### `BH-8` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** Within one client tick the gesture packets flush in handleKeybinds BEFORE the movement/sprint packets, and inside handleKeybinds attack clicks drain before use clicks: order is INTERACT(ATTACK), SWING, then USE_ITEM (same-tick re-block), then PlayerInput-diff, then PlayerCommand START/STOP_SPRINTING, then MovePlayer

**Evidence:** Minecraft.tick = gfj.x (mapped 1868:1999): 'Keybindings' -> by() at offset 373, ClientLevel.tickEntities (hif.g, mapped 325:331) at offset 436. by() else-branch: while(keyAttack.consumeClick) startAttack (699-719) BEFORE while(keyUse.consumeClick) startUseItem (722-739). LocalPlayer.tick = hnh.g: sends ajk ServerboundPlayerInputPacket iff !lastSentInput.equals(input.keyPresses), then sendPosition Q() whose FIRST instruction is R() (sendIsSprintingIfNeeded: ajj with ajj$a.b START/c STOP on cX mismatch) before the ajb MovePlayer variants.

**Mental implication:** Any sprint edge caused by an attack (full-charge clear or echo adoption) reaches the wire AFTER that hit's INTERACT in the same tick — SprintWire's arrival-order semantics see hit-then-STOP, never STOP-then-hit, for client-predicted clears; and the era 're-engage START' always lands before the movement packet of its tick, matching the reconcile ordering S1(b) leans on.

### `BH-9` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** A victim-aimed blockhit right-click emits THREE packets — INTERACT_AT, INTERACT, then USE_ITEM — because player-target interacts locally return PASS and startUseItem falls through to useItem

**Evidence:** Minecraft.startUseItem = gfj.bv ENTITY case (offsets 140-273): canInteractWithEntity(hnh.b(cgk,D)) fail -> goto 394 (useItem); else interactAt (hio.a(ddm,cgk,ftj,cdb) sends aiy.a(cgk,Z,cdb,ftm)) and if !consumesAction interact (hio.a(ddm,cgk,cdb) sends aiy.a(cgk,Z,cdb)); only an InteractionResult$Success (cdc$d) returns early — PASS falls to offset 394 -> hio.a(ddm,cdb) useItem -> lambda builds akf ServerboundUseItemPacket(cdb,seq,ec(),ee()) via startPrediction and runs dlt.a(dwo,ddm,cdb) locally (BH-1 path). Player has no interact override (only interactOn ddm.a(cgk,cdb)); Entity.interact/interactAt defaults are the fallthrough source.

**Mental implication:** S2(b)'s PlayerInteractEntityEvent handler is correct but not strictly necessary for coverage: USE_ITEM always follows for a BLOCKS_ATTACKS sword even when aimed at the victim, so S2(a) (ignoreCancelled=false + useItemInHand!=DENY on the born-cancelled RIGHT_CLICK_AIR event) alone catches every re-block cycle; keep (b) as redundancy, not as the primary path.

### `BH-10` (blockhit-item-use) — contradicts-prior-assumption, verified

**Claim:** After an externally-induced sprint drop, a player who HOLDS (or toggles) the sprint key re-sprints and sends START_SPRINTING at the first tick that begins un-blocked — the one-way latch is unconditional only for double-tap-W sprinters

**Evidence:** hnh.d_ offsets 376-441: `if (aa()) { if (!hadForwardImpulse[pre-tick hnf.c]) { if (sprintTriggerTime>0) i(true) else sprintTriggerTime = options.sprintWindow(gfo.au) } ; if (input.keyPresses.sprint() [ddk 7th component = gfo.y keySprint via hng.a]) i(true) }`. aa() passes once fZ() is false (release tick) with forward held; the key-press path needs no impulse edge. The double-tap path needs hasForwardImpulse false on the PREVIOUS tick — impossible while W is held — and sprintTriggerTime is zeroed every using tick (BH-4).

**Mental implication:** The plan's step 4 ('No START ever returns') is overstated as written: it is airtight for double-tap sprinters, but a hold-to-sprint/toggle-sprint modern client emits a genuine START in every release-attack window, which would re-arm the wire with freshness even without S1. The owner's 900-hit latched capture is therefore also evidence about his input style (double-tap or W-held-no-key), and S1's re-arm remains necessary for exactly that class of client; tests should cover both key styles.

### `BH-11` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** The client adopts server entity-data echoes for ITSELF with no self-exclusion: sprint is read straight from shared flag 3, and the item-use living-flag echo force-starts/stops the LOCAL use state both directions

**Evidence:** ClientPacketListener.handleSetEntityData = hig.a(agp): level.getEntity(packet.id) -> cgk.aD().a(List) SynchedEntityData.assignValues — any entity including the local player. Entity.isSprinting = cgk.cA: getSharedFlag(3) (i(I)). LocalPlayer.onSyncedDataUpdated = hnh.a(alw): for DATA_LIVING_ENTITY_FLAGS(bk): bit1&&!df -> startUsingItem(hand from bit2); !bit1&&df -> stopUsingItem().

**Mental implication:** Confirms the latch's adoption leg (plan step 3): Mental's echoed setSprinting(false) flips the client's own isSprinting the moment the SetEntityData applies, and R() then emits the single confirming STOP; it also means any Mental-side manipulation of a player's living-entity use flag would push the client in/out of block state — a lever (and a hazard) for SwordBlockingUnit.

### `BH-12` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** The movement slowdown while blocking is exactly ×0.2 on the input vector (after a global 0.98 scale), applied only when not a passenger, and it never feeds back into the sprint state machine

**Evidence:** hnh.a(ftl) (modifyInput): if length!=0: scale(0.98f); if (fZ() && !cq()) scale(U()) where U() = useItem.getOrDefault(USE_EFFECTS, DEFAULT).speedMultiplier() = 0.2f default; then isMovingSlowly -> SNEAKING_SPEED attr scale. applyInput (hnh.fQ) writes the result to xxa(bN)/zza(bP). Sprint checks (aa/V) read raw keyPresses/moveVector, never the modified vector.

**Mental implication:** A blocking sprinter's MovePlayer deltas shrink ~5x while the sprint flag stays true — Mental's ring-derived pace/alignment readers (strafe chase, speed-conformal scaling) will see slow movement WITH sprint=true on block-hitters; do not treat low measured pace as evidence the sprint verdict is stale.

### `BH-13` (blockhit-item-use) — confirmed-from-bytecode, verified

**Claim:** Re-blocking by HOLDING right-click (no fresh click) is throttled by rightClickDelay=4 ticks and gated on !isUsingItem, while a fresh click re-blocks immediately

**Evidence:** gfj.by tail (offsets 798-831): `if (options.keyUse.isDown(gfh.f) && rightClickDelay(aR)==0 && !player.fZ()) startUseItem()`. gfj.bv sets aR=4 unconditionally at entry (offsets 11-13) with no delay check on the consumeClick path; gfj.x decrements aR once per tick (offsets 34-48).

**Mental implication:** A modern block-hitter who never lifts RMB re-enters block at most every 4 ticks after an attack window, so Mental sees USE_ITEM cadence quantized to ~4-tick multiples for hold-style players versus immediate for click-style — useful for interpreting journal timing of the re-arm interacts.

### `BH-14` (blockhit-item-use) — unresolvable-from-binaries, verified

**Claim:** Whether the owner's specific 1.21.11 client build had toggle-sprint enabled, and the exact per-server Paper handling that turns these packets into Bukkit events, cannot be established from these two binaries

**Evidence:** The client jar defines the mechanism (Options gfo.y keySprint feeding ddk.g via hng.a; KeyMapping toggle wrappers exist) but the runtime option state is player configuration, not bytecode; the Paper-side event mapping (born-cancelled RIGHT_CLICK_AIR, PlayerInteractEntityEvent dispatch) lives in the server jar's ServerGamePacketListenerImpl/CraftEventFactory which this axis did not disassemble.

**Mental implication:** Before hard-coding assumptions about which latch class (BH-10) the owner's captures represent, either ask the owner for his sprint keybind mode or have the server axis confirm the event dispatch; S1's tests should pin both the double-tap-latch and key-held-START traces so the fix is honest for either input style.

**Verifier correction:** Claim's classification and thesis stand: toggle-sprint runtime state (user config) and the Paper event dispatch (server jar's ServerGamePacketListenerImpl/CraftEventFactory) are not derivable from these two client binaries — verified the mechanism exists in hng (KeyboardInput builds ddk/Input from gfo.y keySprint via gfh.f/isDown, stored in field a) and ToggleKeyMapping (gfw) exists. Single correction to the evidence wording: keySprint does NOT feed "ddk.g" — ddk (Input) record fields are c=forward, d=backward, e=left, f=right, g=jump, h=shift, i=sprint; keySprint is the 7th constructor arg, so it feeds ddk.i (sprint). ddk.g is the jump field. Swap "ddk.g" → "ddk.i" in the evidence.

### `F1-chain-and-packet-order` (attack-unsprint-gate) — confirmed-from-bytecode, verified

**Claim:** The INTERACT ATTACK packet is sent BEFORE the client-side attack simulation runs: MultiPlayerGameMode.attack sends first, then calls Player.attack, then resetAttackStrengthTicker.

**Evidence:** hio.a(ddm,cgk) [MultiPlayerGameMode.attack]: offset 0 l() [ensureHasSentCarriedItem]; offset 13 aiy.a(cgk,Z) [ServerboundInteractPacket.createAttackPacket(target, ddm.cu()=isShiftKeyDown)] → hig.b(packet) [ClientPacketListener/ClientCommonPacketListenerImpl.send → wu/Connection.a(aay) → a(aay,null,flush=true)]; offset 26 gameType != dwl.d [SPECTATOR] → ddm.e(target) [Player.attack]; offset 35 ddm.hf() [resetAttackStrengthTicker, zeroes both bz and bA]. Caller: gfj.bu [Minecraft.startAttack] case ENTITY → hio.a.

**Mental implication:** On the wire the ATTACK always precedes any sprint STOP from the same swing; SprintWire verdict-at-arrival ordering for the hit itself is unaffected by the client's post-attack prediction.

### `F2-090-constant-and-what-it-gates` (attack-unsprint-gate) — confirmed-from-bytecode, verified

**Claim:** The 0.9 constant exists verbatim and is strict (charge > 0.9f); it gates the sprint-knockback ATTACK bonus branch, crit, and sweep — NOT damage scaling, which is smooth (0.2 + 0.8·charge²) with no threshold.

**Evidence:** ddm.e [Player.attack] offsets 101-115: fload 5 [charge=I(0.5f)=getAttackStrengthScale]; ldc 0.9f; fcmpl; ifle → flag7. flag7 && cA() [isSprinting] → flag8 (offsets 117-137, plays bda.wO=PLAYER_ATTACK_KNOCKBACK); flag7 && J(cgk) [canCriticalAttack] → crit ×1.5f (offsets 159-189); isSweepAttack a(ZZZ) requires flag7 first arg (offset 0: iload_1 ifeq→false). Damage: p() [baseDamageScaleFactor] = 0.2f + I(0.5f)²·0.8f applied unconditionally at offset 68-74; ench bonus = charge·(getEnchantedDamage−dmg) at 53-66.

**Mental implication:** The prior '>=90% charge' framing is confirmed in substance (strictly greater-than 0.9); on 1.21.11 the gate still controls the attack bonus branch itself, not merely damage — Mental's charge-gate assumptions in the latch plan hold.

### `F3-unsprint-lives-in-causeExtraKnockback-gated-on-kb` (attack-unsprint-gate) — confirmed-from-bytecode, verified

**Claim:** setSprinting(false) and the ×0.6 self-slow are inside causeExtraKnockback and run only when total knockback strength > 0; on the client that means only on a sprint-bonus hit (Knockback enchant is server-branch-only and ATTACK_KNOCKBACK defaults to 0.0).

**Evidence:** ddm.a(cgk,F,ftm) [causeExtraKnockback]: offset 0-3 'fload_2; fconst_0; fcmpl; ifle 117' skips everything if kb<=0; offsets 94-109 this.k(this.dN().d(0.6,1.0,0.6)) [setDeltaMovement(getDeltaMovement().multiply(0.6,1.0,0.6))]; offsets 112-114 this.i(false) [setSprinting(false)]. Call site ddm.e offset 252-276: kb = b(target,source) [LivingEntity.getKnockback] + (flag8 ? 0.5f : 0f). chl.b(cgk,cex): reads cis.e [ATTACK_KNOCKBACK] attr, applies dsq.d [EnchantmentHelper knockback] ONLY under instanceof axf [ServerLevel], /2 both paths; cis static init registers attack_knockback as RangedAttribute(0.0, 0.0, 5.0).

**Mental implication:** The client-side un-sprint mirror fires exactly on sprint+full-charge PvP hits — Mental's KnockbackUnit deferred clear (S1 removal target) is reproducing a vanilla effect that only exists at that gate, never at spam cadence; the plan's rationale is bytecode-true.

### `F4-prediction-only-vs-RemotePlayer` (attack-unsprint-gate) — confirmed-from-bytecode, verified

**Claim:** The client's attack success branch (knockback prediction, ×0.6, un-sprint) runs only when hurtOrSimulate→hurtClient returns true: RemotePlayer returns true, base Entity returns false, and LivingEntity does NOT override — so attacking mobs never triggers the client-side un-sprint/self-slow on 1.21.11.

**Evidence:** cgk.b(cex,F) [Entity.hurtOrSimulate]: instanceof axf → a(axf,cex,F) [hurtServer] else b(cex) [hurtClient]; cgk.b(cex) = 'iconst_0; ireturn'; hnj.b(cex) [RemotePlayer.hurtClient] = 'iconst_1; ireturn'; full scan of chl.javap shows no 'boolean b(cex)' override in LivingEntity; only ExperienceOrb/EndCrystal/BlockAttachedEntity/ItemFrame/ItemEntity/ShulkerBullet/VehicleEntity/RemotePlayer override per mappings.

**Mental implication:** PvE spam is entirely un-sprint-free client-side; any Mental scenario staged against mob victims can never elicit the client mirror — combat-feel tests of the latch must use player (RemotePlayer) victims.

### `F5-stop-packet-timing` (attack-unsprint-gate) — confirmed-from-bytecode, verified

**Claim:** There is no packet sent from setSprinting(false) itself; sprint state crosses only via the edge-triggered diff sender (sendIsSprintingIfNeeded), which runs later in the SAME client tick as the attack — so when a STOP crosses, it is TCP-ordered after the INTERACT ATTACK (separate flush), after the PlayerInput diff, and before the movement packet.

**Evidence:** chl.i(Z)/cgk.i(Z) [setSprinting] touch only the shared flag + MOVEMENT_SPEED (cis.x) modifier — no send. hnh.R() [sendIsSprintingIfNeeded]: cA() vs field cX [wasSprinting]; on diff sends ajj [ServerboundPlayerCommandPacket] action ajj$a.b/c then cX=now. hnh.Q() [sendPosition] calls R() first (offset 0-1). hnh.g() [tick]: super.tick (offset 18, aiStep inside) → input diff ajk [ServerboundPlayerInputPacket] (offsets 22-57) → Q()/R() (offsets 140-144). gfj.x() [Minecraft.tick]: by() [handleKeybinds, where bu/startAttack fires] at offset 373 precedes hif.g() [ClientLevel.tickEntities] at offset 436. wu.a(aay,ChannelFutureListener,Z) defaults flush=true per send.

**Mental implication:** SprintWire's arrival-order reads are safe: the hit's verdict is always taken before that same swing's STOP can arrive; the STOP (when it exists) lands within the same tick's packet run, so wire clears attributed to the NEXT tick boundary are era-correct.

### `F6-same-tick-rearm-suppresses-STOP` (attack-unsprint-gate) — contradicts-prior-assumption, verified

**Claim:** A modern client holding sprint with forward impulse and not slowed by item use re-arms sprint in aiStep in the SAME tick as the attack's setSprinting(false), BEFORE the diff sender runs — so NO STOP (and no START) ever crosses the wire for a full-charge sprint hit; the un-sprint is wire-invisible. The plan's bucket 'CADENCE-spoofed modern clients send STOP → w-tap required' is wrong for sprint-key holders.

**Evidence:** hnh.d_() [aiStep] offsets 376-440: if aa() [canStartSprinting] and ddk.g() [Input.sprint() key held] → i(true) [setSprinting(true)]; aa() = !cA() && hnf.c() [hasForwardImpulse] && A(abilities.b) [isSprintingPossible] && !T() [isSlowDueToUsingItem] && (!gj() [isFallFlying] || bC() [isUnderWater]) && (!E() [isMovingSlowly] || bC()). aiStep runs inside super.tick (hnh.g offset 18) strictly before R() (via Q at offset 143); R() then sees isSprinting==wasSprinting==true → no packet. Genuine w-taps still emit STOP: releasing forward kills hnf.c() → V() [shouldStopRunSprinting] → i(false) → diff.

**Mental implication:** S1(b)'s auto-re-arm branch (clientSprinting still true, no STOP) is exactly the bucket CADENCE-spoofed and unspoofed-modern key-holders land in — the w-tap separation story survives, but only genuine key-edge w-taps produce STOP/START; do not expect spoofed clients to volunteer STOPs. Also: on plain modern vanilla, the server flag latches false after one full-charge sprint hit while the key is held (client never re-tells), so 'sprint=f' journal reads against vanilla baselines are expected, not a Mental bug.

### `F7-blockhit-blocks-restart` (attack-unsprint-gate) — confirmed-from-bytecode, verified

**Claim:** Item use that slows the player (sword block-hitting) vetoes canStartSprinting via isSlowDueToUsingItem, so after any sprint clear (local prediction or adopted server echo) a block-hitting client sends exactly one STOP and cannot re-arm until the item use ends — the one-way latch's client half, verbatim.

**Evidence:** hnh.aa() offset 31-35: invokevirtual T() [LocalPlayer.isSlowDueToUsingItem, mapping line '545:545 -> T'] → ifne → return false. With aa() false the aiStep re-arm block (offsets 376-440) is skipped entirely; R() then diffs true→false once and latches cX=false.

**Mental implication:** Confirms the latch chain step 3-4 of the 2026-07-10 plan from the client binary: the echo→adopt→single-STOP→no-START sequence is exactly what this bytecode produces for a block-hitter; S1's kill-the-echo fix attacks the correct trigger.

### `F8-spam-click-enumeration` (attack-unsprint-gate) — confirmed-from-bytecode, verified

**Claim:** At spam-click cadence (charge <= 0.9) the client runs: ticker reset (onAttack zeroes attackStrengthTicker, then MultiPlayerGameMode zeroes both tickers), smooth damage prediction (0.2+0.8c² base, linear-c enchant bonus), hurtClient prediction vs players, visual/sound effects, stats and 0.1 exhaustion. It skips: the sprint-kb bonus (+0.5, PLAYER_ATTACK_KNOCKBACK), crit, sweep, the entire causeExtraKnockback body (victim kb prediction, ×0.6 self-slow, setSprinting(false)), and consequently any sprint packet.

**Evidence:** ddm.e: fO() [onAttack→hg, bz=0] at offset 75 runs before all gating; flag7=false kills flag8 (117-142), crit (159-177), sweep (isSweepAttack iload_1 ifeq); causeExtraKnockback called with kb=0+0f → its 'ifle 117' skips body; success-branch always runs a(cgk,ZZZZF) [attackVisualEffects], C(cgk) [setLastHurtMob], a(cgk,dlt,cex,Z) [itemAttackInteraction], a(cgk,F) [damageStatsAndHearts], a(0.1f) [causeFoodExhaustion]; miss plays bda.wP [PLAYER_ATTACK_NODAMAGE]. hio.a calls ddm.hf() after e().

**Mental implication:** 'Client mirrors the un-sprint only at >=90% charge' is CONFIRMED and sharpened: strictly >0.9, requires isSprinting at swing and a hurtClient-true (player) victim; unspoofed modern spam-clickers never touch their sprint flag, so their wire silence is authentic vanilla, and Mental's echo was the only STOP source at that cadence.

### `F9-cadence-spoof-per-click` (attack-unsprint-gate) — confirmed-from-bytecode, verified

**Claim:** With attack_speed spoofed to 1024, charge is exactly 1.0 on every click (delay = 20/1024 ≈ 0.0195 ticks, clamp hits 1 even with ticker=0), so every sprint PvP click runs the full era client contract: +0.5 kb bonus prediction, PLAYER_ATTACK_KNOCKBACK sound, ×0.6 self-slow, setSprinting(false) — the spoof does restore the era per-hit client-side technique contract; it does NOT create STOP/START wire cadence while the sprint key is held (see F6).

**Evidence:** ddm.he() [getCurrentItemAttackStrengthDelay] = (1.0d / attr(cis.f [ATTACK_SPEED])) × 20.0d; ddm.I(F) [getAttackStrengthScale] = bgj.a((bz + 0.5f)/he(), 0f, 1f) — with he()≈0.0195, (0+0.5)/0.0195 ≫ 1 → clamp 1.0 every swing; flag7 = 1.0 > 0.9f true; remainder per F2/F3.

**Mental implication:** 'Spoof restores era sprint cadence' is true for the client-side 0.6/bonus contract (the untouchable era feel), but the era WIRE cadence (STOP→gap→START) is not reproduced by the spoof for key-holders — S1(b)'s wire re-arm from surviving clientSprinting is the piece that supplies the era cadence, and the spoof neither helps nor conflicts with it.

### `F10-server-never-rederives` (attack-unsprint-gate) — confirmed-from-bytecode, verified

**Claim:** The deployed Paper 1.21.11 server sets the sprint flag ONLY from ServerboundPlayerCommandPacket START/STOP_SPRINTING; handlePlayerInput stores lastClientInput, fires PlayerInputEvent/PlayerToggleSneakEvent (and setShiftKeyDown), and never calls setSprinting — the server does not re-derive sprint from the modern input packet.

**Evidence:** paper-1.21.11.jar (mojang-mapped) net/minecraft/server/network/ServerGamePacketListenerImpl: handlePlayerInput (javap lines 720-214ret) contains setShiftKeyDown/setLastClientInput/resetLastActionTime/removeEntitiesOnShoulder only; the sole ServerPlayer.setSprinting call sites in the class (javap lines 7997, 8002) sit inside handlePlayerCommand's START_SPRINTING/STOP_SPRINTING switch behind PlayerToggleSprintEvent.

**Mental implication:** Confirms the latch plan's step 4 on the actual deployed binary: once the wire/server flag drops false, only a client START (a real flag rising edge, F6) can restore it — the SprintWire re-arm from surviving clientSprinting is the only honest reconstruction.

### `F11-toggle-sprint-key-semantics` (attack-unsprint-gate) — unresolvable-from-binaries, REFUTED

**Claim:** Whether Input.sprint() (ddk.g, the field aiStep's re-arm reads) remains held-true under the client's toggle-sprint accessibility option could not be established from the disassembled classes — the KeyboardInput/ClientInput option processing was not traced.

**Evidence:** hnh.d_ reads hnf.a [ClientInput.keyPresses, an Input record] .g() [sprint()]; the population of that record from Options' toggle-sprint handling lives in the ClientInput/KeyboardInput tick paths, which were not disassembled in this pass.

**Mental implication:** If toggle-sprint produces a persistently-true sprint() key, F6's same-tick re-arm covers toggle users too; if not, toggle users would emit a STOP after full-charge hits. Worth one targeted disassembly of KeyboardInput before pinning wire-cadence expectations for toggle-sprint players.

**Verifier correction:** The cited bytecode is accurate (LocalPlayer hnh.d_/aiStep at bci 425-440 reads ClientInput.keyPresses.sprint() = ddk.g() and re-arms sprint). But the "unresolvable-from-binaries" classification is wrong: the toggle-sprint answer is fully derivable from classes in the same jar. KeyboardInput.tick (hng.a) builds the Input record's sprint field from Options.keySprint.isDown() (gfo.y via gfh.f). Options (gfo) constructs keySprint as `new gfw` (ToggleKeyMapping) with the "toggleSprint" needsToggle supplier. ToggleKeyMapping.setDown (gfw.a) flips isDown to !isDown on press when needsToggle is true, and KeyMapping.isDown (f) returns that stored flag — so under toggle-sprint the sprint key reads persistently-true until pressed again. Therefore Input.sprint() stays true and LocalPlayer.d_ re-arms sprint every tick: F6's same-tick re-arm DOES cover toggle-sprint users; they would NOT emit a STOP after a full-charge hit. The question the claim flags as unknown is resolvable and resolves affirmatively.

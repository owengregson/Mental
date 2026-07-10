# F3 — valve arm lifecycle races (three composing fixes)

## 1. Problem

The `VelocityValve` slot is keyed only by `(recipient, entityId, 3 quantized shorts)` with no hit identity (`core/src/main/java/me/vexmc/mental/v5/VelocityValve.java:30-41`), and its lifecycle has three holes. (a) `DeskRouter.onPlayerVelocity` (`core/src/main/java/me/vexmc/mental/v5/delivery/DeskRouter.java:53`, `@EventHandler(HIGH)`, no cancel check) arms the valve at HIGH-time via `DirectiveExecutor.apply` (`DirectiveExecutor.java:29`); a foreign HIGHEST/MONITOR listener that then cancels/modifies `PlayerVelocityEvent` means vanilla never emits the matching duplicate, the arm goes dead, and — because spam-trade stamps are byte-identical and `BurstSender` uses the non-silent `user.writePacket` which fires PE's `PacketSendEvent` — the NEXT hit's pre-send burst is consumed against the dead arm by `ValveListener` (`ValveListener.java:56`): that hit reaches the client with ZERO velocity copies, cascading across an exchange. (b) The blocked-knock redelivery arms in the TASK phase (`core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java:533`) while its duplicate only emits at the end-of-tick tracker triggered by `setVelocity` (`:546`); `SessionService.tick`'s `valve.clearStale` (`SessionService.java:348`) is unconditional (no age check) and can wipe the arm between them (dup ships uncancelled = doubled stamp), and in the `!wirePreSent` branch the owning-thread `arm()` runs before netty processes the just-issued `writePacket`, so the valve reliably eats Mental's OWN burst (knock lands a tick late = mushy).

## 2. Design decision

Three composing changes, each closing one hole structurally rather than probabilistically:

1. **Arm confirmation at MONITOR** — `DirectiveExecutor` stops arming; it returns the `ValvePayload` intent, `DeskRouter`'s HIGH handler stashes it in a `ThreadLocal` (event-identity-guarded), and a new MONITOR handler on the SAME class arms only if the event survived (not cancelled, final velocity still quantizes to the planned payload). Chosen over "arm-then-disarm-on-observed-cancel" because arming late can never leave a dead arm from any ≤HIGHEST cancel; a Bukkit ordering fact makes it sound: `PlayerVelocityEvent` is dispatched synchronously inside `ServerEntity.sendChanges` and ALL priorities complete before `ClientboundSetEntityMotionPacket` is built, so a MONITOR arm still strictly precedes the duplicate. `DeliveryDesk` stays sole velocity writer — the MONITOR handler only reads the event and arms.
2. **Burst-origin immunity via silent writes** — `BurstSender.ship` switches `user.writePacket(...)` → `user.writePacketSilently(...)`. PE 2.12.1 facts (verified in this design against `packetevents-api-2.12.1-sources.jar` and `packetevents-netty-common-2.12.1-sources.jar`): `User.writePacketSilently(PacketWrapper)` → `ProtocolManager.writePacketSilently` runs the SAME `transformWrappers(wrapper, channel, true)` encode as the plain variant, then `ProtocolManagerAbstract.writePacketSilently` → `ChannelHelper.writeInContext(channel, PacketEvents.ENCODER_NAME, buf)` — the write enters the pipeline AT PE's encoder context, skipping only PE's own encoder stage (where `PacketSendEvent` fires), while every handler head-side of it (Via, compression, encryption) still processes it; bytes on the wire are identical. Because Mental's PE is shaded+relocated (`me.vexmc.mental.lib.packetevents`), the ONLY listeners skipped are Mental's own — external anticheats (their own injectors/handlers) and SimpleBoxer's captured connection still see the burst. Grep-verified: `ValveListener` is the only Mental `onPacketSend` listener that watches ENTITY_VELOCITY (the cadence listeners watch SOUND/PARTICLE/attribute/tooltip packets). This kills the entire "valve eats Mental's own pre-send" class — leg (a)'s cascade step AND leg (b)'s `!wirePreSent` self-eat — structurally, whereas a thread-local mark around `writePacket` is BROKEN here: both burst call sites run off the victim's channel event loop (fast path = attacker's netty read thread; blocked path = victim's region thread), so netty schedules the write and PE's send event fires on the victim's event loop where the caller's ThreadLocal is invisible.
3. **Age-aware clearStale (N=2)** — the valve arm carries a `TickStamp`; `clearStale(victim, now)` clears only arms with `now − armedAt ≥ 2` (or unknown stamps, degrading to today's behavior). Derivation of N: both arm sites stamp within the same tick T whose end-of-tick `sendChanges` emits the duplicate (normal path arms at MONITOR inside sendChanges itself; blocked path arms in the task phase of T and `setVelocity` marks `hurtMarked` for T's sendChanges), so the dup's consume happens in T except for (i) netty event-loop scheduling lag under backlog (up to ~a tick) and (ii) Folia counter/region phase skew of ±1 — the exact skew `DeliveryDesk.sweep`'s documented two-tick margin absorbs (`DeliveryDesk.java:232-239`). Age ≥2 is provably past any legal dup; age ≤1 must be kept.

`kernel/src/main/java/me/vexmc/mental/kernel/delivery/ValvePayload.java`: **NO change** (listed in the brief; the quantization stays the single wire-encoding seam and the kernel stays untouched — no japicmp exposure).

## 3. Exact changes

### `core/src/main/java/me/vexmc/mental/v5/VelocityValve.java`
- Add import `me.vexmc.mental.kernel.model.TickStamp`.
- Add a private nested record + constant:
```java
/** Legal-dup grace: end-of-arm-tick emit + event-loop lag + Folia ±1 skew (mirrors DeliveryDesk.sweep). */
private static final int DUP_GRACE_TICKS = 2;

private record Armed(ValvePayload payload, TickStamp at) {}
```
- Slot type becomes `ConcurrentHashMap<UUID, AtomicReference<Armed>>`.
- `arm` signature change (core-internal, no API surface): `public void arm(UUID victim, ValvePayload payload, TickStamp at)` — sets `new Armed(payload, at)`.
- `consume` unchanged signature; body reads `Armed armed = slot.get();` and matches on `armed.payload().entityId()/qx()/qy()/qz()`; CAS on the `Armed` reference.
- `clearStale` becomes:
```java
/** Session-tick sweep: drop an arm only once it is provably past its legal duplicate (age >= 2 ticks or unknown clock). */
public void clearStale(UUID victim, TickStamp now) {
    AtomicReference<Armed> slot = slots.get(victim);
    if (slot == null) return;
    Armed armed = slot.get();
    if (armed == null) return;
    if (!armed.at().known() || !now.known()
            || now.value() - armed.at().value() >= DUP_GRACE_TICKS) {
        slot.compareAndSet(armed, null);
    }
}
```
- `forget` unchanged. Update class javadoc: arm is placed by the MONITOR confirm (normal path) or the blocked-redelivery task (aged), consumed once by the tracker dup, and swept only past the two-tick dup grace; Mental's own bursts are silent-written and never traverse this valve.

### `core/src/main/java/me/vexmc/mental/v5/delivery/DirectiveExecutor.java`
- Signature: `public static ValvePayload apply(Directive directive, VelocitySink sink)` (drop `UUID victim, VelocityValve valve`; drop both imports). `SHIP_AND_ARM_VALVE` does `sink.ship(directive.ship()); return directive.arm();`; all other cases return `null` after their existing side effect. Javadoc: the returned payload is an ARM INTENT the caller confirms at MONITOR after the event survives every priority — executing the arm here (HIGH-time) left a dead arm whenever a HIGHEST/MONITOR foreign listener cancelled/modified the event, and a dead arm aliases the next byte-identical hit's duplicate.

### `core/src/main/java/me/vexmc/mental/v5/delivery/DeskRouter.java`
- Add imports: `me.vexmc.mental.kernel.delivery.ValvePayload`.
- Add: `private record PendingArm(PlayerVelocityEvent event, UUID victim, ValvePayload payload) {}` and `private final ThreadLocal<PendingArm> pendingArm = new ThreadLocal<>();`
- In `onPlayerVelocity` (HIGH), replace line 79:
```java
ValvePayload armIntent = DirectiveExecutor.apply(directive, sinkFor(event));
if (armIntent != null) {
    pendingArm.set(new PendingArm(event, victim.getUniqueId(), armIntent));
}
```
(the `if (context != null && directive.ship() != null)` block below is untouched).
- New MONITOR handler (deliberately `ignoreCancelled = false`, the default, so it observes cancelled events and drops the intent):
```java
/**
 * The arm confirmation (F3): the valve is armed only after the velocity event has
 * survived every listener priority — not cancelled, final velocity still quantizing
 * to the pre-sent payload — because vanilla emits the matching ENTITY_VELOCITY
 * duplicate only for a surviving event. Arming at HIGH left a dead arm behind any
 * HIGHEST/MONITOR foreign cancel/modify, and a dead arm aliases the next
 * byte-identical hit's duplicate. Read-only on the event: the desk (HIGH) stays the
 * sole PlayerVelocityEvent writer.
 */
@EventHandler(priority = EventPriority.MONITOR)
public void onPlayerVelocityConfirm(PlayerVelocityEvent event) {
    PendingArm intent = pendingArm.get();
    if (intent == null || intent.event() != event) {
        return; // no arm planned for THIS dispatch (identity, not equality)
    }
    pendingArm.remove();
    Vector velocity = event.getVelocity();
    if (confirmsArm(event.isCancelled(),
            velocity.getX(), velocity.getY(), velocity.getZ(), intent.payload())) {
        valve.arm(intent.victim(), intent.payload(), clock.current());
    }
}

/** Pure confirm predicate (unit-pinned): survive + still quantize to the planned wire encoding. */
static boolean confirmsArm(boolean cancelled, double x, double y, double z, ValvePayload planned) {
    return !cancelled
            && ValvePayload.of(planned.entityId(), new KnockbackVector(x, y, z)).equals(planned);
}
```
`KnockbackVector` is already imported. Note in the HIGH handler's comment why ThreadLocal: nested `PlayerVelocityEvent` dispatches (a plugin calling `setVelocity` inside our `KnockbackApplyEvent`) fully complete BEFORE the intent is set, and Folia dispatches concurrent velocity events on different region threads — the ThreadLocal plus event-identity check make both safe. Quantize-equal (not exact-equal) is deliberate: a sub-quantum MONITOR touch still produces a byte-identical dup, which must be eaten.

### `core/src/main/java/me/vexmc/mental/v5/rim/BurstSender.java`
- In `ship`, replace `user.writePacket(packetFor(...))` with `user.writePacketSilently(packetFor(...))` (whole burst: bundle delimiters, velocity, hurt — one seam). `flushPackets()` unchanged.
- Class javadoc addition: the burst is written SILENTLY (`ChannelHelper.writeInContext(ENCODER_NAME)`) so it skips Mental's own relocated-PE send-event stage — the `ValveListener` therefore sees only server-originated ENTITY_VELOCITY (the tracker dup, foreign plugins) and can never consume Mental's own pre-send against a stale arm; bytes on the wire are identical (same `transformWrappers` encode, all head-side handlers — Via/compression/encryption — still run), and external anticheats/bots on their own injectors still observe the burst.

### `core/src/main/java/me/vexmc/mental/v5/rim/ValveListener.java`
- Javadoc only: note that Mental's own bursts are silent-written and never reach this listener, so every ENTITY_VELOCITY seen here is the vanilla tracker's or a foreign plugin's — the consume can no longer eat a pre-send.

### `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java` (blocked path, :515-547)
- Line 533: `valve.arm(victimId, ValvePayload.of(entityId, era), clock.current());` (the unit already holds `clock`).
- Lines 519-527: capture the burst outcome so a failed blocked-burst cannot arm a valve for a wire copy that never existed (the blocked-path twin of F2's fix — keep semantics aligned with F2: UNSENDABLE ⇒ no wire copy ⇒ no arm):
```java
if (!wirePreSent) {
    User user = PacketEvents.getAPI().getPlayerManager().getUser(victim);
    if (user != null) {
        BurstSender.Outcome outcome = burstSender().ship(user, entityId, era, hurtYaw, bundle);
        wireCopy = outcome == BurstSender.Outcome.DELIVERED;
    }
}
```
- Update the `wireCopy` arm comment: the arm now carries its tick stamp and survives the session sweep for the two-tick dup grace, so the end-of-tick tracker re-emission is consumed even when the session tick runs between the task-phase arm and `sendChanges` (the leg-b race); the burst itself is silent-written and cannot be eaten by this arm.

### `core/src/main/java/me/vexmc/mental/v5/session/SessionService.java` (tick, :348)
- `valve.clearStale(player.getUniqueId(), view.at());` — the same `now` the sweep/net use (`view.at()`), one clock read per tick. Comment: age-aware — a task-phase blocked-knock arm made THIS tick must survive to its end-of-tick tracker dup; only an arm ≥2 ticks old (provably past any legal dup, the desk-sweep margin) is dropped.

## 4. Threading analysis

- `PendingArm` ThreadLocal: written and consumed on the SAME thread within one synchronous event dispatch (Bukkit dispatches HIGH→HIGHEST→MONITOR in-line on the victim's owning region thread; Folia region threads each get their own ThreadLocal slot). Event-identity check makes nested dispatches inert; the slot is cleared on match, and any never-matched residue is overwritten by the next intent on that thread (bounded: one entry per region thread).
- `VelocityValve.Armed` is immutable; `arm` runs on the victim's owning region thread (DeskRouter MONITOR, KnockbackUnit task), `clearStale` on the same region thread (session tick), `consume` on the victim's netty event loop — `ConcurrentHashMap` + `AtomicReference` CAS as today; `clearStale`'s CAS cannot clobber a concurrently-consumed slot.
- The MONITOR confirm reads only the event and `clock.current()` (TickClock is any-thread by contract); it never touches live entity state beyond the event, and never writes the event — DeskRouter's HIGH handler remains the sole `PlayerVelocityEvent` writer, `DeliveryDesk` the sole journal writer.
- `BurstSender` silent writes: same calling threads as today (attacker netty thread / victim region thread); netty still marshals onto the victim's event loop; only PE's event stage is skipped.
- Kernel untouched: no new kernel state, no Bukkit/PE types anywhere near the kernel.

## 5. Era/zero-touch analysis

No config knob is added or changed (`DUP_GRACE_TICKS` is a structural constant, precedented by the desk sweep's two-tick margin); `parse(empty)==LEGACY_17` is untouched. With no foreign velocity-touching plugin, the wire is byte-identical: same burst bytes (same encode, silent skips only Mental's own bus), same dup consumed once, arm merely moves from HIGH to MONITOR within the same synchronous dispatch — still strictly before the packet is built. Zero-touch holds: with all features off nothing submits to the desk, `resolve` never returns SHIP_AND_ARM_VALVE, the MONITOR handler sees a null intent and returns, and the valve stays empty so `ValveListener` cancels nothing. The client-side technique contract and all knock vectors are untouched — this changes only which duplicates are suppressed, never a shipped value.

## 6. Tests (hand-computed)

Quantization arithmetic used below: `q(0.4)=trunc(0.4·8000)=3200`, `q(0.3608)=trunc(2886.4)=2886`, `q(0.0)=0`, `q(0.5)=4000`, `q(0.400001)=trunc(3200.008)=3200`.

**`core/src/test/java/me/vexmc/mental/v5/VelocityValveTest.java`** (modify): all `arm` calls gain a stamp, e.g. `valve.arm(PLAYER, ARMED, new TickStamp(10))`. Keep `armedPayloadIsConsumedExactlyOnce`, `aMismatchNeverConsumes`, `consumeWithNothingArmedIsFalse` verbatim in semantics. Replace `clearStaleDropsAnUnconsumedPayload` with:
- `clearStaleKeepsASameTickArm`: arm at 10, `clearStale(PLAYER, new TickStamp(10))` (10−10=0 < 2) → consume true. This IS the blocked-knock scenario: task-phase arm survives its own session-tick sweep to the end-of-tick dup.
- `clearStaleKeepsAOneTickOldArm`: arm 10, clearStale(11) (1 < 2) → consume true (event-loop backlog / Folia ±1 case).
- `clearStaleDropsATwoTickOldArm`: arm 10, clearStale(12) (2 ≥ 2) → consume false (the stale-arm expiry).
- `clearStaleDropsAnUnknownStampArm`: arm at `TickStamp.NO_TICK`, clearStale(12) → consume false; and arm 10, clearStale(NO_TICK) → consume false (dead-clock degrade = old behavior).

**`core/src/test/java/me/vexmc/mental/v5/delivery/DirectiveExecutorTest.java`** (modify): drop `VelocityValve`/`UUID` from every `apply` call; assertions become return-value pins:
- `shipSetsTheVelocityAndReturnsNoArm`: SHIP → `assertSame(VECTOR, sink.shipped)`, `assertNull(returned)`.
- `shipAndArmValveReturnsTheExactPayload`: `assertSame(VECTOR, sink.shipped)`, `assertSame(payload, returned)` (payload = `ValvePayload.of(42, VECTOR)`).
- `cancelEventCancels` / `passThroughLeavesTheEventAlone`: existing side-effect asserts + `assertNull(returned)`.

**`core/src/test/java/me/vexmc/mental/v5/delivery/DeskRouterConfirmTest.java`** (new, pure — tests the package-private `DeskRouter.confirmsArm`): with `planned = ValvePayload.of(7, new KnockbackVector(0.4, 0.3608, 0.0))` (shorts 3200/2886/0):
- `aCancelledEventNeverConfirms`: `confirmsArm(true, 0.4, 0.3608, 0.0, planned)` → false (foreign cancel ⇒ no arm ⇒ the next hit's dup meets an empty slot — the leg-a cascade's root is gone; the burst side is structurally immune via silent writes).
- `anUntouchedVelocityConfirms`: `confirmsArm(false, 0.4, 0.3608, 0.0, planned)` → true.
- `aMonitorModifiedVelocityRefusesTheArm`: `confirmsArm(false, 0.5, 0.3608, 0.0, planned)` → false (q 4000≠3200; the modified dup must ship as the correction).
- `aSubQuantumTouchStillConfirms`: `confirmsArm(false, 0.400001, 0.3608, 0.0, planned)` → true (trunc(3200.008)=3200 — byte-identical dup must still be eaten).

**Pins that must NOT change**: `DeliveryDeskTest` (kernel untouched — `resolve` still returns SHIP_AND_ARM_VALVE with `ValvePayload.of(victimEntityId, carried)`, including `sweepNeverTimeDropsAConnectedVictimsMelee`); `ValvePayload` record tests; `FeedbackPlan` pins; `PacketTapStateTest`; all `KnockbackEngineTest` values.

## 7. Verification

- `./gradlew build` first — all unit tests green (including the four gates: verifyDowngrade/verifyJdk8Api/verifyTesterIsolation/verifyRelocation; nothing here adds post-Java-8 types to cross-plugin descriptors — all new signatures are core-internal, D-8 safe), japicmp clean (api/kernel untouched).
- `./gradlew integrationTestMatrix` — nonce+PASS per entry, never the banner. Expected NO suite changes: tester FakePlayers are clientless (no PE user), so they never receive bursts (PINNED path) and never traverse the arm/consume flow the fix changes — the matrix regresses compile/wiring, journal parity (blocked-knock suite included: clientless blocked path has `user == null`, `wireCopy` stays false, no arm, setVelocity delivers exactly as today), and zero-touch. Per live-server-testing limits, the burst-origin immunity and the foreign-cancel cascade cannot be driven by FakePlayers; runtime confirmation is the owner's SimpleBoxer/live setup (the captured connection sees silent-written bytes unchanged). The implementer should NOT attempt a FakePlayer suite for these.
- Optional implementer sanity check before coding: `javap`/read the shaded `me.vexmc.mental.lib.packetevents...User` to confirm `writePacketSilently(PacketWrapper)` survived relocation (it will — relocation renames, never strips).

## 8. Risks + rollback

- **Residual leg-a hole**: a foreign MONITOR listener registered AFTER Mental that cancels/modifies (contract-violating but possible) can still strand an arm — now bounded to 2 ticks by fix 3, and the burst-eat consequence is gone entirely (silent bursts), so the worst case is one coincidentally byte-identical foreign velocity eaten within 2 ticks (consume-once, self-healing; pre-existing risk, now narrower).
- **Longer stale-arm life**: arms now survive ≤2 ticks instead of ≤1. Only reachable when no dup arrived (rare, foreign-cancel); the next Mental hit's confirm re-arm overwrites the slot (last-writer-wins), so no cross-hit starvation is possible.
- **Silent-write blind spot**: any FUTURE Mental PE send-listener will not see Mental's own bursts — documented in the BurstSender javadoc as the deliberate contract.
- **Batch conflicts**: coordinate with F2 (same `BurstSender.ship` method + Outcome semantics; the KnockbackUnit:519-527 outcome capture must match F2's UNSENDABLE⇒pin doctrine) and F1 (same KnockbackUnit file, different region). F8's PositionRing first-tick seed may touch `SessionService.tick` adjacent to the clearStale line.
- **Rollback**: single revert — no config, no migration, no kernel/API surface touched. Commit as one conventional commit, e.g. `fix(valve): confirm arms after the event survives, silence own bursts, age-gate the sweep` with a prose body citing the three races.

## Files touched

- `core/src/main/java/me/vexmc/mental/v5/VelocityValve.java`
- `core/src/main/java/me/vexmc/mental/v5/delivery/DirectiveExecutor.java`
- `core/src/main/java/me/vexmc/mental/v5/delivery/DeskRouter.java`
- `core/src/main/java/me/vexmc/mental/v5/rim/BurstSender.java`
- `core/src/main/java/me/vexmc/mental/v5/rim/ValveListener.java`
- `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java`
- `core/src/main/java/me/vexmc/mental/v5/session/SessionService.java`
- `core/src/test/java/me/vexmc/mental/v5/VelocityValveTest.java`
- `core/src/test/java/me/vexmc/mental/v5/delivery/DirectiveExecutorTest.java`
- `core/src/test/java/me/vexmc/mental/v5/delivery/DeskRouterConfirmTest.java`

## Cross-lane conflicts (integrator notes)

F2 (presend outcome pin): overlaps on BurstSender.ship (F2 consumes its Outcome; F3 makes its writes silent) and on the UNSENDABLE⇒no-wire-copy doctrine — F3 adds the blocked-path Outcome capture in KnockbackUnit:519-527 which must match F2's semantics; merge BurstSender edits together. F1 (sprint retro-clear): same file KnockbackUnit.java, different regions (sprintVerdict/obligations vs deliverBlockedKnock :515-550) — textual merge care only. F4 (desk supersede passthrough): touches DeliveryDesk.resolve whose Directives DeskRouter/DirectiveExecutor execute — no file overlap (F3 leaves the kernel untouched) but the SHIP_AND_ARM_VALVE return-value contract of DirectiveExecutor.apply changes here; F4 must not reintroduce a direct arm. F8 (config-minor batch): PositionRing first-tick seed may edit SessionService.tick adjacent to the clearStale line — textual merge care.
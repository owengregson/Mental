# F5+F6 — stamp registration-time truth (attacker enchant level + click-flush yaw)

## 1. Problem

(A) The netty pre-send builds the attacker `EntityState` with `knockbackEnchantLevel = 0` (`core/.../feature/delivery/HitRegistrationUnit.java:461-463`, 10th positional arg; the comment at :456-459 falsely claims "enchant is corrected on the authoritative pass") — but PRE_SENT/PINNED transactions are ADOPTED verbatim (`core/.../feature/knockback/KnockbackUnit.java:256-266`) and the desk ships the carried vector unmodified, so a KB-enchanted weapon's pre-sent hit permanently loses `0.5 × level` horizontal (and a non-sprint KB hit loses the whole extras block incl. the flat 0.1 vertical, `kernel/.../math/KnockbackEngine.java:214-228`). Region-path hits read the live enchant and ship FULL → weak/full alternation by delivery path.
(B) Suppressed pre-sends (REGISTERED melee — systematic under AnticheatMode.AUTO with a detected anticheat) recompute at the deferred EDBEE with the attacker's LIVE yaw (`KnockbackUnit.java:271-272` → `core/.../EntityStates.java:42-51` `location.getYaw()`), 1–2 ticks after the click, while the pre-send correctly used the click-flush `domain.lastYaw()`. The sprint/enchant extra is yaw-directed (`KnockbackEngine.java:224-227`), so a flicking attacker's sprint extra rotates off the click aim: away = 0.4 + 0.5·cos(Δyaw) on LEGACY_17 (0.40 at 90° — exactly the plain-hit value). `HitContext` freezes the `SprintVerdict` but not the yaw — a half-honored era-moment freeze.

## 2. Design decision

Freeze BOTH truths at their natural freeze points, mirroring existing precedents exactly:

- **Enchant** rides the published `PlayerView` (the `moveSpeedAttr` precedent — "the ONLY way a pre-sent knock can see the attacker's X and scale identically to the tick path"): `SessionService.buildView` reads the held item on the owning region thread once per tick and freezes `kbEnchantLevel`; the pre-send reads `view.kbEnchantLevel()`. A netty inventory read is illegal (Folia `ensureTickThread`), so a view freeze is the only lawful carrier. Fallback for views built without the field: **0** (current behavior — netty must never live-read).
- **Yaw** rides the `HitContext` (the `SprintVerdict` precedent — registration stamps, the authoritative pass consumes): a new nullable `Float attackerYaw` component, stamped from `domain.lastYaw()` at registration (netty thread; `lastYaw` is written by `PacketTap` on the SAME netty event loop as the ATTACK parse, so at registration it IS the click-flush yaw). The region recompute consumes the stamp when present; `null` (packetless attacker, Vanilla-source mints) falls back to the live capture, which is byte-identical to today and era-correct for those cases (verified in the finding: Vanilla-source EDBEEs run synchronously at click-flush; a packetless FakePlayer's live yaw IS its attack-time yaw).

Rejected alternatives: stamping the enchant into `HitContext` too (would fix only the recompute agreement, not the pre-send's own vector — the pre-send needs the value BEFORE any context consumer runs, and the view is already the attacker-state carrier); a post-hoc region "correction" of adopted vectors (breaks the compute-once contract and the wire already shipped).

## 3. Exact changes

### 3.1 `kernel/src/main/java/me/vexmc/mental/kernel/model/PlayerView.java`

Grow the canonical record by ONE trailing component `int kbEnchantLevel` (pure JDK — kernel stays Bukkit-free):

```java
public record PlayerView(UUID id, int entityId, TickStamp at,
                         Decay.Motion motion, boolean grounded, double slipperiness,
                         double gravity, double jumpImpulse, int jumpBoostAmplifier,
                         boolean sprinting, boolean creative, boolean pvpAllowed,
                         int noDamageTicks, int maxNoDamageTicks,
                         double knockbackResistance,
                         KnockbackProfile profile, int pingMillis,
                         KinematicState kinematics, double moveSpeedAttr,
                         UUID comboAttackerId,
                         double measuredVx, double measuredVz, float yaw,
                         double eyeHeight, int groundedTicks,
                         double yawRateDegPerTick, int kbEnchantLevel) {
```

Add an explicit 26-arg constructor with the PREVIOUS canonical signature (the exact arity of the current record header, lines 14-25) delegating with `kbEnchantLevel = 0`, javadoc'd in the file's established style: *"The pre-enchant-freeze arity: the attacker's held Knockback level defaults to 0 — the pre-send's historical (enchant-blind) value, so a view built without the freeze is byte-identical to the pre-fix pre-send. The netty realm may never read inventory (Folia ensureTickThread), so this frozen per-tick value is the only lawful way a pre-sent knock can carry the enchant extra — the moveSpeedAttr precedent."* The existing 25-, 20-, 19- and 18-arg delegating constructors are UNTOUCHED (they chain into the 26-arg one, which now chains into the new canonical — verify each still compiles by chaining order: 18→19→20→25(existing "pre-target-v2" ctor, add nothing)→26(new explicit)→canonical 27). Concretely: the current 25-arg ctor at :37-53 currently delegates to the canonical; change ONLY its delegation target to the new 26-arg (append nothing to its own signature).

### 3.2 `kernel/src/main/java/me/vexmc/mental/kernel/model/HitContext.java`

Grow the canonical record by ONE trailing component `Float attackerYaw`; keep the 8-arg arity as an explicit delegating constructor:

```java
public record HitContext(HitId id, HitSource source,
                         UUID attackerId, UUID victimId,
                         SprintVerdict sprint,
                         boolean victimHasWire, Double compensationY,
                         TickStamp registeredAt, Float attackerYaw) {

    /** The pre-yaw-stamp arity: no registration yaw was frozen (Vanilla-source
     *  mints, projectile/rod contexts, packetless attackers) — the recompute
     *  reads the live capture, byte-identical to the pre-fix behavior. */
    public HitContext(HitId id, HitSource source, UUID attackerId, UUID victimId,
                      SprintVerdict sprint, boolean victimHasWire, Double compensationY,
                      TickStamp registeredAt) {
        this(id, source, attackerId, victimId, sprint, victimHasWire, compensationY,
                registeredAt, null);
    }
}
```

Extend the record javadoc's `@param` block: `@param attackerYaw the attacker's click-flush yaw at registration (the connection's last movement-packet yaw, the value the pre-send directs the sprint/enchant extra along), or null when the hit never had a netty registration — the recompute then reads the live capture.` `Float` is java.lang (D-8 safe). All other constructors (`DamageRouter.mintVanilla`, `FishingKnockbackUnit` ×2, `ProjectileKnockbackUnit`, all tests) use the 8-arg arity and need NO edits.

### 3.3 `kernel/src/main/java/me/vexmc/mental/kernel/model/EntityState.java`

Add one additive method (kernel additive-only — no signature changes):

```java
/**
 * A copy with the yaw replaced — the registration-time yaw stamp folded over a
 * live owning-thread capture (the recompute half of the era-moment freeze:
 * the SprintVerdict is stamped and consumed; the yaw the extras are directed
 * along must be the same click-flush instant, not the 1–2-tick-later live read).
 */
public EntityState withYaw(float yaw) {
    return new EntityState(x, y, z, yaw, vx, vy, vz, grounded, sprinting,
            knockbackEnchantLevel, knockbackResistance, moveSpeedAttr);
}
```

### 3.4 `core/src/main/java/me/vexmc/mental/v5/EntityStates.java`

Change `heldKnockbackLevel(LivingEntity)` from `private` to `public`, extending its javadoc: *"Public for the SessionService view freeze — the per-tick owning-thread read that lets the netty pre-send carry the enchant extra (PlayerView.kbEnchantLevel)."* Body unchanged (main-hand-else-off-hand, `Enchantments.knockback()` — `Enchantment.KNOCKBACK` kept its name across the whole 1.9.4→modern range per the platform seam's own doc; `getItemInMainHand`/`getItemInOffHand` exist since 1.9 and this exact code already runs on every matrix entry via the live captures, so zero new cross-version surface).

### 3.5 `core/src/main/java/me/vexmc/mental/v5/session/ViewBuilder.java`

Add one overload appending `int kbEnchantLevel` after `yawRateDegPerTick` (delegating to the new 27-component canonical); the existing top overload (:78-95) changes ONLY its delegation body to call the new overload with `0`. Javadoc in-house style: *"The enchant-freeze overload — carries the attacker's held Knockback level so the netty pre-send ships the enchant extra off the same frozen truth the region path reads live (the moveSpeedAttr pattern). The pre-freeze overload defaults it to 0 (the historical enchant-blind pre-send)."*

### 3.6 `core/src/main/java/me/vexmc/mental/v5/session/SessionService.java` — `buildView` (:491-541)

Add one read alongside the other attribute reads (place it next to the `moveSpeedAttr` read at :505, with a why-comment: era read the held item live inside `attack()`; the netty realm cannot, so this per-tick freeze is the carrier — one-tick staleness on a swap-click is the accepted sprint-view-class divergence):

```java
int kbEnchantLevel = EntityStates.heldKnockbackLevel(player);
```

and pass it as the final argument of the new `viewBuilder.build(...)` overload (import `me.vexmc.mental.v5.EntityStates`). **Folia check (done):** `tick(player)` runs via `scheduling.repeatOn(player, …)` — the player's owning region thread; `getEquipment().getItemInMainHand()` is a handle-routed read that is legal exactly there (same legality class as the `Attributes`/`getActivePotionEffects` reads already in this method, and the same call `EntityStates.capture` already makes on this thread for every region-path hit).

### 3.7 `core/src/main/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationUnit.java`

(a) **`plan(...)` :328-330** — stamp the yaw into the context:

```java
HitContext context = new HitContext(
        ids.next(), new HitSource.Melee(), attackerId, victimId,
        verdict, victimHasWire, compensationY, clock.current(), registrationYaw(attackerId));
```

(b) New private helper in the `Listener` inner class, beside `lastYaw` (:493-496):

```java
/**
 * The click-flush attacker yaw for the era-moment stamp — the same
 * connection-domain value the pre-send directs the extras along — or null
 * when the attacker has no connection domain (packetless attacker: no wire
 * yaw exists, and the region recompute's live capture IS its attack-time
 * yaw). NON-creating peek — the 2.4.4 domain-poisoning rule.
 */
private Float registrationYaw(UUID id) {
    ConnectionDomains.Domain domain = domains.peek(id);
    return domain == null ? null : domain.lastYaw();
}
```

Note: the pre-send's own `lastYaw(attackerId)` (0f fallback) stays as-is — the pre-send path is byte-identical.

(c) **`preAttackerState` :451-464** — split into a thin instance wrapper plus a pure static (the `admitVelocityPreSend` unit-pin idiom), and read the view's frozen enchant. Replace the inner-class method body with:

```java
private EntityState preAttackerState(UUID attackerId, PlayerView view, SprintVerdict verdict) {
    PositionRing.Sample sample = sessions.positions().latest(attackerId);
    return HitRegistrationUnit.preAttackerState(
            sample != null ? sample.x() : 0,
            sample != null ? sample.y() : 0,
            sample != null ? sample.z() : 0,
            lastYaw(attackerId), view, verdict);
}
```

and add to the outer class's "shared helpers" section (next to `admitVelocityPreSend`):

```java
/**
 * The pre-send attacker capture, pure over its frozen inputs so the enchant
 * parity is unit-pinned at this seam. Attacker velocity is unused by the
 * formula's attacker terms; sprint is the stamped verdict; the Knockback
 * enchant level is the view's per-tick freeze — the netty thread cannot read
 * inventory (Folia), and an adopted PRE_SENT/PINNED vector is never
 * recomputed, so the value shipped HERE is the value the era extra rides.
 */
static EntityState preAttackerState(
        double x, double y, double z, float lastYaw, PlayerView view, SprintVerdict verdict) {
    return new EntityState(x, y, z, lastYaw,
            0, 0, 0, view.grounded(), verdict.sprinting(),
            view.kbEnchantLevel(), view.knockbackResistance(), view.moveSpeedAttr());
}
```

This DELETES the lying ":456-459" comment ("enchant is corrected on the authoritative pass"). The call site at :363 becomes `preAttackerState(attackerId, attackerView, verdict)` (unchanged shape).

### 3.8 `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java`

(a) **Recompute capture (:272)** — consume the stamp:

```java
// The era-moment yaw: the extras are directed along the attacker's facing,
// which vanilla read synchronously inside attack() at click-flush. A
// fast-path REGISTERED hit recomputes here 1–2 ticks later, so the live
// location yaw has drifted with the attacker's mouse — consume the
// registration stamp instead (the SprintVerdict's exact sibling). Null
// stamp (Vanilla-source mint, packetless attacker) keeps the live capture,
// which is the click-flush yaw for both of those cases.
EntityState attackerState = adoptRegistrationYaw(
        EntityStates.capture(attacker, sprinting), tx.context().attackerYaw());
```

(b) New package-private static beside the other shared helpers:

```java
/** The registration-time yaw stamp folded over the live capture; null = no stamp, live wins. */
static EntityState adoptRegistrationYaw(EntityState captured, Float registrationYaw) {
    return registrationYaw == null ? captured : captured.withYaw(registrationYaw);
}
```

(c) **`mint(...)` (:553-562)** — carry the stamp through the blocked-knock redelivery so the fresh context stays honest: add a trailing `Float attackerYaw` parameter, pass it as the 9th `HitContext` arg; the one caller (`deliverBlockedKnock` :505) passes `original.context().attackerYaw()`.

Do NOT touch `applyAttackerObligations`/`heldKnockbackLevel` (:404-449, :653-663 — F1's territory; the live enchant gate there is region-thread-legal and era-correct as a bonus gate).

### 3.9 `kernel/src/main/java/me/vexmc/mental/kernel/math/KnockbackEngine.java`

**Untouched** (verified: extras already consume `attacker.knockbackEnchantLevel()` at :215 and `attacker.yaw()` at :224; `servoFactor` shares the same `EntityState`, so the servo solve sees the stamped yaw automatically).

## 4. Threading analysis

- `kbEnchantLevel`: written once per tick by the session's owning region thread inside `buildView` (a live equipment read that is legal exactly there), frozen into the immutable `PlayerView`, published via the session's `AtomicReference.set`. Netty reads only the published record — the identical lifecycle as `moveSpeedAttr`/`sprinting`. No new mutable state anywhere.
- `attackerYaw`: a final component of the immutable `HitContext`, written on the netty thread at registration (the source, `domain.lastYaw`, is a volatile written by `PacketTap` on the same connection's event loop, so program order guarantees it is the pre-ATTACK click-flush value), read on the victim's region thread through the transaction the `scheduling.runOn` handoff / `submitFromWire` carries — the same safe-publication edges `SprintVerdict` and `compensationY` already ride.
- `EntityState.withYaw` / `adoptRegistrationYaw` / static `preAttackerState`: pure functions over immutable values.
- Single-writer domains intact: no D1 code gains a live-entity read; the DeliveryDesk remains the sole velocity-event/journal writer (untouched).

## 5. Era / zero-touch analysis

- **No config change**: no new knobs, `parse(empty) == LEGACY_17` untouched.
- **Byte-identical defaults**: every old-arity `PlayerView`/`HitContext` construction resolves `kbEnchantLevel = 0` / `attackerYaw = null` — exactly today's values/behavior. Unenchanted weapons: `view.kbEnchantLevel() == 0` ⇒ the extras term is bit-identical. Null yaw stamp ⇒ live capture, bit-identical.
- **Zero-touch**: `fastPath` off ⇒ no Melee contexts are minted with a yaw (DamageRouter's Vanilla mints pass null) ⇒ everything live, byte-identical. The `SessionService` enchant read is observation-only (always-on infrastructure reads live state and publishes frozen values; it mutates nothing).
- **Era**: era read the enchant and the yaw live INSIDE `attack()` at click-flush processing. The yaw stamp restores exactly that instant for recomputed hits. The enchant freeze is ≤1 tick stale on a swap-then-click within one tick — the only divergence, the exact mirror of the accepted sprint-published-view staleness (and narrower: weapon-swap-click is not a timing technique the way w-tap is, the REGISTERED recompute still reads live enchant so anticheat deployments are era-exact even on swap, and it self-heals next tick). The client-side technique contract (0.6 self-multiplier, w-tap, jump-resets) is untouched.

## 6. Tests (all expectations hand-computed)

**Kernel — `KnockbackEngineTest` (add one case; ALL existing cases must stay green unchanged):**
- `sprintKnockbackTwoShipsThreeBonusLevelsAlongYaw`: `computed(attacker(0, 0, 0.0f, true, 2), victim(0, 2, 0, 0, 0, 0), DEFAULTS, null)`. Arithmetic: deltaX=0, deltaZ=0−2=−2, mag=2, push=0.4 ⇒ pushZ=−0.4 ⇒ base z = 0 − (−0.4) = 0.4, x = 0; y = 0×0.5 + 0.4 = 0.4 (≤ vertical limit 0.4, no clamp). Extras: sprintLevels = sprintFactor = 1.0, enchantLevels = 2 ⇒ horizontalBonus = (1 + 2) × 0.5 = **1.5**; yaw 0 ⇒ x += −sin0×1.5 = 0, z += cos0×1.5 ⇒ z = **1.9**; y += 0.1 ⇒ **0.5**. Assert `(0.0, 0.5, 1.9)` at EPSILON 1e-9 — the KB-II-sprint pin the finding names (Mental currently pre-sends 0.9 here).

**Kernel — new `kernel/src/test/java/me/vexmc/mental/kernel/model/EntityStateTest.java`:**
- `withYawReplacesOnlyTheYaw`: `new EntityState(1.5, 64.0, -2.25, 90.0f, 0.1, -0.2, 0.3, true, true, 2, 0.25, 0.13).withYaw(15.5f)` ⇒ yaw 15.5f, all 11 other accessors equal the originals (assert each).
- `withYawOnTheElevenArgArityKeepsTheSentinel`: 11-arg construction `.withYaw(1f).moveSpeedAttr() == EntityState.MOVE_SPEED_UNAVAILABLE`.

**Kernel — new `kernel/src/test/java/me/vexmc/mental/kernel/model/HitContextTest.java`:**
- `preYawArityDefaultsTheRegistrationYawToNull`: 8-arg constructor ⇒ `attackerYaw() == null`; 9-arg with `33.5f` ⇒ `33.5f` (and the other 8 components carried 1:1).

**Kernel — `PlayerViewTest` (add one case; existing two stay green):**
- `preEnchantArityDefaultsKbEnchantToZero`: the existing 18-arg `view(...)` helper ⇒ `kbEnchantLevel() == 0`; a 27-arg canonical construction with `kbEnchantLevel = 2` ⇒ `2`.

**Core — `ViewBuilderTest` (add one case; existing two stay green):**
- `enchantOverloadCarriesTheLevelAndOldOverloadsDefaultZero`: new full overload with `kbEnchantLevel = 2` ⇒ `view.kbEnchantLevel() == 2`; the existing 18-arg and precision overloads ⇒ `0`.

**Core — new `core/src/test/java/me/vexmc/mental/v5/feature/delivery/PreAttackerStateTest.java`** (the parity seam, the `HitRegistrationPacingTest` sibling):
- `preSendAttackerStateFreezesTheViewEnchant`: `PlayerView` via the 27-arg canonical (any coherent values; `kbEnchantLevel = 2`, `knockbackResistance = 0.25`, `moveSpeedAttr = 0.13`, `grounded = true`), `SprintVerdict(true, true, new TickStamp(5))`; `HitRegistrationUnit.preAttackerState(1.0, 65.0, -3.0, 47.5f, view, verdict)` ⇒ EntityState `(x 1.0, y 65.0, z −3.0, yaw 47.5f, vx/vy/vz 0, grounded true, sprinting true, knockbackEnchantLevel 2, knockbackResistance 0.25, moveSpeedAttr 0.13)`.
- `preEnchantViewShipsEnchantBlind`: view via the 26-arg (pre-freeze) arity ⇒ `knockbackEnchantLevel() == 0` (the documented fallback = pre-fix behavior).

**Core — new `core/src/test/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnitYawTest.java`:**
- `nullStampKeepsTheLiveCapture`: `assertSame(captured, KnockbackUnit.adoptRegistrationYaw(captured, null))`.
- `stampOverridesTheLiveYaw`: captured with yaw 90f, stamp 15.5f ⇒ yaw 15.5f, every other field equal.

**Pins that must NOT change:** every existing `KnockbackEngineTest`/`KnockbackEngineServoTest` case (engine untouched), `ViewBuilderTest` both cases, `PlayerViewTest` both cases, `HitTransactionTest`, `DeliveryDeskTest`, `DamageRouterTest`, `HitRegistrationPacingTest`, `FeedbackGateTest`, `HurtYawTest`, tester `KnockbackSuite`/`ProfileSuite` expectations (packetless FakePlayer attackers have no domain ⇒ null yaw stamp ⇒ live capture, and their region-path enchant read is the live one — both byte-identical).

## 7. Verification

- `./gradlew build` — all unit tests green including the seven new/extended cases; japicmp green (constructor/method additions only, old arities preserved explicitly); kernel-Bukkit-free assertion green (`Float`/`int` are pure JDK); the D-8 stub-descriptor gate green (no post-Java-8 type in any new descriptor).
- `./gradlew integrationTestMatrix` (or `scripts/integration-matrix.sh` after a fresh build — the script does NOT build; stale-jar trap) — must stay green UNCHANGED: FakePlayers are packetless and never traverse the netty pre-send (live-server-testing limit), so no suite can drive path A/B end-to-end; the parity is pinned at the pure seams above. Honor the nonce rule — never trust the banner.
- No tester-suite additions: pre-send-vs-recompute enchant parity for a WIRED attacker is only drivable by the owner's SimpleBoxer setup (out-of-band); note this in the commit body.
- Commit: `fix(delivery): freeze the enchant level and click-flush yaw into the era-moment stamp` with a prose body citing both finding ids, ending `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## 8. Risks + rollback

- **PlayerView/HitContext canonical growth**: all positional constructions verified (grep) to use preserved old arities (kernel/core tests, ViewBuilder) — compile-clean. Risk: a parallel-batch fix adding ITS own component to the same records (F9 journal enrichment may want `attackerYaw`) — trailing-component collisions merge textually; coordinate ordering.
- **One-tick enchant staleness on swap-click** (pre-send only): bounded at ±0.5×Δlevel for one hit, self-healing; documented in the constructor javadoc.
- **Per-tick equipment read cost**: one `getItemInMainHand` copy + enchant-map lookup per player-tick — lighter than the `getActivePotionEffects` scan already in `buildView`.
- **Rollback**: single revert — all changes are additive constructors/methods plus three localized call-site edits; no config, no wire-format, no journal-shape change.

## Files touched

- `kernel/src/main/java/me/vexmc/mental/kernel/model/PlayerView.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/model/HitContext.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/model/EntityState.java`
- `core/src/main/java/me/vexmc/mental/v5/EntityStates.java`
- `core/src/main/java/me/vexmc/mental/v5/session/ViewBuilder.java`
- `core/src/main/java/me/vexmc/mental/v5/session/SessionService.java`
- `core/src/main/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationUnit.java`
- `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/math/KnockbackEngineTest.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/model/EntityStateTest.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/model/HitContextTest.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/model/PlayerViewTest.java`
- `core/src/test/java/me/vexmc/mental/v5/session/ViewBuilderTest.java`
- `core/src/test/java/me/vexmc/mental/v5/feature/delivery/PreAttackerStateTest.java`
- `core/src/test/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnitYawTest.java`

## Cross-lane conflicts (integrator notes)

F2 (direct overlap: HitRegistrationUnit.plan() — my HitContext mint at :328-330 sits inside the region F2 rewrites for outcome pinning; whichever lands second must re-apply the 9th constructor arg). F1 (same files, different methods: HitRegistrationUnit.sprintVerdict and KnockbackUnit.applyAttackerObligations/heldKnockbackLevel — import-block and adjacent-line merge friction only; I deliberately do not touch the obligations' live-enchant gate F1 owns). F8 (SessionService.buildView — the PositionRing first-tick seed touches the measuredVx read at :518-520, immediately above my kbEnchantLevel read insertion; textual merge care). F9 (kernel HitContext/JournalEntry — if journal enrichment captures context fields it may want the new attackerYaw component and will collide on the record header; coordinate trailing-component order).
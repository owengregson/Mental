# F9 — Journal capture enrichment: the discriminating measurement as a permanent debug capability

## 1. Problem

The three-round weak-KB investigation had to be reconstructed from ad-hoc probes because the delivery journal (`kernel/src/main/java/me/vexmc/mental/kernel/model/JournalEntry.java:28`) records only `shipped/wireCarried/suppressReason/paceFactor/comboFactor` — it cannot distinguish a verdict-flip bug (sprint read wrong at registration) from era geometry (wrong ring positions / yaw fed to `base()`) from an eaten delivery (pre-send suppressed, resolve pass-through). The desk (`kernel/.../delivery/DeliveryDesk.java:338` `record(...)`) is the sole journal writer, and there is currently no operator surface that streams a per-hit journal line: the `DebugLog` two-sink model (console at `MentalPluginV5.java:234`, chat via `core/.../debug/PlayerDebugSink.java`) exists but nothing emits delivery-journal lines into it.

## 2. Design decision

**Inventory result (extend, do not duplicate):** `JournalEntry` already has the additive-arity-constructor growth pattern (its own javadoc, lines 32–51); `HitTransaction` already has the mutable per-hit stamp pattern (`paceFactor`/`comboFactor`, lines 46–90) written at exactly the two compute sites (netty `plan()`, region EDBEE) and copied into the entry by the desk; `PlayerDebugSink` + the `DebugMenu` subscribe tile (slot 40) + the console sink are the complete operator surface — they only need a new `DebugCategory` channel fed with formatted lines.

**Shape chosen:**
- One new nullable `JournalEntry` component: a nested immutable `Capture` record grouping all new fields. Canonical constructor grows by ONE component; every existing arity is kept as an explicit delegating constructor (`capture = null`) — kernel stays additive (japicmp-safe), tester accessors untouched.
- Dispositions and resolutions are **String namespaces, not enums** — that is the merge contract with F2/F4: they add new string VALUES at the sites they create, never a type change. (Precedent: `suppressReason` is already a string namespace.)
- Stamps live on `HitTransaction` (the D-6 `paceFactor` precedent), **not** on `HitContext` — deliberately, because F5/F6 grows `HitContext`'s canonical constructor in a parallel lane and a second canonical-arity change here would collide textually and semantically. `verdict.sprinting`/`verdict.fresh` need no stamp at all: they already ride `HitContext.sprint()` and the desk copies them at record time.
- The desk gains an optional `JournalObserver` (new kernel functional interface, pure JDK) invoked on every journal append; core installs a `JournalCapture` formatter that gates on a new `DebugCategory.JOURNAL` channel and emits one greppable line through the existing `DebugLog` → console sink (log file) + `PlayerDebugSink` (subscribed admins). The desk stays the sole journal writer; the observer only *reads* the entry it just wrote.
- Geometry records **what was actually fed to the engine** (the `EntityState` pair at the compute site), so when F5/F6 changes which yaw/positions feed `base()`, F9 automatically records the new truth — no coordination needed beyond both touching the same methods.
- `shipped h,v` are NOT stored: `h = hypot(x,z)`, `v = y` are derived by the formatter from the existing `shipped` vector.
- Enabled feature families are appended at **format time** from the live `Snapshot` (they change only on config swap; per-hit storage would be waste). The effective profile name IS stored per hit (it varies per victim/world).

## 3. Exact changes

### 3a. NEW `kernel/src/main/java/me/vexmc/mental/kernel/model/HitGeometry.java`

```java
package me.vexmc.mental.kernel.model;

/**
 * The horizontal geometry actually fed to the knockback engine's base() for one
 * hit — the ring/live positions and the attacker yaw the direction was computed
 * from. Journal attribution only (F9): lets a live capture separate a wrong-
 * geometry knock from a verdict flip or an eaten delivery. All components are
 * Java-8 primitives, so the record crosses the tester boundary without a
 * downgraded stub type (D-8).
 */
public record HitGeometry(double attackerX, double attackerZ, float attackerYaw,
                          double victimX, double victimZ) {
}
```

### 3b. `kernel/src/main/java/me/vexmc/mental/kernel/model/JournalEntry.java`

Add nested record + one canonical component. New canonical:

```java
public record JournalEntry(HitId id, HitSource source, KnockbackVector shipped,
                           boolean wireCarried, String suppressReason, TickStamp at,
                           double paceFactor, double comboFactor, Capture capture) {

    /**
     * The F9 per-hit delivery capture — the investigation's discriminating
     * measurement as a permanent journal field. Null on entries built through a
     * pre-F9 arity (additive growth) and on sources that never stamp (projectiles).
     *
     * <p>{@code presend} and {@code resolution} are open STRING namespaces (the
     * suppressReason precedent), so parallel lanes extend them with new values,
     * never a type change: F2 adds its pre-send outcome pins (e.g.
     * "unsendable-downgrade"); F4 adds its supersede-passthrough resolutions.</p>
     *
     * <p>presend values (stamped at registration): "wire", "pinned", "paced-out",
     * "suppressed:anticheat", "suppressed:resistance-roll",
     * "suppressed:frozen-immune", "no-view", "off"; null = the region path (no
     * fast-path pre-send existed — the formatter prints "none").</p>
     *
     * <p>resolution values (chosen by the desk branch that journaled):
     * "ship-valve", "ship-corrected", "ship-pinned", "ship-formula", "ensured",
     * "cancel", "superseded", "drop", "sweep", "late-resolve".</p>
     */
    public record Capture(boolean sprinting, Boolean sprintFresh, String presend,
                          String resolution, HitGeometry geometry, String profile) {
    }
```

Keep the existing 6-arg and 7-arg delegating constructors AND add an explicit 8-arg constructor (the old canonical) delegating with `capture = null`, javadoc'd as additive growth exactly like lines 32–51 today. `Boolean sprintFresh` is `java.lang.Boolean` (Java 8 — D-8 safe), nullable = "no wire view existed", mirroring `SprintVerdict.fresh()`.

### 3c. `kernel/src/main/java/me/vexmc/mental/kernel/delivery/HitTransaction.java`

Three new stamp fields + accessors, inserted after `comboFactor` (lines 47/83–90 pattern, same javadoc style — "journal attribution, stamped by the compute/registration site, copied by the desk"):

```java
    private String presend;          // pre-send disposition (F9 namespace), null = region path
    private HitGeometry geometry;    // the base() geometry actually consumed, null until a compute stamped it
    private String profileName;      // the effective victim profile at compute time, null until stamped

    public String presend() { return presend; }
    public void presend(String disposition) { this.presend = disposition; }
    public HitGeometry geometry() { return geometry; }
    public void geometry(HitGeometry geometry) { this.geometry = geometry; }
    public String profileName() { return profileName; }
    public void profileName(String name) { this.profileName = name; }
```

Import `me.vexmc.mental.kernel.model.HitGeometry`.

### 3d. NEW `kernel/src/main/java/me/vexmc/mental/kernel/delivery/JournalObserver.java`

```java
package me.vexmc.mental.kernel.delivery;

import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.JournalEntry;

/**
 * Read-only tap on the desk's journal appends (F9): the desk stays the SOLE
 * journal writer; the observer merely sees each entry the instant it is
 * appended, with the hit's compute-once context for attacker/victim identity.
 * Invoked on the desk's owning thread (every journaling method is owner-called).
 * The desk guards the call — a throwing observer can never break delivery.
 */
@FunctionalInterface
public interface JournalObserver {

    /** The no-op default every pre-F9 construction gets. */
    JournalObserver NONE = (context, entry) -> { };

    void journaled(HitContext context, JournalEntry entry);
}
```

### 3e. `kernel/src/main/java/me/vexmc/mental/kernel/delivery/DeliveryDesk.java`

1. New field `private final JournalObserver observer;`. New 4-arg constructor `DeliveryDesk(int victimEntityId, TickClock clock, int journalCapacity, JournalObserver observer)`; the existing 3-arg constructor delegates with `JournalObserver.NONE` (additive).
2. `private void record(HitTransaction tx, KnockbackVector shipped, boolean wireCarried, String reason)` gains a fifth param `String resolution`. Body becomes:
```java
    private void record(HitTransaction tx, KnockbackVector shipped, boolean wireCarried,
                        String reason, String resolution) {
        appendJournal(tx.context(), new JournalEntry(
                tx.context().id(), tx.context().source(), shipped, wireCarried, reason,
                clock.current(), tx.paceFactor(), tx.comboFactor(), captureOf(tx, resolution)));
        tx.recorded();
    }

    /** Copies the transaction's F9 stamps + its context's sprint verdict into the entry's capture. */
    private static JournalEntry.Capture captureOf(HitTransaction tx, String resolution) {
        SprintVerdict sprint = tx.context().sprint();
        return new JournalEntry.Capture(
                sprint != null && sprint.sprinting(),
                sprint == null ? null : sprint.fresh(),
                tx.presend(), resolution, tx.geometry(), tx.profileName());
    }
```
(import `me.vexmc.mental.kernel.model.SprintVerdict`.)
3. `appendJournal` becomes `appendJournal(HitContext context, JournalEntry entry)`; after the existing add/evict, notify:
```java
        try {
            observer.journaled(context, entry);
        } catch (Throwable failure) {
            // A debug tap must never break the delivery core.
        }
```
4. Resolution tags per existing site (the F4 merge contract: every record/appendJournal site MUST pass a tag; F4's new sites pass their own new values):
   - `resolve` case 2 (`resistance-roll`): `"cancel"`.
   - case 3 (unmodified PRE_SENT → SHIP_AND_ARM_VALVE): `"ship-valve"`.
   - case 4 (third-party modified): `"ship-corrected"`.
   - case 5 (PINNED): `"ship-pinned"`.
   - live REGISTERED/PLANNED branch: `"ship-formula"`.
   - F6-hardening terminal branch (line 222): build via `appendJournal(tx.context(), new JournalEntry(..., captureOf(tx, "late-resolve")))`.
   - `withdrawSuperseded` (line 81): `"superseded"`.
   - `replacePending` supersede (line 316): `"superseded"`.
   - `sweep` LIVE time-drop (line 274): `"drop"`; already-resolved sweep record (line 281): `"sweep"`.
   - `ensure` (line 291): `"ensured"`.
   - `journalDrop` (line 93): build the entry with `captureOf(tx, "drop")` and call `appendJournal(tx.context(), ...)`.

### 3f. `core/src/main/java/me/vexmc/mental/v5/CombatSession.java`

Constructor (line 87) gains a `JournalObserver journalObserver` param, passed straight to the new 4-arg `DeliveryDesk` ctor. Keep the existing 4-arg `CombatSession` constructor delegating with `JournalObserver.NONE` (tests construct it). Import `me.vexmc.mental.kernel.delivery.JournalObserver`.

### 3g. `core/src/main/java/me/vexmc/mental/v5/session/SessionService.java`

New final field `private final JournalObserver journalObserver;` appended to the constructor param list (after `comboEvents`); `join(...)` (line 245) passes it into `new CombatSession(gravity, entityId, clock, JOURNAL_CAPACITY, journalObserver)`.

### 3h. NEW `core/src/main/java/me/vexmc/mental/v5/debug/JournalCapture.java`

```java
package me.vexmc.mental.v5.debug;

import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Supplier;
import me.vexmc.mental.platform.debug.DebugLog;
import me.vexmc.mental.kernel.delivery.JournalObserver;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitGeometry;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;

public final class JournalCapture implements JournalObserver {

    private final DebugLog.Scoped debug;
    private final Supplier<Snapshot> snapshot;

    public JournalCapture(DebugLog.Scoped debug, Supplier<Snapshot> snapshot) { ... }

    @Override
    public void journaled(HitContext context, JournalEntry entry) {
        // Zero-touch: channel off ⇒ one volatile read + set-contains, nothing else.
        if (!debug.active()) {
            return;
        }
        debug.log(() -> format(context, entry, snapshot.get()));
    }

    /** Package-private, pure and Snapshot-injected so the unit pin needs no live plugin. */
    static String format(HitContext context, JournalEntry entry, Snapshot snapshot) { ... }
}
```

`format` builds ONE line, `Locale.ROOT`, exact template (each `key=value` token space-separated, `-` for absent so every line greps uniformly):

```
hit=<id.value()> src=<src> out=<capture.resolution|-> presend=<capture.presend, null→"none", no capture→"-"> reason=<suppressReason|-> ship=<h=%.3f v=%.3f | -> wire=<t|f> sprint=<t|f|-> fresh=<t|f|-> pace=<%.2f> combo=<%.2f> geom=<a(%.2f,%.2f yaw %.1f) v(%.2f,%.2f) | -> profile=<capture.profile|-> families=<CSV|-> attacker=<uuid8|-> victim=<uuid8|-> tick=<at.value()|->
```

Details: `src` = `source.getClass().getSimpleName()` lowercased, with `Vanilla` rendered `vanilla(<damageCause>)`. `ship`: `h = Math.hypot(shipped.x(), shipped.z())`, `v = shipped.y()`; `-` when `shipped == null`. `sprint`/`fresh` come from `entry.capture()` (both `-` when capture is null; `fresh` `-` when `sprintFresh == null`). `families` iterates `Feature.values()` collecting `snapshot.enabled(f)` names joined by `,` (format-time, debug-on only; documented as capture-time truth — a mid-capture config swap is the operator's own action). `uuid8` = first 8 chars of `UUID.toString()` — never a live `getName()` (netty/Folia discipline; the desk's owning thread is safe but `journalDrop` can arrive via a retire callback, and UUIDs are unconditionally safe + greppable). `tick` prints `-` when `!at.known()`.

### 3i. `platform/src/main/java/me/vexmc/mental/platform/debug/DebugCategory.java`

Add constant `JOURNAL` (after `KNOCKBACK`, keeping delivery channels adjacent). `key` derives automatically (`"journal"`); `byKey` picks it up with no other change.

### 3j. `core/src/main/java/me/vexmc/mental/v5/MentalPluginV5.java`

Before the `SessionService` construction (line 281): `JournalCapture journalCapture = new JournalCapture(debug.scoped(DebugCategory.JOURNAL), this::snapshot);` and append it to the `SessionService` ctor args. (Both `debug` — field-initialized at line 143 — and `this::snapshot` — assigned line 228 — are live at that point.)

### 3k. `core/src/main/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationUnit.java` (stamp sites, inside `plan()`)

1. Split the combined early return (line 334) so each absence stamps its disposition:
```java
            if (playerVictim == null || victimId == null || attackerView == null || victimView == null) {
                tx.presend("no-view");
                return tx;
            }
            if (!settings.preSendFeedback()) {
                tx.presend("off");
                return tx;
            }
            if (victimView.damageImmune()) {
                tx.presend("suppressed:frozen-immune");
                return tx;
            }
            tx.profileName(victimView.profile().name());
```
(`PlayerView.profile()` is already read two lines later at line 339 — same frozen view, netty-safe.)
2. `suppressed != null` branch: after computing `suppressed` (line 341), add `if (suppressed != null) { tx.presend("suppressed:" + suppressed); }` — yields `suppressed:anticheat` / `suppressed:resistance-roll`.
3. Inside the `suppressed == null` branch, immediately after `preAttacker`/`preVictim` are built (lines 363–364): `tx.geometry(new HitGeometry(preAttacker.x(), preAttacker.z(), preAttacker.yaw(), preVictim.x(), preVictim.z()));` — these are the exact `EntityState`s handed to `KnockbackEngine.computePaced`, so the journal records precisely what `base()` consumed (and will automatically record F5/F6's registration-stamped yaw once that lane changes what feeds these states).
4. After the `velocityShips` gate (line 401): 
```java
            if (velocityShips) {
                tx.presend(victimHasWire ? "wire" : "pinned");
            } else if (vector != null) {
                tx.presend("paced-out"); // the FeedbackGate window ate the velocity component
            }
```
(F2 merge contract: F2's UNSENDABLE-downgrade adds its own `tx.presend("unsendable-downgrade")` at the site it creates; the namespace is open.)

### 3l. `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java` (stamp sites)

1. In `onEntityDamageByEntity`, immediately after the D-6 stamps (lines 299–300):
```java
        tx.profileName(profile.name());
        tx.geometry(new HitGeometry(attackerState.x(), attackerState.z(), attackerState.yaw(),
                victimState.x(), victimState.z()));
```
(`attackerState`/`victimState` are the live-capture `EntityState`s fed to `computePaced` — again exactly what `base()` consumed. Region-path `Vanilla(ENTITY_ATTACK)` hits get geometry+profile here too; their `presend` stays null → formatter prints `none`. The PRE_SENT/PINNED adopt branch returns before this — its stamps were written at `plan()`, which is correct: the adopted vector was computed there.)
2. In `deliverBlockedKnock` (after `mint(...)`, line 505): carry the original's F9 stamps like the existing factor carry:
```java
        fresh.presend(original.presend());
        fresh.geometry(original.geometry());
        fresh.profileName(original.profileName());
```
Import `me.vexmc.mental.kernel.model.HitGeometry`.

### 3m. `core/src/main/java/me/vexmc/mental/v5/gui/DebugMenu.java`

- **`Map.of` has a 10-pair ceiling and ICONS is at exactly 10** — switching to `Map.ofEntries(Map.entry(...), ...)` is REQUIRED, adding `Map.entry(DebugCategory.JOURNAL, "BOOK")` (BOOK exists across the whole material range; WRITABLE_BOOK is taken by CONFIG).
- Extend `CATEGORY_SLOTS` to `{20, 21, 22, 23, 24, 29, 30, 31, 32, 33, 38}` (row-4 slot 38; the subscribe tile keeps 40). No other change — the draw loop already iterates `values().length`.

### 3n. `core/src/main/resources/config.yml`

Under `debug.categories:` add (with the section's comment style):
```yaml
    # Per-hit delivery journal lines: verdict, pre-send disposition, resolve
    # outcome, shipped h/v, engine geometry, profile — one greppable line per hit.
    journal: false
```
`DebugSettings`/`SnapshotParser` need NO change (categories are freeform string keys; `DebugCategory.byKey` resolves the new one). `PlayerDebugSink` needs NO change — it is the operator stream already.

## 4. Threading analysis

- **Stamps (`presend`/`geometry`/`profileName`)** are written at exactly the two code points that already write `paceFactor`/`comboFactor`: the registering netty thread inside `plan()` (all writes complete before `submitFromWire`'s `AtomicReference.set` / the `Scheduling.runOn` enqueue — both publish with happens-before), and the victim's owning region thread inside the EDBEE handler. One writer at a time by the existing hand-off discipline; no new concurrency.
- **`JournalEntry.Capture` / `HitGeometry`** are immutable records crossing threads only by publication.
- **Observer invocation** happens only inside `appendJournal`, i.e. only from journaling desk methods — all owner-called today (netty's `submitFromWire` never journals). `journalDrop` via the retire callback already wrote the journal deque on that thread pre-F9, so the single-writer envelope is unchanged; the observer adds no desk state. Downstream, `DebugLog.log` supplies to thread-safe sinks (the console logger; `PlayerDebugSink`'s concurrent set + per-recipient `Scheduling.runOn` hop). The formatter reads only immutable values + one `Supplier<Snapshot>` atomic read; UUIDs, never live entities.
- **Failure isolation**: the desk swallows observer throwables, and `DebugLog.log` already swallows supplier throwables — a formatting bug can never eat a hit (the netty-fast-path corollary).

## 5. Era / zero-touch analysis

- No math, no packet, no event flow changes: every edit is a field copy of an already-computed value, or debug-off-gated formatting. `parse(empty) == LEGACY_17` untouched (no profile-schema or settings-record change; `journal: false` is a comment-documented default of an already-freeform string set whose absence means inactive).
- With `debug.enabled: false` (the default) or the `journal` channel off: per journaled hit the added cost is three reference-field writes, one 5-field `HitGeometry` allocation, one `Capture` allocation inside `record(...)`, and one `debug.active()` volatile read + early return in the observer — on a path that already allocates an `EntityState` pair, a `HitContext`, a `KnockbackVector`, and the `JournalEntry` itself per hit. Nothing runs for disabled features (no hit → no journal write), so zero-touch holds by construction.
- The technique contract (0.6 self-multiplier, w-tap, jump-resets) is untouched — no delivery value changes.

## 6. Tests

### `kernel/src/test/java/me/vexmc/mental/kernel/delivery/DeliveryDeskTest.java` — new cases (existing pins MUST NOT change: `"resistance-roll"`, `"superseded"`, `"blocked-redeliver -> 7"`, `"victim-retired"`, `"no-velocity-event"`, all `shipped()`/`wireCarried()`/journal-size assertions, and every `KnockbackEngine`/`DeliveryScenariosTest` pin — no math was touched):

1. `legacyAritiesBuildWithNullCapture`: `new JournalEntry(id, src, vec, true, null, stamp)` and the 7-arg and 8-arg forms all give `capture() == null`, `paceFactor() == 1.0` where defaulted (additive-growth pin, mirrors the existing pattern).
2. `observerSeesEveryAppendInOrderWithTheContext`: desk built with a recording observer (list of `(context, entry)`); drive submit→await→resolve(ship) then `journalDrop` → observer received exactly 2 callbacks, `entry` same instance as `journal().get(i)`, `context.id()` matches, order preserved.
3. `captureCopiesStampsAndVerdict`: context with `SprintVerdict(true, Boolean.TRUE, stamp)`; `tx.presend("paced-out"); tx.profileName("legacy-1.7"); tx.geometry(new HitGeometry(1.5, -2.25, 90f, 3.0, 4.5));` submit+await+resolve → capture: `sprinting=true`, `sprintFresh=TRUE`, `presend="paced-out"`, `resolution="ship-formula"` (REGISTERED live branch), geometry `equals` the stamped record, `profile="legacy-1.7"`.
4. `resolutionTagsPerBranch` (may be several small cases, hand-derived from the desk branches): unmodified PRE_SENT → `"ship-valve"`; api-modified PRE_SENT → `"ship-corrected"`; PINNED → `"ship-pinned"`; null-formula resistance → `"cancel"`; second submit supersede → `"superseded"`; packetless sweep age-2 → `"drop"`; `ensure` → `"ensured"`; the F6 terminal-resolve path → `"late-resolve"`; `withdrawSuperseded` → `"superseded"`. Also: null `SprintVerdict` in the context → `sprinting=false`, `sprintFresh=null` (no NPE).
5. `observerThrowNeverBreaksDelivery`: observer that throws → resolve still returns the SHIP directive and the journal still holds the entry.

### NEW `core/src/test/java/me/vexmc/mental/v5/debug/JournalCaptureTest.java`

Pin the exact line via the package-private pure `format(context, entry, snapshot)`. Hand-computed expectation — shipped `(0.3, 0.36, 0.4)`: `h = sqrt(0.3² + 0.4²) = sqrt(0.09 + 0.16) = sqrt(0.25) = 0.5` → `"h=0.500 v=0.360"`. Geometry `(1.5, -2.25, 90f, 3.0, 4.5)` → `"geom=a(1.50,-2.25 yaw 90.0) v(3.00,4.50)"`. Use a `Snapshot` stub/fixture with two features enabled and pin the `families=` CSV; a second case pins the all-absent line (`capture == null`, `shipped == null` → `out=- presend=- ship=- geom=- profile=-` etc., `sprint=- fresh=-`). If constructing a `Snapshot` in a plain unit test is impractical (check `core/src/test/java/me/vexmc/mental/v5/config` for an existing fixture — `SnapshotParser` parse of an empty YAML is the established path), overload `format` to take the families string and pin the joiner separately.

### Tester

No suite changes required: every tester `JournalEntry` usage is accessor-only (`shipped()`, `suppressReason()`, `journal().size()` — verified across KnockbackSuite/ComboSuite/BlockingSuite/ProfileSuite/FoliaCombatSmoke); no suite constructs a `JournalEntry` or switches on its arity, so the shape assumption holds.

## 7. Verification

- `./gradlew build` green: kernel unit tests (new + existing pins), japicmp/kernel-additive gate (old `JournalEntry` constructors kept explicitly; `DeliveryDesk` 3-arg ctor kept; only additions elsewhere), kernel-Bukkit-free check (all new kernel types are pure JDK + kernel-model imports).
- `./gradlew integrationTestMatrix` (or `scripts/integration-matrix.sh` — remember it does NOT rebuild the jar; build first): existing suites must pass unchanged — the honest nonce+PASS check, not the banner.
- Manual smoke (owner's flow): GUI → Debug → master on + `journal` channel on + "Stream to my chat" → land one w-tap trade → one line per hit in the server log (greppable by `hit=`/`out=`/`presend=`) and mirrored to chat. FakePlayer limitation (live-server-testing): packetless victims exercise the `pinned`/`drop` dispositions, real clients the `wire`/`ship-valve` ones — the journal line itself is what the manual capture reads.

## 8. Risks + rollback

- **`Map.of` 10-pair ceiling in `DebugMenu.ICONS`** — compile error if the `Map.ofEntries` switch is missed; called out in 3m.
- **Merge conflicts (textual, same methods)**: F2 rewrites `plan()`'s outcome pinning and `HitTransaction` states; F4 rewrites `resolve`/`drainWire`; F1 touches `sprintVerdict`/`KnockbackUnit` obligations; F5/F6 touches the same `EntityState`-building lines. Contracts: F9's `presend`/`resolution` are open string namespaces — F2/F4 add values at the sites they create (every `record`/`appendJournal` call MUST pass a resolution tag; the 5-arg `record` signature enforces it at compile time). F9 deliberately does NOT touch `HitContext`, so F5/F6's canonical-constructor growth cannot collide; F9's geometry reads the `EntityState`s actually fed to the engine, so it records F5/F6's improved inputs automatically.
- **Journal-line volume**: one line per journaled hit when the channel is on — bounded by CPS and by the operator having explicitly enabled a debug channel; same exposure as the existing servo debug line.
- **Rollback**: revert the commit. Everything is additive (delegating constructors, null-defaulted fields, a no-op observer default), so a partial revert of the core wiring alone (drop the `JournalCapture` arg → `NONE`) also restores pre-F9 behavior byte-for-byte.

Commit: `feat(debug): per-hit delivery capture in the journal + JOURNAL debug channel` with a prose body citing the weak-KB findings doc and the one-capture triage purpose.

## Files touched

- `kernel/src/main/java/me/vexmc/mental/kernel/model/JournalEntry.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/model/HitGeometry.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/delivery/JournalObserver.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/delivery/HitTransaction.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/delivery/DeliveryDesk.java`
- `core/src/main/java/me/vexmc/mental/v5/CombatSession.java`
- `core/src/main/java/me/vexmc/mental/v5/session/SessionService.java`
- `core/src/main/java/me/vexmc/mental/v5/MentalPluginV5.java`
- `core/src/main/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationUnit.java`
- `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java`
- `core/src/main/java/me/vexmc/mental/v5/debug/JournalCapture.java`
- `platform/src/main/java/me/vexmc/mental/platform/debug/DebugCategory.java`
- `core/src/main/java/me/vexmc/mental/v5/gui/DebugMenu.java`
- `core/src/main/resources/config.yml`
- `kernel/src/test/java/me/vexmc/mental/kernel/delivery/DeliveryDeskTest.java`
- `core/src/test/java/me/vexmc/mental/v5/debug/JournalCaptureTest.java`

## Cross-lane conflicts (integrator notes)

F2 (HitRegistrationUnit.plan + HitTransaction — F9 stamps presend at the exact gates F2 pins; F2 extends the presend string namespace with e.g. "unsendable-downgrade"); F4 (DeliveryDesk.resolve/drainWire — F9's 5-arg record(...) forces every F4-added journal site to pass a resolution tag, e.g. a supersede-passthrough value); F1 (HitRegistrationUnit.sprintVerdict + KnockbackUnit obligations — same files, adjacent lines, no semantic overlap); F5/F6 (HitRegistrationUnit attacker capture + HitContext — F9 deliberately avoids HitContext and reads the EntityStates fed to the engine, so it records F5/F6's yaw stamp automatically); F3 (SessionService — F9 appends a ctor param while F3 touches clearStale; trivial textual merge). No overlap with F7/F8.
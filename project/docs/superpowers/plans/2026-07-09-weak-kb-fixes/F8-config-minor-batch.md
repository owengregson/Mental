# F8 — config-minor batch: byte-identity preset upgrades, dead latency knobs, ring seed, Folia-safe spoof listener

Four independent confirmed defects, one plan. All paths are absolute under `/Users/owengregson/Documents/StrikeSync/`.

---

## Part 1 — `superseded-upgrade-reverts-owner-edit`

### 1.1 Problem

`ConfigStore.upgradeSupersededPreset` (core/src/main/java/me/vexmc/mental/v5/config/ConfigStore.java:105-131) runs on every boot AND every `/mental reload` (`MentalPluginV5.reloadAll`:441 → `ensureDefaultFiles`). It parses each preset file and, when the parsed VALUES + identity strings match any `SupersededPresets` revision (`isSupersededVerbatim`, kernel/src/main/java/me/vexmc/mental/kernel/profile/SupersededPresets.java:403-412), overwrites the file with the current bundle. Value matching cannot distinguish "unedited old bundle" from "owner edit that lands on old values" — and the bundled files' own comment instructs exactly such an edit ("Restore -3.9 to unfloor", core/src/main/resources/profiles/legacy-1.7.yml:37 and the parallel comments in legacy-1.8/kohi/mmc/minehq/badlion/custom). An owner who edits `vertical-min: 0.0` → `-3.9` in the current legacy-1.7.yml parses to exactly `LEGACY17_2_4_7` and is silently reverted on the next reload, with a log line falsely claiming the file was "carried unedited". Verified: the 2.4.7 bundle (git `66083d1`) and the current bundle (`a54332f`) differ in the comment block too, so the owner's edit is NEVER byte-identical to the old bundle — bytes discriminate where values cannot.

### 1.2 Design decision

Match on the **raw bytes** of the on-disk file against SHA-256 hashes of every superseded bundled revision's exact historical text (newline-normalized CRLF/CR→LF only). Only a byte-identical old bundle upgrades; any edit — value, comment, formatting — freezes the file. This keeps the pristine-upgrade feature (the owner's chosen design) fully working for genuinely-untouched installs, is parser-drift-proof (the 2.4.8 "LEGACY_17 edits move the parse fallback" trap can never break it), and makes the owner-edit collision structurally impossible. Hashes (not full texts) live in the kernel `SupersededPresets` (pure JDK: `java.security.MessageDigest`, Java-8-safe); the full historical texts are pinned as **core test resources** with a test that recomputes every hash constant. Alternative rejected: value-match + a "bytes differ" secondary check — still needs the byte archive, more code, and keeps two sources of truth. The existing value-based `isSupersededVerbatim`/`of` kernel API **stays** (kernel is additive-only; `SupersededPresetsTest` keeps pinning it) but ConfigStore no longer calls it.

### 1.3 Historical byte forms — the exact enumeration (verified via `git log --follow` on each preset file)

For each superseded value-revision, every distinct released byte form. Pre-1.4.0 forms lack the `delivery:` block; `ensureDeliverySection` runs BEFORE the upgrade check in `ensureDefaultFiles` (ConfigStore.java:94-95) and its insertion logic is byte-identical to v1.4.0's (verified: same block string `"  delivery:\n    melee: <m>\n    projectile: <p>\n"`, same `"  modifiers:"` anchor, same per-preset melee/projectile mapping), so the byte form the check actually sees is the **patched** text — hash that. v1.3.0 == v1.3.1 texts (verified byte-identical for all six presets of that era). custom is excluded from the patcher, so its 1.3.x form is hashed raw. 221fb49 never shipped (superseded same-day by 9c4cec7 before v1.8.0). 66083d1 shipped in 2.4.7 (verified ancestor of the release commit b6bc1fa).

Generate each text with `git show <ref>:core/src/main/resources/profiles/<preset>.yml` and each hash with `... | shasum -a 256`:

| preset | forms (ref → resource name) |
|---|---|
| kohi | v1.3.0 **+delivery patch** (tracker/tracker) → `kohi@1.3.x-patched.yml`; v1.4.0 → `kohi@1.4.0.yml`; e0f0af4 → `kohi@1.5.0.yml`; v1.8.0 → `kohi@1.8.0.yml` |
| mmc | v1.3.0 **+patch** (immediate/immediate) → `mmc@1.3.x-patched.yml`; v1.4.0 → `mmc@1.4.0.yml`; v1.8.0 → `mmc@1.8.0.yml` |
| lunar | v1.3.0 **+patch** (tracker/tracker) → `lunar@1.3.x-patched.yml`; v1.4.0; e0f0af4 → `lunar@1.5.0.yml`; v1.8.0 |
| minehq | v1.8.0 → `minehq@1.8.0.yml` |
| badlion | v1.8.0 → `badlion@1.8.0.yml` |
| signature | v2.2.0 → `signature@2.2.0.yml`; v2.2.1 → `signature@2.2.1.yml`; v2.4.0 → `signature@2.4.0.yml` |
| legacy-1.7 | v1.3.0 **+patch** (tracker/tracker); v1.4.0; e0f0af4 → `legacy-1.7@1.5.0.yml`; 66083d1 → `legacy-1.7@2.4.7.yml` |
| legacy-1.8 | v1.3.0 **+patch** (immediate/tracker); v1.4.0; e0f0af4; 66083d1 → `legacy-1.8@2.4.7.yml` |
| custom | v1.3.0 **raw** → `custom@1.3.x.yml`; v1.4.0; e0f0af4 → `custom@1.5.0.yml`; 00d970a → `custom@2.4.0.yml` |

28 resources under `core/src/test/resources/superseded-bundles/`, plus one extra `kohi@1.3.x-raw.yml` (the unpatched v1.3.0 text — used ONLY to prove the patch→hash→upgrade chain end-to-end, not hashed). To build a `-patched` resource: take the raw text, insert the block immediately before the `  modifiers:` line (all five patchable v1.3.0 presets contain the anchor — kohi verified at line 49; implementer verifies the other four; if one lacks it, apply the fallback append `content + "\n" + block` exactly as the code does). Sanity duties for the implementer: (a) every resource's `shasum -a 256` equals its constant; (b) NO constant equals the hash of any CURRENT bundle (`core/src/main/resources/profiles/*.yml`) — self-upgrade-loop guard, also pinned by test; (c) dedupe identical hashes via `Set`.

### 1.4 Exact changes

**kernel/src/main/java/me/vexmc/mental/kernel/profile/SupersededPresets.java** — add (nothing removed):
- imports: `java.nio.charset.StandardCharsets`, `java.security.MessageDigest`, `java.security.NoSuchAlgorithmException`, `java.util.Set`.
- A `private static final Map<String, Set<String>> BUNDLE_SHA256_BY_PRESET = Map.of(...)` with the 9 presets → hash sets from §1.3, each hex constant carrying a `// <preset>@<ref>` provenance comment, plus a javadoc block explaining: byte identity (newline-normalized) is what ConfigStore matches since this fix; value matching reverted owner edits that landed on old values (the file's own "Restore -3.9" instruction); pre-1.4.0 forms hashed WITH the delivery patch because `ensureDeliverySection` runs first; texts pinned in core test resources `superseded-bundles/`.
- ```java
  /** Whether {@code fileText} is byte-identical (newline-normalized) to a superseded bundled revision of {@code preset}. */
  public static boolean isSupersededBundleText(String preset, String fileText) {
      Set<String> hashes = BUNDLE_SHA256_BY_PRESET.get(preset);
      if (hashes == null || fileText == null) {
          return false;
      }
      String normalized = fileText.replace("\r\n", "\n").replace('\r', '\n');
      return hashes.contains(sha256Hex(normalized));
  }

  private static String sha256Hex(String text) {
      try {
          MessageDigest digest = MessageDigest.getInstance("SHA-256");
          byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
          StringBuilder hex = new StringBuilder(hash.length * 2);
          for (byte b : hash) {
              hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                 .append(Character.forDigit(b & 0xF, 16));
          }
          return hex.toString();
      } catch (NoSuchAlgorithmException impossible) {
          throw new IllegalStateException("SHA-256 unavailable", impossible);
      }
  }
  ```
  All APIs are Java-8-native (verifyJdk8Api/verifyDowngrade safe); no Bukkit (kernel gate safe). Update the class javadoc's "parsed values" sentence to the byte contract.

**core/src/main/java/me/vexmc/mental/v5/config/ConfigStore.java**:
- Rewrite `upgradeSupersededPreset` (drop the parse; keep the same call site and order after `ensureDeliverySection`):
  ```java
  private void upgradeSupersededPreset(String preset, Path file) {
      if (!Files.isRegularFile(file)) {
          return;
      }
      String onDisk;
      try {
          onDisk = Files.readString(file, StandardCharsets.UTF_8);
      } catch (IOException failure) {
          log.accept("Could not read profiles/" + preset + ".yml: " + failure);
          return;
      }
      if (!SupersededPresets.isSupersededBundleText(preset, onDisk)) {
          return;
      }
      String current = readResource(PROFILES_DIR + "/" + preset + ".yml");
      if (current == null) { /* unchanged missing-resource branch */ }
      try {
          Files.writeString(file, current, StandardCharsets.UTF_8);
          log.accept("profiles/" + preset + ".yml is a superseded bundled revision,"
                  + " byte-identical and unedited — upgraded to the corrected bundle"
                  + " (delete the file to regenerate anytime)");
      } catch (IOException failure) { /* unchanged */ }
  }
  ```
  The log line MUST still contain "upgraded" (tests match on it). Method javadoc: byte-identity contract + why value matching was retired (cite the reverted-owner-edit bug). Class javadoc lines 26-29: "…whose RAW BYTES still match a superseded bundled revision…; ANY difference — a value, a comment, formatting — is an owner edit and the file is frozen." Remove now-unused imports: `KnockbackProfile`, `ConfigurationSection` (verify no other use; `Configuration` stays for `Sources`).

**docs/knockback-profiles.md** (~line 31): reword the exception to byte identity ("byte-identical to a superseded bundled revision — you never touched it, not even a comment"). **.claude/skills/knockback-profiles/SKILL.md** line ~25: change "(`SupersededPresets`, value-equality not bytes)" to "(`SupersededPresets`, byte-identity after newline normalization — since 2.4.9)".

### 1.5 Migration story

- Files already reverted by the old logic are now byte-identical to the current bundle → no hash matches → never touched. The owner re-applies the edit ONCE and it now sticks forever (its bytes differ from every archived revision because the current comment block differs from every archived one — verified for legacy-1.7 in §1.1). State this in the release notes / commit body; it is not detectable at runtime.
- A pristine old install (any released form since 1.3.0) still upgrades — feature parity with today, proven per-form by the hash test.
- New freeze case (intended): a file with pristine values but owner-edited comments/formatting now freezes instead of upgrading — that IS "owner edits are sacred" applied consistently.

---

## Part 2 — the three dead latency knobs

### 2.1 Problem

latency-compensation.yml documents three behavioral knobs that are parsed into `CompensationSettings` (SnapshotParser.java:173-177) and consumed by NOTHING: `ping-offset-ms` (":13-15 'Subtracted from each measured round trip'"), `spike-threshold-ms` (":16-18 'a ping jump larger than this is treated as a one-off spike and the previous reading is used instead'" — `LatencyModel.Record.previousPingMillis` exists explicitly for this, kernel LatencyModel.java:52, but has zero production readers), and `off-ground-sync` (":23-25" — `CompensationQuery.verticalFor` runs the airborne branch unconditionally, CompensationQuery.java:42-43).

### 2.2 Per-knob decisions

- **`ping-offset-ms` → REMOVE.** Implementing it can never have a no-op default: any nonzero subtraction shifts every compensated hit's tick count, and existing installs carry `25` on disk. The "tournament-derived constant" has no principled basis and three years inert proves no one depended on it. Removal changes zero runtime behavior (it was consumed by nothing) — the honest option.
- **`spike-threshold-ms` → IMPLEMENT** (consumer-side, stateless). The machinery half-exists (`previousPingMillis` kept "spike detection"); removing instead would orphan a public kernel getter forever (additive-only). Semantics: when the two most recent RTT samples disagree by MORE than the threshold, trust the **smaller** — an RTT can be overstated by a delayed echo (GC pause, bufferbloat) but never understated. This is strictly better than "use the previous reading": it also rejects the spike on the bounce-back sample (40→240→40 uses 40 in all three windows), while a sustained shift (40→240→240) is adopted on its second sample, and a downward shift is adopted immediately. On a stable stream (|Δ| ≤ 20 ms between consecutive samples) behavior is byte-identical — the default is a no-op in the absence of the anomaly it filters; in anomaly windows the documented contract finally holds (a deliberate, non-silent behavior delta, called out in the commit body).
- **`off-ground-sync` → IMPLEMENT.** Default `true` == the current unconditional airborne rewrite → **exact no-op** at default. `false` now honored: only the landing-fold/grounded prediction runs; the airborne free-fall simulation is skipped (returns null → raw ledger vy, the era baseline) — exactly the YAML's "not just the on-ground case" phrasing inverted.

### 2.3 Exact changes

**kernel/src/main/java/me/vexmc/mental/kernel/wire/LatencyModel.java** — add to `Record` (additive):
```java
/**
 * The spike-filtered RTT reading (the spike-threshold-ms contract): when the two
 * most recent samples disagree by more than the threshold, the SMALLER is trusted —
 * a round trip can be overstated by a delayed echo but never understated, so a
 * one-off upward spike is rejected on arrival AND on bounce-back while a sustained
 * shift is adopted on its second sample. {@code threshold <= 0} disables the
 * filter. Null until the first response.
 */
public Double filteredPingMillis(int spikeThresholdMillis) {
    Double ping = this.pingMillis;
    Double previous = this.previousPingMillis;
    if (ping == null) {
        return null;
    }
    if (spikeThresholdMillis > 0 && previous != null
            && Math.abs(ping - previous) > spikeThresholdMillis) {
        return Math.min(ping, previous);
    }
    return ping;
}
```
`onResponse` and stored state unchanged (raw samples keep flowing to `pingMillis`/`previousPingMillis`/jitter). Update the class javadoc sentence "(spike detection)" to "(consumed by {@link Record#filteredPingMillis})".

**kernel/src/main/java/me/vexmc/mental/kernel/wire/CompensationQuery.java** — add a 4-arg overload; 3-arg delegates (kernel additive):
```java
/** Backward-compatible: the full client-side simulation (off-ground-sync true). */
public static Double verticalFor(PlayerView victim, int rttMillis, double baseVy) {
    return verticalFor(victim, rttMillis, baseVy, true);
}

public static Double verticalFor(PlayerView victim, int rttMillis, double baseVy, boolean offGroundSync) {
    ... existing body unchanged through the ticksToLand fold ...
    if (ticksToLand >= 0 && ticksToLand <= ticks) {
        return Decay.groundedEquilibrium(gravity);
    }
    if (!offGroundSync) {
        return null; // the documented opt-out: only the on-ground/landing case is rewritten
    }
    return MotionMath.simulateVerticalVelocity(baseVy, gravity, ticks);
}
```

**core/src/main/java/me/vexmc/mental/v5/config/settings/CompensationSettings.java** — remove `pingOffsetMillis`; record becomes `(ProbeStrategy probeStrategy, int spikeThresholdMillis, long probeIntervalTicks, long combatTimeoutTicks, boolean offGroundSync)`; `DEFAULTS = new CompensationSettings(ProbeStrategy.PING, 20, 5L, 30L, true)`. (Core-internal record — not public API; japicmp gates the `api` module only.)

**core/src/main/java/me/vexmc/mental/v5/config/SnapshotParser.java** `parseCompensation` (:163-178) — drop the ping-offset read; add the retirement notice:
```java
if (reader.section() != null && reader.section().isSet("ping-offset-ms")) {
    reader.issues().add("latency-compensation.ping-offset-ms: retired — it was never"
            + " applied (the measured round trip is used as-is); delete the line");
}
```
(`ConfigReader` is a record; `section()`/`issues()` are accessors — no ConfigReader change needed.) Keep `spike-threshold-ms` floor at 1 (`intAtLeast(..., 1)`; the 0-disables path stays internal to the kernel method).

**core/src/main/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationUnit.java** `compensationFor` (:537-544):
```java
private Double compensationFor(UUID victimId, PlayerView victimView) {
    if (victimId == null || victimView == null
            || !snapshot.get().enabled(Feature.LATENCY_COMPENSATION)) {
        return null;
    }
    CompensationSettings settings = compensationSettings();
    Double ping = latency.forPlayer(victimId).filteredPingMillis(settings.spikeThresholdMillis());
    int rtt = ping == null ? 0 : (int) Math.round(ping);
    return CompensationQuery.verticalFor(
            victimView, rtt, victimView.motion().vy(), settings.offGroundSync());
}

@SuppressWarnings("unchecked")
private CompensationSettings compensationSettings() {
    return snapshot.get().settings(
            (SettingsKey<CompensationSettings>) Feature.LATENCY_COMPENSATION.settingsKey());
}
```
(the exact `LatencyCompensationUnit.settings()` idiom, :85-88; add the `CompensationSettings` import — `SettingsKey`/`Feature` already imported.)

**core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java** `compensationFor` (:373-388) — the non-melee branch gets the identical two-line change (filtered ping + 4-arg verticalFor) and the same private `compensationSettings()` helper + import. The melee branch (`tx.context().compensationY()`, compute-once from the fast path) is untouched — it inherits the fix through Part 2's registration-time computation, keeping netty/region coherent.

**Deliberately untouched:** `ComboPredictor.rttMillis` (:323-329) keeps the raw read — it prices combo timing (servo, default OFF), not compensation; widening this batch there is out of scope. Note this in the commit body.

**core/src/main/resources/latency-compensation.yml** — delete lines 13-15 (the ping-offset comment + key); replace the spike comment (16-17) with: `# A jump larger than this between consecutive probe readings is a one-off\n# spike: the smaller of the two readings is trusted (a round trip can be\n# overstated by a delayed echo, never understated).`. `off-ground-sync` comment already matches the implemented semantics — unchanged. (Human YAML on existing installs is never rewritten; owners keep the stale line and get the one-line parse notice per reload until they delete it — that is the migration note.)

---

## Part 3 — `ring-origin-fallback-first-tick`

### 3.1 Problem

The ring's first sample is recorded at the END of the first session tick, AFTER the view publish inside the same task (SessionService.tick: `buildView` :335 precedes `positions.record` :352-354), so a hit registered in that sub-tick window sees `viewOf()` non-null but `positions.latest()` null — HitRegistrationUnit's 0.0 fallbacks (:452-484) then compute a full-magnitude push toward/away from the world origin (KnockbackEngine normalizes (0,0,0)-sourced deltas at full push), plus a corrupt HurtYaw. Join-only today (the ring survives death; only quit forgets, :309), but post-respawn the latest sample is the corpse position until the first post-respawn tick — same wrong-origin family.

### 3.2 Exact changes — core/src/main/java/me/vexmc/mental/v5/session/SessionService.java

In `join(Player player)` (:241), after `samplers.put(id, new GroundFsm(clock));` and before `scheduleTick(player, id);`:
```java
// Seed the ring with the join location so a hit registered before the first
// session tick's record() never falls back to the fabricated (0,0,0) origin —
// the pre-send push direction and hurt yaw would otherwise point at the world
// origin (ring-origin-fallback-first-tick).
Location location = player.getLocation();
positions.record(id, location.getX(), location.getY(), location.getZ(), System.nanoTime());
```
Add a respawn re-seed (new handler; import `org.bukkit.event.player.PlayerRespawnEvent`):
```java
@EventHandler(priority = EventPriority.MONITOR)
public void onRespawn(PlayerRespawnEvent event) {
    // The ring survives death (only quit forgets), so its latest sample is the
    // corpse position until the first post-respawn tick — re-seed at the actual
    // respawn point so a first-instant hit reads the true origin.
    Location location = event.getRespawnLocation();
    positions.record(event.getPlayer().getUniqueId(),
            location.getX(), location.getY(), location.getZ(), System.nanoTime());
}
```
Side effect (accept, document in commit body): `buildView`'s first-tick `measuredVx/measuredVz` (:518-520) becomes the real join→tick-1 delta instead of 0.0 — strictly more accurate; consumed only by the pocket servo (default OFF) and the pre-send victim-motion, both of which previously read the fabricated fallback in exactly this window. `getLocation()` and `getRespawnLocation()` are Folia-safe on their event threads; the seed does not extend to `start()`'s adopt loop beyond what `join` already does.

---

## Part 4 — `spoof-listener-folia-entityid-throw`

### 4.1 Problem

`CooldownSpoofListener.onPacketSend` (core/src/main/java/me/vexmc/mental/v5/feature/cadence/CooldownSpoofListener.java:41) calls `receiver.getEntityId()` on the netty thread. On Folia every `getHandle()`-routed accessor throws `IllegalStateException` off the owning region (netty-fast-path skill, measured; confirmed by javap on Folia 1.21.11: `getEntityId()` = `getHandle().getId()` behind `ensureTickThread`), and the blanket `catch (Exception ignored)` (:47-49) swallows it — the client-presentation half silently no-ops for every outbound UPDATE_ATTRIBUTES on Folia. The rim's established Folia-safe identity idiom is the PacketEvents cached `event.getUser()`.

### 4.2 Exact changes — CooldownSpoofListener.java

Replace the Bukkit-player resolution with the PE user (verified against packetevents-api 2.12.1: `User.getEntityId()` exists; the field is initialized to `-1` until PE learns it from JOIN_GAME — bytecode-verified `iconst_m1 putfield entityId`):
```java
@Override
public void onPacketSend(PacketSendEvent event) {
    if (event.getPacketType() != PacketType.Play.Server.UPDATE_ATTRIBUTES) {
        return;
    }
    User user = event.getUser();
    if (user == null || user.getEntityId() == -1) {
        return; // no connection identity yet (pre-JOIN_GAME) — nothing to compare against
    }
    try {
        WrapperPlayServerUpdateAttributes packet = new WrapperPlayServerUpdateAttributes(event);
        if (packet.getEntityId() != user.getEntityId()) {
            return; // another entity's attribute update routed through this connection
        }
        if (CooldownSpoof.forceFullAttackSpeed(packet)) {
            event.markForReEncode(true);
        }
    } catch (Exception ignored) {
        // Never let a parse/mutate failure propagate on the netty thread.
    }
}
```
Imports: remove `org.bukkit.entity.Player`; add `com.github.retrooper.packetevents.protocol.player.User`. Class javadoc: add one sentence — identity comes from the PE cached User, never a Bukkit handle accessor, because `getEntityId()` throws off-region on Folia (netty-fast-path rule); the old handle read was thrown-and-swallowed, silently disabling this half on Folia. Residual (note in commit body): a self UPDATE_ATTRIBUTES arriving before PE learns the entity id is skipped — masked by `AttackChargeReset`'s server-side base=1024.

---

## Threading analysis (whole batch)

- **SupersededPresets hashes**: static final immutable state; `isSupersededBundleText` is pure (fresh `MessageDigest` per call). `ConfigStore.ensureDefaultFiles` runs on the boot/reload thread (D3) only — unchanged.
- **`filteredPingMillis`**: reads two volatiles written by the probe rims on the netty read thread; readers are the netty registration (HitRegistrationUnit) and the victim's region thread (KnockbackUnit). No new mutable state; a concurrent sample landing between the two volatile reads yields one of the two most recent samples — benign, self-corrects next probe. `CompensationQuery` stays pure/static.
- **Ring seed**: `PositionRing.record` is designed for cross-thread use (per-ring `synchronized`). `join` runs on the join-event thread (main on Paper, owning region on Folia); `onRespawn` on the respawn-event thread; `getLocation()`/`getRespawnLocation()` are safe reads there. The session tick's own once-per-tick record is unchanged — single-writer discipline holds (the seed happens before the tick task is armed / at a Bukkit-ordered event boundary).
- **Spoof listener**: netty thread now touches only PE-cached connection state and the packet-local wrapper — zero Bukkit handle reads (B10 preserved).
- DeliveryDesk remains the sole velocity/journal writer; nothing here writes game state (the spoof rewrite is packet-local, pre-existing).

## Era / zero-touch analysis

- **Part 1**: pure config-file lifecycle; parsing, presets, `parse(empty)==LEGACY_17`, and every runtime value are untouched. A pristine install upgrades exactly as before (proven per historical form); the only behavior delta is that edited-but-value-colliding files are now correctly frozen.
- **Part 2**: `ping-offset-ms` had zero consumers — removal is byte-identical at runtime; only a console notice appears when the stale key exists. `off-ground-sync` default `true` delegates to the exact current code path — byte-identical for every untouched config; `false` was documented and previously ignored. Spike filter: byte-identical whenever consecutive RTT samples differ ≤ 20 ms (all stable links, all LAN/test rigs, every grounded close-range trade — grounded victims short-circuit before the RTT is even used); in genuine ≥threshold anomaly windows the documented contract now holds (deliberate, stated in the commit body, not silent). LATENCY_COMPENSATION off ⇒ all of Part 2 unreachable (zero-touch).
- **Part 3**: always-on observation infrastructure; the seed replaces only the fabricated (0,0,0) fallback with the true origin. No default-config hit outside the bug window changes by a byte; the first-tick `measuredVx` refinement feeds only the fallback-window pre-send and the default-OFF servo.
- **Part 4**: CADENCE/ATTACK_COOLDOWN is default OFF (zero-touch holds); when ON, Paper behavior is byte-identical (same comparison values); Folia gains the behavior the contract already promised.

## Tests

**New: `core/src/test/java/me/vexmc/mental/v5/config/SupersededBundleHashTest.java`** — for every resource in §1.3's table (explicit `Map<String resourceName, String preset>` in the test): load from the test classpath, assert `SupersededPresets.isSupersededBundleText(preset, text)` is **true**; assert the CRLF variant `text.replace("\n", "\r\n")` also matches (normalization pin); for each of the ten CURRENT bundles (main-classpath `profiles/<name>.yml`) assert **false** (no self-upgrade loop); assert garbage (`"display-name: X\n"`) false and unknown preset (`"velt"`, any text) false.

**Modified: `core/src/test/java/me/vexmc/mental/v5/config/ConfigStoreTest.java`** — delete the synthetic `MMC_1_3_BODY` / `SIGNATURE_2_2_0_BODY` / `KOHI_2_4_6_BODY` / `LEGACY17_2_4_7_BODY` constants (they match values, not bytes — dead under the new regime) and restage:
- `unTunedSupersededPresetIsUpgradedInPlace`: stage `superseded-bundles/kohi@1.3.x-raw.yml` (raw, unpatched) as profiles/kohi.yml → `ensureDefaultFiles()` → assert the file now equals the CURRENT bundle (the patcher inserted delivery, the hash matched the patched form, the upgrade overwrote), the log contains "kohi.yml" + "upgraded", and a second pass is idempotent. This is the end-to-end patch-chain proof.
- `unTunedSignaturePresetUpgradesToTheVerticalTuning`: stage `signature@2.2.0.yml` → assert `knockback.base.vertical == 0.365`, `air.vertical == 0.98` after (unchanged expectations, real bytes staged).
- `unTunedPreFloorKohiUpgradesToThePracticeFloor`: stage `kohi@1.8.0.yml` → assert `limits.vertical-min == 0.0` and `base.horizontal == 0.35` after.
- `unTunedPreFloorLegacyUpgradesToTheOwnerFloor`: stage `legacy-1.7@2.4.7.yml` → assert `vertical-min == 0.0` after.
- `tunedSupersededPresetIsNeverTouched` / `tunedPreFloorKohiKeepsItsOldFloorForever` / `tunedSignaturePresetIsNeverTouched`: stage the same real texts with one value substring tweaked (e.g. `replace("horizontal: 0.35", "horizontal: 0.37")`) → file byte-identical after two passes, no "upgraded" log.
- **NEW — the brief's collision pin, `ownerUnfloorEditOnTheCurrentBundleIsFrozen`**: read the CURRENT bundled `profiles/legacy-1.7.yml` from the classpath, `replace("vertical-min: 0.0", "vertical-min: -3.9")` (the file's own "Restore -3.9 to unfloor" instruction — parses to exactly `LEGACY17_2_4_7`'s values, the case the old code reverted), write it, run `ensureDefaultFiles()` twice → assert the file is byte-identical to the staged edit, still contains `-3.9`, and NO "upgraded" log line. Same-shaped second case for signature: current bundle with the whole `speed-scaling:` block deleted (parses to `SIGNATURE_2_2_1` values) → frozen.
- First test (`ensureDefaultFilesExtractsEverythingOnceAndNeverOverwrites`) unchanged — still passes (the "display-name: Mine" edit never hash-matches).

**Kernel `SupersededPresetsTest`** — unchanged (still pins the public value API).

**Modified: `kernel/src/test/java/me/vexmc/mental/kernel/wire/LatencyModelTest.java`** — add `filteredPingMillis` cases (RTT = (respNanos − sentNanos)/1e6, hand-computed):
- fresh record → `filteredPingMillis(20) == null`.
- sent(1, 0), resp(1, 40_000_000) → rtt 40.0; prev null → `filtered(20) == 40.0`.
- sent(2, 1_000_000_000), resp(2, 1_240_000_000) → rtt 240.0, prev 40.0; |240−40| = 200 > 20 → `filtered(20) == min(240,40) == 40.0`; raw `pingMillis() == 240.0` (storage unfiltered).
- bounce-back: sent(3, 2e9), resp(3, 2_040_000_000) → rtt 40.0, prev 240.0; |200| > 20 → `filtered(20) == 40.0` (recovered sample trusted immediately).
- sustained shift (separate test): 40 → 240 → 240: after the third response prev == 240, |0| ≤ 20 → `filtered(20) == 240.0` (adopted on the second sample).
- boundary: samples 40 → 60: |20| is NOT > 20 → `filtered(20) == 60.0` (strictly-greater per the YAML wording).
- disabled: with the 240-vs-40 state, `filtered(0) == 240.0` (raw).
Existing `onResponse`/`previousPingMillis`/eviction pins unchanged.

**Modified: `kernel/src/test/java/me/vexmc/mental/kernel/wire/CompensationQueryTest.java`** — add:
- `offGroundSyncFalseSkipsTheAirborneRewrite`: airborne rising victim (`view(0, new KinematicState(70.0, 10.0, false))`), rtt 100, baseVy 0.42 → 4-arg `verticalFor(..., false) == null`; 4-arg `(..., true)` == 3-arg == `simulateVerticalVelocity(0.42, GRAVITY, 2)`. Hand arithmetic: t1 = (0.42 − 0.08) × 0.98 = 0.3332; t2 = (0.3332 − 0.08) × 0.98 = **0.248136**.
- `offGroundSyncFalseKeepsTheLandingFold`: falling victim about to land (`baseVy −0.5`, `distanceToGround 0.4` → ticksToFall = 1 ≤ ticks = 2) → BOTH `(..., false)` and `(..., true)` return `Decay.groundedEquilibrium(GRAVITY)` = −0.08 × 0.98 = **−0.0784**.
- `defaultOverloadIsOffGroundSyncTrue`: 3-arg == 4-arg(true) on the airborne case.
All existing 3-arg pins must NOT change (pure delegation).

**Modified: `core/src/test/java/me/vexmc/mental/v5/config/SnapshotTest.java`** — add `retiredPingOffsetKeyReportsAndStillParses`: parse latency yaml `latency-compensation:\n  ping-offset-ms: 25\n  spike-threshold-ms: 60\n  off-ground-sync: false` → one issue containing "ping-offset-ms" and "retired"; `comp.spikeThresholdMillis() == 60`, `comp.offGroundSync() == false`. Existing probe-strategy tests (empty-key yamls) still assert zero issues — unchanged. Line 69's `DEFAULTS` equality pin holds with the new 5-component record.

**New: `core/src/test/java/me/vexmc/mental/v5/feature/cadence/CooldownSpoofListenerTest.java`** — using the `PacketTapStateTest` scaffolding (stub `PacketEvents.setAPI` + `NettyManagerImpl` + netty on the test classpath make PE events constructible): a send event whose `User` entityId equals the wrapper's → `CooldownSpoof.forceFullAttackSpeed` path runs (assert re-encode marked when attack_speed present at 4.0); user entityId `-1` → untouched; mismatched ids → untouched. The load-bearing property: the listener compiles with NO `org.bukkit.entity.Player` reference. If send-event construction proves infeasible with the existing scaffolding (it is receive-oriented), drop the test and note the fallback: the fix is a strict reduction to already-pinned rim idioms and is exercised by the Folia boot smoke when CADENCE is enabled.

**No new tests for the ring seed** — SessionService is a Bukkit shell with no unit scaffold; the seed is two `positions.record` calls proven by type (`PositionRing` is kernel, already pinned). Existing suites must stay green (`ReachClampTest`, combo suites — none pin a null first-tick ring or a zero first-tick measuredVx; verify by running them).

**Pins that must NOT change**: `ProfileParserTest.emptyBlockParsesToLegacy17` (parse(empty)==LEGACY_17), `PresetsTest`, `PresetVerticalFloorTest`, `SupersededPresetsTest`, `KnockbackDocsTest`, all `KnockbackEngineTest` values, existing `CompensationQueryTest`/`LatencyModelTest`/`ProbeTransactionsTest` cases, `ConfigStoreTest`'s extraction/idempotence test.

## Verification

1. `./gradlew build` — all unit tests green; japicmp green (api untouched; kernel changes additive); kernel-Bukkit-free gate green (MessageDigest/StandardCharsets are JDK); `verifyDowngrade`/`verifyJdk8Api` green (all new kernel APIs are Java-8-native — no `HexFormat`, hand-rolled hex).
2. `./gradlew integrationTestMatrix` (or `scripts/integration-matrix.sh` — remember it does NOT rebuild; build first, honor the nonce rule: fresh-UUID + PASS in test-results.txt, never the banner). No suite additions required; the matrix pins nothing here regresses (boot report, KB suites, Folia smoke).
3. Manual spot-check: stage a data dir with the current legacy-1.7.yml edited to `-3.9`, boot twice → edit survives and takes effect (profile dump shows verticalMin −3.9); stage a v1.8.0 kohi.yml → upgraded once with the console line.

## Commits (conventional, prose bodies)

1. `fix(config): match superseded preset upgrades on raw bundle bytes` — body: the reverted-owner-edit mechanism, the archive provenance (refs), the migration story for already-reverted files.
2. `fix(latency): retire ping-offset-ms, implement the spike filter and off-ground-sync` — body: per-knob decision rationale, the min-of-two-readings semantics and the honest anomaly-window behavior delta, ComboPredictor deliberately untouched.
3. `fix(session): seed the position ring at join and respawn` — body: the publish-before-record window, the (0,0,0) fabricated origin, first-tick measuredVx side effect.
4. `fix(cadence): resolve the spoof listener's identity from the PacketEvents user` — body: the Folia getHandle throw + swallow, the rim idiom, the −1 pre-JOIN_GAME residual.

## Risks + rollback

- **Missed historical byte form** → a pristine old install freezes at old values silently (conservative failure — never a wrong overwrite). Mitigated by full `git log --follow` enumeration per preset; any report is fixed by adding one hash constant. Rollback: revert commit 1 (the value-match code path is preserved in kernel, ConfigStore flips back with a two-line change).
- **Spike filter mis-tuned** (threshold 20 too tight on chaotic links) → operator raises `spike-threshold-ms`; worst case equals today's raw behavior. Rollback: revert the two consumer lines (kernel method is additive/harmless).
- **Respawn seed on an exotic plugin-mutated respawn location** → one sample at the announced respawn point; the next tick's record corrects. Strictly bounded.
- **PE User entityId semantics change across PE upgrades** → pinned by the new unit test; the `-1` guard degrades to skip-packet (masked by the server-side base).
- Whole-batch rollback: four independent commits, each individually revertible with no cross-dependency.

## Files touched

- `kernel/src/main/java/me/vexmc/mental/kernel/profile/SupersededPresets.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/wire/LatencyModel.java`
- `kernel/src/main/java/me/vexmc/mental/kernel/wire/CompensationQuery.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/wire/LatencyModelTest.java`
- `kernel/src/test/java/me/vexmc/mental/kernel/wire/CompensationQueryTest.java`
- `core/src/main/java/me/vexmc/mental/v5/config/ConfigStore.java`
- `core/src/main/java/me/vexmc/mental/v5/config/SnapshotParser.java`
- `core/src/main/java/me/vexmc/mental/v5/config/settings/CompensationSettings.java`
- `core/src/main/java/me/vexmc/mental/v5/feature/delivery/HitRegistrationUnit.java`
- `core/src/main/java/me/vexmc/mental/v5/feature/knockback/KnockbackUnit.java`
- `core/src/main/java/me/vexmc/mental/v5/session/SessionService.java`
- `core/src/main/java/me/vexmc/mental/v5/feature/cadence/CooldownSpoofListener.java`
- `core/src/main/resources/latency-compensation.yml`
- `core/src/test/java/me/vexmc/mental/v5/config/ConfigStoreTest.java`
- `core/src/test/java/me/vexmc/mental/v5/config/SnapshotTest.java`
- `core/src/test/java/me/vexmc/mental/v5/config/SupersededBundleHashTest.java`
- `core/src/test/java/me/vexmc/mental/v5/feature/cadence/CooldownSpoofListenerTest.java`
- `core/src/test/resources/superseded-bundles/ (29 new .yml resources per the plan's table)`
- `docs/knockback-profiles.md`
- `.claude/skills/knockback-profiles/SKILL.md`

## Cross-lane conflicts (integrator notes)

F1 (HitRegistrationUnit.sprintVerdict + KnockbackUnit obligations — same two files, different methods than my compensationFor edits; textual merge only), F2 (HitRegistrationUnit.plan — same file, different method; note my compensationFor feeds the compensationY that plan() stamps into HitContext, so if F2 restructures plan() the call site name may shift), F3 (SessionService — F3 edits tick()/clearStale, I edit join() and add onRespawn; same file, disjoint regions), F5+F6 (HitRegistrationUnit attacker-state capture ~:452-484 — the SAME fallback block my Part 3 makes unreachable-at-first-tick; if F5/F6 restructures preAttackerState/preVictimState the ring-read call sites move but my change is upstream in SessionService, semantic overlap is complementary not conflicting; also ViewBuilder/PlayerView arity changes may touch SessionService.buildView near my join() edit). F4, F7, F9: no file overlap (I deliberately do NOT touch ComboPredictor/PocketServo despite its raw rtt read — noted in plan).
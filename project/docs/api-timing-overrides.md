# Mental public API — temporary hit-timing overrides

**Audience:** the Mental developer implementing this. **Requester:** StarEnchants (SE), the
first consumer (the Berry Overdrive weapon reforge — formerly Quickening Fang). **Goal:** a
small, versioned service through which a third party may TEMPORARILY re-price the victim's
hit-admission window for ONE attacker, inside Mental's own timing math — so no consumer ever
again writes `noDamageTicks` around Mental's model and fights the pipeline that owns it.

The division of ownership is unchanged: **Mental owns hit timing; integrators request a
priced deviation and Mental applies it wherever IT reads windows.** SE has already shipped
the consumer side behind capability detection (`feature.combat.MentalTimingBridge`): the day
this service registers, SE stops writing vanilla i-frames entirely on Mental servers.

---

## 1. Motivation: the failure modes this deletes

SE's Berry Overdrive grants "your strikes come twice as fast against your victims for 5 s,
at 1/3 damage." Without this API, SE must express that as a vanilla i-frame write at
MONITOR (`victim.setNoDamageTicks(max − W/2)` one tick after the hit, to survive vanilla's
post-dispatch `invulnerableTime = max` reset). Audited consequences on Mental servers:

- **The ct8c inversion.** SE's window model was frozen against `WindowJudge` (gate = max/2,
  max intact at 20). `Ct8cIframesUnit` rewrites the per-hit `maximumNoDamageTicks` to
  `2 × min(attackDelay, 10)` — the doubled transport that makes CraftBukkit's half-window
  gate span 8c's full window — so SE's derived write equals the naturally-decayed counter
  (a no-op steal) while SE's 1/3 damage tax still lands: the ability degrades to a pure
  self-nerf on exactly the profile the flagship deployment runs. SE heuristically treated an
  observed `max <= 10` as the ct8c profile — a fingerprint of Mental internals that this API
  deletes, and one that the 2× transport (field 14–20, not 7–10) would silently invert
  anyway: exactly the reason an integrator must never fingerprint the field.
- **The spawn-invulnerability trap.** On 1.16.5–1.20.6 the Bukkit setter also arms
  `ServerPlayer.spawnInvulnerableTime`, whose gate silently voids EVERY hit — Mental ships
  `SpawnInvulnerability.disarm` for its own writes; SE had to copy the recipe. A foreign
  write that Mental never sees also risks tripping Mental's own foreign-window-reject
  reconciliation.
- **Third-party fairness is guessed at.** SE erases i-frames for ONE attacker but the
  vanilla counter is global, so SE ships a LOWEST-priority guard cancelling third-party
  hits inside stolen intervals — knock-without-damage ghosts under the fast path. When
  Mental prices the window per-(victim, attacker) internally, nothing global is erased and
  the guard becomes unnecessary.

## 2. The service

One interface, registered with Bukkit's `ServicesManager` at Mental enable (the capability
signal — consumers probe the registration, never a class or version string):

```java
package me.vexmc.mental.api.timing;

import java.util.UUID;

/**
 * Temporary per-(victim, attacker) hit-window re-pricing, applied inside Mental's own
 * admission math. While an override is active, every place Mental prices the victim's
 * hit-admission window FOR THAT ATTACKER reads {@code round(effectiveWindow * factor)}
 * instead of {@code effectiveWindow} — the default WindowJudge gate, the ct8c per-hit
 * maxima, and the hit-registration fast path alike. Third-party attackers, environment
 * damage and Mental's own cosmetics are untouched: nothing global is erased.
 */
public interface HitTimingOverrides {

    /**
     * Register (or refresh — never stack) an override: hits from {@code attacker} on
     * {@code victim} admit at {@code factor} x the profile's effective window for
     * {@code durationTicks} server ticks. {@code factor} is clamped to [0.25, 1.0] —
     * a floor Mental owns so no consumer can price a machine-gun window; 0.5 = the
     * canonical "twice as fast". Re-registering the same pair replaces the previous
     * override and its clock (refresh-not-stack).
     *
     * <p>Threading: call from the VICTIM's owning region thread (the thread a damage
     * event for the victim fires on — the natural call site is a MONITOR listener).
     */
    void overrideWindow(UUID victim, UUID attacker, double factor, int durationTicks);

    /** Whether an override is live for the pair right now. */
    boolean isActive(UUID victim, UUID attacker);

    /** Drop one pair's override early (a consumer's own cancel path). */
    void clear(UUID victim, UUID attacker);

    /** Drop every override touching {@code victim} — death/quit hygiene on the victim side. */
    void clearVictim(UUID victim);
}
```

## 3. Semantics Mental implements (each names the seam that already carries the data)

- **S1 — one read point.** Every admission decision already flows through the window gate
  (`WindowJudge` / `Ct8cIframesUnit`'s per-hit max + the fast-path judge in
  `HitRegistrationUnit`). The override multiplies the EFFECTIVE window at that read, after
  profile selection — never a stored-config mutation, never a vanilla-counter write. The
  ct8c profile composes naturally: `min(attackDelay, 10) * factor`.
- **S2 — pair-scoped.** The key is (victim UUID, attacker UUID). A third attacker's
  admission math is untouched — no erased global frames, no fairness reconciliation, no
  knock-without-damage ghosts.
- **S3 — expiry in server ticks,** wall-clock-independent (the tick the pair was registered
  plus `durationTicks`; Mental already carries a tick clock for its combo windows). Expired
  entries are lazily evicted at read + swept with the combo-state sweep.
- **S4 — refresh-not-stack.** Re-registration replaces factor AND clock. Two DIFFERENT
  attackers may each hold an override on one victim (independent pairs).
- **S5 — death/quit hygiene.** Victim death or quit clears the victim's pairs (the
  combo-ledger sweep seam); an attacker's quit may leave the entry to lapse on its clock —
  it prices hits that can no longer happen.
- **S6 — cosmetics follow admission.** An admitted accelerated hit is a REAL hit: knockback
  pre-send, combo accounting and transaction minting run exactly as for any admitted hit
  (no special-case path — the override only changed the gate's arithmetic).
- **S7 — no vanilla writes.** Mental does not write `noDamageTicks` on behalf of the
  override (its gate already out-prices the vanilla counter where profiles shrink windows);
  the spawn-invulnerability trap is therefore structurally unreachable from this API.

## 4. The consumer contract (what SE already ships)

At Berry Overdrive's MONITOR commit for a LANDED holder hit, SE probes the service
(memoized `ServicesManager` lookup, `feature.combat.MentalTimingBridge`):

- **Service present:** `overrideWindow(victim, holder, 0.5, observedMax)` — refreshed per
  landed hit for the life of SE's 5 s tempo window; SE performs NO vanilla i-frame write,
  NO spawn-invulnerability disarm, and does NOT stamp its third-party fairness guard (S2
  makes it moot). SE's 1/3 self-tax stays SE-side (its own damage fold).
- **Service absent** (vanilla servers, older Mental): SE's shipped fallback — the derived
  window write + companion disarm + fairness guard — unchanged.

## 5. Acceptance tests Mental should ship

1. Default profile: override(0.5) admits a full hit at gate/2 ticks; a third attacker still
   waits the full gate; expiry restores the pair.
2. ct8c profile: sword logical window 7 (field 14) → priced to 4 (field 8) under
   override(0.5), admitting at ~4 ticks; no vanilla-counter interaction (the counter may
   still read 6 — the GATE admitted early).
3. Fast path on: pre-sent knock and damage agree for an accelerated admitted hit (S6).
4. Refresh-not-stack: two registrations at t and t+20 admit against ONE clock ending at
   t+20+duration.
5. Victim death mid-override → clearVictim swept; no stale admission on respawn.

## 6. Versioning

The service registration IS the capability. If a later generation changes semantics,
register the new interface alongside (`HitTimingOverridesV2`) — never repurpose this one's
method contracts.

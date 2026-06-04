# The Fast Path

How Mental moves a hit from click to client feedback, and why it is faster
than vanilla without ever leaving the server authoritative.

## Vanilla's pipeline

A vanilla melee hit crosses the wire and then waits — twice:

```
client click
  └─ INTERACT_ENTITY packet ──────────────► netty thread (read, queue)
                                              │ up to 1 tick
                                              ▼
                                            main thread tick
                                              ├─ Player#attack: damage events,
                                              │  armor, invulnerability, knockback
                                              │  computation, hurtMarked = true
                                              │ up to 1 tracker pulse (1–2 ticks)
                                              ▼
                                            entity tracker
                                              ├─ SET_ENTITY_MOTION → victim
                                              └─ HURT_ANIMATION   → viewers
```

Between the packet arriving and the victim's client *feeling* the hit lies
`(queue-to-tick) + (tick-to-tracker-pulse)` — 25–100 ms of server-side dead
time that has nothing to do with network latency.

## Mental's pipeline

```
client click
  └─ INTERACT_ENTITY ───────────► netty thread (PacketEvents)
       ├─ CPS limiter (per-attacker sliding window)
       ├─ validation against tick-frozen snapshots
       ├─ AsyncHitRegisterEvent (cancellable, truly async)
       ├─ vanilla packet cancelled
       ├─ PRE-SEND  ── velocity packet ───────► victim      (T + one-way trip)
       │            └─ hurt animation ────────► victim + attacker
       └─ Scheduling.runOn(victim) ──► owning thread
                                         ├─ re-resolve + re-validate
                                         ├─ DamageCalculator (1.8 damage + crits)
                                         └─ damage(amount, attacker)
                                              └─ full vanilla event chain:
                                                 damage events → knockback module
                                                 → PlayerVelocityEvent → tracker
```

The pre-send is the headline win: the victim's knockback and both players'
hurt feedback ship straight from the netty thread, arriving one round-trip
leg earlier than vanilla's tracker pulse. The main-thread damage that
follows re-emits the same signals through vanilla; clients treat the
duplicates as no-op corrections.

### What is preserved 1:1

Damage events (cancellable by any plugin), armor reduction, invulnerability
windows, hurt sound, the knockback event chain, vanilla's once-per-window
knockback cadence (enforced on the netty thread by a per-victim atomic
gate biased toward *not* pre-sending).

### What is deliberately omitted

Sweep attacks, weapon durability, statistics, advancements, hunger — the
1.8 target audience runs without them, and reimplementing them via internals
would cost the cross-version stability this plugin is built on.

## Why the snapshots

The netty thread may not touch live entities (undefined on Paper, forbidden
on Folia). Every online player therefore snapshots itself once per tick
*from its own owning thread* — fifteen primitive fields into a lock-free
map. The packet thread reads immutable records, never entities. On Folia
each snapshot task rides its player's region; on Paper it is the main
thread; the code is identical.

## Latency compensation

For the victim, the knockback that matters is the one their client expects.
Mental measures combat-time RTT on a private probe channel (the dedicated
play ping/pong packets added in 1.17 — no keep-alive disconnect semantics,
no anticheat transaction collisions), filters spikes against the previous
sample, subtracts a tournament-derived 25 ms offset, and simulates vanilla
gravity forward by the compensated tick count. If the victim's client must
already have landed, the engine computes ground knockback; if airborne sync
is enabled, the engine receives the simulated vertical motion instead of
the server's stale value. The engine owns the formula; the compensator only
corrects its input.

## Anticheat posture

Prediction engines verify what the client did against the velocity the
server's own pipeline produced. Everything above except one step happens in
that pipeline. The exception — the pre-sent velocity packet — is exactly
what `anticheat.mode: auto` suppresses while GrimAC or Vulcan is installed:
the hit still registers at packet speed, knockback reverts to vanilla
cadence, and the prediction engine sees a perfectly ordinary server. The
hurt-animation pre-send is cosmetic and stays on. Mental never cancels
flags and never requests exemptions.

## Edge cases

| Case | Behavior |
| --- | --- |
| First hit after join | Snapshot seeded on join; a missing snapshot skips the pre-send (vanilla cadence) |
| Victim already invulnerable | Cached immunity check + per-victim window gate — no pre-send, no phantom feedback |
| Spam past the CPS cap | Packet cancelled at the netty layer, nothing downstream |
| `AsyncHitRegisterEvent` cancelled | Packet cancelled, hit never existed |
| Target retired mid-flight | Owning-thread task retires silently |
| Server lag spike | Snapshots age but stay consistent; damage path re-validates everything against live state |
| Reload mid-hit | Settings are one atomic snapshot; a hit sees old or new config, never a mix |

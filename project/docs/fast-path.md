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
       ├─ reach validation (optional, ping-rewound — see below)
       ├─ validation against tick-frozen snapshots
       ├─ AsyncHitRegisterEvent (cancellable, truly async)
       ├─ vanilla packet cancelled
       ├─ PRE-SEND  ── ⟦bundle: velocity + hurt⟧ ──► victim  (T + one-way trip)
       │            └─ hurt animation ─────────────► attacker
       └─ Scheduling.runOn(victim) ──► owning thread
                                         ├─ re-resolve + re-validate
                                         ├─ DamageShaper + DamageTables (1.7.10 damage + crits)
                                         └─ damage(amount, attacker)
                                              └─ full vanilla event chain:
                                                 damage events → the delivery desk
                                                 → PlayerVelocityEvent → tracker
```

The pre-send is the headline win: the victim's knockback and both players'
hurt feedback ship straight from the netty thread, arriving one round-trip
leg earlier than vanilla's tracker pulse. The main-thread damage that
follows re-emits the same signals through vanilla; clients treat the
duplicates as no-op corrections.

On 1.19.4+ the victim's burst is wrapped in **Bundle delimiters**
(`fast-path.bundle-feedback`), so the client applies the velocity and the
hurt animation in the same frame — the knock and the flinch can never split
across a frame boundary. Velocity always precedes hurt; a suppressed
velocity ships the hurt bare, never a single-packet bundle. The pre-send
deliberately drives `HURT_ANIMATION` (with an explicit direction yaw)
rather than `DAMAGE_EVENT`: clients couple damage-type effects to the
latter, which the authoritative re-send would double-fire.

The knockback the pre-send predicts is computed from the **victim's
resolved profile**, frozen into their per-tick snapshot — the same profile
the authoritative path resolves on the owning thread, so prediction and
truth can never use different math (see
[knockback-profiles.md](knockback-profiles.md)).

### What is preserved 1:1

Damage events (cancellable by any plugin), armor reduction, invulnerability
windows, hurt sound, the knockback event chain, vanilla's once-per-window
knockback cadence (enforced on the netty thread by a per-victim atomic
gate biased toward *not* pre-sending).

### What is deliberately omitted

Sweep attacks, weapon durability, statistics, advancements, hunger — the
1.7.10 target audience runs without them, and reimplementing them via
internals would cost the cross-version stability this plugin is built on.

## Why the snapshots

The netty thread may not touch live entities (undefined on Paper, forbidden
on Folia). Every online player therefore **publishes** one immutable
`PlayerView` of itself once per tick *from its own owning thread* — a plain
value swapped by a single `AtomicReference.set`. The packet thread reads that
record, never entities. On Folia each session tick rides its player's region;
on Paper it is the main thread; the code is identical. Because the view is
published at the start of the session tick, it is the state as of the *end of
the previous tick* — the boundary read the era ordering depends on holds by
construction.

The motion fields inside each view come from the `MotionLedger` — the decaying
residual of previously applied knockback that makes hits stack the way 1.7.10
delivered them — so the netty pre-send and the owning-thread apply compute from
one model. The era semantics live in [legacy-combat.md](legacy-combat.md).

## Sprint on the wire (w-tap registration)

The era server applied inbound packets in arrival order: an attack always
read its attacker's sprint flag with every earlier STOP/START already
applied, so a w-tap or s-tap counted no matter how little wall-clock
separated it from the follow-up click. The fast path registers attacks
*mid-tick* — faster than the era — but the tick-frozen `PlayerView` it
validates against holds sprint state from the last boundary, up to a tick
**older** than the era contract. A w-tap whose re-press landed in the same
tick as the attack shipped a plain knock; a release that should have denied
the bonus didn't.

The `wtap-registration` feature (default on) restores the in-order read at
packet granularity. The parse rim already observes each player's
entity-action packets on their own netty thread; it additionally feeds the
`SprintWire`'s **arrival-order view** — the flag replayed in packet order,
freshness armed the instant a START arrives, and vanilla's in-attack sprint
clear mirrored onto the wire alone (`onServerClear`). The deferred server-flag
`setSprinting(false)` the delivery desk used to issue beside it is gone since
2.5.0 (it echoed to the attacker's own modern client and latched sprint off), so
the wire clear by itself CONSUMES the engagement. Because a player's packets all
arrive on their own connection thread, the wire view an ATTACK reads is in
program order with the toggles that preceded it: zero added latency, no races by
construction. An owning-thread per-tick reconcile seeds first-sighted players and
re-adopts server-initiated `setSprinting` (which never crosses the wire) once the
wire has been quiet — EXCEPT it will not re-adopt a still-high server flag while a
hit-consume is outstanding (the spend latch): one engagement, one sprint knock,
re-armed past a consumed engagement only by a client-expressed re-gesture (a wire
STOP→START, or the block-hit re-arm).

Registration stamps the `SprintVerdict` it used — sprint and freshness — into
the hit's context, so the pre-sent velocity and the authoritative damage pass
can never disagree about one hit. Synthetic players send no packets and always
fall back to the published view; so does the whole pipeline when the feature is
disabled.

This is strictly faster than the era queue, which quantized both the
toggle application and the attack to tick boundaries (0–50 ms each).
What remains — the client sampling its keyboard once per client tick, and
one-way network transit — is where the information physically lives: a tap
shorter than one client tick never produces packets on any server, era or
modern.

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

## Reach validation (optional)

`hit-registration.reach-validation` (default **off**) is a ping-rewound
sanity gate for player-vs-player packets — the ClubSpigot-lite shape. Every
tracked player keeps a forty-sample position ring (one per tick); an ATTACK
is checked against the victim's hitbox at the instant the attacker actually
*saw* — history rewound by `ping + interpolation-offset-ms` (capped) — plus
their live position, and dropped only when **every** candidate sits beyond
`max-reach + leniency` from the attacker's eye. Deliberately lenient:
borderline hits always land. Creative attackers are exempt and a raised
1.20.5+ `entity-interaction-range` attribute widens the gate. This is a
blatant-reach filter for servers running no anticheat, not an anticheat.

## Anticheat posture

Prediction engines verify what the client did against the velocity the
server's own pipeline produced. Everything above except one step happens in
that pipeline. The exception — the pre-sent velocity packet — is exactly
what `anticheat.mode: auto` suppresses while GrimAC or Vulcan is installed:
the hit still registers at packet speed, knockback reverts to vanilla
cadence, and the prediction engine sees a perfectly ordinary server. The
hurt-animation pre-send is cosmetic and stays on. Reach validation defers
to a detected anticheat the same way — reach is its department. Mental
never cancels flags and never requests exemptions.

## Edge cases

| Case | Behavior |
| --- | --- |
| First hit after join | Snapshot seeded on join; a missing snapshot skips the pre-send (vanilla cadence) |
| Victim without a connection (in-process bots, synthetic players) | No burst can ship and nothing is accounted as wire-delivered; the registration-time vector is **pinned** — the authoritative pass adopts the era-moment values and ships them once, through the normal velocity event, at vanilla cadence |
| Victim already invulnerable | Cached immunity check + per-victim window gate — no pre-send, no phantom feedback |
| Spam past the CPS cap | Packet cancelled at the netty layer, nothing downstream |
| Beyond rewound reach (validation on) | Packet cancelled at the netty layer, best candidate distance debug-logged |
| `AsyncHitRegisterEvent` cancelled | Packet cancelled, hit never existed |
| Target retired mid-flight | Owning-thread task retires silently |
| Server lag spike | Snapshots age but stay consistent; damage path re-validates everything against live state |
| Reload mid-hit | Settings are one atomic snapshot; a hit sees old or new config, never a mix |

---
name: netty-fast-path
description: Use when touching the packet layer — HitPacketListener, FeedbackSenders/FeedbackBurst, pre-send behavior, reach validation, PacketEvents usage, or anything that runs on the netty thread.
---

# The netty fast path (hit-registration)

## Thread split (the load-bearing decision)

Registration on the netty thread (validate against tick-frozen snapshots,
cancel the vanilla packet, optionally pre-send feedback), DAMAGE on the
victim's owning thread (re-resolve, re-validate, `damage(amount, attacker)` —
the full vanilla event chain). This is the surviving conclusion of the
async-knockback fork lineage; full-async entity mutation was abandoned by
everyone who tried it. Pipeline walk-through: docs/fast-path.md.

## Pre-send composition rules (FeedbackBurst — pure, unit-pinned)

- Velocity BEFORE hurt, always.
- Bundle delimiters wrap the burst on 1.19.4+ (`fast-path.bundle-feedback`),
  one `User.writePacket` each + single `flushPackets()` — velocity and flinch
  land in the same client frame. Below 1.19.4: bare, back-to-back.
- Never a single-packet bundle: a suppressed velocity ships hurt bare.
- Velocity suppressors (hurt still ships): AnticheatGate, OCM owning the
  attacker's knockback, pending LEGACY resistance roll, missing snapshots,
  the per-victim feedback window (`auto` = live maxNoDamageTicks/2).
- Pre-send HURT_ANIMATION (explicit yaw = directional tilt), **never
  DAMAGE_EVENT**: clients couple damage-type effects to DAMAGE_EVENT and the
  authoritative re-send would double-fire them. Pre-1.19.4 falls back to
  entity-status 2.
- Duplicates are fine by design: the authoritative path re-emits through
  vanilla; clients treat them as no-op corrections.

## PacketEvents specifics

- Shaded + relocated (`me.vexmc.mental.lib.packetevents.*`) — external code
  (tester) cannot reference PE types at runtime; test seams must be pure
  Mental-side classes instead.
- Built/loaded in `onLoad`, `init()` after modules register listeners,
  terminated after they unregister.
- `getUser(player)` is null for synthetic/disconnecting players — guard and
  skip; never assume a live connection.
- **Listeners fire for EVERY connection state** (handshaking, status, login,
  configuration, play). NEVER downcast `event.getPacketType()` to
  `PacketType.Play.*` — a real client's join traffic throws CCE per packet
  (shipped in 1.3.0; integration suites can't catch it because FakePlayers
  inject in Play state). Compare by REFERENCE against specific constants:
  Configuration has its own KEEP_ALIVE/PONG which must not match the Play
  guard — cancelling one times the client out mid-(re)configuration.
  Regression pin: `ProbeListenerStateTest` (stub `PacketEvents.setAPI` +
  `NettyManagerImpl` + netty on the test classpath make events constructible
  in plain unit tests).
- Sending a Play wrapper to a player mid-(re)configuration (1.20.2+) throws
  inside PE — wrap sends whose target may be reconfiguring in catch-all and
  drop (a missed probe/feedback beats a pipeline exception).

## Reach validation (P5, default OFF)

Ping-rewound sanity gate, ClubSpigot-lite: 40-sample/tick position ring per
player; ATTACK passes unless EVERY candidate (history around
now − ping − interpolation-offset, plus live) puts the victim's 0.6×1.8 AABB
beyond `max(max-reach, entity-interaction-range attribute) + leniency` from
the attacker's eye. Bias to allow: creative attackers, untracked parties, and
any detected anticheat (gate defers — reach is its department) all skip.
It is a blatant-reach filter, not an anticheat.

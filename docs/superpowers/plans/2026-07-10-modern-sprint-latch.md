# The modern-client sprint latch — fix plans (2026-07-10)

Root-caused from the owner's live JOURNAL capture (Mental v2.4.9, velt, owner vs SimpleBoxer,
block-hitting every hit). Verified chain — all PRE-EXISTING, not a 2.4.9 regression
(diagnosis record: the sprint-dropout workflow findings, esp. `sprint-dropout-one-way-latch`,
`blockhit-trigger-born-cancelled-interact`, `packetless-net-ships-armed-difference-hits`,
`B2-journal-sprint-stamp-lies-for-vanilla`):

1. Hit 1 ships the sprint bonus (wire armed by the client's genuine START).
2. `KnockbackUnit`'s deferred `player.setSprinting(false)` (the era in-attack-clear
   reconstruction) is ECHOED to the attacker's own client on modern servers
   (`sendDirtyEntityData → sendToTrackingPlayersAndSelf`, javap-verified 26.1.2).
3. The modern client adopts the echo, drops its local sprint, and confirms with ONE
   STOP_SPRINTING → the wire's raw `clientSprinting` latches false.
4. No START ever returns: 1.21.2+ sends START only on a rising edge of its own flag;
   item-use (block-hitting) blocks STARTING a sprint; the server flag is edge-triggered on
   the entity-action handler and never re-derives (full-jar scan: only
   `ServerGamePacketListenerImpl` and vanilla's own `causeExtraKnockback` — which Mental's
   packet-cancel bypasses — call `setSprinting`).
5. The only reconstruction path, the SWORD_BLOCKING blockhit re-arm, never fires in real
   combat (RIGHT_CLICK_AIR `PlayerInteractEvent` is born cancelled on Paper; the handler is
   `ignoreCancelled=true`; victim-aimed right-clicks fire `PlayerInteractEntityEvent`,
   unlistened), and its gate requires the latched-false `clientSprinting`.

Result: a one-way latch — every subsequent melee verdict reads plain. The journal's
`sprint=f fresh=f h=0.325` on ~900 consecutive owner hits is this latch, verbatim.

## S1 — kill the echo; re-arm from the client's persistent belief (the core fix)

> **SUPERSEDED IN PART (2026-07-10, 2.5.1):** S1(a) — kill the echo — shipped and
> STANDS. S1(b) — the `reconcile` post-clear re-arm from a surviving
> `clientSprinting` — shipped in 2.5.0 and was REMOVED in 2.5.1 by owner
> directive: **one engagement, one sprint knock.** The wire clear now CONSUMES the
> engagement (a spend latch, `clearedAt`, blocks `reconcile` from re-adopting the
> stale-high server flag — an adopt-true latch guard); re-arming requires a
> client-expressed re-gesture (a wire STOP→START, or the `SwordBlockingUnit` block
> re-arm), never an automatic one-tick re-engage. Held-W hits no longer each arm a
> sprint knock (era-measured: a no-w-tap double flew 7.2 blocks, a w-tap double
> 11.4; the 2.5.0 auto re-arm collapsed both to 10.09). Historical S1(b) plan text
> below is untouched — read it under this banner.

**Files:** `core/.../feature/knockback/KnockbackUnit.java` (the deferred-clear obligation),
`kernel/.../wire/SprintWire.java`, `core/.../rim/PacketTap.java` (reconcile call unchanged),
`kernel/src/test/java/.../wire/SprintWireTest.java`, plus skill/doc updates
(`.claude/skills/netty-fast-path/SKILL.md` obligations section,
`.claude/skills/era-accuracy/SKILL.md` client-contract section).

**Design.** Two coupled changes:

(a) **Remove the deferred `player.setSprinting(false)` obligation entirely** (KnockbackUnit's
post-bonus-hit `runOn` task; the wire clear stays). Rationale: the server flag's only Mental
consumer on the melee leg is the WIRE (verdicts read `verdictAt`, never the live flag; the
view fallback serves only packetless attackers, whose own brains manage their flag). Its
echo to the attacker's client is the latch source — a side effect real vanilla never
produces at spam cadence (vanilla's clear sits behind the ≥90% charge gate, where the
client simultaneously predicts the same clear and re-engages, so the echo is always
redundant there). Era analysis: the era server cleared its flag AND the era client
un-sprinted itself + re-sent START with intent held — the observable era contract is the
WIRE cadence (bonus → one-tick gap → re-arm on client intent), which (b) reproduces. The
flag now keeps meaning "what the client last said," which is the only stable truth on
1.21.2+.

(b) **Wire re-arm from surviving client belief.** `SprintWire` gains a post-clear re-arm:
when a movement-packet reconcile runs ≥1 tick after an `onServerClear` (the era re-engage
delay) and the state shows `!sprinting && clientSprinting` (the raw flag — untouched by
clears BY DESIGN — still says the client's last transmitted state is sprinting, i.e. no
STOP followed the hit), set `sprinting=true` (seq bump; `armed` untouched — freshness still
comes only from real STARTs). Implementation shape: `onServerClear` records
`clearedAt=now` in the State; `reconcile(...)` gets the additional branch BEFORE the
seed/adopt logic:

```java
if (!s.sprinting() && s.clientSprinting()
        && s.clearedAt() != TickStamp.NO_TICK
        && now.isAfter(s.clearedAt())) {   // ≥1 tick after the hit's clear — era re-engage
    return new State(true, true, s.armed(), true, s.blockReset(), s.clearedAt(), now, s.seq() + 1);
}
```

(exact record shape per the current State; `clearedAt` reset to NO_TICK on any client
START/STOP so a genuine un-sprint is never overridden). W-tap separation is PRESERVED
exactly where a client expresses it: a client that predicts the attack un-sprint (legacy
via-Via, CADENCE-spoofed modern, slow full-charge clickers) sends STOP → `clientSprinting`
false → no auto re-arm → START (the w-tap) required, freshness armed as today. A client
whose flag never dropped (unspoofed modern spam, block-hitters) re-arms after the era
one-tick gap — matching both its own belief and the modern-vanilla baseline (which never
cleared its flag at low charge either).

**Threading:** all writes stay inside the existing CAS loop on the netty thread (reconcile
call site unchanged at PacketTap's movement handler); `onServerClear` writes `clearedAt`
from the victim's region thread through the same CAS — the State stays a single immutable
record.

**Era/zero-touch:** packetless attackers unchanged (no wire). Legacy-via-Via clients
unchanged (their STOP arrives before any reconcile tick → `clientSprinting` false → no
re-arm branch). The one behavioral change is deliberate and scoped: modern clients that
never expressed un-sprint keep the bonus, at era ctrl-holder cadence.

**Tests (SprintWireTest):** (1) clear → movement reconcile same tick → NO re-arm (gap
holds); (2) clear → reconcile at +1 tick with clientSprinting=true → re-armed, seq bumped,
armed unchanged; (3) clear → client STOP at +0..1 → reconcile at +1 → NOT re-armed
(w-tap contract); then START → armed=true (freshness); (4) packetless/view-fallback paths
byte-identical; (5) the existing 22 pins unchanged except any that pinned the deferred
server-flag clear (delete with the obligation; KnockbackUnit test for the removed runOn).

## S2 — make the blockhit re-arm actually fire

**Files:** `core/.../feature/damage/SwordBlockingUnit.java`, its tests.

(a) The interact listener becomes `ignoreCancelled=false` and self-filters:
`event.useItemInHand() != Event.Result.DENY` (the Paper idiom — RIGHT_CLICK_AIR events are
born cancelled with useItemInHand=ALLOW). (b) Add a `PlayerInteractEntityEvent` handler
(same guards: main hand, sword, feature gates) so victim-aimed right-clicks also re-arm.
(c) The `clientSprinting` gate STAYS — with S1 the flag no longer latches false, and the
gate remains the correct phantom-bonus guard for stationary defensive blocks. Tests: a
born-cancelled RIGHT_CLICK_AIR event reaches resetSprintForBlock; a DENY-use event does
not; the entity-interact path re-arms.

## S3 — era-gate the packetless ensure net

**Files:** `core/.../session/SessionService.java` (`ensureStrandedPacketlessMelee`,
~:425-442), `kernel` desk untouched, tests in core.

Before shipping a stranded packetless pending, corroborate era-silence with the victim's
LIVE `noDamageTicks` at ensure time: a full vanilla accept resets the counter to max (read
≈ max−2 at the +2-tick net), while a mid-invuln difference hit leaves the old countdown
(read < max−3). Ship only the full-accept case; journal the withheld case as a drop with
reason `era-silent-difference` (never silently vanish). This kills the manufactured
velocity-event loop (`ensure → hurtMarked → next armed pending ships`) at its head. Blast
radius: packetless victims only (bots/harness) — this is what was re-knocking the
SimpleBoxer every ~2 ticks in the owner's capture and poisoning feel tests. Tests: staged
desk with a difference-hit pending (old countdown) → withheld + journaled; full-accept
pending → ships as today.

## S4 — journal honesty for vanilla-source hits

**Files:** `core/.../delivery/DamageRouter.java` (the vanilla mint), `kernel/.../delivery/
DeliveryDesk.java` or `core/.../debug/JournalCapture.java` (formatting), tests.

The vanilla-source mint hardcodes `SprintVerdict(false, null)` while the engine reads live
`isSprinting()` — the journal then prints `sprint=f` for hits that shipped sprint-scale.
Fix: mint the vanilla context with the live-read sprint value (same read the formula uses,
region thread, already available at the mint site) so the journal tells the truth; `fresh`
stays null (`-`). Do NOT change which value the ENGINE reads (live, unchanged). Test: a
vanilla-source hit with a sprinting attacker journals `sprint=t fresh=-`.

## Verification

`./gradlew build` after each plan; the matrix before any release. Live validation: the
owner repeats the exact capture scenario — expected journal afterwards: `sprint=t` on
every spam hit while holding sprint (modern client), `sprint=f` for one hit after a real
STOP until the re-engage START, blockhit lines showing the re-arm, and the boxer no longer
receiving `ensured` knocks at 2-tick cadence.

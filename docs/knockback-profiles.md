# Knockback profiles

A **profile** is one complete knockback feel — every knob the engine
consumes, in one file under `plugins/Mental/profiles/`. The server picks one
default and worlds can override it; the selection is **server-wide** — there
is no per-player pin. Mental ships ten presets and loads every `profiles/*.yml`
it finds, so you can keep as many as you like.

## Resolution

The profile that governs a knock is the **victim's** — knockback is the
victim's motion, so a duel arena assigns both fighters the same profile and
a spectator wandering in keeps the world's. Resolution order:

1. **Per-world map** — `knockback.per-world` in `knockback.yml`.
2. **Default** — `knockback.profile` in `knockback.yml`.

Selection is server-wide; the per-player override was retired when management
moved into the in-game GUI. The netty pre-send reads the profile frozen into
the victim's per-tick `PlayerView`, so prediction and the authoritative pass
always resolve the same profile for one hit.

Profiles only shape knocks **Mental owns**: where OldCombatMechanics'
modules govern an interaction (see
[ocm-coexistence.md](ocm-coexistence.md)), OCM's own values apply and the
profile is irrelevant for that hit.

## The shipped presets

Presets are extracted when missing and **never overwritten** — edits are
yours, and deleting a preset regenerates the original. One exception, in
your favor: a preset file that still carries a *superseded bundled
revision* verbatim (every value untouched — you never tuned it) is
upgraded in place when research corrects the preset, with a console
notice. Any edited value freezes the file forever.

| Preset | What it is | Provenance |
| --- | --- | --- |
| `legacy-1.7` | The 1.7.10 model: vanilla-era values with ledger combos. **The default — zero configuration keeps today's behavior.** | Decompiled 1.7.10 (`SYNOPSIS.md`) |
| `legacy-1.8` | Identical math, 1.8.9 flat delivery: melee never feeds itself, rod/projectile residuals still feed the next melee hit once. | Decompiled 1.8.9 |
| `kohi` | The canonical Kohi/HCF values (2016 season) — lower base, gentler sprint bonus, 1.7.10 ledger combos. The 2015 season's hotter set is in the file's header. | Three independent sources + the archived `kohi2016` values, byte-identical |
| `minehq` | MineHQ's archived HCF values — between Kohi and vanilla, ledger combos. The feel era players asked other servers for by name. | Archived values (sprytex/Knockback-Values) |
| `badlion` | Badlion's archived NoDebuff/PotPvP values — softest base of the practice set, strong sprint differential, on the 1.7 back-end Badlion ran (ledger combos). BuildUHC/RodPvP variant in the file's header. | Two independent archives, byte-identical |
| `velt` | VeltPvP's archived values — friction ÷10 residual **wipe**, fixed 0.36 vertical, full sprint horizontal. The late-era "dead consistent" practice shape (Ikari variant in the header). | Archived values (sprytex/Knockback-Values) |
| `mmc` | Minemen Club's archived **dev123 (2017)** values — soft 0.32 base with the full vanilla sprint bonus, flat 1.8 delivery. Replaces the remake-derived revision (assigned vertical + taper). | Two independent archives, byte-identical |
| `lunar` | Lunar Network's archived **Season 5** values — heavy base, split friction, the weakest sprint differential of any archived server (the era's "hold W" complaints are in the numbers, faithfully). Replaces the community-recreation revision. | Two independent archives, byte-identical |
| `signature` | **Mental's own**, derived from `velt` and tuned by playtesting: the same residual wipe and full 0.5 sprint horizontal, with `air.horizontal 0.92` + `air.vertical 0.98` trimming the airborne follow-ups (hits 2+) so the victim holds the reach pocket instead of drifting out, and `base.vertical 0.365` keeping a touch more lift on descending hits (the 0.36 cap still bites on the grounded opener). The only preset that opts into **speed-conformal knockback** (`speed-scaling: attacker`, `exponent 0.95` — the owner's Speed-III feel tune) so Speed/Slowness fights keep the base-speed combo rhythm. | Original Mental tuning (a velt derivative) |
| `custom` | Yours. Ships as legacy-1.7 values with every knob documented in the file. | — |

The full research trail behind these numbers — fork lineage, formula
archaeology, flagged fakes — is in
[research/2026-06-04-improved-knockback.md](research/2026-06-04-improved-knockback.md)
and the archive verification in
[research/2026-06-12-archived-server-values.md](research/2026-06-12-archived-server-values.md).

## The knob vocabulary

Everything lives under `knockback:` in a profile file. Defaults (shown) are
era-exact no-ops — an empty file parses to exactly `legacy-1.7`.

```yaml
knockback:
  base: {horizontal: 0.4, vertical: 0.4}   # the push every hit applies
  vertical-mode: add        # add = vanilla accumulation; set = ASSIGN the
                            # vertical outright — every hit launches
                            # identically (the "static KB" lever)
  extra: {horizontal: 0.5, vertical: 0.1}  # per bonus level: sprint counts
                            # as modifiers.sprint levels, each Knockback
                            # enchant level adds another
  wtap-extra:               # WindSpigot's split: a freshly re-engaged
    enabled: false          # sprint (the w-tap timing) uses these values —
    horizontal: 0.5         # horizontally for sprint levels only (enchant
    vertical: 0.1           # stays on extra), and for the hit's one flat
                            # vertical bonus (vanilla never scales it)
  friction: {x: 0.5, y: 0.5, z: 0.5}       # surviving fraction of the
                            # victim's residual motion (see the hazard note)
  limits:
    vertical: 0.4           # clamps the BASE vertical, before bonuses
    vertical-min: -3.9      # floors the FINAL vertical; -3.9 = off
    horizontal: -1          # caps the post-friction base; <= 0 = off
  air: {horizontal: 1.0, vertical: 1.0}    # multipliers for airborne victims
  add: {horizontal: 0.0, vertical: 0.0}    # flat post-formula offsets,
                            # sign-matched, axis-ratio-distributed
  range-reduction:          # the MMC reach softening: beyond
    enabled: false          # start-distance the horizontal push loses
    start-distance: 3.0     # min(factor × (d − offset), max-reduction).
    factor: 0.025           # Melee only — rods and projectiles never taper.
    offset: 1.2
    max-reduction: 0.12
  delivery:                 # the wire half of era accuracy. tracker ships
    melee: tracker          # the full stamp (vanilla's 1.7.10 tracker wire
    projectile: tracker     # was CONNECTION-ORDER bimodal — measured both
                            # orders on real vanilla — and the dominant mode
                            # shipped undecayed, identical to 1.8.9);
                            # tracker-decayed opts into the later-joiner
                            # wire (one victim physics tick of decay, ground
                            # hits lose ×0.546 horizontal); immediate = the
                            # 1.8.9 in-attack send
  modifiers:
    sprint: 1.0             # bonus levels granted by sprinting
    combos: true            # 1.7.10 ledger stacking; false = 1.8.9 flat
    armor-resistance: none  # none | legacy (probabilistic) | scaling
    shield-blocking-cancels: true
  speed-scaling:            # speed-conformal knockback (pace scaling)
    mode: off               # off = no scaling (era-exact); attacker = scale
                            # the HORIZONTAL knock by the attacker's movement
                            # speed over the walk baseline (0.10)
    exponent: 1.0           # s = (attr / 0.10)^exponent; 1.0 = fully conformal
                            # (the PARSE DEFAULT; signature ships 0.95)
    min: 0.5                # clamp on the final factor
    max: 2.0
```

### Blocked hits still knock (the `BLOCKS_ATTACKS` silent-knock trap)

Era truth (combat compendium): sword blocking halves damage **after** `knockBack`
already ran, so a **partial** block knocks the victim **FULL** — only a would-be
non-positive result is a "full block", cancelled iff `shield-blocking-cancels:
true`. Mental owns the knock, so the `KnockbackUnit` shapes both:
`isApplicable(BLOCKING) && getDamage(BLOCKING) < 0` distinguishes a block, and
`getFinalDamage()` splits full (`≤ 0` → withdraw) from partial (`> 0` → knock).

The delivery trap this fix closes (live-reproduced on Folia 1.21.11, javap-traced
against the real jar): on the **`BLOCKS_ATTACKS` component tier (1.21.5+)** vanilla
reduces a blocked hit **natively** but **skips `markHurt`**, so it broadcasts no
`ENTITY_VELOCITY` and fires no `PlayerVelocityEvent`. Mental's desk `await` then
had nothing to resolve and the tick sweep dropped the pending as
`no-velocity-event` — the blocked hit landed at halved damage with **no knockback
and no flinch**, contradicting the era. (The software tiers — `CONSUMABLE`
1.21.0–1.21.4 and the ≤1.20.6 off-hand shield — reduce the *base* damage with no
native `BLOCKING` modifier, so vanilla fires its velocity event normally; and a
frontal shield full-block is `finalDamage ≤ 0`, handled by the cancel. The bug is
`BLOCKS_ATTACKS`-specific.)

The fix (`KnockbackUnit.deliverBlockedKnock`): a **fresh** partial block (negative
`BLOCKING`, `finalDamage > 0`, and the victim **not** mid-invulnerability) delivers
the era vector **directly** through the desk's no-velocity-event path (the same
machinery the thrown-projectile ensure uses) — the original pending is withdrawn so
the sweep records no false drop, a fresh transaction is submitted next tick and
`setVelocity` triggers the velocity event the desk resolves to the **full stamp**
(undecayed) and journals a `SHIP`. Presentation mirrors the fast path: a client
with no wire pre-send gets a `VELOCITY + HURT` burst, and the **quantized valve**
is armed whenever a wire copy already carries the value so the authoritative
tracker re-emission (or a late vanilla velocity for the same hit) is consumed
once, never doubled. The **victim's own hurt sound** is played to the victim
alone in both branches (`Sound.ENTITY_PLAYER_HURT`, pitch `1 + (r1 − r2) × 0.2`):
`playHurtSound`'s broadcast excludes exactly the victim, and the victim's own
1.19.4+ client derives its hurt sound from the `ClientboundDamageEventPacket`
that the blocked branch replaces with a no-op `onBlocked` (`blockSound`
`Optional.empty()`, so no anachronistic shield clang) — so the one missing
audience is the victim (javap-verified; the 2.4.0 note that assumed vanilla still
served it was wrong). A world broadcast would double the sound for bystanders. A **mid-invulnerability**
blocked hit stays **era-silent** — the vanilla difference branch carries no knock,
no flinch, on every version, and Mental does not "fix" it. Regression:
`BlockingSuite` (the `BLOCKS_ATTACKS` fresh-ship + in-window-silence cases, forcing
the native block state with `startUsingItem` since a clientless fake never raises it
over the wire).

### ⚠ The #1 porting hazard

`friction` here is the **surviving fraction** of the victim's motion
(vanilla "divide by 2" ≡ `0.5`) — the NachoSpigot convention. The forks
that publish a friction **divisor** (PandaSpigot, SportPaper, WindSpigot —
typically `2.0`) port as `1 / divisor`:

| Published divisor | Mental friction |
| --- | --- |
| 2.0 | 0.5 |
| 1.5 | 0.6667 |

Pasting a divisor value in unchanged inverts the feel entirely.

### Speed-conformal knockback (pace scaling)

A combo is a spacing equilibrium between three speeds: the victim's
knock-**flight** (an absolute velocity stamp — it does *not* scale with the
Speed attribute) and the attacker's ground **chase** and victim's ground
**flee** (both ×1.6 at Speed III). At base speed they balance; Speed III
scales chase and flee but leaves flight untouched, so the attacker overshoots
and nobody can combo anyone — every fixed-stamp system (all of era 1.7/1.8)
breaks the same way.

`speed-scaling` makes the knock **conform**: it multiplies the **horizontal**
components Mental delivers by

```
s = clamp(min, max, (attr / 0.10)^exponent)
```

where `attr` is the attacker's **walk-stance-normalized** movement-speed
attribute against a **single walk baseline** of `0.10`. The attribute is
normalized at capture: `isSprinting()` and the effective value are read
back-to-back on the owning thread and the ×1.3 sprint modifier is divided back
out when sprinting — the flag and the modifier move together inside
`setSprinting`, so stripping that one stance-churning term makes the factor
immune to a wire-vs-server stance disagreement (the 2.4.0 desync that shipped
intermittent weak knockback). Every *length* in the combo then scales together
while every *time* (flight duration, click cadence, the immunity window) stays
fixed — a spatially-zoomed replica with identical rhythm. **Vertical is never
scaled** (that would stretch flight time and desync the rhythm). Plain
base-speed play yields `s ≈ 1.0` (the live base attribute carries only ~1.5e-8
of float slack, below the wire quantum), so the era stamp ships
byte-identically; a Speed III attacker (normalized `attr 0.16` / `0.10` = `1.6`)
yields `1.6^0.95 ≈ 1.563` at the signature's `0.95` exponent, and Slowness gives
`s < 1` (slowed players stay comboable). Melee only — rods and projectiles are
unaffected.

`mode: off` (the default, and every archived-server preset) is a complete
no-op. Only Mental's own `signature` preset opts in (`exponent 0.95`). Reach
(`3.0`) cannot scale, so the `max` clamp marks where the conformal window ends
(Speed V+ still compresses the margin).

## Runtime control

Management is in-game. `/mental` (permission `mental.command.use`) opens the
management menu; its **Knockback** screen lists every loaded profile and the
server-wide default and switches it. A switch writes the machine-owned overlay
(`state/overrides.yml`) and reloads — the human `knockback.yml` is never
re-serialized, and the effective default is shown with the overridden key
marked. `/mental reload` (console, permission `mental.command.reload`) re-reads
every file; a default pointing at a profile that vanished falls back to
`legacy-1.7` with a warning.

### API

```java
MentalApi mental = Mental.get();
mental.setKnockbackProfile("kohi");            // server-wide; validated, returns false if unknown
String active = mental.knockbackProfile();      // the server-wide default
Set<String> available = mental.knockbackProfiles();

// React to a switch (fired on a global change — there is no player):
@EventHandler
public void onProfileChange(KnockbackProfileChangeEvent event) {
    // event.getPreviousProfile() / event.getNewProfile()
}
```

`setKnockbackProfile` routes through the same write-back path (`Management`) as
the GUI, so the API and the menu can never disagree.

## What profiles deliberately do not include

Recorded as non-goals after the research round:

- **Hit delay / `noDamageTicks`** — combat *rules*, owned by
  OldCombatMechanics' `attack-frequency`; Mental's feedback pacing follows
  it automatically (`feedback-min-interval-ms: auto`).
- **Randomized knockback** — the most-hated era of every server that tried
  it. Determinism is the product.
- **CPS-scaled anything / victim "reduce" toggles** — the 0.6
  self-velocity multiplier, w-tap re-arming, jump-resets and strafe
  redirection are client-side technique, preserved by Mental's
  client-authoritative design. They keep working; nothing here touches
  them.

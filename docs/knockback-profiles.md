# Knockback profiles

A **profile** is one complete knockback feel — every knob the engine
consumes, in one file under `plugins/Mental/profiles/`. The server picks a
default, worlds can override it, and individual players can be pinned to a
profile at runtime (the practice-core path). Mental ships six presets and
loads every `profiles/*.yml` it finds, so you can keep as many as you like.

## Resolution

The profile that governs a knock is the **victim's** — knockback is the
victim's motion, so a duel arena assigns both fighters the same profile and
a spectator wandering in keeps the world's. Resolution order:

1. **Per-player override** — `/mental kb set <profile> [player]` or
   `Mental.get().setKnockbackProfile(player, name)`. Survives world
   changes, clears on quit.
2. **Per-world map** — `knockback.per-world` in `knockback.yml`.
3. **Default** — `knockback.profile` in `knockback.yml`.

Profiles only shape knocks **Mental owns**: where OldCombatMechanics'
modules govern an interaction (see
[ocm-coexistence.md](ocm-coexistence.md)), OCM's own values apply and the
profile is irrelevant for that hit.

## The shipped presets

Presets are extracted when missing and **never overwritten** — edits are
yours, and deleting a preset regenerates the original.

| Preset | What it is | Provenance |
| --- | --- | --- |
| `legacy-1.7` | The 1.7.10 model: vanilla-era values with ledger combos. **The default — zero configuration keeps today's behavior.** | Decompiled 1.7.10 (`SYNOPSIS.md`) |
| `legacy-1.8` | Identical math, 1.8.9 flat delivery: melee never feeds itself, rod/projectile residuals still feed the next melee hit once. | Decompiled 1.8.9 |
| `kohi` | The canonical Kohi/HCF values — lower base, gentler sprint bonus. | Confirmed across three independent sources |
| `mmc` | The community remake of the Minemen Club feel: **assigned vertical** + **distance taper**, flat delivery, zero randomness. Not the private server's exact values — nobody outside MMC has those. | `MatiasNoble3/ClubSpigot` remake, label swap resolved (see the file's header) |
| `lunar` | A community recreation of Lunar-network-era knockback — more residual survives each hit. | Community recreation (confidence: likely) |
| `custom` | Yours. Ships as legacy-1.7 values with every knob documented in the file. | — |

The full research trail behind these numbers — fork lineage, formula
archaeology, flagged fakes — is in
[research/2026-06-04-improved-knockback.md](research/2026-06-04-improved-knockback.md).

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
```

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

## Runtime control

```
/mental kb                       list profiles, default, per-world map
/mental kb info <profile>        a profile's key values
/mental kb set <profile> [player]   pin a player (self when omitted)
/mental kb reset [player]        back to world/default resolution
```

All behind `mental.command.knockback` (default: op). `/mental reload`
re-reads every file; overrides pointing at a profile that vanished are
cleared with a warning.

### API (practice cores)

```java
MentalApi mental = Mental.get();
mental.setKnockbackProfile(victim, "kohi");   // validated; null clears
String active = mental.knockbackProfile(victim);  // resolved name
Set<String> available = mental.knockbackProfiles();

// Mirror kit/arena swaps:
@EventHandler
public void onProfileChange(PlayerKnockbackProfileChangeEvent event) {
    // event.getPreviousProfile() / getNewProfile() — null = no override
}
```

Call profile mutations from the player's owning thread.

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

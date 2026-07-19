# Combat Test 8c

Mental can replay Minecraft's final combat experiment — Java Edition Combat
Test 8c (October 2020, the `1.16_combat-6` snapshot) — on any server in the
supported range. Every number below was ground-truthed against the decompiled
8c client (SHA1 `177472ace3ff5d98fbd63b4bcd5bbef5b035a018`, Mojang-mapped),
cross-checked against jeb_'s changelogs and his damage spreadsheet; where
sources disagreed, the shipped binary won. The full verified list with
provenance lives in `docs/superpowers/specs/2026-07-18-combat-test-8c-design.md`.

## Turning it on

One click: **/mental → Combat Presets → Combat Test 8c**. The bundle flips the
thirteen CT8c rule features on, turns every classic-era rule off (they would
double-apply), and selects the `ct8c` knockback profile. The delivery engine —
latency compensation, hit registration, the pre-send — stays as you configured
it; that part is why Mental is installed. `signature` (Mental's full classic
suite) and `vanilla` (everything off, modern-vanilla knockback — Mental
transparent) sit beside it; applying another bundle is the undo. Bundles are
macros: one atomic overlay batch + one reload, your hand-edited YAML files are
never rewritten.

## What you get (all server-side, all default-OFF outside the bundle)

- **Per-weapon attack speeds** — sword 3.0 att/s, axe/shovel/trident 2.0,
  pickaxe/fist 2.5, hoes 2.0→3.5 by tier. Real attribute values: the client
  renders the true cooldown.
- **Charged attacks** — no damage below 100% charge (with 8c's lenient 4-tick
  miss-recovery lane), the 200% overcharge window, +1.0 reach at ≥195% unless
  sneaking.
- **The 8c damage tables** — every tier one point down (netherite sword 7,
  axe 8), fists 2, trident 7, hoes 2/2/3/2/3/4.
- **Crits** — sprinting no longer cancels them; enchant damage folds in before
  the flat ×1.5.
- **Sweep** — requires Sweeping Edge (axes eligible via book+anvil), ≥195%
  charge, halved ratios (25/33.3/37.5%).
- **I-frames** — fast weapons grant shorter immunity (`min(weapon delay, 10)`
  ticks); projectiles grant none, so snowball/egg/arrow spam all connects.
- **Shields** — instant, a 148° protection cone, cap 5 damage per melee hit
  with the excess passing through, **projectiles blocked in full through that
  same 148° cone** (a piercing arrow bypasses it), crouching blocks (damage-math,
  melee and projectiles) with an offhand shield on the ground, 50% knockback
  resistance while blocking, axes always disable for 1.6s (+0.5s per Cleaving
  level) and cost 1 durability.
- **Food** — 1.8-style regen: +1 HP every 2s above 3 shanks, a 50% chance to
  drain hunger per heal, no saturation fast-heal; drinks take 1s; getting hit
  by a player or mob interrupts eating or drinking (only those — a raised shield
  or drawn bow is never dropped).
- **Potions** — Instant Health 3 hearts (6·2^level), Strength/Weakness become
  ±20% per level, drinkable potions stack to 16.
- **Projectiles** — snowballs and eggs knock players (0 damage, full 0.4
  knock, 4-tick throw gate, snowballs stack 64), bow fatigue after 3 seconds
  drawn ramps the spread up and shakes off critical arrows (a fresh shot still
  carries 8c's small 0.125 base spread, never perfectly straight),
  thrown-projectile momentum only in the aim direction, Loyalty tridents return
  from the void.
- **Cleaving** — a real registered axe enchantment on modern Paper
  (registry-injected, ~1.21.4+): +2/+3/+4 damage, +0.5s shield-disable per
  level, exclusive with Sharpness.
- **The `ct8c` knockback profile** — 8c's exact vertical shape: grounded
  victims get `min(0.4, 0.75·strength)`, airborne victims gain
  `min(0.4, vy + 0.5·strength)` — the "extra air knockback" 8c is remembered
  for.
- **Targeting assists** — small hitboxes inflate to 0.9 blocks for hit
  acceptance (8c's replacement for the removed CT5 "coyote time", which never
  reached 8c and is deliberately absent here).

## What a server cannot give you (the honest gap list)

Client-authoritative, impossible from a plugin:

- Hold-to-attack auto-swinging (and its +1-tick pacing / 5-tick auto-miss).
- The attack indicator's 130%→200% display and the charge meter itself.
- The shield model visually raising while crouch-blocking (the damage math
  applies; the arm animation cannot).
- Snowballs hiding for their first 2 flight ticks.

Not client-impossible, but not implemented here:

- The 80–100% charge click buffer (`retainAttack`). Reconstructable
  server-side for a vanilla client — swallow the sub-100% swing packet, then
  on the next available tick raytrace the crosshair target and re-drive a
  synthetic attack — but that requires the server to own swing→target
  reconstruction, which the rewound-reach path does not currently do.

Version-gated (era-correct fallback + one loud boot-report line below):

- Potion stacking, 20-tick drinks (potions/milk/honey drunk, bowl stews eaten),
  snowball stacking, eat-interrupt: 1.20.5+.
- The 3.5-block hoe/trident reach: 1.20.5+ (the client cannot aim past 3.0
  below that; ≤3.0 values are honored everywhere via Mental's own reach gate,
  which also applies the charged bonus and hitbox inflation leniently — it is
  a blatant-reach filter, never an anticheat).
- Cleaving: ~1.21.4+ (no registry API below; the shield-disable then runs at
  its flat 1.6s base).

Deliberate scope calls:

- **Banner shields are omitted** (10-absorption banner-decorated shields).
  They are in 8c, but jeb_ called them "not the intended design, just the
  quickest way of testing different kinds of shields" — the owner chose to
  leave the experiment in 2020.
- Starvation stays vanilla-paced (8c's 40-tick starvation would mean
  re-implementing per-difficulty death floors for a non-combat mechanic).
- Tipped-arrow instant effects keep vanilla's scale (the Bukkit surface
  carries no amplifier to rescale by); Instant Damage is untouched because
  vanilla already deals 8c's 6/level.
- Sweep applies on vanilla-path hits (fast-path-delivered hits bypass the
  vanilla attack routine, same as the classic sweep rule).
- Mob melee stats are out of scope (Mental is a PvP plugin).

Interactions the bundle already arbitrates (mind them if you hand-toggle):
`ct8c-crits` vs `old-critical-hits`/`simulate-crits` double-crits;
`weapon-attack-speeds` vs `attack-cooldown` (the removal spoof wins if both
are on); `combo-hold` owns the same immunity window as `ct8c-iframes`.

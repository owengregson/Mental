# Combat Test 8c import — design

Owner-approved 2026-07-18. Scope: import every combat change from Java Edition
Combat Test 8c (the `1.16_combat-6` experimental snapshot) that can live
server-side, package it as a selectable **rules bundle** (a third preset
mechanism), and ship two sibling bundles (`signature`, `vanilla`). Branch
`feat/combat-test-8c` from `origin/main`, PR to `main`.

## 1. Ground truth and provenance

Three independent verification tracks (adversarial wiki changelog pass, jeb_'s
primary posts + his `Minecraft Damage Calculations (v2).xlsx`, and a full
decompile of the SHA1-verified `1_16_combat-6` client remapped with Mojang's
official mappings). Where sources conflicted, **the decompiled binary wins**.
Decompile artifacts live in the session scratchpad (`ct8c/src/`), and every
number below is code-confirmed unless marked otherwise.

Conflicts resolved by code: pickaxe speed **2.5** att/s (spreadsheet said 2.0);
iron/diamond hoe damage **3.0** (wiki said 3.5); shield arc **148°** (an earlier
pass said 100°); sweep charge gate **≥195% for swords AND axes** (wiki said
185/190); crit is a flat **×1.5** (the code's ≥195% "special crit" ternary is a
dead no-op — both branches 1.5).

**Dropped — not in 8c**: coyote time (CT5-only; CT6 verbatim "Removed 'Coyote
Time'", replaced by the 0.9-block hitbox inflation, which IS in 8c).
**Excluded by owner decision**: banner shields (in 8c, but jeb_ flagged them
"not the intended design, just the quickest way of testing different kinds of
shields" — document as an intentional omission).
**Out of scope**: the mob melee-damage rebalance (PvE tuning, outside Mental's
lane).

## 2. The verified CT8c mechanic list (implementation ground truth)

### 2.1 Attack timing / charge (code: `Player`, obf `bft`)

- Attack cooldown charges to **200%**: `getAttackStrengthScale` returns
  `clamp(2·(1 − ticker/startValue), 0, 2)`; full delay = `getAttackDelay()·2`
  ticks, 100% at the halfway point.
- `getAttackDelay() = (int)(20 / clamp(attackSpeed − 1.5, 0.1, 1024) + 0.5)`.
  The wiki's "attacks per second" = `attackSpeed − 1.5`.
- Attacks only at scale ≥ 1.0, EXCEPT miss recovery: an air swing sets
  `missedAttackRecovery=true`, allowing re-attack once
  `startValue − ticker > 4.0` ticks (a lenient 4-tick partial-recharge gate,
  not a hard lockout). A landed hit requires full recharge.
- Auto-attack (client hold) pays +1 tick via `isAttackAvailable(-1.0f)` — this
  is client-side pacing; server-side we cannot detect "holding". GAP (see §7).
- Item switch does NOT reset the attack timer; only attacking resets it.
- Creative is exempt from cooldowns; Creative's old reach bonus is removed.
- Charged reach bonus: `+1.0` block when `scale > 1.95 && !isCrouching()`.
- Click buffer `retainAttack` (a click at 80–100% charge re-fires at 100%
  against the CURRENT crosshair target) — client-side. GAP.

### 2.2 Attack speed / reach / damage tables (code: `WeaponType`, `Attributes`, `Tiers`)

Attribute bases: ATTACK_SPEED 4.0 (min 0.1 max 1024); ATTACK_REACH **2.5**
(min 0.0 max 6.0); player ATTACK_DAMAGE base 2.0 (fist = 2). Weapon values are
ADDITION modifiers.

| item | att/s (speed) | delay ticks | reach | damage (wood/stone/iron/gold/diamond/netherite) |
|---|---|---|---|---|
| fist | 2.5 (4.0) | 8 | 2.5 | 2 |
| sword | 3.0 (4.5) | 7 | 3.0 | 4/4/5/4/6/7 |
| axe | 2.0 (3.5) | 10 | 2.5 | 5/5/6/5/7/8 |
| pickaxe | 2.5 (4.0) | 8 | 2.5 | 3/3/4/3/5/6 |
| shovel | 2.0 (3.5) | 10 | 2.5 | 2/2/3/2/4/5 |
| hoe | 2.0/2.5/3.0/3.5/3.5/3.5 | 10/8/7/6/6/6 | 3.5 | 2/2/3/2/3/4 |
| trident | 2.0 (3.5) | 10 | 3.5 | 7 |

(Damage composition: player base 2.0 + weapon-type bonus + tier bonus, tier
bonuses WOOD 0 / STONE 0 / IRON 1 / GOLD 0 / DIAMOND 2 / NETHERITE 3 — one
lower than vanilla across the board. Hoe ignores tier bonus: flat +1.0
iron/diamond, +2.0 netherite. Trident flat +5.0.)

### 2.3 Crits, sweep

- Crit: `fallDistance > 0 && !onGround && !climbing && !inWater && !blind &&
  !passenger` — **no sprint exclusion**, no crit-specific charge gate beyond
  attack availability. Flat ×1.5.
- Enchant damage (Sharpness etc.) is added to ATTACK_DAMAGE **before** the crit
  multiplier (vanilla adds it after).
- Sweep: requires `scale > 1.95` AND Sweeping Edge ratio > 0 (plain swords no
  longer sweep) AND not-crit AND not-sprint-knockback. Ratios
  `0.5 − 0.5/(level+1)` = **0.25 / 0.333 / 0.375**. Sweep damage to secondaries
  `1 + ratio·mainDamage`, sweep knockback 0.4. Sweeping Edge applicable to axes
  via book+anvil.

### 2.4 I-frames (code: `LivingEntity.hurt`)

`invulnerableTime = min(attackerPlayer.getAttackDelay(), 10)` for melee;
**0 for every projectile source** (arrows, snowballs, eggs re-hit freely).

### 2.5 Knockback (code: `LivingEntity.knockback`, `Player.attack`)

After scalar resistance (`strength = f·(1−resist)`):

```
vx = old.x/2 − dir.x·strength
vy = onGround ? min(0.4, 0.75·strength)          // grounded
              : min(0.4, old.y + 0.5·strength)   // airborne: ADD, capped
vz = old.z/2 − dir.z·strength
```

Sprint adds +1 knockback level; attacker self-velocity ×0.6 and sprint cleared
(vanilla). Hits dealing 0 damage still knock (no damage>0 guard). Knockback
attribute has NO random full-negate (scalar only). Shields grant 0.5 knockback
resistance while blocking.

### 2.6 Shields (code: `LivingEntity.isDamageSourceBlocked`/`blockedByShield`, `ShieldItem`)

- Arc: blocked when `dot(viewDir, attacker→victim)·π < −0.8726646` (−50° rad)
  → **≈148° frontal cone** (vanilla 180°).
- **Instant**: `isBlocking` = using a BLOCK-anim item, no 5-tick use-duration
  gate.
- **Crouch-to-shield**: offhand shield blocks while `onGround && crouching`
  (or passenger), no right-click — and 8c disabled it while jumping
  (the onGround condition). Accessibility toggle default on.
- Melee cap: `blocked = min(5.0, damage)`, **excess passes through**. (No
  shield_strength attribute is registered — hardcoded 5.0.) Projectiles and
  explosions remain 100% blocked. Crits do not bypass.
- Axe hit **always** disables: `1.6s + 0.5s·CleavingLevel` (32 + 10/level
  ticks) via item cooldown; blocked victim also knocked 0.5. Axes cost **1**
  durability on attack (vanilla 2).

### 2.7 Food / regen (code: `FoodData` — the 1.8-style model)

- Regen: every **40 ticks**, +1 HP, while `foodLevel > 6` and hurt; **50%
  chance to drain 1 hunger** per heal. Saturation fast-heal path REMOVED
  (saturation only pauses hunger loss).
- Starvation: every 40 ticks at foodLevel 0. Sprint requires foodLevel > 6.
- Eat durations: solid 32 ticks (fast food 16); **drinking 20 ticks**
  (potions/stews/milk/honey — vanilla 32).
- Eating/drinking interrupted when hit **by a player or mob** (returned in
  CT8b; ON in 8c).

### 2.8 Potions / effects (code: `MobEffects`, `AttackDamageMobEffect`, `Items`)

- Instant Health heals `6·2^amp` (I=6, II=12); Instant Damage mirror.
- Strength = ATTACK_DAMAGE ×(+0.2·(amp+1)) MULTIPLY_TOTAL (**+20%/level**);
  Weakness −20%/level. (Replaces vanilla flat +3/−4.)
- Drinkable potions `stacksTo(16)`; splash/lingering stay 1.
- Tipped-arrow instantaneous effects scaled ×1/8.
- Regeneration effect interval `50 >> amp`, Poison `25 >> amp` (vanilla-same;
  listed for completeness).

### 2.9 Enchantments

- **Cleaving** (axe-only, max 3, incompatible Sharpness/Smite/Bane): damage
  bonus `1 + level` (+2/+3/+4), shield-disable +10 ticks/level (consumed by
  §2.6).
- Axes accept Fire Aspect / Looting / Knockback / Sweeping Edge via
  book+anvil only.
- Impaling applies to all mobs in water or rain. Loyalty returns from the
  void.

### 2.10 Ranged / projectiles

- Snowballs and eggs: 0 damage to players but full 0.4 knockback; no i-frames
  (§2.4); 4-tick throw gate (client `rightClickDelay` — server emulates via
  the Cooldowns API); snowballs stack to 64; first-2-tick render suppression
  is client-only (GAP).
- Bow fatigue: accuracy decay only after **3 seconds** held; fatigued shots
  cannot be critical arrows. Base/crossbow inaccuracy 0.25.
- Player momentum added to projectiles **only in the aim direction** (vertical
  momentum never).
- Multishot arrows all connect (consequence of projectile i-frame bypass).

### 2.11 Targeting assists

- Entities with bounding boxes < 0.9 blocks are inflated to 0.9 **for attack
  targeting**.
- Attacks connect through non-solid blocks (grass, vines) without breaking
  them.
- Attack indicator shows 130%→200% only — client visual, GAP.

## 3. Architecture

Three deliverable layers, all honoring zero-touch, era-exact no-op defaults,
additive-only kernel, and the single-writer domain rules.

### 3.1 Kernel (additive only)

- `ModernKnockback` gains an optional **vertical-shape** component
  (`VerticalShape { VANILLA, CT8C_SPLIT }` + `groundedFactor`,
  `airborneFactor`, reusing the existing `verticalCap`), default VANILLA via a
  delegating constructor — every existing preset constructs byte-identical;
  `parse(empty) == LEGACY_17` untouched. Engine applies §2.5 exactly when
  CT8C_SPLIT. Hand-computed unit pins.
- New pure-math classes under `kernel math/`: `Ct8cTables` (speed/delay/reach/
  damage per §2.2 incl. `attackDelay(speed)`), `Ct8cChargeMath` (scale, miss
  recovery, 1.95 gates), `Ct8cShieldMath` (arc dot test, 5-cap split),
  `Ct8cRegenMath`, `Ct8cPotionMath`. Exhaustive unit tests with hand-computed
  expectations; provenance comments cite this spec + the decompile.
- `Presets` gains `CT8C` (see §4); kernel `PresetsTest` era-pins it.

### 3.2 New rule features (core)

All default-OFF, one `Feature` constant + settings record + `parseX` +
`settingsFor` case + unit each, registered in `registerUnits`, converged by the
reconciler. Sub-floor listener-descriptor rule and once-at-boot probe idiom
apply throughout.

| yamlKey | Family | contract |
|---|---|---|
| `weapon-attack-speeds` | CADENCE | Weapon-aware ATTACK_SPEED attribute recompute on held-item change (HitboxUnit reconcile template + `AttributeModifiers`); settings = per-class att/s map defaulting to the §2.2 table. Client renders the true cooldown. Works to 1.9.4 (attribute exists since 1.9). Mutually exclusive in spirit with `attack-cooldown` (the removal spoof); bundles arbitrate. |
| `charged-attacks` | CADENCE | Server-side charge ledger per player (from attack timestamps × current attack speed): reject damage below 100% except the 4-tick miss-recovery lane; ≥195% grants +1.0 reach (fed to reach validation) unless sneaking; publishes charge for `ct8c-sweep`. D2-owned state, netty reads published views only. |
| `ct8c-damage` | DAMAGE | §2.2 damage tables via the `DamageShaper` seam (fist 2, tiers −1, hoe/trident flats). |
| `ct8c-crits` | DAMAGE | Sprint-crit allowed, enchant-in-base ordering, flat ×1.5 (CritFallbackUnit twin, CT8c policy). |
| `ct8c-sweep` | CADENCE | Sweeping-Edge-required, ≥195% charge (when `charged-attacks` enabled; else enchant-gate only), ratios 0.25/0.333/0.375, axe anvil eligibility via `PrepareAnvilEvent`. |
| `ct8c-iframes` | DAMAGE | `min(attackerDelay, 10)` melee, 0 projectiles — through the platform `SpawnInvulnerability`-safe seam (NEVER a raw positive `setNoDamageTicks` on 1.16.5–1.20.6). |
| `ct8c-shields` | DAMAGE | §2.6 complete: 148° arc + 5-cap passthrough + instant + crouch-shield damage math (onGround only) + 0.5 KB resist + axe disable 32t (+10t/Cleaving) + axe 1-durability. Banner shields intentionally omitted. |
| `ct8c-regen` | SUSTAIN | §2.7 regen/starvation/sprint-gate + eat-interrupt on player/mob hit. |
| `ct8c-consumables` | SUSTAIN | 20-tick drinks, potion stack 16, snowball stack 64 — 1.20.5+ item components via the `AttackRangeAdapter`-style component seam; loud boot degrade below. |
| `ct8c-potions` | SUSTAIN | Instant Health/Damage 6·2^amp, Strength/Weakness ±20%/level (attribute-modifier substitution), tipped ×1/8. |
| `ct8c-reach` | LOADOUT | Base 2.5 / sword 3.0 / hoe+trident 3.5 (+ charged bonus from `charged-attacks`): 1.20.5+ interaction-range attribute + item components; below, values ≤3.0 enforced by Mental's own reach validation, 3.5 impossible → loud degrade. Also owns the targeting assists (§2.11): 0.9 hitbox inflation and through-non-solid acceptance inside Mental's rewound reach validation. |
| `ct8c-projectiles` | KNOCKBACK | Snowball/egg 0-damage knockback + 4-tick Cooldowns gate; bow fatigue (3s → spread + crit-arrow cancel); aim-direction-only momentum; arrow spread 0.25. (Projectile i-frames live in `ct8c-iframes`.) |
| `cleaving` | DAMAGE | Real enchantment on modern Paper via platform-seam registry injection (`CleavingRegistrar`, probe-gated ~1.21.3+, exact floor decided by nms-archaeology during implementation; unfreeze→register→refreeze contained entirely in the seam, loud degrade below/on failure). Damage +1+level folded through `ct8c-damage`; disable-scaling consumed by `ct8c-shields`. |

### 3.3 Rules bundles (the third preset mechanism)

Mirrors the knockback/effects preset pattern:

- Files: `bundles/<name>.yml` (data folder), bundled resources extracted only
  when missing; `SupersededBundles` (SHA-256 raw-byte twin of
  `SupersededPresets`) for in-place upgrades of unedited bundled revisions.
- Schema: `display-name`, `description`, `knockback-profile` (optional),
  `effects-preset` (optional), `modules:` (yamlKey → bool; a bundle states
  every module it owns explicitly), `settings:` (optional dotted overlay
  keys).
- Semantics: a bundle is a **macro, not a mode** — `Management.applyBundle
  (name)` validates, writes one batch of machine-overlay keys, and performs a
  single `reloadAll()` snapshot swap. Human YAML never re-serialized. A
  `RulesBundleAppliedEvent` fires (api gen 3 additive).
- GUI: a "Combat Presets" screen off the dashboard (Menu/Icon pattern) listing
  discovered bundles → applyBundle. `/mental` GUI remains the only admin
  surface.

Shipped bundles:

- **`ct8c`** — all §3.2 features ON (cleaving ON, probe-gated), every classic
  rule module OFF (`attack-cooldown`, `old-*`, `sword-blocking`,
  `disable-sword-sweep`, …), FEEDBACK modules OFF (pure snapshot experience),
  engine families untouched (always-on), `knockback-profile: ct8c`.
- **`signature`** — every classic module ON (DAMAGE/CADENCE/SUSTAIN/LOADOUT
  classics, FEEDBACK, LOOT, COMBO, POTS), all `ct8c-*`/`weapon-attack-speeds`/
  `charged-attacks`/`cleaving` OFF, `knockback-profile: signature`,
  `effects-preset: signature`.
- **`vanilla`** — every toggleable module OFF, `knockback-profile:
  modern-vanilla`. Mental effectively transparent (zero-touch makes this
  literal).

### 3.4 Knockback profile `ct8c`

`profiles/modern/ct8c.yml` + `Presets.CT8C`: modern formula enabled with
`vertical-shape: ct8c-split` (grounded 0.75, airborne 0.5, cap 0.4), residual
0.5/0.5, sprint/enchant bonuses vanilla-1.16, delivery immediate/tracker,
resistance NONE (scalar handled by the engine's scaling step),
`shield-blocking-cancels: false` (the 50% shield KB lives in `ct8c-shields`).
Era pins in kernel `PresetsTest` + core `ProfileParserTest`; ConfigStore
`BUNDLED_PROFILES` + modern-folder mapping updated.

## 4. Version posture (graceful degradation)

Full experience on 1.20.5+ (reach attribute, item components); 1.21.3+ adds
the real Cleaving enchant. Below: every gated piece resolves at boot via
presence probes to an era-correct fallback with ONE loud boot-report line
(mandate B10); nothing version-parses. New manifest entries feed the boot
report and `BootSuite.manifestPresentSince()`. Charge model, damage tables,
crits, sweep rules, i-frames, shield math, regen, potion values, projectile
knockback all work to 1.9.4.

## 5. Documented gaps (client-authoritative — cannot exist server-side)

Hold-to-attack auto-swing (+1-tick/5-tick auto pacing), the 80–100%
`retainAttack` click buffer, attack-indicator 130–200% visuals, the charge
meter itself, the shield model visually raising on crouch (damage math
applies; the visual doesn't), snowball 2-tick render suppression, potion
stacking + 20-tick drink animation below 1.20.5, Cleaving below its registry
floor. Each listed in `docs/combat-test-8c.md` with the era-accuracy
rationale. Banner shields: intentional omission (owner).

## 6. Testing

- Kernel: unit pins for every §3.1 math class + the ModernKnockback shape
  extension no-op-at-default property + preset era pins.
- Core: parse tests (`parse(empty)` = defaults per feature; bundle parser),
  ProfileParserTest ct8c pin, registry test covers the new Feature constants.
- Tester: `CombatTest8cSuite` (charge gating incl. miss-recovery lane, damage
  table via staged weapons, shield arc/cap/crouch/axe-disable, i-frame
  scaling, regen cadence, snowball knockback + no-i-frames, reach acceptance
  on modern) + `RulesBundleSuite` (applying each shipped bundle converges the
  exact expected feature set; vanilla bundle passes ZeroTouch-grade
  transparency; signature bundle round-trips). BootSuite manifest additions.
- Gate: `./gradlew build` first, then foreground matrix batches (3–5 versions,
  nonce-honest, `--rerun-tasks` for repeats), full range before PR.

## 7. Non-goals

No OCM coexistence logic (unchanged doctrine), no mob-AI/PvE rebalance, no
banner shields, no per-player bundles (bundles are global, like knockback
profiles since 2.1.0), no client mods.

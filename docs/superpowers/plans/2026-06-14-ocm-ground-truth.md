# OCM Port — Decompile-Cited Ground Truth (reference for all sub-plans)

Source: NMS-archaeology research workflow, 2026-06-14. Every value below was read
from `javap` bytecode of the matrix paper jars (`run/<v>/versions/<v>/paper-<v>.jar`)
or the decompiled era sources (`legacy-lab/decomp-1.7.10`, `legacy-lab/decomp-1.8.9`,
`legacy-lab/decomp-1.21.11`), or OCM source. Citations are abbreviated as
`[class:line]` (era jars use obfuscated names, e.g. `pe.java` = 1.8.9 MobEffects).
These are the unit-test pins — do not change a value without a new citation.

---

## 1. Attack cooldown removal + client-animation suppression

**Era truth:** 1.7.10 (`yz.java`) and 1.8.9 (`wn.java`) `EntityHuman.attack()` have
ZERO cooldown machinery — no ticker, no `attack_speed` attribute, no scale. Damage =
attackDamage attribute, applied every call. `generic.attackSpeed` does not exist in
`vy.java` (1.8.9 GenericAttributes).

**Modern mechanism (verified 1.20.6 / 1.21.11 / 26.1.2, byte-identical):**
- `LivingEntity.attackStrengthTicker` (int, on the SUPERCLASS, inherited by Player),
  incremented in `Player.tick`, zeroed by `resetAttackStrengthTicker()` (sets
  `attackStrengthTicker=0; itemSwapTicker=0`).
- `getCurrentItemAttackStrengthDelay() = (1.0 / attributeValue(ATTACK_SPEED)) * 20.0` ticks.
- `getAttackStrengthScale(adjust) = clamp((attackStrengthTicker + adjust) / delay, 0, 1)`.
- The CLIENT computes the overlay from the `attack_speed` value it received over the
  wire for ITS OWN entity (same shared `Player` class runs client-side). High value →
  delay ≈ 0 → scale always 1.0 → no charge bar, no greyed swing.

**Chosen mechanism (option a — packet-only client spoof):** an `onPacketSend`
PacketEvents listener (mirror `VelocityDuplicateSuppressor`) gated on
`PacketType.Play.Server.UPDATE_ATTRIBUTES`. When the wrapped
`WrapperPlayServerUpdateAttributes.getEntityId() == receiver.getEntityId()` (own
entity only), find the `attack_speed` Property by **PacketEvents `Attributes.ATTACK_SPEED`
identity** (NOT a raw string) and `setValue(1024.0)`; if absent, add it. Leaves the
server attribute at vanilla 4.0 → no plugin conflict, no teardown, Folia-safe (netty
thread, reads only an int). Fast-path already removes the cooldown's damage effect; this
is the cosmetic + residual-gate remnant only.

| Range | Mechanism |
|---|---|
| 1.17.1–1.20.4 | wire key `generic.attack_speed`; `PacketPlayOutUpdateAttributes` present (spigot-mapped). Uniform. |
| 1.20.5–1.21.1 | mapping flip; ctor takes `Collection<AttributeInstance>`, snapshot carries `Holder<Attribute>`; key STILL `generic.attack_speed`. PacketEvents abstracts it. |
| 1.21.2+ (incl 1.21.4, 1.21.11) | key RENAMED `attack_speed`. Use `Attributes.ATTACK_SPEED` (resolves via `getName(ClientVersion)`). |
| 26.x | same as 1.21.2+; no further packet-shape change. |
| Folia | packet-only path needs NO scheduling hop. (Option b, `resetAttackStrengthTicker`, would need `Scheduling.runOn`.) |

**Constants:** spoof value `1024.0`; `ATTACK_SPEED` default base `4.0`; OCM's
(inferior) approach sets server base `40.0` and must reset to `4.0` on quit.

**Open items:** confirm PacketEvents `Attribute.sanitizeValue` does not down-clamp 1024.0;
confirm whether a spoofed packet must also be sent proactively at join/respawn (if the
server never re-sends `attack_speed` after spawn, a rewrite-only listener misses the
window). Default the knob OFF (era-exact no-op).

---

## 2. Sword blocking (per-version capability tiers)

**Reduction model (1.8):** taken damage `= (1 + damage) * 0.5` ⟺ reduction `(damage-1)*0.5`.
`[OCM ModuleShieldDamageReduction f=(1.0F+f)*0.5F; compendium wire-measured 4.5=(1+8)*0.5]`.
Era truth: a **partial** block still knocks FULL; only a fully-aligned block cancels.

**Capability tiers (gate on class/field presence, NEVER version literals):**

- **(A) BLOCKS_ATTACKS present (1.21.5+ / 26.x; confirmed present 1.21.11, absent 1.21.4):**
  set `DataComponents.BLOCKS_ATTACKS` on the held sword with one `DamageReduction{
  horizontalBlockingAngle=180.0, base=-0.5, factor=0.5}` and `blockDelaySeconds=0`.
  Native `resolve = clamp(base + factor*damage, 0, damage)` ⇒ `(damage-1)*0.5`. Gives
  native `isBlocking()` + native reduction + client block pose — the most client-identical.
  `[BlocksAttacks$DamageReduction.resolve javap 1.21.11; angle gate uses 0.017453292f = π/180]`
  Optionally add `CONSUMABLE{animation=BLOCK}` if the sword doesn't animate from
  BLOCKS_ATTACKS alone (probe on-server). `blockDelayTicks()` replaces `getShieldBlockingDelay`.
- **(B) CONSUMABLE present, BLOCKS_ATTACKS absent (1.21.0–1.21.4):** set
  `DataComponents.CONSUMABLE = Consumable.builder().consumeSeconds(huge).animation(
  ItemUseAnimation.BLOCK).build()` → `Item.getUseAnimation()==BLOCK` → native `isBlocking()`
  gated by `getShieldBlockingDelay`. Detect via `getUseItem().is(ItemTags.SWORDS)`. Apply
  the `(damage-1)*0.5` reduction in software (the component does NOT reduce here). Enum is
  `net.minecraft.world.item.ItemUseAnimation` (renamed from `UseAnim`). Must STRIP the
  component on hotbar/swap/drop/death/world/quit. `[OCM PaperSwordBlocking; consumeSeconds Float.MAX_VALUE, OCM BLOCK_CONSUME_SECONDS=1.6f]`
- **(C) No component model (1.17.1–1.20.6) + any client < the component version:** OFFHAND
  REAL-SHIELD injection. On `PlayerInteractEvent` RIGHT_CLICK_* (+ `PlayerInteractEntity/At`,
  `BlockCanBuildEvent`) with a sword in main hand: store offhand, set a `Material.SHIELD`
  (mark with PDC `NamespacedKey(plugin,"temporary_legacy_shield")` BYTE=1), `updateInventory`,
  `startUsingItem(EquipmentSlot.OFF_HAND)`. Detect via `isBlocking()`/`isHandRaised()`.
  Reduction in software. Full ~9-event restore lifecycle (drop/hotbar/world/death-keepInv/
  quit/swap/inventory-click slot 40). `[OCM ModuleSwordBlocking; offhand slot index 40; restoreDelay 40 ticks]`

**Prefer Paper Bukkit API** (`ItemStack.setData/unsetData` with `DataComponentTypes.*`)
when present; NMS `applyComponents` fallback. Never packet-only (would desync `isBlocking()`).
**Folia:** all item-meta mutation + delayed restore via `Scheduling.runOn/repeatOn(player)`
(EntityScheduler), never `BukkitScheduler`. Reduction runs in `EntityDamageByEntityEvent`
(victim region — safe inline).

**Honest limit:** cannot be byte-identical to the 1.7 CLIENT (lowered-sword pose is a
client asset). Achievable = server BEHAVIORAL parity (right-click block, `(dmg-1)*0.5`,
full-KB-while-blocking, a version-appropriate block pose). Probe exact BLOCKS_ATTACKS
lower bound (1.21.5 expected) on a real jar; gate on capability not literal.

---

## 3. Hitboxes / reach (deepest server-side; honest limit)

**Irrecoverable server-side:** the CLIENT picks the melee target (sends a fixed entityId
in `ServerboundInteractPacket`; server resolves `getEntityOrPart(id)` verbatim — no server
raytrace on the attack path). True 1.7 selection geometry (wider client targeting, the
~0.1 grow on the target box) lives in the client. Server can only EXPAND/CONTRACT the
validation window/box.

**Era numbers:** player AABB `0.6 × 1.8` (half-width 0.3), eye `1.62`, hit-detection grow
`~0.1`. `[Mental ReachValidator; OCM hitbox-margin default 0.1 — not re-decompiled]`

| Range | Lever |
|---|---|
| 1.17.1–1.20.4 | NO interaction-range attribute. Gate hardcoded `36.0` squared (6 blocks) in `handleInteract`. No safe lever — already more lenient than era 3.0; client enforces era targeting. Do nothing. |
| 1.20.5–1.21.1 | `ENTITY_INTERACTION_RANGE` attribute (default `3.0`), key `generic.entity_interaction_range`. `handleInteract` leniency `1.0` (1.20.6). Set via Bukkit `AttributeInstance` (Mental `Attributes.entityInteractionRange()`), Folia: write on entity thread. Tighten to 3.0 to enforce era reach. `BLOCK_INTERACTION_RANGE`=4.5 — don't touch. |
| 1.21.2–1.21.4 | same attribute, key unprefixed `entity_interaction_range`. Leniency = Paper `clientInteractionLeniencyDistance` (default 3.0, added ON TOP). AttackRange component does NOT exist yet. `Capabilities.registryAttributes()==true` here. |
| 1.21.5/1.21.11+ | TWO levers: (1) `ENTITY_INTERACTION_RANGE` attr still sets the default attack window (`AttackRange.defaultFor()` margin=0); (2) item `DataComponents.ATTACK_RANGE` (`io.papermc.paper.datacomponent.item.AttackRange`) per-weapon `min_reach/max_reach/hitbox_margin/mob_factor` — the path to era `max_reach=3.0 + hitbox_margin≈0.1` on swords. `AttackRange.isInRange` uses `[min−margin−leniency, max+margin+leniency]` eye-to-AABB. CODEC_DEFAULT margin=0.3; defaultFor margin=0 (only the explicit component gives margin control). |
| 26.x | same two levers; `isWithinAttackRange(ItemStack, AABB, double)` signature drift only. Gate by capability, not version. |

**OCM AttackRange item defaults:** min 0, max 3.0, minCreative 0, maxCreative 4.0,
hitboxMargin 0.1, mobFactor 1.0. **Achievable net:** era-correct reach distance + small
hitbox margin (1.21.5+); target selection stays whatever the modern client does (already
close to 1.7 for melee). Yield to OCM's attack-range if it's active (existing coexistence
gate philosophy — but new modules are OCM-agnostic per owner decision).

---

## 4. Armour (strength + durability)

**1.8 reduction pipeline (order: armour → resistance → enchant EPF → absorption):**
- Armour: `dmg * (25 - armourPoints) / 25` ⇒ `0.04` per point. `[pr.java:709-712, divider 25.0 pr.java:712]`
- Resistance: `0.2` per level (`(lvl+1)*5 / 25`). `[pr.java:724]`
- Enchant EPF per piece: `floor((6 + level²) / 3.0 * typeModifier)`. `[acr.java:40]`
  - typeModifiers: Protection `0.75` `[acr:42]`, Fire Protection `1.25` `[acr:45]`,
    Feather Falling `2.5` `[acr:48]`, Blast `1.5` `[acr:51]`, Projectile `1.5` `[acr:54]`.
  - Sum EPF, clamp raw to `25` `[ack.java:111-114]`, randomize
    `(epf+1>>1) + nextInt((epf>>1)+1)` `[ack.java:116]`, clamp final to `20` `[pr.java:733-735]`
    ⇒ max 80% enchant reduction.
- Hard-hat: helmet vs falling block/anvil `* 0.75` before armour. `[pr.java:511-514]`

**Armour point values per piece (helmet,chest,legs,boots):** diamond `{3,8,6,3}=20`,
iron `{2,6,5,2}=15`, chain `{2,5,4,1}=12`, gold `{2,5,3,1}`, leather `{1,3,2,1}`.
`[yj.java:136-140]` Toughness MUST be ignored (era has none).

**Modern toughness (what we override):** `effArmor = clamp(armor - dmg/(2+tough/4),
armor*0.2, 20)`. `[decomp-1.21.11 CombatRules.java:15-26]`

**Durability:** per-hit `max(1, floor(eventDamage/4))` per worn piece — IDENTICAL 1.8↔modern
`[wm.java:370-380]`, so don't touch magnitude (OCM's flat-1 is NOT era-exact). Only era
difference = Unbreaking armour skip prob: 1.8 `0.6 + 0.4/(level+1)` `[acg.java:37+40]` vs
modern `level/(level+1)`. Restore via `PlayerItemDamageEvent` (LOWEST): on a `60+40/(L+1)`%
roll `setDamage(0)`, else leave vanilla `getDamage()`.

**Hook:** `EntityDamageEvent` (NOT ...ByEntity — must cover mobs/projectiles/explosions/
environmental), priority LOWEST. Modern `DamageModifier.ARMOR` throws on 1.20.5+ — recompute
final from `event.getDamage()` and `setDamage(double)`. Read victim armour from worn pieces
(values unchanged), ignore toughness. **Folia:** victim-region thread → all reads/`setDamage`
safe inline; 1.8 model needs NO attacker enchant data.

**Pins:** full-diamond no-ench, 10 dmg → `10*(25-20)/25 = 2.0`. Prot IV ×4 → EPF
`floor((6+16)/3*0.75)=5`, ×4=20, clamp25, clamp20 → reduction `0.04*20=0.80`. Prot II single
→ `floor((6+4)/3*0.75)=2`.

---

## 5. Potions (strength/weakness) + crits

**Strength (MULTIPLY_TOTAL / op 2, amount × (amp+1)):** 1.8 = `2.5` ⇒ I = ×3.5, II = ×6.0
`[pe.java:17, scaling :197-199, op qh.java:133-135]`; 1.7 = `3.0` ⇒ I = ×4.0 `[rv.java:15,:185-187]`.
Modern (strip this) = `3.0` ADD_VALUE flat `[javap 1.21.11 MobEffects]`.
**Weakness (ADD / op 0, negated as harmful):** 1.7 & 1.8 = `2.0` ⇒ net `-2.0`/level
`[pe.java:30, rv.java:28]`; modern = `-4.0` ADD_VALUE `[javap 1.21.11]`.
**OCM's defaults are WRONG** (Strength 1.3, Weakness -0.5) — use the decompiled era values.

**Event conversion (override modern → era, in `EntityDamageByEntityEvent`):**
- Strength: `baseNoStr = getDamage(BASE) - 3.0*(amp+1)`; `era = baseNoStr * (1 + ERA_AMT*(amp+1))`, ERA_AMT 2.5(1.8)/3.0(1.7).
- Weakness: `baseNoWk = getDamage(BASE) + 4.0*(amp+1)`; `era = baseNoWk - 2.0*(amp+1)`.

**Durations (item rewrite on consume/throw/dispense):** OCM table is the reference but was
NOT independently decompile-verified (the duration table is a different class than `pe.java`).
Treat OCM `config.yml:394-484` as starting values; flag for byte-check if exactness needed.

**Crit:** multiplier `1.5` applied to weapon+strength BEFORE enchant bonus `[wn.java:761-764]`.
1.8 preconditions: `fallDistance>0 && !onGround && !onLadder && !inWater && !blindness &&
vehicle==null && target instanceof EntityLiving` `[wn.java:760]`. 1.9 ADDED
`&& attackCooldown>0.9 && !isSprinting`. Mental's `DamageCalculator.isCritical` already has
the 1.8 set on the fast path; the GAP is fast-path-OFF / mob melee — extract `isCritical`
to a shared static, apply in an `EntityDamageByEntityEvent` handler (ENTITY_ATTACK, attacker
Player, fast-path inactive). Projectile/arrow crits are a separate unchanged vanilla path —
do NOT touch.

**Open byte-check:** read `pb.java`/`pe.java:30` to confirm the Weakness negation lives in
the harmful-effect subclass (5-min check before pinning the weakness test).

---

## 6. Golden apples, ender pearl cooldown, natural regen (3 independent modules)

All pure Bukkit-API + `Scheduling` — no NMS, no platform resolver.

**Golden apples** (`PlayerItemConsumeEvent` HIGHEST → `Scheduling.runOn` +1 tick to strip
vanilla effects and apply era table; modern apples are component-food so effects apply AFTER
the event):
- Normal: Regeneration II (amp 1) `100t` + Absorption I (amp 0) `2400t`. `[zt.java:22, zw.java:641]`
- Notch: Regeneration V (amp 4) `600t` + Resistance I `6000t` + Fire Res I `6000t` +
  Absorption I `2400t` (NOT Absorption IV). `[zt.java:22,26,27,28]`
- food 4, saturation modifier `1.2f`. `[zw.java:641]`
- Recipes: gapple = 8 gold INGOTS + apple `[abt.java:136]`; **napple = 8 gold BLOCKS + apple
  (removed 1.9)** `[abt.java:137]` — register `ShapedRecipe(NamespacedKey(plugin,
  "enchanted_golden_apple"), ggg/gag/ggg)`, remove on disable; guard `PrepareItemCraftEvent`.

**Ender pearl cooldown:** 1.8 = 0; modern = `20t` (1s). 1.21.2+ uses `use_cooldown`
component (group Identifier) instead of hardcoded `addCooldown`. CLEAR via the ONLY stable
surface: `HumanEntity.setCooldown(Material.ENDER_PEARL, 0)` (CraftBukkit resolves the group),
scheduled `runOn(player)` after `ProjectileLaunchEvent`. `[EnderpearlItem.use javap 1.20.6; CraftHumanEntity:670]`

**Natural regen:** cancel `EntityRegainHealthEvent` (PLAYER + `RegainReason.SATIATED` — both
modern branches use it), re-drive era model via per-player `Scheduling.repeatOn(player,1,1)`
(EntityScheduler — NEVER a global loop): interval `80t`, heal `1.0` HP, exhaustion `+3.0f`
(cap 40), gated `foodLevel>=18 && naturalRegeneration && 0<hp<max`. `[xg.java:31-35,75-77; FoodData javap 1.21.11 rates 10/80/80]`
OCM uses a global `Bukkit.getScheduler` timer — replace with per-player tasks (Folia).

**Open item:** owner chose OCM-agnostic, so these do NOT yield to OCM (standalone). Confirm
`Scheduling` exposes a +1-tick deferral (golden-apple strip, regen exhaustion fixup) —
likely `repeatOn(entity,1,LONG,…)` cancelled after first run if no `runDelayed` variant.

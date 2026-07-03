# The `combat:effective_material` contract

A vendor-neutral, cross-plugin contract that lets a plugin which **display-swaps** gear to a weaker
material tell an era-combat plugin (like Mental) what the item *really* is, so it gets the right era
stats instead of the display material's.

## The problem it solves

Some plugins reforge gear into a cosmetic form — e.g. a "heroic" upgrade that shows a diamond item as a
**gold** one while it should still function as diamond. Mental's era model reads the item to decide its
1.8 stats:

- **Armour** already works: Mental reads the summed `GENERIC_ARMOR` attribute, so a real diamond
  armour modifier on the gold piece is honoured with no contract needed.
- **Weapon legacy damage** keys off the item's **material** (`legacyAttackDamage(weapon.getType())`), so
  a gold display piece would deal gold-era damage — the attribute is ignored on that path.

The contract closes that gap: the display-swapping plugin stamps the item's **true** material, and Mental
treats the item as that material for legacy stat computation and the era tooltip.

## The key

| | |
|---|---|
| **Namespace + key** | `combat:effective_material` (`NamespacedKey.fromString("combat:effective_material")`) |
| **Type** | `PersistentDataType.STRING` |
| **Value** | an exact Bukkit `Material` enum name, e.g. `DIAMOND_SWORD`, `DIAMOND_CHESTPLATE` |
| **Direction** | the display-swapping plugin **writes**; era-combat plugins **read** (read-only) |
| **Absent** | ⇒ use the item's own `getType()` — a pure no-op for every unmarked item |

`combat:` is a neutral namespace both sides agree on — not owned by Mental or by any one reforge plugin —
so any plugin can participate.

## Version floor (1.14)

The contract rides the `PersistentDataContainer` API, which arrived in Bukkit **1.14**. On the legacy
backport's older targets (1.9.4–1.13.2) there is no PDC, so the marker is unreadable and
`EffectiveMaterial.of(item)` degrades to the item's own `getType()` for every item — the display-swap
contract simply does not apply below 1.14. The degradation is announced once, loudly, in Mental's boot
log (`persistent-data-container ABSENT …`), never silent. Internally the key is built lazily via the
`new NamespacedKey("combat", "effective_material")` constructor (present from 1.12) rather than the older
`NamespacedKey.fromString` spelling (a 1.16 API that would break class-init on every server below it); the
two produce the identical key, so writers on 1.16+ may use either form.

## What Mental does with it (v-next)

Mental resolves the effective material via `me.vexmc.mental.platform.EffectiveMaterial.of(item)` (the
marked material when present + valid, else the item's own type; unknown names degrade to the type, never
throw) and honours it **only where its own restore modules are enabled** — Mental owns the era decision;
the marker never forces legacy values on:

- **`legacy-tool-damage`** → `DamageTables` (via the `DamageShaper`) computes legacy weapon damage from the
  effective material, so a gold-in-disguise diamond sword deals diamond-era damage (8), not gold's (5).
- **`legacy-tool-damage`** → the packet tooltip re-values the weapon's `attack_damage` to the era number
  of the effective material, so the shown value matches the damage dealt.
- Durability needs no marker: Mental's weapon durability now breaks against the item's `max_damage`
  **component** (1.20.5+), so a diamond-in-disguise carrying a diamond max lasts like diamond.

Unmarked items and disabled modules are completely unaffected (zero-touch).

## Stamping it (writer side, illustrative)

```java
NamespacedKey key = NamespacedKey.fromString("combat:effective_material");
ItemMeta meta = goldSword.getItemMeta();
meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "DIAMOND_SWORD");
// Also write the real diamond attribute modifiers + max_damage component so the item is diamond
// on vanilla + Mental's non-legacy paths; the marker covers Mental's legacy paths.
goldSword.setItemMeta(meta);
```

The reference writer is StarEnchants' heroic upgrade (ADR 0032): it writes real modern diamond
`GENERIC_ARMOR`(+toughness) / `GENERIC_ATTACK_DAMAGE` modifiers + a diamond `max_damage` component + this
marker, and Mental presents the era-correct form on servers running its 1.8-combat modules.

---

# Legacy backport — accepted compatibility residuals (1.9.4–1.16.5)

The 2026-07-02 backport extended Mental's runtime floor from Paper 1.17.1 down to
1.9.4 (see `docs/superpowers/plans/2026-07-02-mental-legacy-backport.md`). Version
variance enters only at the platform seam via boot-time probing — no version
conditionals scattered through the code, no silent degradation (every legacy
absence is a loud boot line or a documented fallback). The following residuals
were reviewed and **accepted** across Phases 3–5.5. All of them ride
**default-OFF** rule features or are harness-only, and every one degrades in the
benign direction.

- **Effective-material / PDC floor (1.14).** The `combat:effective_material`
  marker rides the `PersistentDataContainer` API (Bukkit 1.14+). Below 1.14 the
  marker is unreadable and `EffectiveMaterial.of(item)` degrades to the item's own
  `getType()` for every item — the display-swap contract simply does not apply
  there. Announced once, loudly, in the boot log (`persistent-data-container
  ABSENT …`). See the [Version floor (1.14)](#version-floor-114) section above.

- **Pre-1.14 in-memory shield identity (F-BP2).** The ephemeral sword-blocking
  marker (`sword-blocking` feature) is item-PDC on 1.14+; below 1.14 there is no
  PDC, so it is an in-memory per-player set with the same single-writer lifecycle
  (region thread), losing only cross-restart persistence of a marker that is
  ephemeral by definition. Edge: if a player swaps a *real* shield into the
  off-hand inside the sub-second temporary-shield window, the in-memory identity
  could misattribute it as Mental's temp shield. Bounded by the refuse-to-overwrite
  guard plus the tracked-membership check; the feature is default-OFF.

- **≤1.15.2 recipe-scan notch-apple identification.** `Bukkit.getRecipe(key)` is
  1.16.5+ and `removeRecipe(key)` is 1.15.2+; representation and key-lifecycle are
  independent axes (1.13.2/1.15.2 are flattened but key-lifecycle-less), so on
  1.9.4–1.15.2 the `old-golden-apples` notch recipe is identified by its result
  shape through the universal recipe iterator. A third-party notch recipe with the
  same result therefore de-dupes with Mental's (and a feature-disable could remove
  theirs). Default-OFF feature, benign direction.

- **CritPosture in-water approximation pre-1.16.** The `old-critical-hits` era
  rule needs the attacker's in-water state for its non-fast-path crit gate. The
  modern accessor is used verbatim on 1.17+ (unit-pinned); the pre-1.16 fallback
  approximates in-water by the feet-block type. Crit-fallback path only
  (non-fast-path melee under a default-OFF feature); minor edge cases around
  waterlogged blocks.

- **1.9.4 thrown-projectile knock hole.** `ProjectileHitEvent#getHitEntity` — the
  API that attributes a thrown projectile to its victim — is absent on 1.9.4
  (present from 1.10.2 up in the supported set). Thrown-projectile knockback
  (snowball / egg / ender-pearl) is therefore unavailable on 1.9.4 only; arrow and
  Punch-enchant knockback work on every legacy version. Loud, documented.

- **Ender-pearl cooldown native no-op below 1.11.2.** The
  `disable-enderpearl-cooldown` feature relies on the item-cooldown API; below
  1.11.2 the 1.9 pearl throw-cooldown does not exist natively — the era state is
  already the one the feature wants — so the feature is a documented **loud no-op**
  there, not a silent one.

- **Fast-path clientless verification blind spot (F-BP4).** The live matrix drives
  clientless fake players: they void all outbound traffic and inject no
  PacketEvents user, so the WIRE-level fast path (pre-send bundles, the
  duplicate-`ENTITY_VELOCITY` valve, the actual shipped values, and the
  transaction-probe RTT round-trip on 1.8–1.16) is not exercised in the matrix. It
  is pinned instead by kernel unit tests plus the `legacy-lab` protocol harness
  against real clients; end-to-end wire verification on the legacy transport is
  out-of-band (SimpleBoxer / the owner's real setup). This is the legacy tier's
  known verification boundary — the EVENT-level behaviour and the send-path encode
  are live-pinned per version.

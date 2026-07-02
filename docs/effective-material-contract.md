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

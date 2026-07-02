package me.vexmc.mental.v5.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import me.vexmc.mental.common.platform.ServerEnvironment;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The weapon-tooltip adapter for attack-cooldown removal (the rule-1 half of the
 * retired {@code WeaponAttributeTooltipHider} on the v5 {@link PlatformProbe}),
 * boot-probed once. Raising the client {@code attack_speed} to full charge makes
 * a weapon's tooltip render a huge "Attack Speed" line (the client merges the
 * item's modifier with the spoofed base); 1.8 weapons showed attack damage but
 * never an attack-speed line, so this adapter strips ONLY the {@code attack_speed}
 * modifier from a display copy, leaving {@code attack_damage} intact.
 *
 * <p>Two reflective paths, each resolved once at boot, tried in order on the hot
 * path, each a clean no-op on a reflective slip (never corrupts an item):</p>
 * <ol>
 *   <li><b>(A) modern NMS component</b> (1.20.5+): walk to the {@code CraftItemStack}
 *       handle, {@code ItemStack.get(DataComponents.ATTRIBUTE_MODIFIERS)} (the
 *       resolving getter, which materialises the item-type defaults), rebuild
 *       without the {@code attack_speed} entry, {@code set(...)} it back on the
 *       copy's handle.</li>
 *   <li><b>(B) legacy Bukkit defaults</b> (≤1.20.x): {@code Material.getItemAttributes(HAND)}
 *       gives the default modifier multimap; rebuild without the attack_speed key
 *       and {@code ItemMeta.setAttributeModifiers} the explicit result.</li>
 * </ol>
 *
 * <h2>Loud-fail (B10)</h2>
 * <p>Every supported version resolves at least one path (A on 1.20.5+, B below
 * 1.21). If NEITHER resolves at boot it is a mapping break, logged loudly once —
 * never silent — before the adapter degrades to leaving the line in place.</p>
 */
public final class WeaponTooltipAdapter {

    /* ---- Path (A): NMS component handles, resolved once; null when absent ---- */
    private final @Nullable Object nmsAttributeModifiersType;
    private final @Nullable Method nmsItemStackGet;
    private final @Nullable Method nmsItemStackSet;
    private final @Nullable Method itemAttributeModifiersList;
    private final @Nullable Method entryAttribute;
    private final @Nullable java.lang.reflect.Constructor<?> itemAttributeModifiersCtor;
    private final @Nullable Object nmsAttackSpeedHolder;
    private final @Nullable Method holderValue;
    private volatile @Nullable Field craftItemStackHandleField;

    /* ---- Path (B): legacy Bukkit defaults, resolved once; null when absent ---- */
    private final @Nullable Method materialGetItemAttributes;

    private WeaponTooltipAdapter(
            @Nullable Object nmsAttributeModifiersType, @Nullable Method nmsItemStackGet,
            @Nullable Method nmsItemStackSet, @Nullable Method itemAttributeModifiersList,
            @Nullable Method entryAttribute, @Nullable java.lang.reflect.Constructor<?> itemAttributeModifiersCtor,
            @Nullable Object nmsAttackSpeedHolder, @Nullable Method holderValue,
            @Nullable Method materialGetItemAttributes) {
        this.nmsAttributeModifiersType = nmsAttributeModifiersType;
        this.nmsItemStackGet = nmsItemStackGet;
        this.nmsItemStackSet = nmsItemStackSet;
        this.itemAttributeModifiersList = itemAttributeModifiersList;
        this.entryAttribute = entryAttribute;
        this.itemAttributeModifiersCtor = itemAttributeModifiersCtor;
        this.nmsAttackSpeedHolder = nmsAttackSpeedHolder;
        this.holderValue = holderValue;
        this.materialGetItemAttributes = materialGetItemAttributes;
    }

    /** Boot-probes the two paths and loud-logs a mapping break (neither resolves). */
    public static @NotNull WeaponTooltipAdapter probe(
            @NotNull ServerEnvironment environment, @NotNull Consumer<String> log) {
        Object attrType = null;
        Method get = null;
        Method set = null;
        Method modsList = null;
        Method entryAttr = null;
        java.lang.reflect.Constructor<?> iamCtor = null;
        Object attackSpeedHolder = null;
        Method holderVal = null;
        try {
            Class<?> dataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType");
            Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
            Class<?> itemAttributeModifiers =
                    Class.forName("net.minecraft.world.item.component.ItemAttributeModifiers");
            Class<?> entry =
                    Class.forName("net.minecraft.world.item.component.ItemAttributeModifiers$Entry");
            Class<?> attributesClass =
                    Class.forName("net.minecraft.world.entity.ai.attributes.Attributes");
            Class<?> holder = Class.forName("net.minecraft.core.Holder");

            attrType = dataComponents.getField("ATTRIBUTE_MODIFIERS").get(null);
            get = findUnary(nmsItemStack, "get", componentType);
            set = findBinary(nmsItemStack, "set", componentType);
            modsList = itemAttributeModifiers.getMethod("modifiers");
            entryAttr = entry.getMethod("attribute");
            iamCtor = itemAttributeModifiers.getConstructor(List.class);
            attackSpeedHolder = attributesClass.getField("ATTACK_SPEED").get(null);
            holderVal = holder.getMethod("value");
        } catch (Throwable absent) {
            attrType = null;
            get = null;
            set = null;
            modsList = null;
            entryAttr = null;
            iamCtor = null;
            attackSpeedHolder = null;
            holderVal = null;
        }

        Method matGetAttrs = null;
        try {
            matGetAttrs = Material.class.getMethod("getItemAttributes", EquipmentSlot.class);
        } catch (Throwable absent) {
            matGetAttrs = null;
        }

        boolean pathA = attrType != null && get != null && set != null && modsList != null
                && entryAttr != null && iamCtor != null && attackSpeedHolder != null;
        boolean pathB = matGetAttrs != null;
        if (!pathA && !pathB) {
            log.accept("platform-probe: neither attack-speed tooltip path resolved on "
                    + environment.describe() + " (NMS ATTRIBUTE_MODIFIERS component, legacy "
                    + "Material.getItemAttributes) — a mapping break; the cooldown-spoof "
                    + "attack-speed tooltip line stays visible on this version.");
        }
        return new WeaponTooltipAdapter(
                attrType, get, set, modsList, entryAttr, iamCtor, attackSpeedHolder, holderVal, matGetAttrs);
    }

    /**
     * Returns a display copy of {@code bukkit} with its {@code attack_speed}
     * tooltip line removed, or {@code null} when no change is needed (not a
     * weapon/tool, no attack_speed line, or every path unavailable). Operates on
     * the given copy only — never a live inventory item.
     */
    public @Nullable ItemStack stripAttackSpeed(@Nullable ItemStack bukkit) {
        if (bukkit == null || !isWeaponOrTool(bukkit.getType())) {
            return null;
        }
        ItemStack viaComponent = stripViaComponent(bukkit);
        if (viaComponent != null) {
            return viaComponent;
        }
        return stripViaBukkitMeta(bukkit);
    }

    /* ------------------------------------------------------------------ */
    /*  Path (A): NMS component                                            */
    /* ------------------------------------------------------------------ */

    private @Nullable ItemStack stripViaComponent(@NotNull ItemStack bukkit) {
        if (nmsAttributeModifiersType == null || nmsItemStackGet == null || nmsItemStackSet == null
                || itemAttributeModifiersList == null || entryAttribute == null
                || itemAttributeModifiersCtor == null || nmsAttackSpeedHolder == null) {
            return null;
        }
        try {
            Object handle = nmsHandle(bukkit);
            if (handle == null) {
                return null;
            }
            Object resolved = nmsItemStackGet.invoke(handle, nmsAttributeModifiersType);
            if (resolved == null) {
                return null;
            }
            Object listObj = itemAttributeModifiersList.invoke(resolved);
            if (!(listObj instanceof List<?> entries)) {
                return null;
            }
            List<Object> kept = new ArrayList<>(entries.size());
            boolean removed = false;
            for (Object entry : entries) {
                Object attrHolder = entryAttribute.invoke(entry);
                if (isAttackSpeedHolder(attrHolder)) {
                    removed = true;
                    continue; // drop only the attack_speed entry
                }
                kept.add(entry);
            }
            if (!removed) {
                return null; // no attack_speed entry — nothing to do
            }
            Object rebuilt = itemAttributeModifiersCtor.newInstance(kept);
            nmsItemStackSet.invoke(handle, nmsAttributeModifiersType, rebuilt);
            return bukkit;
        } catch (Throwable ignored) {
            return null; // clean no-op on any reflective failure
        }
    }

    private boolean isAttackSpeedHolder(@Nullable Object attrHolder) {
        if (attrHolder == null) {
            return false;
        }
        if (holderValue != null) {
            try {
                Object value = holderValue.invoke(attrHolder);
                Object expected = holderValue.invoke(nmsAttackSpeedHolder);
                if (value != null && value == expected) {
                    return true;
                }
                if (value != null && expected != null) {
                    return false; // value() worked but differs — definitively NOT attack_speed
                }
            } catch (Throwable ignored) {
                // fall through to the string fallback
            }
        }
        String s = String.valueOf(attrHolder);
        return s.endsWith("attack_speed") || s.endsWith("attack_speed]") || s.contains(":attack_speed");
    }

    /* ------------------------------------------------------------------ */
    /*  Path (B): legacy Bukkit defaults                                   */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    private @Nullable ItemStack stripViaBukkitMeta(@NotNull ItemStack bukkit) {
        if (materialGetItemAttributes == null) {
            return null;
        }
        org.bukkit.attribute.Attribute attackSpeed = me.vexmc.mental.platform.Attributes.attackSpeed();
        if (attackSpeed == null) {
            return null;
        }
        try {
            Object raw = materialGetItemAttributes.invoke(bukkit.getType(), EquipmentSlot.HAND);
            if (!(raw instanceof com.google.common.collect.Multimap<?, ?> defaults)) {
                return null;
            }
            com.google.common.collect.Multimap<org.bukkit.attribute.Attribute,
                    org.bukkit.attribute.AttributeModifier> typed =
                    (com.google.common.collect.Multimap<org.bukkit.attribute.Attribute,
                            org.bukkit.attribute.AttributeModifier>) defaults;
            if (!typed.containsKey(attackSpeed)) {
                return null; // no attack_speed entry — leave untouched
            }
            com.google.common.collect.Multimap<org.bukkit.attribute.Attribute,
                    org.bukkit.attribute.AttributeModifier> kept =
                    com.google.common.collect.LinkedHashMultimap.create();
            for (var e : typed.entries()) {
                if (!attackSpeed.equals(e.getKey())) {
                    kept.put(e.getKey(), e.getValue());
                }
            }
            ItemMeta meta = bukkit.getItemMeta();
            if (meta == null) {
                return null;
            }
            meta.setAttributeModifiers(kept);
            bukkit.setItemMeta(meta);
            return bukkit;
        } catch (Throwable ignored) {
            return null; // clean no-op on any failure
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Reflection helpers                                                 */
    /* ------------------------------------------------------------------ */

    private @Nullable Object nmsHandle(@NotNull ItemStack stack) throws ReflectiveOperationException {
        Field field = craftItemStackHandleField;
        if (field == null) {
            Class<?> c = stack.getClass();
            while (c != null && c != Object.class) {
                try {
                    Field candidate = c.getDeclaredField("handle");
                    candidate.setAccessible(true);
                    field = candidate;
                    craftItemStackHandleField = candidate;
                    break;
                } catch (NoSuchFieldException next) {
                    c = c.getSuperclass();
                }
            }
            if (field == null) {
                return null; // not a CraftItemStack (a plain Bukkit ItemStack)
            }
        }
        return field.get(stack);
    }

    private static @Nullable Method findUnary(
            @NotNull Class<?> owner, @NotNull String name, @NotNull Class<?> firstParam) {
        for (Method m : owner.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) {
                continue;
            }
            if (m.getParameterTypes()[0].isAssignableFrom(firstParam)) {
                return m;
            }
        }
        return null;
    }

    private static @Nullable Method findBinary(
            @NotNull Class<?> owner, @NotNull String name, @NotNull Class<?> firstParam) {
        for (Method m : owner.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 2) {
                continue;
            }
            if (m.getParameterTypes()[0].isAssignableFrom(firstParam)) {
                return m;
            }
        }
        return null;
    }

    /** Items that carry an {@code attack_speed} modifier (and thus render the line). */
    private static boolean isWeaponOrTool(@Nullable Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                || name.equals("TRIDENT") || name.equals("MACE");
    }
}

package me.vexmc.mental.v5.platform;

import com.google.common.collect.Multimap;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import me.vexmc.mental.platform.ServerEnvironment;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The weapon-tooltip adapter for attack-cooldown removal (the rule-1 half of the
 * retired {@code WeaponAttributeTooltipHider} on the v5 {@link PlatformProfile}),
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
 *   <li><b>(B) legacy Bukkit defaults</b> (1.16.5–1.20.x): {@code Material.getItemAttributes(HAND)}
 *       gives the default modifier multimap; rebuild without the attack_speed key
 *       and {@code ItemMeta.setAttributeModifiers} the explicit result. (javap on
 *       the matrix jars corrected the assumed 1.13.2 floor: {@code getItemAttributes}
 *       is absent on 1.13.2 and 1.15.2, present on 1.16.5 — so path C, not B, covers
 *       1.13.2/1.15.2.)</li>
 *   <li><b>(C) versioned NMS NBT</b> (1.9.4–1.15.2): the {@code ItemMeta}
 *       attribute-modifier API floors above this band, so below it the strip is a
 *       per-revision NMS NBT edit. Legacy vanilla renders an item's attribute
 *       modifiers ("When in Main Hand: +X Attack Speed") from the item defaults
 *       UNLESS the stack carries an explicit {@code AttributeModifiers} NBT list,
 *       in which case ONLY those are shown. So the strip copies the effective
 *       main-hand modifiers ({@code ItemStack.a(EnumItemSlot.MAINHAND)}) into an
 *       explicit list minus the {@code attack_speed} entry, serialising each with
 *       vanilla's own {@code GenericAttributes.a(AttributeModifier)}. All handles
 *       are resolved once at boot through the versioned {@code
 *       net.minecraft.server.v1_x_Rn} / {@code org.bukkit.craftbukkit.v1_x_Rn}
 *       packages (spigot names ARE the runtime names below 1.17 — no remapper).
 *       Verified by javap: the shapes ({@code ItemStack.a(EnumItemSlot)},
 *       {@code GenericAttributes.a(AttributeModifier)}) are uniform across
 *       v1_9_R2…v1_15_R1, then removed on v1_16_R3 (where path B takes over).</li>
 * </ol>
 *
 * <h2>Loud-fail (B10)</h2>
 * <p>Every supported version resolves at least one path (A on 1.20.5+, B on
 * 1.16.5–1.20.x, C on 1.9.4–1.15.2). If NONE resolves at boot it is a mapping
 * break, logged loudly once — never silent — before the adapter degrades to
 * leaving the line in place.</p>
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

    /* ---- Path (C): pre-1.13 versioned-NMS NBT strip, resolved once; null when absent ---- */
    private final @Nullable LegacyNbtStrip legacyNbt;

    private WeaponTooltipAdapter(
            @Nullable Object nmsAttributeModifiersType, @Nullable Method nmsItemStackGet,
            @Nullable Method nmsItemStackSet, @Nullable Method itemAttributeModifiersList,
            @Nullable Method entryAttribute, @Nullable java.lang.reflect.Constructor<?> itemAttributeModifiersCtor,
            @Nullable Object nmsAttackSpeedHolder, @Nullable Method holderValue,
            @Nullable Method materialGetItemAttributes, @Nullable LegacyNbtStrip legacyNbt) {
        this.nmsAttributeModifiersType = nmsAttributeModifiersType;
        this.nmsItemStackGet = nmsItemStackGet;
        this.nmsItemStackSet = nmsItemStackSet;
        this.itemAttributeModifiersList = itemAttributeModifiersList;
        this.entryAttribute = entryAttribute;
        this.itemAttributeModifiersCtor = itemAttributeModifiersCtor;
        this.nmsAttackSpeedHolder = nmsAttackSpeedHolder;
        this.holderValue = holderValue;
        this.materialGetItemAttributes = materialGetItemAttributes;
        this.legacyNbt = legacyNbt;
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

        // Path (C): the pre-1.13 versioned-NMS NBT strip — self-contained resolution (null if any
        // handle is absent, e.g. on modern/unversioned CraftBukkit or in a serverless unit test).
        LegacyNbtStrip legacyNbt = LegacyNbtStrip.probe();

        boolean pathA = attrType != null && get != null && set != null && modsList != null
                && entryAttr != null && iamCtor != null && attackSpeedHolder != null;
        boolean pathB = matGetAttrs != null;
        boolean pathC = legacyNbt != null;
        if (!pathA && !pathB && !pathC) {
            log.accept("platform-probe: no attack-speed tooltip path resolved on "
                    + environment.describe() + " (NMS ATTRIBUTE_MODIFIERS component, legacy "
                    + "Material.getItemAttributes, pre-1.13 versioned-NMS NBT) — a mapping break; "
                    + "the cooldown-spoof attack-speed tooltip line stays visible on this version.");
        }
        return new WeaponTooltipAdapter(
                attrType, get, set, modsList, entryAttr, iamCtor, attackSpeedHolder, holderVal,
                matGetAttrs, legacyNbt);
    }

    /** Whether any strip path (NMS component, legacy Bukkit defaults, or pre-1.13 NMS NBT) resolved at boot. */
    public boolean supported() {
        boolean pathA = nmsAttributeModifiersType != null && nmsItemStackGet != null && nmsItemStackSet != null
                && itemAttributeModifiersList != null && entryAttribute != null
                && itemAttributeModifiersCtor != null && nmsAttackSpeedHolder != null;
        boolean pathB = materialGetItemAttributes != null;
        boolean pathC = legacyNbt != null;
        return pathA || pathB || pathC;
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
        ItemStack viaBukkitMeta = stripViaBukkitMeta(bukkit);
        if (viaBukkitMeta != null) {
            return viaBukkitMeta;
        }
        return legacyNbt == null ? null : legacyNbt.strip(bukkit);
    }

    /**
     * The effective main-hand attribute-modifier names on {@code bukkit}, lowercased (e.g. {@code
     * generic.attackdamage}), read through the pre-1.13 NMS path — or {@code null} where that path
     * is unavailable (1.13+, modern, or a serverless test). Exposed for the boot-suite tooltip-strip
     * verification (item 7): after a strip the returned set must contain the attack-damage name and
     * NOT the attack-speed name. Reads a copy; never touches a live stack.
     */
    public @Nullable Set<String> mainHandAttributeNames(@Nullable ItemStack bukkit) {
        if (bukkit == null || legacyNbt == null) {
            return null;
        }
        return legacyNbt.mainHandNames(bukkit);
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

    /* ------------------------------------------------------------------ */
    /*  Path (C): pre-1.13 versioned-NMS NBT strip                         */
    /* ------------------------------------------------------------------ */

    /**
     * Path C's self-contained handle set (v1_9_R2 … v1_12_R1). Resolves the
     * versioned CraftBukkit/NMS members once at boot; {@link #probe()} returns
     * {@code null} unless every handle is present, so the outer adapter treats an
     * unresolved path C as simply absent (never a half-resolved surface). Every
     * hot-path reflective slip degrades to a clean {@code null} — the display item
     * is never corrupted.
     */
    private static final class LegacyNbtStrip {

        /** The versioned CraftBukkit package token, e.g. {@code v1_9_R2} — the pre-1.17 spigot revision. */
        private static final Pattern REVISION = Pattern.compile("v\\d+_\\d+_R\\d+");

        private final Method asNmsCopy;         // CraftItemStack.asNMSCopy(org.bukkit.inventory.ItemStack)
        private final Method asBukkitCopy;      // CraftItemStack.asBukkitCopy(nms ItemStack)
        private final Method itemStackModifiers; // nms ItemStack.a(EnumItemSlot) -> Multimap<String, AttributeModifier>
        private final Method serializeModifier; // GenericAttributes.a(AttributeModifier) -> NBTTagCompound
        private final Method itemStackHasTag;
        private final Method itemStackGetTag;
        private final Method itemStackSetTag;
        private final Method nbtSet;            // NBTTagCompound.set(String, NBTBase)
        private final Method nbtSetString;      // NBTTagCompound.setString(String, String)
        private final Method nbtListAdd;        // NBTTagList.add(NBTBase)
        private final Constructor<?> nbtCompoundCtor;
        private final Constructor<?> nbtListCtor;
        private final Object mainHandSlot;      // EnumItemSlot.MAINHAND

        private LegacyNbtStrip(
                Method asNmsCopy, Method asBukkitCopy, Method itemStackModifiers, Method serializeModifier,
                Method itemStackHasTag, Method itemStackGetTag, Method itemStackSetTag, Method nbtSet,
                Method nbtSetString, Method nbtListAdd, Constructor<?> nbtCompoundCtor,
                Constructor<?> nbtListCtor, Object mainHandSlot) {
            this.asNmsCopy = asNmsCopy;
            this.asBukkitCopy = asBukkitCopy;
            this.itemStackModifiers = itemStackModifiers;
            this.serializeModifier = serializeModifier;
            this.itemStackHasTag = itemStackHasTag;
            this.itemStackGetTag = itemStackGetTag;
            this.itemStackSetTag = itemStackSetTag;
            this.nbtSet = nbtSet;
            this.nbtSetString = nbtSetString;
            this.nbtListAdd = nbtListAdd;
            this.nbtCompoundCtor = nbtCompoundCtor;
            this.nbtListCtor = nbtListCtor;
            this.mainHandSlot = mainHandSlot;
        }

        static @Nullable LegacyNbtStrip probe() {
            String revision = versionedRevision();
            if (revision == null) {
                return null; // modern/unversioned CraftBukkit, or no live server (a unit test)
            }
            try {
                String nms = "net.minecraft.server." + revision + ".";
                Class<?> craftItemStack =
                        Class.forName("org.bukkit.craftbukkit." + revision + ".inventory.CraftItemStack");
                Class<?> nmsItemStack = Class.forName(nms + "ItemStack");
                Class<?> enumItemSlot = Class.forName(nms + "EnumItemSlot");
                Class<?> genericAttributes = Class.forName(nms + "GenericAttributes");
                Class<?> attributeModifier = Class.forName(nms + "AttributeModifier");
                Class<?> nbtBase = Class.forName(nms + "NBTBase");
                Class<?> nbtTagCompound = Class.forName(nms + "NBTTagCompound");
                Class<?> nbtTagList = Class.forName(nms + "NBTTagList");

                return new LegacyNbtStrip(
                        craftItemStack.getMethod("asNMSCopy", ItemStack.class),
                        craftItemStack.getMethod("asBukkitCopy", nmsItemStack),
                        nmsItemStack.getMethod("a", enumItemSlot),
                        genericAttributes.getMethod("a", attributeModifier),
                        nmsItemStack.getMethod("hasTag"),
                        nmsItemStack.getMethod("getTag"),
                        nmsItemStack.getMethod("setTag", nbtTagCompound),
                        nbtTagCompound.getMethod("set", String.class, nbtBase),
                        nbtTagCompound.getMethod("setString", String.class, String.class),
                        nbtTagList.getMethod("add", nbtBase),
                        nbtTagCompound.getConstructor(),
                        nbtTagList.getConstructor(),
                        enumItemSlot.getField("MAINHAND").get(null));
            } catch (Throwable absent) {
                return null; // any handle missing ⇒ path C simply does not apply on this server
            }
        }

        /**
         * A display copy of {@code bukkit} with an explicit main-hand {@code AttributeModifiers} NBT
         * list minus the {@code attack_speed} entry (so vanilla renders attack-damage but not
         * attack-speed), or {@code null} when the item carries no attack-speed modifier / any slip.
         */
        @Nullable ItemStack strip(@NotNull ItemStack bukkit) {
            try {
                Object nms = asNmsCopy.invoke(null, bukkit);
                if (nms == null) {
                    return null;
                }
                Object raw = itemStackModifiers.invoke(nms, mainHandSlot);
                if (!(raw instanceof Multimap<?, ?> modifiers) || !containsAttackSpeed(modifiers)) {
                    return null; // no attack-speed line to strip — leave the copy untouched
                }
                Object list = nbtListCtor.newInstance();
                for (Map.Entry<?, ?> entry : modifiers.entries()) {
                    Object attributeName = entry.getKey();
                    if (isAttackSpeedName(attributeName)) {
                        continue; // drop only the attack_speed modifier
                    }
                    Object nbt = serializeModifier.invoke(null, entry.getValue());
                    nbtSetString.invoke(nbt, "AttributeName", String.valueOf(attributeName));
                    nbtSetString.invoke(nbt, "Slot", "mainhand");
                    nbtListAdd.invoke(list, nbt);
                }
                Object tag = Boolean.TRUE.equals(itemStackHasTag.invoke(nms))
                        ? itemStackGetTag.invoke(nms)
                        : nbtCompoundCtor.newInstance();
                nbtSet.invoke(tag, "AttributeModifiers", list);
                itemStackSetTag.invoke(nms, tag);
                Object result = asBukkitCopy.invoke(null, nms);
                return result instanceof ItemStack stripped ? stripped : null;
            } catch (Throwable ignored) {
                return null; // clean no-op on any reflective failure
            }
        }

        /** The lowercased effective main-hand modifier names on {@code bukkit}, or {@code null} on a slip. */
        @Nullable Set<String> mainHandNames(@NotNull ItemStack bukkit) {
            try {
                Object nms = asNmsCopy.invoke(null, bukkit);
                if (nms == null) {
                    return null;
                }
                Object raw = itemStackModifiers.invoke(nms, mainHandSlot);
                if (!(raw instanceof Multimap<?, ?> modifiers)) {
                    return null;
                }
                Set<String> names = new LinkedHashSet<>();
                for (Object key : modifiers.keySet()) {
                    names.add(String.valueOf(key).toLowerCase(Locale.ROOT));
                }
                return names;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static boolean containsAttackSpeed(@NotNull Multimap<?, ?> modifiers) {
            for (Object key : modifiers.keySet()) {
                if (isAttackSpeedName(key)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The attribute name is {@code generic.attackSpeed} (1.9–1.12) or the underscored
         * {@code generic.attack_speed} (1.13+); underscores are stripped so both normalise to a
         * {@code attackspeed} substring — attack_damage never matches.
         */
        private static boolean isAttackSpeedName(@Nullable Object attributeName) {
            if (attributeName == null) {
                return false;
            }
            String normalised = attributeName.toString().toLowerCase(Locale.ROOT).replace("_", "");
            return normalised.contains("attackspeed");
        }

        private static @Nullable String versionedRevision() {
            try {
                Server server = Bukkit.getServer();
                if (server == null) {
                    return null;
                }
                Package pkg = server.getClass().getPackage();
                if (pkg == null) {
                    return null;
                }
                String name = pkg.getName();
                int dot = name.lastIndexOf('.');
                String token = dot < 0 ? name : name.substring(dot + 1);
                return REVISION.matcher(token).matches() ? token : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}

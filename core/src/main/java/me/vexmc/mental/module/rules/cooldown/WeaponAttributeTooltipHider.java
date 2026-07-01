package me.vexmc.mental.module.rules.cooldown;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.vexmc.mental.config.MentalConfig;
import me.vexmc.mental.module.hitreg.DamageCalculator;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.EffectiveMaterial;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Strips ONLY the "Attack Speed" line from the 1.9 attribute tooltip block on
 * weapons and tools while the attack-cooldown module is enabled — keeping the
 * "Attack Damage" line, which 1.8 weapons DID show.
 *
 * <p>Mental removes the 1.9 attack cooldown by spoofing the client's
 * {@code attack_speed} attribute base high (see {@link CooldownSpoof}); a side
 * effect is that a weapon's tooltip then renders a huge "Attack Speed" value (the
 * client merges the item's modifier with the player's spoofed base). The owner's
 * requirement is era-faithful: 1.8 weapons displayed attack damage but never an
 * attack-speed line, so this listener removes only the {@code attack_speed}
 * modifier from the item's resolved attribute set, leaving {@code attack_damage}
 * (and thus its tooltip line) intact.</p>
 *
 * <p>Display-only: the modifier is dropped on the item COPY carried in the
 * outbound SET_SLOT / WINDOW_ITEMS packet, never the real server-side stack, so it
 * needs no teardown and the item's actual modifiers are untouched. Gated on the
 * attack-cooldown config flag (read from the atomic snapshot the netty thread
 * already consumes) — a clean no-op when the module is off. Folia-safe: runs on
 * the netty send thread and only reads/rewrites the packet's item data.</p>
 *
 * <h2>Per-version mechanism (no single cross-version Bukkit API exists)</h2>
 * <p>Vanilla weapon modifiers are IMPLICIT item-type defaults: they are NOT
 * present in the wire item, so a naive {@code ItemMeta.getAttributeModifiers()}
 * returns nothing and there is no attack_speed entry to remove. We must read the
 * RESOLVED modifiers, drop the attack_speed one, and write the result back
 * explicitly. Two reflective paths, tried in order, each a graceful no-op on
 * failure (older server / reflection miss → the speed line simply stays on that
 * version, no breakage):</p>
 * <ol>
 *   <li><b>(A) Modern NMS component (1.20.5+; the owner's likely server).</b>
 *       Walk to the {@code CraftItemStack} handle (mirroring
 *       {@code SwordBlockComponents.nmsHandle}) and call
 *       {@code ItemStack.get(DataComponents.ATTRIBUTE_MODIFIERS)} — the NMS
 *       resolving getter, which materialises the item-type defaults so a vanilla
 *       sword returns an {@code ItemAttributeModifiers} carrying both the damage
 *       and speed entries (javap-verified record on the 1.21.11 jar). Rebuild it
 *       without the {@code attack_speed} {@code Entry} and
 *       {@code set(...)} it back, then convert the mutated Bukkit item out.</li>
 *   <li><b>(B) Legacy Bukkit Material defaults (≤1.20.x; removed on 1.21+).</b>
 *       {@code Material.getItemAttributes(EquipmentSlot.HAND)} returns the default
 *       modifier {@code Multimap}; rebuild it without the attack_speed key and
 *       {@code ItemMeta.setAttributeModifiers(...)} the result.</li>
 * </ol>
 * <p>If neither path is available, {@link #hideWeaponAttributes} returns
 * {@code null} (no change). Because tooltips are CLIENT-rendered, this fix is
 * verified by the owner's client, not the integration matrix — the matrix cannot
 * assert on tooltip text.</p>
 */
public final class WeaponAttributeTooltipHider implements PacketListener {

    private final MentalConfig config;

    /** Stable display id for the era attack-damage modifier this rewriter stamps on the packet copy (rule 3). */
    private static final UUID LEGACY_ATTACK_ID =
            UUID.nameUUIDFromBytes("mental:legacy:attack_damage".getBytes(StandardCharsets.UTF_8));

    /* ---- Modern NMS path (A) handles, resolved once; null when absent ---- */
    private final @Nullable Object nmsAttributeModifiersType; // DataComponents.ATTRIBUTE_MODIFIERS
    private final @Nullable Method nmsItemStackGet;           // ItemStack.get(DataComponentType)
    private final @Nullable Method nmsItemStackSet;           // ItemStack.set(DataComponentType, Object)
    private final @Nullable Method itemAttributeModifiersList; // ItemAttributeModifiers.modifiers()
    private final @Nullable Method entryAttribute;            // Entry.attribute() -> Holder
    private final @Nullable java.lang.reflect.Constructor<?> itemAttributeModifiersCtor; // (List)
    private final @Nullable Object nmsAttackSpeedHolder;      // Attributes.ATTACK_SPEED
    private final @Nullable Method holderValue;               // Holder.value()
    private volatile @Nullable Field craftItemStackHandleField;

    /* ---- Legacy Bukkit path (B) handle, resolved once; null when absent ---- */
    private final @Nullable Method materialGetItemAttributes; // Material.getItemAttributes(EquipmentSlot)

    public WeaponAttributeTooltipHider(@NotNull MentalConfig config) {
        this.config = config;

        Object nmsAttrType = null;
        Method nmsGet = null;
        Method nmsSet = null;
        Method modsList = null;
        Method entryAttr = null;
        java.lang.reflect.Constructor<?> iamCtor = null;
        Object attackSpeedHolder = null;
        Method holderVal = null;

        try {
            // (A) NMS component classes. ItemStack.get(DataComponentType) RESOLVES the
            // item-type defaults, so a vanilla sword yields its full ItemAttributeModifiers.
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

            nmsAttrType = dataComponents.getField("ATTRIBUTE_MODIFIERS").get(null);
            // get(DataComponentType<? extends T>) and set(DataComponentType<T>, T) — match by
            // arity + first-param assignability (mirrors SwordBlockComponents' finders), so the
            // generic wildcard erasure never trips the lookup.
            nmsGet = findUnary(nmsItemStack, "get", componentType);
            nmsSet = findBinary(nmsItemStack, "set", componentType);
            modsList = itemAttributeModifiers.getMethod("modifiers");
            entryAttr = entry.getMethod("attribute");
            iamCtor = itemAttributeModifiers.getConstructor(List.class);
            attackSpeedHolder = attributesClass.getField("ATTACK_SPEED").get(null);
            holderVal = holder.getMethod("value");
        } catch (Throwable absent) {
            // Pre-component server (≤1.20.4) or a relocated internal — path (A) stays off.
            nmsAttrType = null;
            nmsGet = null;
            nmsSet = null;
            modsList = null;
            entryAttr = null;
            iamCtor = null;
            attackSpeedHolder = null;
            holderVal = null;
        }

        this.nmsAttributeModifiersType = nmsAttrType;
        this.nmsItemStackGet = nmsGet;
        this.nmsItemStackSet = nmsSet;
        this.itemAttributeModifiersList = modsList;
        this.entryAttribute = entryAttr;
        this.itemAttributeModifiersCtor = iamCtor;
        this.nmsAttackSpeedHolder = attackSpeedHolder;
        this.holderValue = holderVal;

        // (B) Legacy Bukkit defaults: Material.getItemAttributes(EquipmentSlot). Present on the
        // 1.17.1 compile floor and ≤1.20.x; removed on 1.21+. Resolved reflectively so a version
        // lacking it is a clean no-op rather than a NoSuchMethodError on the netty thread.
        Method matGetAttrs = null;
        try {
            matGetAttrs = Material.class.getMethod("getItemAttributes", EquipmentSlot.class);
        } catch (Throwable absent) {
            matGetAttrs = null;
        }
        this.materialGetItemAttributes = matGetAttrs;
    }

    @Override
    public void onPacketSend(@NotNull PacketSendEvent event) {
        Object type = event.getPacketType();
        if (PacketType.Play.Server.SET_SLOT.equals(type)) {
            if (!anyRuleActive()) {
                return; // zero-touch when no era-display rule is active
            }
            try {
                WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
                ItemStack hidden = rewriteAttributes(wrapper.getItem());
                if (hidden != null) {
                    wrapper.setItem(hidden);
                    event.markForReEncode(true);
                }
            } catch (Exception ignored) {
                // Never let a conversion failure propagate on the netty thread.
            }
            return;
        }
        if (PacketType.Play.Server.WINDOW_ITEMS.equals(type)) {
            if (!anyRuleActive()) {
                return;
            }
            try {
                WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
                List<ItemStack> items = wrapper.getItems();
                List<ItemStack> rewritten = new ArrayList<>(items.size());
                boolean changed = false;
                for (ItemStack item : items) {
                    ItemStack hidden = rewriteAttributes(item);
                    if (hidden != null) {
                        rewritten.add(hidden);
                        changed = true;
                    } else {
                        rewritten.add(item);
                    }
                }
                if (changed) {
                    wrapper.setItems(rewritten);
                    event.markForReEncode(true);
                }
            } catch (Exception ignored) {
                // Never let a conversion failure propagate on the netty thread.
            }
        }
    }

    /** Whether any of the three era-display rules is active — the packet listener is otherwise a full no-op. */
    private boolean anyRuleActive() {
        return config.cooldown().enabled()
                || config.armourStrength().enabled()
                || config.hitReg().legacyToolDamage();
    }

    /**
     * Returns a display copy of {@code peStack} with its attribute tooltip made era-correct, or {@code null}
     * when no change is needed. Three per-rule transforms, each gated on its own module (Mental owns the era
     * decision — nothing here fires unless a restore module elects it):
     *
     * <ol>
     *   <li><b>Rule 1 — strip attack_speed</b> (weapons, {@code attack-cooldown}): the existing surgical strip
     *       of the cooldown-spoof speed line from vanilla weapon defaults; keeps attack_damage. Paths A/B below.</li>
     *   <li><b>Rule 2 — strip armour_toughness</b> (armour, {@code old-armour-strength}): the 1.8 model has no
     *       toughness, so the era display omits the line. Explicit-modifier items only (e.g. a heroic piece).</li>
     *   <li><b>Rule 3 — re-value attack_damage</b> (weapons, {@code legacy-tool-damage}): show the era number
     *       ({@code legacyAttackDamage} of the item's EFFECTIVE material — marker-aware), so tooltip == the
     *       damage this hit actually deals. An explicit modifier set also suppresses the item defaults, so the
     *       speed line drops for free. Explicit-modifier items only.</li>
     * </ol>
     *
     * <p>Display-only, on the packet copy — never the real stack. Graceful no-op on any version/reflection miss
     * (the line simply stays). Rules 2/3 key off {@code getAttributeModifiers()} (the item's EXPLICIT set), so
     * they touch only items a plugin stamped and never mangle a vanilla item's implicit defaults.</p>
     */
    private @Nullable ItemStack rewriteAttributes(@Nullable ItemStack peStack) {
        if (peStack == null || peStack.isEmpty()) {
            return null;
        }
        org.bukkit.inventory.ItemStack bukkit = SpigotConversionUtil.toBukkitItemStack(peStack);
        if (bukkit == null) {
            return null;
        }
        Material type = bukkit.getType();
        if (isWeaponOrTool(type)) {
            // Rule 3 first: it re-values attack_damage AND drops speed (explicit set suppresses defaults).
            if (config.hitReg().legacyToolDamage()) {
                ItemStack legacy = rewriteLegacyAttack(bukkit);
                if (legacy != null) {
                    return legacy;
                }
            }
            // Rule 1: strip only the attack_speed line from vanilla weapon defaults (the cooldown spoof).
            if (config.cooldown().enabled()) {
                ItemStack viaComponent = stripAttackSpeedViaComponent(bukkit);
                return viaComponent != null ? viaComponent : stripAttackSpeedViaBukkitMeta(bukkit);
            }
            return null;
        }
        if (isArmour(type) && config.armourStrength().enabled()) {
            return stripToughnessExplicit(bukkit); // Rule 2
        }
        return null;
    }

    /**
     * Rule 3 — re-value an explicit-modifier weapon's {@code attack_damage} to the era total for its EFFECTIVE
     * material (the {@code combat:effective_material} marker when present, else its own type), so a display-swapped
     * diamond-in-disguise shows the diamond-era number rather than the display material's. Returns {@code null}
     * when the item carries no explicit attack_damage (a vanilla weapon — left to rule 1) or is not a legacy melee
     * tool. The explicit set also suppresses the item's default modifiers, so the speed line drops with it.
     */
    private @Nullable ItemStack rewriteLegacyAttack(@NotNull org.bukkit.inventory.ItemStack bukkit) {
        Attribute attackDamage = Attributes.attackDamage();
        if (attackDamage == null) {
            return null;
        }
        ItemMeta meta = bukkit.getItemMeta();
        if (meta == null) {
            return null;
        }
        Multimap<Attribute, AttributeModifier> explicit = meta.getAttributeModifiers();
        if (explicit == null || !explicit.containsKey(attackDamage)) {
            return null; // implicit-default (vanilla) weapon — not ours to re-value; rule 1 handles its speed line
        }
        Double legacyTotal = DamageCalculator.legacyAttackDamage(EffectiveMaterial.of(bukkit));
        if (legacyTotal == null) {
            return null; // not a legacy melee tool (hoe, rod, …)
        }
        double amount = legacyTotal - 1.0; // player base attack is 1.0; the modifier carries the remainder
        if (amount <= 0.0) {
            return null;
        }
        Multimap<Attribute, AttributeModifier> rebuilt = LinkedHashMultimap.create();
        for (var e : explicit.entries()) {
            if (!attackDamage.equals(e.getKey())) {
                rebuilt.put(e.getKey(), e.getValue()); // keep any non-attack modifiers the plugin set
            }
        }
        rebuilt.put(attackDamage, new AttributeModifier(
                LEGACY_ATTACK_ID, "mental.legacy.attack_damage", amount,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HAND));
        meta.setAttributeModifiers(rebuilt);
        bukkit.setItemMeta(meta);
        return SpigotConversionUtil.fromBukkitItemStack(bukkit);
    }

    /**
     * Rule 2 — drop the {@code armor_toughness} line from an explicit-modifier armour piece (the 1.8 model has no
     * toughness). Returns {@code null} when the item has no explicit toughness (a vanilla piece — its implicit
     * defaults are left untouched, matching the era math which reads only the armour attribute).
     */
    private @Nullable ItemStack stripToughnessExplicit(@NotNull org.bukkit.inventory.ItemStack bukkit) {
        Attribute toughness = Attributes.armorToughness();
        if (toughness == null) {
            return null;
        }
        ItemMeta meta = bukkit.getItemMeta();
        if (meta == null) {
            return null;
        }
        Multimap<Attribute, AttributeModifier> explicit = meta.getAttributeModifiers();
        if (explicit == null || !explicit.containsKey(toughness)) {
            return null;
        }
        Multimap<Attribute, AttributeModifier> kept = LinkedHashMultimap.create();
        for (var e : explicit.entries()) {
            if (!toughness.equals(e.getKey())) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        meta.setAttributeModifiers(kept);
        bukkit.setItemMeta(meta);
        return SpigotConversionUtil.fromBukkitItemStack(bukkit);
    }

    /** Worn-armour pieces (the toughness line lives here). */
    private static boolean isArmour(@Nullable Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }

    /**
     * Path (A): drop the {@code attack_speed} {@code Entry} from the NMS
     * {@code ItemAttributeModifiers} resolved on the item's handle, keeping every
     * other entry (including {@code attack_damage}) as-is. Returns the converted PE
     * item on success, or {@code null} when this path is unavailable, the resolved
     * component had no attack_speed entry, or any reflective step fails.
     */
    private @Nullable ItemStack stripAttackSpeedViaComponent(@NotNull org.bukkit.inventory.ItemStack bukkit) {
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
            // get() RESOLVES item-type defaults: a vanilla sword returns both damage + speed.
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
                kept.add(entry); // reuse the kept Entry object as-is
            }
            if (!removed) {
                return null; // no attack_speed entry — nothing to do, leave the item untouched
            }
            Object rebuilt = itemAttributeModifiersCtor.newInstance(kept);
            // set() mutates the COPY's handle only (the converted Bukkit item), never the real stack.
            nmsItemStackSet.invoke(handle, nmsAttributeModifiersType, rebuilt);
            return SpigotConversionUtil.fromBukkitItemStack(bukkit);
        } catch (Throwable ignored) {
            return null; // clean no-op on any reflective failure
        }
    }

    /**
     * Whether {@code attrHolder} (an NMS {@code Holder<Attribute>}) is the
     * attack_speed attribute. Primary check: {@code holder.value() ==
     * ATTACK_SPEED.value()} (same registered Attribute instance). Fallback when
     * {@code value()} is unavailable: the holder's string form ends with
     * {@code "attack_speed"} (covers a holder whose value cannot be unwrapped).
     */
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
                // value() worked but the instances differ -> definitively NOT attack_speed.
                if (value != null && expected != null) {
                    return false;
                }
            } catch (Throwable ignored) {
                // fall through to the string fallback
            }
        }
        String s = String.valueOf(attrHolder);
        return s.endsWith("attack_speed") || s.endsWith("attack_speed]") || s.contains(":attack_speed");
    }

    /**
     * Path (B): on servers where {@code Material.getItemAttributes(EquipmentSlot)}
     * exists (≤1.20.x), read the default modifier {@code Multimap}, rebuild it
     * without the {@code attack_speed} key, and set it explicitly on the item's
     * {@code ItemMeta}. Returns the converted PE item on success, or {@code null}
     * when this path is unavailable, there is no attack_speed entry, or any step
     * fails.
     */
    private @Nullable ItemStack stripAttackSpeedViaBukkitMeta(@NotNull org.bukkit.inventory.ItemStack bukkit) {
        if (materialGetItemAttributes == null) {
            return null;
        }
        Attribute attackSpeed = Attributes.attackSpeed();
        if (attackSpeed == null) {
            return null;
        }
        try {
            Object raw = materialGetItemAttributes.invoke(bukkit.getType(), EquipmentSlot.HAND);
            if (!(raw instanceof Multimap<?, ?> defaults)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Multimap<Attribute, AttributeModifier> typed = (Multimap<Attribute, AttributeModifier>) defaults;
            if (!typed.containsKey(attackSpeed)) {
                return null; // no attack_speed entry — leave the item untouched
            }
            Multimap<Attribute, AttributeModifier> kept = LinkedHashMultimap.create();
            for (var e : typed.entries()) {
                if (!attackSpeed.equals(e.getKey())) {
                    kept.put(e.getKey(), e.getValue());
                }
            }
            ItemMeta meta = bukkit.getItemMeta();
            if (meta == null) {
                return null;
            }
            // Set the explicit (speed-stripped) modifier set: the damage line stays, the speed
            // line goes. Display-only — applied to the converted COPY's meta, never the real stack.
            meta.setAttributeModifiers(kept);
            bukkit.setItemMeta(meta);
            return SpigotConversionUtil.fromBukkitItemStack(bukkit);
        } catch (Throwable ignored) {
            return null; // clean no-op on any failure
        }
    }

    /**
     * The {@code CraftItemStack} {@code handle} field, walked up the class
     * hierarchy and cached — the exact field-walking pattern of
     * {@code SwordBlockComponents.nmsHandle}. Returns {@code null} for a plain
     * Bukkit {@code ItemStack} that has no NMS handle.
     */
    private @Nullable Object nmsHandle(@NotNull org.bukkit.inventory.ItemStack stack)
            throws ReflectiveOperationException {
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
                return null; // Not a CraftItemStack (e.g. a Bukkit ItemStack with no handle).
            }
        }
        return field.get(stack);
    }

    /* ------------------------------------------------------------------ */
    /*  Reflection finders (mirror SwordBlockComponents)                   */
    /* ------------------------------------------------------------------ */

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

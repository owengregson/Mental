package me.vexmc.mental.v5.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import me.vexmc.mental.platform.ServerEnvironment;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The block-component adapter for 1.7-style sword blocking (the retired
 * {@code module.block.SwordBlockComponents} on the v5 {@link PlatformProbe}),
 * boot-probed once. It picks the highest component tier the server can drive
 * (by class/field presence, never a version literal) and exposes
 * {@link #apply}/{@link #strip}/{@link #isBlockingSword}/{@link #startUsing} for
 * the {@code SwordBlockingUnit}. A single write surface is chosen per stack
 * (Paper datacomponent API preferred; the javap-verified NMS handle path the
 * fallback) — the two are never mixed on one item.
 *
 * <h2>Tiers</h2>
 * <ul>
 *   <li><b>BLOCKS_ATTACKS</b> (1.21.5+) — the native component with a single
 *       {@code DamageReduction(angle=180, base=-0.5, factor=0.5)} that resolves
 *       to exactly the 1.8 {@code (damage-1)*0.5}; the server reports
 *       {@code isBlocking()} and runs the blocked-damage pipeline natively, so no
 *       software reduction is applied.</li>
 *   <li><b>CONSUMABLE</b> (1.21.0–1.21.4) — a {@code CONSUMABLE} with the BLOCK
 *       use animation grants the active-use pose; the unit applies the software
 *       {@code (damage-1)*0.5} reduction.</li>
 *   <li><b>NONE</b> (≤1.20.6) — no in-place component pose (the off-hand shield
 *       decoration serves those versions).</li>
 * </ul>
 *
 * <h2>Loud-fail (B10)</h2>
 * <p>A version that <em>should</em> expose a component tier but resolves to
 * {@link Tier#NONE} is a mapping break, logged loudly once at boot. Hot-path
 * reflective slips still degrade to a clean no-op — never corrupting an item —
 * which is the safety invariant the retired module established.</p>
 */
public final class SwordBlockAdapter {

    /** Which component capability this server can drive. */
    public enum Tier {
        BLOCKS_ATTACKS,
        CONSUMABLE,
        NONE
    }

    private final Tier tier;

    private final @Nullable Object nmsBlocksAttacksType;
    private final @Nullable Object nmsBlocksAttacksComponent;
    private final @Nullable Object nmsConsumableType;
    private final @Nullable Object nmsConsumableComponent;
    private final @Nullable Method nmsItemStackSet;
    private final @Nullable Method nmsItemStackRemove;
    private final @Nullable Method nmsItemStackHas;
    private final @Nullable Method craftPlayerGetHandle;
    private final @Nullable Method nmsGetUseItem;
    private final @Nullable Method nmsGetUseAnimation;
    private final @Nullable Object nmsBlockUseAnimation;
    private volatile @Nullable Field craftItemStackHandleField;

    private final @Nullable Method startUsingItemMethod;

    private final @Nullable Object paperBlocksAttacksType;
    private final @Nullable Object paperBlocksAttacksValue;
    private final @Nullable Object paperConsumableType;
    private final @Nullable Object paperConsumableValue;
    private final @Nullable Method paperSetData;
    private final @Nullable Method paperUnsetData;
    private final @Nullable Method paperHasData;

    private final boolean usePaperApi;

    /**
     * Boot-probes the adapter and loud-logs a mapping break (a tier the running
     * version should expose but does not resolve).
     */
    public static @NotNull SwordBlockAdapter probe(
            @NotNull ServerEnvironment environment, @NotNull Consumer<String> log) {
        SwordBlockAdapter adapter = new SwordBlockAdapter();
        boolean consumableExpected = environment.isAtLeast(1, 21, 0);
        if (consumableExpected && adapter.tier == Tier.NONE) {
            log.accept("platform-probe: the sword-block components (DataComponents.CONSUMABLE/"
                    + "BLOCKS_ATTACKS) did not resolve on " + environment.describe()
                    + " — a mapping break; the in-place sword-block pose is unavailable.");
        }
        return adapter;
    }

    private SwordBlockAdapter() {
        Tier resolvedTier = Tier.NONE;

        Object nmsBlocksType = null;
        Object nmsBlocksComponent = null;
        Object nmsConsType = null;
        Object nmsConsComponent = null;
        Method nmsSet = null;
        Method nmsRemove = null;
        Method nmsHas = null;
        Method getHandle = null;
        Method getUseItem = null;
        Method getUseAnim = null;
        Object blockAnim = null;
        Method startUsing = null;

        Object paperBlocksType = null;
        Object paperBlocksValue = null;
        Object paperConsType = null;
        Object paperConsValue = null;
        Method setData = null;
        Method unsetData = null;
        Method hasData = null;

        try {
            Class<?> nmsDataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            boolean hasBlocksAttacks = fieldPresent(nmsDataComponents, "BLOCKS_ATTACKS");
            boolean hasConsumable = fieldPresent(nmsDataComponents, "CONSUMABLE");

            if (hasBlocksAttacks) {
                resolvedTier = Tier.BLOCKS_ATTACKS;
            } else if (hasConsumable) {
                resolvedTier = Tier.CONSUMABLE;
            }

            if (resolvedTier != Tier.NONE) {
                Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
                Class<?> nmsComponentType = Class.forName("net.minecraft.core.component.DataComponentType");
                nmsSet = findItemStackBinary(nmsItemStack, "set", nmsComponentType);
                nmsRemove = findItemStackUnary(nmsItemStack, "remove", nmsComponentType);
                nmsHas = findItemStackUnary(nmsItemStack, "has", nmsComponentType);

                Class<?> nmsUseAnim = Class.forName("net.minecraft.world.item.ItemUseAnimation");
                blockAnim = nmsUseAnim.getField("BLOCK").get(null);

                if (hasConsumable) {
                    nmsConsType = nmsDataComponents.getField("CONSUMABLE").get(null);
                    Class<?> nmsConsumable = Class.forName("net.minecraft.world.item.component.Consumable");
                    Object builder = nmsConsumable.getMethod("builder").invoke(null);
                    builder = builder.getClass().getMethod("consumeSeconds", float.class)
                            .invoke(builder, Float.MAX_VALUE);
                    builder = builder.getClass().getMethod("animation", nmsUseAnim).invoke(builder, blockAnim);
                    nmsConsComponent = builder.getClass().getMethod("build").invoke(builder);
                }

                if (hasBlocksAttacks) {
                    nmsBlocksType = nmsDataComponents.getField("BLOCKS_ATTACKS").get(null);
                    nmsBlocksComponent = buildNmsBlocksAttacks();
                    if (nmsBlocksComponent == null) {
                        resolvedTier = hasConsumable ? Tier.CONSUMABLE : Tier.NONE;
                    }
                }

                Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
                getHandle = craftPlayer.getMethod("getHandle");
                Class<?> nmsPlayer = Class.forName("net.minecraft.world.entity.player.Player");
                getUseItem = nmsPlayer.getMethod("getUseItem");
                getUseAnim = nmsItemStack.getMethod("getUseAnimation");

                try {
                    Class<?> livingEntity = Class.forName("org.bukkit.entity.LivingEntity");
                    startUsing = livingEntity.getMethod("startUsingItem", EquipmentSlot.class);
                } catch (Throwable absent) {
                    startUsing = null;
                }

                try {
                    Class<?> paperTypes = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
                    Class<?> paperType = Class.forName("io.papermc.paper.datacomponent.DataComponentType");
                    Class<?> paperValuedType =
                            Class.forName("io.papermc.paper.datacomponent.DataComponentType$Valued");
                    Class<?> paperItemStack = Class.forName("org.bukkit.inventory.ItemStack");

                    setData = findPaperSetData(paperItemStack, paperValuedType);
                    if (setData != null) {
                        unsetData = paperItemStack.getMethod("unsetData", paperType);
                        hasData = paperItemStack.getMethod("hasData", paperType);

                        if (hasConsumable && fieldPresent(paperTypes, "CONSUMABLE")) {
                            paperConsType = paperTypes.getField("CONSUMABLE").get(null);
                            paperConsValue = buildPaperConsumable();
                        }
                        if (resolvedTier == Tier.BLOCKS_ATTACKS && fieldPresent(paperTypes, "BLOCKS_ATTACKS")) {
                            Object blocksValue = buildPaperBlocksAttacks();
                            if (blocksValue != null) {
                                paperBlocksType = paperTypes.getField("BLOCKS_ATTACKS").get(null);
                                paperBlocksValue = blocksValue;
                            }
                        }
                    }
                } catch (Throwable apiAbsent) {
                    setData = null;
                    unsetData = null;
                    hasData = null;
                    paperConsType = null;
                    paperConsValue = null;
                    paperBlocksType = null;
                    paperBlocksValue = null;
                }
            }
        } catch (Throwable absent) {
            resolvedTier = Tier.NONE;
        }

        this.tier = resolvedTier;
        this.nmsBlocksAttacksType = nmsBlocksType;
        this.nmsBlocksAttacksComponent = nmsBlocksComponent;
        this.nmsConsumableType = nmsConsType;
        this.nmsConsumableComponent = nmsConsComponent;
        this.nmsItemStackSet = nmsSet;
        this.nmsItemStackRemove = nmsRemove;
        this.nmsItemStackHas = nmsHas;
        this.craftPlayerGetHandle = getHandle;
        this.nmsGetUseItem = getUseItem;
        this.nmsGetUseAnimation = getUseAnim;
        this.nmsBlockUseAnimation = blockAnim;
        this.startUsingItemMethod = startUsing;
        this.paperBlocksAttacksType = paperBlocksType;
        this.paperBlocksAttacksValue = paperBlocksValue;
        this.paperConsumableType = paperConsType;
        this.paperConsumableValue = paperConsValue;
        this.paperSetData = setData;
        this.paperUnsetData = unsetData;
        this.paperHasData = hasData;

        boolean canPaper = setData != null && unsetData != null && hasData != null;
        boolean paperHasConsumable = paperConsType != null && paperConsValue != null;
        boolean paperHasBlocks = paperBlocksType != null && paperBlocksValue != null;
        this.usePaperApi = switch (resolvedTier) {
            case BLOCKS_ATTACKS -> canPaper && paperHasConsumable && paperHasBlocks;
            case CONSUMABLE -> canPaper && paperHasConsumable;
            case NONE -> false;
        };
    }

    public @NotNull Tier tier() {
        return tier;
    }

    /** Whether this server can drive an in-place sword-block pose at all. */
    public boolean supported() {
        return tier != Tier.NONE;
    }

    /** Whether the active reduction is native (Tier A) — no software reduction must be applied. */
    public boolean nativeReduction() {
        return tier == Tier.BLOCKS_ATTACKS;
    }

    public @NotNull String describe() {
        return tier + " via " + (tier == Tier.NONE ? "(none)" : (usePaperApi ? "paper-api" : "nms"));
    }

    /* ------------------------------------------------------------------ */
    /*  Apply / strip                                                      */
    /* ------------------------------------------------------------------ */

    /** Applies the tier-appropriate block component(s); true when the stack was modified. */
    public boolean apply(@Nullable ItemStack stack) {
        if (!supported() || stack == null || !isSword(stack.getType())) {
            return false;
        }
        boolean changed = false;
        if (usePaperApi) {
            if (paperConsumableType != null && paperConsumableValue != null) {
                changed |= paperSet(stack, paperConsumableType, paperConsumableValue);
            }
            if (tier == Tier.BLOCKS_ATTACKS
                    && paperBlocksAttacksType != null && paperBlocksAttacksValue != null) {
                changed |= paperSet(stack, paperBlocksAttacksType, paperBlocksAttacksValue);
            }
        } else {
            if (nmsConsumableType != null && nmsConsumableComponent != null) {
                changed |= nmsSet(stack, nmsConsumableType, nmsConsumableComponent);
            }
            if (tier == Tier.BLOCKS_ATTACKS
                    && nmsBlocksAttacksType != null && nmsBlocksAttacksComponent != null) {
                changed |= nmsSet(stack, nmsBlocksAttacksType, nmsBlocksAttacksComponent);
            }
        }
        return changed;
    }

    /** Removes any block component(s) this driver may have applied; true when the stack was modified. */
    public boolean strip(@Nullable ItemStack stack) {
        if (!supported() || stack == null || !isSword(stack.getType())) {
            return false;
        }
        boolean changed = false;
        if (usePaperApi) {
            if (paperConsumableType != null) {
                changed |= paperUnset(stack, paperConsumableType);
            }
            if (paperBlocksAttacksType != null) {
                changed |= paperUnset(stack, paperBlocksAttacksType);
            }
        } else {
            if (nmsConsumableType != null) {
                changed |= nmsRemove(stack, nmsConsumableType);
            }
            if (nmsBlocksAttacksType != null) {
                changed |= nmsRemove(stack, nmsBlocksAttacksType);
            }
        }
        return changed;
    }

    /** Puts {@code player} into the active-use (hand-raised) main-hand state; no-op when unresolved. */
    public void startUsing(@Nullable Player player) {
        if (player == null || startUsingItemMethod == null) {
            return;
        }
        try {
            startUsingItemMethod.invoke(player, EquipmentSlot.HAND);
        } catch (Throwable ignored) {
            // Best-effort — the component alone still drives the BLOCK animation.
        }
    }

    /** Whether {@code player} is actively raising a sword — the reliable component block-state read. */
    public boolean isBlockingSword(@Nullable Player player) {
        if (!supported() || player == null
                || craftPlayerGetHandle == null || nmsGetUseItem == null || nmsGetUseAnimation == null) {
            return false;
        }
        try {
            Object handle = craftPlayerGetHandle.invoke(player);
            if (handle == null) {
                return false;
            }
            Object useItem = nmsGetUseItem.invoke(handle);
            if (useItem == null) {
                return false;
            }
            Object animation = nmsGetUseAnimation.invoke(useItem);
            return animation != null && animation == nmsBlockUseAnimation;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  NMS component construction (verified)                              */
    /* ------------------------------------------------------------------ */

    private static @Nullable Object buildNmsBlocksAttacks() {
        try {
            Class<?> blocksAttacks = Class.forName("net.minecraft.world.item.component.BlocksAttacks");
            Class<?> damageReduction =
                    Class.forName("net.minecraft.world.item.component.BlocksAttacks$DamageReduction");
            Class<?> itemDamageFunction =
                    Class.forName("net.minecraft.world.item.component.BlocksAttacks$ItemDamageFunction");

            Object reduction = damageReduction
                    .getConstructor(float.class, Optional.class, float.class, float.class)
                    .newInstance(180.0f, Optional.empty(), -0.5f, 0.5f);

            Object defaultItemDamage = itemDamageFunction.getField("DEFAULT").get(null);

            return blocksAttacks
                    .getConstructor(float.class, float.class, List.class, itemDamageFunction,
                            Optional.class, Optional.class, Optional.class)
                    .newInstance(
                            0.0f, 1.0f, List.of(reduction), defaultItemDamage,
                            Optional.empty(), Optional.empty(), Optional.empty());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable Object buildPaperConsumable() {
        try {
            Class<?> consumable = Class.forName("io.papermc.paper.datacomponent.item.Consumable");
            Class<?> useAnim =
                    Class.forName("io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation");
            Object block = useAnim.getField("BLOCK").get(null);
            Object builder = consumable.getMethod("consumable").invoke(null);
            builder = builder.getClass().getMethod("consumeSeconds", float.class).invoke(builder, Float.MAX_VALUE);
            builder = builder.getClass().getMethod("animation", useAnim).invoke(builder, block);
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Deliberately unbuilt — the Paper BlocksAttacks builder was never javap-verified; Tier A uses NMS. */
    private static @Nullable Object buildPaperBlocksAttacks() {
        return null;
    }

    /* ------------------------------------------------------------------ */
    /*  Reflective set/unset plumbing                                      */
    /* ------------------------------------------------------------------ */

    private boolean paperSet(@NotNull ItemStack stack, @NotNull Object type, @NotNull Object value) {
        if (paperSetData == null) {
            return false;
        }
        try {
            paperSetData.invoke(stack, type, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean paperUnset(@NotNull ItemStack stack, @NotNull Object type) {
        if (paperUnsetData == null || paperHasData == null) {
            return false;
        }
        try {
            if (!paperHas(stack, type)) {
                return false;
            }
            paperUnsetData.invoke(stack, type);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean paperHas(@NotNull ItemStack stack, @NotNull Object type) {
        if (paperHasData == null) {
            return false;
        }
        try {
            Object result = paperHasData.invoke(stack, type);
            return result instanceof Boolean bool && bool;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean nmsSet(@NotNull ItemStack stack, @NotNull Object type, @NotNull Object component) {
        if (nmsItemStackSet == null) {
            return false;
        }
        try {
            Object handle = nmsHandle(stack);
            if (handle == null) {
                return false;
            }
            nmsItemStackSet.invoke(handle, type, component);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean nmsRemove(@NotNull ItemStack stack, @NotNull Object type) {
        if (nmsItemStackRemove == null) {
            return false;
        }
        try {
            Object handle = nmsHandle(stack);
            if (handle == null || !nmsHas(stack, type)) {
                return false;
            }
            nmsItemStackRemove.invoke(handle, type);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean nmsHas(@NotNull ItemStack stack, @NotNull Object type) {
        if (nmsItemStackHas == null) {
            return false;
        }
        try {
            Object handle = nmsHandle(stack);
            if (handle == null) {
                return false;
            }
            Object result = nmsItemStackHas.invoke(handle, type);
            return result instanceof Boolean bool && bool;
        } catch (Throwable ignored) {
            return false;
        }
    }

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
                return null; // Not a CraftItemStack (a plain Bukkit ItemStack with no handle).
            }
        }
        return field.get(stack);
    }

    /* ------------------------------------------------------------------ */
    /*  Reflection helpers                                                 */
    /* ------------------------------------------------------------------ */

    private static boolean fieldPresent(@NotNull Class<?> owner, @NotNull String name) {
        try {
            owner.getField(name);
            return true;
        } catch (NoSuchFieldException absent) {
            return false;
        }
    }

    private static @Nullable Method findItemStackBinary(
            @NotNull Class<?> itemStack, @NotNull String name, @NotNull Class<?> componentType) {
        for (Method m : itemStack.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 2) {
                continue;
            }
            if (m.getParameterTypes()[0].isAssignableFrom(componentType)) {
                return m;
            }
        }
        return null;
    }

    private static @Nullable Method findItemStackUnary(
            @NotNull Class<?> itemStack, @NotNull String name, @NotNull Class<?> componentType) {
        for (Method m : itemStack.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) {
                continue;
            }
            if (m.getParameterTypes()[0].isAssignableFrom(componentType)) {
                return m;
            }
        }
        return null;
    }

    private static @Nullable Method findPaperSetData(
            @NotNull Class<?> itemStack, @NotNull Class<?> valuedType) {
        for (Method m : itemStack.getMethods()) {
            if (!m.getName().equals("setData") || m.getParameterCount() != 2) {
                continue;
            }
            if (!valuedType.isAssignableFrom(m.getParameterTypes()[0])) {
                continue;
            }
            String second = m.getParameterTypes()[1].getName();
            if (!second.contains("DataComponentBuilder")) {
                return m;
            }
        }
        return null;
    }

    /** A sword by material name suffix — version-stable across the whole range. */
    public static boolean isSword(@Nullable Material material) {
        return material != null && material.name().endsWith("_SWORD");
    }
}

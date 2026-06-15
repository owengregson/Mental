package me.vexmc.mental.module.block;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The capability resolver and component driver for 1.7-style sword blocking.
 *
 * <p>This is the verified-once-at-init half of the module: it probes the server
 * for the data-component classes, picks the highest tier it can drive, and
 * exposes {@link #apply(ItemStack)} / {@link #strip(ItemStack)} /
 * {@link #isBlockingSword(Player)} for the listener to call. Everything is
 * resolved by class/field presence and reflection — never by a version literal —
 * so the same code path lights up on whichever capability a given server has.</p>
 *
 * <h2>Tiers (decompile- and javap-verified against the matrix jars)</h2>
 * <ul>
 *   <li><b>Tier A</b> — {@code BLOCKS_ATTACKS} present (verified present on the
 *       1.21.11 jar, ABSENT on 1.21.4). The sword gets a {@code BLOCKS_ATTACKS}
 *       component with a single {@code DamageReduction(horizontalBlockingAngle=180,
 *       base=-0.5, factor=0.5)} and {@code blockDelaySeconds=0}. The native
 *       {@code resolve = clamp(base + factor*damage, 0, damage)} produces exactly
 *       {@code (damage-1)*0.5} (verified in the {@code DamageReduction.resolve}
 *       bytecode), and the server natively reports {@code isBlocking()} and runs
 *       the blocked-damage pipeline. No software reduction is applied here. A
 *       {@code CONSUMABLE} component is also set so a sword reliably animates.</li>
 *   <li><b>Tier B</b> — {@code CONSUMABLE} present, {@code BLOCKS_ATTACKS} absent
 *       (1.21.0–1.21.4). The sword gets a {@code CONSUMABLE} component with the
 *       {@code BLOCK} use animation so {@code Item.getUseAnimation()==BLOCK} →
 *       the server enters the active-use / {@code isBlocking()} state. The
 *       component does not reduce damage on this tier, so the caller applies the
 *       1.8 {@code (damage-1)*0.5} reduction in software.</li>
 *   <li><b>Tier C</b> — neither component (1.17.1–1.20.6). {@link #tier()}
 *       returns {@link Tier#NONE}; the module never registers and never writes.</li>
 * </ul>
 *
 * <h2>API preference (mirrors OCM)</h2>
 * <p>The Paper Bukkit datacomponent API ({@code ItemStack.setData/unsetData/hasData}
 * with {@code io.papermc.paper.datacomponent.*}) is preferred when present, so we
 * mutate the inventory-backed stack through the supported surface. The NMS path
 * (set/remove the component on the {@code CraftItemStack} handle) is the verified
 * fallback where the API is absent — exactly OCM's order. All reflective lookups
 * happen once in the constructor; the hot path only invokes cached handles.</p>
 *
 * <p>All failures are swallowed in favour of a clean no-op: if Paper changes its
 * internals we strip back to tier {@link Tier#NONE} rather than corrupt an item.</p>
 */
final class SwordBlockComponents {

    /** Which component capability this server can drive. */
    enum Tier {
        /** {@code BLOCKS_ATTACKS} present — native reduction + native block state. */
        BLOCKS_ATTACKS,
        /** {@code CONSUMABLE} only — block pose, software reduction. */
        CONSUMABLE,
        /** Neither — the module is a no-op. */
        NONE
    }

    private final Tier tier;

    /* ---- NMS handles (the verified fallback path) ---- */
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

    /**
     * Paper's {@code LivingEntity#startUsingItem(EquipmentSlot)} — resolved
     * reflectively because Mental compiles against the 1.17.1 API floor where the
     * method does not exist, but every component tier (≥1.21) has it at runtime.
     */
    private final @Nullable Method startUsingItemMethod;

    /* ---- Paper Bukkit datacomponent API handles (preferred) ---- */
    private final @Nullable Object paperBlocksAttacksType;
    private final @Nullable Object paperBlocksAttacksValue;
    private final @Nullable Object paperConsumableType;
    private final @Nullable Object paperConsumableValue;
    private final @Nullable Method paperSetData;
    private final @Nullable Method paperUnsetData;
    private final @Nullable Method paperHasData;

    /**
     * Whether to drive ALL of this tier's component writes through the Paper API.
     * One surface per stack: never mix a Paper-API {@code setData} with an NMS
     * handle mutation on the same item (the API may rebuild the handle, orphaning
     * the NMS write). True only when every value this tier needs was built through
     * the Paper API; otherwise the whole tier uses the verified NMS path.
     */
    private final boolean usePaperApi;

    SwordBlockComponents() {
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
            // --- NMS: which components exist on this server? ---
            Class<?> nmsDataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            boolean hasBlocksAttacks = fieldPresent(nmsDataComponents, "BLOCKS_ATTACKS");
            boolean hasConsumable = fieldPresent(nmsDataComponents, "CONSUMABLE");

            if (hasBlocksAttacks) {
                resolvedTier = Tier.BLOCKS_ATTACKS;
            } else if (hasConsumable) {
                resolvedTier = Tier.CONSUMABLE;
            }

            if (resolvedTier != Tier.NONE) {
                // --- NMS component value construction (the verified path) ---
                Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
                Class<?> nmsComponentType = Class.forName("net.minecraft.core.component.DataComponentType");
                nmsSet = findItemStackBinary(nmsItemStack, "set", nmsComponentType);
                nmsRemove = findItemStackUnary(nmsItemStack, "remove", nmsComponentType);
                nmsHas = findItemStackUnary(nmsItemStack, "has", nmsComponentType);

                Class<?> nmsUseAnim = Class.forName("net.minecraft.world.item.ItemUseAnimation");
                blockAnim = nmsUseAnim.getField("BLOCK").get(null);

                // Consumable component (used on both tiers to drive the use animation).
                if (hasConsumable) {
                    nmsConsType = nmsDataComponents.getField("CONSUMABLE").get(null);
                    Class<?> nmsConsumable = Class.forName("net.minecraft.world.item.component.Consumable");
                    Object builder = nmsConsumable.getMethod("builder").invoke(null);
                    builder = builder.getClass().getMethod("consumeSeconds", float.class)
                            .invoke(builder, Float.MAX_VALUE);
                    builder = builder.getClass().getMethod("animation", nmsUseAnim).invoke(builder, blockAnim);
                    nmsConsComponent = builder.getClass().getMethod("build").invoke(builder);
                }

                // BlocksAttacks component (Tier A only).
                if (hasBlocksAttacks) {
                    nmsBlocksType = nmsDataComponents.getField("BLOCKS_ATTACKS").get(null);
                    nmsBlocksComponent = buildNmsBlocksAttacks();
                    if (nmsBlocksComponent == null) {
                        // Could not construct the verified component — drop to Consumable
                        // (still present on a BLOCKS_ATTACKS server) rather than ship nothing.
                        resolvedTier = hasConsumable ? Tier.CONSUMABLE : Tier.NONE;
                    }
                }

                // --- Active-item read for isBlockingSword ---
                Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
                getHandle = craftPlayer.getMethod("getHandle");
                Class<?> nmsPlayer = Class.forName("net.minecraft.world.entity.player.Player");
                getUseItem = nmsPlayer.getMethod("getUseItem");
                getUseAnim = nmsItemStack.getMethod("getUseAnimation");

                // --- startUsingItem(EquipmentSlot): reflective (not on the API floor) ---
                try {
                    Class<?> livingEntity = Class.forName("org.bukkit.entity.LivingEntity");
                    startUsing = livingEntity.getMethod("startUsingItem", EquipmentSlot.class);
                } catch (Throwable absent) {
                    startUsing = null; // The component alone still drives the BLOCK animation.
                }

                // --- Paper Bukkit datacomponent API (preferred when present) ---
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
                        // BlocksAttacks is intentionally routed through the verified NMS path
                        // (see buildPaperBlocksAttacks): we only resolve the Paper type if we
                        // can actually build the Paper value, so all BLOCKS_ATTACKS ops stay
                        // on one (NMS) surface and stripping never mismatches the apply path.
                        if (resolvedTier == Tier.BLOCKS_ATTACKS && fieldPresent(paperTypes, "BLOCKS_ATTACKS")) {
                            Object blocksValue = buildPaperBlocksAttacks();
                            if (blocksValue != null) {
                                paperBlocksType = paperTypes.getField("BLOCKS_ATTACKS").get(null);
                                paperBlocksValue = blocksValue;
                            }
                        }
                    }
                } catch (Throwable apiAbsent) {
                    // No Paper datacomponent API — the NMS fallback handles everything.
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
            // No component model at all — Tier.NONE, every field stays null.
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

        // Single-surface decision: use the Paper API only if it can build every value
        // this tier writes. Tier A needs both CONSUMABLE and BLOCKS_ATTACKS values;
        // Tier B needs only CONSUMABLE. (BLOCKS_ATTACKS is currently NMS-only — see
        // buildPaperBlocksAttacks — so Tier A always lands on NMS today; the flag is
        // future-proof for when the Paper BlocksAttacks builder is verified.)
        boolean canPaper = setData != null && unsetData != null && hasData != null;
        boolean paperHasConsumable = paperConsType != null && paperConsValue != null;
        boolean paperHasBlocks = paperBlocksType != null && paperBlocksValue != null;
        this.usePaperApi = switch (resolvedTier) {
            case BLOCKS_ATTACKS -> canPaper && paperHasConsumable && paperHasBlocks;
            case CONSUMABLE -> canPaper && paperHasConsumable;
            case NONE -> false;
        };
    }

    @NotNull Tier tier() {
        return tier;
    }

    /** Whether this server can drive an in-place sword-block pose at all. */
    boolean supported() {
        return tier != Tier.NONE;
    }

    /** Whether the active reduction is native (Tier A) — no software reduction must be applied. */
    boolean nativeReduction() {
        return tier == Tier.BLOCKS_ATTACKS;
    }

    @NotNull String describe() {
        return tier + " via " + (tier == Tier.NONE ? "(none)" : (usePaperApi ? "paper-api" : "nms"));
    }

    /* ------------------------------------------------------------------ */
    /*  Apply / strip                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Applies the tier-appropriate block component(s) to {@code stack}. Returns
     * {@code true} if the stack was modified (the caller must write it back).
     */
    boolean apply(@Nullable ItemStack stack) {
        if (!supported() || stack == null || !isSword(stack.getType())) {
            return false;
        }
        boolean changed = false;

        // Consumable drives the use animation on both tiers.
        if (usePaperApi) {
            // usePaperApi guarantees every value this tier needs was built via the API.
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
            // BlocksAttacks adds the native reduction + block state on Tier A.
            if (tier == Tier.BLOCKS_ATTACKS
                    && nmsBlocksAttacksType != null && nmsBlocksAttacksComponent != null) {
                changed |= nmsSet(stack, nmsBlocksAttacksType, nmsBlocksAttacksComponent);
            }
        }
        return changed;
    }

    /**
     * Removes any block component(s) this driver may have applied. Returns
     * {@code true} if the stack was modified (the caller must write it back).
     */
    boolean strip(@Nullable ItemStack stack) {
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

    /**
     * Puts {@code player} into the active-use (hand-raised) state for the main
     * hand, so the block pose shows and {@code isBlocking()} becomes true. Resolved
     * reflectively (the method is absent on the 1.17.1 compile floor but present on
     * every component-tier runtime). A no-op if unresolved — the component alone
     * still gives the sword its {@code BLOCK} use animation.
     */
    void startUsing(@Nullable Player player) {
        if (player == null || startUsingItemMethod == null) {
            return;
        }
        try {
            startUsingItemMethod.invoke(player, EquipmentSlot.HAND);
        } catch (Throwable ignored) {
            // Best-effort.
        }
    }

    /**
     * Whether {@code player} is currently actively using (raising) a sword — the
     * reliable block-state read for the component path, via the NMS active item's
     * {@code getUseAnimation() == BLOCK}. Bukkit's {@code isBlocking()} is
     * shield-biased and does not reliably track the consumable-based sword use.
     */
    boolean isBlockingSword(@Nullable Player player) {
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

    /**
     * Builds {@code new BlocksAttacks(0.0f blockDelaySeconds, 0.0f disableCooldownScale,
     * List.of(new DamageReduction(180.0f, Optional.empty(), -0.5f, 0.5f)),
     * ItemDamageFunction.DEFAULT, Optional.empty(), Optional.empty(), Optional.empty())}.
     *
     * <p>Constructor and {@code resolve} math verified by javap on the 1.21.11
     * jar: {@code DamageReduction.resolve} computes {@code clamp(base + factor*damage,
     * 0, damage)} (gated by the blocking angle), so {@code base=-0.5, factor=0.5}
     * yields exactly the 1.8 {@code (damage-1)*0.5}. {@code blockDelaySeconds=0}
     * means the block is effective immediately.</p>
     */
    private static @Nullable Object buildNmsBlocksAttacks() {
        try {
            Class<?> blocksAttacks = Class.forName("net.minecraft.world.item.component.BlocksAttacks");
            Class<?> damageReduction =
                    Class.forName("net.minecraft.world.item.component.BlocksAttacks$DamageReduction");
            Class<?> itemDamageFunction =
                    Class.forName("net.minecraft.world.item.component.BlocksAttacks$ItemDamageFunction");

            // DamageReduction(float horizontalBlockingAngle, Optional<HolderSet> type, float base, float factor)
            Object reduction = damageReduction
                    .getConstructor(float.class, Optional.class, float.class, float.class)
                    .newInstance(180.0f, Optional.empty(), -0.5f, 0.5f);

            Object defaultItemDamage = itemDamageFunction.getField("DEFAULT").get(null);

            // BlocksAttacks(float blockDelaySeconds, float disableCooldownScale,
            //   List<DamageReduction>, ItemDamageFunction, Optional bypassedBy,
            //   Optional blockSound, Optional disableSound)
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

    /* ------------------------------------------------------------------ */
    /*  Paper API component construction                                   */
    /* ------------------------------------------------------------------ */

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

    /**
     * The Paper-API {@code BlocksAttacks} value is deliberately NOT constructed
     * here. Its builder surface (factory name, {@code DamageReduction} sub-builder,
     * {@code addDamageReduction} parameter type) could not be javap-verified on
     * the matrix jars (the Paper API interface classes ship in the separate
     * paper-api artifact, absent from the server jars), and the project rule is to
     * never ship an unverified component write. Tier A therefore always writes the
     * {@code BlocksAttacks} component through the fully javap-verified NMS path
     * ({@link #buildNmsBlocksAttacks()}); only the {@code CONSUMABLE} value, whose
     * Paper-API shape is field-proven by OCM, is set through the Paper API when
     * available. Returning {@code null} keeps {@code paperBlocksAttacksValue} unset
     * so {@link #apply(ItemStack)} falls through to the NMS write.
     */
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
                return null; // Not a CraftItemStack (e.g. a Bukkit ItemStack with no handle).
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
            // The value overload (second param is NOT a builder) — set the value directly.
            String second = m.getParameterTypes()[1].getName();
            if (!second.contains("DataComponentBuilder")) {
                return m;
            }
        }
        return null;
    }

    /** A sword by material name suffix — version-stable across the whole range. */
    static boolean isSword(@Nullable Material material) {
        return material != null && material.name().endsWith("_SWORD");
    }
}

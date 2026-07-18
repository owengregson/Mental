package me.vexmc.mental.v5.feature.pots;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import me.vexmc.mental.platform.Enchantments;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Constructs (and verifies) the {@code pot-fill} payload — a splash Instant
 * Health II potion carrying an enchant glint — across the whole supported range,
 * resolving every version-fragile symbol by name/probe at construction, never by
 * a hard link above or below its availability.
 *
 * <h2>The three cross-version seams</h2>
 * <ul>
 *   <li><b>The item</b> — {@code SPLASH_POTION} (the item, distinct from the base
 *       {@code POTION}) has existed since 1.9; resolved by
 *       {@link Material#getMaterial(String)} so no enum constant is hard-linked.</li>
 *   <li><b>The effect</b> — from 1.20.5 the flattened {@code PotionType} carries
 *       {@code STRONG_HEALING} (already level II), set via {@code
 *       setBasePotionType(PotionType)}. Below that, {@code INSTANT_HEAL} + a
 *       {@code PotionData(type, extended=false, upgraded=true)} is Instant Health
 *       II, set via {@code setBasePotionData(PotionData)}. Both {@code PotionType}
 *       constants are resolved by {@code valueOf} so the getstatic of a
 *       constant absent on the running server never links.</li>
 *   <li><b>The glint</b> — from 1.20.5 {@code setEnchantmentGlintOverride(true)}
 *       gives a glint with no enchantment; below that the era-classic trick is a
 *       dummy enchantment plus {@link ItemFlag#HIDE_ENCHANTS} so the glow shows
 *       but the enchant line does not.</li>
 * </ul>
 *
 * <p>The strategy <em>selection</em> ({@link #chooseBase}, {@link #chooseGlint})
 * is pure and unit-pinned; the Bukkit mutation is integration-verified live
 * (PotsSuite) and self-checked here through {@link #isConstructedHealPotion} and
 * {@link #hasGlint}, which read the item back through the SAME probes.</p>
 */
public final class HealPotItems {

    /** Which base-effect API path applies on the running server. */
    public enum BaseStrategy { MODERN_BASE_TYPE, LEGACY_POTION_DATA, NONE }

    /** Which glint path applies on the running server. */
    public enum GlintStrategy { GLINT_OVERRIDE, DUMMY_ENCHANT }

    private final @Nullable Material splash;
    private final BaseStrategy baseStrategy;
    private final GlintStrategy glintStrategy;

    private final @Nullable PotionType strongHealing;   // 1.20.5+ level-II healing
    private final @Nullable PotionType instantHeal;      // pre-1.20.5 base healing
    private final @Nullable Method setBasePotionType;    // PotionMeta#setBasePotionType(PotionType)
    private final @Nullable Method getBasePotionType;    // PotionMeta#getBasePotionType()
    private final @Nullable Method setGlintOverride;      // ItemMeta#setEnchantmentGlintOverride(boolean)
    private final @Nullable Method getGlintOverride;      // ItemMeta#getEnchantmentGlintOverride()
    private final @Nullable Enchantment dummyEnchant;     // hidden glint carrier on the legacy path

    public HealPotItems() {
        this.splash = Material.getMaterial("SPLASH_POTION");
        this.strongHealing = potionType("STRONG_HEALING");
        this.instantHeal = potionType("INSTANT_HEAL");
        this.setBasePotionType = method(PotionMeta.class, "setBasePotionType", PotionType.class);
        this.getBasePotionType = method(PotionMeta.class, "getBasePotionType");
        this.setGlintOverride = method(ItemMeta.class, "setEnchantmentGlintOverride", boolean.class);
        this.getGlintOverride = method(ItemMeta.class, "getEnchantmentGlintOverride");
        this.dummyEnchant = Enchantments.unbreaking();
        this.baseStrategy = chooseBase(
                setBasePotionType != null, strongHealing != null, instantHeal != null);
        this.glintStrategy = chooseGlint(setGlintOverride != null);
    }

    /* ------------------------------ pure selection ----------------------------- */

    /** Modern base-type where both the setter and the level-II constant exist; else legacy; else none. */
    public static BaseStrategy chooseBase(
            boolean setBaseTypePresent, boolean strongHealingPresent, boolean instantHealPresent) {
        if (setBaseTypePresent && strongHealingPresent) {
            return BaseStrategy.MODERN_BASE_TYPE;
        }
        if (instantHealPresent) {
            return BaseStrategy.LEGACY_POTION_DATA;
        }
        return BaseStrategy.NONE;
    }

    /** The native glint override where present, else the dummy-enchant + hide-flag classic. */
    public static GlintStrategy chooseGlint(boolean glintOverridePresent) {
        return glintOverridePresent ? GlintStrategy.GLINT_OVERRIDE : GlintStrategy.DUMMY_ENCHANT;
    }

    /* ------------------------------- construction ------------------------------ */

    /** Which base-effect path this server resolved to (the boot report / test seam). */
    public BaseStrategy baseStrategy() {
        return baseStrategy;
    }

    /** Which glint path this server resolved to (the boot report / test seam). */
    public GlintStrategy glintStrategy() {
        return glintStrategy;
    }

    /** Whether a heal potion can be constructed at all on this server. */
    public boolean available() {
        return splash != null && baseStrategy != BaseStrategy.NONE;
    }

    /** A short, honest description of the resolved seams — for the boot/enable log. */
    public @NotNull String describe() {
        return "splash=" + (splash != null ? splash.name() : "ABSENT")
                + ", base=" + baseStrategy + ", glint=" + glintStrategy;
    }

    /**
     * A single splash Instant Health II potion with a glint, or {@code null} when
     * the server cannot express it (no splash item, or no healing base API). Best
     * effort: any Bukkit failure degrades to {@code null} rather than throwing.
     */
    public @Nullable ItemStack createSplashHealPotion() {
        if (splash == null) {
            return null;
        }
        try {
            ItemStack item = new ItemStack(splash);
            ItemMeta base = item.getItemMeta();
            if (!(base instanceof PotionMeta meta)) {
                return null;
            }
            if (!applyHealing(meta)) {
                return null;
            }
            applyGlint(meta);
            item.setItemMeta(meta);
            return item;
        } catch (Throwable failure) {
            return null;
        }
    }

    @SuppressWarnings("deprecation") // the PotionData path is the only base API pre-1.20.5
    private boolean applyHealing(PotionMeta meta) {
        switch (baseStrategy) {
            case MODERN_BASE_TYPE -> {
                try {
                    setBasePotionType.invoke(meta, strongHealing);
                    return true;
                } catch (ReflectiveOperationException failure) {
                    return false;
                }
            }
            case LEGACY_POTION_DATA -> {
                // Pre-1.20.5 only (guarded by strategy). PotionData + setBasePotionData are
                // resolved lazily here and never link on 1.20.5+, where this branch is dead.
                try {
                    Constructor<?> ctor = Class.forName("org.bukkit.potion.PotionData")
                            .getConstructor(PotionType.class, boolean.class, boolean.class);
                    Object data = ctor.newInstance(instantHeal, false, true); // Instant Health II
                    PotionMeta.class
                            .getMethod("setBasePotionData", Class.forName("org.bukkit.potion.PotionData"))
                            .invoke(meta, data);
                    return true;
                } catch (ReflectiveOperationException | LinkageError failure) {
                    return false;
                }
            }
            default -> {
                return false;
            }
        }
    }

    private void applyGlint(PotionMeta meta) {
        if (glintStrategy == GlintStrategy.GLINT_OVERRIDE && setGlintOverride != null) {
            try {
                setGlintOverride.invoke(meta, true);
                return;
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the dummy-enchant classic.
            }
        }
        if (dummyEnchant != null) {
            meta.addEnchant(dummyEnchant, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
    }

    /* ------------------------------ verification ------------------------------- */

    /**
     * Whether {@code item} is a splash healing potion this seam would have built —
     * read back through the SAME base-type probe (modern {@code getBasePotionType}
     * naming a healing type, or the legacy {@code getBasePotionData} healing +
     * upgraded). The suite asserts against this rather than a re-derivation.
     */
    @SuppressWarnings("deprecation")
    public boolean isConstructedHealPotion(@Nullable ItemStack item) {
        if (item == null || splash == null || item.getType() != splash) {
            return false;
        }
        if (!(item.getItemMeta() instanceof PotionMeta meta)) {
            return false;
        }
        if (getBasePotionType != null) {
            try {
                Object type = getBasePotionType.invoke(meta);
                return type instanceof PotionType potionType && isHealing(potionType.name());
            } catch (ReflectiveOperationException failure) {
                return false;
            }
        }
        try {
            org.bukkit.potion.PotionData data = meta.getBasePotionData();
            return data != null && isHealing(data.getType().name());
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Whether the item shows a glint — via the override probe or the hidden dummy enchant. */
    public boolean hasGlint(@Nullable ItemStack item) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (getGlintOverride != null) {
            try {
                Object value = getGlintOverride.invoke(meta);
                if (value instanceof Boolean flag && flag) {
                    return true;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the enchant check.
            }
        }
        return meta.hasEnchants() && meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS);
    }

    private static boolean isHealing(String potionTypeName) {
        // Covers HEALING / STRONG_HEALING (1.20.5+) and INSTANT_HEAL (pre-1.20.5).
        return potionTypeName.contains("HEAL");
    }

    private static @Nullable PotionType potionType(String name) {
        try {
            return PotionType.valueOf(name);
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }

    private static @Nullable Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }
}

package me.vexmc.mental.module.consumable;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Era-pinned 1.8.9 potion-effect tables for both golden apple types.
 *
 * <p>Every value in this class is decompile-cited against the 1.8.9 jar
 * ({@code zt.java:22,26,27,28} — Items/Foods — and {@code zw.java:641} —
 * EntityHuman.eat).  They are the unit-test pins; changing a value requires
 * a new citation from legacy-lab/decomp-1.8.9.</p>
 *
 * <p>String keys (lowercase Minecraft namespaced path, e.g.
 * {@code "regeneration"}) are used rather than {@code PotionEffectType} enum
 * constants for two reasons:
 * <ol>
 *   <li>Cross-version safety — {@code DAMAGE_RESISTANCE} was renamed to
 *       {@code RESISTANCE} in 1.9 Bukkit; string key resolution via
 *       {@code Registry} or {@code PotionEffectType.getByKey(NamespacedKey)}
 *       is stable across the full 1.17–26.x range.</li>
 *   <li>Unit-testability — this class has zero Bukkit server dependencies and
 *       can be tested in a plain JUnit environment without MockBukkit.</li>
 * </ol>
 *
 * <p>Amplifier values follow the 0-based Bukkit convention: level I = amp 0,
 * level II = amp 1, level V = amp 4.</p>
 */
public final class GoldenAppleEffects {

    private GoldenAppleEffects() {}

    /**
     * A pinned era potion effect: key, 0-based amplifier, and duration in ticks.
     *
     * @param effectKey     lowercase Minecraft key, e.g. {@code "regeneration"}
     * @param amplifier     0-based amplifier (I=0, II=1, V=4)
     * @param durationTicks duration in server ticks (20 t = 1 s)
     */
    public record EffectSpec(
            @NotNull String effectKey,
            int amplifier,
            int durationTicks) {}

    /**
     * 1.8.9 normal golden apple effects (gapple).
     *
     * <ul>
     *   <li>Regeneration II (amp 1) for 100 ticks  — {@code zt.java:22}</li>
     *   <li>Absorption I   (amp 0) for 2400 ticks  — {@code zw.java:641}</li>
     * </ul>
     *
     * @return immutable ordered list, one entry per effect
     */
    public static @NotNull List<EffectSpec> normalApple() {
        return NORMAL_APPLE;
    }

    /**
     * 1.8.9 enchanted (notch) golden apple effects (napple).
     *
     * <ul>
     *   <li>Regeneration V   (amp 4) for  600 ticks — {@code zt.java:22}</li>
     *   <li>Resistance I     (amp 0) for 6000 ticks — {@code zt.java:26}</li>
     *   <li>Fire Resistance I(amp 0) for 6000 ticks — {@code zt.java:27}</li>
     *   <li>Absorption I     (amp 0) for 2400 ticks — {@code zt.java:28}
     *       (Absorption I, NOT IV — the decompile is unambiguous)</li>
     * </ul>
     *
     * @return immutable ordered list, one entry per effect
     */
    public static @NotNull List<EffectSpec> notchApple() {
        return NOTCH_APPLE;
    }

    /* ------------------------------------------------------------------ */
    /*  Pinned tables (constants — never recomputed)                       */
    /* ------------------------------------------------------------------ */

    // Normal golden apple: Regen II (amp 1) 100 t + Absorption I (amp 0) 2400 t
    private static final List<EffectSpec> NORMAL_APPLE = List.of(
            new EffectSpec("regeneration", 1, 100),
            new EffectSpec("absorption",   0, 2400));

    // Notch apple: Regen V (amp 4) 600 t + Resistance I (amp 0) 6000 t
    //            + Fire Res I (amp 0) 6000 t + Absorption I (amp 0) 2400 t
    private static final List<EffectSpec> NOTCH_APPLE = List.of(
            new EffectSpec("regeneration",   4, 600),
            new EffectSpec("resistance",     0, 6000),
            new EffectSpec("fire_resistance",0, 6000),
            new EffectSpec("absorption",     0, 2400));
}

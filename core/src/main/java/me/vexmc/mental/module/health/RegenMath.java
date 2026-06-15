package me.vexmc.mental.module.health;

/**
 * Pure gate logic for the 1.8 natural health regeneration model.
 *
 * <p>Constants and gate logic are pinned to the decompiled 1.8.9 FoodStats
 * (xg.java): the {@code foodTickTimer} increments every tick; when it reaches
 * 80 the server calls {@code heal(1.0)} and adds 3.0f exhaustion, then resets
 * the timer. The gate additionally requires the gamerule
 * {@code naturalRegeneration} to be on and health to be strictly between 0
 * and max. This class contains no Bukkit state — pure math, exhaustively unit
 * tested in {@code RegenMathTest}.</p>
 */
public final class RegenMath {

    /**
     * Ticks between heals — {@code foodTickTimer} threshold in FoodStats.
     * 80 ticks = 4 seconds at 20 TPS. [FoodStats/xg.java: if (++foodTickTimer >= 80)]
     */
    public static final long INTERVAL_TICKS = 80L;

    /**
     * Half a heart healed per cycle. [FoodStats: heal(1.0f)]
     */
    public static final double HEAL_AMOUNT = 1.0;

    /**
     * Exhaustion added per heal. [FoodStats: addExhaustion(3.0f)]
     * Vanilla clamps exhaustion at 40.0f internally; we just add 3.0f and
     * leave the clamping to the vanilla food stat machinery.
     */
    public static final float EXHAUSTION_PER_HEAL = 3.0f;

    /**
     * Minimum food level for regen to fire. [FoodStats: if (foodLevel >= 18)]
     */
    public static final int FOOD_GATE = 18;

    private RegenMath() {}

    /**
     * Returns {@code true} when the 1.8 regen tick should fire.
     *
     * <p>Gate conditions (all must hold):</p>
     * <ul>
     *   <li>{@code naturalRegeneration} gamerule is on.</li>
     *   <li>{@code foodLevel >= FOOD_GATE} (18).</li>
     *   <li>{@code health > 0.0} — the player is alive.</li>
     *   <li>{@code health < maxHealth} — there is room to heal.</li>
     * </ul>
     *
     * @param foodLevel            player's current food level (0–20)
     * @param health               player's current health
     * @param maxHealth            player's max health (from the MAX_HEALTH attribute)
     * @param naturalRegeneration  value of the {@code naturalRegeneration} gamerule
     * @return {@code true} if a heal tick should be applied
     */
    public static boolean shouldHeal(
            int foodLevel,
            double health,
            double maxHealth,
            boolean naturalRegeneration) {
        return naturalRegeneration
                && foodLevel >= FOOD_GATE
                && health > 0.0
                && health < maxHealth;
    }
}

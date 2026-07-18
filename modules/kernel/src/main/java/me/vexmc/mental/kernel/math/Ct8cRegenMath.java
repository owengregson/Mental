package me.vexmc.mental.kernel.math;

/**
 * The Combat Test 8c food/regen gates (spec §2.7, code-confirmed against the
 * 1.8-style {@code FoodData}). CT8c retires the modern saturation fast-heal path
 * (saturation only pauses hunger loss) and returns to the 1.8 cadence: natural
 * regen and starvation each tick every <b>40 ticks</b>, and both regeneration
 * and sprinting require {@code foodLevel > 6}.
 *
 * <p>Pure gates only; the {@code +1 HP} heal, the {@code 50%} hunger-drain roll,
 * and the eat-interrupt live in the core shell over these constants (the drain
 * roll goes through a seeded, testable hook there — spec §3.2).</p>
 */
public final class Ct8cRegenMath {

    /** Ticks between natural-regen heals — the 1.8 cadence, spec §2.7. */
    public static final int REGEN_INTERVAL_TICKS = 40;

    /** Ticks between starvation damage applications at food 0 — spec §2.7. */
    public static final int STARVE_INTERVAL_TICKS = 40;

    /** The food level regen and sprint both gate strictly above — spec §2.7. */
    public static final int FOOD_GATE = 6;

    private Ct8cRegenMath() {}

    /** Whether natural regen may fire: {@code foodLevel > 6} (spec §2.7). */
    public static boolean canRegen(int foodLevel) {
        return foodLevel > FOOD_GATE;
    }

    /** Whether sprinting is permitted: {@code foodLevel > 6} (spec §2.7). */
    public static boolean canSprint(int foodLevel) {
        return foodLevel > FOOD_GATE;
    }
}

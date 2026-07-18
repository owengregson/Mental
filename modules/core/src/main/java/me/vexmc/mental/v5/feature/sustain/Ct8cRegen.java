package me.vexmc.mental.v5.feature.sustain;

import me.vexmc.mental.kernel.math.Ct8cRegenMath;

/**
 * The pure Combat Test 8c natural-regen decision (spec §2.7, the 1.8-style
 * {@code FoodData}). CT8c retires the modern saturation fast-heal and returns to
 * the 1.8 cadence: heal 1 HP while {@code foodLevel > 6} and hurt, with a 50%
 * chance to drain 1 hunger per heal. The cadence itself (every 40 ticks) is the
 * kernel {@link Ct8cRegenMath#REGEN_INTERVAL_TICKS} constant, driven by
 * {@link Ct8cRegenDriver}; this class holds only the per-tick gates.
 *
 * <p>Mirrors {@code RegenMath.shouldHeal} but with the CT8c food gate
 * ({@code > 6}, not {@code >= 18}) and no exhaustion model — CT8c drains a whole
 * hunger point on a coin-flip instead of adding exhaustion. The
 * {@code naturalRegeneration} gamerule is still honoured (a server that turns
 * natural regen off keeps it off).</p>
 */
public final class Ct8cRegen {

    private static final double DRAIN_CHANCE = 0.5;

    private Ct8cRegen() {}

    /**
     * Whether a natural-regen heal should fire this cycle: {@code foodLevel > 6}
     * (kernel gate), the {@code naturalRegeneration} gamerule on, and the player
     * alive with room to heal ({@code 0 < health < maxHealth}).
     */
    public static boolean heals(int foodLevel, double health, double maxHealth, boolean naturalRegen) {
        return naturalRegen
                && Ct8cRegenMath.canRegen(foodLevel)
                && health > 0.0
                && health < maxHealth;
    }

    /**
     * Whether the accompanying 1-hunger drain fires — a 50% coin flip per heal
     * (spec §2.7). The {@code roll} is a {@code [0,1)} draw injected by the
     * caller (a seeded value in tests, {@code ThreadLocalRandom} in production).
     */
    public static boolean drains(double roll) {
        return roll < DRAIN_CHANCE;
    }

    /**
     * Whether an incoming hit interrupts an in-progress eat/drink (spec §2.7,
     * returned in CT8b and ON in 8c). Only a hit <b>by a player or a mob</b>
     * interrupts — a living damager — and only while the victim is actually using
     * an item ({@code handRaised}); projectiles, environmental damage and blocks
     * never interrupt. Both are read at the seam by the caller
     * ({@code HandStates.isHandRaised}, {@code damager instanceof LivingEntity}).
     */
    public static boolean interruptsConsume(boolean handRaised, boolean damagerIsLiving) {
        return handRaised && damagerIsLiving;
    }
}

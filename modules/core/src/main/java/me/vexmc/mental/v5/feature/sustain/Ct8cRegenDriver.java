package me.vexmc.mental.v5.feature.sustain;

import java.util.function.DoubleSupplier;
import me.vexmc.mental.kernel.math.Ct8cRegenMath;
import org.jetbrains.annotations.NotNull;

/**
 * A per-player CT8c regen clock: it is ticked once every server tick and, on
 * each 40-tick boundary ({@link Ct8cRegenMath#REGEN_INTERVAL_TICKS}), decides
 * the heal/drain action from the player's state and a drain coin flip
 * ({@link Ct8cRegen}). Isolating the counter here makes the cadence
 * fake-clock-testable without a live scheduler — the {@code Ct8cRegenUnit} owns
 * one driver per player and applies the returned {@link Outcome}.
 *
 * <p>The counter resets on every boundary regardless of whether a heal fires (a
 * full-health or under-fed player still consumes the cycle, matching the 1.8
 * {@code foodTickTimer}). The drain coin flip is only drawn when a heal actually
 * fires — the drain is "per heal", not "per cycle" (spec §2.7).</p>
 */
public final class Ct8cRegenDriver {

    /** The action a boundary tick resolves to. */
    public enum Outcome {
        /** No heal this tick (mid-interval, or the gates failed on the boundary). */
        NONE,
        /** Heal 1 HP, no hunger drain (the coin flip came up ≥ 0.5). */
        HEAL,
        /** Heal 1 HP and drain 1 hunger (the coin flip came up < 0.5). */
        HEAL_AND_DRAIN
    }

    private final DoubleSupplier drainRoll;
    private int ticks;

    /**
     * @param drainRoll a {@code [0,1)} draw for the 50% hunger drain — seeded in
     *                  tests, {@code ThreadLocalRandom.current()::nextDouble} live
     */
    public Ct8cRegenDriver(@NotNull DoubleSupplier drainRoll) {
        this.drainRoll = drainRoll;
    }

    /**
     * Advances the clock by one tick and resolves the action. Returns
     * {@link Outcome#NONE} until the 40-tick boundary; on the boundary it applies
     * the {@link Ct8cRegen} gates and, when a heal fires, the drain coin flip.
     */
    public @NotNull Outcome tick(int foodLevel, double health, double maxHealth, boolean naturalRegen) {
        if (++ticks < Ct8cRegenMath.REGEN_INTERVAL_TICKS) {
            return Outcome.NONE;
        }
        ticks = 0;
        if (!Ct8cRegen.heals(foodLevel, health, maxHealth, naturalRegen)) {
            return Outcome.NONE;
        }
        return Ct8cRegen.drains(drainRoll.getAsDouble()) ? Outcome.HEAL_AND_DRAIN : Outcome.HEAL;
    }
}

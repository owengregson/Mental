package me.vexmc.mental.v5.feature.cadence;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.kernel.math.Ct8cChargeMath;
import me.vexmc.mental.kernel.math.Ct8cTables;

/**
 * The per-player attack-charge ledger for {@code charged-attacks} (CT8c decompile,
 * spec §2.1) — pure state over the kernel {@link Ct8cChargeMath}/{@link
 * Ct8cTables}, so the gate logic is unit-testable with a fake tick. It tracks, per
 * player, the tick the attack timer was last reset and whether the last swing was
 * a miss (arming the lenient recovery lane).
 *
 * <p>The full charge delay for the held weapon is {@code getAttackDelay()·2} =
 * {@code Ct8cTables.attackDelayTicks(attackSpeed)·2}; the scale ramps 0→2.0 across
 * it, hitting 1.0 (100%) at the halfway point. A landed hit at an entity requires
 * a full recharge ({@code scale ≥ 1}); the sole exception is the miss-recovery
 * lane — an air swing arms it, and a re-attack lands once more than four ticks
 * have elapsed since the reset.</p>
 *
 * <p><b>The reset rule is load-bearing.</b> The timer resets ONLY when a hit is
 * allowed to land — a rejected (too-early) hit leaves the reset untouched so the
 * charge keeps building; the era makes you WAIT, it does not restart the meter on
 * a mistimed click. An air swing, by contrast, always resets (and arms recovery).
 * A player who has never attacked is fully charged (their first hit always
 * lands).</p>
 *
 * <p>D2-owned state: mutated only by the region-thread damage / animation handlers
 * that feed it. The backing maps are concurrent so the enable / disable pass may
 * clear them from another thread.</p>
 */
public final class Ct8cChargeLedger {

    /** Ticks-since-reset for a player who has never attacked — a value past the full delay ⇒ fully charged. */
    private static final double NEVER_RESET_TICKS = Double.MAX_VALUE;

    private final Map<UUID, Long> resetTick = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> missRecovery = new ConcurrentHashMap<>();

    /** The outcome of an attack evaluation: whether it may land, and the charge scale it read. */
    public record Decision(boolean allowed, double scale) {}

    /**
     * Evaluates a landed swing at the default four-tick miss-recovery gate. The
     * {@link #onAttack(UUID, long, double, boolean, double) five-arg overload}
     * takes the configured {@code missRecoveryTicks}.
     */
    public Decision onAttack(UUID id, long nowTick, double attackSpeed, boolean requireFullCharge) {
        return onAttack(id, nowTick, attackSpeed, requireFullCharge, Ct8cChargeMath.MISS_RECOVERY_TICKS);
    }

    /**
     * Evaluates a landed swing at an entity for {@code id} at {@code nowTick} with
     * the attacker's effective {@code attackSpeed} attribute. Returns whether the
     * hit may land (always so when {@code requireFullCharge} is off) and the charge
     * scale. The miss-recovery lane opens once more than {@code missRecoveryTicks}
     * ticks have elapsed. Resets the timer and clears miss-recovery iff the hit lands.
     */
    public Decision onAttack(
            UUID id, long nowTick, double attackSpeed, boolean requireFullCharge, double missRecoveryTicks) {
        double fullDelay = fullDelayTicks(attackSpeed);
        double ticks = ticksSinceReset(id, nowTick);
        double scale = Ct8cChargeMath.scale(ticks, fullDelay);
        boolean miss = missRecovery.getOrDefault(id, Boolean.FALSE);
        boolean allowed = !requireFullCharge
                || Ct8cChargeMath.attackAllowed(scale, miss, ticks, missRecoveryTicks);
        if (allowed) {
            resetTick.put(id, nowTick);
            missRecovery.put(id, Boolean.FALSE);
        }
        return new Decision(allowed, scale);
    }

    /**
     * Records an air swing (a swing that connected with nothing) for {@code id} at
     * {@code nowTick}: resets the timer and arms the miss-recovery lane (spec §2.1).
     */
    public void onAirSwing(UUID id, long nowTick) {
        resetTick.put(id, nowTick);
        missRecovery.put(id, Boolean.TRUE);
    }

    /**
     * The current charge scale for {@code id} at {@code nowTick} with {@code
     * attackSpeed} — a read-only probe (no reset), for the published view the sweep
     * and reach consumers read.
     */
    public double scaleAt(UUID id, long nowTick, double attackSpeed) {
        return Ct8cChargeMath.scale(ticksSinceReset(id, nowTick), fullDelayTicks(attackSpeed));
    }

    /** Forgets a player's charge state (quit / disable). */
    public void forget(UUID id) {
        resetTick.remove(id);
        missRecovery.remove(id);
    }

    /** Drops all state (scope close). */
    public void clear() {
        resetTick.clear();
        missRecovery.clear();
    }

    private double ticksSinceReset(UUID id, long nowTick) {
        Long reset = resetTick.get(id);
        return reset == null ? NEVER_RESET_TICKS : (double) (nowTick - reset);
    }

    /** The full-charge delay in ticks: {@code getAttackDelay()·2} (spec §2.1). */
    static double fullDelayTicks(double attackSpeed) {
        return Ct8cTables.attackDelayTicks(attackSpeed) * 2.0;
    }
}

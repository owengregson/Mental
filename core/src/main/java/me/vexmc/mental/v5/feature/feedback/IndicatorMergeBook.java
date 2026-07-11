package me.vexmc.mental.v5.feature.feedback;

import java.util.UUID;
import me.vexmc.mental.kernel.fx.IndicatorPlacement;

/**
 * One attacker's same-tick indicator aggregation slot. Enchantment plugins
 * (StarEnchants and kin) deal their bonus as a SECOND {@code victim.damage(bonus,
 * attacker)} call inside the first hit's aftermath, raising a second EDBEE for
 * the same (attacker, victim) pair in the same tick — vanilla's no-damage-ticks
 * make that multiplicity impossible on its own, so same-pair-same-tick IS the
 * plugin-bonus signature. Without folding, the attacker sees two stands, each
 * under-reporting its own event's slice; folded, one stand carries the exact
 * total the full hit applied.
 *
 * <p>The book is ONE slot, overwritten by every fresh spawn — never a map, so
 * memory is bounded by construction and dies with its {@link IndicatorDriver}
 * on forget/close. {@link #merge} is a pure query: it folds the incoming event
 * against the remembered spawn and reports what the replacement needs (the
 * stand to destroy, the frozen geometry to reuse, the summed damage, the OR-ed
 * crit) without touching the slot — only {@link #remember} commits, so a
 * replacement that never ships (no entity id) leaves the slot honest about the
 * still-alive stand.</p>
 *
 * <p>Synchronized because two victims of one attacker sit on different region
 * threads under Folia; the critical sections are field copies.</p>
 */
final class IndicatorMergeBook {

    /**
     * A successful fold: the stand the replacement destroys, the first spawn's
     * frozen geometry (ring position, ballistic launch bearing, ground plane —
     * ZERO fresh world reads on the merge path), and the summed presentation.
     */
    record Merged(
            int priorEntityId, IndicatorPlacement.Spawn spawn, double groundY,
            double totalDamage, boolean crit) {}

    private boolean occupied;
    private UUID victimId;
    private long tick;
    private int entityId;
    private double totalDamage;
    private boolean crit;
    private IndicatorPlacement.Spawn spawn;
    private double groundY;

    /** Commits a shipped stand as this attacker's merge target, evicting any prior slot. */
    synchronized void remember(
            UUID victimId, long tick, int entityId, double totalDamage, boolean crit,
            IndicatorPlacement.Spawn spawn, double groundY) {
        this.occupied = true;
        this.victimId = victimId;
        this.tick = tick;
        this.entityId = entityId;
        this.totalDamage = totalDamage;
        this.crit = crit;
        this.spawn = spawn;
        this.groundY = groundY;
    }

    /**
     * Folds an event against the remembered spawn, or {@code null} when the slot
     * is empty, holds another victim, or holds another tick (⇒ a fresh
     * independent indicator). The merged crit is the OR of the remembered crit,
     * the event's own crit posture, and the summed damage reaching
     * {@code critThresholdDamage} — a big combined hit reads as a crit exactly
     * as a big single hit would.
     */
    synchronized Merged merge(
            UUID victimId, long tick, double damage, boolean eventCrit, double critThresholdDamage) {
        if (!occupied || this.tick != tick || !this.victimId.equals(victimId)) {
            return null;
        }
        double total = this.totalDamage + damage;
        boolean mergedCrit = this.crit || eventCrit || total >= critThresholdDamage;
        return new Merged(entityId, spawn, groundY, total, mergedCrit);
    }
}

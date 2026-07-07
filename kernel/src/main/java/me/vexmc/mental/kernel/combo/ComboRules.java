package me.vexmc.mental.kernel.combo;

/**
 * The detector's tunables (combo-hold §3.1) — a kernel-pure, immutable value the
 * {@link ComboTracker} reads. Every default is the design's:
 *
 * <ul>
 *   <li>{@code minHits 2} — the chain is <em>active</em> from the second hit;
 *       the first develops the chain and the second confirms intent, engaging the
 *       servo (a victim in a two-hit chain is "in combo" — no retaliation yet).</li>
 *   <li>{@code maxGapTicks 20} — sweet-spot cadence is ~10–12 ticks; a gap over
 *       20 ends the chain (era combos never idle that long).</li>
 *   <li>{@code groundedRunTicks 10} — brief ground-skims survive, a real
 *       touchdown (10 consecutive grounded ticks) ends it.</li>
 *   <li>{@code blowoutBlocks 6.0} — past 6 blocks of separation the pocket is
 *       gone and the chain is over.</li>
 * </ul>
 *
 * <p>Conservative by intent: a false positive mid-scrap costs only a clamped KB
 * nudge (§3.1's graceful-degradation property), so the thresholds err toward NOT
 * detecting a combo rather than toward a phantom one.</p>
 */
public record ComboRules(int minHits, int maxGapTicks, int groundedRunTicks, double blowoutBlocks) {

    /** The design defaults (§3.1) — the era-exact starting point the config carries. */
    public static final ComboRules DEFAULTS = new ComboRules(2, 20, 10, 6.0);
}

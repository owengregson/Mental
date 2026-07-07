package me.vexmc.mental.kernel.math;

/**
 * The vertical-trim shaper's tunables (COMBO_VERTICAL) — a kernel-pure, immutable
 * value the {@link VerticalTrim} solve reads. The third combo keeper, orthogonal to
 * the horizontal pocket servo ({@link PocketServoConfig}): where σ scales the fresh
 * <em>horizontal</em> knock, this shapes the fresh <em>vertical</em> so a juggled
 * victim reaches a chosen apex elevation — the height that keeps the deny-geometry
 * favorable (a higher victim eye point lengthens their reach-back hypotenuse, so the
 * separation at which they can answer shrinks).
 *
 * <p><b>Minimal-shaping contract (owner directive 2026-07-06).</b> "Combos can be
 * extensive with the victim only at standard KB with no vertical shaping, so we
 * expect RELATIVELY MINIMAL shaping on the vertical axis." So the shaping is
 * hard-bounded: the per-hit adjustment to the fresh vertical can never exceed
 * {@code bound} b/t (the era-safety guarantee). When the bounded solve wants MORE
 * than {@code bound} — the "something is wrong" signal — it clamps and the
 * {@link VerticalTrim.Result#saturated()} flag is raised so the compute site can
 * journal it. The bound damps OR lifts (both bounded): the shaper may raise the
 * fresh vertical above the era value, not only trim it.</p>
 *
 * <p>{@code targetApex} is the peak feet-height (blocks above the launch ground)
 * the shaper steers toward; the default (~1.15) is the era apex of a standard 0.4
 * fresh launch, so at the default a standard knock is held at its own canonical
 * apex and the trim barely moves — the {@code bound} does the rest of the guarding.
 * {@link #INACTIVE} is the module-off / not-this-attacker value: with it the solve
 * short-circuits to the era vertical (byte-identical — zero-touch). The caller ANDs
 * the config's activeness with "this hit's attacker holds the victim's active
 * combo," passing {@link #INACTIVE} otherwise, so a single {@link #active()} gate
 * covers both.</p>
 */
public record VerticalTrimConfig(boolean active, double targetApex, double bound) {

    /** The era apex of a standard 0.4 fresh launch from ground (≈ 1.153; see {@code VerticalTrim}). */
    public static final double DEFAULT_TARGET_APEX = 1.15;

    /**
     * The default per-hit vertical adjustment bound (b/t). 0.06 is ~15% of the era
     * 0.4 launch cap — small enough that the apex authority is a fraction of a block
     * per hit (not a juggle), large enough to hold a consistent apex; the design's
     * suggested order. The bound IS the era-safety guarantee and the "don't shape a
     * lot" guard.
     */
    public static final double DEFAULT_BOUND = 0.06;

    /** The module-off / not-applicable value — the solve returns the era vertical unchanged. */
    public static final VerticalTrimConfig INACTIVE =
            new VerticalTrimConfig(false, DEFAULT_TARGET_APEX, DEFAULT_BOUND);

    /** An active config with the given knobs (the config parser's constructor). */
    public static VerticalTrimConfig of(double targetApex, double bound) {
        return new VerticalTrimConfig(true, targetApex, bound);
    }
}

package me.vexmc.mental.v5.config.settings;

import me.vexmc.mental.kernel.math.VerticalTrimConfig;

/**
 * The vertical-trim module's tunables (COMBO_VERTICAL) — the third combo keeper,
 * orthogonal to the horizontal pocket servo ({@link ComboSettings}) and the reach
 * handicap ({@link ReachHandicapSettings}). Where the servo shapes the fresh
 * HORIZONTAL knock to hold a separation, this shapes the fresh VERTICAL knock so a
 * juggled victim reaches a chosen apex elevation — the height whose longer reach-back
 * hypotenuse denies their answer at closer range (a complement to the reach handicap).
 *
 * <p><b>Minimal-shaping contract (owner directive 2026-07-06).</b> Combos hold at
 * standard KB with no vertical shaping, so the shaping is deliberately small and
 * HARD-BOUNDED: {@code bound} caps the per-hit adjustment to the fresh vertical (the
 * era-safety guarantee and the "don't shape a lot" guard). {@code target-apex} defaults
 * to the era apex of a standard 0.4 fresh launch (~1.15 blocks — "a useful apex" the
 * standard knock already reaches), so at the default a standard knock is held at its own
 * canonical apex and the trim barely moves; a server wanting more denial raises it. When
 * the bounded solve wants more than {@code bound}, it clamps and flags the over-shaping
 * to the debug sink.</p>
 *
 * <p>The whole record is a byte-identical no-op contract only because the MODULE
 * defaults OFF (strictly server-opt-in — this is Mental's own novel mechanic, no
 * community precedent). The parser hard-caps {@code bound} so an operator cannot widen
 * it into non-minimal shaping.</p>
 */
public record VerticalTrimSettings(double targetApex, double bound) {

    /** The design defaults; the MODULE toggle — not this record — is what makes it a no-op. */
    public static final VerticalTrimSettings DEFAULTS =
            new VerticalTrimSettings(VerticalTrimConfig.DEFAULT_TARGET_APEX, VerticalTrimConfig.DEFAULT_BOUND);

    /** The active kernel config the {@code VerticalTrim} solve reads (never INACTIVE — the caller gates that). */
    public VerticalTrimConfig trim() {
        return VerticalTrimConfig.of(targetApex, bound);
    }
}

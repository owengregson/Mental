package me.vexmc.mental.kernel.profile;

/**
 * The modern (Paper 26.1.2) melee knockback formula — the per-profile knob
 * family that swaps the legacy 1.7/1.8 math for the current server's two-stage
 * knockback, ported byte-exact from the decompiled server jar (constants read
 * from the bytecode; the grounded closed form reproduces the live-measured
 * modern wire — standing {@code (0.4, 0.3608)}, sprint {@code (0.7, 0.4)}).
 *
 * <p>A modern melee hit is <b>two sequential applications</b> of one core:
 * a positional <em>base</em> knock ({@link #baseStrength}, direction away from
 * the attacker's position) followed by a yaw-directed <em>extra</em> knock
 * ({@link #sprintBonus} + Knockback-enchant levels × {@link #enchantBonus}),
 * each halving the surviving motion ({@link #residualHorizontal} /
 * {@link #residualVertical}, the vanilla {@code ÷ 2}) and scaling its added
 * strength by the fractional {@code (1 − knockbackResistance)}. The vertical is
 * grounded-gated: a grounded victim gets {@code min(}{@link #verticalCap}{@code,
 * vy·residualVertical + strength)}, an airborne victim keeps its own vy
 * unchanged (zero lift — the modern "slammed sideways/down mid-air" feel) when
 * {@link #downwardKnockback} is on, or the grounded lift when it is off.</p>
 *
 * <p><b>Era-exact no-op default.</b> {@link #OFF} carries the vanilla numbers
 * with {@code enabled=false}, so parsing a profile that names no formula (or
 * {@code formula: legacy}) yields the legacy path byte-identically and the
 * value stays comparison-stable. Only the {@code modern-*} presets opt in
 * ({@code formula: modern}). This family is additive: it entered the profile
 * record as the 20th component behind the same delegating-constructor seam
 * {@link PaceScaling} used, so every pre-modern preset, superseded revision,
 * and unit pin constructs unchanged and resolves to {@link #OFF}.</p>
 *
 * <p><b>Vertical shape (additive within this family).</b> The
 * {@link #verticalShape} + {@link #groundedVerticalFactor} +
 * {@link #airborneVerticalFactor} trio is an era-exact-no-op extension: an
 * eight-argument delegating constructor fills {@code (VerticalShape.VANILLA,
 * 0.75, 0.5)}, so every existing preset and unit pin constructs the byte-exact
 * vanilla shape and the two factors are carried-but-inert under it. Only the
 * {@code ct8c} preset ({@code vertical-shape: ct8c-split}) reads them, applying
 * the Combat Test 8c split (spec §2.5): grounded {@code min(cap,
 * groundedVerticalFactor·strength)}, airborne {@code min(cap, vyIn +
 * airborneVerticalFactor·strength)}. See {@link VerticalShape}.</p>
 */
public record ModernKnockback(
        boolean enabled,
        double baseStrength,
        double sprintBonus,
        double enchantBonus,
        double residualHorizontal,
        double residualVertical,
        double verticalCap,
        boolean downwardKnockback,
        VerticalShape verticalShape,
        double groundedVerticalFactor,
        double airborneVerticalFactor) {

    /**
     * The pre-shape convenience constructor (every existing preset's arity):
     * defaults {@link #verticalShape} to {@link VerticalShape#VANILLA} and the
     * two CT8c factors to the vanilla {@code 0.75} / {@code 0.5} — carried but
     * inert under the vanilla shape, so the modern output is byte-identical to
     * the pre-shape engine. Only the {@code ct8c} preset and the parser reach for
     * the eleven-argument canonical constructor.
     */
    public ModernKnockback(
            boolean enabled,
            double baseStrength,
            double sprintBonus,
            double enchantBonus,
            double residualHorizontal,
            double residualVertical,
            double verticalCap,
            boolean downwardKnockback) {
        this(enabled, baseStrength, sprintBonus, enchantBonus, residualHorizontal,
                residualVertical, verticalCap, downwardKnockback,
                VerticalShape.VANILLA, 0.75, 0.5);
    }

    /**
     * The era-exact no-op default: the vanilla 26.1.2 constants with the formula
     * switched OFF, so {@code parse(empty)} keeps yielding {@code LEGACY_17} and a
     * modern-less profile compares equal to one carrying this component. The
     * numbers are live regardless of {@link #enabled} so a profile that flips the
     * switch on without restating them still ships the byte-exact modern wire.
     */
    public static final ModernKnockback OFF =
            new ModernKnockback(false, 0.4, 0.5, 0.5, 0.5, 0.5, 0.4, true);
}

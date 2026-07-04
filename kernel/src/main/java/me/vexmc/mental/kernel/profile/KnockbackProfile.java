package me.vexmc.mental.kernel.profile;

/**
 * One complete knockback "feel": every knob the engine consumes, parsed and
 * validated as a unit. Profiles are immutable; the engine receives exactly
 * one per hit (resolved per victim), so a reload or a profile switch can
 * never tear a single computation.
 *
 * <p>The knob vocabulary is the union of the public improved-knockback fork
 * lineage (Kohi → NachoSpigot → WindSpigot → the ClubSpigot remakes), with
 * one deliberate translation: {@code friction} here is the <em>surviving
 * fraction</em> of the victim's motion (vanilla {@code ÷ 2.0} ≡ {@code 0.5}),
 * the Nacho convention — forks that publish a friction <em>divisor</em>
 * (Panda/Sport/Wind, typically {@code 2.0}) port as {@code 1 / divisor}.</p>
 *
 * <p>Every knob beyond the vanilla set defaults to an era-exact no-op:
 * parsing an empty section yields {@link #LEGACY_17}, byte-identical to the
 * 1.7.10 model Mental has always shipped.</p>
 */
public record KnockbackProfile(
        String name,
        String displayName,
        String description,
        Push base,
        VerticalMode verticalMode,
        Push extra,
        WtapExtra wtapExtra,
        Friction friction,
        Limits limits,
        Push air,
        Push add,
        RangeReduction rangeReduction,
        double sprintFactor,
        boolean combos,
        KnockbackDelivery meleeDelivery,
        KnockbackDelivery projectileDelivery,
        ResistancePolicy resistance,
        boolean shieldBlockingCancels,
        PaceScaling paceScaling) {

    /**
     * Additive growth: the 17-arg constructor defaults {@link #paceScaling} to
     * {@link PaceScaling#OFF} (the era-exact no-op), so every existing preset,
     * superseded revision, and unit pin constructs unchanged and yields OFF —
     * the no-op proof — while only the {@code signature} preset and the parser
     * pass the 18th argument to opt in.
     */
    public KnockbackProfile(
            String name,
            String displayName,
            String description,
            Push base,
            VerticalMode verticalMode,
            Push extra,
            WtapExtra wtapExtra,
            Friction friction,
            Limits limits,
            Push air,
            Push add,
            RangeReduction rangeReduction,
            double sprintFactor,
            boolean combos,
            KnockbackDelivery meleeDelivery,
            KnockbackDelivery projectileDelivery,
            ResistancePolicy resistance,
            boolean shieldBlockingCancels) {
        this(name, displayName, description, base, verticalMode, extra, wtapExtra, friction,
                limits, air, add, rangeReduction, sprintFactor, combos, meleeDelivery,
                projectileDelivery, resistance, shieldBlockingCancels, PaceScaling.OFF);
    }

    /** A horizontal/vertical pair — base push, bonus, multiplier, or offset. */
    public record Push(double horizontal, double vertical) {}

    /**
     * The WindSpigot sprint-freshness split: when enabled, a sprint hit whose
     * sprint was freshly (re-)engaged since the attacker's last attack — the
     * w-tap timing — uses these values instead of {@code extra}. Horizontally
     * that swap applies to the sprint levels only (enchant levels always use
     * {@code extra.horizontal}); the vertical bonus is a single flat term in
     * vanilla — applied once, never per level — so sprint freshness picks
     * which pair supplies it for the whole hit.
     */
    public record WtapExtra(boolean enabled, double horizontal, double vertical) {}

    /** Surviving fraction of the victim's residual motion, per axis. */
    public record Friction(double x, double y, double z) {}

    /**
     * {@code vertical} clamps the BASE vertical before bonus levels (vanilla
     * ordering — sprint hits reach past it); values {@code <= 0} disable.
     * {@code verticalMin} floors the final vertical after every addition;
     * {@code -3.9} (the packet encoding limit) is a no-op. {@code horizontal}
     * caps the post-friction base; values {@code <= 0} disable.
     */
    public record Limits(double vertical, double verticalMin, double horizontal) {

        public boolean limitsVertical() {
            return vertical > 0;
        }

        public boolean limitsHorizontal() {
            return horizontal > 0;
        }
    }

    /**
     * The MMC-remake distance taper: full knockback within
     * {@code startDistance}, beyond it the horizontal base push is reduced by
     * {@code min(factor × (distance − offset), maxReduction)} — max-reach
     * hits launch slightly less, point-blank hits are untouched. Melee only.
     */
    public record RangeReduction(
            boolean enabled, double startDistance, double factor, double offset, double maxReduction) {

        public static final RangeReduction DISABLED = new RangeReduction(false, 3.0, 0.025, 1.2, 0.12);

        /** The horizontal push reduction for a hit at {@code distance} blocks. */
        public double reductionAt(double distance) {
            if (!enabled || distance <= startDistance) {
                return 0.0;
            }
            return Math.min(factor * (distance - offset), maxReduction);
        }
    }

    /** The 1.7.10 era profile — Mental's default, and the parse fallback. */
    public static final KnockbackProfile LEGACY_17 = new KnockbackProfile(
            "legacy-1.7",
            "Legacy 1.7",
            "The 1.7.10 combat model: vanilla-era values with ledger combos.",
            new Push(0.4, 0.4),
            VerticalMode.ADD,
            new Push(0.5, 0.1),
            new WtapExtra(false, 0.5, 0.1),
            new Friction(0.5, 0.5, 0.5),
            new Limits(0.4, -3.9, -1.0),
            new Push(1.0, 1.0),
            new Push(0.0, 0.0),
            RangeReduction.DISABLED,
            1.0,
            true,
            KnockbackDelivery.TRACKER,
            KnockbackDelivery.TRACKER,
            ResistancePolicy.NONE,
            true);

    /** Whether {@code other} computes identical knockback (identity fields ignored). */
    public boolean sameValues(KnockbackProfile other) {
        return base.equals(other.base)
                && verticalMode == other.verticalMode
                && extra.equals(other.extra)
                && wtapExtra.equals(other.wtapExtra)
                && friction.equals(other.friction)
                && limits.equals(other.limits)
                && air.equals(other.air)
                && add.equals(other.add)
                && rangeReduction.equals(other.rangeReduction)
                && sprintFactor == other.sprintFactor
                && combos == other.combos
                && meleeDelivery == other.meleeDelivery
                && projectileDelivery == other.projectileDelivery
                && resistance == other.resistance
                && shieldBlockingCancels == other.shieldBlockingCancels
                && paceScaling.equals(other.paceScaling);
    }
}

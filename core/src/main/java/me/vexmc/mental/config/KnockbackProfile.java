package me.vexmc.mental.config;

import org.jetbrains.annotations.NotNull;

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
        @NotNull String name,
        @NotNull String displayName,
        @NotNull String description,
        @NotNull Push base,
        @NotNull VerticalMode verticalMode,
        @NotNull Push extra,
        @NotNull WtapExtra wtapExtra,
        @NotNull Friction friction,
        @NotNull Limits limits,
        @NotNull Push air,
        @NotNull Push add,
        @NotNull RangeReduction rangeReduction,
        double sprintFactor,
        boolean combos,
        @NotNull ResistancePolicy resistance,
        boolean shieldBlockingCancels) {

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
            ResistancePolicy.NONE,
            true);

    /** Whether {@code other} computes identical knockback (identity fields ignored). */
    public boolean sameValues(@NotNull KnockbackProfile other) {
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
                && resistance == other.resistance
                && shieldBlockingCancels == other.shieldBlockingCancels;
    }

    /**
     * Parses the knockback knob block. Missing keys fall back to
     * {@link #LEGACY_17}; out-of-range values warn through the reader and
     * fall back per key.
     */
    public static @NotNull KnockbackProfile parse(
            @NotNull String name,
            @NotNull String displayName,
            @NotNull String description,
            @NotNull ConfigReader reader) {
        ConfigReader base = reader.sub("base");
        ConfigReader extra = reader.sub("extra");
        ConfigReader wtap = reader.sub("wtap-extra");
        ConfigReader friction = reader.sub("friction");
        ConfigReader limits = reader.sub("limits");
        ConfigReader air = reader.sub("air");
        ConfigReader add = reader.sub("add");
        ConfigReader range = reader.sub("range-reduction");
        ConfigReader modifiers = reader.sub("modifiers");
        return new KnockbackProfile(
                name,
                displayName,
                description,
                new Push(
                        base.numberAtLeast("horizontal", LEGACY_17.base.horizontal(), 0),
                        base.numberAtLeast("vertical", LEGACY_17.base.vertical(), 0)),
                reader.oneOf("vertical-mode", LEGACY_17.verticalMode, VerticalMode.class),
                new Push(
                        extra.numberAtLeast("horizontal", LEGACY_17.extra.horizontal(), 0),
                        extra.numberAtLeast("vertical", LEGACY_17.extra.vertical(), 0)),
                new WtapExtra(
                        wtap.flag("enabled", LEGACY_17.wtapExtra.enabled()),
                        wtap.numberAtLeast("horizontal", LEGACY_17.wtapExtra.horizontal(), 0),
                        wtap.numberAtLeast("vertical", LEGACY_17.wtapExtra.vertical(), 0)),
                new Friction(
                        friction.numberAtLeast("x", LEGACY_17.friction.x(), 0),
                        friction.numberAtLeast("y", LEGACY_17.friction.y(), 0),
                        friction.numberAtLeast("z", LEGACY_17.friction.z(), 0)),
                new Limits(
                        limits.number("vertical", LEGACY_17.limits.vertical()),
                        limits.number("vertical-min", LEGACY_17.limits.verticalMin()),
                        limits.number("horizontal", LEGACY_17.limits.horizontal())),
                new Push(
                        air.numberAtLeast("horizontal", LEGACY_17.air.horizontal(), 0),
                        air.numberAtLeast("vertical", LEGACY_17.air.vertical(), 0)),
                new Push(
                        add.numberAtLeast("horizontal", LEGACY_17.add.horizontal(), 0),
                        add.numberAtLeast("vertical", LEGACY_17.add.vertical(), 0)),
                new RangeReduction(
                        range.flag("enabled", RangeReduction.DISABLED.enabled()),
                        range.numberAtLeast("start-distance", RangeReduction.DISABLED.startDistance(), 0),
                        range.numberAtLeast("factor", RangeReduction.DISABLED.factor(), 0),
                        range.numberAtLeast("offset", RangeReduction.DISABLED.offset(), 0),
                        range.numberAtLeast("max-reduction", RangeReduction.DISABLED.maxReduction(), 0)),
                modifiers.numberAtLeast("sprint", LEGACY_17.sprintFactor, 0),
                modifiers.flag("combos", LEGACY_17.combos),
                modifiers.oneOf("armor-resistance", LEGACY_17.resistance, ResistancePolicy.class),
                modifiers.flag("shield-blocking-cancels", LEGACY_17.shieldBlockingCancels));
    }
}

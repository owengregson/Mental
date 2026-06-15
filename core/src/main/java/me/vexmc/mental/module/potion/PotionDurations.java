package me.vexmc.mental.module.potion;

import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * The 1.8 potion-duration lookup that {@link PotionDurationModule} restores.
 *
 * <p>1.9 shortened many potion durations. This table returns the pre-1.9 (1.8)
 * duration, in server ticks, for a given potion identity. The source values are
 * OCM's {@code old-potion-effects.potion-durations} config table (in seconds),
 * converted here to ticks (×20):
 * {@code BukkitOldCombatMechanics/src/main/resources/config.yml}. OCM's table is
 * the de-facto reference for the pre-1.9 durations; per
 * {@code docs/superpowers/plans/2026-06-14-ocm-ground-truth.md §5} it is treated
 * as the agreed reference (not independently decompile-verified).</p>
 *
 * <p>This is a pure lookup — no Bukkit server registry, fully unit-testable. The
 * module resolves a {@code PotionType} to a base name + {@code extended}/
 * {@code upgraded} flags (modern {@code PotionData}) OR to a folded enum name
 * like {@code STRONG_STRENGTH}/{@code LONG_STRENGTH} (legacy {@code PotionType}),
 * then asks this class for the era ticks.</p>
 *
 * <p>Identity model (mirrors OCM's {@code PotionKey}):</p>
 * <ul>
 *   <li><b>base</b> — the effect family (strength, swiftness, …);</li>
 *   <li><b>upgraded</b> ("strong", level II) — picks the strong row when one
 *       exists, else falls back to the base row;</li>
 *   <li><b>extended</b> ("long") — picks the long row when one exists, else the
 *       base row;</li>
 *   <li><b>splash</b> — the splash/lingering column vs the drinkable column.</li>
 * </ul>
 *
 * <p>Instant potions (Healing/Harming) and unknown / no-effect bases
 * ({@code WATER}, {@code AWKWARD}, …) are absent from the table and return
 * {@link #NO_OVERRIDE} so the module leaves them untouched.</p>
 */
public final class PotionDurations {

    private PotionDurations() {}

    /** Sentinel returned when there is no era override for the given potion. */
    public static final int NO_OVERRIDE = -1;

    /**
     * The drink / splash duration in ticks for one variant of a potion family.
     * A {@code null} entry on {@link Family} means "no such variant" → fall back
     * to the base variant.
     *
     * @param drinkTicks  drinkable-column duration in ticks
     * @param splashTicks splash/lingering-column duration in ticks
     */
    private record Variant(int drinkTicks, int splashTicks) {}

    /**
     * The three OCM duration rows for a potion family. {@code strong} and
     * {@code extended} may be {@code null} when that family has no such variant
     * (e.g. Weakness has no strong row; Fire Resistance has no strong row).
     *
     * @param base     the level-I, non-extended row (always present)
     * @param strong   the upgraded (level II / "strong") row, or {@code null}
     * @param extended the extended ("long") row, or {@code null}
     */
    private record Family(@NotNull Variant base, Variant strong, Variant extended) {

        int ticks(boolean upgraded, boolean extendedDuration, boolean splash) {
            // OCM keys are mutually exclusive in practice (a potion is either
            // STRONG_ or LONG_, never both); prefer strong over extended if both
            // flags arrive, falling back to base when the variant is absent.
            Variant chosen = base;
            if (upgraded && strong != null) {
                chosen = strong;
            } else if (extendedDuration && extended != null) {
                chosen = extended;
            }
            return splash ? chosen.splashTicks() : chosen.drinkTicks();
        }
    }

    private static Variant v(int drinkSeconds, int splashSeconds) {
        return new Variant(drinkSeconds * 20, splashSeconds * 20);
    }

    /**
     * The era table, keyed by canonical base name. Seconds → ticks happens in
     * {@link #v}. Each row reproduces OCM's {@code drinkable}/{@code splash}
     * pair, with {@code strong}/{@code long} rows where OCM provides them.
     *
     * <p>OCM config rows (seconds), drink / splash:</p>
     * <pre>
     *   regeneration   45/33   strong 22/16   long 120/90
     *   swiftness     180/135  strong 90/67   long 480/360
     *   fire_resistance 180/135               long 480/360
     *   poison         45/33   strong 22/16   long 120/90
     *   night_vision  180/180               long 480/480
     *   weakness       90/90                 long 240/240
     *   strength      180/135  strong 90/67   long 480/360
     *   slowness       90/67                 long 240/180
     *   leaping       180/135  strong 90/67   long 480/360
     *   water_breathing 180/135             long 480/360
     *   invisibility  180/135               long 480/360
     * </pre>
     */
    private static final Map<String, Family> TABLE = Map.ofEntries(
            Map.entry("regeneration",
                    new Family(v(45, 33), v(22, 16), v(120, 90))),
            Map.entry("swiftness",
                    new Family(v(180, 135), v(90, 67), v(480, 360))),
            Map.entry("fire_resistance",
                    new Family(v(180, 135), null, v(480, 360))),
            Map.entry("poison",
                    new Family(v(45, 33), v(22, 16), v(120, 90))),
            Map.entry("night_vision",
                    new Family(v(180, 180), null, v(480, 480))),
            Map.entry("weakness",
                    new Family(v(90, 90), null, v(240, 240))),
            Map.entry("strength",
                    new Family(v(180, 135), v(90, 67), v(480, 360))),
            Map.entry("slowness",
                    new Family(v(90, 67), null, v(240, 180))),
            Map.entry("leaping",
                    new Family(v(180, 135), v(90, 67), v(480, 360))),
            Map.entry("water_breathing",
                    new Family(v(180, 135), null, v(480, 360))),
            Map.entry("invisibility",
                    new Family(v(180, 135), null, v(480, 360))));

    /**
     * Maps a modern/alias base name onto the OCM canonical key used in
     * {@link #TABLE}. Covers the 1.9 renames (SPEED→swiftness, JUMP→leaping,
     * SLOW→slowness) so a {@code PotionType.name()} from any version resolves.
     */
    private static final Map<String, String> ALIASES = Map.of(
            "speed", "swiftness",
            "jump", "leaping",
            "slow", "slowness");

    /**
     * The 1.8 duration in ticks for the given potion identity, or
     * {@link #NO_OVERRIDE} if no era override applies (instant potions,
     * no-effect bases, or an unrecognised type — the module leaves these alone).
     *
     * <p>{@code potionTypeKey} is tolerant of: case, a {@code minecraft:}
     * namespace prefix, and the legacy folded enum names {@code STRONG_*} /
     * {@code LONG_*} (which set {@code upgraded}/{@code extended} implicitly, in
     * addition to the explicit flags from modern {@code PotionData}).</p>
     *
     * @param potionTypeKey the base potion type token (e.g. {@code "strength"},
     *                      {@code "minecraft:strength"}, {@code "STRONG_STRENGTH"})
     * @param extended      the {@code PotionData.isExtended()} ("long") flag
     * @param upgraded      the {@code PotionData.isUpgraded()} ("strong") flag
     * @param splash        {@code true} for splash/lingering, {@code false} drink
     * @return the era duration in ticks, or {@link #NO_OVERRIDE}
     */
    public static int eraDurationTicks(
            @NotNull String potionTypeKey, boolean extended, boolean upgraded, boolean splash) {
        String name = potionTypeKey.toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }

        // Legacy PotionType folds the level into the enum name; honour either the
        // explicit flags (modern PotionData) or the name prefix (legacy enum).
        boolean strong = upgraded;
        boolean longDuration = extended;
        if (name.startsWith("strong_")) {
            strong = true;
            name = name.substring("strong_".length());
        } else if (name.startsWith("long_")) {
            longDuration = true;
            name = name.substring("long_".length());
        }

        String canonical = ALIASES.getOrDefault(name, name);
        Family family = TABLE.get(canonical);
        if (family == null) {
            return NO_OVERRIDE;
        }
        return family.ticks(strong, longDuration, splash);
    }
}

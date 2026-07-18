package me.vexmc.mental.v5.config.settings;

/**
 * The per-weapon-class attack speeds (attacks per second) for the
 * {@code weapon-attack-speeds} module — Combat Test 8c's rebalanced melee
 * cadence (design spec §2.2, code-confirmed from CT8c's {@code WeaponType} /
 * {@code Tiers}). Each value is the era attacks-per-second; the attribute the
 * module writes is {@code attacksPerSecond + 1.5} (the CT8c {@code attackSpeed}
 * base offset), and the effective attack delay is
 * {@code (int)(20 / clamp(attacksPerSecond, 0.1, 1024) + 0.5)} ticks.
 *
 * <p>Hoes alone are tier-sensitive in 8c (wood..netherite climb 2.0 → 3.5), so
 * they carry their own nested {@link HoeSpeeds}; every other class is one flat
 * value. The whole record is a byte-identical no-op because the MODULE defaults
 * OFF — the {@code DEFAULTS} are the code-confirmed §2.2 table, applied only once
 * the operator turns {@code weapon-attack-speeds} on.</p>
 */
public record WeaponSpeedSettings(
        double fist,
        double sword,
        double axe,
        double pickaxe,
        double shovel,
        double trident,
        HoeSpeeds hoe) {

    /**
     * The tier-sensitive hoe speeds — the one 8c weapon class whose attack speed
     * climbs with material tier (every other class is one flat att/s value).
     */
    public record HoeSpeeds(
            double wood,
            double stone,
            double iron,
            double gold,
            double diamond,
            double netherite) {

        /** The code-confirmed CT8c hoe att/s ladder (spec §2.2): 2.0 / 2.5 / 3.0 / 3.5 / 3.5 / 3.5. */
        public static final HoeSpeeds DEFAULTS = new HoeSpeeds(2.0, 2.5, 3.0, 3.5, 3.5, 3.5);
    }

    /** The code-confirmed CT8c att/s table (spec §2.2); inert while the module is OFF. */
    public static final WeaponSpeedSettings DEFAULTS =
            new WeaponSpeedSettings(2.5, 3.0, 2.0, 2.5, 2.0, 2.0, HoeSpeeds.DEFAULTS);
}

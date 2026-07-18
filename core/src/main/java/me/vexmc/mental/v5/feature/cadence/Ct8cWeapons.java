package me.vexmc.mental.v5.feature.cadence;

import me.vexmc.mental.kernel.math.Ct8cTables;
import me.vexmc.mental.kernel.math.Ct8cTables.Tier;
import me.vexmc.mental.kernel.math.Ct8cTables.WeaponClass;
import me.vexmc.mental.v5.config.settings.WeaponSpeedSettings;

/**
 * Pure classification of a held Bukkit item — by its {@code Material} NAME, so
 * the mapping is unit-testable without a Bukkit server — onto the CT8c {@link
 * WeaponClass}/{@link Tier} pair, plus the operator-tunable attacks-per-second
 * and the resulting ATTACK_SPEED attribute value (CT8c decompile, spec §2.2).
 *
 * <p>The material-name suffix set matches {@code AttackRangeAdapter.isWeapon}
 * ({@code _SWORD}/{@code _AXE}/{@code _PICKAXE}/{@code _SHOVEL}/{@code _HOE}/
 * {@code TRIDENT}), version-stable across the whole 1.9.4→modern range. Tier is
 * read from the material prefix, handling BOTH the modern spelling
 * ({@code WOODEN_}/{@code GOLDEN_}) and the pre-1.13 flattening spelling
 * ({@code WOOD_}/{@code GOLD_}) so the classifier is correct on the legacy tier
 * without routing through {@code LegacyMaterialNames}.</p>
 *
 * <p>Anything not one of the six CT8c melee classes — bare hand ({@code AIR}), a
 * non-weapon, or an out-of-table weapon such as the mace (spec §2.2 does not tune
 * it) — resolves to {@link WeaponClass#FIST}/{@link Tier#NONE}, the bare-hand
 * cadence (2.5 att/s), which is exactly vanilla's own fallback for a non-weapon in
 * hand.</p>
 */
public final class Ct8cWeapons {

    /**
     * The CT8c ATTACK_SPEED base offset: the wiki's attacks-per-second plus {@code
     * 1.5} is the raw ATTACK_SPEED attribute value (spec §2.2 — ATTACK_SPEED base
     * 4.0 minus fist's 2.5 att/s). Matches the kernel {@code Ct8cTables}' own
     * private offset; a unit test pins {@link #attributeValue} against {@link
     * Ct8cTables#attackSpeed} so this constant can never drift from the table.
     */
    static final double ATTACK_SPEED_OFFSET = 1.5;

    private Ct8cWeapons() {}

    /** A classified held item: its CT8c weapon class and (for tier-sensitive classes) its tier. */
    public record Kind(WeaponClass weaponClass, Tier tier) {}

    /** The bare-hand / non-weapon cadence (2.5 att/s) — vanilla's own non-weapon fallback. */
    public static final Kind FIST = new Kind(WeaponClass.FIST, Tier.NONE);

    /**
     * Classifies a Bukkit {@code Material} name onto its CT8c weapon class and tier.
     * A {@code null}/{@code AIR}/non-weapon/out-of-table name resolves to {@link
     * #FIST} (spec §2.2 has no entry for it, so the bare-hand cadence applies).
     */
    public static Kind classify(String materialName) {
        if (materialName == null) {
            return FIST;
        }
        String name = materialName.toUpperCase();
        WeaponClass weaponClass = weaponClass(name);
        if (weaponClass == null) {
            return FIST;
        }
        return new Kind(weaponClass, tier(name, weaponClass));
    }

    /** The operator-tunable attacks-per-second for {@code kind} (spec §2.2; hoes are tier-sensitive). */
    public static double attacksPerSecond(Kind kind, WeaponSpeedSettings settings) {
        return switch (kind.weaponClass()) {
            case FIST -> settings.fist();
            case SWORD -> settings.sword();
            case AXE -> settings.axe();
            case PICKAXE -> settings.pickaxe();
            case SHOVEL -> settings.shovel();
            case TRIDENT -> settings.trident();
            case HOE -> hoeAttacksPerSecond(kind.tier(), settings.hoe());
        };
    }

    /**
     * The ATTACK_SPEED attribute value the {@code weapon-attack-speeds} module
     * writes for {@code kind}: the configured attacks-per-second plus the CT8c base
     * offset (spec §2.2). A sword on defaults reads {@code 4.5}, matching {@link
     * Ct8cTables#attackSpeed}.
     */
    public static double attributeValue(Kind kind, WeaponSpeedSettings settings) {
        return attacksPerSecond(kind, settings) + ATTACK_SPEED_OFFSET;
    }

    private static double hoeAttacksPerSecond(Tier tier, WeaponSpeedSettings.HoeSpeeds hoe) {
        return switch (tier) {
            case NONE, WOOD -> hoe.wood();
            case STONE -> hoe.stone();
            case IRON -> hoe.iron();
            case GOLD -> hoe.gold();
            case DIAMOND -> hoe.diamond();
            case NETHERITE -> hoe.netherite();
        };
    }

    private static WeaponClass weaponClass(String name) {
        if (name.endsWith("_SWORD")) {
            return WeaponClass.SWORD;
        }
        if (name.endsWith("_AXE")) {
            return WeaponClass.AXE;
        }
        if (name.endsWith("_PICKAXE")) {
            return WeaponClass.PICKAXE;
        }
        if (name.endsWith("_SHOVEL")) {
            return WeaponClass.SHOVEL;
        }
        if (name.endsWith("_HOE")) {
            return WeaponClass.HOE;
        }
        if (name.equals("TRIDENT")) {
            return WeaponClass.TRIDENT;
        }
        return null; // AIR, non-weapons, and out-of-table weapons (mace) → the bare-hand cadence
    }

    private static Tier tier(String name, WeaponClass weaponClass) {
        if (weaponClass == WeaponClass.TRIDENT) {
            return Tier.NONE; // the trident is tier-independent (flat +5, spec §2.2)
        }
        // Handle both the modern (WOODEN_/GOLDEN_) and pre-1.13 (WOOD_/GOLD_) prefixes.
        if (name.startsWith("WOODEN_") || name.startsWith("WOOD_")) {
            return Tier.WOOD;
        }
        if (name.startsWith("STONE_")) {
            return Tier.STONE;
        }
        if (name.startsWith("IRON_")) {
            return Tier.IRON;
        }
        if (name.startsWith("GOLDEN_") || name.startsWith("GOLD_")) {
            return Tier.GOLD;
        }
        if (name.startsWith("DIAMOND_")) {
            return Tier.DIAMOND;
        }
        if (name.startsWith("NETHERITE_")) {
            return Tier.NETHERITE;
        }
        return Tier.NONE;
    }
}

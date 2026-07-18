package me.vexmc.mental.kernel.math;

/**
 * The Combat Test 8c weapon tables — attack speed, attack delay, reach and
 * damage — as pure, version-blind math. Every constant is spec §2.2, which is
 * itself code-confirmed against the decompiled {@code 1_16_combat-6} client
 * ({@code WeaponType}, {@code Attributes}, {@code Tiers}); where the spreadsheet
 * and the binary disagreed the binary won (pickaxe 2.5 att/s, iron/diamond hoe
 * 3.0 damage — spec §1). Bukkit-free: the core shells map their platform enums
 * onto {@link WeaponClass}/{@link Tier} and read these values.
 *
 * <p><b>Damage composition (spec §2.2).</b> {@code base 2.0 + weapon bonus +
 * tier bonus}, one point lower than post-1.9 vanilla across the board. The tier
 * bonus is {@code WOOD/STONE/GOLD 0, IRON 1, DIAMOND 2, NETHERITE 3}. Two
 * weapons opt out: the hoe ignores that ladder for a flat {@code +1} on
 * iron/diamond and {@code +2} on netherite, and the trident is a flat {@code +5}
 * with no tier at all.</p>
 *
 * <p><b>Attack speed (spec §2.1/§2.2).</b> {@link #attackSpeed} returns the
 * ATTRIBUTE value (the wiki's "attacks per second" plus the {@code 1.5} the
 * delay formula subtracts back off), so a sword reads {@code 4.5}, not {@code
 * 3.0}. The hoe is the only weapon whose speed climbs with tier.</p>
 */
public final class Ct8cTables {

    /** The seven melee weapon classes CT8c tunes distinctly (spec §2.2 rows). */
    public enum WeaponClass {
        FIST,
        SWORD,
        AXE,
        PICKAXE,
        SHOVEL,
        HOE,
        TRIDENT
    }

    /** Material tiers; {@link #NONE} is the fist/trident (tier-independent) sentinel. */
    public enum Tier {
        NONE,
        WOOD,
        STONE,
        IRON,
        GOLD,
        DIAMOND,
        NETHERITE
    }

    /** The {@code −1.5} the wiki's att/s carries versus the raw ATTACK_SPEED attribute value. */
    private static final double ATTACK_SPEED_OFFSET = 1.5;

    private Ct8cTables() {}

    /**
     * The ATTACK_SPEED attribute value for a weapon — the wiki's att/s plus
     * {@code 1.5} (spec §2.2 parenthesised column). Only the hoe varies by tier.
     */
    public static double attackSpeed(WeaponClass weapon, Tier tier) {
        return attacksPerSecond(weapon, tier) + ATTACK_SPEED_OFFSET;
    }

    /** The wiki's "attacks per second" = {@code attackSpeed − 1.5} (spec §2.1). */
    private static double attacksPerSecond(WeaponClass weapon, Tier tier) {
        return switch (weapon) {
            case FIST, PICKAXE -> 2.5;
            case SWORD -> 3.0;
            case AXE, SHOVEL, TRIDENT -> 2.0;
            case HOE -> switch (tier) {
                case NONE, WOOD -> 2.0;
                case STONE -> 2.5;
                case IRON -> 3.0;
                case GOLD, DIAMOND, NETHERITE -> 3.5;
            };
        };
    }

    /**
     * The full-charge delay in ticks: {@code (int)(20 / clamp(attackSpeed − 1.5,
     * 0.1, 1024) + 0.5)} (spec §2.1's {@code getAttackDelay}). Feeding it {@link
     * #attackSpeed} reproduces spec §2.2's delay column exactly.
     */
    public static int attackDelayTicks(double attackSpeed) {
        double attacksPerSecond = Math.max(0.1, Math.min(1024.0, attackSpeed - ATTACK_SPEED_OFFSET));
        return (int) (20.0 / attacksPerSecond + 0.5);
    }

    /** The ATTACK_REACH block distance (spec §2.2): sword 3.0, hoe/trident 3.5, else 2.5. */
    public static double reach(WeaponClass weapon) {
        return switch (weapon) {
            case SWORD -> 3.0;
            case HOE, TRIDENT -> 3.5;
            case FIST, AXE, PICKAXE, SHOVEL -> 2.5;
        };
    }

    /**
     * The total melee damage (spec §2.2): player base {@code 2.0} + the
     * weapon-type bonus + the tier bonus. Fist is a bare {@code 2.0}; the trident
     * is a flat {@code +5.0} with no tier; the hoe swaps the tier ladder for its
     * flat iron/diamond/netherite additions.
     */
    public static double damage(WeaponClass weapon, Tier tier) {
        if (weapon == WeaponClass.FIST) {
            return 2.0;
        }
        if (weapon == WeaponClass.TRIDENT) {
            return 2.0 + 5.0; // flat +5, tier-independent
        }
        double tierBonus = weapon == WeaponClass.HOE ? hoeTierBonus(tier) : standardTierBonus(tier);
        return 2.0 + weaponBonus(weapon) + tierBonus;
    }

    /** The weapon-type addition to the player's base 2.0 (derived from spec §2.2's table). */
    private static double weaponBonus(WeaponClass weapon) {
        return switch (weapon) {
            case SWORD -> 2.0;
            case AXE -> 3.0;
            case PICKAXE -> 1.0;
            case SHOVEL, HOE -> 0.0;
            case FIST, TRIDENT -> 0.0; // handled before this call — kept for exhaustiveness
        };
    }

    /** The tier ladder: {@code IRON 1, DIAMOND 2, NETHERITE 3}, everything else 0 (spec §2.2). */
    private static double standardTierBonus(Tier tier) {
        return switch (tier) {
            case IRON -> 1.0;
            case DIAMOND -> 2.0;
            case NETHERITE -> 3.0;
            case NONE, WOOD, STONE, GOLD -> 0.0;
        };
    }

    /** The hoe's flat tier additions: {@code +1} iron/diamond, {@code +2} netherite (spec §2.2). */
    private static double hoeTierBonus(Tier tier) {
        return switch (tier) {
            case IRON, DIAMOND -> 1.0;
            case NETHERITE -> 2.0;
            case NONE, WOOD, STONE, GOLD -> 0.0;
        };
    }
}

package me.vexmc.mental.v5.feature;

/**
 * The dashboard grouping a feature belongs to — the navigation axis the GUI
 * and the reconciler's status reporting organise by (spec §7).
 *
 * <p>Each constant carries its own dashboard-section display metadata (title,
 * one-line blurb, icon material name), so the GUI's per-section navigation tile
 * reads straight off the descriptor — there is no separate string-keyed section
 * catalog (Task 6.0). The icon names resolve through the platform layer's
 * name-probe resolver, so an unknown constant on a future server degrades to a
 * staple rather than failing.</p>
 */
public enum Family {

    DELIVERY("Hit Delivery", "COMPARATOR",
            "The async hit pipeline — registration and packet-order w-tap reads."),
    KNOCKBACK("Knockback", "PISTON",
            "The server-wide knockback feel, plus the melee and projectile sources."),
    DAMAGE("Damage", "IRON_CHESTPLATE",
            "Era armour, durability, critical hits, and sword blocking."),
    CADENCE("Combat Cadence", "NETHERITE_SWORD",
            "The 1.9 attack cooldown, sweep attack, and swing sounds."),
    SUSTAIN("Sustain", "GOLDEN_APPLE",
            "Era potions and golden apples, ender-pearl spam, and natural regen."),
    LOADOUT("Loadout", "SHIELD",
            "Off-hand blocking, crafting restrictions, and era melee reach."),
    COMBO("Combo Hold", "TRIPWIRE_HOOK",
            "The pocket servo — hold a sweet-spot combo by shaping the fresh knock.");

    private final String displayName;
    private final String iconName;
    private final String blurb;

    Family(String displayName, String iconName, String blurb) {
        this.displayName = displayName;
        this.iconName = iconName;
        this.blurb = blurb;
    }

    /** The dashboard section title. */
    public String displayName() {
        return displayName;
    }

    /** The section navigation tile's icon (a platform-resolved material name). */
    public String iconName() {
        return iconName;
    }

    /** The one-line "why" shown under the section title. */
    public String blurb() {
        return blurb;
    }
}

package me.vexmc.mental.v5.gui;

import java.util.List;
import me.vexmc.mental.v5.feature.Family;
import org.jetbrains.annotations.NotNull;

/**
 * The home screen's five navigation categories — a pure presentation grouping
 * over {@link Family} (the descriptor axis stays untouched; a category owns no
 * feature state and no config). The home renders exactly these five tiles;
 * each opens a {@link CategoryMenu} listing its families, which open the
 * unchanged {@link FamilyMenu} screens.
 *
 * <p>{@link #SYSTEM} is the one non-family category: it fronts the always-on
 * compatibility and debug screens plus the reload action, which previously sat
 * loose on the home. Its {@link #families()} list is empty by design —
 * {@link CategoryMenu} renders its three system tiles instead.</p>
 *
 * <p>Reachability: {@code DashboardModelTest} pins that every {@link Family}
 * appears in exactly one category and every category on exactly one home row,
 * so a new family or category can never be silently unreachable from the home.</p>
 */
public enum Category {

    ENGINE("Combat Engine", "PISTON",
            "How every hit lands — knockback feel, the async delivery pipeline, and the combo solver.",
            List.of(Family.KNOCKBACK, Family.DELIVERY, Family.COMBO)),
    RULES("Era Rules", "IRON_CHESTPLATE",
            "The 1.7/1.8 rulebook — era damage, attack cadence, and loadout rules.",
            List.of(Family.DAMAGE, Family.CADENCE, Family.LOADOUT)),
    SUSTAIN("Sustain & Potions", "GOLDEN_APPLE",
            "Health and consumables — golden apples, regen, pearls, and splash potions.",
            List.of(Family.SUSTAIN, Family.POTS)),
    FEEDBACK("Effects & Loot", "NOTE_BLOCK",
            "The read on every hit — sounds, particles, indicators, and killer loot protection.",
            List.of(Family.FEEDBACK, Family.LOOT)),
    SYSTEM("Server & Diagnostics", "COMPASS",
            "Anticheat posture, debug channels, and the configuration reload.",
            List.of());

    private final String displayName;
    private final String iconName;
    private final String blurb;
    private final List<Family> families;

    Category(String displayName, String iconName, String blurb, List<Family> families) {
        this.displayName = displayName;
        this.iconName = iconName;
        this.blurb = blurb;
        this.families = families;
    }

    /** The home tile title. */
    public @NotNull String displayName() {
        return displayName;
    }

    /** The home tile's icon (a platform-resolved material name). */
    public @NotNull String iconName() {
        return iconName;
    }

    /** The one-line "why" shown under the tile title. */
    public @NotNull String blurb() {
        return blurb;
    }

    /** The families this category fronts — empty ONLY for {@link #SYSTEM}. */
    public @NotNull List<Family> families() {
        return families;
    }

    /**
     * The category that fronts {@code family} — the back-navigation anchor for a
     * {@link FamilyMenu}. Total by the {@code DashboardModelTest} partition pin
     * (every family in exactly one category), so the throw is unreachable while
     * the build is green.
     */
    public static @NotNull Category of(@NotNull Family family) {
        for (Category category : values()) {
            if (category.families.contains(family)) {
                return category;
            }
        }
        throw new IllegalStateException(family + " belongs to no home category");
    }
}

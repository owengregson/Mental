package me.vexmc.mental.platform;

/**
 * The sixteen stained-glass-pane colours as a version-blind vocabulary. Modern
 * servers (1.13+) carry one Material per colour; pre-flattening servers carry a
 * single STAINED_GLASS_PANE whose colour is the item's data value. Each
 * constant owns both spellings so the resolver (MenuMaterials.pane) can pick
 * the era-correct construction — the same one-seam philosophy as
 * LegacyMaterialNames, applied to menu chrome.
 */
public enum PaneColor {
    WHITE("WHITE_STAINED_GLASS_PANE", (short) 0),
    ORANGE("ORANGE_STAINED_GLASS_PANE", (short) 1),
    MAGENTA("MAGENTA_STAINED_GLASS_PANE", (short) 2),
    LIGHT_BLUE("LIGHT_BLUE_STAINED_GLASS_PANE", (short) 3),
    YELLOW("YELLOW_STAINED_GLASS_PANE", (short) 4),
    LIME("LIME_STAINED_GLASS_PANE", (short) 5),
    PINK("PINK_STAINED_GLASS_PANE", (short) 6),
    GRAY("GRAY_STAINED_GLASS_PANE", (short) 7),
    LIGHT_GRAY("LIGHT_GRAY_STAINED_GLASS_PANE", (short) 8),
    CYAN("CYAN_STAINED_GLASS_PANE", (short) 9),
    PURPLE("PURPLE_STAINED_GLASS_PANE", (short) 10),
    BLUE("BLUE_STAINED_GLASS_PANE", (short) 11),
    BROWN("BROWN_STAINED_GLASS_PANE", (short) 12),
    GREEN("GREEN_STAINED_GLASS_PANE", (short) 13),
    RED("RED_STAINED_GLASS_PANE", (short) 14),
    BLACK("BLACK_STAINED_GLASS_PANE", (short) 15);

    private final String modernName;
    private final short legacyData;

    PaneColor(String modernName, short legacyData) {
        this.modernName = modernName;
        this.legacyData = legacyData;
    }

    /** The post-flattening per-colour material name (1.13+). */
    public String modernName() {
        return modernName;
    }

    /** The pre-1.13 STAINED_GLASS_PANE data value for this colour. */
    public short legacyData() {
        return legacyData;
    }
}

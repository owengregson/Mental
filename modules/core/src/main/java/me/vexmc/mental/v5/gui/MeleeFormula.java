package me.vexmc.mental.v5.gui;

import me.vexmc.mental.kernel.profile.KnockbackProfile;

/**
 * The melee knockback FORMULA a profile uses — the category the GUI groups
 * profiles by (mirroring the on-disk {@code profiles/legacy/} and
 * {@code profiles/modern/} folders). It is not a config field of its own: it is
 * read straight off {@link KnockbackProfile#modern()}, so a profile always
 * appears under the formula it actually computes.
 *
 * <p>The display-metadata shape mirrors {@link me.vexmc.mental.v5.feature.Family}
 * (title, icon material, one-line blurb); the icon names resolve through the
 * platform layer's name-probe, so an absent material degrades to a staple.</p>
 */
public enum MeleeFormula {

    LEGACY("Legacy (1.7 / 1.8)", "STONE_SWORD",
            "The 1.7/1.8 knockback math — the fork presets and Mental's own tunings.", false),
    MODERN("Modern (26.1.2)", "NETHERITE_SWORD",
            "The byte-exact Paper 26.1.2 melee formula — the modern server feel.", true);

    private final String displayName;
    private final String iconName;
    private final String blurb;
    private final boolean modern;

    MeleeFormula(String displayName, String iconName, String blurb, boolean modern) {
        this.displayName = displayName;
        this.iconName = iconName;
        this.blurb = blurb;
        this.modern = modern;
    }

    /** The formula {@code profile} computes with — {@link #MODERN} when it opts in, else {@link #LEGACY}. */
    public static MeleeFormula of(KnockbackProfile profile) {
        return profile != null && profile.modern().enabled() ? MODERN : LEGACY;
    }

    /** Whether {@code profile} belongs to this formula category (the ProfileMenu filter). */
    public boolean matches(KnockbackProfile profile) {
        return profile != null && profile.modern().enabled() == modern;
    }

    public String displayName() {
        return displayName;
    }

    /** The chooser tile's icon (a platform-resolved material name). */
    public String iconName() {
        return iconName;
    }

    public String blurb() {
        return blurb;
    }
}

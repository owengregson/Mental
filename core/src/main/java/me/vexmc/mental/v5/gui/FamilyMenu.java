package me.vexmc.mental.v5.gui;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * One dashboard {@link Family} section: an on/off toggle for every
 * non-infrastructure {@link Feature} in the family, its list and copy read
 * straight from the descriptors via {@link DashboardModel#entries(Family)} and
 * the feature's own display metadata (no hand-authored catalog). Each toggle
 * flips the feature's {@code modules.*} machine-overlay key through {@link
 * me.vexmc.mental.v5.manage.Management#setModuleEnabled} and reloads live; the
 * screen repaints from the reconciler's new active state.
 *
 * <p>The {@link Family#KNOCKBACK} section additionally carries the server-wide
 * profile picker — the "knockback screen" — built from {@code
 * Snapshot.profileNames()/defaultProfile()} and applied through {@code
 * Management.setGlobalProfile}. Selecting a profile sets it for every player at
 * once (Mental's knockback is global; there is no per-player assignment).</p>
 */
public final class FamilyMenu extends Menu {

    /** Themed icon per shipped preset; unknown (custom) profiles fall back to paper. */
    private static final Map<String, String> PROFILE_ICONS = Map.of(
            "legacy-1.7", "STONE_SWORD",
            "legacy-1.8", "IRON_SWORD",
            "kohi", "DIAMOND_SWORD",
            "minehq", "GOLDEN_SWORD",
            "badlion", "IRON_AXE",
            "velt", "DIAMOND_AXE",
            "mmc", "BOW",
            "lunar", "ENDER_EYE",
            "signature", "NETHER_STAR",
            "custom", "WRITABLE_BOOK");

    /** The knockback screen's two profile rows (rows 2–3 of a six-row inventory). */
    private static final int[] PROFILE_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    /** The knockback screen's feature-toggle row (row 1). */
    private static final int TOGGLE_ROW_BASE = 9;

    /** A plain family screen's centred toggle row (row 2). */
    private static final int PLAIN_ROW_BASE = 18;

    private final Family family;

    public FamilyMenu(@NotNull MenuContext ctx, @NotNull Family family) {
        super(ctx);
        this.family = family;
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · " + family.displayName(), Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        // The knockback screen carries the profile picker too, so it needs the
        // full six rows; every other family is a compact toggle screen.
        return family == Family.KNOCKBACK ? 6 : 4;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        Icon header = Buttons.title(family.iconName(), family.displayName());
        Buttons.wrap(family.blurb()).forEach(line -> header.lore(line, Brand.MUTED));
        set(4, header.build());

        List<Feature> entries = DashboardModel.entries(family);
        if (family == Family.KNOCKBACK) {
            drawToggles(viewer, entries, TOGGLE_ROW_BASE);
            drawProfiles(viewer);
            set(49, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
        } else {
            drawToggles(viewer, entries, PLAIN_ROW_BASE);
            set(31, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
        }
    }

    /** Lays the family's feature toggles out centred within the nine-wide row at {@code rowBase}. */
    private void drawToggles(@NotNull Player viewer, @NotNull List<Feature> entries, int rowBase) {
        int count = entries.size();
        int start = rowBase + Math.max(0, (9 - count) / 2);
        for (int i = 0; i < count && start + i <= rowBase + 8; i++) {
            Feature feature = entries.get(i);
            boolean enabled = ctx.plugin().featureActive(feature);
            set(start + i,
                    Buttons.toggle(feature.iconName(), feature.displayName(), enabled, feature.blurb()),
                    click -> apply(viewer, () -> ctx.management().setModuleEnabled(feature, !enabled)));
        }
    }

    /** The server-wide profile picker — the knockback screen's defining half. */
    private void drawProfiles(@NotNull Player viewer) {
        String active = ctx.plugin().snapshot().defaultProfile();
        List<String> names = ctx.plugin().snapshot().profileNames().stream().sorted().toList();
        for (int i = 0; i < names.size() && i < PROFILE_SLOTS.length; i++) {
            String name = names.get(i);
            KnockbackProfile profile = ctx.plugin().snapshot().profile(name);
            boolean isActive = name.equals(active);
            set(PROFILE_SLOTS[i], profileTile(profile, name, isActive),
                    click -> apply(viewer, () -> ctx.management().setGlobalProfile(name)));
        }
    }

    private @NotNull ItemStack profileTile(KnockbackProfile profile, @NotNull String name, boolean active) {
        String material = PROFILE_ICONS.getOrDefault(name, "PAPER");
        Icon icon = Buttons.title(material,
                profile != null ? profile.displayName() : name,
                active ? Brand.SUCCESS : Brand.PRIMARY);
        icon.lore(Component.text("(" + name + ")", Brand.MUTED));
        if (profile != null) {
            if (!profile.description().isEmpty()) {
                icon.blank();
                Buttons.wrap(profile.description()).forEach(line -> icon.lore(line, Brand.MUTED));
            }
            icon.blank();
            icon.lore(kv("Base h/v",
                    round(profile.base().horizontal()) + " / " + round(profile.base().vertical())));
            icon.lore(kv("Vertical", profile.verticalMode().name().toLowerCase(Locale.ROOT)));
            icon.lore(kv("Delivery", profile.meleeDelivery().name().toLowerCase(Locale.ROOT).replace('_', '-')));
            icon.lore(kv("Combos", profile.combos() ? "yes" : "no"));
            icon.lore(kv("Sprint x", round(profile.sprintFactor())));
            icon.lore(kv("Resistance", profile.resistance().name().toLowerCase(Locale.ROOT)));
        }
        icon.blank();
        icon.lore(active
                ? Component.text("● ACTIVE — server-wide", Brand.SUCCESS).decoration(TextDecoration.BOLD, true)
                : Component.text("▸ Click to apply server-wide", Brand.SECONDARY));
        return icon.glow(active).build();
    }

    private static @NotNull Component kv(@NotNull String label, @NotNull String value) {
        return Component.text()
                .append(Component.text(label + ": ", Brand.MUTED))
                .append(Component.text(value, Brand.ACCENT))
                .build();
    }

    private static @NotNull String round(double value) {
        return String.valueOf(Math.round(value * 1000.0) / 1000.0);
    }
}

package me.vexmc.mental.v5.gui;

import java.util.List;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
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
 * <p>The {@link Family#KNOCKBACK} section additionally carries a nav tile to the
 * "Melee Knockback Formula" chooser ({@link KnockbackFormulaMenu}) — the era
 * formula, then a preset — which is where the server-wide profile is actually
 * selected. The FEEDBACK and LOOT families are reached from the home through
 * their own dedicated screens ({@link EffectsMenu} / {@link LootProtectionMenu}),
 * not this generic toggle screen.</p>
 */
public final class FamilyMenu extends Menu {

    /** A preset-carrying family screen's feature-toggle row (row 1). */
    private static final int TOGGLE_ROW_BASE = 9;

    /** A plain family screen's centred toggle row (row 2). */
    private static final int PLAIN_ROW_BASE = 18;

    /** A preset-carrying family screen's nav tile to its chooser (row 2, centred). */
    private static final int FORMULA_SLOT = 22;

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
        // The knockback and effects screens carry a preset nav tile and their back
        // at slot 49, so they need the full six rows; every other family is a
        // compact toggle screen.
        return family == Family.KNOCKBACK || family == Family.FEEDBACK ? 6 : 4;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        Icon header = Buttons.title(family.iconName(), family.displayName());
        Buttons.wrap(family.blurb()).forEach(line -> header.lore(line, Brand.MUTED));
        set(4, header.build());

        List<Feature> entries = DashboardModel.entries(family);
        if (family == Family.KNOCKBACK) {
            drawToggles(viewer, entries, TOGGLE_ROW_BASE);
            set(FORMULA_SLOT, Buttons.nav("PISTON", "Melee Knockback Formula",
                    "Choose the era formula, then a preset."),
                    click -> navigate(viewer, new KnockbackFormulaMenu(ctx)));
            set(49, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
        } else {
            drawToggles(viewer, entries, PLAIN_ROW_BASE);
            set(31, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
        }
    }

    /**
     * Lays the family's feature toggles out balanced within the nine-wide row at
     * {@code rowBase}. A small set is spread with a one-slot gap between tiles and
     * centred (so two toggles sit symmetric around the centre column rather than
     * cramped together); a set too wide to breathe falls back to a centred
     * contiguous run. See {@link #spreadColumns(int)}.
     */
    private void drawToggles(@NotNull Player viewer, @NotNull List<Feature> entries, int rowBase) {
        int[] columns = spreadColumns(entries.size());
        for (int i = 0; i < entries.size() && i < columns.length; i++) {
            Feature feature = entries.get(i);
            boolean enabled = ctx.plugin().featureActive(feature);
            set(rowBase + columns[i],
                    Buttons.toggle(feature.iconName(), feature.displayName(), enabled, feature.blurb()),
                    click -> apply(viewer, () -> ctx.management().setModuleEnabled(feature, !enabled)));
        }
    }

    /**
     * The centred columns (0–8) for {@code count} tiles in a nine-wide row. Up to
     * five tiles get a one-slot gap between them (width {@code 2*count-1}), centred;
     * a larger set packs contiguously, centred, and is capped at the row width.
     */
    static int[] spreadColumns(int count) {
        if (count <= 0) {
            return new int[0];
        }
        int capped = Math.min(count, 9);
        int spread = 2 * capped - 1;
        boolean gapped = spread <= 9;
        int width = gapped ? spread : capped;
        int startCol = (9 - width) / 2;
        int[] columns = new int[capped];
        for (int i = 0; i < capped; i++) {
            columns[i] = startCol + (gapped ? i * 2 : i);
        }
        return columns;
    }
}

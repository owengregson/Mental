package me.vexmc.mental.v5.gui;

import java.util.List;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The intermediate "Melee Knockback Formula" chooser between the Knockback
 * screen ({@link FamilyMenu} for {@link Family#KNOCKBACK}) and the preset list
 * ({@link ProfileMenu}). Two nav tiles — Legacy and Modern — each open the
 * preset list filtered to that formula; the tile whose category holds the
 * ACTIVE server-wide profile glows and shows it. Selecting a formula does
 * nothing on its own (formula is a per-profile property) — it just narrows the
 * list; the actual switch happens when a preset is clicked in {@link
 * ProfileMenu}.
 *
 * <p>There is no back stack, so Back is hardcoded to the Knockback family
 * screen (which still carries the KNOCKBACK feature toggles).</p>
 */
public final class KnockbackFormulaMenu extends Menu {

    private static final int HEADER_SLOT = 4;
    private static final int LEGACY_SLOT = 20;
    private static final int MODERN_SLOT = 24;
    private static final int BACK_SLOT = 31;

    public KnockbackFormulaMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · Melee Knockback Formula", Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        set(HEADER_SLOT, headerIcon());
        MeleeFormula active = activeFormula();
        set(LEGACY_SLOT, formulaTile(MeleeFormula.LEGACY, active),
                click -> navigate(viewer, new ProfileMenu(ctx, MeleeFormula.LEGACY)));
        set(MODERN_SLOT, formulaTile(MeleeFormula.MODERN, active),
                click -> navigate(viewer, new ProfileMenu(ctx, MeleeFormula.MODERN)));
        set(BACK_SLOT, Buttons.back(), click -> navigate(viewer, new FamilyMenu(ctx, Family.KNOCKBACK)));
    }

    /** The formula the current server-wide default profile computes with. */
    private @NotNull MeleeFormula activeFormula() {
        String name = ctx.plugin().snapshot().defaultProfile();
        return MeleeFormula.of(ctx.plugin().snapshot().profile(name));
    }

    private @NotNull ItemStack headerIcon() {
        Icon header = Buttons.title("PISTON", "Melee Knockback Formula");
        Buttons.wrap("Pick the era formula, then a preset. Each profile keeps its own"
                + " formula — this only narrows the list.").forEach(line -> header.lore(line, Brand.MUTED));
        return header.build();
    }

    private @NotNull ItemStack formulaTile(@NotNull MeleeFormula formula, @NotNull MeleeFormula active) {
        boolean isActive = formula == active;
        Icon icon = Buttons.title(formula.iconName(), formula.displayName(),
                isActive ? Brand.SUCCESS : Brand.PRIMARY);
        Buttons.wrap(formula.blurb()).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        if (isActive) {
            KnockbackProfile profile = ctx.plugin().snapshot().profile(ctx.plugin().snapshot().defaultProfile());
            String label = profile != null ? profile.displayName() : ctx.plugin().snapshot().defaultProfile();
            icon.lore(Component.text("● ACTIVE — " + label, Brand.SUCCESS).decoration(TextDecoration.BOLD, true));
        } else {
            icon.lore(Component.text("▸ Open", Brand.SECONDARY));
        }
        return icon.glow(isActive).build();
    }

    /**
     * Boot self-test seam (mirrors {@link DashboardMenu#selfTestIcons}): the
     * load-bearing icons rendered to {@link ItemStack}s with no viewer, so the
     * tester can prove the Adventure/String sink path classloads on legacy
     * servers. Returns only Bukkit types.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        MeleeFormula active = activeFormula();
        return List.of(headerIcon(), formulaTile(MeleeFormula.LEGACY, active),
                formulaTile(MeleeFormula.MODERN, active), Buttons.back());
    }
}

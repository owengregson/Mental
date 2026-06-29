package me.vexmc.mental.gui.menu;

import java.util.List;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.engine.ModuleRegistry;
import me.vexmc.mental.gui.Menu;
import me.vexmc.mental.gui.MenuContext;
import me.vexmc.mental.text.Brand;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A category of module on/off toggles (Hit Registration, Combat Rules, Damage,
 * Potions &amp; Food, Player). Each toggle flips {@code modules.<id>} in
 * config.yml and reloads live; the screen repaints from the new state. The
 * module list and copy come from {@link Catalog}.
 */
public final class ModuleCategoryMenu extends Menu {

    private final Catalog.Category category;

    public ModuleCategoryMenu(@NotNull MenuContext ctx, @NotNull Catalog.Category category) {
        super(ctx);
        this.category = category;
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · " + category.title(), Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        ModuleRegistry registry = ctx.services().plugin().modules();

        var header = Buttons.title(category.navMaterial(), category.title());
        Buttons.wrap(category.navBlurb()).forEach(line -> header.lore(line, Brand.MUTED));
        set(4, header.build());

        List<String> ids = category.moduleIds();
        int count = ids.size();
        int start = 18 + Math.max(0, (9 - count) / 2);
        for (int i = 0; i < count && start + i <= 26; i++) {
            String id = ids.get(i);
            CombatModule module = registry.byId(id).orElse(null);
            Catalog.Glyph glyph = Catalog.glyph(id);
            boolean enabled = module != null && module.active();
            String name = module != null ? module.displayName() : id;
            set(start + i, Buttons.toggle(glyph.material(), name, enabled, glyph.blurb()),
                    click -> apply(viewer, () -> ctx.management().setModuleEnabled(id, !enabled)));
        }

        set(31, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
    }
}

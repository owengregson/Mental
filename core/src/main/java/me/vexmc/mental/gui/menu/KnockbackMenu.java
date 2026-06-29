package me.vexmc.mental.gui.menu;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.vexmc.mental.config.KnockbackProfile;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.engine.ModuleRegistry;
import me.vexmc.mental.gui.Icon;
import me.vexmc.mental.gui.Menu;
import me.vexmc.mental.gui.MenuContext;
import me.vexmc.mental.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The knockback screen: the engine master switch, the source toggles (fishing,
 * projectile, rod), and the profile picker.
 *
 * <p>Selecting a profile sets it <em>server-wide</em> — Mental's knockback is
 * global, so a pick here writes {@code knockback.profile} in knockback.yml,
 * reloads, and becomes the active feel for every player at once (there is no
 * per-player assignment). The currently-active profile glows and is marked
 * ACTIVE; each tile's lore previews the profile's defining values.</p>
 */
public final class KnockbackMenu extends Menu {

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
            "custom", "WRITABLE_BOOK");

    private static final int[] PROFILE_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    public KnockbackMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · Knockback", Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        ModuleRegistry registry = ctx.services().plugin().modules();

        engineToggle(viewer, registry, "knockback", 4);

        String active = ctx.services().knockbackProfiles().defaultProfileName();
        Map<String, KnockbackProfile> profiles = ctx.services().config().knockback().profiles();
        List<String> names = profiles.keySet().stream().sorted().toList();
        for (int i = 0; i < names.size() && i < PROFILE_SLOTS.length; i++) {
            String name = names.get(i);
            KnockbackProfile profile = profiles.get(name);
            boolean isActive = name.equals(active);
            set(PROFILE_SLOTS[i], profileTile(profile, name, isActive),
                    click -> apply(viewer, () -> ctx.management().setGlobalProfile(name)));
        }

        // Source toggles along the bottom.
        engineToggle(viewer, registry, "fishing-knockback", 47);
        engineToggle(viewer, registry, "projectile-knockback", 49);
        engineToggle(viewer, registry, "rod-velocity", 51);

        set(45, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
    }

    private void engineToggle(
            @NotNull Player viewer, @NotNull ModuleRegistry registry, @NotNull String id, int slot) {
        CombatModule module = registry.byId(id).orElse(null);
        Catalog.Glyph glyph = Catalog.glyph(id);
        boolean enabled = module != null && module.active();
        String name = module != null ? module.displayName() : id;
        set(slot, Buttons.toggle(glyph.material(), name, enabled, glyph.blurb()),
                click -> apply(viewer, () -> ctx.management().setModuleEnabled(id, !enabled)));
    }

    private @NotNull org.bukkit.inventory.ItemStack profileTile(
            KnockbackProfile profile, @NotNull String name, boolean active) {
        String material = PROFILE_ICONS.getOrDefault(name, "PAPER");
        Icon icon = Icon.of(me.vexmc.mental.gui.Materials.of(material))
                .name(Component.text(profile != null ? profile.displayName() : name,
                        active ? Brand.SUCCESS : Brand.PRIMARY).decoration(TextDecoration.BOLD, true));
        icon.lore(Component.text("(" + name + ")", Brand.MUTED));
        if (profile != null) {
            if (!profile.description().isEmpty()) {
                icon.blank();
                Buttons.wrap(profile.description()).forEach(line -> icon.lore(line, Brand.MUTED));
            }
            icon.blank();
            icon.lore(kv("Base h/v", round(profile.base().horizontal()) + " / " + round(profile.base().vertical())));
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

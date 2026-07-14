package me.vexmc.mental.v5.gui;

import java.util.List;
import java.util.function.Supplier;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The Combat Effects hub — the home the FEEDBACK family tile now opens instead
 * of the old crammed toggle row. Each of the three cosmetic modules (Hit
 * Effects, Death Effects, Damage Indicators) gets its own dedicated screen with
 * in-GUI value editing; this hub shows each module's enabled state and opens it,
 * plus a nav to the whole-tune preset picker.
 */
public final class EffectsMenu extends Menu {

    /** The three module tiles ride the centred row 2; the preset nav sits below. */
    private static final int MODULE_ROW_BASE = 18;
    private static final int PRESET_SLOT = 31;

    public EffectsMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · Combat Effects", Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        Icon header = Buttons.title("NOTE_BLOCK", "Combat Effects");
        Buttons.wrap("Hit sounds, pop-off damage numbers, and death effects — each on its own"
                + " screen with in-game editing. Or apply a ready-made preset below.")
                .forEach(line -> header.lore(line, Brand.MUTED));
        set(4, header.build());

        placeCentered(MODULE_ROW_BASE, List.of(
                moduleTile(viewer, Feature.HIT_FEEDBACK, "NOTE_BLOCK", () -> new HitEffectsMenu(ctx)),
                moduleTile(viewer, Feature.DEATH_EFFECTS, "FIREWORK_ROCKET", () -> new DeathEffectsMenu(ctx)),
                moduleTile(viewer, Feature.DAMAGE_INDICATORS, "ARMOR_STAND",
                        () -> new DamageIndicatorsMenu(ctx))));

        set(PRESET_SLOT, Buttons.nav("JUKEBOX", "Combat Effects Preset",
                "Apply a whole ready-made tune — hits, indicators, and deaths at once."),
                click -> navigate(viewer, new EffectsPresetMenu(ctx)));

        set(49, Buttons.back(), click -> navigate(viewer, new DashboardMenu(ctx)));
    }

    private @NotNull Tile moduleTile(
            @NotNull Player viewer, @NotNull Feature feature, @NotNull String icon,
            @NotNull Supplier<Menu> destination) {
        boolean enabled = ctx.plugin().featureActive(feature);
        Icon tile = Buttons.title(icon, feature.displayName(), enabled ? Brand.SUCCESS : Brand.PRIMARY);
        Buttons.wrap(feature.blurb()).forEach(line -> tile.lore(line, Brand.MUTED));
        tile.blank();
        tile.lore(Component.text(enabled ? "● ENABLED" : "○ DISABLED",
                enabled ? Brand.SUCCESS : Brand.FAILURE).decoration(TextDecoration.BOLD, true));
        tile.lore(Component.text("▸ Open to toggle and configure", Brand.SECONDARY));
        return Tile.of(tile.glow(enabled).build(), click -> navigate(viewer, destination.get()));
    }
}

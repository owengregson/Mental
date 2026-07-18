package me.vexmc.mental.v5.gui;

import java.util.List;
import java.util.Locale;
import me.vexmc.mental.v5.config.AnticheatMode;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Coexistence posture: how Mental behaves alongside movement-prediction
 * anticheats, as three radio tiles (one per {@link AnticheatMode}). It is not a
 * {@code modules.*} toggle — it is always-on infrastructure driven by a config
 * enum ({@code anticheat.mode}).
 *
 * <p>Selecting a tile writes its <em>machine-overlay</em> key and reloads through
 * {@link me.vexmc.mental.v5.manage.Management}; the human YAML is never
 * re-serialized. The overlay wins over the file, so the reload picks up the
 * change atomically.</p>
 */
public final class CompatibilityMenu extends Menu {

    private static final String ANTICHEAT_KEY = "anticheat.mode";

    private static final int HEADER_SLOT = 4;
    private static final int AUTO_SLOT = 11;
    private static final int FORCE_SAFE_SLOT = 13;
    private static final int OFF_SLOT = 15;
    private static final int BACK_SLOT = 22;

    private static final String AUTO_BLURB =
            "Detect GrimAC and Vulcan and hold pre-send back automatically — the shipped default.";
    private static final String FORCE_SAFE_BLURB =
            "Always hold pre-send back, anticheat or not — the most conservative posture.";
    private static final String OFF_BLURB =
            "Full fast path, no accommodation. For servers that trust their anticheat pairing.";

    public CompatibilityMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental", Brand.PRIMARY, TextDecoration.BOLD)
                .append(Component.text(" · ", Brand.MUTED))
                .append(Component.text("Compatibility", Palette.system().accent()));
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        paintChrome(Palette.system().pane());
        AnticheatMode mode = ctx.plugin().snapshot().anticheat().mode();

        set(HEADER_SLOT, headerCard(mode));
        set(AUTO_SLOT, radioTile("ENDER_EYE", "auto", AUTO_BLURB, mode == AnticheatMode.AUTO),
                click -> apply(viewer, () -> ctx.management().setOverlay(ANTICHEAT_KEY, "auto")));
        set(FORCE_SAFE_SLOT, radioTile("IRON_BARS", "force-safe", FORCE_SAFE_BLURB, mode == AnticheatMode.FORCE_SAFE),
                click -> apply(viewer, () -> ctx.management().setOverlay(ANTICHEAT_KEY, "force-safe")));
        set(OFF_SLOT, radioTile("BARRIER", "off", OFF_BLURB, mode == AnticheatMode.OFF),
                click -> apply(viewer, () -> ctx.management().setOverlay(ANTICHEAT_KEY, "off")));
        set(BACK_SLOT, Buttons.back("the Dashboard"), click -> navigate(viewer, new DashboardMenu(ctx)));
    }

    /** Boot self-test seam: header + three radios + back, as pure Bukkit stacks. */
    public @NotNull List<ItemStack> selfTestIcons() {
        AnticheatMode mode = ctx.plugin().snapshot().anticheat().mode();
        return List.of(
                headerCard(mode),
                radioTile("ENDER_EYE", "auto", AUTO_BLURB, mode == AnticheatMode.AUTO),
                radioTile("IRON_BARS", "force-safe", FORCE_SAFE_BLURB, mode == AnticheatMode.FORCE_SAFE),
                radioTile("BARRIER", "off", OFF_BLURB, mode == AnticheatMode.OFF),
                Buttons.back("the Dashboard"));
    }

    private @NotNull ItemStack headerCard(@NotNull AnticheatMode mode) {
        Icon icon = Buttons.title("IRON_BARS", "Anticheat Coexistence", Brand.TEXT);
        Buttons.wrap("Mental's pre-send fast path predicts what an anticheat may dislike. Pick how"
                + " hard Mental yields when one is present.").forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(Buttons.kv("Mode", mode.name().toLowerCase(Locale.ROOT), Brand.TEXT));
        return icon.build();
    }

    private @NotNull ItemStack radioTile(
            @NotNull String materialName, @NotNull String label, @NotNull String blurb, boolean selected) {
        Icon icon = Buttons.title(materialName, label, selected ? Brand.SUCCESS : Brand.TEXT);
        Buttons.wrap(blurb).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        if (selected) {
            icon.lore(Component.text("● SELECTED", Brand.SUCCESS).decoration(TextDecoration.BOLD, true));
        } else {
            icon.lore(Component.text("▸ Click to select", Brand.SECONDARY));
        }
        return icon.glow(selected).build();
    }
}

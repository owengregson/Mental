package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.text.Brand;
import me.vexmc.mental.v5.text.TextPort;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The one settings screen: renders a {@link SettingsCatalog} page for its feature
 * — a master module card in the header, one tile per knob laid out by group row,
 * every write a single overlay key through {@code Management}, every tile carrying
 * the override flag and the Q-reset affordance. Adding a knob to the catalog is
 * the ONLY step to surface it here.
 *
 * <p>The click grammar (§2.4): left-click is the primary (toggle / +step / cycle
 * forward / start chat edit), right-click is the inverse (−step / cycle back),
 * shift multiplies a step by ten, and Q (the drop key) resets the knob to its
 * file value via {@code Management.clearOverlay}. Read-only POINTER/INFO tiles
 * carry no handler.</p>
 */
public final class SettingsMenu extends Menu {

    private final Feature feature;
    private final SettingsCatalog.Page page;
    private final Palette.Theme theme;

    public SettingsMenu(@NotNull MenuContext ctx, @NotNull Feature feature) {
        super(ctx);
        this.feature = feature;
        this.page = SettingsCatalog.pageFor(feature).orElseThrow(() ->
                new IllegalArgumentException(feature + " has no settings page"));
        this.theme = Palette.of(feature.family());
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental", Brand.PRIMARY, TextDecoration.BOLD)
                .append(Component.text(" · ", Brand.MUTED))
                .append(Component.text(feature.displayName(), theme.accent()));
    }

    @Override
    protected int rows() {
        return 2 + page.groups().size();
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        paintChrome(theme.pane());
        Snapshot snapshot = ctx.plugin().snapshot();

        boolean active = ctx.plugin().featureActive(feature);
        set(4, Buttons.moduleCard(feature.iconName(), feature.displayName(), theme.accent(),
                        active, feature.blurb(), null),
                click -> apply(viewer, () -> ctx.management().setModuleEnabled(feature, !active)));

        List<List<SettingsCatalog.Knob>> groups = page.groups();
        for (int i = 0; i < groups.size(); i++) {
            List<SettingsCatalog.Knob> group = groups.get(i);
            int[] slots = Layout.contentRow(9 * (i + 1), group.size());
            for (int j = 0; j < group.size(); j++) {
                SettingsCatalog.Knob knob = group.get(j);
                set(slots[j], renderTile(snapshot, knob), clickFor(viewer, knob));
            }
        }

        int backSlot = (rows() - 1) * 9 + 4;
        set(backSlot, Buttons.back(feature.family().displayName()),
                click -> navigate(viewer, new FamilyMenu(ctx, feature.family())));
    }

    /**
     * Boot self-test seam: the master card + every knob tile (read from the live
     * snapshot) + back, as pure Bukkit stacks with no scheduler hop and no viewer.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        Snapshot snapshot = ctx.plugin().snapshot();
        List<ItemStack> icons = new ArrayList<>();
        icons.add(Buttons.moduleCard(feature.iconName(), feature.displayName(), theme.accent(),
                ctx.plugin().featureActive(feature), feature.blurb(), null));
        for (List<SettingsCatalog.Knob> group : page.groups()) {
            for (SettingsCatalog.Knob knob : group) {
                icons.add(renderTile(snapshot, knob));
            }
        }
        icons.add(Buttons.back(feature.family().displayName()));
        return icons;
    }

    private @NotNull ItemStack renderTile(@NotNull Snapshot snapshot, @NotNull SettingsCatalog.Knob knob) {
        TextColor accent = theme.accent();
        boolean overridden = knob.key() != null && ctx.plugin().overlayHas(knob.key());
        return switch (knob.kind()) {
            case TOGGLE -> Buttons.toggle(knob.materialName(), knob.label(), accent,
                    (Boolean) knob.reader().apply(snapshot), knob.blurb(), overridden);
            case STEP_INT, STEP_DOUBLE -> Buttons.stepper(knob.materialName(), knob.label(), accent,
                    displayValue(knob, (Number) knob.reader().apply(snapshot)), knob.blurb(),
                    displayStep(knob), overridden);
            case CYCLE -> Buttons.cycle(knob.materialName(), knob.label(), accent,
                    knob.options(), (String) knob.reader().apply(snapshot), knob.blurb(), overridden);
            case TEXT -> Buttons.editText(knob.materialName(), knob.label(), accent,
                    String.valueOf(knob.reader().apply(snapshot)), knob.blurb(), overridden);
            case NUMBER -> Buttons.numberPrompt(knob.materialName(), knob.label(), accent,
                    displayValue(knob, (Number) knob.reader().apply(snapshot)), knob.blurb(), overridden);
            case POINTER -> Buttons.pointer(knob.materialName(), knob.label(), knob.blurb(),
                    knob.file().replace("<selected>", snapshot.selectedEffectsPreset()), knob.section());
            case INFO -> Buttons.info(knob.materialName(), knob.label(), Buttons.wrap(knob.blurb()));
        };
    }

    private @Nullable Consumer<InventoryClickEvent> clickFor(
            @NotNull Player viewer, @NotNull SettingsCatalog.Knob knob) {
        return switch (knob.kind()) {
            case POINTER, INFO -> null;
            default -> event -> handleKnob(viewer, knob, event);
        };
    }

    private void handleKnob(
            @NotNull Player viewer, @NotNull SettingsCatalog.Knob knob, @NotNull InventoryClickEvent event) {
        ClickType click = event.getClick();
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            if (ctx.plugin().overlayHas(knob.key())) {
                apply(viewer, () -> ctx.management().clearOverlay(knob.key()));
            }
            return;
        }
        Snapshot snapshot = ctx.plugin().snapshot();
        switch (knob.kind()) {
            case TOGGLE -> {
                boolean current = (Boolean) knob.reader().apply(snapshot);
                apply(viewer, () -> ctx.management().setOverlay(knob.key(), !current));
            }
            case STEP_INT, STEP_DOUBLE -> {
                double current = ((Number) knob.reader().apply(snapshot)).doubleValue();
                double delta = knob.step() * (event.isShiftClick() ? 10 : 1) * (event.isRightClick() ? -1 : 1);
                double next = Math.max(knob.min(), Math.min(knob.max(), current + delta));
                Object typed;
                if (knob.kind() == SettingsCatalog.Kind.STEP_INT) {
                    typed = (int) Math.round(next);
                } else {
                    typed = round4(next);
                }
                apply(viewer, () -> ctx.management().setOverlay(knob.key(), typed));
            }
            case CYCLE -> {
                List<String> options = knob.options();
                String current = (String) knob.reader().apply(snapshot);
                int index = options.indexOf(current);
                if (index < 0) {
                    index = 0;
                }
                int nextIndex = event.isRightClick()
                        ? (index - 1 + options.size()) % options.size()
                        : (index + 1) % options.size();
                String option = options.get(nextIndex);
                apply(viewer, () -> ctx.management().setOverlay(knob.key(), option));
            }
            case TEXT -> promptOverlay(viewer, knob.label(), knob.key());
            case NUMBER -> promptNumber(viewer, knob);
            default -> { }
        }
    }

    /**
     * A NUMBER knob edits through a chat prompt with its own parse-and-bounds
     * gate (§4.3): a non-numeric or out-of-range line writes NOTHING, tells the
     * player so, and reopens the screen; a valid line writes the 4-decimal-rounded
     * double.
     */
    private void promptNumber(@NotNull Player viewer, @NotNull SettingsCatalog.Knob knob) {
        ctx.chatPrompt().request(viewer, knob.label(),
                input -> {
                    double parsed;
                    try {
                        parsed = Double.parseDouble(input.trim());
                    } catch (NumberFormatException notANumber) {
                        rejectNumber(viewer, knob);
                        return;
                    }
                    if (parsed < knob.min() || parsed > knob.max()) {
                        rejectNumber(viewer, knob);
                        return;
                    }
                    ctx.management().setOverlay(knob.key(), round4(parsed));
                    open(viewer);
                },
                () -> open(viewer));
    }

    private void rejectNumber(@NotNull Player viewer, @NotNull SettingsCatalog.Knob knob) {
        TextPort.send(viewer, Brand.failure("That's not a number between "
                + Buttons.round(knob.min()) + " and " + Buttons.round(knob.max())
                + " — nothing changed."));
        open(viewer);
    }

    private static @NotNull String displayValue(@NotNull SettingsCatalog.Knob knob, @NotNull Number value) {
        String number = knob.kind() == SettingsCatalog.Kind.STEP_INT
                ? String.valueOf(value.intValue())
                : Buttons.round(value.doubleValue());
        return knob.unit().isEmpty() ? number : number + " " + knob.unit();
    }

    private static @NotNull String displayStep(@NotNull SettingsCatalog.Knob knob) {
        return knob.kind() == SettingsCatalog.Kind.STEP_INT
                ? String.valueOf((int) knob.step())
                : Buttons.round(knob.step());
    }

    /** 4-decimal overlay rounding (§4.3) — kills float drift in the overlay file. */
    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}

package me.vexmc.mental.v5.gui;

import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The dedicated Death Effects screen: the module toggle plus in-GUI editing of
 * the kill title / subtitle (chat prompt), the three title timings (steppers),
 * and the cosmetic lightning (cycle). Every edit writes an
 * {@code effects.death.<field>} machine-overlay key through Management, layered
 * over the selected preset — the sound and firework lists stay preset/YAML-only
 * this round and show as a read-only preview.
 */
public final class DeathEffectsMenu extends Menu {

    private static final String KEY = "effects.death.";
    private static final int FADE_MAX = 200;
    private static final int STAY_MAX = 400;

    public DeathEffectsMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · Death Effects", Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        DeathEffectsSettings settings = settings();
        DeathEffectsSettings.KillTitle title = settings.killTitle();

        Icon header = Buttons.title("FIREWORK_ROCKET", "Death Effects");
        Buttons.wrap("What plays when a player dies — plus the kill title flashed to the killer"
                + " of an enemy player. Tokens: {NAME}, {KILLER}, {PROTECT_SECONDS}.")
                .forEach(line -> header.lore(line, Brand.MUTED));
        set(4, header.build());

        boolean enabled = ctx.plugin().featureActive(Feature.DEATH_EFFECTS);
        set(19, Buttons.toggle("LEVER", "Death Effects module", enabled,
                "Master switch for this screen's effects."),
                click -> apply(viewer, () -> ctx.management().setModuleEnabled(Feature.DEATH_EFFECTS, !enabled)));

        set(21, Buttons.editText("NAME_TAG", "Kill title", title.title(),
                "The big line shown to the killer."),
                click -> promptOverlay(viewer, "Death kill title", KEY + "kill-title"));
        set(23, Buttons.editText("PAPER", "Kill subtitle", title.subtitle(),
                "The smaller line under the title."),
                click -> promptOverlay(viewer, "Death kill subtitle", KEY + "kill-subtitle"));
        set(25, Buttons.cycle("GLOWSTONE_DUST", "Cosmetic lightning", enabledLabel(settings.lightning()),
                "A packet-only lightning bolt at the death spot (1.19+)."),
                click -> apply(viewer, () -> ctx.management().setOverlay(KEY + "lightning", !settings.lightning())));

        set(30, Buttons.stepper("CLOCK", "Fade in", title.fadeIn() + " ticks", "Title fade-in time."),
                click -> step(viewer, click, KEY + "title-fade-in", title.fadeIn(), 0, FADE_MAX));
        set(31, Buttons.stepper("CLOCK", "Stay", title.stay() + " ticks", "How long the title holds."),
                click -> step(viewer, click, KEY + "title-stay", title.stay(), 0, STAY_MAX));
        set(32, Buttons.stepper("CLOCK", "Fade out", title.fadeOut() + " ticks", "Title fade-out time."),
                click -> step(viewer, click, KEY + "title-fade-out", title.fadeOut(), 0, FADE_MAX));

        set(40, previewTile(settings));

        set(49, Buttons.back(), click -> navigate(viewer, new EffectsMenu(ctx)));
    }

    /** The read-only slice — sound and firework lists stay preset/YAML-only this round. */
    private @NotNull ItemStack previewTile(@NotNull DeathEffectsSettings settings) {
        Icon icon = Buttons.title("JUKEBOX", "Sounds & firework", Brand.MUTED);
        icon.lore(kv("Death sounds", settings.sounds().isEmpty()
                ? "none" : settings.sounds().size() + " layered"));
        icon.lore(kv("Firework", settings.fireworkColors().isEmpty()
                ? "none" : settings.fireworkColors().size() + "-color blast"));
        icon.blank();
        Buttons.wrap("Edit sound and firework lists in the preset file (effects/presets/), then reload.")
                .forEach(line -> icon.lore(line, Brand.MUTED));
        return icon.build();
    }

    private void step(@NotNull Player viewer, @NotNull InventoryClickEvent click,
            @NotNull String key, int current, int min, int max) {
        int delta = (click.isShiftClick() ? 10 : 1) * (click.isRightClick() ? -1 : 1);
        int next = Math.max(min, Math.min(max, current + delta));
        if (next != current) {
            apply(viewer, () -> ctx.management().setOverlay(key, next));
        }
    }

    @SuppressWarnings("unchecked")
    private @NotNull DeathEffectsSettings settings() {
        return ctx.plugin().snapshot().settings(
                (SettingsKey<DeathEffectsSettings>) Feature.DEATH_EFFECTS.settingsKey());
    }

    private static @NotNull String enabledLabel(boolean on) {
        return on ? "on" : "off";
    }

    private static @NotNull Component kv(@NotNull String label, @NotNull String value) {
        return Component.text()
                .append(Component.text(label + ": ", Brand.MUTED))
                .append(Component.text(value, Brand.ACCENT))
                .build();
    }
}

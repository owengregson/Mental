package me.vexmc.mental.v5.gui;

import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The dedicated Hit Effects screen: the module toggle plus the low-health
 * threshold (stepper). The layered sound and particle lists stay preset/YAML-only
 * this round and show as a read-only preview. The threshold writes the
 * {@code effects.hit.low-health-threshold-percent} overlay key through Management.
 */
public final class HitEffectsMenu extends Menu {

    private static final String THRESHOLD_KEY = "effects.hit.low-health-threshold-percent";

    public HitEffectsMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · Hit Effects", Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        return 5;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        HitFeedbackSettings settings = settings();

        Icon header = Buttons.title("NOTE_BLOCK", "Hit Effects");
        Buttons.wrap("Replace the vanilla hit sound with your own layered sounds and particles,"
                + " with an extra layer below a low-health threshold.")
                .forEach(line -> header.lore(line, Brand.MUTED));
        set(4, header.build());

        boolean enabled = ctx.plugin().featureActive(Feature.HIT_FEEDBACK);
        set(19, Buttons.toggle("LEVER", "Hit Effects module", enabled,
                "Master switch for this screen's effects."),
                click -> apply(viewer, () -> ctx.management().setModuleEnabled(Feature.HIT_FEEDBACK, !enabled)));

        int percent = (int) Math.round(settings.lowHealthThresholdPercent());
        set(22, Buttons.stepper("REDSTONE", "Low-health threshold", percent + "% of max health",
                "The extra low-HP sound layer fires below this percent of the victim's max health."),
                click -> step(viewer, click, percent));

        set(25, previewTile(settings));

        set(40, Buttons.back(), click -> navigate(viewer, new EffectsMenu(ctx)));
    }

    private @NotNull ItemStack previewTile(@NotNull HitFeedbackSettings settings) {
        Icon icon = Buttons.title("JUKEBOX", "Sounds & particles", Brand.MUTED);
        icon.lore(kv("Hit sounds", settings.vanillaTune()
                ? "vanilla hurt (era jitter)" : settings.sounds().size() + " layered"));
        icon.lore(kv("Particles", settings.particles().isEmpty()
                ? "none" : String.valueOf(settings.particles().size())));
        icon.lore(kv("Low-HP layer", settings.lowHealthSounds().isEmpty()
                ? "none" : settings.lowHealthSounds().size() + " sounds"));
        icon.blank();
        Buttons.wrap("Edit the sound and particle lists in the preset file (effects/presets/), then reload.")
                .forEach(line -> icon.lore(line, Brand.MUTED));
        return icon.build();
    }

    private void step(@NotNull Player viewer, @NotNull InventoryClickEvent click, int current) {
        int delta = (click.isShiftClick() ? 10 : 1) * (click.isRightClick() ? -1 : 1);
        int next = Math.max(0, Math.min(100, current + delta));
        if (next != current) {
            apply(viewer, () -> ctx.management().setOverlay(THRESHOLD_KEY, next));
        }
    }

    @SuppressWarnings("unchecked")
    private @NotNull HitFeedbackSettings settings() {
        return ctx.plugin().snapshot().settings(
                (SettingsKey<HitFeedbackSettings>) Feature.HIT_FEEDBACK.settingsKey());
    }

    private static @NotNull Component kv(@NotNull String label, @NotNull String value) {
        return Component.text()
                .append(Component.text(label + ": ", Brand.MUTED))
                .append(Component.text(value, Brand.ACCENT))
                .build();
    }
}

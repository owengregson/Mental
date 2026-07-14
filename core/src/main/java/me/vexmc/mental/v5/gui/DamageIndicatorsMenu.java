package me.vexmc.mental.v5.gui;

import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The dedicated Damage Indicators screen: the module toggle plus in-GUI editing
 * of the three label templates (the normal, crit, and heal numbers) via chat
 * prompt. Each writes an {@code effects.indicators.<field>} overlay key through
 * Management, layered over the selected preset. The ballistics knobs stay
 * preset/YAML-only this round.
 */
public final class DamageIndicatorsMenu extends Menu {

    private static final String KEY = "effects.indicators.";

    public DamageIndicatorsMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · Damage Indicators", Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        return 5;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        DamageIndicatorsSettings settings = settings();

        Icon header = Buttons.title("ARMOR_STAND", "Damage Indicators");
        Buttons.wrap("Pop a floating damage number off the victim on the attacker's screen."
                + " {HEALTH} is the amount in hearts; '&' colour codes are translated.")
                .forEach(line -> header.lore(line, Brand.MUTED));
        set(4, header.build());

        boolean enabled = ctx.plugin().featureActive(Feature.DAMAGE_INDICATORS);
        set(19, Buttons.toggle("LEVER", "Damage Indicators module", enabled,
                "Master switch for the pop-off numbers."),
                click -> apply(viewer,
                        () -> ctx.management().setModuleEnabled(Feature.DAMAGE_INDICATORS, !enabled)));

        set(21, Buttons.editText("PAPER", "Damage text", settings.text(),
                "The normal damage number template."),
                click -> promptOverlay(viewer, "Damage indicator text", KEY + "text"));
        set(22, Buttons.editText("BLAZE_POWDER", "Crit text", settings.critText(),
                "The critical-hit number template."),
                click -> promptOverlay(viewer, "Crit indicator text", KEY + "crit-text"));
        set(23, Buttons.editText("GLISTERING_MELON_SLICE", "Heal text", settings.healText(),
                "The healing number template — empty disables heal indicators."),
                click -> promptOverlay(viewer, "Heal indicator text", KEY + "heal-text"));

        set(40, Buttons.back(), click -> navigate(viewer, new EffectsMenu(ctx)));
    }

    @SuppressWarnings("unchecked")
    private @NotNull DamageIndicatorsSettings settings() {
        return ctx.plugin().snapshot().settings(
                (SettingsKey<DamageIndicatorsSettings>) Feature.DAMAGE_INDICATORS.settingsKey());
    }
}

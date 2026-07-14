package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.vexmc.mental.v5.config.EffectsPreset;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The server-wide Combat Effects preset picker — the {@link ProfileMenu} model
 * mirrored for the FEEDBACK family (no formula tier: effects presets are one
 * flat library). Lists every loaded {@code effects/presets/*.yml} by stem;
 * clicking a tile applies it server-wide through
 * {@code Management.setEffectsPreset}, which writes the {@code effects.preset}
 * machine overlay and reloads — the reconciler's settings-change bounce
 * re-assembles the three effects modules live. The value preview is READ-ONLY:
 * the hit tune, the indicator feel, and the death strike at a glance.
 *
 * <p>No back stack, so Back is hardcoded to the Combat Effects hub
 * ({@link EffectsMenu}), which the preset picker is reached from.</p>
 */
public final class EffectsPresetMenu extends Menu {

    /** Themed icon per shipped preset; unknown (user) presets fall back to paper. */
    private static final Map<String, String> PRESET_ICONS = Map.of(
            "signature", "NETHER_STAR",
            "custom", "WRITABLE_BOOK");

    /** The picker's two preset rows (rows 2–3 of a six-row inventory). */
    private static final int[] PRESET_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    public EffectsPresetMenu(@NotNull MenuContext ctx) {
        super(ctx);
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · Combat Effects Preset", Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        // PRESET_SLOTS reach slot 34, so the picker needs the full six rows.
        return 6;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        Icon header = Buttons.title("JUKEBOX", "Combat Effects Preset");
        Buttons.wrap("One preset carries the whole cosmetic tune — hit sounds and"
                + " particles, the damage indicator feel, and the death strike."
                + " The module toggles stay on the family screen.")
                .forEach(line -> header.lore(line, Brand.MUTED));
        set(4, header.build());

        String active = ctx.plugin().snapshot().selectedEffectsPreset();
        List<String> names = presetNames();
        for (int i = 0; i < names.size() && i < PRESET_SLOTS.length; i++) {
            String name = names.get(i);
            EffectsPreset preset = ctx.plugin().snapshot().effectsPreset(name);
            boolean isActive = name.equals(active);
            set(PRESET_SLOTS[i], presetTile(preset, name, isActive),
                    click -> apply(viewer, () -> ctx.management().setEffectsPreset(name)));
        }

        set(49, Buttons.back(), click -> navigate(viewer, new EffectsMenu(ctx)));
    }

    /** Every loaded preset name, sorted — any file dropped into effects/presets/ shows up. */
    private @NotNull List<String> presetNames() {
        List<String> names = new ArrayList<>(ctx.plugin().snapshot().effectsPresetNames());
        names.sort(null);
        return names;
    }

    private @NotNull ItemStack presetTile(EffectsPreset preset, @NotNull String name, boolean active) {
        String material = PRESET_ICONS.getOrDefault(name, "PAPER");
        Icon icon = Buttons.title(material,
                preset != null ? preset.displayName() : name,
                active ? Brand.SUCCESS : Brand.PRIMARY);
        icon.lore(Component.text("(" + name + ")", Brand.MUTED));
        if (preset != null) {
            if (!preset.description().isEmpty()) {
                icon.blank();
                Buttons.wrap(preset.description()).forEach(line -> icon.lore(line, Brand.MUTED));
            }
            icon.blank();
            preview(icon, preset);
        }
        icon.blank();
        icon.lore(active
                ? Component.text("● ACTIVE — server-wide", Brand.SUCCESS).decoration(TextDecoration.BOLD, true)
                : Component.text("▸ Click to apply server-wide", Brand.SECONDARY));
        return icon.glow(active).build();
    }

    /** The read-only tune summary: what a hit, an indicator, and a death each ship. */
    private void preview(@NotNull Icon icon, @NotNull EffectsPreset preset) {
        HitFeedbackSettings hit = preset.hitFeedback();
        icon.lore(kv("Hit sounds", hit.vanillaTune()
                ? "vanilla hurt (era jitter)"
                : hit.sounds().size() + " layered"));
        icon.lore(kv("Hit particles", hit.particles().isEmpty()
                ? "none" : String.valueOf(hit.particles().size())));
        icon.lore(kv("Low-HP layer", hit.lowHealthSounds().isEmpty()
                ? "none"
                : "below " + round(hit.lowHealthThresholdPercent()) + "% of max health"));
        icon.lore(kv("Indicator", preset.damageIndicators().text()));
        DeathEffectsSettings death = preset.deathEffects();
        boolean deathNothing = !death.lightning() && death.sounds().isEmpty()
                && death.particles().isEmpty() && death.fireworkColors().isEmpty();
        if (deathNothing) {
            icon.lore(kv("Death", "nothing (vanilla)"));
        } else {
            icon.lore(kv("Death", (death.lightning() ? "lightning" : "no bolt")
                    + " · " + death.sounds().size() + " sounds"
                    + (death.fireworkColors().isEmpty()
                            ? "" : " · " + death.fireworkColors().size() + "-color blast")));
        }
    }

    /**
     * Boot self-test seam (mirrors {@link ProfileMenu#selfTestIcons}): the
     * load-bearing icons rendered with no viewer, so the tester can prove the
     * Adventure/String sink path classloads on legacy servers. Returns only
     * Bukkit types.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        List<ItemStack> icons = new ArrayList<>();
        Icon header = Buttons.title("JUKEBOX", "Combat Effects Preset");
        icons.add(header.build());
        String active = ctx.plugin().snapshot().selectedEffectsPreset();
        for (String name : presetNames()) {
            icons.add(presetTile(ctx.plugin().snapshot().effectsPreset(name), name, name.equals(active)));
        }
        icons.add(Buttons.back());
        return icons;
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

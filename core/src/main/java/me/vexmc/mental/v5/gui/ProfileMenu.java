package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.profile.ModernKnockback;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The server-wide profile picker for one melee {@link MeleeFormula} — the leaf
 * of the Knockback → Formula → Preset navigation. Lists only the profiles that
 * belong to the chosen formula (the same bucket the on-disk
 * {@code profiles/legacy/} or {@code profiles/modern/} folder holds); clicking a
 * tile applies it server-wide through {@code Management.setGlobalProfile}. The
 * value preview is READ-ONLY and formula-aware: a modern profile shows its
 * modern knobs, a legacy profile its legacy knobs.
 *
 * <p>Lifted from the former inline picker on {@link FamilyMenu}; no back stack,
 * so Back is hardcoded to the {@link KnockbackFormulaMenu} chooser.</p>
 */
public final class ProfileMenu extends Menu {

    /**
     * Themed icon per shipped preset; unknown (user) profiles fall back to paper.
     * {@code Map.ofEntries} (not {@code Map.of}) — the three modern presets take
     * this past the ten-pair {@code Map.of} ceiling.
     */
    private static final Map<String, String> PROFILE_ICONS = Map.ofEntries(
            Map.entry("legacy-1.7", "STONE_SWORD"),
            Map.entry("legacy-1.8", "IRON_SWORD"),
            Map.entry("kohi", "DIAMOND_SWORD"),
            Map.entry("minehq", "GOLDEN_SWORD"),
            Map.entry("badlion", "IRON_AXE"),
            Map.entry("velt", "DIAMOND_AXE"),
            Map.entry("mmc", "BOW"),
            Map.entry("lunar", "ENDER_EYE"),
            Map.entry("signature", "NETHER_STAR"),
            Map.entry("modern-vanilla", "NETHERITE_SWORD"),
            Map.entry("modern-uplift", "NETHERITE_AXE"),
            Map.entry("modern-combo", "TRIDENT"),
            Map.entry("custom", "WRITABLE_BOOK"));

    /** The picker's two profile rows (rows 2–3 of a six-row inventory). */
    private static final int[] PROFILE_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    private final MeleeFormula formula;

    public ProfileMenu(@NotNull MenuContext ctx, @NotNull MeleeFormula formula) {
        super(ctx);
        this.formula = formula;
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental · " + formula.displayName(), Brand.PRIMARY);
    }

    @Override
    protected int rows() {
        // PROFILE_SLOTS reach slot 34, so the picker needs the full six rows.
        return 6;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        Icon header = Buttons.title(formula.iconName(), formula.displayName());
        Buttons.wrap(formula.blurb()).forEach(line -> header.lore(line, Brand.MUTED));
        set(4, header.build());

        String active = ctx.plugin().snapshot().defaultProfile();
        List<String> names = profileNames();
        for (int i = 0; i < names.size() && i < PROFILE_SLOTS.length; i++) {
            String name = names.get(i);
            KnockbackProfile profile = ctx.plugin().snapshot().profile(name);
            boolean isActive = name.equals(active);
            set(PROFILE_SLOTS[i], profileTile(profile, name, isActive),
                    click -> apply(viewer, () -> ctx.management().setGlobalProfile(name)));
        }

        set(49, Buttons.back(), click -> navigate(viewer, new KnockbackFormulaMenu(ctx)));
    }

    /** The loaded profiles that belong to this formula, sorted by name. */
    private @NotNull List<String> profileNames() {
        List<String> names = new ArrayList<>();
        for (String name : ctx.plugin().snapshot().profileNames()) {
            if (formula.matches(ctx.plugin().snapshot().profile(name))) {
                names.add(name);
            }
        }
        names.sort(null);
        return names;
    }

    private @NotNull ItemStack profileTile(KnockbackProfile profile, @NotNull String name, boolean active) {
        String material = PROFILE_ICONS.getOrDefault(name, "PAPER");
        Icon icon = Buttons.title(material,
                profile != null ? profile.displayName() : name,
                active ? Brand.SUCCESS : Brand.PRIMARY);
        icon.lore(Component.text("(" + name + ")", Brand.MUTED));
        if (profile != null) {
            if (!profile.description().isEmpty()) {
                icon.blank();
                Buttons.wrap(profile.description()).forEach(line -> icon.lore(line, Brand.MUTED));
            }
            icon.blank();
            if (profile.modern().enabled()) {
                modernPreview(icon, profile);
            } else {
                legacyPreview(icon, profile);
            }
        }
        icon.blank();
        icon.lore(active
                ? Component.text("● ACTIVE — server-wide", Brand.SUCCESS).decoration(TextDecoration.BOLD, true)
                : Component.text("▸ Click to apply server-wide", Brand.SECONDARY));
        return icon.glow(active).build();
    }

    /** The modern (26.1.2) knob preview — the legacy knobs are inert under this formula. */
    private void modernPreview(@NotNull Icon icon, @NotNull KnockbackProfile profile) {
        ModernKnockback modern = profile.modern();
        icon.lore(kv("Base", round(modern.baseStrength())));
        icon.lore(kv("Sprint +", round(modern.sprintBonus())));
        icon.lore(kv("Enchant +", round(modern.enchantBonus())));
        icon.lore(kv("Downward", modern.downwardKnockback() ? "yes (mid-air slam)" : "no (uplift)"));
        icon.lore(kv("Combos", profile.combos() ? "yes" : "no"));
        icon.lore(kv("Delivery", profile.meleeDelivery().name().toLowerCase(Locale.ROOT).replace('_', '-')));
    }

    private void legacyPreview(@NotNull Icon icon, @NotNull KnockbackProfile profile) {
        icon.lore(kv("Base h/v",
                round(profile.base().horizontal()) + " / " + round(profile.base().vertical())));
        icon.lore(kv("Vertical", profile.verticalMode().name().toLowerCase(Locale.ROOT)));
        icon.lore(kv("Delivery", profile.meleeDelivery().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        icon.lore(kv("Combos", profile.combos() ? "yes" : "no"));
        icon.lore(kv("Sprint x", round(profile.sprintFactor())));
        icon.lore(kv("Resistance", profile.resistance().name().toLowerCase(Locale.ROOT)));
        if (profile.paceScaling().active()) {
            icon.lore(kv("Pace scale", "attacker x"
                    + round(profile.paceScaling().min()) + "–" + round(profile.paceScaling().max())
                    + " ^" + round(profile.paceScaling().exponent())));
        }
    }

    /**
     * Boot self-test seam (mirrors {@link DashboardMenu#selfTestIcons}): the
     * load-bearing icons rendered with no viewer, so the tester can prove the
     * Adventure/String sink path classloads on legacy servers. Returns only
     * Bukkit types.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        List<ItemStack> icons = new ArrayList<>();
        Icon header = Buttons.title(formula.iconName(), formula.displayName());
        Buttons.wrap(formula.blurb()).forEach(line -> header.lore(line, Brand.MUTED));
        icons.add(header.build());
        String active = ctx.plugin().snapshot().defaultProfile();
        for (String name : profileNames()) {
            icons.add(profileTile(ctx.plugin().snapshot().profile(name), name, name.equals(active)));
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

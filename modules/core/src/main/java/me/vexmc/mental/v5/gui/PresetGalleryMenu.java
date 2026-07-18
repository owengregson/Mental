package me.vexmc.mental.v5.gui;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Family;
import me.vexmc.mental.v5.preset.PresetCatalog;
import me.vexmc.mental.v5.preset.PresetInfo;
import me.vexmc.mental.v5.preset.PresetKind;
import me.vexmc.mental.v5.text.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The unified preset gallery: one screen for both {@link PresetKind}s, read
 * entirely through {@link PresetCatalog} and written entirely through its
 * {@code apply} (which delegates to {@code Management.setGlobalProfile} /
 * {@code setEffectsPreset} — the tester's seam). For {@link PresetKind#KNOCKBACK}
 * the legacy/modern formula split is a TAB, not a navigation tier: the tab
 * filters the same gallery in place and writes nothing. Twenty-one tiles per page
 * with pure-math pagination for the day a server drops in a folder of custom
 * presets.
 *
 * <p>The gallery keeps NO per-preset icon map of its own — every tile's material,
 * copy, and preview lines come from {@code PresetCatalog.info(...)}. The tab and
 * page state are menu-local fields flipped on the viewer's region thread and
 * repainted with {@link #refresh}; only an actual preset apply hops to the global
 * thread through {@link #apply}.</p>
 */
public final class PresetGalleryMenu extends Menu {

    /** Tiles shown per page — the {@link Layout#galleryGrid()} capacity. */
    private static final int PAGE_SIZE = 21;

    private static final int HEADER_SLOT = 4;
    private static final int TAB_LEGACY_SLOT = 2;
    private static final int TAB_MODERN_SLOT = 6;
    private static final int PREV_SLOT = 47;
    private static final int BACK_SLOT = 49;
    private static final int NEXT_SLOT = 51;

    private final PresetKind kind;
    private final Palette.Theme theme;

    /** The active formula tab — the KNOCKBACK filter; always initialised, unused for EFFECTS. */
    private MeleeFormula tab;
    private int page;

    public PresetGalleryMenu(@NotNull MenuContext ctx, @NotNull PresetKind kind) {
        super(ctx);
        this.kind = kind;
        this.theme = Palette.gallery(kind);
        Snapshot snapshot = ctx.plugin().snapshot();
        // Open on the ACTIVE formula so the gallery shows the feel the server is
        // running; EFFECTS ignores the tab, so any value is inert there.
        this.tab = MeleeFormula.of(snapshot.profile(snapshot.defaultProfile()));
    }

    @Override
    protected @NotNull Component title() {
        return Component.text("Mental", Brand.PRIMARY, TextDecoration.BOLD)
                .append(Component.text(" · ", Brand.MUTED))
                .append(Component.text(kind.displayName(), theme.accent()));
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void draw(@NotNull Player viewer) {
        paintChrome(theme.pane());
        Snapshot snapshot = ctx.plugin().snapshot();

        // ONE catalog build per repaint: the active tab's slice AND both tab
        // counts are all derived from this single list — no second/third
        // PresetCatalog.infos(...) call remains on any draw path.
        List<PresetInfo> all = PresetCatalog.infos(kind, snapshot);
        List<PresetInfo> filtered = filterByTab(all, tab);
        Layout.Page window = Layout.page(filtered.size(), PAGE_SIZE, page);
        this.page = window.page();

        set(HEADER_SLOT, headerCard(snapshot, window));

        if (kind == PresetKind.KNOCKBACK) {
            set(TAB_LEGACY_SLOT, tabTile(MeleeFormula.LEGACY, tab, countFor(all, MeleeFormula.LEGACY)),
                    click -> selectTab(viewer, MeleeFormula.LEGACY));
            set(TAB_MODERN_SLOT, tabTile(MeleeFormula.MODERN, tab, countFor(all, MeleeFormula.MODERN)),
                    click -> selectTab(viewer, MeleeFormula.MODERN));
        }

        int[] grid = Layout.galleryGrid();
        for (int i = window.fromIndex(); i < window.toIndex(); i++) {
            PresetInfo info = filtered.get(i);
            set(grid[i - window.fromIndex()], presetTile(info),
                    click -> apply(viewer, () -> PresetCatalog.apply(kind, info.name(), ctx.management())));
        }

        if (window.hasPrev()) {
            set(PREV_SLOT, prevTile(window), click -> {
                page--;
                refresh(viewer);
            });
        }
        Family backFamily = kind == PresetKind.KNOCKBACK ? Family.KNOCKBACK : Family.FEEDBACK;
        String backName = kind == PresetKind.KNOCKBACK ? "Knockback" : "Combat Effects";
        set(BACK_SLOT, Buttons.back(backName), click -> navigate(viewer, new FamilyMenu(ctx, backFamily)));
        if (window.hasNext()) {
            set(NEXT_SLOT, nextTile(), click -> {
                page++;
                refresh(viewer);
            });
        }
    }

    /**
     * Boot self-test seam: the gallery's icons on the ACTIVE tab. Pure Bukkit
     * stacks, no viewer, no scheduler hop.
     */
    public @NotNull List<ItemStack> selfTestIcons() {
        return selfTestIcons(tab);
    }

    /**
     * Tab-forced render for the boot self-test: renders the gallery as if
     * {@code which} were the active formula tab. KNOCKBACK filters by it (keeping
     * the {@link MeleeFormula} compile pin alive); EFFECTS ignores it.
     */
    public @NotNull List<ItemStack> selfTestIcons(@NotNull MeleeFormula which) {
        Snapshot snapshot = ctx.plugin().snapshot();
        List<PresetInfo> all = PresetCatalog.infos(kind, snapshot);
        List<PresetInfo> filtered = filterByTab(all, which);
        Layout.Page window = Layout.page(filtered.size(), PAGE_SIZE, 0);
        List<ItemStack> icons = new ArrayList<>();
        icons.add(headerCard(snapshot, window));
        if (kind == PresetKind.KNOCKBACK) {
            icons.add(tabTile(MeleeFormula.LEGACY, which, countFor(all, MeleeFormula.LEGACY)));
            icons.add(tabTile(MeleeFormula.MODERN, which, countFor(all, MeleeFormula.MODERN)));
        }
        for (int i = window.fromIndex(); i < window.toIndex(); i++) {
            icons.add(presetTile(filtered.get(i)));
        }
        icons.add(Buttons.back(kind == PresetKind.KNOCKBACK ? "Knockback" : "Combat Effects"));
        return icons;
    }

    private void selectTab(@NotNull Player viewer, @NotNull MeleeFormula which) {
        this.tab = which;
        this.page = 0;
        refresh(viewer);
    }

    /**
     * The active tab's slice of an ALREADY-built catalog list — a pure filter, no
     * catalog rebuild. KNOCKBACK partitions by formula; EFFECTS shows the list
     * as-is (the tab is inert there).
     */
    private @NotNull List<PresetInfo> filterByTab(@NotNull List<PresetInfo> all, @NotNull MeleeFormula activeTab) {
        if (kind != PresetKind.KNOCKBACK) {
            return all;
        }
        List<PresetInfo> filtered = new ArrayList<>();
        for (PresetInfo info : all) {
            if (info.modernFormula() == (activeTab == MeleeFormula.MODERN)) {
                filtered.add(info);
            }
        }
        return filtered;
    }

    private @NotNull ItemStack headerCard(@NotNull Snapshot snapshot, @NotNull Layout.Page window) {
        Icon icon = Buttons.title(kind.iconName(), kind.displayName(), theme.accent());
        PresetInfo active = PresetCatalog.info(kind, PresetCatalog.selected(kind, snapshot), snapshot);
        icon.lore(Buttons.kv("Active", active.displayName(), theme.accent()));
        if (window.pageCount() > 1) {
            icon.lore(Buttons.kv("Page", (window.page() + 1) + " / " + window.pageCount(), theme.accent()));
        }
        icon.blank();
        icon.lore(kind == PresetKind.KNOCKBACK
                ? "Pick the era formula above, then a feel below."
                : "One tune per tile — sounds, indicators and deaths.", Brand.MUTED);
        return icon.build();
    }

    private @NotNull ItemStack tabTile(
            @NotNull MeleeFormula formula, @NotNull MeleeFormula activeTab, int count) {
        boolean active = activeTab == formula;
        // The material is the formula enum's own icon — one source of truth for the
        // formula glyph (STONE_SWORD / NETHERITE_SWORD). The LABEL stays deliberate
        // gallery copy ("Legacy formula" / "Modern formula"): the enum's displayName
        // is the long-form name ("Legacy (1.7 / 1.8)") used elsewhere, and the
        // gallery wants the short heading here.
        String material = formula.iconName();
        String name = formula == MeleeFormula.LEGACY ? "Legacy formula" : "Modern formula";
        Icon icon = Buttons.title(material, name, active ? theme.accent() : Brand.TEXT);
        Buttons.wrap(formula.blurb()).forEach(line -> icon.lore(line, Brand.MUTED));
        icon.blank();
        icon.lore(Buttons.kv("Presets", String.valueOf(count), theme.accent()));
        if (active) {
            icon.lore(Component.text("● SHOWING", Brand.SUCCESS).decoration(TextDecoration.BOLD, true));
        } else {
            icon.lore(Component.text("▸ Click to view", Brand.SECONDARY));
        }
        return icon.glow(active).build();
    }

    /** Count of an already-built catalog list belonging to {@code formula} — no catalog rebuild. */
    private static int countFor(@NotNull List<PresetInfo> all, @NotNull MeleeFormula formula) {
        int count = 0;
        for (PresetInfo info : all) {
            if (info.modernFormula() == (formula == MeleeFormula.MODERN)) {
                count++;
            }
        }
        return count;
    }

    private @NotNull ItemStack presetTile(@NotNull PresetInfo info) {
        Icon icon = Buttons.title(info.iconName(), info.displayName(), info.active() ? Brand.SUCCESS : Brand.TEXT);
        icon.lore(Component.text("(" + info.name() + ")", Brand.MUTED));
        icon.lore(Component.text(info.bundled() ? "Bundled preset" : "Your preset", NamedTextColor.DARK_GRAY));
        icon.blank();
        if (!info.description().isEmpty()) {
            Buttons.wrap(info.description()).forEach(line -> icon.lore(line, Brand.MUTED));
            icon.blank();
        }
        for (PresetInfo.PreviewLine line : info.preview()) {
            icon.lore(Buttons.kv(line.label(), line.value(), theme.accent()));
        }
        icon.blank();
        if (info.active()) {
            icon.lore(Component.text("● ACTIVE — server-wide", Brand.SUCCESS).decoration(TextDecoration.BOLD, true));
        } else {
            icon.lore(Component.text("▸ Click to apply server-wide", Brand.SECONDARY));
        }
        return icon.glow(info.active()).build();
    }

    private @NotNull ItemStack prevTile(@NotNull Layout.Page window) {
        return Buttons.title("ARROW", "Previous page", Brand.SECONDARY)
                .lore("Page " + window.page() + " of " + window.pageCount() + ".", Brand.MUTED)
                .build();
    }

    private @NotNull ItemStack nextTile() {
        return Buttons.title("ARROW", "Next page", Brand.SECONDARY).build();
    }
}

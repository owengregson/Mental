package me.vexmc.mental.v5.preset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import me.vexmc.mental.v5.config.ConfigStore;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.SnapshotParser;
import me.vexmc.mental.v5.config.settings.DamageIndicatorsSettings;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The catalog's resolution over a real parsed {@link Snapshot} — the names/sort
 * pin, per-kind selection, bundled membership, and the preview vocabulary
 * (hand-computed from the kernel/DEFAULTS constants, never recomputed through
 * the code under test). {@code apply()} is not unit-tested: it is pure
 * delegation to the final, plugin-bound {@code Management}, live-pinned by
 * ProfileSuite through the very seam it calls.
 */
class PresetCatalogTest {

    private static YamlConfiguration resource(String classpath) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                PresetCatalogTest.class.getClassLoader().getResourceAsStream(classpath),
                StandardCharsets.UTF_8));
    }

    private static Snapshot snapshot() {
        Map<String, Configuration> profiles = Map.of(
                "signature", resource("profiles/legacy/signature.yml"),
                "modern-vanilla", resource("profiles/modern/modern-vanilla.yml"));
        Map<String, Configuration> effectsPresets = Map.of(
                "signature", resource("effects/presets/signature.yml"),
                "custom", resource("effects/presets/custom.yml"),
                "bare", new YamlConfiguration());
        ConfigStore.Sources sources = new ConfigStore.Sources(
                new MemoryConfiguration(), new MemoryConfiguration(),
                new MemoryConfiguration(), new MemoryConfiguration(),
                new MemoryConfiguration(), new MemoryConfiguration(),
                new MemoryConfiguration(), new MemoryConfiguration(),
                new MemoryConfiguration(), effectsPresets, profiles);
        return SnapshotParser.parse(sources).snapshot();
    }

    @Test
    void namesAreTheSnapshotNamesSortedPerKind() {
        Snapshot s = snapshot();
        assertEquals(List.of("legacy-1.7", "modern-vanilla", "signature"),
                PresetCatalog.names(PresetKind.KNOCKBACK, s));
        assertEquals(List.of("bare", "custom", "signature"),
                PresetCatalog.names(PresetKind.EFFECTS, s));
    }

    @Test
    void selectedDelegatesToTheSnapshotSelectionPerKind() {
        Snapshot s = snapshot();
        assertEquals(s.defaultProfile(), PresetCatalog.selected(PresetKind.KNOCKBACK, s));
        assertEquals("legacy-1.7", PresetCatalog.selected(PresetKind.KNOCKBACK, s));
        assertEquals(s.selectedEffectsPreset(), PresetCatalog.selected(PresetKind.EFFECTS, s));
        assertEquals("signature", PresetCatalog.selected(PresetKind.EFFECTS, s));
    }

    @Test
    void bundledNamesComeFromTheConfigStoreLists() {
        for (String name : ConfigStore.BUNDLED_PROFILES) {
            assertTrue(PresetCatalog.isBundled(PresetKind.KNOCKBACK, name));
        }
        for (String name : ConfigStore.BUNDLED_EFFECTS_PRESETS) {
            assertTrue(PresetCatalog.isBundled(PresetKind.EFFECTS, name));
        }
        assertFalse(PresetCatalog.isBundled(PresetKind.KNOCKBACK, "nope"));
        assertFalse(PresetCatalog.isBundled(PresetKind.EFFECTS, "kohi"));
        assertFalse(PresetCatalog.isBundled(PresetKind.KNOCKBACK, "bare"));
    }

    @Test
    void aLegacyProfileInfoCarriesTheLegacyPreviewLines() {
        Snapshot s = snapshot();
        PresetInfo info = PresetCatalog.info(PresetKind.KNOCKBACK, "legacy-1.7", s);
        assertTrue(info.loaded());
        assertTrue(info.bundled());
        assertTrue(info.active());
        assertFalse(info.modernFormula());
        assertEquals("STONE_SWORD", info.iconName());
        assertEquals(s.profile("legacy-1.7").displayName(), info.displayName());
        assertEquals(List.of(
                new PresetInfo.PreviewLine("Base h/v", "0.4 / 0.4"),
                new PresetInfo.PreviewLine("Vertical", "add"),
                new PresetInfo.PreviewLine("Delivery", "tracker"),
                new PresetInfo.PreviewLine("Combos", "yes"),
                new PresetInfo.PreviewLine("Sprint x", "1.0"),
                new PresetInfo.PreviewLine("Resistance", "none")),
                info.preview());
    }

    @Test
    void theSignatureProfileInfoCarriesThePaceLine() {
        Snapshot s = snapshot();
        List<PresetInfo.PreviewLine> pace = PresetCatalog.info(PresetKind.KNOCKBACK, "signature", s)
                .preview().stream()
                .filter(line -> line.label().equals("Pace scale"))
                .toList();
        assertEquals(List.of(
                new PresetInfo.PreviewLine("Pace scale", "attacker x0.5–2.0 ^0.95")), pace);
    }

    @Test
    void aModernProfileInfoCarriesTheModernPreviewLines() {
        Snapshot s = snapshot();
        PresetInfo info = PresetCatalog.info(PresetKind.KNOCKBACK, "modern-vanilla", s);
        assertTrue(info.modernFormula());
        assertEquals("NETHERITE_SWORD", info.iconName());
        assertEquals(List.of(
                new PresetInfo.PreviewLine("Base", "0.4"),
                new PresetInfo.PreviewLine("Sprint +", "0.5"),
                new PresetInfo.PreviewLine("Enchant +", "0.5"),
                new PresetInfo.PreviewLine("Downward", "yes (mid-air slam)"),
                new PresetInfo.PreviewLine("Combos", "no"),
                new PresetInfo.PreviewLine("Delivery", "immediate")),
                info.preview());
    }

    @Test
    void anEffectsPresetInfoCarriesTheTuneLines() {
        Snapshot s = snapshot();
        assertEquals("PAPER", PresetCatalog.info(PresetKind.EFFECTS, "bare", s).iconName());
        assertEquals("NETHER_STAR",
                PresetCatalog.info(PresetKind.EFFECTS, "signature", s).iconName());
        assertEquals(List.of(
                new PresetInfo.PreviewLine("Hit sounds", "vanilla hurt (era jitter)"),
                new PresetInfo.PreviewLine("Hit particles", "none"),
                new PresetInfo.PreviewLine("Low-HP layer", "none"),
                new PresetInfo.PreviewLine("Indicator", DamageIndicatorsSettings.DEFAULTS.text()),
                new PresetInfo.PreviewLine("Death", "nothing (vanilla)")),
                PresetCatalog.info(PresetKind.EFFECTS, "bare", s).preview());
    }

    @Test
    void anUnloadedNameDegradesToANameOnlyInfo() {
        Snapshot s = snapshot();
        PresetInfo knockback = PresetCatalog.info(PresetKind.KNOCKBACK, "ghost", s);
        assertFalse(knockback.loaded());
        assertEquals("ghost", knockback.displayName());
        assertTrue(knockback.description().isEmpty());
        assertEquals("PAPER", knockback.iconName());
        assertTrue(knockback.preview().isEmpty());
        assertFalse(knockback.active());
        assertFalse(knockback.bundled());
        assertFalse(knockback.modernFormula());
        PresetInfo effects = PresetCatalog.info(PresetKind.EFFECTS, "ghost", s);
        assertFalse(effects.loaded());
        assertEquals("ghost", effects.displayName());
        assertTrue(effects.description().isEmpty());
        assertEquals("PAPER", effects.iconName());
        assertTrue(effects.preview().isEmpty());
        assertFalse(effects.active());
        assertFalse(effects.bundled());
        assertFalse(effects.modernFormula());
    }

    @Test
    void activeFlagsExactlyTheSelectedPreset() {
        Snapshot s = snapshot();
        for (PresetKind kind : PresetKind.values()) {
            List<PresetInfo> active = PresetCatalog.infos(kind, s).stream()
                    .filter(PresetInfo::active)
                    .toList();
            assertEquals(1, active.size());
            assertEquals(PresetCatalog.selected(kind, s), active.get(0).name());
        }
    }

    @Test
    void infosCoverEveryNameInOrder() {
        Snapshot s = snapshot();
        for (PresetKind kind : PresetKind.values()) {
            assertEquals(PresetCatalog.names(kind, s),
                    PresetCatalog.infos(kind, s).stream().map(PresetInfo::name).toList());
        }
    }

    @Test
    void previewListsAreImmutable() {
        Snapshot s = snapshot();
        PresetInfo info = PresetCatalog.info(PresetKind.KNOCKBACK, "legacy-1.7", s);
        assertThrows(UnsupportedOperationException.class,
                () -> info.preview().add(new PresetInfo.PreviewLine("x", "y")));
    }
}

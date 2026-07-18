package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import me.vexmc.mental.v5.feature.Feature;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The machine-owned overlay: it steers the parsed snapshot without ever
 * rewriting the human config files, persists only its own file, and survives a
 * round-trip through disk.
 */
class OverlayTest {

    @TempDir
    Path dataFolder;

    private ConfigStore store() {
        return new ConfigStore(dataFolder,
                name -> getClass().getClassLoader().getResourceAsStream(name), message -> {});
    }

    @Test
    void overlaySteersTheSnapshotWithoutRewritingHumanFiles() throws Exception {
        ConfigStore store = store();
        store.ensureDefaultFiles();

        byte[] mainBefore = Files.readAllBytes(dataFolder.resolve(ConfigStore.MAIN_FILE));
        byte[] knockbackBefore = Files.readAllBytes(dataFolder.resolve(ConfigStore.KNOCKBACK_FILE));

        Overlay overlay = new Overlay(store.overridesFile());
        overlay.set("modules.knockback", false);
        overlay.set("knockback.profile", "kohi");

        ConfigStore.Sources sources = store.loadSources();
        overlay.apply(sources);
        SnapshotParser.Result result = SnapshotParser.parse(sources);
        Snapshot snapshot = result.snapshot();

        // The snapshot reflects BOTH overrides.
        assertFalse(snapshot.enabled(Feature.KNOCKBACK), "modules.knockback override applied");
        assertEquals("kohi", snapshot.profileFor("world").name(), "knockback.profile override applied");

        // The human files on disk are byte-identical to their bundled originals.
        assertArrayEquals(mainBefore, Files.readAllBytes(dataFolder.resolve(ConfigStore.MAIN_FILE)),
                "config.yml must never be rewritten by the overlay");
        assertArrayEquals(knockbackBefore, Files.readAllBytes(dataFolder.resolve(ConfigStore.KNOCKBACK_FILE)),
                "knockback.yml must never be rewritten by the overlay");

        // The overlay persisted its own file.
        assertTrue(Files.isRegularFile(store.overridesFile()), "overlay file must be written");
    }

    @Test
    void overlayKeysOnMovedSectionsRouteToTheSplitRoots() throws Exception {
        // The 2.5.2 split (and the 2.5.3 effects selection): an override whose
        // first path segment is a moved section must land on the root the
        // parser actually reads, so effective = overlay ?? file ?? default
        // keeps holding for those keys.
        ConfigStore store = store();
        store.ensureDefaultFiles();
        Overlay overlay = new Overlay(store.overridesFile());
        overlay.set("effects.preset", "signature");
        overlay.set("combo-hold.gain", 0.5);

        ConfigStore.Sources sources = store.loadSources();
        overlay.apply(sources);

        assertEquals("signature", sources.effects().getString("effects.preset"),
                "the effects-preset override rides the effects.yml root");
        assertEquals(0.5, sources.combo().getDouble("combo-hold.gain"),
                "the combo-hold override rides the combo.yml root");
        SnapshotParser.Result result = SnapshotParser.parse(sources);
        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());
        // The overlaid selection steers the parsed snapshot: the signature
        // preset's tune is what the FEEDBACK settings carry.
        assertEquals("signature", result.snapshot().selectedEffectsPreset(),
                "the overlay wins over the vanilla selection in effects.yml");
    }

    @Test
    void anOverlayWriteOnAStillHonouredOldLocationSectionOverridesInPlace() {
        // The shadow trap (Overlay.splitOrHonouredMain): a pre-2.5.2 install whose
        // combo.yml was never extracted still carries combo-hold in config.yml, and
        // SnapshotParser.movedSection HONOURS that old location. A lone overlay write
        // must land THERE — not on the empty split root, which would materialise the
        // section, flip movedSection to split-wins, and silently revert every other
        // value the owner tuned in config.yml.
        YamlConfiguration main = new YamlConfiguration();
        main.set("combo-hold.gain", 0.7);                 // a tuned value in the honoured old location
        Configuration combo = new MemoryConfiguration();  // the split root never carried the section
        ConfigStore.Sources sources = new ConfigStore.Sources(
                main, new MemoryConfiguration(), new MemoryConfiguration(), new MemoryConfiguration(),
                combo, new MemoryConfiguration(), new MemoryConfiguration(), new MemoryConfiguration(),
                new MemoryConfiguration(), Map.of(), Map.of());

        Overlay overlay = new Overlay(dataFolder.resolve("state").resolve("overrides.yml"));
        overlay.set("combo-hold.min-hits", 3);
        overlay.apply(sources);

        assertNull(combo.getConfigurationSection("combo-hold"),
                "the empty split root must never be materialised by the overlay");
        assertEquals(3, main.getInt("combo-hold.min-hits"),
                "the override lands on config.yml — exactly where the parser reads");
        assertEquals(0.7, main.getDouble("combo-hold.gain"),
                "the owner's other config.yml value survives — no silent revert");

        // The normal case is unchanged: when the split root DOES carry the section,
        // the same key routes to the split root (the pinned byte-identical behavior).
        YamlConfiguration mainNormal = new YamlConfiguration();
        YamlConfiguration comboNormal = new YamlConfiguration();
        comboNormal.set("combo-hold.gain", 0.7);          // section present on the split root
        ConfigStore.Sources normal = new ConfigStore.Sources(
                mainNormal, new MemoryConfiguration(), new MemoryConfiguration(), new MemoryConfiguration(),
                comboNormal, new MemoryConfiguration(), new MemoryConfiguration(), new MemoryConfiguration(),
                new MemoryConfiguration(), Map.of(), Map.of());

        Overlay normalOverlay = new Overlay(dataFolder.resolve("state").resolve("overrides-normal.yml"));
        normalOverlay.set("combo-hold.min-hits", 3);
        normalOverlay.apply(normal);

        assertEquals(3, comboNormal.getInt("combo-hold.min-hits"),
                "with the section on the split root, the override rides the split root");
        assertNull(mainNormal.getConfigurationSection("combo-hold"),
                "config.yml is untouched in the normal case");
    }

    @Test
    void overlayRoundTripsThroughDiskAndSupportsRemoval() {
        ConfigStore store = store();
        Path stateFile = store.overridesFile();

        Overlay first = new Overlay(stateFile);
        first.set("modules.knockback", false);
        first.set("hit-registration.max-cps", 12);

        // A fresh Overlay reads the persisted entries back.
        Overlay reloaded = new Overlay(stateFile);
        assertEquals(false, reloaded.overrides().get("modules.knockback"));
        assertEquals(12, reloaded.overrides().get("hit-registration.max-cps"));

        // Removal persists too.
        reloaded.remove("modules.knockback");
        Overlay afterRemoval = new Overlay(stateFile);
        assertFalse(afterRemoval.overrides().containsKey("modules.knockback"));
        assertTrue(afterRemoval.overrides().containsKey("hit-registration.max-cps"));
    }

    @Test
    void absentOverlayIsEmpty() {
        Overlay overlay = new Overlay(dataFolder.resolve("state").resolve("overrides.yml"));
        assertTrue(overlay.overrides().isEmpty());
    }
}

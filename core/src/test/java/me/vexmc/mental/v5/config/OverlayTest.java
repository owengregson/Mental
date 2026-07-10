package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import me.vexmc.mental.v5.feature.Feature;
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
        // The 2.5.2 split: an override whose first path segment is a moved section
        // must land on the split root the parser actually reads, so
        // effective = overlay ?? file ?? default keeps holding for those keys.
        ConfigStore store = store();
        store.ensureDefaultFiles();
        Overlay overlay = new Overlay(store.overridesFile());
        overlay.set("hit-feedback.preset", "signature");
        overlay.set("combo-hold.gain", 0.5);

        ConfigStore.Sources sources = store.loadSources();
        overlay.apply(sources);

        assertEquals("signature", sources.hitFeedback().getString("hit-feedback.preset"),
                "the hit-feedback override rides the effects/hit-feedback.yml root");
        assertEquals(0.5, sources.combo().getDouble("combo-hold.gain"),
                "the combo-hold override rides the combo.yml root");
        SnapshotParser.Result result = SnapshotParser.parse(sources);
        assertTrue(result.issues().isEmpty(), () -> "unexpected issues: " + result.issues());
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

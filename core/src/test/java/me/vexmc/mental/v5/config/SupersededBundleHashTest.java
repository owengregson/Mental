package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import me.vexmc.mental.kernel.profile.SupersededPresets;
import org.junit.jupiter.api.Test;

/**
 * Pins the byte-identity archive behind the pristine preset upgrade
 * ({@link SupersededPresets#isSupersededBundleText}). Every historical bundled
 * revision is stored verbatim under {@code superseded-bundles/}; this test
 * recomputes each hash by asking the kernel to recognise the text (both LF and
 * CRLF), proves NO archived revision collides with a current bundle (the
 * self-upgrade-loop guard), and rejects garbage and unknown presets. Because the
 * texts and the kernel constants are pinned independently, a regenerated preset
 * — or a mistyped hash — can never drift silently.
 */
class SupersededBundleHashTest {

    /** Every hashed archive resource → its preset (the §1.3 enumeration, 29 forms). */
    private static final Map<String, String> ARCHIVE = archive();

    /** The current bundles — none may hash-match any archived revision. */
    private static final String[] CURRENT_PRESETS = {
            "legacy-1.7", "legacy-1.8", "kohi", "minehq", "badlion", "velt",
            "mmc", "lunar", "signature",
            "modern-vanilla", "modern-uplift", "modern-combo", "custom"
    };

    private static Map<String, String> archive() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("kohi@1.3.x-patched.yml", "kohi");
        map.put("kohi@1.4.0.yml", "kohi");
        map.put("kohi@1.5.0.yml", "kohi");
        map.put("kohi@1.8.0.yml", "kohi");
        map.put("mmc@1.3.x-patched.yml", "mmc");
        map.put("mmc@1.4.0.yml", "mmc");
        map.put("mmc@1.8.0.yml", "mmc");
        map.put("lunar@1.3.x-patched.yml", "lunar");
        map.put("lunar@1.4.0.yml", "lunar");
        map.put("lunar@1.5.0.yml", "lunar");
        map.put("lunar@1.8.0.yml", "lunar");
        map.put("minehq@1.8.0.yml", "minehq");
        map.put("badlion@1.8.0.yml", "badlion");
        map.put("signature@2.2.0.yml", "signature");
        map.put("signature@2.2.1.yml", "signature");
        map.put("signature@2.4.0.yml", "signature");
        map.put("legacy-1.7@1.3.x-patched.yml", "legacy-1.7");
        map.put("legacy-1.7@1.4.0.yml", "legacy-1.7");
        map.put("legacy-1.7@1.5.0.yml", "legacy-1.7");
        map.put("legacy-1.7@2.4.7.yml", "legacy-1.7");
        map.put("legacy-1.8@1.3.x-patched.yml", "legacy-1.8");
        map.put("legacy-1.8@1.4.0.yml", "legacy-1.8");
        map.put("legacy-1.8@1.5.0.yml", "legacy-1.8");
        map.put("legacy-1.8@2.4.7.yml", "legacy-1.8");
        map.put("custom@1.3.x.yml", "custom");
        map.put("custom@1.4.0.yml", "custom");
        map.put("custom@1.5.0.yml", "custom");
        map.put("custom@2.4.0.yml", "custom");
        map.put("custom@2.4.8.yml", "custom");
        return map;
    }

    private static String read(String classpath) {
        try (InputStream stream = SupersededBundleHashTest.class.getClassLoader()
                .getResourceAsStream(classpath)) {
            assertNotNull(stream, () -> "resource missing from the test classpath: " + classpath);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Test
    void everyArchivedRevisionIsRecognisedInLfAndCrlf() {
        ARCHIVE.forEach((resource, preset) -> {
            String text = read("superseded-bundles/" + resource);
            assertTrue(SupersededPresets.isSupersededBundleText(preset, text),
                    () -> resource + " must be recognised as a superseded " + preset + " revision");
            // Newline normalization pin: a Windows-checked-out (CRLF) file matches too.
            String crlf = text.replace("\n", "\r\n");
            assertTrue(SupersededPresets.isSupersededBundleText(preset, crlf),
                    () -> resource + " must still match after CRLF normalization");
        });
    }

    @Test
    void noArchivedRevisionCollidesWithACurrentBundle() {
        // The self-upgrade-loop guard: if any archived hash equalled a current
        // bundle, that bundle would upgrade to itself forever.
        for (String preset : CURRENT_PRESETS) {
            String current = read("profiles/" + ConfigStore.bundledFolder(preset) + "/" + preset + ".yml");
            assertFalse(SupersededPresets.isSupersededBundleText(preset, current),
                    () -> "the current " + preset + " bundle must NOT be a superseded revision");
        }
    }

    @Test
    void theRawPre14FormIsNotRecognisedUntilThePatcherRuns() {
        // kohi@1.3.x-raw is the unpatched 1.3.0 text; ConfigStore.ensureDeliverySection
        // patches it (inserting the delivery block) BEFORE the upgrade check, so only
        // the PATCHED form is archived. The raw text must not match on its own.
        String raw = read("superseded-bundles/kohi@1.3.x-raw.yml");
        assertFalse(SupersededPresets.isSupersededBundleText("kohi", raw),
                "the unpatched 1.3.0 kohi text must not match before the delivery patch");
    }

    @Test
    void garbageAndUnknownPresetsAreRejected() {
        assertFalse(SupersededPresets.isSupersededBundleText("kohi", "display-name: X\n"),
                "arbitrary text must never match");
        assertFalse(SupersededPresets.isSupersededBundleText("kohi", null),
                "null text must never match");
        // velt has no superseded revision (combos moot at 0.1 survival) — any text is
        // an owner edit; an unknown preset name likewise.
        assertFalse(SupersededPresets.isSupersededBundleText("velt",
                        read("profiles/" + ConfigStore.bundledFolder("velt") + "/velt.yml")),
                "velt carries no superseded revision");
        assertFalse(SupersededPresets.isSupersededBundleText("nope", "anything\n"),
                "an unknown preset must never match");
    }
}

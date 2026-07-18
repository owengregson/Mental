package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Pins the byte-identity archive behind the pristine Combat Effects preset
 * upgrade — the effects twin of {@link SupersededBundleHashTest}. The four
 * historical signature.yml revisions are stored verbatim under
 * {@code superseded-effects-bundles/} (extracted from the shipped tags); the
 * hashes in {@link SupersededEffectsPresets} were hand-computed when each round
 * shipped and were UNGUARDED until this test: recognition, no-collision,
 * custom-exemption, and set-completeness are asserted so a regenerated bundle —
 * or a mistyped hash — can never drift silently.
 */
class SupersededEffectsBundleHashTest {

    /** Every archived signature.yml revision → its preset stem (all {@code signature}). */
    private static final Map<String, String> ARCHIVE = archive();

    private static Map<String, String> archive() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("signature@2.5.3.yml", "signature");
        map.put("signature@2.5.5.yml", "signature");
        map.put("signature@2.6.2.yml", "signature");
        map.put("signature@2.7.0.yml", "signature");
        return map;
    }

    private static String read(String classpath) {
        try (InputStream stream = SupersededEffectsBundleHashTest.class.getClassLoader()
                .getResourceAsStream(classpath)) {
            assertNotNull(stream, () -> "resource missing from the test classpath: " + classpath);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String sha256Hex(String text) {
        try {
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @Test
    void everyArchivedRevisionIsRecognisedInLfAndCrlf() {
        ARCHIVE.forEach((resource, preset) -> {
            String text = read("superseded-effects-bundles/" + resource);
            assertTrue(SupersededEffectsPresets.isSupersededBundleText(preset, text),
                    () -> resource + " must be recognised as a superseded " + preset + " revision");
            // Newline normalization pin: a Windows-checked-out (CRLF) file matches too.
            String crlf = text.replace("\n", "\r\n");
            assertTrue(SupersededEffectsPresets.isSupersededBundleText(preset, crlf),
                    () -> resource + " must still match after CRLF normalization");
        });
    }

    @Test
    void noArchivedRevisionCollidesWithACurrentBundle() {
        // If any archived hash equalled a current bundle, that bundle would
        // upgrade to itself forever.
        for (String preset : ConfigStore.BUNDLED_EFFECTS_PRESETS) {
            String current = read("effects/presets/" + preset + ".yml");
            assertFalse(SupersededEffectsPresets.isSupersededBundleText(preset, current),
                    () -> "the current " + preset + " bundle must NOT be a superseded revision");
        }
    }

    @Test
    void customIsExemptWhateverItsBytes() {
        // The archive is keyed only by signature — custom is the owner's preset
        // by definition, even if its bytes matched an old signature.
        ARCHIVE.keySet().forEach(resource -> {
            String text = read("superseded-effects-bundles/" + resource);
            assertFalse(SupersededEffectsPresets.isSupersededBundleText("custom", text),
                    () -> "custom must never be upgraded, even matching " + resource);
        });
    }

    @Test
    void garbageUnknownAndRetiredPresetsAreRejected() {
        assertFalse(SupersededEffectsPresets.isSupersededBundleText("signature", "display-name: X\n"),
                "arbitrary text must never match");
        assertFalse(SupersededEffectsPresets.isSupersededBundleText("signature", null),
                "null text must never match");
        // vanilla left the bundle in 2.5.5 — its retirement is the 4 → 5
        // migration's job, never the upgrade driver's.
        assertFalse(SupersededEffectsPresets.isSupersededBundleText("vanilla",
                        read("superseded-effects-bundles/signature@2.5.3.yml")),
                "vanilla carries no superseded revision");
        assertFalse(SupersededEffectsPresets.isSupersededBundleText("nope", "anything\n"),
                "an unknown preset must never match");
    }

    @Test
    void everyArchivedHashHasItsTextInTheArchive() {
        // Recompute the archived texts' hashes with a test-local copy of the
        // normalization + digest and assert they EXACTLY equal the class's set:
        // a hash added without an archived text fails here, not just a text
        // without a hash.
        Set<String> recomputed = new TreeSet<>();
        ARCHIVE.keySet().forEach(resource ->
                recomputed.add(sha256Hex(read("superseded-effects-bundles/" + resource))));
        assertEquals(new TreeSet<>(SupersededEffectsPresets.archivedHashes("signature")), recomputed,
                "the archived signature texts must reproduce exactly the class's hash set");
        assertTrue(SupersededEffectsPresets.archivedHashes("custom").isEmpty(),
                "custom carries no archived hashes");
        assertTrue(SupersededEffectsPresets.archivedHashes("vanilla").isEmpty(),
                "the retired vanilla carries no archived hashes");
    }
}

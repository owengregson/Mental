package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The rules-bundle supersession contract. The archive is empty at 2.8.x (the
 * bundles ship for the first time here), so every input must be rejected — most
 * importantly, no CURRENT bundle may be recognised as superseded (that would make
 * it upgrade to itself forever the moment a hash is added). The null/garbage
 * rejection pins the guard so a future archived entry can never widen it.
 */
class SupersededBundlesTest {

    private static String resource(String classpath) {
        try (InputStream stream = SupersededBundlesTest.class.getClassLoader()
                .getResourceAsStream(classpath)) {
            assertNotNull(stream, () -> "missing test resource: " + classpath);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Test
    void noCurrentBundleIsRecognisedAsSuperseded() {
        // The self-upgrade-loop guard: a current bundle must never hash-match an
        // archived revision (there are none yet, and none may collide when added).
        for (String bundle : ConfigStore.BUNDLED_BUNDLES) {
            String current = resource("bundles/" + bundle + ".yml");
            assertFalse(SupersededBundles.isSupersededBundleText(bundle, current),
                    () -> "the current " + bundle + " bundle must NOT be a superseded revision");
        }
    }

    @Test
    void garbageNullAndUnknownBundlesAreRejected() {
        assertFalse(SupersededBundles.isSupersededBundleText("ct8c", "display-name: X\n"),
                "arbitrary text must never match");
        assertFalse(SupersededBundles.isSupersededBundleText("ct8c", null),
                "null text must never match");
        assertFalse(SupersededBundles.isSupersededBundleText("nope", "anything\n"),
                "an unknown bundle name must never match");
    }
}

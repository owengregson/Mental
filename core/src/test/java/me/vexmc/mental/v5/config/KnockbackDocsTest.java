package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import org.junit.jupiter.api.Test;

/**
 * Docs-cannot-drift: the public knockback vocabulary and its reference doc are
 * pinned together so neither can move without the other noticing.
 *
 * <p>The kernel {@link KnockbackProfile} record IS the schema (the checklist in
 * the {@code knockback-profiles} skill lists "docs/knockback-profiles.md" as a
 * required stop when adding a knob). This test reads the actual repo doc — no
 * copy, no resource — and asserts (a) every documented knob YAML key is present
 * and (b) the record still carries exactly the known component count, so adding
 * a knob to the schema fails here until the new key is documented and listed.</p>
 */
class KnockbackDocsTest {

    /**
     * The YAML knob keys the profile schema defines under {@code knockback:}
     * (what {@link ProfileParser#parse} reads). Every one must appear verbatim
     * in docs/knockback-profiles.md.
     */
    private static final List<String> KNOB_KEYS = List.of(
            "base",
            "vertical-mode",
            "extra",
            "wtap-extra",
            "friction",
            "limits",
            "air",
            "add",
            "range-reduction",
            "delivery",
            "melee",
            "projectile",
            "modifiers",
            "sprint",
            "combos",
            "armor-resistance",
            "shield-blocking-cancels",
            "speed-scaling",
            "exponent");

    /**
     * KnockbackProfile's components: 3 identity fields (name, displayName,
     * description) + 16 knob-bearing fields (the 16th is the {@code paceScaling}
     * knob family). A change here is a schema change — update {@link #KNOB_KEYS},
     * the doc, and this pin together.
     */
    private static final int EXPECTED_COMPONENTS = 19;

    private static String readDoc() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path doc = dir.resolve("docs/knockback-profiles.md");
            if (Files.isRegularFile(doc)) {
                try {
                    return Files.readString(doc, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return fail("docs/knockback-profiles.md not found walking up from "
                + Path.of("").toAbsolutePath());
    }

    @Test
    void everySchemaKnobIsDocumented() {
        String doc = readDoc();
        for (String key : KNOB_KEYS) {
            assertTrue(doc.contains(key),
                    "docs/knockback-profiles.md must document the '" + key
                            + "' knob (schema key present in KnockbackProfile) — the doc drifted from the schema.");
        }
    }

    @Test
    void schemaShapeIsPinnedToTheDocumentedVocabulary() {
        assertEquals(EXPECTED_COMPONENTS, KnockbackProfile.class.getRecordComponents().length,
                "KnockbackProfile gained or lost a component — a schema change. Document any new knob in "
                        + "docs/knockback-profiles.md, add its YAML key to KNOB_KEYS, and update this pin.");
    }
}

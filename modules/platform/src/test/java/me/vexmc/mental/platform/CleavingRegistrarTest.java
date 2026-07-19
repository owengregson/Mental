package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the Cleaving registrar's degrade contract. A unit run has no live server,
 * so the whole registry-access chain misses at {@code Bukkit.getServer()} — the
 * registrar must resolve to {@link Optional#empty()} with exactly one loud line
 * and never throw (mandate B10). The successful injection path is exercised by
 * the integration matrix on live 1.21.4 / 1.21.11, where the frozen enchantment
 * registry actually exists.
 */
class CleavingRegistrarTest {

    /**
     * The four component accessors + canonical constructor of the modern
     * {@code Enchantment} record shape, mirrored locally — the copy must be a
     * DISTINCT instance (the registry's {@code byValue}/{@code toId} maps are
     * identity-keyed and {@code register} throws on a duplicate value) carrying
     * equal components.
     */
    public record EnchantmentShaped(String description, int definition, List<String> exclusiveSet, Object effects) {}

    @Test
    void copyTemplateBuildsADistinctInstanceWithEqualComponents() {
        EnchantmentShaped template = new EnchantmentShaped("Cleaving", 3, List.of("mental"), new Object());
        Object copy = CleavingRegistrar.copyTemplate(template);

        assertTrue(copy instanceof EnchantmentShaped, "the copy re-enters the template's own type, was " + copy);
        assertTrue(copy != template, "the copy must be a distinct instance — identity-keyed registry maps");
        assertTrue(template.equals(copy), "the copy carries the template's exact components");
    }

    @Test
    void copyTemplateDegradesToNullOffThePreRecordShape() {
        // Below 1.21 Enchantment is a plain class without the record accessors —
        // the probe must miss and return null (the floor gate), never throw.
        assertTrue(CleavingRegistrar.copyTemplate(new Object()) == null,
                "a shape without the pinned accessors degrades to null");
    }

    @Test
    void degradesLoudAndEmptyWithoutALiveRegistry() {
        List<String> lines = new ArrayList<>();
        Optional<CleavingHandle> resolved = CleavingRegistrar.install(lines::add);

        assertFalse(resolved.isPresent(), "no live registry ⇒ no Cleaving handle");
        assertTrue(CleavingRegistrar.handle().isEmpty(), "the cached handle stays empty");
        assertTrue(lines.size() == 1 && lines.get(0).contains("Cleaving"),
                "exactly one loud degrade line mentioning Cleaving, was " + lines);
        assertTrue(CleavingRegistrar.describe().startsWith("cleaving="),
                "the boot-report description is exposed, was " + CleavingRegistrar.describe());
    }
}

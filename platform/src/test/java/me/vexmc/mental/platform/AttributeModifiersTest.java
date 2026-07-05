package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.junit.jupiter.api.Test;

/**
 * Runs against the 1.17 paper-api on the test classpath, where only the legacy
 * {@code (UUID, String, amount, Operation)} construction shape exists — exercising
 * that resolution branch and the by-identity sweep. The modern {@link
 * org.bukkit.NamespacedKey} branch is exercised by the integration matrix on 1.21+.
 */
class AttributeModifiersTest {

    @Test
    void amountIsTheMultiplyTotalScaleMinusOne() {
        // MULTIPLY_SCALAR_1 multiplies the total by (1 + amount); base × 0.8 needs -0.2.
        assertEquals(-0.2, AttributeModifiers.amountFor(0.8), 1e-12);
        assertEquals(-0.5, AttributeModifiers.amountFor(0.5), 1e-12);
        assertEquals(0.0, AttributeModifiers.amountFor(1.0), 1e-12);
    }

    @Test
    void comboReachCarriesTheFixedIdentityAndMultiplyTotalOperation() {
        assertTrue(AttributeModifiers.supported(), "a construction shape resolves on the floor API");
        AttributeModifier modifier = AttributeModifiers.comboReach(0.8);
        assertNotNull(modifier, "the legacy ctor builds the modifier");
        assertEquals(AttributeModifier.Operation.MULTIPLY_SCALAR_1, modifier.getOperation());
        assertEquals(-0.2, modifier.getAmount(), 1e-12);
        assertEquals("mental:combo-reach", modifier.getName(), "the fixed legacy identity name");
        assertEquals(
                UUID.nameUUIDFromBytes("mental:combo-reach".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                modifier.getUniqueId(),
                "the deterministic legacy identity uuid — stable across restarts");
    }

    @Test
    void removeMatchingStripsOnlyOurModifierAndIsIdempotent() {
        FakeInstance instance = new FakeInstance();
        AttributeModifier foreign = new AttributeModifier(
                UUID.randomUUID(), "some-other-plugin", 2.0, AttributeModifier.Operation.ADD_NUMBER);
        instance.addModifier(foreign);
        instance.addModifier(AttributeModifiers.comboReach(0.8));

        assertTrue(AttributeModifiers.removeMatching(instance), "our modifier was present and removed");
        assertEquals(List.of(foreign), new ArrayList<>(instance.getModifiers()),
                "only the foreign modifier survives");

        // A second sweep is a no-op — idempotent by identity.
        assertFalse(AttributeModifiers.removeMatching(instance), "nothing left of ours to remove");
    }

    @Test
    void removeMatchingSweepsAStaleLeakedModifier() {
        // A crash-leaked modifier carries the SAME deterministic identity, so a fresh
        // instance that never saw our apply still gets swept clean (the NBT-leak guard).
        FakeInstance instance = new FakeInstance();
        instance.addModifier(AttributeModifiers.comboReach(0.6));
        assertTrue(AttributeModifiers.removeMatching(instance));
        assertTrue(instance.getModifiers().isEmpty());
    }

    /** A minimal in-memory {@link AttributeInstance} — the floor API surface only. */
    private static final class FakeInstance implements AttributeInstance {
        private final List<AttributeModifier> modifiers = new ArrayList<>();
        private double base = 3.0;

        @Override public Attribute getAttribute() {
            return Attribute.GENERIC_ATTACK_DAMAGE;
        }

        @Override public double getBaseValue() {
            return base;
        }

        @Override public void setBaseValue(double value) {
            this.base = value;
        }

        @Override public Collection<AttributeModifier> getModifiers() {
            return new ArrayList<>(modifiers);
        }

        @Override public void addModifier(AttributeModifier modifier) {
            modifiers.add(modifier);
        }

        @Override public void removeModifier(AttributeModifier modifier) {
            modifiers.remove(modifier);
        }

        @Override public double getDefaultValue() {
            return 3.0;
        }

        @Override public double getValue() {
            double value = base;
            for (AttributeModifier modifier : modifiers) {
                if (modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1) {
                    value *= (1.0 + modifier.getAmount());
                }
            }
            return value;
        }
    }
}

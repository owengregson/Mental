package me.vexmc.mental.v5.preset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.config.ConfigStore;
import org.junit.jupiter.api.Test;

/**
 * The kind enum's frozen wiring constants and renderable metadata. The overlay
 * keys, default names, and bundled lists must stay the single sources of truth
 * (Overlay routing / Management / ConfigStore); the display metadata must be
 * renderable copy.
 */
class PresetKindTest {

    @Test
    void overlayKeysAreTheFrozenSelectionKeys() {
        assertEquals("knockback.profile", PresetKind.KNOCKBACK.overlayKey());
        assertEquals("effects.preset", PresetKind.EFFECTS.overlayKey());
    }

    @Test
    void defaultNamesAreTheShippedDefaults() {
        assertEquals(KnockbackProfile.LEGACY_17.name(), PresetKind.KNOCKBACK.defaultName());
        assertEquals("legacy-1.7", PresetKind.KNOCKBACK.defaultName());
        assertEquals("signature", PresetKind.EFFECTS.defaultName());
    }

    @Test
    void bundledNamesMirrorTheConfigStoreBundleLists() {
        assertSame(ConfigStore.BUNDLED_PROFILES, PresetKind.KNOCKBACK.bundledNames());
        assertSame(ConfigStore.BUNDLED_EFFECTS_PRESETS, PresetKind.EFFECTS.bundledNames());
    }

    @Test
    void displayMetadataIsRenderable() {
        for (PresetKind kind : PresetKind.values()) {
            assertFalse(kind.displayName().isBlank());
            assertFalse(kind.iconName().isBlank());
            assertFalse(kind.blurb().isBlank());
        }
    }
}

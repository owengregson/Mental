package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Name resolution is a pure decision against an injected (major, minor,
 * inlineByName) tuple, so it is pinned without a live server. {@code inlineByName}
 * is the 1.19.3+ "sounds ship as inline holders" gate: below it an unknown custom
 * name has no trustworthy id and must resolve to "" (skip), while a floored name
 * always resolves against its era floor regardless of the gate.
 */
class FeedbackSoundTableTest {

    @Test
    void knownNameIsIdentityOnAModernServer() {
        FeedbackSoundTable modern = new FeedbackSoundTable(1, 20, true);
        assertEquals("block.lodestone.break", modern.resolve("block.lodestone.break"));
    }

    @Test
    void lodestoneFallsBackToStoneBelowItsFloor() {
        FeedbackSoundTable era1_12 = new FeedbackSoundTable(1, 12, false);
        assertEquals("block.stone.break", era1_12.resolve("block.lodestone.break"));
    }

    @Test
    void breezeDeflectFallsBackToShieldBlockBelow1Point21() {
        FeedbackSoundTable era1_20 = new FeedbackSoundTable(1, 20, true);
        assertEquals("item.shield.block", era1_20.resolve("entity.breeze.deflect"));
    }

    @Test
    void glowSquidFallsBackToSquidBelow1Point17() {
        FeedbackSoundTable era1_16 = new FeedbackSoundTable(1, 16, false);
        assertEquals("entity.squid.hurt", era1_16.resolve("entity.glow_squid.hurt"));
        assertEquals("entity.squid.death", era1_16.resolve("entity.glow_squid.death"));
    }

    @Test
    void glowSquidIsIdentityAtOrAboveItsFloor() {
        FeedbackSoundTable era1_17 = new FeedbackSoundTable(1, 17, false);
        assertEquals("entity.glow_squid.hurt", era1_17.resolve("entity.glow_squid.hurt"));
    }

    @Test
    void unknownCustomNameSkipsBelow1Point19Point3() {
        FeedbackSoundTable era1_12 = new FeedbackSoundTable(1, 12, false);
        assertEquals("", era1_12.resolve("mymod.custom.chime"));
    }

    @Test
    void unknownCustomNameInlinesAtOrAbove1Point19Point3() {
        FeedbackSoundTable inline = new FeedbackSoundTable(1, 20, true);
        assertEquals("mymod.custom.chime", inline.resolve("mymod.custom.chime"));
    }

    @Test
    void universalNameSurvivesEvenBelow1Point19Point3() {
        FeedbackSoundTable era1_12 = new FeedbackSoundTable(1, 12, false);
        assertEquals("entity.player.hurt", era1_12.resolve("entity.player.hurt"));
        assertEquals("entity.squid.hurt", era1_12.resolve("entity.squid.hurt"));
    }

    @Test
    void bukkitStyleNameNormalizes() {
        FeedbackSoundTable modern = new FeedbackSoundTable(1, 20, true);
        assertEquals("block.lodestone.break", modern.resolve("BLOCK_LODESTONE_BREAK"));
    }

    @Test
    void minecraftPrefixIsStripped() {
        FeedbackSoundTable modern = new FeedbackSoundTable(1, 20, true);
        assertEquals("entity.player.hurt", modern.resolve("minecraft:entity.player.hurt"));
    }

    @Test
    void yearSchemeServerCountsAsNewest() {
        FeedbackSoundTable year26 = new FeedbackSoundTable(26, 1, true);
        assertEquals("block.lodestone.break", year26.resolve("block.lodestone.break"));
        assertEquals("entity.breeze.deflect", year26.resolve("entity.breeze.deflect"));
    }
}

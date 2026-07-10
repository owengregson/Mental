package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the two wire contracts of {@code death-effects} that no fake-player suite
 * can observe (fakes carry no PacketEvents user): the cosmetic lightning spawn
 * wrapper carries a {@code LIGHTNING_BOLT} at the death location, and the signature
 * dust hexes map to the exact {@link ParticleDustData} components. The pure
 * hex→channel parse is pinned separately on {@link DustColor} — no PacketEvents at
 * all — because it is trivially unit-testable in isolation.
 *
 * <p>PacketEvents' {@link WrapperPlayServerSpawnEntity} constructor reads the server
 * version off the API (to resolve the packet id), so a minimal stub API supplying a
 * modern {@link ServerVersion} is installed for the wrapper build — the same posture
 * as {@code PacketTapStateTest}. {@link ParticleDustData} needs no API (pure field
 * assignment), so the dust assertions stand on their own.</p>
 */
class DeathEffectsPacketsTest {

    private static final float EPSILON = 1.0e-6f;

    @BeforeAll
    static void installStubApi() {
        NettyManager nettyManager = new NettyManagerImpl();
        ServerManager serverManager = () -> ServerVersion.V_1_21; // getVersion() is the one abstract method
        PacketEvents.setAPI(new PacketEventsAPI<>() {
            @Override public void load() {}
            @Override public boolean isLoaded() { return true; }
            @Override public void init() {}
            @Override public boolean isInitialized() { return true; }
            @Override public void terminate() {}
            @Override public boolean isTerminated() { return false; }
            @Override public Object getPlugin() { return null; }
            @Override public ServerManager getServerManager() { return serverManager; }
            @Override public ProtocolManager getProtocolManager() { return null; }
            @Override public PlayerManager getPlayerManager() { return null; }
            @Override public NettyManager getNettyManager() { return nettyManager; }
            @Override public ChannelInjector getInjector() { return null; }
        });
    }

    // ---------------------------------------------------------------------
    // The pure hex→channel parse (no PacketEvents).
    // ---------------------------------------------------------------------

    @Test
    void dustColorParsesEachChannelFromHex() {
        assertEquals(new DustColor(255, 170, 0), DustColor.of("ffaa00"), "gold &6");
        assertEquals(new DustColor(255, 255, 255), DustColor.of("ffffff"), "white &f");
        assertEquals(new DustColor(255, 255, 85), DustColor.of("ffff55"), "yellow &e");
    }

    @Test
    void dustColorToleratesHashPrefixAndCase() {
        assertEquals(new DustColor(255, 170, 0), DustColor.of("#FFAA00"));
    }

    @Test
    void dustColorValidityRejectsMalformedHex() {
        assertTrue(DustColor.isValid("ffaa00"));
        assertTrue(DustColor.isValid("#ffaa00"));
        assertFalse(DustColor.isValid("fff"), "too short");
        assertFalse(DustColor.isValid("gggggg"), "non-hex digits");
        assertFalse(DustColor.isValid(""));
        assertFalse(DustColor.isValid(null));
    }

    // ---------------------------------------------------------------------
    // The dust-hex → ParticleDustData wire mapping (the runtime's own helper).
    // ---------------------------------------------------------------------

    @Test
    void goldHexMapsToExactDustComponents() {
        ParticleDustData dust = DeathEffectsListener.dustData("ffaa00");
        assertEquals(1.0f, dust.getScale(), EPSILON, "unit dust scale");
        assertEquals(1.0f, dust.getRed(), EPSILON);
        assertEquals(170.0f / 255.0f, dust.getGreen(), EPSILON, "0xAA rides as 170/255 ≈ 0.667");
        assertEquals(0.0f, dust.getBlue(), EPSILON);
    }

    @Test
    void whiteHexMapsToFullChannels() {
        ParticleDustData dust = DeathEffectsListener.dustData("ffffff");
        assertEquals(1.0f, dust.getRed(), EPSILON);
        assertEquals(1.0f, dust.getGreen(), EPSILON);
        assertEquals(1.0f, dust.getBlue(), EPSILON);
    }

    @Test
    void yellowHexMapsWithTheLowBlueChannel() {
        ParticleDustData dust = DeathEffectsListener.dustData("ffff55");
        assertEquals(1.0f, dust.getRed(), EPSILON);
        assertEquals(1.0f, dust.getGreen(), EPSILON);
        assertEquals(85.0f / 255.0f, dust.getBlue(), EPSILON, "0x55 rides as 85/255 ≈ 0.333");
    }

    // ---------------------------------------------------------------------
    // The cosmetic lightning spawn wrapper.
    // ---------------------------------------------------------------------

    @Test
    void lightningSpawnCarriesLightningBoltAtTheDeathLocation() {
        int boltId = 424242;
        Vector3d death = new Vector3d(12.5, 64.0, -33.25);
        WrapperPlayServerSpawnEntity spawn = DeathEffectsListener.lightningSpawn(boltId, death);

        assertEquals(boltId, spawn.getEntityId(), "the shared cosmetic-bolt id");
        assertSame(EntityTypes.LIGHTNING_BOLT, spawn.getEntityType(), "a client-only lightning render");
        assertEquals(death, spawn.getPosition(), "spawned at the frozen death location");
    }
}

package me.vexmc.mental.v5.feature.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings.GlowColor;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-player glow wire — the one contract no fake-player suite can see:
 * the "glowing" base-flags byte ({@code 0x40} on, {@code 0x00} off) and the
 * named team colour for each {@link GlowColor}. A minimal stub API supplies a
 * modern {@link ServerVersion} so the wrappers build, mirroring
 * {@code DeathEffectsPacketsTest}.
 */
class GlowPacketsTest {

    @BeforeAll
    static void installStubApi() {
        NettyManager nettyManager = new NettyManagerImpl();
        ServerManager serverManager = () -> ServerVersion.V_1_21;
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

    @Test
    void glowingMetadataSetsTheBaseFlagsGlowBit() {
        WrapperPlayServerEntityMetadata on = GlowPackets.glowingMetadata(42, true);
        EntityData<?> flags = on.getEntityMetadata().get(0);
        assertEquals(0, flags.getIndex(), "the base-flags byte is metadata index 0");
        assertEquals((byte) 0x40, flags.getValue(), "0x40 is the glowing bit");
    }

    @Test
    void unglowMetadataClearsTheFlags() {
        WrapperPlayServerEntityMetadata off = GlowPackets.glowingMetadata(42, false);
        assertEquals((byte) 0x00, off.getEntityMetadata().get(0).getValue(), "cleared flags un-glow");
    }

    @Test
    void eachGlowColorMapsToItsNamedTeamColor() {
        assertSame(NamedTextColor.GOLD, GlowPackets.namedColor(GlowColor.GOLD));
        assertSame(NamedTextColor.YELLOW, GlowPackets.namedColor(GlowColor.YELLOW));
    }

    @Test
    void theTwoColorsUseDistinctTeamNames() {
        assertNotEquals(GlowPackets.teamName(GlowColor.GOLD), GlowPackets.teamName(GlowColor.YELLOW),
                "a colour change is a distinct team so the old-coloured entry never lingers");
    }
}

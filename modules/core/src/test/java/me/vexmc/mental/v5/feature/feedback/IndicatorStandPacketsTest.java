package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.vexmc.mental.kernel.fx.IndicatorBallistics;
import me.vexmc.mental.kernel.fx.IndicatorPlacement;
import me.vexmc.mental.v5.text.TextPort;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code damage-indicators} wire contract that no fake-player suite can
 * observe (fakes carry no PacketEvents user): the invisible marker armor stand's
 * spawn/metadata/destroy triple, the per-client-version metadata shapes (the
 * component name on 1.13+ clients vs the §-string below, and the armor-stand
 * status byte's index band), and the relative-move deltas the driver ships each
 * tick.
 *
 * <p>The status-byte bands were VERIFIED against the actual servers (DataWatcher
 * registration counts of Entity + EntityLiving via javap on the cached jars, per
 * {@code nms-archaeology}): 10 on 1.9.x, 11 on 1.10–1.13.2, <b>13 on 1.14.x</b>
 * (bee stingers only land in 1.15 — the plan's single 1.14–1.16.5 band was
 * wrong), 14 on 1.15–1.16.5, 15 on 1.17+.</p>
 *
 * <p>PacketEvents' wrapper constructors read the server version off the API (to
 * resolve packet ids), so the minimal stub API from {@code PacketTapStateTest} /
 * {@code DeathEffectsPacketsTest} is installed for the wrapper builds.</p>
 */
class IndicatorStandPacketsTest {

    private static final int ENTITY_ID = 777;
    private static final UUID STAND_UUID = UUID.fromString("00000000-0000-4000-8000-000000000042");
    private static final String RAW_TEMPLATE = "&f-2.5 &c❤&r";

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

    private static Component name() {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(RAW_TEMPLATE);
    }

    // ---------------------------------------------------------------------
    // The modern-client spawn/metadata pair.
    // ---------------------------------------------------------------------

    @Test
    void modernClientSpawnCarriesAnArmorStandBelowTheTextPosition() {
        IndicatorPlacement.Spawn at = new IndicatorPlacement.Spawn(10.5, 65.0, -3.25, 0.0);
        List<PacketWrapper<?>> packets = IndicatorStandPackets.spawn(
                ENTITY_ID, STAND_UUID, at, name(), ClientVersion.V_1_21, true);

        assertEquals(2, packets.size(), "spawn + metadata");
        WrapperPlayServerSpawnEntity spawn =
                assertInstanceOf(WrapperPlayServerSpawnEntity.class, packets.get(0), "server 1.19+ spawn");
        assertEquals(ENTITY_ID, spawn.getEntityId());
        assertSame(EntityTypes.ARMOR_STAND, spawn.getEntityType());
        assertEquals(
                new Vector3d(10.5, 65.0 - IndicatorStandPackets.NAMEPLATE_OFFSET, -3.25),
                spawn.getPosition(),
                "the driven stand rides one nameplate offset below the text");
    }

    @Test
    void modernClientMetadataCarriesTheInvisibleNamedMarkerStand() {
        IndicatorPlacement.Spawn at = new IndicatorPlacement.Spawn(10.5, 65.0, -3.25, 0.0);
        Component name = name();
        List<PacketWrapper<?>> packets = IndicatorStandPackets.spawn(
                ENTITY_ID, STAND_UUID, at, name, ClientVersion.V_1_21, true);

        WrapperPlayServerEntityMetadata metadata =
                assertInstanceOf(WrapperPlayServerEntityMetadata.class, packets.get(1));
        assertEquals(ENTITY_ID, metadata.getEntityId());
        assertEquals((byte) 0x20, valueAt(metadata, 0), "entity flags: invisible");
        assertEquals(Boolean.TRUE, valueAt(metadata, 3), "custom name visible");
        assertEquals((byte) 0x11, valueAt(metadata, 15), "1.17+ stand byte: marker | small");

        Object nameValue = valueAt(metadata, 2);
        assertInstanceOf(Optional.class, nameValue, "1.13+ clients take an optional component name");
        Component carried = (Component) ((Optional<?>) nameValue).orElseThrow();
        assertEquals(TextPort.legacy(name), TextPort.legacy(carried), "the rendered template rides the name");
    }

    @Test
    void legacyClientMetadataFallsBackToStringNameAndItsEraIndex() {
        IndicatorPlacement.Spawn at = new IndicatorPlacement.Spawn(0.0, 64.0, 0.0, 0.0);
        Component name = name();
        List<PacketWrapper<?>> packets = IndicatorStandPackets.spawn(
                ENTITY_ID, STAND_UUID, at, name, ClientVersion.V_1_12_2, true);

        WrapperPlayServerEntityMetadata metadata =
                assertInstanceOf(WrapperPlayServerEntityMetadata.class, packets.get(1));
        assertEquals(TextPort.legacy(name), valueAt(metadata, 2), "pre-1.13 clients take a §-string name");
        assertEquals((byte) 0x11, valueAt(metadata, 11), "the 1.10–1.13.2 stand byte index");
    }

    // ---------------------------------------------------------------------
    // The verified status-byte band table.
    // ---------------------------------------------------------------------

    @Test
    void standFlagsIndexFollowsTheVerifiedBands() {
        assertEquals(10, IndicatorStandPackets.standFlagsIndex(ClientVersion.V_1_9));
        assertEquals(10, IndicatorStandPackets.standFlagsIndex(ClientVersion.V_1_9_3));
        assertEquals(11, IndicatorStandPackets.standFlagsIndex(ClientVersion.V_1_10));
        assertEquals(11, IndicatorStandPackets.standFlagsIndex(ClientVersion.V_1_12_2));
        assertEquals(11, IndicatorStandPackets.standFlagsIndex(ClientVersion.V_1_13_2));
        assertEquals(13, IndicatorStandPackets.standFlagsIndex(ClientVersion.V_1_14), "1.14 has no bee stingers yet");
        assertEquals(13, IndicatorStandPackets.standFlagsIndex(ClientVersion.V_1_14_4));
        assertEquals(14, IndicatorStandPackets.standFlagsIndex(ClientVersion.V_1_15));
        assertEquals(14, IndicatorStandPackets.standFlagsIndex(ClientVersion.V_1_16_4));
        assertEquals(15, IndicatorStandPackets.standFlagsIndex(ClientVersion.V_1_17));
        assertEquals(15, IndicatorStandPackets.standFlagsIndex(ClientVersion.V_1_21));
    }

    // ---------------------------------------------------------------------
    // The per-tick relative move and the despawn.
    // ---------------------------------------------------------------------

    @Test
    void moveShipsTheStepDeltas() {
        IndicatorBallistics.State from = new IndicatorBallistics.State(10.0, 65.0, -3.0, 0.0, 0.0, 0.0);
        IndicatorBallistics.State to = new IndicatorBallistics.State(10.06, 65.25, -3.02, 0.0, 0.0, 0.0);
        WrapperPlayServerEntityRelativeMove move =
                assertInstanceOf(WrapperPlayServerEntityRelativeMove.class,
                        IndicatorStandPackets.move(ENTITY_ID, from, to));

        assertEquals(ENTITY_ID, move.getEntityId());
        assertEquals(0.06, move.getDeltaX(), 1.0e-9);
        assertEquals(0.25, move.getDeltaY(), 1.0e-9);
        assertEquals(-0.02, move.getDeltaZ(), 1.0e-9);
    }

    @Test
    void destroyCarriesTheStandId() {
        WrapperPlayServerDestroyEntities destroy =
                assertInstanceOf(WrapperPlayServerDestroyEntities.class,
                        IndicatorStandPackets.destroy(ENTITY_ID));
        assertArrayEquals(new int[] {ENTITY_ID}, destroy.getEntityIds());
    }

    // ---------------------------------------------------------------------

    private static Object valueAt(WrapperPlayServerEntityMetadata metadata, int index) {
        for (EntityData<?> data : metadata.getEntityMetadata()) {
            if (data.getIndex() == index) {
                assertNotNull(data.getValue(), "metadata value at index " + index);
                return data.getValue();
            }
        }
        assertTrue(false, "no metadata entry at index " + index);
        return null;
    }
}

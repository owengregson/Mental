package me.vexmc.mental.module.compensation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import java.util.UUID;
import java.util.stream.Stream;
import me.vexmc.mental.config.ProbeStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the connection-state contract that broke on real 26.1.2 joins:
 * PacketEvents calls listeners for EVERY state, so a joining client's
 * handshake/login/configuration packets (and server-list status pings)
 * reach {@link ProbeListener} before the channel ever enters Play.
 *
 * <p>The original code downcast {@code getPacketType()} to
 * {@code PacketType.Play.Client} and threw a {@code ClassCastException}
 * per pre-Play packet. Fake-player suites can never reproduce this —
 * they inject directly in Play state — hence this unit pin.</p>
 */
class ProbeListenerStateTest {

    /**
     * Event construction consults the global API: re-encode settings
     * directly, and a real netty manager indirectly ({@link User} init
     * class-loads registries whose definitions allocate buffers).
     */
    @BeforeAll
    static void installStubApi() {
        NettyManager nettyManager = new NettyManagerImpl();
        PacketEvents.setAPI(new PacketEventsAPI<>() {
            @Override public void load() {}
            @Override public boolean isLoaded() { return true; }
            @Override public void init() {}
            @Override public boolean isInitialized() { return true; }
            @Override public void terminate() {}
            @Override public boolean isTerminated() { return false; }
            @Override public Object getPlugin() { return null; }
            @Override public ServerManager getServerManager() { return null; }
            @Override public ProtocolManager getProtocolManager() { return null; }
            @Override public PlayerManager getPlayerManager() { return null; }
            @Override public NettyManager getNettyManager() { return nettyManager; }
            @Override public ChannelInjector getInjector() { return null; }
        });
    }

    static Stream<Arguments> prePlayPackets() {
        return Stream.of(
                Arguments.of(ConnectionState.HANDSHAKING, PacketType.Handshaking.Client.HANDSHAKE),
                Arguments.of(ConnectionState.STATUS, PacketType.Status.Client.PING),
                Arguments.of(ConnectionState.LOGIN, PacketType.Login.Client.LOGIN_START),
                Arguments.of(ConnectionState.CONFIGURATION, PacketType.Configuration.Client.KEEP_ALIVE),
                Arguments.of(ConnectionState.CONFIGURATION, PacketType.Configuration.Client.PONG));
    }

    /**
     * Configuration KEEP_ALIVE/PONG are the sharp edge: same packet names
     * as the Play responses the probe matches, different state. Cancelling
     * one would time the client out mid-(re)configuration, so the listener
     * must ignore them entirely — reference identity, never name or id.
     */
    @ParameterizedTest
    @MethodSource("prePlayPackets")
    void prePlayPacketsFlowThroughUntouched(ConnectionState state, PacketTypeCommon type) throws Exception {
        ProbeListener listener = new ProbeListener(new LatencyTracker(), ProbeStrategy.PING);
        PacketReceiveEvent event = receiveEvent(state, type);

        assertDoesNotThrow(() -> listener.onPacketReceive(event));
        assertFalse(event.isCancelled(), "pre-Play traffic must never be cancelled");
    }

    /** Play responses can arrive before the Bukkit player attaches to the channel. */
    @ParameterizedTest
    @MethodSource("playResponseTypes")
    void playResponsesWithoutBukkitPlayerAreIgnored(PacketTypeCommon type) throws Exception {
        ProbeListener listener = new ProbeListener(new LatencyTracker(), ProbeStrategy.PING);
        PacketReceiveEvent event = receiveEvent(ConnectionState.PLAY, type);

        assertDoesNotThrow(() -> listener.onPacketReceive(event));
        assertFalse(event.isCancelled());
    }

    static Stream<PacketTypeCommon> playResponseTypes() {
        return Stream.of(PacketType.Play.Client.KEEP_ALIVE, PacketType.Play.Client.PONG);
    }

    /** Mirrors the decoder path: a serverbound packet of the given type, no Bukkit player yet. */
    private static PacketReceiveEvent receiveEvent(ConnectionState state, PacketTypeCommon type)
            throws Exception {
        User user = new User(null, state, ClientVersion.UNKNOWN,
                new UserProfile(UUID.randomUUID(), "joining"));
        return new PacketReceiveEvent(0, type, ServerVersion.V_1_21, null, user, null, null) {};
    }
}

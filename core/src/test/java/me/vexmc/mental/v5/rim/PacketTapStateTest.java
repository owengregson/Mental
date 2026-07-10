package me.vexmc.mental.v5.rim;

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
import me.vexmc.mental.kernel.model.LedgerEvent;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.v5.session.SessionAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the connection-state contract for the movement/sprint tap (the 1.3.0
 * regression, ported technique from {@code ProbeListenerStateTest}). PacketEvents
 * calls listeners for EVERY state, so a joining client's handshake/login/
 * configuration packets reach {@link PacketTap} before Play. The tap must compare
 * types by reference and never downcast pre-Play traffic — fake-player suites
 * can't reproduce this (they inject in Play state), hence this unit pin.
 */
class PacketTapStateTest {

    private static final TickClock CLOCK = () -> TickStamp.NO_TICK;

    /** A session access that records nothing and never yields a view — pre-Play never touches it. */
    private static final SessionAccess NO_SESSIONS = new SessionAccess() {
        @Override public PlayerView viewOf(UUID id) {
            throw new AssertionError("pre-Play traffic must not reach the session domain");
        }
        @Override public void enqueue(UUID id, LedgerEvent event) {
            throw new AssertionError("pre-Play traffic must not enqueue a ledger event");
        }
    };

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
     * A pre-Play packet must never match the Play movement/entity-action guards,
     * never be downcast to a movement wrapper, and never be cancelled — the tap is
     * observation-only and downcasting would throw a CCE per join packet.
     */
    @ParameterizedTest
    @MethodSource("prePlayPackets")
    void prePlayPacketsFlowThroughUntouched(ConnectionState state, PacketTypeCommon type) {
        PacketTap tap = new PacketTap(new ConnectionDomains(CLOCK), NO_SESSIONS, CLOCK);
        PacketReceiveEvent event = receiveEvent(state, type);

        assertDoesNotThrow(() -> tap.onPacketReceive(event));
        assertFalse(event.isCancelled(), "pre-Play traffic must never be cancelled");
    }

    /**
     * The PLAYER_INPUT sprint-key feed (FIX 3): a PLAY-state PLAYER_INPUT is routed to
     * the new corroborator branch (never the movement branch — its buffer is unreadable
     * here, so a mis-route to {@code onMovement} would throw before NO_SESSIONS), and
     * the defensive wrapper parse is swallowed so the observation tap never breaks the
     * inbound pipeline. Pins that {@code PacketType.Play.Client.PLAYER_INPUT} resolves
     * in the shaded PacketEvents and is handled zero-touch; the {@code onKeyIntent}
     * state semantics are pinned by {@code SprintWireTest}.
     */
    @org.junit.jupiter.api.Test
    void playerInputInPlayStateIsHandledAndNeverBreaksThePipeline() {
        PacketTap tap = new PacketTap(new ConnectionDomains(CLOCK), NO_SESSIONS, CLOCK);
        PacketReceiveEvent event = receiveEvent(ConnectionState.PLAY, PacketType.Play.Client.PLAYER_INPUT);

        assertDoesNotThrow(() -> tap.onPacketReceive(event));
        assertFalse(event.isCancelled(), "an observation tap never cancels PLAYER_INPUT");
    }

    private static PacketReceiveEvent receiveEvent(ConnectionState state, PacketTypeCommon type) {
        User user = new User(null, state, ClientVersion.UNKNOWN,
                new UserProfile(UUID.randomUUID(), "joining"));
        return new PacketReceiveEvent(0, type, ServerVersion.V_1_21, null, user, null, null) {};
    }
}

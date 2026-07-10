package me.vexmc.mental.v5.rim;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import java.util.UUID;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.model.LedgerEvent;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.wire.GroundFsm;
import me.vexmc.mental.v5.rim.ConnectionDomains.Domain;
import me.vexmc.mental.v5.session.SessionAccess;

/**
 * The inbound movement/sprint tap (spec §4, §6) — the era movement-packet
 * bookkeeping, replayed on the victim's own netty thread in arrival order. It
 * parses client movement into {@link GroundFsm} events (jump stamps, landings)
 * enqueued onto the victim's session inbox, and feeds START/STOP_SPRINTING into
 * the connection's {@link me.vexmc.mental.kernel.wire.SprintWire}. Registered at
 * MONITOR-equivalent priority: it observes after every other listener and never
 * cancels or mutates — an observation tap must never break the inbound pipeline.
 *
 * <p>Netty discipline: packet types are compared by REFERENCE against the
 * {@code Play.Client} constants (this listener fires for every connection state,
 * so a pre-Play packet must never be downcast — the 1.3.0 regression pinned by
 * {@code PacketTapStateTest}); identity is the PacketEvents {@link User}'s UUID,
 * never a live entity; the tick comes from the {@link TickClock} abstraction,
 * never {@code Bukkit.getCurrentTick} directly.</p>
 */
public final class PacketTap extends PacketListenerAbstract {

    /** Re-seed the wire from the published server flag after this many quiet ticks. */
    private static final int SPRINT_WIRE_QUIET_TICKS = 3;

    private final ConnectionDomains domains;
    private final SessionAccess sessions;
    private final TickClock clock;

    public PacketTap(ConnectionDomains domains, SessionAccess sessions, TickClock clock) {
        super(PacketListenerPriority.MONITOR);
        this.domains = domains;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        try {
            PacketTypeCommon type = event.getPacketType();
            boolean movement = type == PacketType.Play.Client.PLAYER_POSITION
                    || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                    || type == PacketType.Play.Client.PLAYER_ROTATION
                    || type == PacketType.Play.Client.PLAYER_FLYING;
            if (movement) {
                onMovement(event);
            } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
                onEntityAction(event);
            } else if (type == PacketType.Play.Client.PLAYER_INPUT) {
                onPlayerInput(event);
            }
        } catch (Throwable ignored) {
            // An observation tap must never break the inbound pipeline.
        }
    }

    private void onMovement(PacketReceiveEvent event) {
        UUID id = userId(event);
        if (id == null) {
            return;
        }
        WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
        Domain domain = domains.domainFor(id);
        if (packet.hasRotationChanged()) {
            domain.lastYaw((float) packet.getLocation().getYaw());
        }
        PlayerView view = sessions.viewOf(id);
        if (view != null) {
            // Re-seed the wire from the published server sprint flag only after
            // wire silence — a fresh wire write always wins its within-tick window
            // (netty reads the frozen view, never a live entity).
            domain.sprint().reconcile(view.sprinting(), clock.current(), SPRINT_WIRE_QUIET_TICKS);
        }
        boolean hasPosition = packet.hasPositionChanged();
        double y = hasPosition ? packet.getLocation().getY() : 0.0;
        LedgerEvent produced = domain.ground().onMovement(
                packet.isOnGround(), hasPosition, y, viewSlice(view, domain));
        if (produced != null) {
            sessions.enqueue(id, produced);
        }
    }

    private void onEntityAction(PacketReceiveEvent event) {
        UUID id = userId(event);
        if (id == null) {
            return;
        }
        WrapperPlayClientEntityAction packet = new WrapperPlayClientEntityAction(event);
        Domain domain = domains.domainFor(id);
        if (packet.getAction() == WrapperPlayClientEntityAction.Action.START_SPRINTING) {
            domain.sprint().onSprintStart();
            domain.resetModel().onSprintStart(); // the dynamic-chase ramp restarts on a (re-)engage
        } else if (packet.getAction() == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
            domain.sprint().onSprintStop();
            domain.resetModel().onSprintStop();
        }
    }

    /**
     * The PLAYER_INPUT sprint-KEY feed (1.21.2+ only). PacketEvents dispatches this
     * packet type only for protocol clients that carry the input-flags byte; a
     * pre-1.21.2 / legacy-via-Via client sends STEER_VEHICLE (a different
     * {@link PacketTypeCommon} that never matches the by-REFERENCE check above), so it
     * simply feeds nothing and the wire's {@code keyIntent} stays UNKNOWN. The wrapper
     * decodes the 0x40 flag into {@code isSprint()} = the raw {@code keySprint.isDown()}
     * intent (true for a stationary ctrl-holder, false for a double-tap-W sprinter);
     * the wire never treats it as a verdict source, only a block-hit corroborator. Any
     * wrapper/parse failure is swallowed by {@link #onPacketReceive} — feed nothing.
     */
    private void onPlayerInput(PacketReceiveEvent event) {
        UUID id = userId(event);
        if (id == null) {
            return;
        }
        WrapperPlayClientPlayerInput packet = new WrapperPlayClientPlayerInput(event);
        domains.domainFor(id).sprint().onKeyIntent(packet.isSprint());
    }

    private static GroundFsm.ViewSlice viewSlice(PlayerView view, Domain domain) {
        if (view == null) {
            return new GroundFsm.ViewSlice(Decay.JUMP_IMPULSE, -1, false, domain.lastYaw(),
                    Decay.DEFAULT_GRAVITY);
        }
        return new GroundFsm.ViewSlice(view.jumpImpulse(), view.jumpBoostAmplifier(),
                view.sprinting(), domain.lastYaw(), view.gravity());
    }

    private static UUID userId(PacketReceiveEvent event) {
        User user = event.getUser();
        return user == null ? null : user.getUUID();
    }
}

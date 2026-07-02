package me.vexmc.mental.v5.rim;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.wire.GroundFsm;
import me.vexmc.mental.kernel.wire.SprintWire;

/**
 * The D1 connection domain (spec §2): per-player {@link SprintWire} +
 * {@link GroundFsm}, keyed by UUID, each owned by that connection's netty read
 * thread. The map is concurrent because domains are created/forgotten across
 * threads, but a single {@link Domain}'s FSM/wire state is mutated only by its
 * own connection thread — thread-safety is ownership.
 *
 * <p>Domains are created lazily on the first Play packet a connection sends (the
 * UUID is stable only post-login, and the rim is Play-only anyway) and forgotten
 * on quit through the session service's forget hook.</p>
 */
public final class ConnectionDomains {

    /** One connection's arrival-order sprint + movement FSM, plus its last-seen yaw. */
    public static final class Domain {

        private final SprintWire sprint;
        private final GroundFsm ground;
        private float lastYaw;

        Domain(TickClock clock) {
            this.sprint = new SprintWire(clock);
            this.ground = new GroundFsm(clock);
        }

        public SprintWire sprint() {
            return sprint;
        }

        public GroundFsm ground() {
            return ground;
        }

        /** The last rotation-bearing yaw, tracked for the sprint-jump facing push. */
        public float lastYaw() {
            return lastYaw;
        }

        public void lastYaw(float yaw) {
            this.lastYaw = yaw;
        }
    }

    private final ConcurrentHashMap<UUID, Domain> byId = new ConcurrentHashMap<>();
    private final TickClock clock;

    public ConnectionDomains(TickClock clock) {
        this.clock = clock;
    }

    /** The connection domain for {@code id}, created on first use. */
    public Domain domainFor(UUID id) {
        return byId.computeIfAbsent(id, key -> new Domain(clock));
    }

    /** Drop a disconnected player's domain. */
    public void forget(UUID id) {
        byId.remove(id);
    }
}

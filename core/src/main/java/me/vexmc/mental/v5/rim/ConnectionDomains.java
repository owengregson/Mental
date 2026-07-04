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
 * threads.
 *
 * <p>The {@link GroundFsm} is single-writer by ownership — only its connection
 * thread mutates it. The {@link SprintWire} is the ONE exception: besides its
 * connection thread (packet START/STOP, reconcile, verdict reads) it is written
 * by exactly two other sanctioned threads — {@code KnockbackUnit} on the VICTIM's
 * region thread (the post-hit {@code onServerClear}) and {@code SwordBlockingUnit}
 * on the ATTACKER's thread (the block-hit re-arm + {@code clientSprinting} read).
 * That is licensed because {@code SprintWire} holds its whole state in one
 * immutable snapshot swapped by CAS ({@code AtomicReference}), so every
 * cross-thread read sees a coherent atomic value and each write happens-before the
 * next read — no torn mix, no lost update. Do not add a fourth writer without
 * preserving that atomicity.</p>
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

    /**
     * Whether {@code id} has a live connection domain — i.e. its ground/sprint
     * FSMs are being fed by real inbound packets. Packetless players (synthetic
     * test players, in-process bots) never create one, so the session's
     * tick-sampler serves their ledger ground transitions instead of the FSM.
     */
    public boolean has(UUID id) {
        return byId.containsKey(id);
    }

    /** Drop a disconnected player's domain. */
    public void forget(UUID id) {
        byId.remove(id);
    }
}

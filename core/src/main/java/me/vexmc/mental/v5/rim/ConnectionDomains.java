package me.vexmc.mental.v5.rim;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.wire.GroundFsm;
import me.vexmc.mental.kernel.wire.ResetModelWire;
import me.vexmc.mental.kernel.wire.SprintWire;

/**
 * The D1 connection domain (spec §2): per-player {@link SprintWire} +
 * {@link GroundFsm}, keyed by UUID, each owned by that connection's netty read
 * thread. The map is concurrent because domains are created/forgotten across
 * threads.
 *
 * <p>The {@link GroundFsm} is single-writer by ownership — only its connection
 * thread mutates it. The {@link SprintWire} is the ONE exception: besides its
 * connection thread (packet START/STOP, {@code onBlockReleased}, reconcile,
 * verdict reads) it is written by two other sanctioned writers — {@code
 * KnockbackUnit} on the VICTIM's region thread (the post-hit {@code onServerClear})
 * and {@code SwordBlockingUnit} on the ATTACKER's region thread (the block-hit
 * re-arm {@code onBlockSprintReset} + {@code clientSprinting} read; its own
 * RELEASE_USE_ITEM {@code onBlockReleased} runs on the ATTACKER's netty thread,
 * i.e. the connection thread above). That is licensed because {@code SprintWire}
 * holds its whole state in one immutable snapshot swapped by CAS ({@code
 * AtomicReference}), so every cross-thread read sees a coherent atomic value and
 * each write happens-before the next read — no torn mix, no lost update. Do not add
 * a further writer without preserving that atomicity.</p>
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
        private final ResetModelWire resetModel;
        // Volatile: written by this connection's own netty thread (the rim yaw tap)
        // and read cross-thread — the attacker's netty thread at hit-plan time (the
        // hurt-yaw tilt) and, with the pocket-servo precision round, the dynamic
        // target's victim-facing fallback. A single 32-bit value, so volatility is a
        // coherent atomic read (the seams doc's flagged fix — never repeat the plain
        // field for anything wider; multi-bit state must ride an AtomicReference/Long
        // snapshot per the ConnectionDomains licensing rule).
        private volatile float lastYaw;

        Domain(TickClock clock) {
            this.sprint = new SprintWire(clock);
            this.ground = new GroundFsm(clock);
            this.resetModel = new ResetModelWire(clock);
        }

        public SprintWire sprint() {
            return sprint;
        }

        public GroundFsm ground() {
            return ground;
        }

        /** The attacker's sprint-reset model — the dynamic-chase phase/technique signal (D1). */
        public ResetModelWire resetModel() {
            return resetModel;
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

    /**
     * The connection domain for {@code id}, created on first use. ONLY the packet
     * rim ({@link PacketTap}) may call this: a domain must be born of a real
     * inbound packet, never as a side effect of a read. Any seam that merely
     * OBSERVES a domain (the hurt-yaw tilt, the attacker's facing yaw, the sprint
     * verdict) must use {@link #peek} instead — see its contract.
     */
    public Domain domainFor(UUID id) {
        return byId.computeIfAbsent(id, key -> new Domain(clock));
    }

    /**
     * The connection domain for {@code id} if one already exists, else {@code
     * null} — a NON-creating lookup. Reads that only observe a domain MUST use
     * this rather than {@link #domainFor}: creating a domain as a side effect of
     * a read makes a packetless player (synthetic test player, in-process bot)
     * masquerade as connected, which permanently stands the session ground
     * sampler down ({@link #has}-keyed) and free-falls its {@code MotionLedger} —
     * the 2.4.4 domain-poisoning zero-vertical bug. Callers fall back to a neutral
     * value (yaw 0 / the published-view sprint) when this returns {@code null}.
     */
    public Domain peek(UUID id) {
        return byId.get(id);
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

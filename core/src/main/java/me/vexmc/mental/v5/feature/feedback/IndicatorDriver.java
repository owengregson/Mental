package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import me.vexmc.mental.kernel.fx.IndicatorBallistics;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import org.bukkit.entity.Player;

/**
 * One attacker's in-flight indicators, driven by a lazily-started 1-tick
 * {@code repeatOn} task on the attacker's region. Each tick, every live
 * indicator takes one kernel ballistics step and ships one relative move; a
 * landed or expired indicator ships its destroy and is forgotten. The task
 * cancels itself the moment the list empties, so an attacker who stops
 * fighting costs nothing.
 *
 * <p><b>Zero entity/world reads in the tick body</b>: the ground plane was
 * frozen at spawn time (the listener's one region-legal block scan) and the
 * client is reached through the stored PacketEvents {@link User} — the tick is
 * kernel math plus channel writes, nothing else. Every write is individually
 * catch-wrapped (the {@code BurstSender} posture) so one mid-reconfiguration
 * failure never kills the driver or strands the other indicators.</p>
 *
 * <p>Writers race by design — {@code add} runs on the victim's region thread
 * while the tick runs on the attacker's — so the live list and the task handle
 * are guarded by the driver monitor; packets are built under the lock and sent
 * outside it. A retired attacker (quit, or Paper's transient invalidity) gets
 * its stands destroyed best-effort — writes to a closed channel land in the
 * catch — and the driver stays reusable: the next hit re-arms the task.</p>
 */
final class IndicatorDriver {

    /** One in-flight indicator: kernel state, the frozen ground plane, remaining life. */
    private static final class Live {
        final int entityId;
        final double groundY;
        IndicatorBallistics.State state;
        int ticksLeft;

        Live(int entityId, IndicatorBallistics.State state, double groundY, int ticksLeft) {
            this.entityId = entityId;
            this.state = state;
            this.groundY = groundY;
            this.ticksLeft = ticksLeft;
        }
    }

    private final Scheduling scheduling;
    private final Player attacker;
    private final User user;
    private final IndicatorBallistics.Params params;

    private final List<Live> live = new ArrayList<>(); // guarded by this
    private TaskHandle task; // guarded by this
    private boolean closed; // guarded by this

    IndicatorDriver(Scheduling scheduling, Player attacker, User user, IndicatorBallistics.Params params) {
        this.scheduling = scheduling;
        this.attacker = attacker;
        this.user = user;
        this.params = params;
    }

    /** Adopts one just-spawned indicator and lazily arms the 1-tick drive task. */
    void add(int entityId, IndicatorBallistics.State launch, double groundY, int lifetimeTicks) {
        synchronized (this) {
            if (closed) {
                // The scope closed between the listener's map read and this
                // hand-off: unrender the just-spawned stand rather than adopt it.
                send(IndicatorStandPackets.destroy(entityId));
                flush();
                return;
            }
            live.add(new Live(entityId, launch, groundY, lifetimeTicks));
            if (task == null) {
                task = scheduling.repeatOn(attacker, 1L, 1L, this::tick, this::retire);
            }
        }
    }

    private void tick() {
        List<PacketWrapper<?>> sends;
        synchronized (this) {
            sends = new ArrayList<>(live.size());
            Iterator<Live> indicators = live.iterator();
            while (indicators.hasNext()) {
                Live indicator = indicators.next();
                IndicatorBallistics.State from = indicator.state;
                IndicatorBallistics.State to = IndicatorBallistics.step(from, params);
                indicator.state = to;
                indicator.ticksLeft--;
                if (IndicatorBallistics.landed(to, indicator.groundY) || indicator.ticksLeft <= 0) {
                    sends.add(IndicatorStandPackets.destroy(indicator.entityId));
                    indicators.remove();
                } else {
                    sends.add(IndicatorStandPackets.move(indicator.entityId, from, to));
                }
            }
            if (live.isEmpty() && task != null) {
                task.cancel();
                task = null;
            }
        }
        for (PacketWrapper<?> packet : sends) {
            send(packet);
        }
        flush();
    }

    /**
     * The attacker retired under the task (quit, or a transient invalidity such
     * as death). The repeating task has already cancelled itself per the
     * Scheduling contract; destroy best-effort — a still-rendering client (dead,
     * awaiting respawn) unrenders its stands, a closed channel lands in the
     * catch — and stay reusable for the next hit.
     */
    private void retire() {
        destroyAll(false);
    }

    /** Scope teardown / session forget: destroys every stand, cancels, and refuses future adds. */
    void close() {
        destroyAll(true);
    }

    private void destroyAll(boolean permanent) {
        List<PacketWrapper<?>> destroys;
        synchronized (this) {
            if (permanent) {
                closed = true;
            }
            destroys = new ArrayList<>(live.size());
            for (Live indicator : live) {
                destroys.add(IndicatorStandPackets.destroy(indicator.entityId));
            }
            live.clear();
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
        for (PacketWrapper<?> packet : destroys) {
            send(packet);
        }
        if (!destroys.isEmpty()) {
            flush();
        }
    }

    private void send(PacketWrapper<?> packet) {
        try {
            user.writePacketSilently(packet);
        } catch (Throwable reconfiguring) {
            // One failed write never kills the driver (the BurstSender posture).
        }
    }

    private void flush() {
        try {
            user.flushPackets();
        } catch (Throwable reconfiguring) {
            // A missed cosmetic beats a surfaced exception on the send path.
        }
    }
}

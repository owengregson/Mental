package me.vexmc.mental.module.knockback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins that the packet-fed ground bookkeeping never reads the region clock on
 * the netty thread.
 *
 * <p>{@code GroundTransitionWatcher.onClientMovement} runs on the victim's
 * netty thread (the {@code GroundPacketTap} feed) and tick-stamps each landing
 * or liftoff. {@code Bukkit.getCurrentTick()} routes to
 * {@code RegionizedServer.getCurrentTick()} on Folia, which throws
 * {@code IllegalStateException("No currently ticking region")} off a region
 * thread (verified by javap on the 1.21.11 Folia server jar) — and the netty
 * thread is never a region thread. The throw was swallowed by
 * {@code GroundPacketTap}'s catch-all, silently dropping every real client's
 * liftoff/landing from the {@link VictimMotion} ledger, which then froze
 * grounded and shipped floaty grounded re-stamps. Folia exposes no region tick
 * a netty thread may read, so the packet feed there uses the inclusive
 * {@link VictimMotion#NO_TICK} view (the same the boundary exclusion gives
 * packetless players); the sub-tick attack-ordering refinement is unavailable
 * off-region regardless.</p>
 */
class GroundTransitionWatcherTickTest {

    @Test
    void foliaPacketTickNeverReadsTheRegionClock() {
        int tick = GroundTransitionWatcher.packetMovementTick(
                true,
                () -> {
                    throw new AssertionError(
                            "Bukkit.getCurrentTick() must not be called on the Folia netty thread");
                });
        assertEquals(VictimMotion.NO_TICK, tick);
    }

    @Test
    void paperPacketTickUsesTheServerTick() {
        assertEquals(123, GroundTransitionWatcher.packetMovementTick(false, () -> 123));
    }
}

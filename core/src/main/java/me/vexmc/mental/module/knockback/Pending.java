package me.vexmc.mental.module.knockback;

import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One queued knockback awaiting its victim's velocity event. A null
 * {@code vector} means "suppress the knockback" (the legacy resistance roll
 * succeeded). {@code preDelivered} is the delivery-decayed velocity the client
 * already received on the wire (the netty pre-send); {@code wireDelivered} marks
 * that a packet actually carried it (so the duplicate outbound is suppressed),
 * versus a connectionless pinned knock that only adopts the era-moment values.
 * {@code groundedAtSubmit} is the launch state captured at submit — the era's
 * pre-move friction state. Held in a per-victim FIFO by {@link PendingStore}.
 */
record Pending(
        @Nullable KnockbackVector vector,
        @Nullable KnockbackVector preDelivered,
        boolean wireDelivered,
        @Nullable LivingEntity attacker,
        @NotNull KnockbackPipeline.Cause cause,
        boolean groundedAtSubmit,
        long stampNanos) {

    boolean expired(long nowNanos, long expiryNanos) {
        return nowNanos - stampNanos > expiryNanos;
    }
}

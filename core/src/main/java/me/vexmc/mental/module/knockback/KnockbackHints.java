package me.vexmc.mental.module.knockback;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One-shot latency-compensation hints, consumed at most once per hit.
 * Implemented by the compensation module; {@link #NONE} when it is offline.
 */
@FunctionalInterface
public interface KnockbackHints {

    KnockbackHints NONE = victimId -> null;

    /** The client-expected victim vertical velocity for this hit, or null. */
    @Nullable Double takeYOverride(@NotNull UUID victimId);
}

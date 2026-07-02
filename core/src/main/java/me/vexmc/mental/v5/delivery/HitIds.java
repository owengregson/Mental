package me.vexmc.mental.v5.delivery;

import java.util.concurrent.atomic.AtomicLong;
import me.vexmc.mental.kernel.model.HitId;

/**
 * The single global {@link HitId} counter (spec §3.1). One instance is shared by
 * every minting site — the fast path, the synthetic-source features, and the
 * {@code DamageRouter}'s Vanilla mint — so no two live transactions can collide
 * on an id (the desk withdraws and resolves by exact id).
 */
public final class HitIds {

    private final AtomicLong next = new AtomicLong();

    public HitId next() {
        return new HitId(next.incrementAndGet());
    }
}

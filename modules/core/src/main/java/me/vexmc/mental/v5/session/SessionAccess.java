package me.vexmc.mental.v5.session;

import java.util.UUID;
import me.vexmc.mental.kernel.model.LedgerEvent;
import me.vexmc.mental.kernel.model.PlayerView;

/**
 * The read/enqueue seam the packet rim sees of the session domain — the netty
 * realm's ONLY window onto D2 (spec §2). It exposes no mutating operation on
 * live entities or session internals: the rim may read the last published
 * {@link PlayerView} and drop an immutable {@link LedgerEvent} onto a victim's
 * MPSC inbox, nothing more. Keeping it an interface both certifies that
 * observation-only contract by type and lets the rim be unit-tested without the
 * whole {@link SessionService}.
 */
public interface SessionAccess {

    /** The last published view for {@code id}, or null before its first tick. */
    PlayerView viewOf(UUID id);

    /** Enqueue a D1 ledger event onto the victim's inbox (netty → owning thread). */
    void enqueue(UUID id, LedgerEvent event);
}

package me.vexmc.mental.v5.feature.delivery;

import java.util.concurrent.atomic.AtomicBoolean;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * In-order sprint reads for the fast path (the retired {@code WtapRegistrationModule}
 * on the v5 seams). The connection {@code InputLedger} is always maintained by the
 * packet tap; this feature only gates whether the fast path <em>consults</em> it.
 * When on (the default), registration reads the attacker's START/STOP packets
 * replayed in arrival order — so a w-tap registers however fast the tap, and an
 * s-tap's release denies the bonus, exactly as the era queue did. When off, the
 * fast path falls back to the tick-frozen published sprint flag (byte-identical to
 * the pre-module pipeline); synthetic players who send no packets fall back either
 * way.
 *
 * <p>Since 2.5.1 the toggle also flips sprint-knock SEMANTICS, not just read
 * timing: the wire enforces the spend latch — one engagement, one sprint knock,
 * re-armed only by a client re-gesture — while the published-flag fallback has no
 * consume to enforce, so every held-sprint hit carries the bonus there. The
 * fallback's behavior is deliberate (a view cannot express an engagement), not a
 * regression; packetless synthetic players live on it by construction.</p>
 */
public final class WtapRegistrationUnit implements FeatureUnit {

    private final AtomicBoolean consultWire;

    public WtapRegistrationUnit(AtomicBoolean consultWire) {
        this.consultWire = consultWire;
    }

    @Override
    public Feature descriptor() {
        return Feature.WTAP_REGISTRATION;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Enable-on-assemble / disable-on-close through the task seam: the fast
        // path reads the shared flag; no listener, packet, or timer of its own.
        scope.task(() -> {
            consultWire.set(true);
            return () -> consultWire.set(false);
        });
    }
}

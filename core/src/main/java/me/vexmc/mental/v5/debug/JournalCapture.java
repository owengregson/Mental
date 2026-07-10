package me.vexmc.mental.v5.debug;

import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Supplier;
import me.vexmc.mental.kernel.delivery.JournalObserver;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitGeometry;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.platform.debug.DebugLog;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;

/**
 * The F9 delivery-journal capture: a {@link JournalObserver} that formats one
 * greppable line per journaled hit — verdict, pre-send disposition, resolve
 * outcome, shipped h/v, engine geometry, profile, enabled families — into the
 * {@code JOURNAL} debug channel. The three-round weak-KB investigation had to be
 * reconstructed from ad-hoc probes; this makes the discriminating measurement a
 * permanent operator capability.
 *
 * <p>Zero-touch: the observer is invoked on every append, but off-channel it is
 * one {@code active()} volatile read and an early return — the formatter (and its
 * {@link Snapshot} read) run only when the channel is on. The desk stays the sole
 * journal writer; this only reads the entry it just wrote.</p>
 */
public final class JournalCapture implements JournalObserver {

    private final DebugLog.Scoped debug;
    private final Supplier<Snapshot> snapshot;

    public JournalCapture(DebugLog.Scoped debug, Supplier<Snapshot> snapshot) {
        this.debug = debug;
        this.snapshot = snapshot;
    }

    @Override
    public void journaled(HitContext context, JournalEntry entry) {
        // Zero-touch: channel off ⇒ one volatile read, nothing else.
        if (!debug.active()) {
            return;
        }
        debug.log(() -> format(context, entry, snapshot.get()));
    }

    /** The observer path: compute the enabled-families CSV from the live snapshot, then format. */
    static String format(HitContext context, JournalEntry entry, Snapshot snapshot) {
        return format(context, entry, familiesOf(snapshot));
    }

    /** The enabled feature families at format time (capture-time truth), or {@code -} when none. */
    static String familiesOf(Snapshot snapshot) {
        StringJoiner joiner = new StringJoiner(",");
        for (Feature feature : Feature.values()) {
            if (snapshot.enabled(feature)) {
                joiner.add(feature.name());
            }
        }
        return joiner.length() == 0 ? "-" : joiner.toString();
    }

    /**
     * Package-private, pure and families-injected so the exact line is unit-pinned
     * without a live plugin. One line, {@link Locale#ROOT}, each token
     * space-separated with {@code -} for absent so every line greps uniformly.
     */
    static String format(HitContext context, JournalEntry entry, String families) {
        JournalEntry.Capture capture = entry.capture();
        String out = capture == null ? "-" : orDash(capture.resolution());
        String presend = capture == null ? "-" : (capture.presend() == null ? "none" : capture.presend());
        String reason = orDash(entry.suppressReason());
        String ship = shipToken(entry.shipped());
        String sprint = capture == null ? "-" : tf(capture.sprinting());
        String fresh = (capture == null || capture.sprintFresh() == null) ? "-" : tf(capture.sprintFresh());
        String geom = geomToken(capture);
        String profile = capture == null ? "-" : orDash(capture.profile());
        String tick = entry.at().known() ? Integer.toString(entry.at().value()) : "-";
        return String.format(Locale.ROOT,
                "hit=%d src=%s out=%s presend=%s reason=%s ship=%s wire=%s sprint=%s fresh=%s"
                        + " pace=%.2f combo=%.2f geom=%s profile=%s families=%s"
                        + " attacker=%s victim=%s tick=%s",
                entry.id().value(), srcToken(entry.source()), out, presend, reason, ship,
                tf(entry.wireCarried()), sprint, fresh, entry.paceFactor(), entry.comboFactor(),
                geom, profile, families, uuid8(context.attackerId()), uuid8(context.victimId()), tick);
    }

    private static String srcToken(HitSource source) {
        if (source instanceof HitSource.Vanilla vanilla) {
            return "vanilla(" + vanilla.damageCause() + ")";
        }
        return source.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static String shipToken(KnockbackVector shipped) {
        if (shipped == null) {
            return "-";
        }
        double h = Math.hypot(shipped.x(), shipped.z());
        return String.format(Locale.ROOT, "h=%.3f v=%.3f", h, shipped.y());
    }

    private static String geomToken(JournalEntry.Capture capture) {
        if (capture == null || capture.geometry() == null) {
            return "-";
        }
        HitGeometry g = capture.geometry();
        return String.format(Locale.ROOT, "a(%.2f,%.2f yaw %.1f) v(%.2f,%.2f)",
                g.attackerX(), g.attackerZ(), g.attackerYaw(), g.victimX(), g.victimZ());
    }

    /** First 8 chars of the UUID string (netty/Folia-safe — never a live getName()), or {@code -}. */
    private static String uuid8(UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }

    private static String orDash(String value) {
        return value == null ? "-" : value;
    }

    private static String tf(boolean value) {
        return value ? "t" : "f";
    }
}

package me.vexmc.mental.v5.debug;

import java.util.List;
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
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.wire.InputEvent;
import me.vexmc.mental.kernel.wire.InputLedger;
import me.vexmc.mental.platform.debug.DebugLog;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.rim.ConnectionDomains;

/**
 * The F9 delivery-journal capture: a {@link JournalObserver} that formats one
 * greppable line per journaled hit — verdict, pre-send disposition, resolve
 * outcome, shipped h/v, engine geometry, profile, enabled families, and (2.6.0)
 * the attacker's ledger evidence: {@code resetseq=} (the gesture sequence the
 * verdict peeked), {@code trail=} (the last ledger events with tick offsets
 * relative to the hit — {@code sprint=f trail=STOP-2,START+0} reads "the START
 * trailed the attack inside its own tick") and {@code note=} ({@code
 * start-trailed} names exactly that era-plain case; {@code starved} is the
 * dead-feed alarm) — into the {@code JOURNAL} debug channel. The three-round
 * weak-KB investigation had to be reconstructed from ad-hoc probes; this makes
 * the discriminating measurement a permanent operator capability, and the 2.6.0
 * tokens make every sprint verdict self-explaining.
 *
 * <p>Zero-touch: the observer is invoked on every append, but off-channel it is
 * one {@code active()} volatile read and an early return — the formatter, its
 * {@link Snapshot} read AND the cross-thread ledger trail snapshot run only
 * inside the {@code debug.log} supplier, i.e. only when the channel is on. The
 * desk stays the sole journal writer; this only reads the entry it just
 * wrote.</p>
 */
public final class JournalCapture implements JournalObserver {

    /** The trail token's depth — enough to show the gesture story around one hit. */
    private static final int TRAIL_EVENTS = 6;

    private final DebugLog.Scoped debug;
    private final Supplier<Snapshot> snapshot;
    private final ConnectionDomains domains;

    public JournalCapture(DebugLog.Scoped debug, Supplier<Snapshot> snapshot,
                          ConnectionDomains domains) {
        this.debug = debug;
        this.snapshot = snapshot;
        this.domains = domains;
    }

    @Override
    public void journaled(HitContext context, JournalEntry entry) {
        // Zero-touch: channel off ⇒ one volatile read, nothing else.
        if (!debug.active()) {
            return;
        }
        debug.log(() -> format(context, entry, snapshot.get(), ledgerOf(context)));
    }

    /** The attacker's ledger, or null (no domain / packetless / unknown attacker). */
    private InputLedger ledgerOf(HitContext context) {
        if (context.attackerId() == null) {
            return null;
        }
        ConnectionDomains.Domain domain = domains.peek(context.attackerId());
        return domain == null ? null : domain.sprint();
    }

    /** The observer path: enabled-families CSV from the live snapshot + the ledger evidence. */
    static String format(HitContext context, JournalEntry entry, Snapshot snapshot,
                         InputLedger ledger) {
        List<InputEvent> trail = ledger == null ? List.of() : ledger.trail();
        return format(context, entry, familiesOf(snapshot),
                trailToken(trail, entry.at()),
                noteToken(trail, entry.at(), ledger != null && ledger.starved()));
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

    /** The pre-2.6.0 arity — trail/note absent (the existing pins' shape, kept exact). */
    static String format(HitContext context, JournalEntry entry, String families) {
        return format(context, entry, families, "-", "-");
    }

    /**
     * Package-private, pure and families/trail/note-injected so the exact line is
     * unit-pinned without a live plugin. One line, {@link Locale#ROOT}, each token
     * space-separated with {@code -} for absent so every line greps uniformly.
     */
    static String format(HitContext context, JournalEntry entry, String families,
                         String trail, String note) {
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
        SprintVerdict verdict = context.sprint();
        String resetseq = (verdict == null || !verdict.fromWire()) ? "-"
                : Long.toString(verdict.wireSeq());
        return String.format(Locale.ROOT,
                "hit=%d src=%s out=%s presend=%s reason=%s ship=%s wire=%s sprint=%s fresh=%s"
                        + " pace=%.2f combo=%.2f geom=%s profile=%s families=%s"
                        + " attacker=%s victim=%s tick=%s resetseq=%s trail=%s note=%s",
                entry.id().value(), srcToken(entry.source()), out, presend, reason, ship,
                tf(entry.wireCarried()), sprint, fresh, entry.paceFactor(), entry.comboFactor(),
                geom, profile, families, uuid8(context.attackerId()), uuid8(context.victimId()), tick,
                resetseq, trail, note);
    }

    /**
     * The last {@value #TRAIL_EVENTS} ledger events as {@code KIND±Δtick} relative
     * to the journal entry's tick ({@code STOP-2,START+0,ATTACK+0,CONSUME+1}),
     * oldest first — the gesture story around this hit. {@code -} when no ledger
     * or an empty ring; an unknown tick renders {@code ?} (never breaks the grep
     * shape). Pure over the snapshot copy so the exact token is unit-pinned.
     */
    static String trailToken(List<InputEvent> trail, TickStamp at) {
        if (trail.isEmpty()) {
            return "-";
        }
        StringJoiner joiner = new StringJoiner(",");
        int from = Math.max(0, trail.size() - TRAIL_EVENTS);
        for (int i = from; i < trail.size(); i++) {
            InputEvent event = trail.get(i);
            String offset = (event.tick().known() && at.known())
                    ? String.format(Locale.ROOT, "%+d", event.tick().value() - at.value())
                    : "?";
            joiner.add(event.kind().name() + offset);
        }
        return joiner.toString();
    }

    /**
     * The verdict's explanatory note: {@code starved} (a consume happened on a
     * ledger that never saw a START — the dead-feed alarm) outranks
     * {@code start-trailed} (a SPRINT_START arrived AFTER this hit's ATTACK inside
     * the same CLIENT tick — the era ATTACK-first arrival order shipping the tap's
     * own click plain; the START arms the NEXT hit), else {@code -}.
     *
     * <p>The anchor is the last ATTACK event at or before the entry's tick, and
     * the START is compared against THAT ATTACK's own tick — never against the
     * entry's: the journal entry stamps the DELIVERY tick, one after the parse
     * tick the ledger stamped into the ATTACK event, so an {@code at}-equality
     * anchor made the note structurally unreachable (found live by the 2.6.0
     * wire suite: every real line renders {@code ATTACK-1}). Pure for the pin.</p>
     */
    static String noteToken(List<InputEvent> trail, TickStamp at, boolean starved) {
        if (starved) {
            return "starved";
        }
        int lastAttack = -1;
        for (int i = trail.size() - 1; i >= 0; i--) {
            InputEvent event = trail.get(i);
            if (event.kind() == InputEvent.Kind.ATTACK
                    && (!at.known() || !event.tick().known() || event.tick().value() <= at.value())) {
                lastAttack = i;
                break;
            }
        }
        if (lastAttack < 0) {
            return "-";
        }
        TickStamp attackTick = trail.get(lastAttack).tick();
        for (int i = lastAttack + 1; i < trail.size(); i++) {
            InputEvent event = trail.get(i);
            if (event.kind() == InputEvent.Kind.SPRINT_START
                    && event.tick().known() && attackTick.known()
                    && event.tick().value() == attackTick.value()) {
                return "start-trailed";
            }
        }
        return "-";
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

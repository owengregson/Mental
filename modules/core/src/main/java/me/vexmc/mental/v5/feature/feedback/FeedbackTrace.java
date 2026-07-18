package me.vexmc.mental.v5.feature.feedback;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The FEEDBACK family's decision ring — the journal-pattern seam that makes
 * the modules matrix-testable at all: fake players carry no client and no
 * PacketEvents user, so nothing cosmetic is observable on the wire; suites
 * assert the DECISION (sounds resolved, suppression armed, indicator variant/
 * spawn/sendability) recorded here at the moment it is made, before any send.
 * Bounded and synchronized (writers are region threads); zero writes while
 * both modules are disabled — zero-touch holds.
 */
public final class FeedbackTrace {

    private static final int CAPACITY = 128;

    /** One decision. {@code decision} is an open string namespace (journal pattern). */
    public record Entry(String module, UUID attacker, UUID victim, String decision, String detail) {}

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public synchronized void record(Entry entry) {
        if (entries.size() == CAPACITY) {
            entries.removeFirst();
        }
        entries.addLast(entry);
    }

    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public synchronized void clear() {
        entries.clear();
    }
}

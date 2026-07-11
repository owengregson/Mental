package me.vexmc.mental.v5.feature.feedback;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Era-correct sound-name resolution (the {@code SweepCauses} posture, applied to
 * names instead of constants): PacketEvents maps KNOWN names to per-version ids,
 * but an absent sound's id lookup returns garbage below 1.19.3 (no inline-by-name
 * there), so absence must be resolved HERE, against the server version, before
 * anything reaches a wrapper. Built-in layers carry era fallbacks; an unknown
 * custom name pre-1.19.3 resolves to {@code ""} (skip, one warn at assemble).
 *
 * <p>Pure: the decision is made against an injected {@code (major, minor)} tuple
 * plus the {@code inlineByName} gate, so it needs no live server. A year-scheme
 * server (26.x) parses to {@code major == 26}, which exceeds every legacy floor's
 * major, so {@link #atLeast} answers true for all of them with no special case.</p>
 */
final class FeedbackSoundTable {

    /** A built-in name that exists only from {@code (major, minor)}, with its pre-floor fallback. */
    private record Floored(int major, int minor, String fallback) {}

    private static final Map<String, Floored> FLOORS = Map.of(
            "block.lodestone.break", new Floored(1, 16, "block.stone.break"),
            "entity.breeze.deflect", new Floored(1, 21, "item.shield.block"),
            "entity.glow_squid.hurt", new Floored(1, 17, "entity.squid.hurt"),
            "entity.glow_squid.death", new Floored(1, 17, "entity.squid.death"));

    /**
     * Names the presets/fallbacks can emit that are guaranteed present since 1.9,
     * so they survive even below 1.19.3 where an unknown custom name cannot. Every
     * {@link #FLOORS} fallback target lives here (a fallback must itself be sendable).
     */
    private static final Set<String> UNIVERSAL = Set.of(
            "entity.player.hurt",
            "entity.generic.hurt",
            "block.stone.break",
            "item.shield.block",
            "block.anvil.land",
            "entity.squid.hurt",
            "entity.squid.death");

    private final int major;
    private final int minor;
    private final boolean inlineByName; // 1.19.3+: sounds ship as inline holders

    FeedbackSoundTable(int major, int minor, boolean inlineByName) {
        this.major = major;
        this.minor = minor;
        this.inlineByName = inlineByName;
    }

    /** The name to send on this server, or {@code ""} when the sound cannot ship here. */
    String resolve(String raw) {
        String name = normalize(raw);
        Floored floor = FLOORS.get(name);
        if (floor != null && !atLeast(floor.major(), floor.minor())) {
            return floor.fallback();
        }
        if (floor == null && !inlineByName && !UNIVERSAL.contains(name)) {
            // Pre-1.19.3 an unknown name has no trustworthy id — skip it.
            return "";
        }
        return name;
    }

    /** {@code BLOCK_LODESTONE_BREAK} → {@code block.lodestone.break}; strips a {@code minecraft:} prefix. */
    static String normalize(String raw) {
        String name = raw.trim();
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        if (name.indexOf('.') < 0 && name.indexOf('_') >= 0) {
            name = name.replace('_', '.');
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private boolean atLeast(int wantMajor, int wantMinor) {
        return major > wantMajor || (major == wantMajor && minor >= wantMinor);
    }
}

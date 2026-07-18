package me.vexmc.mental.kernel.math;

/**
 * The Combat Test 8c shield math (spec §2.6, code-confirmed against {@code
 * LivingEntity.isDamageSourceBlocked}/{@code blockedByShield} and {@code
 * ShieldItem}). Three pure pieces the core shell drives from Bukkit events and
 * the Cooldowns API:
 *
 * <ul>
 *   <li><b>Arc.</b> A hit is blocked when {@code dot(viewDir, attacker→victim)·π
 *       < −0.8726646} — the decompiled {@code −0.8726646f} (−50° in radians)
 *       widened to double. That narrows vanilla's 180° block to a ≈148° frontal
 *       cone.</li>
 *   <li><b>5-cap passthrough.</b> A melee hit blocks at most {@code 5.0}; the
 *       excess passes through (no {@code shield_strength} attribute exists — the
 *       cap is hardcoded). Projectiles/explosions are still fully blocked, and
 *       crits do not bypass — those are core policy, not this math.</li>
 *   <li><b>Axe disable.</b> An axe hit always disables the shield for {@code
 *       1.6s + 0.5s·Cleaving} = {@code 32 + 10·level} ticks.</li>
 * </ul>
 */
public final class Ct8cShieldMath {

    /**
     * The blocking-arc dot·π limit: {@code (double) −0.8726646f} = −50° in
     * radians. A {@code dot(viewDir, attacker→victim)·π} strictly below this is
     * inside the ≈148° frontal cone and blocked (spec §2.6).
     */
    public static final double ARC_DOT_LIMIT = -0.8726646304130554;

    /** The hardcoded melee block cap — no {@code shield_strength} attribute is registered (spec §2.6). */
    public static final double MELEE_CAP = 5.0;

    /** The base axe-disable duration in ticks (1.6s), before the per-Cleaving-level addition. */
    public static final int AXE_DISABLE_BASE_TICKS = 32;

    /** The per-Cleaving-level axe-disable addition in ticks (0.5s), consumed from {@code cleaving}. */
    public static final int AXE_DISABLE_PER_LEVEL_TICKS = 10;

    private Ct8cShieldMath() {}

    /**
     * Whether the hit falls inside the blocking arc: {@code dotViewToAttackerTimesPi
     * < ARC_DOT_LIMIT}. The caller supplies {@code dot(viewDir, attacker→victim)}
     * already multiplied by π; a head-on hit (dot ≈ −1 ⇒ dot·π ≈ −π) is blocked,
     * a 90° hit (dot·π = 0) is not (spec §2.6).
     */
    public static boolean withinArc(double dotViewToAttackerTimesPi) {
        return dotViewToAttackerTimesPi < ARC_DOT_LIMIT;
    }

    /** The portion of a melee hit the shield absorbs: {@code min(5.0, damage)}; the rest passes through (spec §2.6). */
    public static double blockedPortion(double damage) {
        return Math.min(MELEE_CAP, damage);
    }

    /** The axe-hit shield-disable duration: {@code 32 + 10·cleavingLevel} ticks (spec §2.6/§2.9). */
    public static int axeDisableTicks(int cleavingLevel) {
        return AXE_DISABLE_BASE_TICKS + AXE_DISABLE_PER_LEVEL_TICKS * cleavingLevel;
    }
}

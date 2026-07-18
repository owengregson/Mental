package me.vexmc.mental.platform;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cross-version "is the {@code naturalRegeneration} gamerule on" read.
 *
 * <p>The typed {@code org.bukkit.GameRule} class lands at <b>1.13</b>
 * (javap-verified absent on the 1.9.4 server jar). A direct
 * {@code world.getGameRuleValue(GameRule.NATURAL_REGENERATION)} therefore
 * {@code NoClassDefFoundError}s on 1.9.4–1.12.2 — and because it is a
 * {@code getstatic} of a constant on an absent class, the failure is STICKY: the
 * constant-pool entry stays failed and rethrows on every subsequent execution. In
 * a per-tick regen driver that spams the exact linkage signature the D-9 log gate
 * scans, every tick, while the boot report claims regen applies.</p>
 *
 * <p>The strategy is chosen ONCE by a class-load presence probe
 * ({@link Class#forName}), never a version parse, and cached. The typed
 * {@code GameRule} reference lives only inside {@link #typedRead(World)}, reached
 * exclusively when the probe found the class (1.13+), so the {@code getstatic} is
 * never executed — and the class never linked — on a pre-1.13 server. Below the
 * floor the read falls back to the deprecated String overload
 * {@code World#getGameRuleValue("naturalRegeneration")} — javap-verified present on
 * 1.9.4, reading a real registered rule (the {@code naturalRegeneration} key is an
 * {@code ldc} constant in {@code v1_9_R2}'s {@code GameRules}). The legacy read is
 * interpreted with the SAME null-is-off semantics as the typed
 * {@code Boolean.TRUE.equals(...)} path, so behaviour is byte-identical across the
 * boundary (era-exact no-op default: a server that turns natural regen off keeps
 * it off on every version).</p>
 *
 * <p>Deliberately a NON-{@link org.bukkit.event.Listener} platform seam: the
 * {@code GameRule} type appears in no descriptor of any registered listener class
 * (the 2.4.1 GAP-1 rule), and the owning regen units call {@link #isEnabled(World)}
 * at the body level through a cached predicate.</p>
 */
public final class NaturalRegen {

    /** The read strategy the boot probe selects — its posture is the boot report's ground truth. */
    enum Strategy {
        TYPED,
        LEGACY
    }

    private static final Strategy STRATEGY = strategyFor(typedGameRuleClassPresent());

    private NaturalRegen() {}

    /**
     * Whether natural regeneration is enabled in {@code world}. Typed
     * {@code GameRule} read on 1.13+, the String-overload read below — the same
     * boolean either way, resolved once at boot.
     */
    public static boolean isEnabled(@NotNull World world) {
        return STRATEGY == Strategy.TYPED ? typedRead(world) : legacyRead(world);
    }

    /** For the boot report: the era-truthful description of the gamerule-read path. */
    public static @NotNull String describe() {
        return STRATEGY == Strategy.TYPED
                ? "GameRule.NATURAL_REGENERATION (typed, 1.13+)"
                : "\"naturalRegeneration\" String gamerule (deprecated overload, pre-1.13)";
    }

    /* --------------------------- probe internals -------------------------- */

    /** The resolved strategy — package-visible so the probe decision is unit-pinnable. */
    static @NotNull Strategy strategy() {
        return STRATEGY;
    }

    /**
     * The probe DECISION: the typed path when {@code org.bukkit.GameRule} is
     * present (1.13+), the legacy String overload otherwise. Split out from the
     * {@link Class#forName} probe so the fallback is testable without the class
     * actually being absent on the test classpath.
     */
    static @NotNull Strategy strategyFor(boolean typedGameRuleClassPresent) {
        return typedGameRuleClassPresent ? Strategy.TYPED : Strategy.LEGACY;
    }

    /**
     * Interprets the deprecated String-overload result with the same null-is-off
     * semantics as the typed {@code Boolean.TRUE.equals(...)} path: only the
     * literal {@code "true"} enables regen, everything else (including {@code null}
     * or an empty/unknown value) reads off.
     */
    static boolean interpretLegacy(@Nullable String raw) {
        return Boolean.parseBoolean(raw);
    }

    private static boolean typedGameRuleClassPresent() {
        try {
            Class.forName("org.bukkit.GameRule");
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    /**
     * The modern typed read — reached ONLY when the probe found
     * {@code org.bukkit.GameRule}, so the {@code getstatic} never executes (and the
     * class never links) on a pre-1.13 server.
     */
    private static boolean typedRead(@NotNull World world) {
        return Boolean.TRUE.equals(world.getGameRuleValue(GameRule.NATURAL_REGENERATION));
    }

    /** The pre-1.13 read via the deprecated String overload — the only gamerule API 1.9.4–1.12.2 has. */
    @SuppressWarnings("deprecation")
    private static boolean legacyRead(@NotNull World world) {
        return interpretLegacy(world.getGameRuleValue("naturalRegeneration"));
    }
}

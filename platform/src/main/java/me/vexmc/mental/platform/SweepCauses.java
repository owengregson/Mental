package me.vexmc.mental.platform;

import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cross-version sweep-damage-cause capability.
 *
 * <p>{@code DamageCause.ENTITY_SWEEP_ATTACK} lands at 1.11 (javap-verified absent on the
 * 1.9.4/1.10.2 server jars). A direct constant reference is a {@code getstatic} whose
 * {@code NoSuchFieldError} is STICKY — the constant-pool entry stays failed and rethrows on every
 * subsequent execution, which the event bus swallows per-event ("Could not pass event … to
 * Mental") — the 2.4.1 GAP-2 finding. So the constant is resolved ONCE here via
 * {@link Enum#valueOf} in a try/catch, never a getstatic, and callers read the cached value.</p>
 *
 * <p>Below 1.11 vanilla sword sweep EXISTS but its splash damage arrives as plain
 * {@code ENTITY_ATTACK} with raw 1.0 — indistinguishable by cause, and a same-tick raw-1.0
 * heuristic is rejected as a zero-touch violation risk. The sweep-suppression features are
 * therefore documented no-ops there: their owning units skip listener registration at assemble
 * (zero per-event cost, zero errors) and print the degrade line once.</p>
 */
public final class SweepCauses {

    private static final @Nullable EntityDamageEvent.DamageCause SWEEP = resolve();

    private SweepCauses() {}

    /** True when {@code DamageCause.ENTITY_SWEEP_ATTACK} exists on this server (1.11+). */
    public static boolean present() {
        return SWEEP != null;
    }

    /** The cached sweep cause; {@code null} below 1.11 (callers gate on {@link #present()}). */
    public static @Nullable EntityDamageEvent.DamageCause sweepCause() {
        return SWEEP;
    }

    /** For the boot report: the era-truthful description of the sweep-cause state. */
    public static @NotNull String describe() {
        return SWEEP != null
                ? "ENTITY_SWEEP_ATTACK cause present (1.11+)"
                : "no ENTITY_SWEEP_ATTACK cause (lands 1.11) — sweep suppression is a documented no-op";
    }

    private static @Nullable EntityDamageEvent.DamageCause resolve() {
        try {
            return EntityDamageEvent.DamageCause.valueOf("ENTITY_SWEEP_ATTACK");
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }
}
